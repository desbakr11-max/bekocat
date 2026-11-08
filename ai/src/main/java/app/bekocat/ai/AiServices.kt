package app.bekocat.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.bekocat.core.analysis.Gray
import app.bekocat.core.analysis.Reframe
import app.bekocat.core.analysis.SceneDetect
import app.bekocat.core.analysis.Stabilizer
import app.bekocat.core.analysis.TemporalFill
import app.bekocat.core.analysis.Tracker
import app.bekocat.core.timeline.AssistantParser
import app.bekocat.core.timeline.AssistantPlan
import app.bekocat.core.timeline.Project
import app.bekocat.core.timeline.Time
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Natural-language planner on an on-device LLM (MediaPipe LLM Inference, Qwen2.5-0.5B-Instruct).
 * The model is asked for JSON in a fixed schema; output is parsed and validated by
 * [AssistantParser.fromJson] so only known intents with bounded values can ever run.
 */
class PlannerEngine(private val context: Context, private val mm: ModelManager) {
    private var llm: LlmInference? = null

    suspend fun plan(command: String, p: Project, selected: String?): AssistantPlan? = withContext(Dispatchers.Default) {
        if (!mm.isInstalled(ModelManager.PLANNER)) return@withContext null
        val engine = llm ?: LlmInference.createFromOptions(context, LlmInference.LlmInferenceOptions.builder()
            .setModelPath(mm.file(ModelManager.PLANNER, "planner.task").path).setMaxTokens(768).build()).also { llm = it }
        val summary = "Project: ${p.sequence.width}x${p.sequence.height}, ${"%.1f".format(p.sequence.duration.toDouble())}s, " +
            "${p.sequence.tracks.sumOf { it.clips.size }} clips, captions=${p.sequence.captions.isNotEmpty()}, selected=${selected != null}."
        val prompt = """<|im_start|>system
You convert a video-editing request (Arabic or English, including Gulf/Saudi dialect) into JSON intents for the Bekocat editor.
Reply with JSON only, exactly following this schema (choose only listed types and values):
${AssistantParser.SCHEMA}
If the request is ambiguous, put a short Arabic question in "question" and the clear intents you are sure about.
<|im_end|>
<|im_start|>user
$summary
Request: $command<|im_end|>
<|im_start|>assistant
"""
        val out = engine.generateResponse(prompt)
        AssistantParser.fromJson(out, p.sequence, selected)
    }

    fun release() { llm?.close(); llm = null }
}

/** Classical video analysis on decoded frames (no model download needed). */
class VideoTools(private val context: Context) {

    private fun gray(f: SmallFrame) = Gray(f.width, f.height, f.luma)

    /** Scene cut times (seconds, source time) in [startUs, endUs). */
    suspend fun scenes(uri: Uri, startUs: Long, endUs: Long, sensitivity: Double, cancelled: () -> Boolean): List<Double> = withContext(Dispatchers.Default) {
        val frames = ArrayList<Gray>(); val times = ArrayList<Long>()
        FrameReader(context, uri).read(startUs, endUs, 10.0, 96, cancelled) { f -> frames += gray(f); times += f.ptsUs; true }
        SceneDetect.cuts(frames, sensitivity).map { times[it] / 1e6 }
    }

    /** Tracks a point given in normalised frame coords; returns (sourceTimeUs, x, y, confidence) normalised. */
    suspend fun track(uri: Uri, startUs: Long, endUs: Long, x: Double, y: Double, fps: Double, progress: (Float) -> Unit, cancelled: () -> Boolean): List<DoubleArray> = withContext(Dispatchers.Default) {
        val frames = ArrayList<Gray>(); val times = ArrayList<Long>()
        FrameReader(context, uri).read(startUs, endUs, fps, 320, cancelled) { f -> frames += gray(f); times += f.ptsUs; progress(0.6f * (f.ptsUs - startUs) / (endUs - startUs)); true }
        if (frames.isEmpty()) return@withContext emptyList()
        val w = frames[0].w; val h = frames[0].h
        val r = Tracker.track(frames, x * w, y * h, maxOf(6, w / 32), maxOf(6, w / 24))
        progress(1f)
        r.mapIndexed { i, t -> doubleArrayOf(times[i].toDouble(), t.x / w, t.y / h, t.confidence) }
    }

