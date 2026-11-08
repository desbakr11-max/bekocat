package app.bekocat.editor.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bekocat.core.timeline.Animatable
import app.bekocat.core.timeline.AssetKind
import app.bekocat.core.timeline.ClipKind
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.ShapeStyle
import app.bekocat.core.timeline.ShapeType
import app.bekocat.core.timeline.TemplateCatalog
import app.bekocat.core.timeline.TextPresetCatalog
import app.bekocat.core.timeline.TextStyle
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
import app.bekocat.ui.components.isArabic
import app.bekocat.ui.components.tr
import app.bekocat.ui.theme.BkColors

@Composable
fun MediaPanel(st: EditorState, vm: EditorViewModel) {
    val p = st.project ?: return
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importMedia(it) }
    val overlayPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importMedia(it, asOverlay = true) }
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.replaceSelected(it, keepAttributes = true) } }
    var relinkId by remember { mutableStateOf<String?>(null) }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> val id = relinkId; if (u != null && id != null) vm.relink(id, u) }
    val types = arrayOf("video/*", "audio/*", "image/*", "font/*", "application/octet-stream", "*/*")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { picker.launch(types) }) { Text(tr("استيراد للمسار الرئيسي", "Import to main track")) }
        OutlinedButton(onClick = { overlayPicker.launch(arrayOf("video/*", "image/*")) }) { Text(tr("كطبقة (PiP)", "As overlay (PiP)")) }
    }
    Hint(tr("يدعم الفيديو والصوت والصور (PNG بشفافية) والخطوط TTF/OTF وجداول الألوان ‎.cube.", "Supports video, audio, images (PNG with alpha), TTF/OTF fonts and .cube LUTs."))
    if (st.selected?.kind == ClipKind.MEDIA || st.selected?.kind == ClipKind.SOLID) {
        OutlinedButton(onClick = { replacePicker.launch(arrayOf("video/*", "image/*")) }) { Text(tr("استبدال وسائط المقطع مع الحفاظ على التوقيت والخصائص", "Replace clip media (keep timing & attributes)")) }
    }
    SectionTitle(tr("طبقات مولّدة", "Generated layers"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.addSolid(0xFF141821) }) { Text(tr("لون", "Solid")) }
        OutlinedButton(onClick = vm::addAdjustment) { Text("Adjustment") }
        OutlinedButton(onClick = vm::addNull) { Text("Null") }
    }
    SectionTitle(tr("وسائط المشروع", "Project media") + " (${p.assets.size})")
    for (a in p.assets) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val missing = a.id in st.missingAssetIds
            Text((if (missing) "⚠ " else "") + "${a.displayName} · ${a.kind.name.lowercase()}" + (a.duration?.let { " · %.1fs".format(it.toDouble()) } ?: "") +
                (if (a.width > 0) " · ${a.width}×${a.height}" else "") + (a.frameRate?.let { " · %.3f fps".format(it.fps) } ?: ""),
                Modifier.weight(1f), color = if (missing) BkColors.Danger else BkColors.Text)
            if (missing) TextButton(onClick = { relinkId = a.id; relinkPicker.launch(arrayOf("*/*")) }) { Text(tr("إعادة ربط", "Relink")) }
        }
    }
    SectionTitle(tr("القوالب", "Templates") + " (${TemplateCatalog.all.size})")
    Hint(tr("القوالب تُفتح كمشاريع جديدة من الشاشة الرئيسية. هنا يمكنك استبدال العناصر النائبة:", "Templates open as new projects from Home. Here you can fill placeholders:"))
    val ph = TemplateCatalog.placeholders(p)
    if (ph.isEmpty()) Hint(tr("لا توجد عناصر نائبة في هذا المشروع.", "No placeholders in this project."))
    for (c in ph) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(c.name.replace("placeholder:", tr("عنصر ", "Slot ")) + " · %.1fs".format(c.duration.toDouble()), Modifier.weight(1f))
        TextButton(onClick = { vm.select(c.id); replacePicker.launch(arrayOf("video/*", "image/*")) }) { Text(tr("استبدال", "Replace")) }
    }
}

private val textProp = { key: String, ar: String, en: String, min: Double, max: Double, def: Double -> key }

