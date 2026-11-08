package app.bekocat.editor.panels

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.bekocat.core.timeline.Animatable
import app.bekocat.core.timeline.ChromaKey
import app.bekocat.core.timeline.ClipKind
import app.bekocat.core.timeline.Geometry
import app.bekocat.core.timeline.KeyView
import app.bekocat.core.timeline.Mask
import app.bekocat.core.timeline.MaskMode
import app.bekocat.core.timeline.MaskShape
import app.bekocat.core.timeline.MaskVertex
import app.bekocat.core.timeline.MatteType
import app.bekocat.core.timeline.RenderPlanner
import app.bekocat.core.timeline.TrackKind
import app.bekocat.core.timeline.TrackMatte
import app.bekocat.editor.EditorState
import app.bekocat.editor.EditorViewModel
import app.bekocat.editor.Prop
import app.bekocat.ui.components.ChipRow
import app.bekocat.ui.components.ColorSwatches
import app.bekocat.ui.components.Hint
import app.bekocat.ui.components.PALETTE
import app.bekocat.ui.components.PropertyRow
import app.bekocat.ui.components.SectionTitle
import app.bekocat.ui.components.ToggleRow
import app.bekocat.ui.components.tr
import app.bekocat.ui.theme.BkColors

/** Frame snapshot where taps are converted to the selected layer's UV space (for mask points and the eyedropper). */
@Composable
internal fun FramePicker(vm: EditorViewModel, onTap: (u: Double, v: Double, argb: Int) -> Unit, overlay: List<Pair<Double, Double>> = emptyList()) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    val st = vm.state.value
    LaunchedEffect(st.playhead, st.primary) { vm.preview.snapshot(st.playhead, 720) { bmp = it } }
    val b = bmp ?: run { Hint("…"); return }
    val p = st.project ?: return
    val c = st.selected ?: return
    Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().pointerInput(b, c.id) {
            detectTapGestures { o ->
                val seq = p.sequence
                val x = o.x / size.width * seq.width; val y = o.y / size.height * seq.height
                val l = RenderPlanner.layerFor(p, seq, c, vm.playheadTime, 0, false) ?: return@detectTapGestures
                val w = if (l.contentW > 0) l.contentW else seq.width; val h = if (l.contentH > 0) l.contentH else seq.height
                val inv = Geometry.invert3(Geometry.homography(Geometry.corners(l.transform, l.parents, w, h, seq.width, seq.height, null, l.contentW == 0)))
                val (u, v) = Geometry.apply(inv, x.toDouble(), y.toDouble())
                val px = b.getPixel((o.x / size.width * b.width).toInt().coerceIn(0, b.width - 1), (o.y / size.height * b.height).toInt().coerceIn(0, b.height - 1))
                onTap(u, v, px)
            }
        }) {
            // Overlay points are in layer UV; draw approximately in frame space via the layer corners.
            val l = RenderPlanner.layerFor(p, p.sequence, c, vm.playheadTime, 0, false) ?: return@Canvas
            val seq = p.sequence
            val w = if (l.contentW > 0) l.contentW else seq.width; val h = if (l.contentH > 0) l.contentH else seq.height
            val H = Geometry.homography(Geometry.corners(l.transform, l.parents, w, h, seq.width, seq.height, null, l.contentW == 0))
            val pts = overlay.map { (u, v) -> Geometry.apply(H, u, v).let { (x, y) -> Offset((x / seq.width * size.width).toFloat(), (y / seq.height * size.height).toFloat()) } }
            for (i in pts.indices) { drawCircle(BkColors.Accent, 8f, pts[i]); if (i > 0) drawLine(BkColors.Accent, pts[i - 1], pts[i], 2.5f) }
            if (pts.size > 2) drawLine(BkColors.Accent.copy(alpha = 0.5f), pts.last(), pts.first(), 2f)
        }
    }
}

