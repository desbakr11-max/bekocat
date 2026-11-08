package app.bekocat.editor.panels

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.bekocat.core.timeline.AssetKind
import app.bekocat.core.timeline.ColorAdjust
import app.bekocat.core.timeline.ColorMath
import app.bekocat.core.timeline.EffectCatalog
import app.bekocat.core.timeline.EffectInstance
import app.bekocat.core.timeline.LookCatalog
import app.bekocat.core.timeline.Animatable
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.Transition
import app.bekocat.core.timeline.TransitionAlign
import app.bekocat.core.timeline.TransitionCatalog
import app.bekocat.core.timeline.TransitionKind
import app.bekocat.editor.EditorState
import app.bekocat.editor.EditorViewModel
import app.bekocat.editor.Prop
import app.bekocat.ui.components.ChipRow
import app.bekocat.ui.components.Hint
import app.bekocat.ui.components.PropertyRow
import app.bekocat.ui.components.SectionTitle
import app.bekocat.ui.components.ToggleRow
import app.bekocat.ui.components.isArabic
import app.bekocat.ui.components.tr
import app.bekocat.ui.theme.BkColors
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

@Composable
fun ColorPanel(st: EditorState, vm: EditorViewModel) = NeedsSelection(st) { c ->
    val col = c.color
    fun upd(label: String, f: (ColorAdjust) -> ColorAdjust) = vm.updateSelected(label) { it.copy(color = f(it.color)) }
    fun prev(f: (ColorAdjust) -> ColorAdjust) = vm.previewSelected { it.copy(color = f(it.color)) }
    val ar = isArabic()
    SectionTitle(tr("المظاهر الجاهزة", "Looks") + " (${LookCatalog.all.size})")
    ChipRow(listOf<String?>(null) + LookCatalog.all.map { it.id }, null, { id -> id?.let { LookCatalog.find(it)?.let { l -> if (ar) l.ar else l.en } } ?: tr("إعادة ضبط", "Reset") }) { id ->
        val look = id?.let { LookCatalog.find(it) }
        vm.updateSelected("look") { cl ->
            if (look == null) cl.copy(color = ColorAdjust(), effects = cl.effects.filterNot { it.options["fromLook"] == "1" })
            else cl.copy(color = look.color, effects = cl.effects.filterNot { it.options["fromLook"] == "1" } + look.effects.map { (type, params) ->
                val spec = EffectCatalog.find(type)!!
                EffectInstance(vm.repo.ids.next("fx"), type, params = spec.params.associate { it.key to Animatable.of(params[it.key] ?: it.default) }, options = mapOf("fromLook" to "1"))
            })
        }
    }
    Scopes(vm)
    SectionTitle(tr("التصحيح الأساسي", "Primary correction"))
    fun row(ar2: String, en: String, v: Double, r: ClosedFloatingPointRange<Double>, set: (ColorAdjust, Double) -> ColorAdjust) =
        Triple(ar2 to en, v, r) to set
    val rows = listOf(
        row("التعريض (Exposure)", "Exposure", col.exposure, -4.0..4.0) { a, v -> a.copy(exposure = v) },
        row("التباين", "Contrast", col.contrast, -1.0..1.0) { a, v -> a.copy(contrast = v) },
        row("الإضاءات (Highlights)", "Highlights", col.highlights, -1.0..1.0) { a, v -> a.copy(highlights = v) },
        row("الظلال (Shadows)", "Shadows", col.shadows, -1.0..1.0) { a, v -> a.copy(shadows = v) },
        row("الأبيض (Whites)", "Whites", col.whites, -1.0..1.0) { a, v -> a.copy(whites = v) },
        row("الأسود (Blacks)", "Blacks", col.blacks, -1.0..1.0) { a, v -> a.copy(blacks = v) },
        row("الحرارة", "Temperature", col.temperature, -1.0..1.0) { a, v -> a.copy(temperature = v) },
        row("الصبغة (Tint)", "Tint", col.tint, -1.0..1.0) { a, v -> a.copy(tint = v) },
        row("التشبع", "Saturation", col.saturation, -1.0..1.0) { a, v -> a.copy(saturation = v) },
        row("الحيوية (Vibrance)", "Vibrance", col.vibrance, -1.0..1.0) { a, v -> a.copy(vibrance = v) },
    )
    for ((info, set) in rows) {
        val (names, v, r) = info
        PropertyRow(tr(names.first, names.second), v, r, { "%+.2f".format(it) }, defaultValue = 0.0, onPreview = { x -> prev { set(it, x) } }) { x -> upd(names.second) { set(it, x) } }
    }
    SectionTitle(tr("عجلات الألوان (Lift / Gamma / Gain)", "Colour wheels (Lift / Gamma / Gain)"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorWheel(tr("الظلال", "Lift"), col.lift, Modifier.weight(1f)) { v, commit -> if (commit) upd("lift") { it.copy(lift = v) } else prev { it.copy(lift = v) } }
        ColorWheel(tr("الوسط", "Gamma"), col.gamma, Modifier.weight(1f)) { v, commit -> if (commit) upd("gamma") { it.copy(gamma = v) } else prev { it.copy(gamma = v) } }
        ColorWheel(tr("الإضاءة", "Gain"), col.gain, Modifier.weight(1f)) { v, commit -> if (commit) upd("gain") { it.copy(gain = v) } else prev { it.copy(gain = v) } }
    }
    SectionTitle(tr("المنحنيات (Curves)", "Curves"))
    var ch by remember { mutableIntStateOf(0) }
    ChipRow(listOf(0, 1, 2, 3), ch, { listOf("Luma", "R", "G", "B")[it] }) { ch = it }
    val pts = when (ch) { 0 -> col.curveMaster; 1 -> col.curveRed; 2 -> col.curveGreen; else -> col.curveBlue }
    CurveEditor(pts, listOf(Color.White, Color(0xFFFF6577), Color(0xFF6EE7C8), Color(0xFF3BA3FF))[ch]) { np, commit ->
        val f: (ColorAdjust) -> ColorAdjust = { a -> when (ch) { 0 -> a.copy(curveMaster = np); 1 -> a.copy(curveRed = np); 2 -> a.copy(curveGreen = np); else -> a.copy(curveBlue = np) } }
        if (commit) upd("curves", f) else prev(f)
    }
    SectionTitle("HSL")
    var band by remember { mutableIntStateOf(0) }
    val bands = listOf(tr("أحمر", "Red"), tr("برتقالي", "Orange"), tr("أصفر", "Yellow"), tr("أخضر", "Green"), tr("سماوي", "Aqua"), tr("أزرق", "Blue"), tr("بنفسجي", "Purple"), tr("وردي", "Magenta"))
    ChipRow((0 until 8).toList(), band, { bands[it] }) { band = it }
    PropertyRow(tr("إزاحة اللون", "Hue"), col.hslHue[band], -60.0..60.0, { "%+.0f°".format(it) }, defaultValue = 0.0) { v -> upd("hsl") { it.copy(hslHue = it.hslHue.toMutableList().also { l -> l[band] = v }) } }
    PropertyRow(tr("التشبع", "Saturation"), col.hslSat[band], -1.0..1.0, defaultValue = 0.0) { v -> upd("hsl") { it.copy(hslSat = it.hslSat.toMutableList().also { l -> l[band] = v }) } }
    PropertyRow(tr("الإضاءة", "Luminance"), col.hslLum[band], -1.0..1.0, defaultValue = 0.0) { v -> upd("hsl") { it.copy(hslLum = it.hslLum.toMutableList().also { l -> l[band] = v }) } }
    SectionTitle("LUT (.cube)")
    val p = st.project!!
    val luts = p.assets.filter { it.kind == AssetKind.LUT }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.importMedia(listOf(it)) } }
    ChipRow(listOf<String?>(null) + luts.map { it.id }, col.lutAssetId, { id -> id?.let { p.asset(it)?.displayName } ?: tr("بدون", "None") }) { id -> upd("lut") { it.copy(lutAssetId = id) } }
    TextButton(onClick = { lutPicker.launch(arrayOf("*/*")) }) { Text(tr("استيراد LUT", "Import LUT")) }
    if (col.lutAssetId != null) PropertyRow(tr("شدة LUT", "LUT intensity"), col.lutIntensity, 0.0..1.0, defaultValue = 1.0) { v -> upd("lut-mix") { it.copy(lutIntensity = v) } }
    SectionTitle(tr("مطابقة اللون", "Colour match"))
    Hint(tr("يطابق متوسط وتباين ألوان المقطع المحدد مع الإطار المرجعي عند العلامة الأولى أو بداية المشروع.", "Matches the selected clip's colour statistics to the reference frame at the first marker (or project start)."))
    Button(onClick = { vm.matchColorToReference() }) { Text(tr("مطابقة مع المرجع", "Match to reference")) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.toggle { it.copy(beforeAfter = !it.beforeAfter) }; vm.applyBeforeAfter() }) { Text(if (st.beforeAfter) tr("عرض بعد", "Show after") else tr("عرض قبل", "Show before")) }
        TextButton(onClick = { upd("color-reset") { ColorAdjust() } }) { Text(tr("إعادة ضبط اللون", "Reset colour")) }
    }
}