@Composable
fun TextPanel(st: EditorState, vm: EditorViewModel) {
    val c = st.selected?.takeIf { it.kind == ClipKind.TEXT }
    val p = st.project ?: return
    var newText by remember { mutableStateOf("") }
    if (c == null) {
        OutlinedTextField(newText, { newText = it }, Modifier.fillMaxWidth(), placeholder = { Text(tr("اكتب النص بالعربية أو الإنجليزية…", "Type Arabic or English text…")) }, minLines = 2)
        Button(onClick = { vm.addText(newText); newText = "" }, enabled = newText.isNotBlank()) { Text(tr("إضافة طبقة نص", "Add text layer")) }
        SectionTitle(tr("عناوين جاهزة", "Quick titles"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.addText("عنوان رئيسي", TextStyle("", sizeSp = 96.0, weight = 900, animation = app.bekocat.core.timeline.TextAnimation(presetIn = "kinetic-punch", presetOut = "blur-out"))) }) { Text(tr("عنوان", "Title")) }
            OutlinedButton(onClick = { vm.addText("الاسم · المسمى", TextStyle("", sizeSp = 44.0, backgroundArgb = 0xE6141821, alignment = "start", animation = app.bekocat.core.timeline.TextAnimation(presetIn = "reveal-side", presetOut = "mask-hide"))) }) { Text("Lower third") }
            OutlinedButton(onClick = { vm.addText("Bekocat 2027", TextStyle("", sizeSp = 72.0, strokeArgb = 0xFF000000, strokeWidth = 6.0, animation = app.bekocat.core.timeline.TextAnimation(presetIn = "typewriter"))) }) { Text(tr("آلة كاتبة", "Typewriter")) }
        }
        return
    }
    val s = c.text!!
    fun upd(label: String, f: (TextStyle) -> TextStyle) = vm.updateSelected(label) { cl -> cl.copy(text = f(cl.text!!), name = f(cl.text!!).text.take(24)) }
    var text by remember(c.id) { mutableStateOf(s.text) }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2)
    Button(onClick = { upd("text") { it.copy(text = text) } }) { Text(tr("تحديث النص", "Update text")) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.importMedia(listOf(it)) } }
    SectionTitle(tr("الخط", "Font"))
    val fonts = listOf("default", "serif", "mono") + p.assets.filter { it.kind == AssetKind.FONT }.map { it.id }
    ChipRow(fonts, s.fontFamily, { id -> p.asset(id)?.displayName ?: id }) { f -> upd("font") { it.copy(fontFamily = f) } }
    TextButton(onClick = { fontPicker.launch(arrayOf("font/*", "application/octet-stream", "*/*")) }) { Text(tr("استيراد خط TTF/OTF مرخّص", "Import licensed TTF/OTF font")) }
    PropertyRow(tr("الحجم", "Size"), s.sizeSp, 8.0..300.0, { "${it.toInt()}" }, defaultValue = 48.0) { v -> upd("text-size") { it.copy(sizeSp = v) } }
    PropertyRow(tr("الوزن", "Weight"), s.weight.toDouble(), 100.0..900.0, { "${it.toInt()}" }, defaultValue = 700.0) { v -> upd("weight") { it.copy(weight = (v / 100).toInt() * 100) } }
    PropertyRow(tr("تباعد الحروف", "Letter spacing"), s.letterSpacing, -0.1..0.5, defaultValue = 0.0) { v -> upd("tracking") { it.copy(letterSpacing = v) } }
    Hint(tr("تباعد الحروف في العربية يُطبّق بحذر لأنه قد يفصل الاتصال في بعض الخطوط.", "Letter spacing on Arabic is applied carefully; some fonts may break joining."))
    PropertyRow(tr("تباعد الأسطر", "Line height"), s.lineHeight, 0.7..2.5, defaultValue = 1.2) { v -> upd("leading") { it.copy(lineHeight = v) } }
    PropertyRow(tr("عرض الصندوق", "Box width"), s.boxWidth, 0.1..1.0, { "${(it * 100).toInt()}%" }, defaultValue = 0.85) { v -> upd("box") { it.copy(boxWidth = v) } }
    ChipRow(listOf("start", "center", "end"), s.alignment, { mapOf("start" to tr("بداية", "Start"), "center" to tr("وسط", "Center"), "end" to tr("نهاية", "End"))[it]!! }) { a -> upd("align") { it.copy(alignment = a) } }
    ChipRow(listOf<Boolean?>(null, true, false), s.rtl, { when (it) { null -> tr("اتجاه تلقائي", "Auto bidi"); true -> "RTL"; false -> "LTR" } }) { d -> upd("direction") { it.copy(rtl = d) } }
    SectionTitle(tr("نص على مسار", "Text on path"))
    ChipRow(listOf("none", "arc-up", "arc-down", "wave", "circle"), TextPaths.idOf(s.path), { mapOf("none" to tr("بدون", "None"), "arc-up" to tr("قوس أعلى", "Arc up"), "arc-down" to tr("قوس أسفل", "Arc down"), "wave" to tr("موجة", "Wave"), "circle" to tr("دائرة", "Circle"))[it]!! }) { id -> upd("text-path") { it.copy(path = TextPaths.of(id)) } }
    SectionTitle(tr("الألوان والحواف", "Colours & strokes"))
    Text(tr("لون النص", "Fill"), color = BkColors.Muted); ColorSwatches(s.colorArgb, PALETTE) { col -> upd("fill") { it.copy(colorArgb = col) } }
    Text(tr("تدرج (اللون الثاني)", "Gradient (2nd colour)"), color = BkColors.Muted); ColorSwatches(s.gradientArgb ?: 0L, listOf(0L) + PALETTE) { col -> upd("gradient") { it.copy(gradientArgb = col.takeIf { c2 -> c2 != 0L }) } }
    Text(tr("الحد (Stroke)", "Stroke"), color = BkColors.Muted); ColorSwatches(s.strokeArgb, PALETTE) { col -> upd("stroke") { it.copy(strokeArgb = col, strokeWidth = if (it.strokeWidth == 0.0) 4.0 else it.strokeWidth) } }
    PropertyRow(tr("سماكة الحد", "Stroke width"), s.strokeWidth, 0.0..30.0, defaultValue = 0.0) { v -> upd("stroke-w") { it.copy(strokeWidth = v) } }
    Text(tr("الظل", "Shadow"), color = BkColors.Muted); ColorSwatches(s.shadowArgb, PALETTE) { col -> upd("shadow") { it.copy(shadowArgb = col, shadowBlur = if (it.shadowBlur == 0.0) 8.0 else it.shadowBlur, shadowDy = if (it.shadowDy == 0.0) 4.0 else it.shadowDy) } }
    PropertyRow(tr("نعومة الظل", "Shadow blur"), s.shadowBlur, 0.0..40.0, defaultValue = 0.0) { v -> upd("shadow-b") { it.copy(shadowBlur = v) } }
    Text(tr("التوهج (Glow)", "Glow"), color = BkColors.Muted); ColorSwatches(s.glowArgb, PALETTE) { col -> upd("glow") { it.copy(glowArgb = col, glowRadius = if (it.glowRadius == 0.0) 14.0 else it.glowRadius) } }
    PropertyRow(tr("نصف قطر التوهج", "Glow radius"), s.glowRadius, 0.0..60.0, defaultValue = 0.0) { v -> upd("glow-r") { it.copy(glowRadius = v) } }
    Text(tr("الخلفية", "Background"), color = BkColors.Muted); ColorSwatches(s.backgroundArgb, PALETTE) { col -> upd("bg") { it.copy(backgroundArgb = col) } }
    PropertyRow(tr("حشوة الخلفية", "Background padding"), s.backgroundPadding, 0.0..60.0, defaultValue = 12.0) { v -> upd("bg-pad") { it.copy(backgroundPadding = v) } }
    PropertyRow(tr("استدارة الخلفية", "Background radius"), s.backgroundRadius, 0.0..60.0, defaultValue = 10.0) { v -> upd("bg-r") { it.copy(backgroundRadius = v) } }
    SectionTitle(tr("تحريك النص", "Text animation") + " (${TextPresetCatalog.all.size})")
    val ar = isArabic()
    fun presets(phase: String) = listOf<String?>(null) + TextPresetCatalog.all.filter { it.phase == phase }.map { it.id }
    fun name(id: String?) = id?.let { TextPresetCatalog.find(it)?.let { p -> if (ar) p.ar else p.en } } ?: if (ar) "بدون" else "None"
    Text(tr("دخول", "In"), color = BkColors.Muted); ChipRow(presets("in"), s.animation.presetIn, { name(it) }) { id -> upd("anim-in") { it.copy(animation = it.animation.copy(presetIn = id)) } }
    Text(tr("خروج", "Out"), color = BkColors.Muted); ChipRow(presets("out"), s.animation.presetOut, { name(it) }) { id -> upd("anim-out") { it.copy(animation = it.animation.copy(presetOut = id)) } }
    Text(tr("تكرار", "Loop"), color = BkColors.Muted); ChipRow(presets("loop"), s.animation.presetLoop, { name(it) }) { id -> upd("anim-loop") { it.copy(animation = it.animation.copy(presetLoop = id)) } }
    PropertyRow(tr("مدة الدخول", "In duration"), s.animation.durationIn.toDouble(), 0.05..4.0, { "%.2fs".format(it) }) { v -> upd("anim-din") { it.copy(animation = it.animation.copy(durationIn = Rational.fromDouble(v, 1000))) } }
    PropertyRow(tr("مدة الخروج", "Out duration"), s.animation.durationOut.toDouble(), 0.05..4.0, { "%.2fs".format(it) }) { v -> upd("anim-dout") { it.copy(animation = it.animation.copy(durationOut = Rational.fromDouble(v, 1000))) } }
    PropertyRow(tr("التتابع (Stagger)", "Stagger"), s.animation.stagger.toDouble(), 0.0..0.5, { "%.3fs".format(it) }) { v -> upd("anim-stagger") { it.copy(animation = it.animation.copy(stagger = Rational.fromDouble(v, 1000))) } }
    PropertyRow(tr("الشدة", "Intensity"), s.animation.intensity, 0.0..2.0, defaultValue = 1.0) { v -> upd("anim-int") { it.copy(animation = it.animation.copy(intensity = v)) } }
    ToggleRow(tr("ترتيب عكسي", "Reverse order"), s.animation.reverseOrder) { v -> upd("anim-rev") { it.copy(animation = it.animation.copy(reverseOrder = v)) } }
    SectionTitle(tr("إبراز كلمات", "Highlight words"))
    val words = s.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    Row(Modifier.fillMaxWidth()) {
        ChipRow(words.indices.toList(), null, { words[it] + if (it in s.highlightWords) " ✓" else "" }) { i -> upd("highlight") { st2 -> st2.copy(highlightWords = if (i in st2.highlightWords) st2.highlightWords - i else st2.highlightWords + i) } }
    }
    ColorSwatches(s.highlightArgb, PALETTE) { col -> upd("highlight-c") { it.copy(highlightArgb = col) } }
    SectionTitle(tr("نص على مسار", "Text on path"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { upd("path") { it.copy(path = listOf(0.05, 0.7, 0.5, 0.2, 0.95, 0.7)) } }) { Text(tr("قوس", "Arc")) }
        OutlinedButton(onClick = { upd("path") { it.copy(path = listOf(0.05, 0.5, 0.3, 0.3, 0.5, 0.5, 0.7, 0.7, 0.95, 0.5)) } }) { Text(tr("موجة", "Wave")) }
        TextButton(onClick = { upd("path") { it.copy(path = null) } }) { Text(tr("إزالة", "Remove")) }
    }
}

