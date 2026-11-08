package app.bekocat.ai

import android.content.Context
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** A decoded, downscaled frame: ARGB pixels and luma, plus its presentation time. */
class SmallFrame(val ptsUs: Long, val width: Int, val height: Int, val argb: IntArray, val luma: FloatArray)

/**
 * Sequential video decoder for analysis (scene detection, tracking, stabilisation, matting).
 * Uses MediaCodec ByteBuffer output (YUV_420_888 via Image) and samples a downscaled RGB/luma
 * grid directly from the planes — no full-resolution bitmaps, so long videos stay cheap.
 */
class FrameReader(private val context: Context, private val uri: Uri) {

    var width = 0; private set
    var height = 0; private set
    var rotation = 0; private set
    var durationUs = 0L; private set
    var fps = 30.0; private set

    /**
     * Decodes frames in [startUs, endUs) and calls [onFrame] for every frame whose pts is ≥ the next sample
     * time (spacing 1/[sampleFps]). The callback returns false to stop. Output long side ≤ [maxSide].
     */
    fun read(startUs: Long, endUs: Long, sampleFps: Double, maxSide: Int, cancelled: () -> Boolean = { false }, onFrame: (SmallFrame) -> Boolean) {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        width = fmt.getInteger(MediaFormat.KEY_WIDTH); height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0
        if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrElse { fmt.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0); dec.start()
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        val step = (1_000_000.0 / sampleFps).toLong().coerceAtLeast(1)
        var nextSample = startUs
        val rotW = if (rotation == 90 || rotation == 270) height else width
        val rotH = if (rotation == 90 || rotation == 270) width else height
        val s = maxSide.toDouble() / maxOf(rotW, rotH)
        val ow = maxOf(8, (rotW * minOf(1.0, s)).toInt()); val oh = maxOf(8, (rotH * minOf(1.0, s)).toInt())
        try {
            while (!outDone) {
                if (cancelled()) break
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > endUs + 1_000_000) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    val pts = info.presentationTimeUs
                    if (info.size > 0 && pts >= nextSample && pts >= startUs && pts < endUs) {
                        val img = dec.getOutputImage(o)
                        if (img != null && img.format == ImageFormat.YUV_420_888) {
                            val f = sample(img, ow, oh, pts)
                            img.close()
                            nextSample = pts + step
                            dec.releaseOutputBuffer(o, false)
                            if (!onFrame(f)) break
                            continue
                        }
                        img?.close()
                    }
                    if (pts >= endUs) outDone = true
                    dec.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            runCatching { dec.stop() }; dec.release(); ex.release()
        }
    }

    private fun sample(img: android.media.Image, ow: Int, oh: Int, pts: Long): SmallFrame {
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        val iw = img.width; val ih = img.height
        val argb = IntArray(ow * oh); val luma = FloatArray(ow * oh)
        for (oy in 0 until oh) for (ox in 0 until ow) {
            // Map output (rotated) coords back to source coords.
            val fx = (ox + 0.5) / ow; val fy = (oy + 0.5) / oh
            val (sx, sy) = when (rotation) {
                90 -> fy to (1 - fx); 180 -> (1 - fx) to (1 - fy); 270 -> (1 - fy) to fx; else -> fx to fy
            }
            val px = (sx * iw).toInt().coerceIn(0, iw - 1); val py = (sy * ih).toInt().coerceIn(0, ih - 1)
            val Y = (yb.get(py * y.rowStride + px * y.pixelStride).toInt() and 0xFF)
            val U = (ub.get((py / 2) * u.rowStride + (px / 2) * u.pixelStride).toInt() and 0xFF) - 128
            val V = (vb.get((py / 2) * v.rowStride + (px / 2) * v.pixelStride).toInt() and 0xFF) - 128
            val yy = 1.164 * (Y - 16)
            val r = (yy + 1.596 * V).toInt().coerceIn(0, 255)
            val g = (yy - 0.392 * U - 0.813 * V).toInt().coerceIn(0, 255)
            val b = (yy + 2.017 * U).toInt().coerceIn(0, 255)
            val i = oy * ow + ox
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            luma[i] = ((0.2126 * r + 0.7152 * g + 0.0722 * b) / 255).toFloat()
        }
        return SmallFrame(pts, ow, oh, argb, luma)
    }
}
