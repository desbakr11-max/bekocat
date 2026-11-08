package app.bekocat.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.bekocat.core.audio.ArrayPcmSource
import app.bekocat.core.audio.Fft
import app.bekocat.core.audio.PcmSource
import app.bekocat.core.audio.Resampler
import app.bekocat.core.export.CaptionIO
import app.bekocat.core.timeline.CaptionSegment
import app.bekocat.core.timeline.CaptionWord
import app.bekocat.core.timeline.Rational
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class AiCancelled : Exception("cancelled")

/** Converts conformed 48 kHz stereo PCM to 16 kHz mono for speech models. */
fun toMono16k(src: PcmSource, startSec: Double, durSec: Double, fs: Int = 48_000): FloatArray {
    val n = (durSec * 16_000).toInt()
    val ratio = fs / 16_000.0
    val st = startSec * fs
    val stereo = Resampler.render(src, n, { i -> st + i * ratio }, lowpass = 1 / ratio)
    return FloatArray(n) { (stereo[2 * it] + stereo[2 * it + 1]) * 0.5f }
}

/**
 * Speech-to-captions: Silero VAD splits speech into short phrases, Whisper (sherpa-onnx)
 * transcribes each phrase (Arabic, English, or auto-detect; or translates to English),
 * optional pyannote + speaker-embedding diarization labels speakers. Word times are measured
 * at phrase level from VAD and distributed inside each short phrase by character length — they
 * are approximate and are shown for review, never claimed as exact.
 */
class CaptionEngine(private val mm: ModelManager) {

    suspend fun transcribe(audio16k: FloatArray, offsetSec: Double, language: String?, translate: Boolean, diarize: Boolean,
                           progress: (Float) -> Unit, cancelled: () -> Boolean): List<CaptionSegment> = withContext(Dispatchers.Default) {
        val model = mm.asrModel() ?: throw IllegalStateException("Speech model not installed")
        val d = mm.dir(model)
        val prefix = if (model == ModelManager.ASR_QUALITY) "small" else "base"
        val vad = Vad(null, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = File(d, "silero_vad.onnx").path, threshold = 0.5f, minSilenceDuration = 0.25f,
                minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 8f),
            sampleRate = 16_000, numThreads = 2, provider = "cpu", debug = false))
        val rec = OfflineRecognizer(null, OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = File(d, "$prefix-encoder.int8.onnx").path, decoder = File(d, "$prefix-decoder.int8.onnx").path,
                    language = language ?: "", task = if (translate) "translate" else "transcribe", tailPaddings = -1),
                tokens = File(d, "$prefix-tokens.txt").path, numThreads = max(2, Runtime.getRuntime().availableProcessors() / 2), debug = false, provider = "cpu", modelType = "whisper"),
            decodingMethod = "greedy_search"))
        val phrases = ArrayList<Pair<Double, FloatArray>>()
        try {
            val win = 512
            var i = 0
            while (i + win <= audio16k.size) {
                vad.acceptWaveform(audio16k.copyOfRange(i, i + win)); i += win
                while (!vad.empty()) { val s = vad.front(); phrases += s.start / 16_000.0 to s.samples; vad.pop() }
            }
            vad.flush()
            while (!vad.empty()) { val s = vad.front(); phrases += s.start / 16_000.0 to s.samples; vad.pop() }
            val segs = ArrayList<CaptionSegment>()
            for ((k, ph) in phrases.withIndex()) {
                if (cancelled()) throw AiCancelled()
                val (start, samples) = ph
                val stream = rec.createStream()
                stream.acceptWaveform(samples, 16_000)
                rec.decode(stream)
                val r = rec.getResult(stream)
                stream.release()
                val text = r.text.trim()
                progress(0.8f * (k + 1) / phrases.size)
                if (text.isEmpty() || text.all { !it.isLetterOrDigit() }) continue
                val s0 = Rational.fromDouble(offsetSec + start, 1000)
                val e0 = Rational.fromDouble(offsetSec + start + samples.size / 16_000.0, 1000)
                // Confidence proxy: very short phrases or repeated tokens (Whisper hallucination pattern) get lower confidence.
                val words = CaptionIO.distributeWords(text, s0, e0).map { w ->
                    val rep = text.split(Regex("\\s+")).count { it == w.text } > 3
                    w.copy(confidence = if (rep || samples.size < 8000) 0.5 else 0.85)
                }
                segs += CaptionSegment("seg-${segs.size + 1}", s0, e0, words)
            }
            if (diarize && mm.isInstalled(ModelManager.DIARIZATION)) {
                progress(0.85f)
                val dd = mm.dir(ModelManager.DIARIZATION)
                val dia = OfflineSpeakerDiarization(null, OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(File(dd, "segmentation.onnx").path), numThreads = 2, debug = false, provider = "cpu"),
                    embedding = SpeakerEmbeddingExtractorConfig(model = File(dd, "embedding.onnx").path, numThreads = 2, debug = false, provider = "cpu"),
                    clustering = FastClusteringConfig(numClusters = -1, threshold = 0.6f), minDurationOn = 0.3f, minDurationOff = 0.5f))
                val turns = dia.process(audio16k)
                dia.release()
                return@withContext segs.map { s ->
                    val mid = s.start.toDouble() - offsetSec + (s.end - s.start).toDouble() / 2
                    val spk = turns.minByOrNull { t -> if (mid in t.start..t.end) 0.0 else min(kotlin.math.abs(mid - t.start), kotlin.math.abs(mid - t.end)).toDouble() }?.speaker ?: 0
                    s.copy(speaker = spk, words = s.words.map { it.copy(speaker = spk) })
                }
            }
            progress(1f)
            segs
        } finally {
            vad.release(); rec.release()
        }
    }
}