@Composable
fun MaskPanel(st: EditorState, vm: EditorViewModel) = NeedsSelection(st) { c ->
    SectionTitle(tr("إضافة قناع", "Add mask"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.updateSelected("mask") { it.copy(masks = it.masks + Mask(vm.repo.ids.next("mask"), MaskShape.RECT)) } }) { Text(tr("مستطيل", "Rect")) }
        OutlinedButton(onClick = { vm.updateSelected("mask") { it.copy(masks = it.masks + Mask(vm.repo.ids.next("mask"), MaskShape.ELLIPSE)) } }) { Text(tr("بيضاوي", "Ellipse")) }
    }
    SectionTitle(tr("رسم قناع باللمس (Polygon / Bezier)", "Draw mask by touch (polygon / bezier)"))
    val draft = remember(c.id) { mutableStateListOf<Pair<Double, Double>>() }
    FramePicker(vm, onTap = { u, v, _ -> draft += u to v }, overlay = draft)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(enabled = draft.size >= 3, onClick = {
            val verts = draft.map { MaskVertex(it.first, it.second) }
            vm.updateSelected("mask-draw") { it.copy(masks = it.masks + Mask(vm.repo.ids.next("mask"), MaskShape.POLYGON, vertices = verts)) }; draft.clear()
        }) { Text(tr("قناع مضلع", "Polygon mask")) }
        OutlinedButton(enabled = draft.size >= 3, onClick = {
            // Smooth bezier through the points (Catmull–Rom tangents).
            val n = draft.size
            val verts = draft.mapIndexed { i, p ->
                val prev = draft[(i - 1 + n) % n]; val next = draft[(i + 1) % n]
                val tx = (next.first - prev.first) / 6; val ty = (next.second - prev.second) / 6
                MaskVertex(p.first, p.second, -tx, -ty, tx, ty)
            }
            vm.updateSelected("mask-draw") { it.copy(masks = it.masks + Mask(vm.repo.ids.next("mask"), MaskShape.BEZIER, vertices = verts)) }; draft.clear()
        }) { Text("Bezier") }
        TextButton(onClick = { draft.clear() }) { Text(tr("مسح", "Clear")) }
    }
    c.masks.forEachIndexed { i, m ->
        SectionTitle(tr("قناع", "Mask") + " ${i + 1} · ${m.shape.name.lowercase()}")
        fun mp(key: String, ar: String, en: String, min: Double, max: Double, def: Double, g: (Mask) -> Animatable, s: (Mask, Animatable) -> Mask) =
            Prop("m:${m.id}:$key", ar, en, min, max, def, { cl -> cl.masks.firstOrNull { it.id == m.id }?.let(g) ?: Animatable.of(def) },
                { cl, a -> cl.copy(masks = cl.masks.map { if (it.id == m.id) s(it, a) else it }) })
        fun updM(label: String, f: (Mask) -> Mask) = vm.updateSelected(label) { cl -> cl.copy(masks = cl.masks.map { if (it.id == m.id) f(it) else it }) }
        ChipRow(MaskMode.entries, m.mode, { when (it) { MaskMode.ADD -> tr("إضافة", "Add"); MaskMode.SUBTRACT -> tr("طرح", "Subtract"); MaskMode.INTERSECT -> tr("تقاطع", "Intersect") } }) { md -> updM("mask-mode") { it.copy(mode = md) } }
        ToggleRow(tr("عكس", "Invert"), m.inverted) { v -> updM("mask-inv") { it.copy(inverted = v) } }
        if (m.shape == MaskShape.RECT || m.shape == MaskShape.ELLIPSE) {
            AnimRow(vm, c, mp("cx", "المركز X", "Center X", 0.0, 1.0, 0.5, { it.centerX }, { x, a -> x.copy(centerX = a) }))
            AnimRow(vm, c, mp("cy", "المركز Y", "Center Y", 0.0, 1.0, 0.5, { it.centerY }, { x, a -> x.copy(centerY = a) }))
            AnimRow(vm, c, mp("w", "العرض", "Width", 0.0, 2.0, 0.5, { it.width }, { x, a -> x.copy(width = a) }))
            AnimRow(vm, c, mp("h", "الارتفاع", "Height", 0.0, 2.0, 0.5, { it.height }, { x, a -> x.copy(height = a) }))
            AnimRow(vm, c, mp("r", "الدوران", "Rotation", -180.0, 180.0, 0.0, { it.rotation }, { x, a -> x.copy(rotation = a) }))
        } else {
            Hint(tr("لتحريك نقاط القناع: ضع المؤشر عند زمن جديد، ارسم الشكل المعدل ثم «حفظ كمفتاح مسار».", "To animate the path: move the playhead, redraw, then 'Save as path key'."))
            TextButton(enabled = draft.size == m.vertices.size || draft.size == (m.pathKeys.firstOrNull()?.vertices?.size ?: -1), onClick = {
                val t = vm.localTime(c)
                val verts = draft.map { MaskVertex(it.first, it.second) }
                updM("mask-key") { mm -> val keys = (if (mm.pathKeys.isEmpty()) listOf(app.bekocat.core.timeline.MaskPathKey(app.bekocat.core.timeline.Rational.ZERO, mm.vertices)) else mm.pathKeys)
                    mm.copy(pathKeys = (keys.filterNot { it.time == t } + app.bekocat.core.timeline.MaskPathKey(t, verts)).sortedBy { it.time }) }
                draft.clear()
            }) { Text(tr("حفظ كمفتاح مسار عند المؤشر", "Save as path key at playhead")) }
        }
        AnimRow(vm, c, mp("f", "التنعيم (Feather)", "Feather", 0.0, 0.3, 0.0, { it.feather }, { x, a -> x.copy(feather = a) }))
        AnimRow(vm, c, mp("e", "التمدد (Expansion)", "Expansion", -0.2, 0.2, 0.0, { it.expansion }, { x, a -> x.copy(expansion = a) }))
        AnimRow(vm, c, mp("o", "الشفافية", "Opacity", 0.0, 1.0, 1.0, { it.opacity }, { x, a -> x.copy(opacity = a) }))
        val tracks = st.project!!.tracking.filter { it.clipId == c.id }
        if (tracks.isNotEmpty()) ChipRow(listOf<String?>(null) + tracks.map { it.id }, m.trackId, { it ?: tr("بدون تتبع", "No tracking") }) { id -> updM("mask-track") { it.copy(trackId = id) } }
        TextButton(onClick = { vm.updateSelected("mask-remove") { cl -> cl.copy(masks = cl.masks.filterNot { it.id == m.id }) } }) { Text(tr("حذف القناع", "Delete mask")) }
    }
    SectionTitle(tr("مطابقة المسار (Track Matte)", "Track matte"))
    val cands = st.project!!.sequence.tracks.filter { it.kind != TrackKind.AUDIO }.flatMap { it.clips }.filter { it.id != c.id }
    ChipRow(listOf<String?>(null) + cands.map { it.id }, c.matte?.sourceClipId, { id -> id?.let { i -> cands.first { it.id == i }.name.ifBlank { i } } ?: tr("بدون", "None") }) { id ->
        vm.updateSelected("matte") { it.copy(matte = id?.let { TrackMatte(it, it.let { _ -> c.matte?.type ?: MatteType.ALPHA }) }) }
    }
    c.matte?.let { m ->
        ChipRow(MatteType.entries, m.type, { it.name.lowercase().replace('_', ' ') }) { t -> vm.updateSelected("matte-type") { it.copy(matte = m.copy(type = t)) } }
        ToggleRow(tr("إخفاء طبقة المصدر", "Hide source layer"), m.hideSource) { v -> vm.updateSelected("matte-hide") { it.copy(matte = m.copy(hideSource = v)) } }
    }
}

