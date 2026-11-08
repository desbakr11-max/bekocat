package app.bekocat.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.bekocat.core.timeline.Animatable
import app.bekocat.core.timeline.BlendMode
import app.bekocat.core.timeline.Expression
import app.bekocat.core.timeline.FitMode
import app.bekocat.core.timeline.FrameBlend
import app.bekocat.core.timeline.Interpolation
import app.bekocat.core.timeline.Keyframe
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.TrackKind
import app.bekocat.editor.EditorState
import app.bekocat.editor.EditorViewModel
import app.bekocat.editor.Prop
import app.bekocat.ui.components.ChipRow
import app.bekocat.ui.components.Hint
import app.bekocat.ui.components.PropertyRow
import app.bekocat.ui.components.SectionTitle
import app.bekocat.ui.components.ToggleRow
import app.bekocat.ui.components.tr
import app.bekocat.ui.theme.BkColors
import kotlin.math.abs

object Props {
    private fun t(key: String, ar: String, en: String, min: Double, max: Double, def: Double,
                  g: (app.bekocat.core.timeline.Transform) -> Animatable, s: (app.bekocat.core.timeline.Transform, Animatable) -> app.bekocat.core.timeline.Transform) =
        Prop(key, ar, en, min, max, def, { c -> g(c.transform) }, { c, a -> c.copy(transform = s(c.transform, a)) })

    val posX = t("posX", "الموضع X", "Position X", -2.0, 2.0, 0.0, { it.positionX }, { tf, a -> tf.copy(positionX = a) })
    val posY = t("posY", "الموضع Y", "Position Y", -2.0, 2.0, 0.0, { it.positionY }, { tf, a -> tf.copy(positionY = a) })
    val posZ = t("posZ", "العمق Z", "Position Z", -2.0, 4.0, 0.0, { it.positionZ }, { tf, a -> tf.copy(positionZ = a) })
    val scale = t("scale", "الحجم", "Scale", 0.0, 5.0, 1.0, { it.scale }, { tf, a -> tf.copy(scale = a) })
    val scaleX = t("scaleX", "الحجم أفقيًا", "Scale X", 0.0, 5.0, 1.0, { it.scaleX }, { tf, a -> tf.copy(scaleX = a) })
    val scaleY = t("scaleY", "الحجم رأسيًا", "Scale Y", 0.0, 5.0, 1.0, { it.scaleY }, { tf, a -> tf.copy(scaleY = a) })
    val rotation = t("rot", "الدوران", "Rotation", -720.0, 720.0, 0.0, { it.rotation }, { tf, a -> tf.copy(rotation = a) })
    val rotX = t("rotX", "دوران X (2.5D)", "Rotation X", -180.0, 180.0, 0.0, { it.rotationX }, { tf, a -> tf.copy(rotationX = a) })
    val rotY = t("rotY", "دوران Y (2.5D)", "Rotation Y", -180.0, 180.0, 0.0, { it.rotationY }, { tf, a -> tf.copy(rotationY = a) })
    val skew = t("skew", "الإمالة", "Skew", -60.0, 60.0, 0.0, { it.skewX }, { tf, a -> tf.copy(skewX = a) })
    val opacity = t("opacity", "الشفافية", "Opacity", 0.0, 1.0, 1.0, { it.opacity }, { tf, a -> tf.copy(opacity = a) })
    val cropL = t("cropL", "قص يسار", "Crop left", 0.0, 0.49, 0.0, { it.cropLeft }, { tf, a -> tf.copy(cropLeft = a) })
    val cropT = t("cropT", "قص أعلى", "Crop top", 0.0, 0.49, 0.0, { it.cropTop }, { tf, a -> tf.copy(cropTop = a) })
    val cropR = t("cropR", "قص يمين", "Crop right", 0.0, 0.49, 0.0, { it.cropRight }, { tf, a -> tf.copy(cropRight = a) })
    val cropB = t("cropB", "قص أسفل", "Crop bottom", 0.0, 0.49, 0.0, { it.cropBottom }, { tf, a -> tf.copy(cropBottom = a) })
    val volume = Prop("vol", "مستوى الصوت", "Volume", 0.0, 2.0, 1.0, { it.audio.volume }, { c, a -> c.copy(audio = c.audio.copy(volume = a)) })
    val pan = Prop("pan", "التوزيع (Pan)", "Pan", -1.0, 1.0, 0.0, { it.audio.pan }, { c, a -> c.copy(audio = c.audio.copy(pan = a)) })