/**
 * Video matting with MODNet (ONNX Runtime). Each frame is resized to a 512 short side (÷32),
 * normalised to [-1,1], matted, then temporally stabilised: in static regions the matte is
 * blended with the previous frame's matte to suppress edge flicker, while moving regions follow
 * the current frame. Mattes are written as PNGs (m_000123.png) for non-destructive compositing.
 */
class MattingEngine(private val context: Context, private val mm: ModelManager) {

    suspend fun run(uri: Uri, startUs: Long, endUs: Long, fps: Double, outDir: File, temporal: Double,
                    progress: (Float) -> Unit, cancelled: () -> Boolean): Int = withContext(Dispatchers.Default) {
        if (!mm.isInstalled(ModelManager.MATTING)) throw IllegalStateException("Matting model not installed")
        outDir.mkdirs()
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2)) }
        val session = env.createSession(mm.file(ModelManager.MATTING, "modnet.onnx").path, opts)
        var count = 0
        var prevAlpha: FloatArray? = null
        var prevLuma: FloatArray? = null
        try {
            val reader = FrameReader(context, uri)
            reader.read(startUs, endUs, fps, 512, cancelled) { f ->
                if (cancelled()) return@read false
                // Model input: dimensions divisible by 32.
                val w = f.width / 32 * 32; val h = f.height / 32 * 32
                val input = FloatBuffer.allocate(3 * w * h)
                for (c in 0 until 3) for (y in 0 until h) for (x in 0 until w) {
                    val p = f.argb[(y * f.height / h) * f.width + (x * f.width / w)]
                    val v = ((p shr (16 - 8 * c)) and 0xFF) / 255f
                    input.put((v - 0.5f) / 0.5f)
                }
                input.rewind()
                val t = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong()))
                val res = session.run(mapOf(session.inputNames.first() to t))
                @Suppress("UNCHECKED_CAST")
                val out = (res[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                t.close(); res.close()
                val alpha = FloatArray(w * h) { out[it / w][it % w].coerceIn(0f, 1f) }
                val luma = FloatArray(w * h) { f.luma[((it / w) * f.height / h) * f.width + (it % w) * f.width / w] }
                val pa = prevAlpha; val pl = prevLuma
                if (pa != null && pl != null && pa.size == alpha.size) {
                    for (i in alpha.indices) {
                        val motion = kotlin.math.abs(luma[i] - pl[i])
                        val k = (temporal * (1 - (motion * 12).coerceAtMost(1f))).toFloat()
                        alpha[i] = pa[i] * k + alpha[i] * (1 - k)
                    }
                }
                prevAlpha = alpha; prevLuma = luma
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.setPixels(IntArray(w * h) { val v = (alpha[it] * 255).toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, 0, w, 0, 0, w, h)
                val index = Math.round(f.ptsUs / 1e6 * fps)
                FileOutputStream(File(outDir, "m_%06d.png".format(index))).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                count++
                progress(((f.ptsUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f))
                true
            }
        } finally { session.close() }
        if (cancelled()) throw AiCancelled()
        count
    }

    /** Single image matte (for smart selection / reframing). Returns alpha at the given size. */
    fun matteOf(f: SmallFrame): Pair<FloatArray, Pair<Int, Int>> {
        val env = OrtEnvironment.getEnvironment()
        env.createSession(mm.file(ModelManager.MATTING, "modnet.onnx").path, OrtSession.SessionOptions()).use { session ->
            val w = max(32, f.width / 32 * 32); val h = max(32, f.height / 32 * 32)
            val input = FloatBuffer.allocate(3 * w * h)
            for (c in 0 until 3) for (y in 0 until h) for (x in 0 until w) {
                val p = f.argb[(y * f.height / h) * f.width + (x * f.width / w)]
                input.put((((p shr (16 - 8 * c)) and 0xFF) / 255f - 0.5f) / 0.5f)
            }
            input.rewind()
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
                session.run(mapOf(session.inputNames.first() to t)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (res[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                    return FloatArray(w * h) { out[it / w][it % w] } to (w to h)
                }
            }
        }
    }
}

/**
 * Vocals / accompaniment separation with the Spleeter 2-stem networks (ONNX Runtime).
 * STFT (Hann 4096, hop 1024) at 44.1 kHz, magnitudes of the first 1024 bins in 512-frame
 * chunks → each network estimates its stem magnitude → Wiener-style soft masks (extended to
 * high bins by averaging) applied to the complex mixture → ISTFT with overlap-add, so stems sum
 * back to the mix and stay sample-aligned. Output is conformed 48 kHz stereo float files.
 */
class SeparationEngine(private val mm: ModelManager) {

    suspend fun separate(src: PcmSource, outDir: File, progress: (Float) -> Unit, cancelled: () -> Boolean): List<String> = withContext(Dispatchers.Default) {
        if (!mm.isInstalled(ModelManager.SEPARATION)) throw IllegalStateException("Separation model not installed")
        outDir.mkdirs()
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2)) }
        val sv = env.createSession(mm.file(ModelManager.SEPARATION, "vocals.onnx").path, opts)
        val sa = env.createSession(mm.file(ModelManager.SEPARATION, "accompaniment.onnx").path, opts)
        val nFft = 4096; val hop = 1024; val bins = 1024; val chunk = 512
        val fsIn = 48_000.0; val fsM = 44_100.0
        val totalM = (src.frames / fsIn * fsM).toLong()
        val win = DoubleArray(nFft) { 0.5 - 0.5 * cos(2 * PI * it / nFft) }
        // Streaming overlap-add: an accumulator covers the current block plus one window of tail;
        // finished samples are flushed to 44.1 kHz temp files, then resampled to 48 kHz files.
        val tmpV = File(outDir, "v44.f32"); val tmpA = File(outDir, "a44.f32")
        val vOut = java.io.BufferedOutputStream(FileOutputStream(tmpV), 1 shl 20)
        val aOut = java.io.BufferedOutputStream(FileOutputStream(tmpA), 1 shl 20)
        val span = chunk * hop + nFft
        var accV = Array(2) { FloatArray(span) }; var accA = Array(2) { FloatArray(span) }; var norm = FloatArray(span)
        var bufStart = -(nFft / 2).toLong() // sample index of acc[0]
        var written = 0L
        fun flush(upTo: Long) {
            // Write samples [max(written, 0), upTo) from the accumulators.
            val from = max(written, 0L); if (upTo <= from) return
            val bbV = ByteBuffer.allocate(((upTo - from) * 8).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            val bbA = ByteBuffer.allocate(((upTo - from) * 8).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            for (sIdx in from until upTo) {
                val k = (sIdx - bufStart).toInt(); val nn = norm[k].let { if (it > 1e-6f) it else 1f }
                for (c in 0..1) { bbV.putFloat(accV[c][k] / nn); bbA.putFloat(accA[c][k] / nn) }
            }
            vOut.write(bbV.array()); aOut.write(bbA.array()); written = upTo
        }
        try {
            val totalFrames = ((totalM + nFft) / hop).toInt() + 1
            var frameStart = 0L
            while (frameStart < totalFrames) {
                if (cancelled()) throw AiCancelled()
                val nFrames = min(chunk, (totalFrames - frameStart).toInt())
                val sampleStart = frameStart * hop - nFft / 2
                val need = (nFrames - 1) * hop + nFft
                val pcm = Resampler.render(src, need, { i -> (sampleStart + i) * fsIn / fsM }, lowpass = 1.0)
                val re = Array(2) { Array(nFrames) { DoubleArray(nFft) } }; val im = Array(2) { Array(nFrames) { DoubleArray(nFft) } }
                val x = FloatBuffer.allocate(2 * chunk * bins)
                for (c in 0..1) for (f in 0 until chunk) {
                    if (f < nFrames) {
                        val r = re[c][f]; val ii = im[c][f]
                        for (k in 0 until nFft) r[k] = pcm[2 * (f * hop + k) + c] * win[k]
                        Fft.transform(r, ii)
                        for (bn in 0 until bins) x.put(sqrt(r[bn] * r[bn] + ii[bn] * ii[bn]).toFloat())
                    } else for (bn in 0 until bins) x.put(0f)
                }
                x.rewind()
                val shape = longArrayOf(2, 1, chunk.toLong(), bins.toLong())
                val yv = OnnxTensor.createTensor(env, x, shape).use { t -> sv.run(mapOf(sv.inputNames.first() to t)).use { (it[0] as OnnxTensor).floatBuffer.let { b2 -> FloatArray(b2.remaining()).also { a2 -> b2.get(a2) } } } }
                x.rewind()
                val ya = OnnxTensor.createTensor(env, x, shape).use { t -> sa.run(mapOf(sa.inputNames.first() to t)).use { (it[0] as OnnxTensor).floatBuffer.let { b2 -> FloatArray(b2.remaining()).also { a2 -> b2.get(a2) } } } }
                val eps = 1e-10
                for (c in 0..1) for (f in 0 until nFrames) {
                    val r = re[c][f]; val ii = im[c][f]
                    val mv = DoubleArray(nFft / 2 + 1); var avgV = 0.0
                    for (bn in 0 until bins) {
                        val v = yv[(c * chunk + f) * bins + bn].toDouble(); val a2 = ya[(c * chunk + f) * bins + bn].toDouble()
                        mv[bn] = (v * v + eps / 2) / (v * v + a2 * a2 + eps)
                        if (bn >= bins - 64) avgV += mv[bn] / 64
                    }
                    for (bn in bins..nFft / 2) mv[bn] = avgV
                    val vr = DoubleArray(nFft); val vi = DoubleArray(nFft); val ar = DoubleArray(nFft); val ai = DoubleArray(nFft)
                    for (bn in 0..nFft / 2) {
                        vr[bn] = r[bn] * mv[bn]; vi[bn] = ii[bn] * mv[bn]; ar[bn] = r[bn] * (1 - mv[bn]); ai[bn] = ii[bn] * (1 - mv[bn])
                        if (bn in 1 until nFft / 2) { vr[nFft - bn] = vr[bn]; vi[nFft - bn] = -vi[bn]; ar[nFft - bn] = ar[bn]; ai[nFft - bn] = -ai[bn] }
                    }
                    Fft.transform(vr, vi, inverse = true); Fft.transform(ar, ai, inverse = true)
                    val base = (frameStart + f) * hop - nFft / 2
                    for (k in 0 until nFft) {
                        val idx = (base + k - bufStart).toInt()
                        accV[c][idx] += (vr[k] * win[k]).toFloat(); accA[c][idx] += (ar[k] * win[k]).toFloat()
                        if (c == 0) norm[idx] += (win[k] * win[k]).toFloat()
                    }
                }
                frameStart += nFrames
                // Samples before the next frame's start are complete.
                val done = min(frameStart * hop - nFft / 2, totalM)
                flush(done)
                // Slide the accumulators so that index 0 == `done`.
                val shift = (done - bufStart).toInt()
                if (shift > 0) {
                    fun slide(a: FloatArray) = FloatArray(span).also { n -> System.arraycopy(a, shift, n, 0, span - shift) }
                    accV = Array(2) { slide(accV[it]) }; accA = Array(2) { slide(accA[it]) }; norm = slide(norm); bufStart = done
                }
                progress(0.85f * frameStart / totalFrames)
            }
            flush(totalM)
        } finally {
            vOut.close(); aOut.close(); sv.close(); sa.close()
        }
        // Resample 44.1 kHz → 48 kHz in blocks (bounded memory).
        val n48 = src.frames
        for ((tmp, name) in listOf(tmpV to "vocals", tmpA to "accompaniment")) {
            val inSrc = app.bekocat.engine.audio.FilePcmSource(tmp)
            val part = File(outDir, "$name.f32.part")
            java.io.BufferedOutputStream(FileOutputStream(part), 1 shl 20).use { os ->
                var done = 0L; val block = 1 shl 15
                while (done < n48) {
                    if (cancelled()) throw AiCancelled()
                    val n = min(block.toLong(), n48 - done).toInt(); val b0 = done
                    val chunkOut = Resampler.render(inSrc, n, { i -> (b0 + i) * fsM / fsIn })
                    val bb = ByteBuffer.allocate(chunkOut.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (v in chunkOut) bb.putFloat(v)
                    os.write(bb.array()); done += n
                }
            }
            inSrc.close(); tmp.delete()
            part.renameTo(File(outDir, "$name.f32"))
        }
        progress(1f)
        return@withContext listOf("vocals", "accompaniment")
    }
}