/** Waveform, histogram and vectorscope computed from a rendered preview frame. */
@Composable
private fun Scopes(vm: EditorViewModel) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var mode by remember { mutableIntStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow(listOf(0, 1, 2), mode, { listOf("Waveform", "Histogram", "Vectorscope")[it] }) { mode = it }
        TextButton(onClick = { vm.preview.snapshot(vm.state.value.playhead, 256) { b -> bmp = b } }) { Text("↻") }
    }
    val b = bmp
    if (b == null) { Hint(tr("اضغط ↻ لقياس الإطار الحالي.", "Tap ↻ to measure the current frame.")); return }
    val px = remember(b) { IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) } }
    Canvas(Modifier.fillMaxWidth().height(140.dp).background(Color.Black, RoundedCornerShape(6.dp))) {
        when (mode) {
            0 -> {
                val step = max(1, px.size / 40000)
                var i = 0
                while (i < px.size) {
                    val p = px[i]; val x = (i % b.width).toFloat() / b.width * size.width
                    val l = (0.2126 * ((p shr 16) and 255) + 0.7152 * ((p shr 8) and 255) + 0.0722 * (p and 255)) / 255
                    drawRect(Color(0x3069F0AE), Offset(x, ((1 - l) * size.height).toFloat()), Size(1.5f, 1.5f))
                    i += step
                }
            }
            1 -> {
                val h = Array(3) { IntArray(256) }
                for (p in px) { h[0][(p shr 16) and 255]++; h[1][(p shr 8) and 255]++; h[2][p and 255]++ }
                val mx = h.maxOf { it.max() }.coerceAtLeast(1)
                val cols = listOf(Color(0x99FF5252), Color(0x9969F0AE), Color(0x99448AFF))
                for (c in 0..2) {
                    val path = Path(); path.moveTo(0f, size.height)
                    for (k in 0..255) path.lineTo(k / 255f * size.width, size.height - h[c][k].toFloat() / mx * size.height)
                    path.lineTo(size.width, size.height); path.close()
                    drawPath(path, cols[c])
                }
            }
            else -> {
                val cx = size.width / 2; val cy = size.height / 2; val r = size.height / 2 - 4
                drawCircle(Color(0x33FFFFFF), r, Offset(cx, cy), style = Stroke(1f))
                val step = max(1, px.size / 20000)
                var i = 0
                while (i < px.size) {
                    val p = px[i]; val rr = ((p shr 16) and 255) / 255.0; val g = ((p shr 8) and 255) / 255.0; val bb = (p and 255) / 255.0
                    val y = 0.2126 * rr + 0.7152 * g + 0.0722 * bb
                    val cb = (bb - y) / 1.8556; val crr = (rr - y) / 1.5748
                    drawRect(Color(0x5069F0AE), Offset((cx + cb * r * 2).toFloat(), (cy - crr * r * 2).toFloat()), Size(1.5f, 1.5f))
                    i += step
                }
            }
        }
    }
}