    fun corner(i: Int) = Prop("pin$i", "زاوية ${i / 2 + 1} ${if (i % 2 == 0) "X" else "Y"}", "Corner ${i / 2 + 1} ${if (i % 2 == 0) "X" else "Y"}", -1.0, 1.0, 0.0,
        { c -> c.transform.cornerPin?.getOrNull(i) ?: Animatable.of(0.0) },
        { c, a -> c.copy(transform = c.transform.copy(cornerPin = (c.transform.cornerPin ?: List(8) { Animatable.of(0.0) }).toMutableList().also { it[i] = a })) })

    val all = listOf(posX, posY, posZ, scale, scaleX, scaleY, rotation, rotX, rotY, skew, opacity, cropL, cropT, cropR, cropB, volume, pan)
}

@Composable
fun InspectorPanel(st: EditorState, vm: EditorViewModel) = NeedsSelection(st) { c ->
    ToggleRow(tr("تسجيل نقاط الحركة تلقائيًا (Auto-key)", "Auto-key (record keyframes)"), st.autoKey) { v -> vm.toggle { it.copy(autoKey = v) } }
    SectionTitle(tr("الموضع والحجم", "Position & scale"))
    AnimRow(vm, c, Props.posX); AnimRow(vm, c, Props.posY)
    AnimRow(vm, c, Props.scale); AnimRow(vm, c, Props.scaleX); AnimRow(vm, c, Props.scaleY)
    AnimRow(vm, c, Props.rotation, { "%.1f°".format(it) }); AnimRow(vm, c, Props.skew, { "%.1f°".format(it) })
    AnimRow(vm, c, Props.opacity, { "${(it * 100).toInt()}%" })
    SectionTitle(tr("نقطة الارتكاز (Anchor)", "Anchor point"))
    PropertyRow(tr("الارتكاز X", "Anchor X"), c.transform.anchorX, -1.0..1.0, defaultValue = 0.0) { v -> vm.updateSelected("anchor") { it.copy(transform = it.transform.copy(anchorX = v)) } }
    PropertyRow(tr("الارتكاز Y", "Anchor Y"), c.transform.anchorY, -1.0..1.0, defaultValue = 0.0) { v -> vm.updateSelected("anchor") { it.copy(transform = it.transform.copy(anchorY = v)) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("↖" to (-1.0 to -1.0), "⊙" to (0.0 to 0.0), "↘" to (1.0 to 1.0), "⬇" to (0.0 to 1.0)).forEach { (l, a) ->
            OutlinedButton(onClick = { vm.updateSelected("anchor") { it.copy(transform = it.transform.copy(anchorX = a.first, anchorY = a.second)) } }) { Text(l) }
        }
    }
    SectionTitle(tr("الملاءمة والانعكاس", "Fit & flip"))
    ChipRow(FitMode.entries, c.transform.fit, { tr(mapOf(FitMode.FIT to "ملاءمة", FitMode.FILL to "ملء", FitMode.STRETCH to "تمديد")[it]!!, it.name) }) { f -> vm.updateSelected("fit") { it.copy(transform = it.transform.copy(fit = f)) } }
    ToggleRow(tr("انعكاس أفقي", "Flip horizontal"), c.transform.flipH) { v -> vm.updateSelected("flip") { it.copy(transform = it.transform.copy(flipH = v)) } }
    ToggleRow(tr("انعكاس رأسي", "Flip vertical"), c.transform.flipV) { v -> vm.updateSelected("flip") { it.copy(transform = it.transform.copy(flipV = v)) } }
    SectionTitle(tr("وضع المزج (Blend mode)", "Blend mode"))
    ChipRow(BlendMode.entries, c.transform.blendMode, { it.name.lowercase().replace('_', ' ') }) { m -> vm.updateSelected("blend") { it.copy(transform = it.transform.copy(blendMode = m)) } }
    SectionTitle(tr("القص (Crop)", "Crop"))
    AnimRow(vm, c, Props.cropL); AnimRow(vm, c, Props.cropT); AnimRow(vm, c, Props.cropR); AnimRow(vm, c, Props.cropB)
    SectionTitle(tr("مساحة 2.5D", "2.5D space"))
    Hint(tr("العمق والدوران الثلاثي يعملان بمنظور وكاميرا؛ هذه ليست أداة نمذجة ثلاثية كاملة.", "Depth and 3-axis rotation use perspective and a camera; this is not a full 3D modelling engine."))
    AnimRow(vm, c, Props.posZ); AnimRow(vm, c, Props.rotX, { "%.1f°".format(it) }); AnimRow(vm, c, Props.rotY, { "%.1f°".format(it) })
    SectionTitle(tr("تثبيت الزوايا (Corner Pin)", "Corner pin"))
    var pin by remember { mutableStateOf(c.transform.cornerPin != null) }
    ToggleRow(tr("تفعيل", "Enable"), pin) { v -> pin = v; if (!v) vm.updateSelected("corner-pin") { it.copy(transform = it.transform.copy(cornerPin = null)) } }
    if (pin) for (i in 0 until 8) AnimRow(vm, c, Props.corner(i))
    SectionTitle(tr("ضبابية الحركة (Motion Blur)", "Motion blur"))
    PropertyRow(tr("زاوية الغالق", "Shutter angle"), c.transform.motionBlurShutter, 0.0..360.0, { "%.0f°".format(it) }, defaultValue = 0.0) { v -> vm.updateSelected("motion-blur") { it.copy(transform = it.transform.copy(motionBlurShutter = v)) } }
    PropertyRow(tr("العينات", "Samples"), c.transform.motionBlurSamples.toDouble(), 2.0..16.0, { "${it.toInt()}" }, defaultValue = 8.0) { v -> vm.updateSelected("motion-blur") { it.copy(transform = it.transform.copy(motionBlurSamples = v.toInt())) } }
    SectionTitle(tr("الربط (Parent)", "Parent"))
    val p = st.project!!
    val candidates = p.sequence.tracks.filter { it.kind != TrackKind.AUDIO }.flatMap { it.clips }.filter { it.id != c.id }
    ChipRow(listOf<String?>(null) + candidates.map { it.id }, c.parentClipId, { id -> if (id == null) tr("بدون", "None") else (candidates.first { it.id == id }.name.ifBlank { id }) }) { id ->
        vm.updateSelected("parent") { it.copy(parentClipId = id) }
    }
}

/** Graph editor: value curve of a chosen property with draggable keyframes and bezier handles; also easing presets and expressions. */
@Composable
fun GraphPanel(st: EditorState, vm: EditorViewModel) = NeedsSelection(st) { c ->
    var propIdx by remember { mutableIntStateOf(0) }
    val props = Props.all
    ChipRow(props.indices.toList(), propIdx, { tr(props[it].ar, props[it].en) }) { propIdx = it }
    val prop = props[propIdx]
    val a = prop.get(c)
    val dur = c.duration.toDouble().coerceAtLeast(0.01)
    var speedView by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.jumpKey(prop, -1) }) { Text("◀◆") }
        Button(onClick = { vm.toggleKey(prop) }) { Text("◆ " + tr("نقطة", "Key")) }
        OutlinedButton(onClick = { vm.jumpKey(prop, 1) }) { Text("◆▶") }
        FilterChip(speedView, { speedView = !speedView }, { Text(tr("منحنى السرعة", "Speed graph")) })
    }
    val keys = a.keyframes
    if (keys.isEmpty()) Hint(tr("أضف نقطتي حركة على الأقل لرسم المنحنى.", "Add at least two keyframes to edit the curve."))
    val vmin = (keys.minOfOrNull { it.value } ?: prop.min).let { if (keys.size < 2) prop.min else it }
    val vmax = (keys.maxOfOrNull { it.value } ?: prop.max).let { if (keys.size < 2) prop.max else it }
    val span = (vmax - vmin).let { if (abs(it) < 1e-9) 1.0 else it }
    var selKey by remember { mutableIntStateOf(-1) }
    var dragKeys by remember { mutableStateOf<List<Keyframe>?>(null) }
    val shown = dragKeys ?: keys
    Canvas(
        Modifier.fillMaxWidth().height(200.dp).background(BkColors.Background, RoundedCornerShape(8.dp))
            .pointerInput(keys, dur, vmin, vmax) {
                fun tx(t: Double) = (t / dur * size.width).toFloat()
                fun vy(v: Double) = (size.height - 16 - (v - vmin) / span * (size.height - 32)).toFloat()
                var mode = 0 // 1 key, 2 out handle, 3 in handle
                var idx = -1
                detectDragGestures(
                    onDragStart = { pos ->
                        idx = -1; mode = 0
                        keys.forEachIndexed { i, k ->
                            if ((Offset(tx(k.time.toDouble()), vy(k.value)) - pos).getDistance() < 40) { idx = i; mode = 1 }
                        }
                        if (idx < 0 && selKey in keys.indices && selKey + 1 in keys.indices) {
                            val k0 = keys[selKey]; val k1 = keys[selKey + 1]
                            val x0 = tx(k0.time.toDouble()); val x1 = tx(k1.time.toDouble()); val y0 = vy(k0.value); val y1 = vy(k1.value)
                            val ho = Offset(x0 + (x1 - x0) * k0.outX.toFloat(), y0 + (y1 - y0) * k0.outY.toFloat())
                            val hi = Offset(x0 + (x1 - x0) * k1.inX.toFloat(), y0 + (y1 - y0) * k1.inY.toFloat())
                            if ((ho - pos).getDistance() < 40) { idx = selKey; mode = 2 } else if ((hi - pos).getDistance() < 40) { idx = selKey + 1; mode = 3 }
                        }
                        if (mode == 1) selKey = idx
                        dragKeys = keys
                    },
                    onDrag = { change, _ ->
                        val ks = dragKeys?.toMutableList() ?: return@detectDragGestures
                        val pos = change.position
                        when (mode) {
                            1 -> {
                                val t = (pos.x / size.width * dur).coerceIn(0.0, dur)
                                val v = vmin + (size.height - 16 - pos.y) / (size.height - 32) * span
                                ks[idx] = ks[idx].copy(time = c.let { st.project!!.sequence.frameRate.snap(Rational.fromDouble(t, 1_000_000)) }, value = v)
                            }
                            2, 3 -> {
                                val i0 = if (mode == 2) idx else idx - 1
                                val k0 = ks[i0]; val k1 = ks[i0 + 1]
                                val x0 = tx(k0.time.toDouble()); val x1 = tx(k1.time.toDouble()); val y0 = vy(k0.value); val y1 = vy(k1.value)
                                val nx = ((pos.x - x0) / (x1 - x0).coerceAtLeast(1f)).toDouble().coerceIn(0.0, 1.0)
                                val ny = ((pos.y - y0) / (y1 - y0).let { if (abs(it) < 1f) 1f else it }).toDouble().coerceIn(-1.0, 2.0)
                                if (mode == 2) ks[i0] = k0.copy(interpolation = Interpolation.BEZIER, outX = nx, outY = ny) else ks[i0 + 1] = k1.copy(inX = nx, inY = ny)
                            }
                        }
                        dragKeys = ks
                        vm.replaceKeyframes(prop, ks, commit = false)
                    },
                    onDragEnd = { dragKeys?.let { vm.replaceKeyframes(prop, it, commit = true) }; dragKeys = null },
                )
            }
    ) {
        fun tx(t: Double) = (t / dur * size.width).toFloat()
        fun vy(v: Double) = (size.height - 16 - (v - vmin) / span * (size.height - 32)).toFloat()
        val anim = a.copy(keyframes = shown)
        val path = Path()
        val n = 160
        var prevV = anim.valueAt(Rational.ZERO)
        for (i in 0..n) {
            val t = dur * i / n
            val v = anim.valueAt(Rational.fromDouble(t, 1_000_000))
            val yv = if (speedView) { val d = (v - prevV) * n / dur; prevV = v; size.height / 2 - (d / span * size.height / 4).toFloat() } else vy(v)
            if (i == 0) path.moveTo(tx(t), yv) else path.lineTo(tx(t), yv)
        }
        drawPath(path, BkColors.Primary, style = Stroke(3f))
        val ph = vm.localTime(c).toDouble()
        drawLine(BkColors.Accent, Offset(tx(ph), 0f), Offset(tx(ph), size.height), 1.5f)
        shown.forEachIndexed { i, k ->
            val p = Offset(tx(k.time.toDouble()), vy(k.value))
            drawCircle(if (i == selKey) BkColors.Accent else Color(0xFFFFC857), 9f, p)
        }
        if (selKey in shown.indices && selKey + 1 in shown.indices) {
            val k0 = shown[selKey]; val k1 = shown[selKey + 1]
            val x0 = tx(k0.time.toDouble()); val x1 = tx(k1.time.toDouble()); val y0 = vy(k0.value); val y1 = vy(k1.value)
            val ho = Offset(x0 + (x1 - x0) * k0.outX.toFloat(), y0 + (y1 - y0) * k0.outY.toFloat())
            val hi = Offset(x0 + (x1 - x0) * k1.inX.toFloat(), y0 + (y1 - y0) * k1.inY.toFloat())
            drawLine(BkColors.Muted, Offset(x0, y0), ho, 1.5f); drawCircle(BkColors.Text, 7f, ho)
            drawLine(BkColors.Muted, Offset(x1, y1), hi, 1.5f); drawCircle(BkColors.Text, 7f, hi)
        }
    }
    SectionTitle(tr("الاستيفاء والتسهيل (Easing)", "Interpolation & easing"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.setKeyInterpolation(prop, Interpolation.HOLD) }) { Text("Hold") }
        OutlinedButton(onClick = { vm.setKeyInterpolation(prop, Interpolation.LINEAR) }) { Text("Linear") }
        OutlinedButton(onClick = { vm.setKeyInterpolation(prop, Interpolation.BEZIER, listOf(0.33, 0.0, 0.67, 1.0)) }) { Text("Easy Ease") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.setKeyInterpolation(prop, Interpolation.BEZIER, listOf(0.42, 0.0, 1.0, 1.0)) }) { Text("Ease In") }
        OutlinedButton(onClick = { vm.setKeyInterpolation(prop, Interpolation.BEZIER, listOf(0.0, 0.0, 0.58, 1.0)) }) { Text("Ease Out") }
        OutlinedButton(onClick = { vm.setKeyInterpolation(prop, Interpolation.BEZIER, listOf(0.34, 1.56, 0.64, 1.0)) }) { Text("Overshoot") }
    }
    SectionTitle(tr("تعبير برمجي آمن (Expression)", "Safe expression"))
    var expr by remember(c.id, prop.key) { mutableStateOf(a.expression ?: "") }
    OutlinedTextField(expr, { expr = it }, Modifier.fillMaxWidth(), placeholder = { Text("value + wiggle(2, 0.05, 7)") }, singleLine = true,
        isError = expr.isNotBlank() && Expression.validate(expr) != null, supportingText = { Expression.validate(expr)?.takeIf { expr.isNotBlank() }?.let { Text(it) } })
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.setExpression(prop, expr) }, enabled = expr.isBlank() || Expression.validate(expr) == null) { Text(tr("تطبيق", "Apply")) }
        TextButton(onClick = { expr = ""; vm.setExpression(prop, null) }) { Text(tr("إزالة", "Remove")) }
    }
    Hint("wiggle(freq, amp, seed) · loopCycle() · loopPingPong() · sin(time*2)*value · clamp()")
}

