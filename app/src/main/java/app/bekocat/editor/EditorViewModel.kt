package app.bekocat.editor

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bekocat.core.audio.Analysis
import app.bekocat.core.timeline.RenderPlanner
import app.bekocat.core.timeline.Animatable
import app.bekocat.core.timeline.Asset
import app.bekocat.core.timeline.AssetKind
import app.bekocat.core.timeline.CaptionTrack
import app.bekocat.core.timeline.Clip
import app.bekocat.core.timeline.ClipKind
import app.bekocat.core.timeline.DirectCommandParser
import app.bekocat.core.timeline.EditException
import app.bekocat.core.timeline.History
import app.bekocat.core.timeline.Interpolation
import app.bekocat.core.timeline.Keyframe
import app.bekocat.core.timeline.Plan
import app.bekocat.core.timeline.PlanApplier
import app.bekocat.core.timeline.Project
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.Sequence
import app.bekocat.core.timeline.ShapeStyle
import app.bekocat.core.timeline.ShapeType
import app.bekocat.core.timeline.TextStyle
import app.bekocat.core.timeline.Time
import app.bekocat.core.timeline.TimelineEditor
import app.bekocat.core.timeline.Track
import app.bekocat.core.timeline.TrackKind
import app.bekocat.data.ProjectRepository
import app.bekocat.engine.export.ExportResult
import app.bekocat.engine.export.ExportSettings
import app.bekocat.engine.export.Exporter
import app.bekocat.engine.preview.PreviewEngine
import app.bekocat.media.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File
import app.bekocat.ai.AiServices
import app.bekocat.ai.ModelManager
import app.bekocat.core.audio.ArrayPcmSource
import app.bekocat.core.audio.Loudness
import app.bekocat.core.export.CaptionIO
import app.bekocat.core.export.Wav
import app.bekocat.core.timeline.AiJob
import app.bekocat.core.timeline.AiMatte
import app.bekocat.core.timeline.AssistantParser
import app.bekocat.core.timeline.AssistantPlan
import app.bekocat.core.timeline.AudioRole
import app.bekocat.core.timeline.BrushStroke
import app.bekocat.core.timeline.CaptionSegment
import app.bekocat.core.timeline.ColorAdjust
import app.bekocat.core.timeline.ColorMath
import app.bekocat.core.timeline.EffectCatalog
import app.bekocat.core.timeline.EffectInstance
import app.bekocat.core.timeline.FillPatch
import app.bekocat.core.timeline.FitMode
import app.bekocat.core.timeline.FrameRate
import app.bekocat.core.timeline.Geometry
import app.bekocat.core.timeline.Intent
import app.bekocat.core.timeline.LookCatalog
import app.bekocat.core.timeline.Marker
import app.bekocat.core.timeline.MotionTrack
import app.bekocat.core.timeline.ProjectCodec
import app.bekocat.core.timeline.Stabilization
import app.bekocat.core.timeline.StemMix
import app.bekocat.core.timeline.Transform
import app.bekocat.core.timeline.Transition
import app.bekocat.core.timeline.TransitionKind
import app.bekocat.engine.audio.FilePcmSource
import app.bekocat.engine.export.ExportCancelled
import app.bekocat.engine.export.ExportKind

enum class Panel { NONE, MEDIA, INSPECTOR, KEYFRAMES, TEXT, CAPTIONS, COLOR, EFFECTS, TRANSITION, MASK, KEY, AUDIO, MIXER, AI, EXPORT, PROJECT, TRACKS, SPEED, SHAPES }

data class ExportJob(val id: Int, val label: String, val settings: ExportSettings, val progress: Double = 0.0, val stage: String = "queued",
                     val result: ExportResult? = null, val uri: Uri? = null, val error: String? = null, val done: Boolean = false)

data class EditorState(
    val project: Project? = null,
    val selection: Set<String> = emptySet(),
    val primary: String? = null,
    val playhead: Double = 0.0,
    val playing: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val ripple: Boolean = true,
    val snapping: Boolean = true,
    val autoKey: Boolean = false,
    val pxPerSecond: Float = 90f,
    val panel: Panel = Panel.NONE,
    val busy: String? = null,
    val message: String? = null,
    val thumbsVersion: Int = 0,
    val waveVersion: Int = 0,
    val missingAssetIds: Set<String> = emptySet(),
    val exports: List<ExportJob> = emptyList(),
    val stats: String = "",
    val loop: Boolean = false,
    val inPoint: Double? = null,
    val outPoint: Double? = null,
    val timelineHeightDp: Int = 260,
    val beforeAfter: Boolean = false,
) {
    val selected: Clip? get() = primary?.let { project?.sequence?.findClip(it)?.second }
    val selectedTrack: Track? get() = primary?.let { project?.sequence?.findClip(it)?.first }
}