    /** Stabilisation corrections per sampled frame: (timeUs, dx, dy in normalised units, rotation deg) and crop scale. */
    suspend fun stabilize(uri: Uri, startUs: Long, endUs: Long, fps: Double, smoothness: Double, progress: (Float) -> Unit, cancelled: () -> Boolean): Pair<List<DoubleArray>, Double> = withContext(Dispatchers.Default) {
        val frames = ArrayList<Gray>(); val times = ArrayList<Long>()
        FrameReader(context, uri).read(startUs, endUs, fps, 240, cancelled) { f -> frames += gray(f); times += f.ptsUs; progress(0.6f * (f.ptsUs - startUs) / (endUs - startUs)); true }
        if (frames.size < 3) return@withContext emptyList<DoubleArray>() to 1.0
        val m = Stabilizer.motions(frames)
        val c = Stabilizer.corrections(m, smoothness)
        val w = frames[0].w; val h = frames[0].h
        progress(1f)
        c.mapIndexed { i, mo -> doubleArrayOf(times[i].toDouble(), mo.dx / w, mo.dy / h, Math.toDegrees(mo.rot)) } to Stabilizer.cropScale(c, w, h)
    }

    /** Subject-following reframe path from MODNet mattes: (timeUs, cx, cy) normalised. */
    suspend fun reframePath(uri: Uri, startUs: Long, endUs: Long, matting: MattingEngine, progress: (Float) -> Unit, cancelled: () -> Boolean): List<DoubleArray> = withContext(Dispatchers.Default) {
        val cents = ArrayList<Pair<Double, Double>?>(); val times = ArrayList<Long>()
        FrameReader(context, uri).read(startUs, endUs, 4.0, 256, cancelled) { f ->
            val (alpha, size) = matting.matteOf(f)
            cents += Reframe.centroid(alpha, size.first, size.second); times += f.ptsUs
            progress(((f.ptsUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f)); true
        }
        Reframe.path(cents).mapIndexed { i, (x, y) -> doubleArrayOf(times[i].toDouble(), x, y) }
    }

    /**
     * Removes a moving object inside a mask (normalised polygon) by temporal median fill; writes
     * the result as a PNG sequence overlay (frames with the filled region only) for compositing.
     */
    suspend fun temporalFill(uri: Uri, startUs: Long, endUs: Long, fps: Double, maskPoly: List<Pair<Double, Double>>, outDir: File,
                             progress: (Float) -> Unit, cancelled: () -> Boolean): Int = withContext(Dispatchers.Default) {
        outDir.mkdirs()
        val frames = ArrayList<SmallFrame>()
        FrameReader(context, uri).read(startUs, endUs, fps, 720, cancelled) { f -> frames += f; progress(0.5f * (f.ptsUs - startUs) / (endUs - startUs)); frames.size < 600 }
        if (frames.isEmpty()) return@withContext 0
        val w = frames[0].width; val h = frames[0].height
        val mask = BooleanArray(w * h) { p -> inside(maskPoly, (p % w + 0.5) / w, (p / w + 0.5) / h) }
        val out = TemporalFill.fill(frames.map { it.argb }, frames.map { mask })
        out.forEachIndexed { i, px ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(IntArray(w * h) { p -> if (mask[p]) px[p] else 0 }, 0, w, 0, 0, w, h)
            FileOutputStream(File(outDir, "f_%06d.png".format(Math.round(frames[i].ptsUs / 1e6 * fps)))).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            progress(0.5f + 0.5f * i / out.size)
        }
        out.size
    }

    private fun inside(poly: List<Pair<Double, Double>>, x: Double, y: Double): Boolean {
        var c = false; var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]; val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi) c = !c
            j = i
        }
        return c
    }
}

/** Facade used by the app. */
class AiServices(context: Context) {
    val models = ModelManager.shared(context)
    val captions = CaptionEngine(models)
    val matting = MattingEngine(context, models)
    val separation = SeparationEngine(models)
    val planner = PlannerEngine(context, models)
    val video = VideoTools(context)
}