@Composable
fun SpeedPanel(st: EditorState, vm: EditorViewModel) = NeedsSelection(st) { c ->
    SectionTitle(tr("سرعة ثابتة", "Constant speed"))
    PropertyRow(tr("السرعة", "Speed"), if (c.timeRemap == null) c.speed.toDouble() else 1.0, 0.1..16.0, { "×%.2f".format(it) }, defaultValue = 1.0) { vm.setSpeed(it) }
    ChipRow(listOf(0.25, 0.5, 1.0, 2.0, 4.0), c.speed.toDouble(), { "×$it" }) { vm.setSpeed(it) }
    ToggleRow(tr("عكس التشغيل (Reverse)", "Reverse"), c.reversed) { vm.setReversed(it) }
    ToggleRow(tr("المحافظة على طبقة الصوت", "Preserve pitch"), c.audio.preservePitch) { v -> vm.updateSelected("pitch") { it.copy(audio = it.audio.copy(preservePitch = v)) } }
    SectionTitle(tr("منحدر السرعة (Speed Ramp)", "Speed ramp"))
    val d = c.duration.toDouble()
    val presets = listOf(
        tr("بطيء في المنتصف", "Hero slow-mo") to listOf(0.0 to 1.5, d * 0.35 to 0.3, d * 0.65 to 0.3, d to 1.5),
        tr("مونتاج", "Montage") to listOf(0.0 to 2.0, d * 0.5 to 0.5, d to 2.0),
        tr("رصاصة", "Bullet") to listOf(0.0 to 3.0, d * 0.4 to 0.15, d * 0.6 to 0.15, d to 3.0),
        tr("دخول سريع", "Flash in") to listOf(0.0 to 4.0, d * 0.3 to 1.0, d to 1.0),
        tr("خروج سريع", "Flash out") to listOf(0.0 to 1.0, d * 0.7 to 1.0, d to 4.0),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { presets.take(3).forEach { (n, pts) -> OutlinedButton(onClick = { vm.speedRamp(pts) }) { Text(n) } } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { presets.drop(3).forEach { (n, pts) -> OutlinedButton(onClick = { vm.speedRamp(pts) }) { Text(n) } } }
    if (c.timeRemap != null) {
        Hint(tr("المقطع يستخدم إعادة توقيت (Time Remap). عدّل المنحنى من «المنحنيات».", "Clip uses time remapping. Edit the curve in the Graph editor."))
        TextButton(onClick = { vm.edit("clear-remap") { s, ed -> ed.clearTimeRemap(s, c.id) } }) { Text(tr("إزالة إعادة التوقيت", "Clear time remap")) }
    }
    SectionTitle(tr("تجميد الإطار", "Freeze frame"))
    Button(onClick = vm::freezeFrame) { Text(tr("تجميد إطار المؤشر لثانيتين", "Freeze playhead frame (2 s)")) }
    SectionTitle(tr("نعومة البطء", "Slow-motion interpolation"))
    ChipRow(FrameBlend.entries, c.frameBlend, { when (it) { FrameBlend.NONE -> tr("بدون", "None"); FrameBlend.BLEND -> "Frame blending"; FrameBlend.OPTICAL_FLOW -> "Optical flow" } }) { m ->
        vm.updateSelected("frame-blend") { it.copy(frameBlend = m) }
    }
    Hint(tr("Optical Flow هنا تقديري بمطابقة الكتل على GPU، وقد تظهر تشوهات حول الحواف والحركة السريعة.", "Optical flow here is a GPU block-matching estimate; artefacts can appear around edges and fast motion."))
    SectionTitle(tr("أدوات القص المتقدمة", "Advanced trimming"))
    val fd = st.project!!.sequence.frameRate.frameDuration.toDouble()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.slip(-fd) }) { Text("Slip −") }; OutlinedButton(onClick = { vm.slip(fd) }) { Text("Slip +") }
        OutlinedButton(onClick = { vm.slide(-fd) }) { Text("Slide −") }; OutlinedButton(onClick = { vm.slide(fd) }) { Text("Slide +") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.roll(-fd) }) { Text("Roll −") }; OutlinedButton(onClick = { vm.roll(fd) }) { Text("Roll +") }
        OutlinedButton(onClick = { vm.insertSelectedAtPlayhead(false) }) { Text("Insert") }
        OutlinedButton(onClick = { vm.insertSelectedAtPlayhead(true) }) { Text("Overwrite") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = vm::duplicate) { Text(tr("تكرار", "Duplicate")) }
        OutlinedButton(onClick = vm::pasteAttributes) { Text(tr("لصق الخصائص", "Paste attributes")) }
        OutlinedButton(onClick = { vm.addMarker() }) { Text(tr("علامة", "Marker")) }
    }
}

