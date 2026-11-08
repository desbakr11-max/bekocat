package app.bekocat.engine.preview

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import app.bekocat.core.audio.AudioMixer
import app.bekocat.core.audio.Loudness
import app.bekocat.core.timeline.FramePlan
import app.bekocat.core.timeline.LayerSource
import app.bekocat.core.timeline.PlanItem
import app.bekocat.core.timeline.Project
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.RenderPlanner
import app.bekocat.engine.EngineAudio
import app.bekocat.engine.gl.EglCore
import app.bekocat.engine.render.Compositor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time preview: a GL thread renders FramePlans at the audio clock, an audio thread streams
 * the same mix used for export. Preview resolution adapts (proxy-like) to keep playback smooth;
 * the user can force full quality. Video always follows the audio clock (A/V sync).
 */
class PreviewEngine(private val context: Context) {

    interface Listener {
        fun onTime(seconds: Double) {}
        fun onPlaying(playing: Boolean) {}
        fun onStats(fps: Double, renderMs: Double, previewScale: Double) {}
        fun onError(message: String) {}
    }

    var listener: Listener? = null
    private val glThread = HandlerThread("bk-preview-gl").apply { start() }
    private val gl = Handler(glThread.looper)
    private var egl: EglCore? = null
    private var window: EGLSurface? = null
    private var pbuffer: EGLSurface? = null
    private var compositor: Compositor? = null
    private var surfaceW = 0; private var surfaceH = 0

    @Volatile private var project: Project? = null
    @Volatile var timeSec = 0.0; private set
    @Volatile var playing = false; private set
    @Volatile var quality = 0.5      // preview scale (0.25..1)
    @Volatile var adaptive = true
    @Volatile var loopRange: Pair<Double, Double>? = null
    private val renderPending = AtomicBoolean(false)

    val audio = EngineAudio(context)
    private var mixer: AudioMixer? = null
    private var track: AudioTrack? = null
    private var audioThread: Thread? = null
    private var playStartSec = 0.0
    private var playStartFrames = 0L
    private var loudnessGainDb = 0.0
    private var lastFrameNs = 0L
    private var fpsEma = 0.0

    init {
        gl.post {
            val e = EglCore()
            egl = e
            pbuffer = e.pbuffer()
            e.makeCurrent(pbuffer!!)
            compositor = Compositor(context)
        }
    }

    fun setSurface(surface: Surface?, w: Int, h: Int) = gl.post {
        val e = egl ?: return@post
        window?.let { e.releaseSurface(it) }
        window = surface?.let { e.windowSurface(it) }
        surfaceW = w; surfaceH = h
        e.makeCurrent(window ?: pbuffer!!)
        requestRender()
    }

    fun setProject(p: Project) {
        val prev = project
        project = p
        compositor?.assetUri = { id -> p.asset(id)?.uri }
        mixer = audio.mixer(p)
        if (prev == null || prev.sequence.tracks.flatMap { it.clips }.map { it.audio } != p.sequence.tracks.flatMap { it.clips }.map { it.audio }) {
            // Recompute master loudness normalisation for preview in the background.
            Thread {
                runCatching {
                    val m = audio.mixer(p)
                    val d = p.sequence.duration.toDouble().coerceAtMost(600.0)
                    val target = p.sequence.master.loudnessTargetLufs
                    loudnessGainDb = if (target != null && d > 0.5) Loudness.normalizationGainDb(m.mix(0.0, d, applyMaster = false), p.sequence.audioSampleRate.toDouble(), target) else 0.0
                }
            }.apply { priority = Thread.MIN_PRIORITY }.start()
        }
        requestRender()
    }

    fun seek(sec: Double) {
        val d = project?.sequence?.duration?.toDouble() ?: 0.0
        timeSec = sec.coerceIn(0.0, max(0.0, d))
        if (playing) { stopAudio(); startAudio() }
        listener?.onTime(timeSec)
        requestRender()
    }

    fun play() {
        val p = project ?: return
        if (playing) return
        if (timeSec >= p.sequence.duration.toDouble() - 0.01) timeSec = loopRange?.first ?: 0.0
        playing = true
        listener?.onPlaying(true)
        startAudio()
        gl.post { Choreographer.getInstance().postFrameCallback(frameCb) }
    }

