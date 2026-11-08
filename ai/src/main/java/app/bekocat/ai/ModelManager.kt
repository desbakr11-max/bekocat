package app.bekocat.ai

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class ModelFile(val path: String, val url: String, val bytes: Long, val sha256: String?)

data class ModelSpec(
    val id: String, val ar: String, val en: String, val task: String,
    val files: List<ModelFile>,
    val licenseCode: String, val licenseWeights: String, val source: String,
    val ramBytes: Long, val languages: String, val notesAr: String, val notesEn: String,
) {
    val totalBytes get() = files.sumOf { it.bytes }
}

data class ModelState(val installed: Boolean, val progress: Float? = null, val error: String? = null)

/**
 * Downloads models on explicit user request only: shows size, checks free space, resumes
 * interrupted downloads with HTTP Range, verifies SHA-256, and stores them in app-private
 * storage. Deleting a model never touches projects. Once installed, everything runs offline.
 */
class ModelManager(private val context: Context) {

    companion object {
        @Volatile private var inst: ModelManager? = null
        /** One manager per process so every screen sees the same download state. */
        fun shared(c: Context): ModelManager = inst ?: synchronized(this) { inst ?: ModelManager(c.applicationContext).also { inst = it } }
        const val ASR = "asr-whisper-base"
        const val ASR_QUALITY = "asr-whisper-small"
        const val VAD = "vad-silero"
        const val DIARIZATION = "diarization"
        const val MATTING = "matting-modnet"
        const val SEPARATION = "separation-spleeter-2"
        const val PLANNER = "planner-qwen2.5-0.5b"

        private fun hf(repo: String, file: String, bytes: Long, sha: String?, local: String = file.substringAfterLast('/')) =
            ModelFile(local, "https://huggingface.co/$repo/resolve/main/$file", bytes, sha)

        fun human(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
            else -> "%.0f KB".format(b / 1024.0)
        }
    }