@Composable
fun TrackPanel(st: EditorState, vm: EditorViewModel) {
    val t = st.project?.sequence?.tracks?.firstOrNull { it.id == vm.trackPanelFor } ?: run { Hint(tr("اختر مسارًا من رأس الخط الزمني.", "Tap a track header.")); return }
    Text(t.name)
    ToggleRow(tr("قفل", "Lock"), t.locked) { v -> vm.updateTrack(t.id) { it.copy(locked = v) } }
    ToggleRow(tr("إخفاء", "Hide"), t.hidden) { v -> vm.updateTrack(t.id) { it.copy(hidden = v) } }
    ToggleRow(tr("كتم", "Mute"), t.muted) { v -> vm.updateTrack(t.id) { it.copy(muted = v) } }
    ToggleRow("Solo", t.solo) { v -> vm.updateTrack(t.id) { it.copy(solo = v) } }
    PropertyRow(tr("الارتفاع", "Height"), t.heightDp.toDouble(), 32.0..120.0, { "${it.toInt()} dp" }, defaultValue = 46.0) { v -> vm.updateTrack(t.id) { it.copy(heightDp = v.toInt()) } }
    if (t.kind == TrackKind.AUDIO) {
        ChipRow(app.bekocat.core.timeline.AudioRole.entries, t.role, { it.name.lowercase() }) { r -> vm.updateTrack(t.id) { it.copy(role = r) } }
        ToggleRow(tr("خفض تلقائي تحت الحوار (Ducking)", "Duck under dialogue"), t.duckUnderDialogue) { v -> vm.updateTrack(t.id) { it.copy(duckUnderDialogue = v) } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.moveTrack(t.id, -1) }) { Text("▲") }
        OutlinedButton(onClick = { vm.moveTrack(t.id, 1) }) { Text("▼") }
        OutlinedButton(onClick = { vm.removeTrack(t.id) }, enabled = t.clips.isEmpty()) { Text(tr("حذف المسار", "Delete track")) }
    }
    SectionTitle(tr("إضافة مسار", "Add track"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.addTrack(TrackKind.OVERLAY) }) { Text(tr("فيديو/طبقة", "Video/overlay")) }
        OutlinedButton(onClick = { vm.addTrack(TrackKind.TEXT) }) { Text(tr("نص", "Text")) }
        OutlinedButton(onClick = { vm.addTrack(TrackKind.AUDIO) }) { Text(tr("صوت", "Audio")) }
    }
}
