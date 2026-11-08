package app.bekocat.engine.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.GLES30
import app.bekocat.core.audio.Limiter
import app.bekocat.core.audio.dbToGain
import app.bekocat.core.export.CaptionIO
import app.bekocat.core.export.GifEncoder
import app.bekocat.core.export.LoudnessMeter
import app.bekocat.core.export.Wav
import app.bekocat.core.timeline.FrameRate
import app.bekocat.core.timeline.Project
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.RenderPlanner
import app.bekocat.engine.EngineAudio
import app.bekocat.engine.gl.EglCore
import app.bekocat.engine.gl.Fbo
import app.bekocat.engine.gl.Quad
import app.bekocat.engine.render.Compositor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class VideoCodec(val mime: String, val label: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264"), HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC"), AV1("video/av01", "AV1")
}

enum class ExportKind { VIDEO, GIF, PNG_SEQUENCE, JPEG_SEQUENCE, WAV, M4A, STEMS, CAPTIONS_SRT, CAPTIONS_VTT, CAPTIONS_ASS }

/** How a sequence is placed when the export size differs from the sequence size. */
enum class Reframe { ADAPT, FIT, FILL }

data class ExportSettings(
    val kind: ExportKind = ExportKind.VIDEO,
    val width: Int,
    val height: Int,
    val frameRate: FrameRate,
    val codec: VideoCodec = VideoCodec.H264,
    val videoBitrate: Int = 16_000_000,
    val audioBitrate: Int = 192_000,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    val startSec: Double = 0.0,
    val endSec: Double? = null,
    val reframe: Reframe = Reframe.ADAPT,
    val transparent: Boolean = false,
    val keyframeIntervalSec: Int = 1,
    val gifFps: Int = 15,
    val wavBits: Int = 24,
)

data class ExportResult(val file: File, val mime: String, val verifiedDurationSec: Double, val tracks: Int, val width: Int, val height: Int, val notes: List<String>)

class ExportCancelled : Exception("cancelled")

/**
 * Offline exporter. Renders every frame through the same [Compositor] used by preview,
 * encodes with MediaCodec (H.264 / HEVC / AV1 when the device has an encoder), muxes AAC
 * audio from the same mixer with two-pass loudness normalisation, then re-opens the file
 * to verify duration and decodability before reporting success.
 */