@Composable
private fun ColorWheel(label: String, v: List<Double>, modifier: Modifier, onChange: (List<Double>, Boolean) -> Unit) {
    var local by remember(v) { mutableStateOf(v) }
    androidx.compose.foundation.layout.Column(modifier) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
            fun toVal(o: Offset): List<Double> {
                val cx = size.width / 2f; val cy = size.height / 2f
                val dx = (o.x - cx) / cx; val dy = (o.y - cy) / cy
                val ang = atan2(-dy, dx); val mag = sqrt(dx * dx + dy * dy).coerceAtMost(1f) * 0.3
                // Hue direction → RGB offsets (R at 0°, G at 120°, B at 240°).
                fun ch(a0: Double) = mag * kotlin.math.cos(ang - a0)
                return listOf(ch(0.0), ch(2.094), ch(4.189))
            }
            detectDragGestures(onDragEnd = { onChange(local, true) }) { change, _ -> local = toVal(change.position); onChange(local, false) }
        }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { local = listOf(0.0, 0.0, 0.0); onChange(local, true) }) }) {
            val r = size.minDimension / 2
            for (k in 0 until 36) {
                val a = k * 10.0
                drawArc(Color.hsv(a.toFloat(), 0.6f, 0.8f), -a.toFloat() - 5, 10f, true, alpha = 0.6f)
            }
            drawCircle(Color(0xFF141821), r * 0.15f, center)
            // Current offset dot.
            val x = (local[0] - 0.5 * (local[1] + local[2])); val y = (local[1] - local[2]) * 0.866
            drawCircle(Color.White, 8f, Offset(center.x + (x / 0.3 * r).toFloat(), center.y - (y / 0.3 * r).toFloat()))
        }
        Text(label, color = BkColors.Muted)
    }
}