/** Generic accessor for an animatable property of a clip (for inspector rows and the graph editor). */
data class Prop(val key: String, val ar: String, val en: String, val min: Double, val max: Double, val def: Double,
                val get: (Clip) -> Animatable, val set: (Clip, Animatable) -> Clip)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val repo = ProjectRepository(app)
    val preview = PreviewEngine(app)
    val thumbs = ThumbnailCache(app)
    val peaks = HashMap<String, FloatArray>()
    private var history: History? = null
    private var saveJob: Job? = null
    private var backupJob: Job? = null
    private val exporter = Exporter(app)
    private var exportCounter = 0
    private var clipboard: List<Clip> = emptyList()

    var trackPanelFor: String? = null
    var gizmoDirty by androidx.compose.runtime.mutableStateOf(false)
    private var transient: Project? = null
    private var gizmoJob: Job? = null

    /** Commits the live gizmo/slider preview as one undoable step. */
    fun commitGizmo() {
        val t = transient ?: return
        gizmoDirty = false; transient = null; gizmoJob?.cancel()
        commit("transform") { t }
    }

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    init {
        preview.listener = object : PreviewEngine.Listener {
            override fun onTime(seconds: Double) { _state.update { it.copy(playhead = seconds) } }
            override fun onPlaying(playing: Boolean) { _state.update { it.copy(playing = playing) } }
            override fun onStats(fps: Double, renderMs: Double, previewScale: Double) {
                _state.update { it.copy(stats = "%.0f fps · %.0f ms · %d%%".format(fps, renderMs, (previewScale * 100).toInt())) }
            }
            override fun onError(message: String) { _state.update { it.copy(message = message) } }
        }
    }

    fun message(m: String?) = _state.update { it.copy(message = m) }

    // ---------------- open / save ----------------
    fun open(projectId: String) {
        if (history?.current?.id == projectId) return
        viewModelScope.launch {
            val p = runCatching { repo.load(projectId) }.getOrElse { e -> message(e.message); return@launch }
            history = History(p)
            val pending = withContext(Dispatchers.IO) { repo.pendingJournal(projectId) }
            val missing = withContext(Dispatchers.IO) { p.assets.filter { it.kind != AssetKind.FONT && !repo.isReachable(it) }.map { it.id }.toSet() }
            _state.update { it.copy(project = p, missingAssetIds = missing,
                message = when {
                    pending.isNotEmpty() -> "تم استرجاع المشروع بعد إغلاق غير متوقع (${pending.size} تعديل) · Recovered after unexpected exit"
                    missing.isNotEmpty() -> "وسائط مفقودة: ${missing.size} — أعد ربطها من لوحة الوسائط · Missing media: relink in Media"
                    else -> null
                }) }
            preview.setProject(p)
            loadMediaCaches(p)
            backupJob?.cancel()
            backupJob = viewModelScope.launch { while (true) { delay(5 * 60_000); history?.current?.let { withContext(Dispatchers.IO) { repo.periodicBackup(it) } } } }
        }
    }

    private fun loadMediaCaches(p: Project) {
        viewModelScope.launch {
            for (a in p.assets) {
                if (a.kind == AssetKind.VIDEO || a.kind == AssetKind.IMAGE) if (thumbs.peek(a.id) == null) { thumbs.load(a); _state.update { it.copy(thumbsVersion = it.thumbsVersion + 1) } }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (a in p.assets) {
                if (!a.hasAudio || peaks.containsKey(a.id)) continue
                val src = runCatching { preview.audio.conform.source(a.uri) }.getOrNull() ?: continue
                val frames = src.frames.toInt().coerceAtMost(48000 * 60 * 30)
                val buckets = (frames / 480).coerceIn(100, 20000) // ~100 buckets per second
                val out = FloatArray(buckets * 2)
                val chunk = FloatArray(4096 * 2)
                var b = 0
                val per = frames.toDouble() / buckets
                while (b < buckets) {
                    val s = (b * per).toLong(); val n = per.toInt().coerceIn(1, 4096)
                    src.read(s, chunk, n)
                    var lo = 0f; var hi = 0f
                    for (i in 0 until n) { val m = (chunk[2 * i] + chunk[2 * i + 1]) / 2; if (m < lo) lo = m; if (m > hi) hi = m }
                    out[2 * b] = lo; out[2 * b + 1] = hi; b++
                }
                peaks[a.id] = out
                _state.update { it.copy(waveVersion = it.waveVersion + 1) }
            }
        }
    }

    private fun scheduleSave(p: Project, label: String) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.journal(p.id, label) } }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            runCatching { repo.save(p) }.onFailure { e -> message("Save failed: ${e.message}") }
        }
    }

    // ---------------- commits ----------------
    private fun editor(p: Project) = TimelineEditor(p.assets.associateBy { it.id }, repo.ids)

    fun commit(label: String, op: (Project) -> Project) {
        val h = history ?: return
        if (label != "transform") { transient = null; gizmoDirty = false; gizmoJob?.cancel() }
        try {
            h.commit(label, System.currentTimeMillis(), op)
        } catch (e: EditException) { message(e.message); preview.setProject(h.current); return }
        catch (e: IllegalArgumentException) { message(e.message); preview.setProject(h.current); return }
        afterChange(label)
    }

    fun edit(label: String, f: (Sequence, TimelineEditor) -> Sequence) = commit(label) { p -> p.copy(sequence = f(p.sequence, editor(p))) }

    /** Live preview of an uncommitted change (slider drag). The next commit or undo replaces it. */
    fun preview(op: (Project) -> Project) {
        val p = history?.current ?: return
        val base = transient ?: p
        runCatching { op(base) }.onSuccess {
            transient = it
            preview.setProject(it); _state.update { s -> s.copy(project = it) }
            if (gizmoDirty) { gizmoJob?.cancel(); gizmoJob = viewModelScope.launch { delay(900); commitGizmo() } }
        }
    }

    private fun afterChange(label: String?, msg: String? = null) {
        val h = history ?: return
        val p = h.current
        _state.update {
            val sel = it.selection.filter { id -> p.sequence.findClip(id) != null }.toSet()
            it.copy(project = p, canUndo = h.canUndo, canRedo = h.canRedo, undoLabel = h.undoLabel, selection = sel,
                primary = it.primary?.takeIf { id -> id in sel }, message = msg ?: it.message)
        }
        preview.setProject(p)
        if (label != null) scheduleSave(p, label)
    }

    fun undo() { val h = history ?: return; val l = h.undoLabel ?: return; h.undo(); afterChange("undo:$l", "↶ $l") }
    fun redo() { val h = history ?: return; val l = h.redoLabel ?: return; h.redo(); afterChange("redo:$l", "↷ $l") }

    // ---------------- transport ----------------
    val playheadTime: Time get() = (_state.value.project?.sequence?.frameRate ?: FrameRate.FPS_30).snap(Rational.fromDouble(_state.value.playhead, 1_000_000))

    fun seek(sec: Double) { preview.seek(sec); _state.update { it.copy(playhead = preview.timeSec) } }
    fun togglePlay() { if (preview.playing) preview.pause() else { applyLoop(); preview.play() } }
    fun stepFrame(d: Int) {
        val p = _state.value.project ?: return
        preview.pause()
        val fr = p.sequence.frameRate
        seek(fr.timeOf((fr.frameAt(Rational.fromDouble(_state.value.playhead, 1_000_000)) + d).coerceAtLeast(0)).toDouble())
    }
    fun setZoom(px: Float) = _state.update { it.copy(pxPerSecond = px.coerceIn(8f, 2400f)) }
    fun toggle(f: (EditorState) -> EditorState) = _state.update(f)
    fun openPanel(p: Panel) = _state.update { it.copy(panel = if (it.panel == p) Panel.NONE else p) }
    fun setInOut(isIn: Boolean) = _state.update { if (isIn) it.copy(inPoint = it.playhead) else it.copy(outPoint = it.playhead) }
    private fun applyLoop() {
        val s = _state.value
        preview.loopRange = if (s.loop) (s.inPoint ?: 0.0) to (s.outPoint ?: (s.project?.sequence?.duration?.toDouble() ?: 0.0)) else null
    }

    // ---------------- selection ----------------
    fun select(clipId: String?, additive: Boolean = false) = _state.update { s ->
        if (clipId == null) s.copy(selection = emptySet(), primary = null)
        else if (additive) { val sel = if (clipId in s.selection) s.selection - clipId else s.selection + clipId; s.copy(selection = sel, primary = clipId.takeIf { it in sel } ?: sel.firstOrNull()) }
        else {
            val p = s.project
            val sel = if (p != null) editor(p).expandToGroups(p.sequence, setOf(clipId)) else setOf(clipId)
            s.copy(selection = sel, primary = clipId)
        }
    }

    // ---------------- media ----------------
    fun importMedia(uris: List<Uri>, asOverlay: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = "…") }
            val assets = uris.mapNotNull { u -> runCatching { repo.probe(u) }.onFailure { e -> message(e.message) }.getOrNull() }
            val at = playheadTime
            commit("import") { p0 ->
                var p = p0.copy(assets = p0.assets + assets)
                val ed = editor(p)
                var s = p.sequence
                for (a in assets) {
                    when (a.kind) {
                        AssetKind.FONT, AssetKind.LUT -> {}
                        AssetKind.AUDIO -> s = ed.append(s, s.tracks.first { it.kind == TrackKind.AUDIO }.id, Clip(repo.ids.next("clip"), a.id, a.displayName, Rational.ZERO, Rational.ZERO, a.duration!!))
                        else -> {
                            val dur = a.duration ?: Rational.seconds(3)
                            val clip = Clip(repo.ids.next("clip"), a.id, a.displayName, Rational.ZERO, Rational.ZERO, dur)
                            s = if (asOverlay) {
                                val track = s.tracks.firstOrNull { t -> t.kind == TrackKind.OVERLAY && t.clips.none { it.timelineStart < at + dur && at < it.timelineEnd } }?.id
                                    ?: run { s = ed.addTrack(s, TrackKind.OVERLAY, "V${s.tracks.count { it.kind != TrackKind.AUDIO } + 1}", s.tracks.indexOfLast { it.kind == TrackKind.OVERLAY } + 1); s.tracks.last { it.kind == TrackKind.OVERLAY }.id }
                                ed.overwrite(s, track, clip.copy(transform = clip.transform.copy(scale = Animatable.of(0.4))), at)
                            } else ed.append(s, "v1", clip)
                        }
                    }
                }
                p.copy(sequence = s)
            }
            _state.update { it.copy(busy = null) }
            history?.current?.let { loadMediaCaches(it) }
        }
    }

    fun relink(assetId: String, uri: Uri) {
        viewModelScope.launch {
            val a = runCatching { repo.probe(uri) }.getOrElse { message(it.message); return@launch }
            commit("relink") { p -> p.copy(assets = p.assets.map { if (it.id == assetId) a.copy(id = assetId) else it }) }
            _state.update { it.copy(missingAssetIds = it.missingAssetIds - assetId) }
        }
    }

    fun replaceSelected(uri: Uri, keepAttributes: Boolean) {
        val sel = _state.value.primary ?: return
        viewModelScope.launch {
            val a = runCatching { repo.probe(uri) }.getOrElse { message(it.message); return@launch }
            commit("replace") { p -> p.copy(assets = p.assets + a, sequence = editor(p.copy(assets = p.assets + a)).replaceMedia(p.sequence, sel, a, keepAttributes)) }
            history?.current?.let { loadMediaCaches(it) }
        }
    }

    // ---------------- basic edits ----------------
    fun split() { val at = playheadTime; val sel = _state.value.selection; edit("split") { s, ed -> if (sel.isEmpty()) ed.splitAll(s, at) else sel.fold(s) { acc, id -> runCatching { ed.split(acc, id, at) }.getOrDefault(acc) } } }
    fun deleteSelected() { val sel = _state.value.selection; if (sel.isEmpty()) return; val r = _state.value.ripple; edit("delete") { s, ed -> ed.delete(s, sel, r) }; select(null) }
    fun trimStartToPlayhead() { val id = _state.value.primary ?: return; val at = playheadTime; val r = _state.value.ripple; edit("trim-in") { s, ed -> ed.trimStart(s, id, at, r) } }
    fun trimEndToPlayhead() { val id = _state.value.primary ?: return; val at = playheadTime; val r = _state.value.ripple; edit("trim-out") { s, ed -> ed.trimEnd(s, id, at, r) } }
    fun duplicate() { val id = _state.value.primary ?: return; edit("duplicate") { s, ed -> ed.duplicate(s, id) } }
    fun copy() { val p = _state.value.project ?: return; clipboard = _state.value.selection.mapNotNull { p.sequence.findClip(it)?.second }; message("📋 ${clipboard.size}") }
    fun paste(insert: Boolean) {
        val p = _state.value.project ?: return
        if (clipboard.isEmpty()) return
        val track = _state.value.selectedTrack?.id ?: p.sequence.findClip(clipboard[0].id)?.first?.id ?: "v1"
        val at = playheadTime
        edit("paste") { s, ed -> ed.paste(s, clipboard, track, at, insert) }
    }
    fun pasteAttributes() { val src = clipboard.firstOrNull() ?: return; val to = _state.value.selection; edit("paste-attributes") { s, ed -> ed.pasteAttributes(s, src.id, to) } }
    fun group() { val sel = _state.value.selection; edit("group") { s, ed -> ed.group(s, sel) } }
    fun ungroup() { val sel = _state.value.selection; edit("ungroup") { s, ed -> ed.ungroup(s, sel) } }
    fun nest() {
        val sel = _state.value.selection; if (sel.isEmpty()) return
        commit("nest") { p -> val (main, comp) = editor(p).nest(p.sequence, sel, "Precomp ${p.compositions.size + 1}"); p.copy(sequence = main, compositions = p.compositions + comp) }
        select(null)
    }
    fun moveSelectedBy(deltaSec: Double) {
        val sel = _state.value.selection; if (sel.isEmpty()) return
        val p = _state.value.project ?: return
        val d = p.sequence.frameRate.snap(Rational.fromDouble(deltaSec, 1_000_000))
        edit("move") { s, ed -> ed.moveMany(s, sel, d) }
    }
    fun moveToTrack(clipId: String, trackId: String, startSec: Double) {
        val p = _state.value.project ?: return
        var t = p.sequence.frameRate.snap(Rational.fromDouble(startSec.coerceAtLeast(0.0), 1_000_000))
        if (_state.value.snapping) t = editor(p).snap(p.sequence, t, Rational.fromDouble(8.0 / _state.value.pxPerSecond, 1_000_000), playheadTime, setOf(clipId))
        edit("move") { s, ed -> ed.move(s, clipId, t, trackId) }
    }
    fun trimEdge(clipId: String, start: Boolean, toSec: Double) {
        val p = _state.value.project ?: return
        var t = p.sequence.frameRate.snap(Rational.fromDouble(toSec.coerceAtLeast(0.0), 1_000_000))
        if (_state.value.snapping) t = editor(p).snap(p.sequence, t, Rational.fromDouble(8.0 / _state.value.pxPerSecond, 1_000_000), playheadTime, setOf(clipId))
        val r = _state.value.ripple
        edit(if (start) "trim-in" else "trim-out") { s, ed -> if (start) ed.trimStart(s, clipId, t, r) else ed.trimEnd(s, clipId, t, r) }
    }
    fun slip(deltaSec: Double) { val id = _state.value.primary ?: return; edit("slip") { s, ed -> ed.slip(s, id, Rational.fromDouble(deltaSec, 1_000_000)) } }
    fun slide(deltaSec: Double) { val id = _state.value.primary ?: return; edit("slide") { s, ed -> ed.slide(s, id, Rational.fromDouble(deltaSec, 1_000_000)) } }
    fun roll(deltaSec: Double) { val id = _state.value.primary ?: return; edit("roll") { s, ed -> ed.roll(s, id, Rational.fromDouble(deltaSec, 1_000_000)) } }
    fun insertSelectedAtPlayhead(overwrite: Boolean) {
        val c = _state.value.selected ?: return; val t = _state.value.selectedTrack ?: return; val at = playheadTime
        edit(if (overwrite) "overwrite" else "insert") { s, ed -> val n = c.copy(id = repo.ids.next("clip")); if (overwrite) ed.overwrite(s, t.id, n, at) else ed.insert(s, t.id, n, at) }
    }
    fun detachAudio() { val id = _state.value.primary ?: return; edit("detach-audio") { s, ed -> ed.detachAudio(s, id, s.tracks.first { it.kind == TrackKind.AUDIO }.id) } }
    fun freezeFrame() { val id = _state.value.primary ?: return; val at = playheadTime; edit("freeze") { s, ed -> ed.freezeFrame(s, id, at, Rational.seconds(2)) } }
    fun setSpeed(x: Double) { val id = _state.value.primary ?: return; edit("speed") { s, ed -> ed.clearTimeRemap(s, id).let { ed.setSpeed(it, id, Rational.fromDouble(x, 100), true) } } }
    fun speedRamp(points: List<Pair<Double, Double>>) { val id = _state.value.primary ?: return; edit("speed-ramp") { s, ed -> ed.speedRamp(s, id, points) } }
    fun setReversed(r: Boolean) { val id = _state.value.primary ?: return; edit("reverse") { s, ed -> ed.setReversed(s, id, r) } }
    fun addMarker(label: String = "") { val at = playheadTime; edit("marker") { s, ed -> ed.addMarker(s, at, label) } }

    fun updateSelected(label: String, f: (Clip) -> Clip) { val sel = _state.value.selection.ifEmpty { setOfNotNull(_state.value.primary) }; if (sel.isEmpty()) return; edit(label) { s, ed -> sel.fold(s) { acc, id -> ed.updateClip(acc, id, f) } } }
    fun previewSelected(f: (Clip) -> Clip) { val sel = _state.value.selection.ifEmpty { setOfNotNull(_state.value.primary) }; preview { p -> p.copy(sequence = sel.fold(p.sequence) { acc, id -> editor(p).updateClip(acc, id, f) }) } }
    fun updateTrack(id: String, f: (Track) -> Track) = edit("track") { s, ed -> ed.updateTrack(s, id, f) }
    fun updateSequence(label: String, f: (Sequence) -> Sequence) = commit(label) { p -> p.copy(sequence = f(p.sequence)) }
    fun addTrack(kind: TrackKind) = edit("add-track") { s, ed -> ed.addTrack(s, kind, (if (kind == TrackKind.AUDIO) "A" else "V") + (s.tracks.count { (it.kind == TrackKind.AUDIO) == (kind == TrackKind.AUDIO) } + 1)) }
    fun moveTrack(id: String, dir: Int) = edit("move-track") { s, ed -> ed.moveTrack(s, id, s.tracks.indexOfFirst { it.id == id } + dir) }
    fun removeTrack(id: String) = edit("remove-track") { s, ed -> ed.removeTrack(s, id) }

    // ---------------- keyframes ----------------
    fun localTime(c: Clip): Time = (playheadTime - c.timelineStart).let { if (it.isNegative) Rational.ZERO else if (it > c.duration) c.duration else it }

    fun setProp(prop: Prop, v: Double, commit: Boolean) {
        val autoKey = _state.value.autoKey
        val f: (Clip) -> Clip = { c -> val a = prop.get(c); prop.set(c, a.set(v, localTime(c), autoKey || a.keyframes.isNotEmpty())) }
        if (commit) updateSelected(prop.key, f) else previewSelected(f)
    }

    fun toggleKey(prop: Prop) = updateSelected("key:${prop.key}") { c ->
        val a = prop.get(c); val t = localTime(c)
        if (a.keyframes.any { it.time == t }) prop.set(c, a.withoutKeyframeAt(t))
        else prop.set(c, a.withKeyframe(Keyframe(t, a.valueAt(t), Interpolation.BEZIER)))
    }

    fun setKeyInterpolation(prop: Prop, interp: Interpolation, ease: List<Double>? = null) = updateSelected("ease:${prop.key}") { c ->
        val a = prop.get(c); val t = localTime(c)
        val idx = a.keyframes.indexOfLast { it.time <= t }.coerceAtLeast(0)
        prop.set(c, a.copy(keyframes = a.keyframes.mapIndexed { i, k ->
            when {
                ease == null && i == idx -> k.copy(interpolation = interp)
                ease != null && i == idx -> k.copy(interpolation = interp, outX = ease[0], outY = ease[1])
                ease != null && i == idx + 1 -> k.copy(inX = ease[2], inY = ease[3])
                else -> k
            }
        }))
    }

    fun replaceKeyframes(prop: Prop, keys: List<Keyframe>, commit: Boolean) {
        val f: (Clip) -> Clip = { c -> prop.set(c, prop.get(c).copy(keyframes = keys.sortedBy { it.time })) }
        if (commit) updateSelected("graph:${prop.key}", f) else previewSelected(f)
    }

    fun setExpression(prop: Prop, expr: String?) = updateSelected("expr:${prop.key}") { c -> prop.set(c, prop.get(c).copy(expression = expr?.ifBlank { null })) }

    fun jumpKey(prop: Prop, dir: Int) {
        val c = _state.value.selected ?: return
        val t = localTime(c)
        val ks = prop.get(c).keyframes
        val k = if (dir > 0) ks.firstOrNull { it.time > t } else ks.lastOrNull { it.time < t }
        k?.let { seek((c.timelineStart + it.time).toDouble()) }
    }

    // ---------------- generated layers ----------------
    fun addText(text: String, style: TextStyle? = null) {
        if (text.isBlank()) return
        val at = playheadTime
        val clip = Clip(repo.ids.next("text"), null, text.take(24), at, Rational.ZERO, Rational.seconds(3), kind = ClipKind.TEXT,
            text = (style ?: TextStyle(text)).copy(text = text))
        placeOnFreeTrack(clip, TrackKind.TEXT, "text")
        select(clip.id)
    }

    fun addShape(type: ShapeType) {
        val at = playheadTime
        val clip = Clip(repo.ids.next("shape"), null, type.name.lowercase(), at, Rational.ZERO, Rational.seconds(3), kind = ClipKind.SHAPE, shape = ShapeStyle(type))
        placeOnFreeTrack(clip, TrackKind.OVERLAY, "shape"); select(clip.id)
    }

    fun addSolid(argb: Long) {
        val clip = Clip(repo.ids.next("solid"), null, "solid", playheadTime, Rational.ZERO, Rational.seconds(3), kind = ClipKind.SOLID, solidArgb = argb)
        placeOnFreeTrack(clip, TrackKind.OVERLAY, "solid"); select(clip.id)
    }

    fun addAdjustment() {
        val clip = Clip(repo.ids.next("adj"), null, "Adjustment", playheadTime, Rational.ZERO, Rational.seconds(5), kind = ClipKind.ADJUSTMENT)
        placeOnFreeTrack(clip, TrackKind.OVERLAY, "adjustment"); select(clip.id)
    }

    fun addNull() {
        val clip = Clip(repo.ids.next("null"), null, "Null", playheadTime, Rational.ZERO, Rational.seconds(5), kind = ClipKind.NULL_OBJECT)
        placeOnFreeTrack(clip, TrackKind.OVERLAY, "null"); select(clip.id)
    }

    private fun placeOnFreeTrack(clip: Clip, kind: TrackKind, label: String) = edit(label) { s0, ed ->
        var s = s0
        val free = s.tracks.firstOrNull { t -> t.kind == kind && !t.locked && t.clips.none { it.timelineStart < clip.timelineEnd && clip.timelineStart < it.timelineEnd } }
        val tid = free?.id ?: run {
            s = ed.addTrack(s, kind, (if (kind == TrackKind.TEXT) "T" else "V") + (s.tracks.count { it.kind == kind } + 1), s.tracks.indexOfLast { it.kind == kind } + 1)
            s.tracks.last { it.kind == kind }.id
        }
        ed.overwrite(s, tid, clip, clip.timelineStart)
    }

    // ---------------- captions ----------------
    fun setCaptions(track: CaptionTrack) = updateSequence("captions") { s -> s.copy(captions = listOf(track) + s.captions.filterNot { it.id == track.id }) }
    fun updateCaptions(f: (CaptionTrack) -> CaptionTrack) = updateSequence("captions-edit") { s -> s.copy(captions = s.captions.mapIndexed { i, c -> if (i == 0) f(c) else c }) }

    // ---------------- assistant ----------------
    fun planCommand(text: String): Plan? {
        val s = _state.value; val p = s.project ?: return null
        return DirectCommandParser.parse(text, p.sequence, s.primary, playheadTime)
    }

    // ---------------- audio analysis ----------------
    fun detectBeats() {
        val p = _state.value.project ?: return
        _state.update { it.copy(busy = "♫") }
        viewModelScope.launch(Dispatchers.Default) {
            val music = p.sequence.tracks.filter { it.kind == TrackKind.AUDIO }.flatMap { it.clips }.firstOrNull()
            val mixer = preview.audio.mixer(p)
            val dur = (music?.timelineEnd?.toDouble() ?: p.sequence.duration.toDouble()).coerceAtMost(600.0)
            val buf = mixer.mix(0.0, dur, applyMaster = false)
            val (bpm, beats) = Analysis.beats(buf, p.sequence.audioSampleRate.toDouble())
            withContext(Dispatchers.Main) {
                edit("beats") { s, ed -> ed.addBeatMarkers(s, beats.map { Rational.fromDouble(it, 1000) }) }
                _state.update { it.copy(busy = null, message = "♫ %.1f BPM · %d".format(bpm, beats.size)) }
            }
        }
    }

    /** Silence removal on the main track using the mixed dialogue: splits and ripple-deletes silent ranges. */
    fun removeSilence(thresholdDb: Double, minSilence: Double, padding: Double) {
        val p = _state.value.project ?: return
        _state.update { it.copy(busy = "…") }
        viewModelScope.launch(Dispatchers.Default) {
            val dur = p.sequence.duration.toDouble()
            val buf = preview.audio.mixer(p).mix(0.0, dur, applyMaster = false)
            val ranges = Analysis.silences(buf, p.sequence.audioSampleRate.toDouble(), thresholdDb, minSilence, padding)
            withContext(Dispatchers.Main) {
                _state.update { it.copy(busy = null) }
                if (ranges.isEmpty()) { message("لا يوجد صمت بهذه العتبة · No silence found"); return@withContext }
                commit("remove-silence (${ranges.size})") { pr ->
                    val ed = editor(pr)
                    var s = pr.sequence
                    for ((a, b) in ranges.reversed()) {
                        val ta = s.frameRate.snap(Rational.fromDouble(a, 1_000_000)); val tb = s.frameRate.snap(Rational.fromDouble(b, 1_000_000))
                        if (tb <= ta) continue
                        s = ed.splitAll(s, ta); s = ed.splitAll(s, tb)
                        val inside = s.tracks.filter { !it.locked }.flatMap { t -> t.clips.filter { it.timelineStart >= ta && it.timelineEnd <= tb }.map { it.id } }.toSet()
                        if (inside.isNotEmpty()) {
                            s = ed.delete(s, inside, ripple = false)
                            // Close the gap on every track (ripple across tracks).
                            val d = tb - ta
                            s = s.copy(tracks = s.tracks.map { t -> if (t.locked) t else t.copy(clips = t.clips.map { c -> if (c.timelineStart >= tb) c.copy(timelineStart = c.timelineStart - d) else c }) })
                        }
                    }
                    pr.copy(sequence = s)
                }
            }
        }
    }

    // ---------------- export ----------------
    fun enqueueExport(label: String, settings: ExportSettings) {
        val job = ExportJob(++exportCounter, label, settings)
        _state.update { it.copy(exports = it.exports + job) }
        if (_state.value.exports.count { !it.done } == 1) runNextExport()
    }

    private fun runNextExport() {
        val job = _state.value.exports.firstOrNull { !it.done && it.stage == "queued" } ?: return
        val p = history?.current ?: return
        preview.pause()
        fun upd(f: (ExportJob) -> ExportJob) = _state.update { s -> s.copy(exports = s.exports.map { if (it.id == job.id) f(it) else it }) }
        viewModelScope.launch(Dispatchers.Default) {
            val outDir = File(getApplication<Application>().cacheDir, "exports")
            try {
                val res = exporter.run(p, job.settings, outDir) { prog, stage -> upd { it.copy(progress = prog, stage = stage) } }
                val uri = publish(res)
                upd { it.copy(progress = 1.0, stage = "done", result = res, uri = uri, done = true) }
            } catch (e: ExportCancelled) {
                upd { it.copy(stage = "cancelled", done = true) }
            } catch (e: Throwable) {
                upd { it.copy(stage = "failed", error = e.message ?: e.javaClass.simpleName, done = true) }
            }
            withContext(Dispatchers.Main) { runNextExport() }
        }
    }

    fun cancelExport() = exporter.cancel()

    private fun publish(r: ExportResult): Uri? {
        val ctx = getApplication<Application>()
        val (collection, dir) = when {
            r.mime.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            r.mime.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            r.mime == "image/gif" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, r.file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, r.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Bekocat")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val res = ctx.contentResolver
        val uri = res.insert(collection, values) ?: return null
        res.openOutputStream(uri)?.use { os -> r.file.inputStream().use { it.copyTo(os) } }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        res.update(uri, values, null, null)
        r.file.delete()
        return uri
    }

    // ---------------- project package ----------------
    fun exportPackage(dest: Uri) {
        val p = history?.current ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.exportPackage(p, dest) }.onSuccess { message("✓ .bekocat") }.onFailure { message(it.message) }
        }
    }


    // =====================================================================
    // AI & analysis
    // =====================================================================
    val ai = AiServices(app)
    @Volatile private var jobCancelled = false
    private fun appCtx() = getApplication<Application>()
    private fun busy(label: String?) = _state.update { it.copy(busy = label) }

    fun downloadModel(id: String) = ai.models.startDownload(id)

    private fun primaryAudioSource(p: Project) = viewModelScope.run { null }

    /** Mixed program audio of the project (pre-master) as 16 kHz mono for speech models. */
    private fun programSpeech16k(p: Project): FloatArray {
        val dur = p.sequence.duration.toDouble()
        val mix = preview.audio.mixer(p).mix(0.0, dur, applyMaster = false)
        return app.bekocat.ai.toMono16k(ArrayPcmSource(mix), 0.0, dur)
    }

    fun generateCaptions(lang: String?, diarize: Boolean, translate: Boolean = false, onDone: ((List<CaptionSegment>) -> Unit)? = null) {
        val p = history?.current ?: return
        busy("📝"); jobCancelled = false
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val audio = programSpeech16k(p)
                val segs = ai.captions.transcribe(audio, 0.0, lang, translate, diarize, { pr -> _state.update { it.copy(busy = "📝 ${(pr * 100).toInt()}%") } }, { jobCancelled })
                withContext(Dispatchers.Main) {
                    busy(null)
                    if (onDone != null) { onDone(segs); return@withContext }
                    if (segs.isEmpty()) { message("لم يُعثر على كلام · No speech found"); return@withContext }
                    val split = CaptionIO.resegment(segs, 7, 42)
                    setCaptions(CaptionTrack("cap-main", lang ?: "auto", split, stylePreset = p.brandKit.captionPreset, modelId = ai.models.asrModel()))
                    commit("ai-job") { pr -> pr.copy(aiJobs = pr.aiJobs + AiJob(repo.ids.next("job"), "asr", ai.models.asrModel() ?: "", "1", mapOf("lang" to (lang ?: "auto"), "diarize" to "$diarize"), System.currentTimeMillis())) }
                }
            } catch (e: Throwable) { withContext(Dispatchers.Main) { busy(null); message("⚠ ${e.message}") } }
        }
    }

    fun translateCaptionsToEnglish() {
        val ct = _state.value.project?.sequence?.captions?.firstOrNull() ?: return
        generateCaptions(null, false, translate = true) { eng ->
            // Attach translation text to overlapping source segments without touching the transcript.
            updateCaptions { c -> c.copy(segments = c.segments.map { seg ->
                val t = eng.filter { it.end > seg.start && it.start < seg.end }.joinToString(" ") { it.text }
                seg.copy(translation = t.ifBlank { seg.translation })
            }, showTranslation = true) }
        }
    }

    fun importCaptions(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val text = appCtx().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            CaptionIO.parse(text)
        }.onSuccess { segs -> withContext(Dispatchers.Main) { if (segs.isEmpty()) message("⚠ empty") else setCaptions(CaptionTrack("cap-main", "auto", segs)) } }
            .onFailure { message("⚠ ${it.message}") }
    }

    fun captionReplace(find: String, rep: String) = updateCaptions { c -> c.copy(segments = c.segments.map { s -> s.copy(words = s.words.map { w -> w.copy(text = w.text.replace(find, rep)) }.filter { it.text.isNotBlank() }) }) }
    fun resegmentCaptions() = updateCaptions { c -> c.copy(segments = CaptionIO.resegment(c.segments, c.maxWordsPerLine, 42)) }
    fun editCaptionWord(si: Int, wi: Int, text: String) = updateCaptions { c -> c.copy(segments = c.segments.mapIndexed { i, s -> if (i != si) s else s.copy(words = s.words.mapIndexed { j, w -> if (j == wi) w.copy(text = text, confidence = 1.0) else w }.filter { it.text.isNotBlank() }) }) }
    fun nudgeSegment(si: Int, d: Double) = updateCaptions { c -> c.copy(segments = c.segments.mapIndexed { i, s ->
        if (i != si) s else { val dd = Rational.fromDouble(d, 1000); s.copy(start = s.start + dd, end = s.end + dd, words = s.words.map { it.copy(start = it.start + dd, end = it.end + dd) }) }
    }) }

    fun runMatting() {
        val p = history?.current ?: return
        val c = _state.value.selected ?: return
        val a = p.asset(c.assetId) ?: run { message("⚠ media clip required"); return }
        busy("✂"); jobCancelled = false
        val job = repo.ids.next("matte")
        val dir = File(appCtx().filesDir, "ai/${p.id}/$job")
        val fps = (a.frameRate ?: p.sequence.frameRate).fps
        viewModelScope.launch(Dispatchers.Default) {
            try {
                ai.matting.run(Uri.parse(a.uri), (c.sourceIn.toDouble() * 1e6).toLong(), (c.sourceOut.toDouble() * 1e6).toLong() + 1, fps, dir, 0.5,
                    { pr -> _state.update { it.copy(busy = "✂ ${(pr * 100).toInt()}%") } }, { jobCancelled })
                withContext(Dispatchers.Main) {
                    busy(null)
                    commit("ai-matte") { pr -> pr.copy(
                        sequence = editor(pr).updateClip(pr.sequence, c.id) { it.copy(aiMatte = AiMatte(jobId = job, modelId = ModelManager.MATTING, modelVersion = "modnet-onnx-fp32", cacheDir = dir.path)) },
                        aiJobs = pr.aiJobs + AiJob(job, "matting", ModelManager.MATTING, "modnet", mapOf("clip" to c.id), System.currentTimeMillis())) }
                }
            } catch (e: Throwable) { withContext(Dispatchers.Main) { busy(null); message("⚠ ${e.message}") } }
        }
    }

    fun withContactShadow(fx: List<EffectInstance>, v: Double): List<EffectInstance> {
        val rest = fx.filterNot { it.options["contact"] == "1" }
        if (v <= 0.0) return rest
        val spec = EffectCatalog.find("drop_shadow")!!
        return rest + spec.instance(repo.ids.next("fx")).let { e -> e.copy(options = mapOf("contact" to "1"), params = e.params + mapOf("distance" to Animatable.of(0.004), "angle" to Animatable.of(270.0), "blur" to Animatable.of(18.0), "opacity" to Animatable.of(v))) }
    }

    fun addBrushStroke(add: Boolean, u: Double, v: Double) = updateSelected("ai-brush") { c ->
        val am = c.aiMatte ?: return@updateSelected c
        val lt = localTime(c)
        c.copy(aiMatte = am.copy(brush = am.brush + BrushStroke(add, 0.04, listOf(u, v, u + 0.001, v), lt, lt + Rational.seconds(1))))
    }

    fun exportAlphaPng() {
        val p = _state.value.project ?: return
        val c = _state.value.selected ?: return
        enqueueExport("PNG alpha", ExportSettings(kind = ExportKind.PNG_SEQUENCE, width = p.sequence.width, height = p.sequence.height,
            frameRate = p.sequence.frameRate, startSec = c.timelineStart.toDouble(), endSec = c.timelineEnd.toDouble(), transparent = true))
    }

    fun runSeparation() {
        val p = history?.current ?: return
        val c = _state.value.selected ?: return
        val src = preview.audio.sourceFor(p, c.copy(audio = c.audio.copy(stems = null))) ?: run { message("⚠ no audio"); return }
        busy("♪"); jobCancelled = false
        val job = repo.ids.next("stems")
        val dir = File(appCtx().filesDir, "ai/${p.id}/$job")
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val names = ai.separation.separate(src, dir, { pr -> _state.update { it.copy(busy = "♪ ${(pr * 100).toInt()}%") } }, { jobCancelled })
                withContext(Dispatchers.Main) {
                    busy(null)
                    commit("stems") { pr -> pr.copy(sequence = editor(pr).updateClip(pr.sequence, c.id) {
                        it.copy(audio = it.audio.copy(stems = StemMix(job, ModelManager.SEPARATION, dir.path, names.associateWith { 1.0 })))
                    }) }
                }
            } catch (e: Throwable) { withContext(Dispatchers.Main) { busy(null); message("⚠ ${e.message}") } }
        }
    }

    private var stemBackup: StemMix? = null
    fun toggleStemAB() {
        val c = _state.value.selected ?: return
        if (c.audio.stems != null) { stemBackup = c.audio.stems; preview { p -> p.copy(sequence = editor(p).updateClip(p.sequence, c.id) { it.copy(audio = it.audio.copy(stems = null)) }) }; message("A (original)") }
        else { history?.current?.let { preview.setProject(it); _state.update { s -> s.copy(project = it) } }; message("B (stems)") }
    }

    fun exportStemFiles() {
        val c = _state.value.selected ?: return
        val sm = c.audio.stems ?: return
        viewModelScope.launch(Dispatchers.IO) {
            for (name in sm.gains.keys) {
                val src = FilePcmSource(File(sm.cacheDir, "$name.f32"))
                val pcm = FloatArray((src.frames * 2).toInt()); src.read(0, pcm, src.frames.toInt()); src.close()
                val out = File(appCtx().cacheDir, "exports/${c.name}_$name.wav").apply { parentFile?.mkdirs() }
                out.outputStream().buffered().use { Wav.write(it, pcm, 48_000, 2, 24) }
                publish(ExportResult(out, "audio/wav", src.frames / 48000.0, 1, 0, 0, emptyList()))
            }
            message("✓ Music/Bekocat")
        }
    }

    fun planAssistant(cmd: String, cb: (Plan?, String?) -> Unit) {
        val p = history?.current ?: return
        val sel = _state.value.primary
        val rule = AssistantParser.parse(cmd, p.sequence, sel, playheadTime)
        viewModelScope.launch {
            val llm = if (ai.models.isInstalled(ModelManager.PLANNER)) runCatching { busy("…"); ai.planner.plan(cmd, p, sel) }.getOrNull().also { busy(null) } else null
            val chosen = llm ?: rule
            pendingAssistant = chosen
            val ops = chosen?.intents?.mapNotNull { (it as? Intent.Edit)?.op } ?: emptyList()
            val note = when {
                chosen == null -> "لم أفهم الطلب. جرّب صياغة أوضح · Not understood"
                chosen.question != null -> chosen.question + " — " + chosen.alternatives.joinToString(" / ")
                else -> (if (llm != null) "🧠 " else "⚡ ") + chosen.summaryAr
            }
            cb(chosen?.let { Plan(ops, it.summaryAr) }, note)
        }
    }

    private var pendingAssistant: AssistantPlan? = null

    /** Executes a plan: runs needed jobs first, then commits every change as ONE undoable transaction. */
    fun applyPlan(plan: Plan) {
        val ap = pendingAssistant
        if (ap == null) { commit("assistant: ${plan.summaryAr}") { p -> PlanApplier.apply(p, plan, repo.ids) }; return }
        pendingAssistant = null
        val I = Intent::class
        if (ap.intents.any { it is Intent.Undo }) { undo(); return }
        viewModelScope.launch {
            busy("🧠")
            try {
                val p0 = history?.current ?: return@launch
                var captions: List<CaptionSegment>? = null
                var silences: List<Pair<Double, Double>> = emptyList()
                val capIntent = ap.intents.filterIsInstance<Intent.Captions>().firstOrNull()
                val silIntent = ap.intents.filterIsInstance<Intent.RemoveSilence>().firstOrNull()
                withContext(Dispatchers.Default) {
                    if (silIntent != null) {
                        val buf = preview.audio.mixer(p0).mix(0.0, p0.sequence.duration.toDouble(), applyMaster = false)
                        silences = Analysis.silences(buf, p0.sequence.audioSampleRate.toDouble(), silIntent.thresholdDb, silIntent.minSilence, silIntent.padding)
                    }
                }
                commit("assistant: ${ap.summaryAr}") { p -> applyIntents(p, ap, silences) }
                // Captions are computed on the edited timeline, then merged into the same undo step.
                if (capIntent != null && ai.models.asrModel() != null) {
                    val edited = history!!.current
                    val segs = withContext(Dispatchers.Default) { ai.captions.transcribe(programSpeech16k(edited), 0.0, capIntent.language, false, false, {}, { false }) }
                    history!!.undo()
                    commit("assistant: ${ap.summaryAr}") { p ->
                        applyIntents(p, ap, silences).let { q -> q.copy(sequence = q.sequence.copy(captions = listOf(CaptionTrack("cap-main", capIntent.language ?: "auto",
                            CaptionIO.resegment(segs, 7, 42), stylePreset = capIntent.style)))) }
                    }
                } else if (capIntent != null) message("نموذج الكابشن غير مثبت · Caption model not installed")
                // Jobs that need their own cache run after (each also undoable): separation / matting.
                ap.intents.forEach { i ->
                    when (i) {
                        is Intent.IsolateVocals -> { selectFirstAudio(); if (ai.models.isInstalled(ModelManager.SEPARATION)) runSeparationThen(i.keep) else message("نموذج فصل الصوت غير مثبت") }
                        is Intent.RemoveBackground -> { if (ai.models.isInstalled(ModelManager.MATTING)) runMatting() else message("نموذج إزالة الخلفية غير مثبت") }
                        else -> {}
                    }
                }
            } catch (e: Throwable) { message("⚠ ${e.message}") } finally { busy(null) }
        }
    }

    private fun selectFirstAudio() {
        val p = _state.value.project ?: return
        if (_state.value.selected?.let { p.asset(it.assetId)?.hasAudio } == true) return
        p.sequence.tracks.flatMap { it.clips }.firstOrNull { p.asset(it.assetId)?.hasAudio == true }?.let { select(it.id) }
    }

    private fun runSeparationThen(keep: String) {
        runSeparation()
        viewModelScope.launch {
            while (_state.value.busy != null) delay(300)
            updateSelected("isolate") { c -> c.audio.stems?.let { sm -> c.copy(audio = c.audio.copy(stems = sm.copy(gains = sm.gains.mapValues { (k, _) -> if (k == keep) 1.0 else 0.0 }))) } ?: c }
        }
    }

    private fun applyIntents(p0: Project, ap: AssistantPlan, silences: List<Pair<Double, Double>>): Project {
        var p = p0
        val ed = editor(p)
        val ops = ap.intents.mapNotNull { (it as? Intent.Edit)?.op }
        if (ops.isNotEmpty()) p = PlanApplier.apply(p, Plan(ops, ""), repo.ids)
        for (i in ap.intents) {
            when (i) {
                is Intent.Aspect -> p = p.copy(sequence = p.sequence.copy(width = i.w, height = i.h))
                is Intent.RemoveSilence -> {
                    var s = p.sequence
                    for ((a, b) in silences.reversed()) {
                        val ta = s.frameRate.snap(Rational.fromDouble(a, 1_000_000)); val tb = s.frameRate.snap(Rational.fromDouble(b, 1_000_000))
                        if (tb <= ta) continue
                        s = ed.splitAll(s, ta); s = ed.splitAll(s, tb)
                        val inside = s.tracks.filter { !it.locked }.flatMap { t -> t.clips.filter { it.timelineStart >= ta && it.timelineEnd <= tb }.map { it.id } }.toSet()
                        if (inside.isNotEmpty()) { s = ed.delete(s, inside, false); val d = tb - ta
                            s = s.copy(tracks = s.tracks.map { t -> if (t.locked) t else t.copy(clips = t.clips.map { c -> if (c.timelineStart >= tb) c.copy(timelineStart = c.timelineStart - d) else c }) }) }
                    }
                    p = p.copy(sequence = s)
                }
                is Intent.DuckMusic -> p = p.copy(sequence = p.sequence.copy(tracks = p.sequence.tracks.map { t ->
                    when { t.kind != TrackKind.AUDIO -> t; t.role == AudioRole.DIALOGUE -> t
                        else -> t.copy(duckUnderDialogue = true, duckAmountDb = i.depthDb, role = if (t.role == AudioRole.OTHER) AudioRole.MUSIC else t.role) } }.let { tracks ->
                    // Dialogue = the main video track's audio when no explicit dialogue track exists.
                    if (tracks.none { it.role == AudioRole.DIALOGUE && it.clips.isNotEmpty() }) tracks.map { t -> if (t.kind == TrackKind.VIDEO) t.copy(role = AudioRole.DIALOGUE) else t } else tracks }))
                is Intent.Look -> {
                    val look = LookCatalog.find(i.lookId) ?: continue
                    p = p.copy(sequence = p.sequence.copy(tracks = p.sequence.tracks.map { t -> if (t.kind != TrackKind.VIDEO && t.kind != TrackKind.OVERLAY) t else t.copy(clips = t.clips.map { c ->
                        if (c.kind != ClipKind.MEDIA) c else c.copy(color = look.color, effects = c.effects.filterNot { it.options["fromLook"] == "1" } + look.effects.map { (type, params) ->
                            val spec = EffectCatalog.find(type)!!
                            EffectInstance(repo.ids.next("fx"), type, params = spec.params.associate { it.key to Animatable.of(params[it.key] ?: it.default) }, options = mapOf("fromLook" to "1")) })
                    }) }))
                }
                is Intent.Transitions -> p = p.copy(sequence = p.sequence.copy(tracks = p.sequence.tracks.map { t ->
                    if (t.kind != TrackKind.VIDEO) t else { val sorted = t.clips.sortedBy { it.timelineStart }
                        t.copy(clips = sorted.mapIndexed { idx, c -> if (idx > 0 && sorted[idx - 1].timelineEnd == c.timelineStart) c.copy(transitionIn = Transition(i.kind, p.sequence.frameRate.snap(Rational.fromDouble(i.duration, 1000)))) else c }) } }))
                is Intent.CaptionColor -> i.argb?.let { col -> p = p.copy(sequence = p.sequence.copy(captions = p.sequence.captions.map { c -> c.copy(style = c.style.copy(colorArgb = col)) })) }
                is Intent.AnimateIn -> {
                    val target = if (i.target == "logo") p.sequence.tracks.filter { it.kind == TrackKind.OVERLAY }.flatMap { it.clips }.firstOrNull { p.asset(it.assetId)?.kind == AssetKind.IMAGE || it.kind == ClipKind.SHAPE } ?: p.sequence.findClip(_state.value.primary ?: "")?.second
                        else p.sequence.findClip(_state.value.primary ?: "")?.second
                    if (target != null) p = p.copy(sequence = ed.updateClip(p.sequence, target.id) { AssistantParser.animateIn(it, i.from, i.easing) })
                }
                is Intent.CopyAnimationToTitles -> {
                    val src = _state.value.primary ?: p.sequence.tracks.flatMap { it.clips }.firstOrNull { it.kind == ClipKind.TEXT }?.id
                    if (src != null) p = p.copy(sequence = AssistantParser.copyAnimationToTitles(p.sequence, src, i.delay, ed))
                }
                else -> {}
            }
        }
        return p
    }

    fun detectScenes() {
        val p = history?.current ?: return
        val c = _state.value.selected ?: p.sequence.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips?.firstOrNull() ?: return
        val a = p.asset(c.assetId) ?: return
        busy("🎬"); jobCancelled = false
        viewModelScope.launch {
            try {
                val cuts = ai.video.scenes(Uri.parse(a.uri), (c.sourceIn.toDouble() * 1e6).toLong(), (c.sourceOut.toDouble() * 1e6).toLong(), 0.5) { jobCancelled }
                busy(null)
                if (cuts.isEmpty()) { message("لا توجد قطعات مشاهد · No scene cuts"); return@launch }
                edit("scenes (${cuts.size})") { s, ed ->
                    cuts.fold(s) { acc, srcT -> val t = c.timelineStart + Rational.fromDouble((srcT - c.sourceIn.toDouble()) / c.speed.toDouble(), 1_000_000)
                        runCatching { ed.addMarker(acc, s.frameRate.snap(t), "scene") }.getOrDefault(acc) }
                }
                message("🎬 ${cuts.size}")
            } catch (e: Throwable) { busy(null); message("⚠ ${e.message}") }
        }
    }

    /** Auto montage: splits on detected scenes and keeps the most active shots in order, with soft transitions. */
    fun autoMontage(targetSec: Double = 30.0) {
        val p = history?.current ?: return
        val main = p.sequence.tracks.firstOrNull { it.kind == TrackKind.VIDEO } ?: return
        busy("🎞"); jobCancelled = false
        viewModelScope.launch {
            try {
                val pieces = ArrayList<Pair<Clip, Double>>() // (sub-clip, energy)
                for (c in main.clips) {
                    val a = p.asset(c.assetId) ?: continue
                    if (a.kind != AssetKind.VIDEO) { pieces += c to 0.5; continue }
                    val cuts = ai.video.scenes(Uri.parse(a.uri), (c.sourceIn.toDouble() * 1e6).toLong(), (c.sourceOut.toDouble() * 1e6).toLong(), 0.5) { jobCancelled }
                    val bounds = (listOf(c.sourceIn.toDouble()) + cuts + listOf(c.sourceOut.toDouble())).distinct().sorted()
                    for (k in 0 until bounds.size - 1) {
                        val s0 = bounds[k]; val s1 = bounds[k + 1]
                        if (s1 - s0 < 0.6) continue
                        val len = (s1 - s0).coerceAtMost(3.0)
                        val mid = s0 + (s1 - s0 - len) / 2
                        pieces += c.copy(id = repo.ids.next("clip"), sourceIn = Rational.fromDouble(mid, 1000), sourceOut = Rational.fromDouble(mid + len, 1000), speed = Rational.ONE, timeRemap = null, remapDuration = null) to len
                    }
                }
                var total = 0.0
                val chosen = pieces.takeWhile { (_, l) -> (total < targetSec).also { total += l } }
                busy(null)
                commit("auto-montage") { pr ->
                    var t = Rational.ZERO
                    val clips = chosen.mapIndexed { i, (c, _) ->
                        val n = c.copy(timelineStart = t, transitionIn = if (i > 0) Transition(TransitionKind.DISSOLVE, Rational.of(2, 5)) else null)
                        t += n.duration; n
                    }
                    pr.copy(sequence = pr.sequence.copy(tracks = pr.sequence.tracks.map { tr -> if (tr.id == main.id) tr.copy(clips = clips) else tr }))
                }
            } catch (e: Throwable) { busy(null); message("⚠ ${e.message}") }
        }
    }

    /** Suggests short highlights (scenes with speech) as markers to review. */
    fun suggestShorts() {
        val p = history?.current ?: return
        busy("✂"); viewModelScope.launch(Dispatchers.Default) {
            val dur = p.sequence.duration.toDouble()
            val buf = preview.audio.mixer(p).mix(0.0, dur, applyMaster = false)
            val env = Analysis.rmsEnvelope(buf, p.sequence.audioSampleRate.toDouble(), 1.0)
            val windows = (0..(env.size - 30).coerceAtLeast(0)).map { s -> s to (s until minOf(env.size, s + 30)).sumOf { env[it] } }.sortedByDescending { it.second }
            val picks = ArrayList<Int>()
            for ((s, _) in windows) { if (picks.none { kotlin.math.abs(it - s) < 30 }) picks += s; if (picks.size >= 3) break }
            withContext(Dispatchers.Main) {
                busy(null)
                updateSequence("shorts") { s -> s.copy(markers = (s.markers + picks.mapIndexed { i, st -> Marker(repo.ids.next("short"), Rational.seconds(st.toLong()), "Short ${i + 1}", 0xFFFFC857, "≈30s", Rational.seconds(30), "chapter") }).sortedBy { it.time }) }
                message("✂ ${picks.size}")
            }
        }
    }

    fun matchColorToReference() {
        val p = history?.current ?: return
        val c = _state.value.selected ?: return
        val refT = p.sequence.markers.firstOrNull()?.time?.toDouble() ?: 0.0
        val neutral = p.copy(sequence = editor(p).updateClip(p.sequence, c.id) { it.copy(color = ColorAdjust()) })
        preview.setProject(neutral)
        preview.snapshot(refT, 160) { ref ->
            preview.snapshot(_state.value.playhead, 160) { cur ->
                preview.setProject(history!!.current)
                if (ref == null || cur == null) return@snapshot
                fun stats(b: android.graphics.Bitmap): Pair<DoubleArray, DoubleArray> {
                    val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                    val m = DoubleArray(3); val s = DoubleArray(3)
                    for (q in px) for (ch in 0..2) m[ch] += ((q shr (16 - 8 * ch)) and 255) / 255.0
                    for (ch in 0..2) m[ch] /= px.size
                    for (q in px) for (ch in 0..2) { val d = ((q shr (16 - 8 * ch)) and 255) / 255.0 - m[ch]; s[ch] += d * d }
                    for (ch in 0..2) s[ch] = Math.sqrt(s[ch] / px.size)
                    return m to s
                }
                val (rm, rs) = stats(ref); val (cm, cs) = stats(cur)
                val adj = ColorMath.matchStats(cm, cs, rm, rs)
                viewModelScope.launch { updateSelected("color-match") { it.copy(color = it.color.copy(gain = adj.gain, lift = adj.lift)) } }
            }
        }
    }

    fun matchColorAll() {
        val p = history?.current ?: return
        val ids = p.sequence.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips?.drop(1)?.map { it.id } ?: return
        viewModelScope.launch { for (id in ids) { select(id); seek(p.sequence.findClip(id)!!.second.timelineStart.toDouble() + 0.1); matchColorToReference(); delay(700) } }
    }

    fun applyBeforeAfter() {
        val c = _state.value.selected ?: return
        if (_state.value.beforeAfter) preview { p -> p.copy(sequence = editor(p).updateClip(p.sequence, c.id) { it.copy(color = ColorAdjust(), effects = emptyList()) }) }
        else history?.current?.let { preview.setProject(it); _state.update { s -> s.copy(project = it) } }
    }

    fun setTransitionMaskImage(uri: Uri) = viewModelScope.launch {
        val a = runCatching { repo.probe(uri) }.getOrElse { message(it.message); return@launch }
        commit("transition-mask") { p -> p.copy(assets = p.assets + a, sequence = editor(p).updateClip(p.sequence, _state.value.primary ?: return@commit p) { it.copy(transitionIn = it.transitionIn?.copy(maskAssetId = a.id)) }) }
    }

    fun applyTransitionToAll(tr: Transition) = updateSequence("transitions-all") { s ->
        s.copy(tracks = s.tracks.map { t -> if (t.kind == TrackKind.AUDIO) t else { val sorted = t.clips.sortedBy { it.timelineStart }
            t.copy(clips = sorted.mapIndexed { i, c -> if (i > 0 && sorted[i - 1].timelineEnd == c.timelineStart) c.copy(transitionIn = tr) else c }) } })
    }

    fun setBackgroundMedia(uri: Uri) {
        val sel = _state.value.selected ?: return
        viewModelScope.launch {
            val a = runCatching { repo.probe(uri) }.getOrElse { message(it.message); return@launch }
            commit("background") { p0 ->
                var p = p0.copy(assets = p0.assets + a)
                val ed = editor(p)
                var s = ed.addTrack(p.sequence, TrackKind.VIDEO, "BG", 0)
                val tid = s.tracks.first().id
                val dur = if (a.duration != null) minOf(a.duration!!, sel.duration) else sel.duration
                s = ed.overwrite(s, tid, Clip(repo.ids.next("clip"), a.id, a.displayName, sel.timelineStart, Rational.ZERO, dur, transform = Transform(fit = FitMode.FILL)), sel.timelineStart)
                p.copy(sequence = s)
            }
            history?.current?.let { loadMediaCaches(it) }
        }
    }

    fun trackPointAtCenter() {
        val p = history?.current ?: return
        val c = _state.value.selected ?: return
        val a = p.asset(c.assetId) ?: return
        busy("⌖"); jobCancelled = false
        viewModelScope.launch {
            try {
                val pts = ai.video.track(Uri.parse(a.uri), (c.sourceIn.toDouble() * 1e6).toLong(), (c.sourceOut.toDouble() * 1e6).toLong(), 0.5, 0.5, 15.0,
                    { pr -> _state.update { it.copy(busy = "⌖ ${(pr * 100).toInt()}%") } }, { jobCancelled })
                busy(null)
                val tr = MotionTrack(repo.ids.next("track"), c.id, pts.map { Rational.fromDouble(it[0] / 1e6, 1000) }, pts.map { it[1] }, pts.map { it[2] }, confidence = pts.map { it[3] })
                commit("motion-track") { pr -> pr.copy(tracking = pr.tracking + tr) }
                message("⌖ ${pts.size} · ${tr.id}")
            } catch (e: Throwable) { busy(null); message("⚠ ${e.message}") }
        }
    }

    /** Attaches the selected layer (text/logo) to the newest motion track: position keyframes follow the tracked point. */
    fun attachSelectedToTrack() {
        val p = history?.current ?: return
        val c = _state.value.selected ?: return
        val tr = p.tracking.lastOrNull() ?: run { message("لا يوجد تتبع · Track a point first"); return }
        val host = p.sequence.findClip(tr.clipId)?.second ?: return
        val l = RenderPlannerAccess.layer(p, host) ?: return
        updateSelected("attach-track") { cl ->
            val kx = ArrayList<Keyframe>(); val ky = ArrayList<Keyframe>()
            tr.times.forEachIndexed { i, st ->
                val tl = host.timelineStart + (st - host.sourceIn) / host.speed
                val lt = tl - cl.timelineStart
                if (lt.isNegative || lt > cl.duration) return@forEachIndexed
                val H = Geometry.homography(Geometry.corners(l.transform, l.parents, l.contentW, l.contentH, p.sequence.width, p.sequence.height, null))
                val (x, y) = Geometry.apply(H, tr.xs[i], tr.ys[i])
                kx += Keyframe(lt, x / p.sequence.width * 2 - 1); ky += Keyframe(lt, y / p.sequence.height * 2 - 1)
            }
            cl.copy(transform = cl.transform.copy(positionX = Animatable(0.0, kx.distinctBy { it.time }), positionY = Animatable(0.0, ky.distinctBy { it.time })))
        }
    }

    fun stabilize() {
        val p = history?.current ?: return
        val c = _state.value.selected ?: return
        val a = p.asset(c.assetId) ?: return
        busy("〰"); jobCancelled = false
        viewModelScope.launch {
            try {
                val (corr, crop) = ai.video.stabilize(Uri.parse(a.uri), (c.sourceIn.toDouble() * 1e6).toLong(), (c.sourceOut.toDouble() * 1e6).toLong(), 15.0, 0.6,
                    { pr -> _state.update { it.copy(busy = "〰 ${(pr * 100).toInt()}%") } }, { jobCancelled })
                busy(null)
                if (corr.isEmpty()) { message("⚠ too short"); return@launch }
                updateSelected("stabilize") { cl ->
                    fun keys(idx: Int, scale: Double) = Animatable(0.0, corr.mapNotNull { k ->
                        val lt = (Rational.fromDouble(k[0] / 1e6, 1000) - cl.sourceIn) / cl.speed
                        if (lt.isNegative || lt > cl.duration) null else Keyframe(lt, k[idx] * scale, Interpolation.LINEAR)
                    }.distinctBy { it.time })
                    cl.copy(stabilize = Stabilization(0.6, true), transform = cl.transform.copy(
                        positionX = keys(1, 2.0), positionY = keys(2, 2.0), rotation = keys(3, 1.0), scale = Animatable.of(crop)))
                }
                message("〰 ×%.2f crop".format(crop))
            } catch (e: Throwable) { busy(null); message("⚠ ${e.message}") }
        }
    }

    fun autoReframe() {
        val p = history?.current ?: return
        val c = _state.value.selected ?: return
        val a = p.asset(c.assetId) ?: return
        busy("⛶"); jobCancelled = false
        viewModelScope.launch {
            try {
                val path = ai.video.reframePath(Uri.parse(a.uri), (c.sourceIn.toDouble() * 1e6).toLong(), (c.sourceOut.toDouble() * 1e6).toLong(), ai.matting,
                    { pr -> _state.update { it.copy(busy = "⛶ ${(pr * 100).toInt()}%") } }, { jobCancelled })
                busy(null)
                val seq = p.sequence
                // FILL the frame, then pan so the subject centre stays in the middle.
                val fill = Geometry.fitScale(FitMode.FILL, a.width, a.height, seq.width, seq.height)
                val overW = a.width * fill.first / seq.width; val overH = a.height * fill.second / seq.height
                updateSelected("auto-reframe") { cl ->
                    fun keys(f: (DoubleArray) -> Double) = Animatable(0.0, path.mapNotNull { k ->
                        val lt = (Rational.fromDouble(k[0] / 1e6, 1000) - cl.sourceIn) / cl.speed
                        if (lt.isNegative || lt > cl.duration) null else Keyframe(lt, f(k), Interpolation.BEZIER)
                    }.distinctBy { it.time })
                    cl.copy(transform = cl.transform.copy(fit = FitMode.FILL,
                        positionX = keys { k -> ((0.5 - k[1]) * overW * 2).coerceIn(-(overW - 1), overW - 1) },
                        positionY = keys { k -> ((0.5 - k[2]) * overH * 2).coerceIn(-(overH - 1), overH - 1) }))
                }
            } catch (e: Throwable) { busy(null); message("⚠ ${e.message}") }
        }
    }

    fun removeObjectTemporal() {
        val p = history?.current ?: return
        val c = _state.value.selected ?: return
        val a = p.asset(c.assetId) ?: return
        val m = c.masks.firstOrNull() ?: run { message("ارسم قناعًا حول العنصر أولًا · Draw a mask around the object first"); return }
        val poly = if (m.vertices.isNotEmpty()) m.vertices.map { it.x to it.y } else listOf(
            (m.centerX.base - m.width.base / 2) to (m.centerY.base - m.height.base / 2), (m.centerX.base + m.width.base / 2) to (m.centerY.base - m.height.base / 2),
            (m.centerX.base + m.width.base / 2) to (m.centerY.base + m.height.base / 2), (m.centerX.base - m.width.base / 2) to (m.centerY.base + m.height.base / 2))
        val fps = (a.frameRate ?: p.sequence.frameRate).fps
        val job = repo.ids.next("fill")
        val dir = File(appCtx().filesDir, "ai/${p.id}/$job")
        busy("🧽"); jobCancelled = false
        viewModelScope.launch {
            try {
                ai.video.temporalFill(Uri.parse(a.uri), (c.sourceIn.toDouble() * 1e6).toLong(), (c.sourceOut.toDouble() * 1e6).toLong(), fps, poly, dir,
                    { pr -> _state.update { it.copy(busy = "🧽 ${(pr * 100).toInt()}%") } }, { jobCancelled })
                busy(null)
                updateSelected("object-removal") { it.copy(fillPatch = FillPatch(dir.path, true, job), masks = it.masks.drop(1)) }
            } catch (e: Throwable) { busy(null); message("⚠ ${e.message}") }
        }
    }

    fun cancelJob() { jobCancelled = true }

    // =====================================================================
    // Audio helpers
    // =====================================================================
    fun applyVoicePreset() = updateSelected("voice-clean") { c ->
        val fx = listOf("eq" to mapOf("highpass" to 80.0, "midGain" to 2.0, "midFreq" to 3000.0), "denoise" to mapOf("reduction" to 14.0), "deesser" to emptyMap(),
            "compressor" to mapOf("threshold" to -20.0, "ratio" to 3.0), "limiter" to mapOf("ceiling" to -1.0)).map { (type, params) ->
            val spec = EffectCatalog.find(type)!!
            EffectInstance(repo.ids.next("afx"), type, params = spec.params.associate { it.key to Animatable.of(params[it.key] ?: it.default) })
        }
        c.copy(audio = c.audio.copy(effects = fx))
    }

    fun measureLoudness() {
        val p = history?.current ?: return
        busy("LUFS")
        viewModelScope.launch(Dispatchers.Default) {
            val buf = preview.audio.mixer(p).renderMaster(p.sequence.duration.toDouble())
            val l = Loudness.integrated(buf, p.sequence.audioSampleRate.toDouble())
            val pk = Loudness.samplePeakDb(buf)
            withContext(Dispatchers.Main) { busy(null); message("%s LUFS · peak %.1f dBFS".format(l?.let { "%.1f".format(it) } ?: "—", pk)) }
        }
    }

    fun cutOnBeats() {
        val p = history?.current ?: return
        val beats = p.sequence.markers.filter { it.kind == "beat" }.map { it.time }
        if (beats.isEmpty()) { message("اكشف الإيقاع أولًا · Detect beats first"); return }
        val main = p.sequence.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.id ?: return
        edit("cut-on-beats") { s, ed -> beats.fold(s) { acc, t -> acc.track(main)?.clips?.firstOrNull { it.contains(t) && it.timelineStart != t }?.let { c -> ed.split(acc, c.id, t) } ?: acc } }
    }

    var recording by androidx.compose.runtime.mutableStateOf(false); private set
    private var recorder: android.media.MediaRecorder? = null
    private var recFile: File? = null
    private var recStart: Time = Rational.ZERO

    fun toggleVoiceOver() {
        if (!recording) {
            val f = File(appCtx().filesDir, "voiceover/vo_${System.currentTimeMillis()}.m4a").apply { parentFile?.mkdirs() }
            val r = android.media.MediaRecorder(appCtx())
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48_000); r.setAudioEncodingBitRate(192_000); r.setAudioChannels(1)
            r.setOutputFile(f.path)
            runCatching { r.prepare(); r.start() }.onFailure { message("⚠ ${it.message}"); r.release(); return }
            recorder = r; recFile = f; recStart = playheadTime; recording = true
            preview.play()
        } else {
            preview.pause()
            runCatching { recorder?.stop() }; recorder?.release(); recorder = null; recording = false
            val f = recFile ?: return
            viewModelScope.launch {
                val a = runCatching { repo.probe(Uri.fromFile(f)) }.getOrElse { message(it.message); return@launch }
                commit("voice-over") { p0 ->
                    val p = p0.copy(assets = p0.assets + a)
                    val ed = editor(p)
                    var s = p.sequence
                    val tid = s.tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.role == AudioRole.DIALOGUE && it.clips.none { c -> c.timelineStart < recStart + a.duration!! && recStart < c.timelineEnd } }?.id
                        ?: run { s = ed.addTrack(s, TrackKind.AUDIO, "VO"); s = s.copy(tracks = s.tracks.map { t -> if (t.id == s.tracks.last().id) t.copy(role = AudioRole.DIALOGUE) else t }); s.tracks.last().id }
                    p.copy(sequence = ed.overwrite(s, tid, Clip(repo.ids.next("clip"), a.id, "Voice-over", recStart, Rational.ZERO, a.duration!!), recStart))
                }
                history?.current?.let { loadMediaCaches(it) }
            }
        }
    }

    // =====================================================================
    // Project helpers
    // =====================================================================
    fun applyBrandKit() = commit("brand-kit") { p ->
        val bk = p.brandKit
        p.copy(sequence = p.sequence.copy(tracks = p.sequence.tracks.map { t -> t.copy(clips = t.clips.map { c ->
            c.text?.let { ts -> c.copy(text = ts.copy(fontFamily = bk.fontFamily, colorArgb = bk.colors.getOrElse(2) { ts.colorArgb }, highlightArgb = bk.colors.getOrElse(0) { ts.highlightArgb })) } ?: c
        }) }))
    }

    fun saveAsTemplate() {
        val p = history?.current ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(appCtx().filesDir, "user-templates").apply { mkdirs() }
            File(dir, "${p.id}.json").writeText(ProjectCodec.encode(p.copy(name = p.name + " (template)")))
            message("✓ " + p.name)
        }
    }

    fun restoreBackup(f: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = runCatching { ProjectCodec.decode(f.readText()) }.getOrElse { message(it.message); return@launch }
            withContext(Dispatchers.Main) { commit("restore-backup") { p } }
        }
    }

    override fun onCleared() {
        history?.current?.let { p -> kotlinx.coroutines.runBlocking { runCatching { repo.save(p) } } }
        preview.release()
    }
}

/** Small bridge to the render planner for geometry helpers in the view model. */
internal object RenderPlannerAccess {
    fun layer(p: Project, c: Clip) = RenderPlanner.layerFor(p, p.sequence, c, c.timelineStart, 0, false)
}