class Exporter(private val context: Context) {

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }
    private fun check() { if (cancelled) throw ExportCancelled() }

    companion object {
        fun encoderSupports(codec: VideoCodec, w: Int, h: Int, fps: Double): Boolean {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            return list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(codec.mime, true) } &&
                    runCatching { info.getCapabilitiesForType(codec.mime).videoCapabilities.let { vc ->
                        // Offline export may run slower than real time, so the size is what must be supported;
                        // the rate limit only describes real-time throughput.
                        vc.areSizeAndRateSupported(w, h, fps) || vc.isSizeSupported(w, h) } }.getOrDefault(false)
            }
        }

        fun availableCodecs(w: Int, h: Int, fps: Double) = VideoCodec.entries.filter { encoderSupports(it, w, h, fps) }

        fun estimateBytes(s: ExportSettings, durationSec: Double): Long = ((s.videoBitrate + s.audioBitrate) / 8.0 * durationSec).toLong()
    }

    fun run(project: Project, s: ExportSettings, outDir: File, progress: (Double, String) -> Unit): ExportResult {
        cancelled = false
        outDir.mkdirs()
        val end = (s.endSec ?: project.sequence.duration.toDouble()).coerceAtMost(project.sequence.duration.toDouble())
        val start = s.startSec.coerceIn(0.0, end)
        val dur = end - start
        require(dur > 0) { "Nothing to export" }
        val base = project.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(40).ifBlank { "bekocat" } + "_" + System.currentTimeMillis()
        return when (s.kind) {
            ExportKind.VIDEO -> exportVideo(project, s, start, dur, File(outDir, "$base.mp4"), progress)
            ExportKind.GIF -> exportGif(project, s, start, dur, File(outDir, "$base.gif"), progress)
            ExportKind.PNG_SEQUENCE, ExportKind.JPEG_SEQUENCE -> exportSequence(project, s, start, dur, File(outDir, "$base.zip"), progress)
            ExportKind.WAV -> exportWav(project, s, start, dur, File(outDir, "$base.wav"), progress)
            ExportKind.M4A -> exportVideo(project, s, start, dur, File(outDir, "$base.m4a"), progress, audioOnly = true)
            ExportKind.STEMS -> exportStems(project, s, start, dur, File(outDir, "${base}_stems.zip"), progress)
            ExportKind.CAPTIONS_SRT, ExportKind.CAPTIONS_VTT, ExportKind.CAPTIONS_ASS -> exportCaptions(project, s, File(outDir, base + when (s.kind) { ExportKind.CAPTIONS_SRT -> ".srt"; ExportKind.CAPTIONS_VTT -> ".vtt"; else -> ".ass" }))
        }
    }

    // ---------------- rendering helpers ----------------
    private class Gpu(context: Context) {
        val egl = EglCore()
        val pbuffer = egl.pbuffer()
        init { egl.makeCurrent(pbuffer) }
        val comp = Compositor(context)
        fun release() { comp.release(); egl.releaseSurface(pbuffer); egl.release() }
    }

    private fun projectFor(p: Project, s: ExportSettings): Project =
        if (s.reframe == Reframe.ADAPT && (s.width != p.sequence.width || s.height != p.sequence.height))
            p.copy(sequence = p.sequence.copy(width = s.width, height = s.height)) else p

    /** Renders the frame at [t] into an FBO of the export size. */
    private fun renderFrame(gpu: Gpu, p: Project, s: ExportSettings, t: Double): Fbo {
        val plan = RenderPlanner.plan(p, Rational.fromDouble(t, 1_000_000))
        val img = gpu.comp.render(plan, transparent = s.transparent, timeSec = t)
        if (img.width == s.width && img.height == s.height) return img
        // FIT / FILL placement of the sequence render into the export frame.
        val out = gpu.comp.obtain(s.width, s.height)
        out.clear(0f, 0f, 0f, if (s.transparent) 0f else 1f)
        val sc = if (s.reframe == Reframe.FILL) max(s.width.toFloat() / img.width, s.height.toFloat() / img.height) else min(s.width.toFloat() / img.width, s.height.toFloat() / img.height)
        val w = (img.width * sc).toInt(); val h = (img.height * sc).toInt()
        out.bind()
        GLES30.glViewport((s.width - w) / 2, (s.height - h) / 2, w, h)
        val pp = gpu.comp.presentProg
        pp.use(); pp.tex("uTex", 0, GLES30.GL_TEXTURE_2D, img.texture.id)
        pp.v2("uOut", w.toFloat(), h.toFloat()); pp.v2("uOffset", ((s.width - w) / 2).toFloat(), ((s.height - h) / 2).toFloat())
        pp.i("uFlip", 0); pp.v4("uBg", 0f, 0f, 0f, 0f)
        Quad.draw()
        gpu.comp.recycle(img)
        return out
    }

    // ---------------- audio ----------------
    /** Two-pass master: measure integrated loudness over the range, then render normalised + limited chunks. */
    private fun masterAudio(p: Project, start: Double, dur: Double, progress: (Double) -> Unit, consume: (FloatArray) -> Unit) {
        val audio = EngineAudio(context, p.sequence.audioSampleRate)
        val mixer = audio.mixer(p)
        val fs = p.sequence.audioSampleRate.toDouble()
        val chunk = 1.0
        val target = p.sequence.master.loudnessTargetLufs
        var gainDb = 0.0
        if (target != null) {
            val meter = LoudnessMeter(fs)
            var t = 0.0
            while (t < dur) { check(); meter.add(mixer.mix(start + t, min(chunk, dur - t), applyMaster = false)); t += chunk; progress(0.5 * t / dur) }
            meter.integrated()?.let { gainDb = (target - it).coerceIn(-40.0, 20.0) }
        }
        val g = (dbToGain(gainDb) * p.sequence.master.volume).toFloat()
        val ceiling = dbToGain(p.sequence.master.limiterCeilingDb).toFloat()
        val limiter = Limiter(fs, p.sequence.master.limiterCeilingDb, 80.0)
        var t = 0.0
        while (t < dur) {
            check()
            val buf = mixer.mix(start + t, min(chunk, dur - t), applyMaster = false)
            for (i in buf.indices) buf[i] *= g
            limiter.process(buf)
            for (i in buf.indices) buf[i] = buf[i].coerceIn(-ceiling, ceiling)
            consume(buf)
            t += chunk
            progress(0.5 + 0.5 * t / dur)
        }
    }

    private fun encodeAac(p: Project, s: ExportSettings, start: Double, dur: Double, progress: (Double) -> Unit): Pair<MediaFormat, List<Pair<ByteBuffer, MediaCodec.BufferInfo>>> {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, s.sampleRate, s.channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, s.audioBitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        val samples = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var framesQueued = 0L
        val pending = java.util.ArrayDeque<ShortArray>()
        fun drain(eos: Boolean) {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return else continue }
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFormat = enc.outputFormat; continue }
                if (o < 0) continue
                val b = enc.getOutputBuffer(o)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val copy = ByteBuffer.allocateDirect(info.size); b.position(info.offset); b.limit(info.offset + info.size); copy.put(b); copy.flip()
                    samples += copy to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                }
                val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                enc.releaseOutputBuffer(o, false)
                if (done) return
            }
        }
        fun feed(pcm: ShortArray, eos: Boolean) {
            var off = 0
            while (off < pcm.size || eos) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i < 0) { drain(false); continue }
                val ib = enc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                val n = min(ib.capacity() / 2, pcm.size - off)
                ib.clear(); ib.asShortBuffer().put(pcm, off, n)
                val pts = framesQueued * 1_000_000L / s.sampleRate
                framesQueued += n / s.channels
                off += n
                val last = eos && off >= pcm.size
                enc.queueInputBuffer(i, 0, n * 2, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                drain(false)
                if (last) break
            }
        }
        masterAudio(p, start, dur, progress) { buf ->
            val pcm = ShortArray(if (s.channels == 1) buf.size / 2 else buf.size) { i ->
                val v = if (s.channels == 1) (buf[2 * i] + buf[2 * i + 1]) / 2 else buf[i]
                (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }
            feed(pcm, false)
        }
        feed(ShortArray(0), true)
        drain(true)
        enc.stop(); enc.release()
        return (outFormat ?: enc.outputFormat) to samples
    }

    // ---------------- video ----------------
    private fun exportVideo(project: Project, s: ExportSettings, start: Double, dur: Double, out: File, progress: (Double, String) -> Unit, audioOnly: Boolean = false): ExportResult {
        val notes = ArrayList<String>()
        val p = projectFor(project, s)
        val hasAudio = p.sequence.tracks.any { t -> t.clips.any { c -> !c.audio.muted && (p.asset(c.assetId)?.hasAudio == true || c.kind == app.bekocat.core.timeline.ClipKind.COMPOUND) } }
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var audioTrack = -1; var videoTrack = -1
        val audio = if (hasAudio || audioOnly) encodeAac(p, s, start, dur) { f -> progress(if (audioOnly) f else f * 0.15, "audio") } else null
        if (audio != null) audioTrack = muxer.addTrack(audio.first)
        if (audioOnly) {
            muxer.start()
            audio?.second?.forEach { (b, i) -> muxer.writeSampleData(audioTrack, b, i) }
            muxer.stop(); muxer.release()
            return verify(out, "audio/mp4", dur, s, notes)
        }
        if (!encoderSupports(s.codec, s.width, s.height, s.frameRate.fps)) {
            muxer.release(); out.delete()
            throw IllegalStateException("${s.codec.label} ${s.width}×${s.height}@${"%.2f".format(s.frameRate.fps)} is not supported by this device's encoder. Choose a lower resolution or H.264.")
        }
        val fmt = MediaFormat.createVideoFormat(s.codec.mime, s.width, s.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, s.videoBitrate)
            setFloat(MediaFormat.KEY_FRAME_RATE, s.frameRate.fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, s.keyframeIntervalSec)
            if (s.codec == VideoCodec.H264) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
        val enc = runCatching { MediaCodec.createEncoderByType(s.codec.mime).also { it.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) } }.getOrElse {
            fmt.removeKey(MediaFormat.KEY_PROFILE); notes += "Encoder profile fallback to default"
            MediaCodec.createEncoderByType(s.codec.mime).also { e -> e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        }
        val inputSurface = enc.createInputSurface()
        enc.start()
        val gpu = Gpu(context)
        val win = gpu.egl.windowSurface(inputSurface)
        val info = MediaCodec.BufferInfo()
        var started = false
        val pendingAudio = audio?.second ?: emptyList()
        fun drain(eos: Boolean) {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return else continue }
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(enc.outputFormat); muxer.start(); started = true
                    pendingAudio.forEach { (b, i) -> muxer.writeSampleData(audioTrack, b, i) }
                    continue
                }
                if (o < 0) continue
                val b = enc.getOutputBuffer(o)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && started) {
                    b.position(info.offset); b.limit(info.offset + info.size); muxer.writeSampleData(videoTrack, b, info)
                }
                val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                enc.releaseOutputBuffer(o, false)
                if (done) return
            }
        }
        try {
            val rate = s.frameRate
            val total = rate.frameAt(Rational.fromDouble(dur, 1_000_000)).coerceAtLeast(1)
            for (n in 0 until total) {
                check()
                val tRel = rate.timeOf(n)
                val t = start + tRel.toDouble()
                gpu.egl.makeCurrent(gpu.pbuffer)
                val img = renderFrame(gpu, p, s, t)
                gpu.egl.makeCurrent(win)
                gpu.comp.present(img, s.width, s.height)
                gpu.comp.recycle(img)
                gpu.egl.setPresentationTime(win, tRel.toMicros() * 1000)
                gpu.egl.swap(win)
                drain(false)
                if (n % 5 == 0L) progress(0.15 + 0.8 * n / total, "video")
            }
            enc.signalEndOfInputStream()
            drain(true)
        } finally {
            runCatching { enc.stop() }; enc.release()
            gpu.egl.releaseSurface(win); inputSurface.release(); gpu.release()
            runCatching { if (started) muxer.stop() }; muxer.release()
        }
        progress(0.97, "verify")
        return verify(out, "video/mp4", dur, s, notes)
    }

    /** Re-opens the file: track count, duration and decodability of the first and middle frames. */
    private fun verify(out: File, mime: String, expected: Double, s: ExportSettings, notes: MutableList<String>): ExportResult {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(out.path)
            var maxDur = 0L; var w = 0; var h = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) maxDur = max(maxDur, f.getLong(MediaFormat.KEY_DURATION))
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { w = f.getInteger(MediaFormat.KEY_WIDTH); h = f.getInteger(MediaFormat.KEY_HEIGHT) }
            }
            val sec = maxDur / 1e6
            val tol = s.frameRate.frameDuration.toDouble() * 1.5 + 0.05
            if (abs(sec - expected) > tol) throw IllegalStateException("Verification failed: duration ${"%.3f".format(sec)} s, expected ${"%.3f".format(expected)} s")
            if (mime.startsWith("video")) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(out.path)
                    if (r.getFrameAtTime(0) == null || r.getFrameAtTime((expected / 2 * 1e6).toLong()) == null) throw IllegalStateException("Verification failed: frames not decodable")
                } finally { r.release() }
            }
            return ExportResult(out, mime, sec, ex.trackCount, w, h, notes)
        } finally { ex.release() }
    }

    // ---------------- other formats ----------------
    private fun exportGif(project: Project, s: ExportSettings, start: Double, dur: Double, out: File, progress: (Double, String) -> Unit): ExportResult {
        val p = projectFor(project, s)
        val gpu = Gpu(context)
        try {
            out.outputStream().buffered().use { os ->
                val enc = GifEncoder(os, s.width, s.height, (100.0 / s.gifFps).toInt())
                val n = (dur * s.gifFps).toInt().coerceAtLeast(1)
                val px = IntArray(s.width * s.height)
                for (i in 0 until n) {
                    check()
                    val img = renderFrame(gpu, p, s, start + i.toDouble() / s.gifFps)
                    val bmp = img.toBitmap(); gpu.comp.recycle(img)
                    bmp.getPixels(px, 0, s.width, 0, 0, s.width, s.height); bmp.recycle()
                    enc.addFrame(px)
                    progress(i.toDouble() / n, "gif")
                }
                enc.finish()
            }
        } finally { gpu.release() }
        return ExportResult(out, "image/gif", dur, 1, s.width, s.height, emptyList())
    }

    private fun exportSequence(project: Project, s: ExportSettings, start: Double, dur: Double, out: File, progress: (Double, String) -> Unit): ExportResult {
        val p = projectFor(project, s)
        val gpu = Gpu(context)
        val png = s.kind == ExportKind.PNG_SEQUENCE
        try {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                zip.setLevel(0)
                val total = s.frameRate.frameAt(Rational.fromDouble(dur, 1_000_000)).coerceAtLeast(1)
                for (n in 0 until total) {
                    check()
                    val t = start + s.frameRate.timeOf(n).toDouble()
                    val img = renderFrame(gpu, p, s.copy(transparent = png && s.transparent), t)
                    val bmp = img.toBitmap(); gpu.comp.recycle(img)
                    zip.putNextEntry(ZipEntry("frame_%06d.%s".format(n, if (png) "png" else "jpg")))
                    bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 95, zip)
                    zip.closeEntry(); bmp.recycle()
                    progress(n.toDouble() / total, "frames")
                }
            }
        } finally { gpu.release() }
        return ExportResult(out, "application/zip", dur, 1, s.width, s.height, listOf(if (png && s.transparent) "PNG with alpha" else "Image sequence"))
    }

    private fun exportWav(p: Project, s: ExportSettings, start: Double, dur: Double, out: File, progress: (Double, String) -> Unit): ExportResult {
        val parts = ArrayList<FloatArray>()
        masterAudio(p, start, dur, { progress(it, "audio") }) { parts += it }
        val all = FloatArray(parts.sumOf { it.size }); var o = 0
        for (b in parts) { System.arraycopy(b, 0, all, o, b.size); o += b.size }
        out.outputStream().buffered().use { Wav.write(it, all, p.sequence.audioSampleRate, 2, s.wavBits) }
        return ExportResult(out, "audio/wav", all.size / 2.0 / p.sequence.audioSampleRate, 1, 0, 0, emptyList())
    }

    private fun exportStems(p: Project, s: ExportSettings, start: Double, dur: Double, out: File, progress: (Double, String) -> Unit): ExportResult {
        val audio = EngineAudio(context, p.sequence.audioSampleRate)
        val stems = audio.mixer(p).renderStems(start + dur).mapValues { (_, b) -> b.copyOfRange((start * p.sequence.audioSampleRate).toInt() * 2, b.size) }
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            var i = 0
            for ((name, b) in stems) {
                check()
                zip.putNextEntry(ZipEntry("${name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")}.wav"))
                Wav.write(zip, b, p.sequence.audioSampleRate, 2, s.wavBits)
                zip.closeEntry(); i++; progress(i.toDouble() / stems.size, "stems")
            }
        }
        return ExportResult(out, "application/zip", dur, stems.size, 0, 0, listOf("${stems.size} stems"))
    }

    private fun exportCaptions(p: Project, s: ExportSettings, out: File): ExportResult {
        val segs = p.sequence.captions.firstOrNull()?.segments ?: throw IllegalStateException("No captions in this project")
        val text = when (s.kind) {
            ExportKind.CAPTIONS_SRT -> CaptionIO.toSrt(segs)
            ExportKind.CAPTIONS_VTT -> CaptionIO.toVtt(segs)
            else -> CaptionIO.toAss(segs, p.sequence.width, p.sequence.height)
        }
        out.writeText(text)
        return ExportResult(out, "text/plain", segs.lastOrNull()?.end?.toDouble() ?: 0.0, 1, 0, 0, emptyList())
    }
}