@Composable
private fun CurveEditor(points: List<Double>, color: Color, onChange: (List<Double>, Boolean) -> Unit) {
    val base = if (points.size >= 4) points else listOf(0.0, 0.0, 1.0, 1.0)
    var pts by remember(points) { mutableStateOf(base.chunked(2).map { it[0] to it[1] }) }
    var drag by remember { mutableIntStateOf(-1) }
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.6f).background(Color.Black, RoundedCornerShape(6.dp))
        .pointerInput(points) {
            detectTapGestures(onDoubleTap = { o ->
                val x = (o.x / size.width).toDouble(); val i = pts.indexOfFirst { kotlin.math.abs(it.first - x) < 0.05 }
                if (i > 0 && i < pts.size - 1) { pts = pts.toMutableList().also { it.removeAt(i) }; onChange(pts.flatMap { listOf(it.first, it.second) }, true) }
            }, onTap = { o ->
                val np = (o.x / size.width).toDouble().coerceIn(0.0, 1.0) to (1 - o.y / size.height).toDouble().coerceIn(0.0, 1.0)
                pts = (pts + np).sortedBy { it.first }; onChange(pts.flatMap { listOf(it.first, it.second) }, true)
            })
        }
        .pointerInput(points) {
            detectDragGestures(onDragStart = { o -> drag = pts.indices.minByOrNull { (Offset((pts[it].first * size.width).toFloat(), ((1 - pts[it].second) * size.height).toFloat()) - o).getDistance() } ?: -1 },
                onDragEnd = { drag = -1; onChange(pts.flatMap { listOf(it.first, it.second) }, true) }) { ch, _ ->
                if (drag < 0) return@detectDragGestures
                val lo = if (drag == 0) 0.0 else pts[drag - 1].first + 0.01; val hi = if (drag == pts.size - 1) 1.0 else pts[drag + 1].first - 0.01
                val nx = if (drag == 0 || drag == pts.size - 1) pts[drag].first else (ch.position.x / size.width).toDouble().coerceIn(lo, hi)
                pts = pts.toMutableList().also { it[drag] = nx to (1 - ch.position.y / size.height).toDouble().coerceIn(0.0, 1.0) }
                onChange(pts.flatMap { listOf(it.first, it.second) }, false)
            }
        }) {
        for (i in 1..3) { drawLine(Color(0x22FFFFFF), Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height)); drawLine(Color(0x22FFFFFF), Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4)) }
        val table = ColorMath.curveTable(pts.flatMap { listOf(it.first, it.second) })
        val path = Path()
        for (k in 0..255) { val x = k / 255f * size.width; val y = (1 - table[k] / 255f) * size.height; if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        drawPath(path, color, style = Stroke(2.5f))
        for (p in pts) drawCircle(color, 8f, Offset((p.first * size.width).toFloat(), ((1 - p.second) * size.height).toFloat()))
    }
    Hint(tr("انقر لإضافة نقطة، اسحب لتعديلها، انقر مرتين لحذفها.", "Tap to add a point, drag to move, double-tap to delete."))
}