@Composable
fun ShapePanel(st: EditorState, vm: EditorViewModel) {
    val c = st.selected?.takeIf { it.kind == ClipKind.SHAPE }
    SectionTitle(tr("إضافة شكل", "Add shape"))
    ChipRow(ShapeType.entries, null, { mapOf(ShapeType.RECT to tr("مستطيل", "Rect"), ShapeType.ELLIPSE to tr("دائرة", "Ellipse"), ShapeType.STAR to tr("نجمة", "Star"),
        ShapeType.POLYGON to tr("مضلع", "Polygon"), ShapeType.LINE to tr("خط", "Line"), ShapeType.PATH to "Bezier")[it]!! }) { vm.addShape(it) }
    if (c == null) return
    val s = c.shape!!
    fun upd(label: String, f: (ShapeStyle) -> ShapeStyle) = vm.updateSelected(label) { it.copy(shape = f(it.shape!!)) }
    fun sp(key: String, ar: String, en: String, min: Double, max: Double, def: Double, g: (ShapeStyle) -> Animatable, set: (ShapeStyle, Animatable) -> ShapeStyle) =
        Prop(key, ar, en, min, max, def, { cl -> g(cl.shape!!) }, { cl, a -> cl.copy(shape = set(cl.shape!!, a)) })
    AnimRow(vm, c, sp("shW", "العرض", "Width", 0.0, 2.0, 0.4, { it.width }, { x, a -> x.copy(width = a) }))
    AnimRow(vm, c, sp("shH", "الارتفاع", "Height", 0.0, 2.0, 0.4, { it.height }, { x, a -> x.copy(height = a) }))
    AnimRow(vm, c, sp("shR", "الاستدارة", "Corner radius", 0.0, 0.5, 0.0, { it.cornerRadius }, { x, a -> x.copy(cornerRadius = a) }))
    Text(tr("التعبئة", "Fill"), color = BkColors.Muted); ColorSwatches(s.fillArgb, PALETTE) { col -> upd("fill") { it.copy(fillArgb = col) } }
    Text(tr("تدرج", "Gradient"), color = BkColors.Muted); ColorSwatches(s.fillGradientArgb ?: 0L, listOf(0L) + PALETTE) { col -> upd("gradient") { it.copy(fillGradientArgb = col.takeIf { it != 0L }) } }
    PropertyRow(tr("زاوية التدرج", "Gradient angle"), s.fillGradientAngle, 0.0..360.0, { "%.0f°".format(it) }) { v -> upd("grad-a") { it.copy(fillGradientAngle = v) } }
    Text(tr("الحد", "Stroke"), color = BkColors.Muted); ColorSwatches(s.strokeArgb, PALETTE) { col -> upd("stroke") { it.copy(strokeArgb = col) } }
    AnimRow(vm, c, sp("shSW", "سماكة الحد", "Stroke width", 0.0, 60.0, 0.0, { it.strokeWidth }, { x, a -> x.copy(strokeWidth = a) }))
    ToggleRow(tr("حد متقطع", "Dashed stroke"), s.dash.isNotEmpty()) { v -> upd("dash") { it.copy(dash = if (v) listOf(24.0, 14.0) else emptyList()) } }
    SectionTitle("Trim Paths")
    AnimRow(vm, c, sp("trS", "البداية", "Start", 0.0, 1.0, 0.0, { it.trimStart }, { x, a -> x.copy(trimStart = a) }))
    AnimRow(vm, c, sp("trE", "النهاية", "End", 0.0, 1.0, 1.0, { it.trimEnd }, { x, a -> x.copy(trimEnd = a) }))
    AnimRow(vm, c, sp("trO", "الإزاحة", "Offset", -1.0, 1.0, 0.0, { it.trimOffset }, { x, a -> x.copy(trimOffset = a) }))
    if (s.type == ShapeType.STAR || s.type == ShapeType.POLYGON) {
        PropertyRow(tr("عدد النقاط", "Points"), s.points.toDouble(), 3.0..24.0, { "${it.toInt()}" }) { v -> upd("points") { it.copy(points = v.toInt()) } }
        if (s.type == ShapeType.STAR) PropertyRow(tr("نصف القطر الداخلي", "Inner radius"), s.innerRadius, 0.1..0.95) { v -> upd("inner") { it.copy(innerRadius = v) } }
    }
    SectionTitle(tr("التكرار (Repeater)", "Repeater"))
    PropertyRow(tr("العدد", "Copies"), s.repeatCount.toDouble(), 1.0..32.0, { "${it.toInt()}" }, defaultValue = 1.0) { v -> upd("rep") { it.copy(repeatCount = v.toInt()) } }
    PropertyRow(tr("إزاحة X", "Offset X"), s.repeatOffsetX, -0.5..0.5) { v -> upd("rep-x") { it.copy(repeatOffsetX = v) } }
    PropertyRow(tr("إزاحة Y", "Offset Y"), s.repeatOffsetY, -0.5..0.5) { v -> upd("rep-y") { it.copy(repeatOffsetY = v) } }
    PropertyRow(tr("دوران", "Rotation"), s.repeatRotation, -180.0..180.0) { v -> upd("rep-r") { it.copy(repeatRotation = v) } }
    PropertyRow(tr("حجم", "Scale"), s.repeatScale, 0.5..1.5, defaultValue = 1.0) { v -> upd("rep-s") { it.copy(repeatScale = v) } }
    PropertyRow(tr("شفافية آخر نسخة", "Last copy opacity"), s.repeatOpacityEnd, 0.0..1.0, defaultValue = 1.0) { v -> upd("rep-o") { it.copy(repeatOpacityEnd = v) } }
}

/** Built-in text paths (offsets from layer centre, in frame units). */
object TextPaths {
    private fun gen(n: Int, f: (Double) -> Pair<Double, Double>) = (0..n).flatMap { val (x, y) = f(it / n.toDouble()); listOf(x, y) }
    private val defs: Map<String, List<Double>> = mapOf(
        "arc-up" to gen(24) { t -> val a = Math.PI * (1 - t); 0.35 * Math.cos(a) to -0.18 * Math.sin(a) + 0.09 },
        "arc-down" to gen(24) { t -> val a = Math.PI * (1 + t); 0.35 * Math.cos(a) to -0.18 * Math.sin(a) - 0.09 },
        "wave" to gen(32) { t -> (t - 0.5) * 0.8 to 0.05 * Math.sin(t * Math.PI * 4) },
        "circle" to gen(48) { t -> val a = Math.PI * (1 - 2 * t) ; 0.22 * Math.cos(a) to -0.22 * Math.sin(a) * 9.0 / 16.0 * 1.6 },
    )
    fun of(id: String): List<Double>? = defs[id]
    fun idOf(path: List<Double>?): String = if (path == null) "none" else defs.entries.firstOrNull { it.value == path }?.key ?: "none"
}