@Composable
fun KeyPanel(st: EditorState, vm: EditorViewModel) = NeedsSelection(st) { c ->
    SectionTitle(tr("الكروما (Green / Blue screen)", "Chroma key"))
    val k = c.chroma
    ToggleRow(tr("تفعيل الكروما", "Enable chroma key"), k != null) { v -> vm.updateSelected("chroma") { it.copy(chroma = if (v) ChromaKey() else null) } }
    if (k != null) {
        fun upd(label: String, f: (ChromaKey) -> ChromaKey) = vm.updateSelected(label) { it.copy(chroma = f(it.chroma!!)) }
        Hint(tr("القطارة: انقر على لون الخلفية في الإطار.", "Eyedropper: tap the screen colour in the frame."))
        FramePicker(vm, onTap = { _, _, px -> upd("chroma-pick") { it.copy(keyColorArgb = (px.toLong() and 0xFFFFFFFFL) or 0xFF000000) } })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { upd("chroma-green") { it.copy(keyColorArgb = 0xFF00B140, lumaKey = false) } }) { Text("Green") }
            OutlinedButton(onClick = { upd("chroma-blue") { it.copy(keyColorArgb = 0xFF0047BB, lumaKey = false) } }) { Text("Blue") }
            OutlinedButton(onClick = { upd("luma") { it.copy(lumaKey = !it.lumaKey) } }) { Text(if (k.lumaKey) "Chroma" else "Luma key") }
        }
        Text("■ #" + java.lang.Long.toHexString(k.keyColorArgb).uppercase(), color = Color(k.keyColorArgb))
        PropertyRow(tr("التشابه (Similarity)", "Similarity"), k.similarity, 0.0..1.0, defaultValue = 0.4) { v -> upd("chroma-sim") { it.copy(similarity = v) } }
        PropertyRow(tr("نعومة الحواف", "Edge softness"), k.smoothness, 0.0..0.5, defaultValue = 0.08) { v -> upd("chroma-soft") { it.copy(smoothness = v) } }
        PropertyRow(tr("إزالة الانسكاب (Spill)", "Spill suppression"), k.spill, 0.0..1.0, defaultValue = 0.5) { v -> upd("chroma-spill") { it.copy(spill = v) } }
        PropertyRow("Matte choke / grow", k.chokeGrow, -1.0..1.0, defaultValue = 0.0) { v -> upd("chroma-choke") { it.copy(chokeGrow = v) } }
        PropertyRow(tr("الحفاظ على الظلال", "Shadow preservation"), k.shadowPreserve, 0.0..1.0, defaultValue = 0.0) { v -> upd("chroma-shadow") { it.copy(shadowPreserve = v) } }
        Text(tr("لون التعويض (Despill)", "Despill colour"), color = BkColors.Muted)
        ColorSwatches(k.despillColorArgb, listOf(0xFF808080, 0xFFF4F7FB, 0xFFFFD8B0, 0xFF3A3A3A)) { col -> upd("despill") { it.copy(despillColorArgb = col) } }
        ChipRow(KeyView.entries, k.view, { when (it) { KeyView.COMPOSITE -> tr("النتيجة", "Composite"); KeyView.MATTE -> "Matte"; KeyView.ORIGINAL -> tr("الأصل", "Original") } }) { v -> upd("key-view") { it.copy(view = v) } }
        Hint(tr("لقناع إقصاء (Garbage mask) استخدم لوحة الأقنعة مع وضع «طرح».", "For a garbage mask use the Masks panel in Subtract mode."))
    }
    SectionTitle(tr("إزالة الخلفية بالذكاء الاصطناعي", "AI background removal"))
    AiMattePanel(st, vm, c)
    SectionTitle(tr("استبدال الخلفية", "Replace background"))
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.setBackgroundMedia(it) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { bgPicker.launch(arrayOf("image/*", "video/*")) }) { Text(tr("صورة/فيديو خلف الشخص", "Image/video behind subject")) }
        TextButton(onClick = { vm.updateSequence("bg-clear") { it.copy(backgroundAssetId = null) } }) { Text(tr("إزالة", "Remove")) }
    }
    ColorSwatches(st.project!!.sequence.backgroundArgb, PALETTE) { col -> vm.updateSequence("bg-color") { it.copy(backgroundArgb = col) } }
}