@Composable
fun EffectsPanel(st: EditorState, vm: EditorViewModel) = NeedsSelection(st) { c ->
    val ar = isArabic()
    SectionTitle(tr("سلسلة المؤثرات (Effect Stack)", "Effect stack"))
    if (c.effects.isEmpty()) Hint(tr("لا توجد مؤثرات. أضف من القائمة أدناه.", "No effects yet. Add from the list below."))
    c.effects.forEachIndexed { i, e ->
        val spec = EffectCatalog.find(e.type) ?: return@forEachIndexed
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((if (ar) spec.ar else spec.en) + if (!e.enabled) " (Bypass)" else "", Modifier.weight(1f), color = if (e.enabled) BkColors.Text else BkColors.Muted)
            TextButton(onClick = { vm.updateSelected("fx-order") { cl -> cl.copy(effects = cl.effects.toMutableList().also { l -> if (i > 0) { val x = l.removeAt(i); l.add(i - 1, x) } }) } }) { Text("▲") }
            TextButton(onClick = { vm.updateSelected("fx-order") { cl -> cl.copy(effects = cl.effects.toMutableList().also { l -> if (i < l.size - 1) { val x = l.removeAt(i); l.add(i + 1, x) } }) } }) { Text("▼") }
            TextButton(onClick = { vm.updateSelected("fx-bypass") { cl -> cl.copy(effects = cl.effects.map { if (it.id == e.id) it.copy(enabled = !it.enabled) else it }) } }) { Text(if (e.enabled) "⏻" else "○") }
            TextButton(onClick = { vm.updateSelected("fx-remove") { cl -> cl.copy(effects = cl.effects.filterNot { it.id == e.id }) } }) { Text("✕") }
        }
        for (ps in spec.params) {
            val prop = Prop("fx:${e.id}:${ps.key}", ps.ar, ps.en, ps.min, ps.max, ps.default,
                { cl -> cl.effects.firstOrNull { it.id == e.id }?.params?.get(ps.key) ?: Animatable.of(ps.default) },
                { cl, a -> cl.copy(effects = cl.effects.map { if (it.id == e.id) it.copy(params = it.params + (ps.key to a)) else it }) })
            AnimRow(vm, c, prop)
        }
    }
    SectionTitle(tr("إضافة مؤثر", "Add effect") + " (${EffectCatalog.video.size})")
    for ((cat, list) in EffectCatalog.video.groupBy { it.category }) {
        Text(cat, color = BkColors.Muted)
        ChipRow(list, null, { if (ar) it.ar else it.en }) { spec -> vm.updateSelected("fx-add") { cl -> cl.copy(effects = cl.effects + spec.instance(vm.repo.ids.next("fx"))) } }
    }
}

