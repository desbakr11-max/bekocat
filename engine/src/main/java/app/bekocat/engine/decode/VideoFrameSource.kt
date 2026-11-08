package app.bekocat.engine.decode

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import app.bekocat.engine.gl.Fbo
import app.bekocat.engine.gl.Program
import app.bekocat.engine.gl.Quad
import app.bekocat.engine.gl.Shaders
import app.bekocat.engine.gl.Texture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Frame-accurate video frame provider. Decodes with MediaCodec (hardware when available)
 * onto a SurfaceTexture and copies the requested frame into a 2D texture. Sequential
 * requests decode forward; backwards or far jumps seek to the previous sync frame and
 * decode forward to the exact target, so preview and export show the same frame.
 * Must be used on the GL thread that owns the current EGL context.
 */
class VideoFrameSource(private val context: Context, private val uri: Uri, private val copyProgram: Program) {

    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val oesTex = Texture.external()
    private val surfaceTexture = SurfaceTexture(oesTex)
    private val surface = Surface(surfaceTexture)
    private val lock = ReentrantLock()
    private val frameReady = lock.newCondition()
    private var frameAvailable = false
    private val cbThread = HandlerThread("vfs-cb").apply { start() }
    val width: Int
    val height: Int
    val durationUs: Long
    private val frameDurationUs: Long
    private var inputDone = false
    private var outputDone = false
    private var currentPtsUs = Long.MIN_VALUE
    private var fbo: Fbo? = null
    private val texMatrix = FloatArray(16)
    private val info = MediaCodec.BufferInfo()
    var framesDecoded = 0L; private set

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw IllegalArgumentException("No video track in $uri")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val rot = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        val w = format.getInteger(MediaFormat.KEY_WIDTH); val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (rot == 90 || rot == 270) { width = h; height = w } else { width = w; height = h }
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() } else 30
        frameDurationUs = 1_000_000L / fps.coerceAtLeast(1)
        surfaceTexture.setOnFrameAvailableListener({
            lock.withLock { frameAvailable = true; frameReady.signalAll() }
        }, Handler(cbThread.looper))
        decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, surface, null, 0)
        decoder.start()
    }

    /** Returns a 2D texture containing the frame displayed at [timeUs] (nearest frame at or before). */
    fun frameAt(timeUs: Long): Texture? {
        val target = timeUs.coerceIn(0, maxOf(0, durationUs - 1))
        val half = frameDurationUs / 2
        if (currentPtsUs != Long.MIN_VALUE && target >= currentPtsUs - half && target < currentPtsUs + frameDurationUs - half) return fbo?.texture
        val needSeek = currentPtsUs == Long.MIN_VALUE || target < currentPtsUs || target > currentPtsUs + 2_000_000L
        if (needSeek) seekTo(target)
        if (!decodeUntil(target)) return fbo?.texture
        return fbo?.texture
    }

    private fun seekTo(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        inputDone = false; outputDone = false
    }

    private fun decodeUntil(target: Long): Boolean {
        var guard = 0
        while (!outputDone && guard++ < 20_000) {
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0); extractor.advance()
                    }
                }
            }
            val outIdx = decoder.dequeueOutputBuffer(info, 5_000)
            if (outIdx >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true }
                val pts = info.presentationTimeUs
                val isTarget = pts + frameDurationUs / 2 >= target || outputDone
                if (info.size > 0 || outputDone) {
                    if (isTarget && info.size > 0) {
                        lock.withLock { frameAvailable = false }
                        decoder.releaseOutputBuffer(outIdx, true)
                        awaitFrame()
                        copyToFbo()
                        currentPtsUs = pts
                        framesDecoded++
                        return true
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    framesDecoded++
                } else decoder.releaseOutputBuffer(outIdx, false)
            }
        }
        return false
    }

    private fun awaitFrame() {
        lock.withLock {
            var waited = 0
            while (!frameAvailable && waited < 40) { frameReady.await(25, java.util.concurrent.TimeUnit.MILLISECONDS); waited++ }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
    }

    private fun copyToFbo() {
        val f = fbo ?: Fbo(width, height).also { fbo = it }
        f.bind()
        GLES30.glDisable(GLES30.GL_BLEND)
        copyProgram.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        copyProgram.i("uTex", 0)
        GLES30.glUniformMatrix4fv(copyProgram.loc("uTexMatrix"), 1, false, texMatrix, 0)
        copyProgram.v2("uSize", width.toFloat(), height.toFloat())
        Quad.draw()
    }

    fun release() {
        runCatching { decoder.stop() }; runCatching { decoder.release() }
        extractor.release(); surface.release(); surfaceTexture.release()
        GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
        fbo?.release()
        cbThread.quitSafely()
    }

    companion object {
        fun shaders() = Program(Shaders.VERTEX, Shaders.COPY_OES)
    }
}
