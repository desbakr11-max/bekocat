package app.bekocat.engine.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.bekocat.core.audio.ArrayPcmSource
import app.bekocat.core.audio.PcmSource
import app.bekocat.core.audio.Resampler
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Decodes an asset's audio once into a "conformed" cache: 32-bit float, stereo, interleaved,
 * at the sequence sample rate. All mixing reads from these files, so seeking is exact and
 * cheap, and waveforms / analysis reuse the same data.
 */
class AudioConform(private val context: Context, private val sampleRate: Int = 48_000) {

    private val dir = File(context.cacheDir, "audio-conform").apply { mkdirs() }

    fun cacheFile(uri: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest("$uri@$sampleRate".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.f32")
    }

    /** Returns a PCM source for [uri], decoding on first use. Returns null if the file has no audio. */
    fun source(uri: String, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): PcmSource? {
        val f = cacheFile(uri)
        if (!f.exists()) {
            val tmp = File(f.path + ".part")
            val ok = decode(Uri.parse(uri), tmp, progress, cancelled)
            if (!ok) { tmp.delete(); return null }
            tmp.renameTo(f)
        }
        return FilePcmSource(f)
    }

    private fun decode(uri: Uri, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return false
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val native = File(out.path + ".native")
            val raf = RandomAccessFile(native, "rw"); raf.setLength(0)
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            val tmp = ByteBuffer.allocate(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
            while (!outDone) {
                if (cancelled()) { codec.stop(); codec.release(); raf.close(); native.delete(); return false }
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); if (durUs > 0) progress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 0.9f)); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    // Convert to stereo float.
                    tmp.clear()
                    val frames = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 / channels else info.size / 2 / channels
                    val outBytes = frames * 8
                    val dst = if (outBytes <= tmp.capacity()) tmp else ByteBuffer.allocate(outBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (fI in 0 until frames) {
                        var l: Float; var r: Float
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            l = buf.float; r = if (channels > 1) buf.float else l
                            for (k in 2 until channels) { val v = buf.float; if (k % 2 == 0) l += v * 0.5f else r += v * 0.5f }
                        } else {
                            l = buf.short / 32768f; r = if (channels > 1) buf.short / 32768f else l
                            for (k in 2 until channels) { val v = buf.short / 32768f; if (k % 2 == 0) l += v * 0.5f else r += v * 0.5f }
                        }
                        dst.putFloat(l); dst.putFloat(r)
                    }
                    raf.write(dst.array(), 0, frames * 8)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            codec.stop(); codec.release(); raf.close()
            // Resample to the sequence rate in blocks.
            val src = FilePcmSource(native)
            if (srcRate == sampleRate) { native.renameTo(out); progress(1f); return true }
            val ratio = srcRate.toDouble() / sampleRate
            val outFrames = (src.frames / ratio).toLong()
            RandomAccessFile(out, "rw").use { w ->
                w.setLength(0)
                val block = 1 shl 15
                var done = 0L
                while (done < outFrames) {
                    if (cancelled()) { native.delete(); return false }
                    val n = minOf(block.toLong(), outFrames - done).toInt()
                    val base = done
                    val chunk = Resampler.render(src, n, { i -> (base + i) * ratio }, lowpass = if (ratio > 1) 1 / ratio else 1.0)
                    val bb = ByteBuffer.allocate(chunk.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (v in chunk) bb.putFloat(v)
                    w.write(bb.array())
                    done += n
                    progress(0.9f + 0.1f * done / outFrames)
                }
            }
            native.delete()
            return true
        } catch (e: Exception) {
            return false
        } finally { ex.release() }
    }
}

/** Little-endian stereo float file with a block cache (fast for both sequential and sinc-resampler access). */
class FilePcmSource(private val file: File) : PcmSource {
    private val raf = RandomAccessFile(file, "r")
    override val frames: Long = file.length() / 8
    private val blockFrames = 1 shl 15
    private var blockStart = -1L
    private val block = FloatArray(blockFrames * 2)
    private val bytes = ByteArray(blockFrames * 8)

    @Synchronized
    override fun read(start: Long, out: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            val f = start + i
            if (f < 0 || f >= frames) { out[2 * i] = 0f; out[2 * i + 1] = 0f; i++; continue }
            val bs = (f / blockFrames) * blockFrames
            if (bs != blockStart) load(bs)
            val off = (f - bs).toInt()
            val n = minOf(count - i, blockFrames - off, (frames - f).toInt())
            System.arraycopy(block, off * 2, out, i * 2, n * 2)
            i += n
        }
    }

    private fun load(bs: Long) {
        raf.seek(bs * 8)
        val n = minOf(blockFrames.toLong(), frames - bs).toInt()
        raf.readFully(bytes, 0, n * 8)
        ByteBuffer.wrap(bytes, 0, n * 8).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(block, 0, n * 2)
        blockStart = bs
    }

    fun close() = raf.close()
}

/** Sums separated stems with gains (non-destructive stem mix playback). */
class StemPcmSource(private val stems: List<Pair<PcmSource, Float>>) : PcmSource {
    override val frames: Long = stems.maxOfOrNull { it.first.frames } ?: 0
    override fun read(start: Long, out: FloatArray, count: Int) {
        java.util.Arrays.fill(out, 0, count * 2, 0f)
        val tmp = FloatArray(count * 2)
        for ((s, g) in stems) { if (g == 0f) continue; s.read(start, tmp, count); for (i in 0 until count * 2) out[i] += tmp[i] * g }
    }
}

fun emptySource() = ArrayPcmSource(FloatArray(0))