@Composable
fun TransitionPanel(st: EditorState, vm: EditorViewModel) = NeedsSelection(st) { c ->
    val ar = isArabic()
    val tr0 = c.transitionIn
    Hint(tr("الانتقال يُطبّق عند بداية المقطع المحدد (القطع مع المقطع السابق على نفس المسار).", "The transition applies at the start of the selected clip (cut with the previous clip on the same track)."))
    for ((fam, kinds) in TransitionKind.entries.groupBy { it.family }) {
        Text(fam, color = BkColors.Muted)
        ChipRow(kinds, tr0?.kind, { k -> TransitionCatalog.names[k]!!.let { if (ar) it.first else it.second } }) { k ->
            vm.updateSelected("transition") { it.copy(transitionIn = (it.transitionIn ?: Transition(k, Rational.of(1, 2))).copy(kind = k)) }
        }
    }
    if (tr0 == null) return@NeedsSelection
    fun upd(label: String, f: (Transition) -> Transition) = vm.updateSelected(label) { it.copy(transitionIn = f(it.transitionIn!!)) }
    PropertyRow(tr("المدة", "Duration"), tr0.duration.toDouble(), 0.05..3.0, { "%.2fs".format(it) }, defaultValue = 0.5) { v -> upd("tr-dur") { it.copy(duration = st.project!!.sequence.frameRate.snap(Rational.fromDouble(v, 1_000_000))) } }
    ChipRow(TransitionAlign.entries, tr0.align, { when (it) { TransitionAlign.CENTER -> tr("على القطع", "Center"); TransitionAlign.BEFORE_CUT -> tr("قبل القطع", "Before cut"); TransitionAlign.AFTER_CUT -> tr("بعد القطع", "After cut") } }) { a -> upd("tr-align") { it.copy(align = a) } }
    PropertyRow(tr("الشدة", "Intensity"), tr0.intensity, 0.0..2.0, defaultValue = 1.0) { v -> upd("tr-int") { it.copy(intensity = v) } }
    PropertyRow(tr("الاتجاه", "Direction"), tr0.direction, 0.0..360.0, { "%.0f°".format(it) }) { v -> upd("tr-dir") { it.copy(direction = v) } }
    ChipRow(listOf(listOf(0.0, 0.0, 1.0, 1.0), listOf(0.42, 0.0, 0.58, 1.0), listOf(0.0, 0.0, 0.2, 1.0), listOf(0.8, 0.0, 1.0, 1.0)), tr0.easing,
        { listOf("Linear", "Ease", "Ease out", "Ease in")[listOf(listOf(0.0, 0.0, 1.0, 1.0), listOf(0.42, 0.0, 0.58, 1.0), listOf(0.0, 0.0, 0.2, 1.0), listOf(0.8, 0.0, 1.0, 1.0)).indexOf(it)] }) { e -> upd("tr-ease") { it.copy(easing = e) } }
    ToggleRow(tr("ربط انتقال الصوت (Crossfade)", "Link audio crossfade"), tr0.linkAudio) { v -> upd("tr-audio") { it.copy(linkAudio = v) } }
    val maskPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.setTransitionMaskImage(it) } }
    if (tr0.kind == TransitionKind.CUSTOM_MASK || tr0.kind == TransitionKind.LUMA_FADE) TextButton(onClick = { maskPicker.launch(arrayOf("image/*")) }) { Text(tr("اختيار صورة تدرج للقناع", "Pick gradient image for the mask")) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.applyTransitionToAll(tr0) }) { Text(tr("تطبيق على كل القطعات", "Apply to all cuts")) }
        TextButton(onClick = { vm.updateSelected("tr-remove") { it.copy(transitionIn = null) } }) { Text(tr("إزالة", "Remove")) }
    }
}