    fun pause() {
        if (!playing) return
        playing = false
        stopAudio()
        listener?.onPlaying(false)
        requestRender()
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            val p = project ?: return
            val t = clock()
            val end = loopRange?.second ?: p.sequence.duration.toDouble()
            if (t >= end) {
                if (loopRange != null) { timeSec = loopRange!!.first; stopAudio(); startAudio() }
                else { timeSec = end; playing = false; stopAudio(); listener?.onPlaying(false); listener?.onTime(timeSec); renderNow(); return }
            } else timeSec = t
            renderNow()
            listener?.onTime(timeSec)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun clock(): Double {
        val tr = track ?: return timeSec
        val fs = project?.sequence?.audioSampleRate ?: 48000
        return playStartSec + (tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL) / fs.toDouble()
    }

    fun requestRender() {
        if (renderPending.getAndSet(true)) return
        gl.post { renderPending.set(false); if (!playing) renderNow() }
    }

    private fun scaledPlan(plan: FramePlan, s: Double): FramePlan = if (s >= 0.999) plan else scalePlan(plan, s)

    private fun scalePlan(p: FramePlan, s: Double): FramePlan = p.copy(
        width = max(16, (p.width * s).toInt() / 2 * 2), height = max(16, (p.height * s).toInt() / 2 * 2),
        items = p.items.map { scaleItem(it, s) },
    )

    private fun scaleItem(it: PlanItem, s: Double): PlanItem = when (it) {
        is PlanItem.Layer -> PlanItem.Layer(scaleLayer(it.layer, s))
        is PlanItem.Trans -> PlanItem.Trans(it.plan.copy(a = it.plan.a?.let { l -> scaleLayer(l, s) }, b = it.plan.b?.let { l -> scaleLayer(l, s) }))
    }

    private fun scaleLayer(l: app.bekocat.core.timeline.LayerPlan, s: Double): app.bekocat.core.timeline.LayerPlan =
        (l.source as? LayerSource.Nested)?.let { n -> l.copy(source = LayerSource.Nested(scalePlan(n.plan, s))) } ?: l

    private fun renderNow() {
        val e = egl ?: return; val c = compositor ?: return; val w = window ?: return
        val p = project ?: return
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            e.makeCurrent(w)
            val plan = RenderPlanner.plan(p, Rational.fromDouble(timeSec, 1_000_000))
            val fit = min(surfaceW.toDouble() / plan.width, surfaceH.toDouble() / plan.height).coerceAtMost(1.0)
            val scale = (if (playing && adaptive) quality else 1.0) * fit
            val img = c.render(scaledPlan(plan, scale.coerceIn(0.1, 1.0)), timeSec = timeSec)
            c.present(img, surfaceW, surfaceH)
            c.recycle(img)
            e.swap(w)
        } catch (ex: Throwable) {
            listener?.onError(ex.message ?: ex.javaClass.simpleName)
        }
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        if (lastFrameNs > 0) { val dt = (t0 - lastFrameNs) / 1e9; if (dt > 0) fpsEma = fpsEma * 0.9 + 0.1 / dt }
        lastFrameNs = t0
        if (playing && adaptive) {
            // Adapt preview resolution to hold ~30 fps.
            if (ms > 36 && quality > 0.25) quality = (quality * 0.85).coerceAtLeast(0.25)
            else if (ms < 18 && quality < 1.0) quality = (quality * 1.05).coerceAtMost(1.0)
        }
        listener?.onStats(fpsEma, ms, quality)
    }

    /** Renders a single frame to a bitmap (thumbnails, scopes, AI analysis). Runs on the GL thread. */
    fun snapshot(sec: Double, maxSide: Int = 640, cb: (android.graphics.Bitmap?) -> Unit) = gl.post {
        val c = compositor; val p = project; val e = egl
        if (c == null || p == null || e == null) { cb(null); return@post }
        runCatching {
            e.makeCurrent(pbuffer!!)
            val plan = RenderPlanner.plan(p, Rational.fromDouble(sec, 1_000_000))
            val s = (maxSide.toDouble() / max(plan.width, plan.height)).coerceAtMost(1.0)
            val img = c.render(scaledPlan(plan, s), timeSec = sec)
            val bmp = img.toBitmap(); c.recycle(img)
            window?.let { e.makeCurrent(it) }
            bmp
        }.onSuccess(cb).onFailure { cb(null) }
    }

    // ---------------- audio ----------------
    private fun startAudio() {
        val p = project ?: return
        val fs = p.sequence.audioSampleRate
        val minBuf = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(fs).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(max(minBuf, fs * 8 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = tr
        playStartSec = timeSec
        playStartFrames = 0
        val m = mixer ?: audio.mixer(p)
        val startAt = timeSec
        val gain = loudnessGainDb
        audioThread = Thread {
            var t = startAt
            val chunk = 0.1
            try {
                // Prime first chunk before starting playback so the clock starts with audio.
                tr.write(m.mix(t, chunk, applyMaster = true, loudnessGainDb = gain), 0, (chunk * fs).toInt() * 2, AudioTrack.WRITE_BLOCKING)
                t += chunk
                tr.play()
                while (playing && track === tr) {
                    val buf = m.mix(t, chunk, applyMaster = true, loudnessGainDb = gain)
                    tr.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                    t += chunk
                }
            } catch (_: Throwable) { }
        }.apply { name = "bk-audio"; priority = Thread.MAX_PRIORITY; start() }
    }

    private fun stopAudio() {
        val tr = track ?: return
        track = null
        runCatching { tr.pause(); tr.flush(); tr.stop() }
        audioThread?.join(300)
        runCatching { tr.release() }
    }

    fun release() {
        pause()
        gl.post {
            compositor?.release(); compositor = null
            egl?.let { e -> window?.let { e.releaseSurface(it) }; pbuffer?.let { e.releaseSurface(it) }; e.release() }
            egl = null
            glThread.quitSafely()
        }
    }
}