    val catalog: List<ModelSpec> = listOf(
        ModelSpec(ASR, "تفريغ الكلام · Whisper base", "Speech recognition · Whisper base", "asr",
            listOf(hf("csukuangfj/sherpa-onnx-whisper-base", "base-encoder.int8.onnx", 29120534, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
                hf("csukuangfj/sherpa-onnx-whisper-base", "base-decoder.int8.onnx", 130672026, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
                hf("csukuangfj/sherpa-onnx-whisper-base", "base-tokens.txt", 816730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
                hf("csukuangfj/vad", "silero_vad.onnx", 1807522, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28")),
            "MIT (Whisper), Apache-2.0 (sherpa-onnx)", "MIT (OpenAI Whisper), MIT (Silero VAD)", "openai/whisper via k2-fsa/sherpa-onnx",
            700L shl 20, "ar, en (+97)", "متوازن: سرعة جيدة، دقة عربية متوسطة. مع كشف الكلام (VAD).", "Balanced: good speed, moderate Arabic accuracy. Includes VAD."),
        ModelSpec(ASR_QUALITY, "تفريغ الكلام · Whisper small (جودة)", "Speech recognition · Whisper small (quality)", "asr",
            listOf(hf("csukuangfj/sherpa-onnx-whisper-small", "small-encoder.int8.onnx", 112442483, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"),
                hf("csukuangfj/sherpa-onnx-whisper-small", "small-decoder.int8.onnx", 262226114, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"),
                hf("csukuangfj/sherpa-onnx-whisper-small", "small-tokens.txt", 816730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
                hf("csukuangfj/vad", "silero_vad.onnx", 1807522, "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28")),
            "MIT, Apache-2.0", "MIT", "openai/whisper via k2-fsa/sherpa-onnx",
            1500L shl 20, "ar, en (+97)", "دقة أعلى للعربية واللهجات، أبطأ.", "Higher accuracy for Arabic and dialects; slower."),
        ModelSpec(DIARIZATION, "فصل المتحدثين", "Speaker diarization", "diarization",
            listOf(hf("csukuangfj/sherpa-onnx-pyannote-segmentation-3-0", "model.int8.onnx", 1540506, "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d", "segmentation.onnx"),
                hf("csukuangfj/speaker-embedding-models", "wespeaker_en_voxceleb_CAM++.onnx", 29292684, "c46fad10b5f81e1aa4a60c162714208577093655076c5450f8c469e522ec54ef", "embedding.onnx")),
            "Apache-2.0", "MIT (pyannote 3.0 segmentation), Apache-2.0 (WeSpeaker CAM++)", "pyannote/segmentation-3.0, wenet-e2e/wespeaker",
            300L shl 20, "language-independent", "يحدد من يتكلم ومتى، لتلوين الكابشن لكل متحدث.", "Finds who speaks when, to colour captions per speaker."),
        ModelSpec(MATTING, "فصل الشخص عن الخلفية · MODNet", "Subject matting · MODNet", "matting",
            listOf(hf("Xenova/modnet", "onnx/model.onnx", 25888640, "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9", "modnet.onnx")),
            "Apache-2.0", "Apache-2.0", "ZHKKKe/MODNet (ONNX by Xenova)",
            400L shl 20, "-", "يعمل على الأشخاص بدون شاشة خضراء، مع تنعيم زمني للحواف.", "Works on people without a green screen, with temporal edge smoothing."),
        ModelSpec(SEPARATION, "فصل الغناء/المرافقة · Spleeter", "Vocals / accompaniment · Spleeter", "separation",
            listOf(hf("csukuangfj/sherpa-onnx-spleeter-2stems", "vocals.onnx", 39318336, "bdc16ab6bf6117ddd4842c19e80e40e2be188fc555295064d424616b0224ac97"),
                hf("csukuangfj/sherpa-onnx-spleeter-2stems", "accompaniment.onnx", 39318343, "671ace17acd3720674a2bc14de32ac6292453dec20d9eb0ba4255d4ad8e3d8c0")),
            "Apache-2.0", "MIT (Deezer Spleeter)", "deezer/spleeter via k2-fsa/sherpa-onnx",
            600L shl 20, "-", "فصل جزأين: الغناء والموسيقى. الفصل ليس مثاليًا دائمًا.", "Two stems: vocals and accompaniment. Not always perfect."),
        ModelSpec(PLANNER, "المساعد اللغوي · Qwen2.5 0.5B", "Assistant language model · Qwen2.5 0.5B", "planner",
            listOf(hf("litert-community/Qwen2.5-0.5B-Instruct", "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", 546660344, "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2", "planner.task")),
            "Apache-2.0 (MediaPipe)", "Apache-2.0 (Qwen2.5-0.5B-Instruct)", "Qwen/Qwen2.5-0.5B-Instruct via litert-community",
            1200L shl 20, "ar, en", "يحوّل الأوامر المركبة إلى عمليات محددة يمكن مراجعتها والتراجع عنها.", "Turns compound requests into reviewable, undoable operations."),
    )

    fun spec(id: String) = catalog.firstOrNull { it.id == id }
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    fun dir(id: String) = File(root, id)
    fun file(id: String, name: String) = File(dir(id), name)

    private val _states = MutableStateFlow(catalog.associate { it.id to ModelState(isInstalled(it.id)) })
    val states: StateFlow<Map<String, ModelState>> = _states
    private val cancelled = ConcurrentHashMap<String, Boolean>()

    fun isInstalled(id: String): Boolean {
        val s = spec(id) ?: return false
        return s.files.all { f -> File(dir(id), f.path).let { it.exists() && it.length() == f.bytes } } && File(dir(id), ".verified").exists()
    }

    /** Best installed ASR model (quality preferred). */
    fun asrModel(): String? = listOf(ASR_QUALITY, ASR).firstOrNull { isInstalled(it) }

    fun freeBytes(): Long = StatFs(context.filesDir.path).availableBytes

    private fun set(id: String, st: ModelState) = _states.update { it + (id to st) }

    fun cancel(id: String) { cancelled[id] = true }

    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    /** Starts a download that outlives the screen that requested it; errors land in [states]. */
    fun startDownload(id: String) { if (_states.value[id]?.progress == null) bg.launch { runCatching { download(id) } } }

    fun delete(id: String) { dir(id).deleteRecursively(); set(id, ModelState(false)) }

    /** Downloads (resumable) and verifies every file of a model. Throws with a readable message on failure. */
    suspend fun download(id: String) = withContext(Dispatchers.IO) {
        val s = spec(id) ?: throw IllegalArgumentException("Unknown model $id")
        if (isInstalled(id)) { set(id, ModelState(true)); return@withContext }
        val d = dir(id).apply { mkdirs() }
        val remaining = s.files.sumOf { f -> f.bytes - (File(d, f.path + ".part").takeIf { it.exists() }?.length() ?: File(d, f.path).takeIf { it.exists() }?.length() ?: 0L) }
        if (freeBytes() < remaining + (64L shl 20)) {
            val msg = "Not enough space: need ${human(remaining)}, free ${human(freeBytes())}"
            set(id, ModelState(false, error = msg)); throw IllegalStateException(msg)
        }
        cancelled[id] = false
        var doneBefore = 0L
        try {
            for (f in s.files) {
                val target = File(d, f.path)
                if (target.exists() && target.length() == f.bytes) { doneBefore += f.bytes; continue }
                val part = File(d, f.path + ".part")
                fetch(f, part) { got -> set(id, ModelState(false, progress = ((doneBefore + got).toFloat() / s.totalBytes).coerceIn(0f, 1f))); cancelled[id] == true }
                if (cancelled[id] == true) { set(id, ModelState(false)); return@withContext }
                if (part.length() != f.bytes) throw IllegalStateException("Size mismatch for ${f.path}: ${part.length()} ≠ ${f.bytes}")
                f.sha256?.let { exp ->
                    val got = sha256(part)
                    if (!got.equals(exp, true)) { part.delete(); throw IllegalStateException("Checksum failed for ${f.path}") }
                }
                if (!part.renameTo(target)) throw IllegalStateException("Cannot store ${f.path}")
                doneBefore += f.bytes
            }
            File(d, ".verified").writeText(System.currentTimeMillis().toString())
            set(id, ModelState(true))
        } catch (e: Exception) {
            set(id, ModelState(false, error = e.message ?: e.javaClass.simpleName)); throw e
        }
    }

    private fun fetch(f: ModelFile, part: File, progress: (Long) -> Boolean) {
        var url = URL(f.url)
        var redirects = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            if (have >= f.bytes) return
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "Bekocat/1.0 (Android; model-manager)")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Redirect without location")
                url = URL(url, loc); c.disconnect()
                if (++redirects > 8) throw IllegalStateException("Too many redirects")
                continue
            }
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code for ${f.path}")
            val append = code == 206 && have > 0
            RandomAccessFile(part, "rw").use { raf ->
                if (!append) raf.setLength(0)
                raf.seek(if (append) have else 0)
                c.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var total = if (append) have else 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); total += n
                        if (total - last > (1 shl 20)) { last = total; if (progress(total)) { c.disconnect(); return } }
                    }
                }
            }
            c.disconnect()
            return
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
