package app.bekocat.editor.panels

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bekocat.core.timeline.Animatable
import app.bekocat.core.timeline.FrameRate
import app.bekocat.engine.export.ExportKind
import app.bekocat.engine.export.ExportSettings
import app.bekocat.engine.export.Exporter
import app.bekocat.engine.export.Reframe
import app.bekocat.engine.export.VideoCodec
import app.bekocat.editor.EditorState
import app.bekocat.editor.EditorViewModel
import app.bekocat.ui.components.ChipRow
import app.bekocat.ui.components.ColorSwatches
import app.bekocat.ui.components.Hint
import app.bekocat.ui.components.PALETTE
import app.bekocat.ui.components.PropertyRow
import app.bekocat.ui.components.SectionTitle
import app.bekocat.ui.components.ToggleRow
import app.bekocat.ui.components.tr
import app.bekocat.ui.theme.BkColors

@Composable
fun ExportPanel(st: EditorState, vm: EditorViewModel) {
    val p = st.project ?: return
    val ctx = LocalContext.current
    val seq = p.sequence
    var kind by remember { mutableStateOf(ExportKind.VIDEO) }
    var w by remember { mutableIntStateOf(seq.width) }
    var h by remember { mutableIntStateOf(seq.height) }
    var fps by remember { mutableStateOf(seq.frameRate) }
    var codec by remember { mutableStateOf(VideoCodec.H264) }
    var mbps by remember { mutableDoubleStateOf(16.0) }
    var abr by remember { mutableIntStateOf(192) }
    var channels by remember { mutableIntStateOf(2) }
    var reframe by remember { mutableStateOf(Reframe.ADAPT) }
    var range by remember { mutableStateOf(false) }
    var transparent by remember { mutableStateOf(false) }
    var gifFps by remember { mutableIntStateOf(15) }
    SectionTitle(tr("النوع", "Format"))
    ChipRow(ExportKind.entries, kind, { when (it) {
        ExportKind.VIDEO -> tr("فيديو MP4", "Video MP4"); ExportKind.GIF -> "GIF"; ExportKind.PNG_SEQUENCE -> "PNG sequence"; ExportKind.JPEG_SEQUENCE -> "JPEG sequence"
        ExportKind.WAV -> "WAV"; ExportKind.M4A -> "M4A (AAC)"; ExportKind.STEMS -> tr("Stems (لكل مسار)", "Stems (per track)")
        ExportKind.CAPTIONS_SRT -> "SRT"; ExportKind.CAPTIONS_VTT -> "VTT"; ExportKind.CAPTIONS_ASS -> "ASS" } }) { kind = it }
    val visual = kind in listOf(ExportKind.VIDEO, ExportKind.GIF, ExportKind.PNG_SEQUENCE, ExportKind.JPEG_SEQUENCE)
    if (visual) {
        SectionTitle(tr("الأبعاد", "Size"))
        val presets = listOf(
            tr("عمودي 1080", "Vertical 1080") to (1080 to 1920), tr("أفقي 1080", "Landscape 1080") to (1920 to 1080), tr("مربع", "Square") to (1080 to 1080),
            "720p" to (if (seq.width < seq.height) 720 to 1280 else 1280 to 720), "1440p" to (if (seq.width < seq.height) 1440 to 2560 else 2560 to 1440),
            "4K" to (if (seq.width < seq.height) 2160 to 3840 else 3840 to 2160), "4:5" to (1080 to 1350),
        )
        ChipRow(presets.indices.toList(), presets.indexOfFirst { it.second == (w to h) }.takeIf { it >= 0 }, { presets[it].first }) { i -> w = presets[i].second.first; h = presets[i].second.second }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(w.toString(), { it.toIntOrNull()?.let { v -> w = v - v % 2 } }, Modifier.weight(1f), label = { Text("W") }, singleLine = true)
            OutlinedTextField(h.toString(), { it.toIntOrNull()?.let { v -> h = v - v % 2 } }, Modifier.weight(1f), label = { Text("H") }, singleLine = true)
        }
        if (w != seq.width || h != seq.height) ChipRow(Reframe.entries, reframe, { when (it) { Reframe.ADAPT -> tr("إعادة توزيع ذكية", "Adaptive layout"); Reframe.FIT -> tr("ملاءمة", "Fit"); Reframe.FILL -> tr("ملء", "Fill") } }) { reframe = it }
        SectionTitle(tr("معدل الإطارات", "Frame rate"))
        ChipRow(FrameRate.STANDARD, fps, { "%.3f".format(it.fps).trimEnd('0').trimEnd('.') }) { fps = it }
    }
    if (kind == ExportKind.VIDEO) {
        SectionTitle(tr("الترميز", "Codec"))
        val avail = remember(w, h, fps) { Exporter.availableCodecs(w, h, fps.fps) }
        ChipRow(VideoCodec.entries, codec, { it.label + if (it !in avail) " ✕" else "" }) { codec = it }
        if (codec !in avail) Hint(tr("هذا الترميز غير مدعوم بهذه الأبعاد على هذا الجهاز؛ سيُرفض التصدير بشرح السبب.", "This codec/size isn't supported by this device's encoder; export will be refused with an explanation."))
        PropertyRow(tr("معدل البت (Mbps)", "Bitrate (Mbps)"), mbps, 1.0..120.0, { "%.0f".format(it) }) { mbps = it }
    }
    if (kind == ExportKind.VIDEO || kind == ExportKind.M4A) {
        ChipRow(listOf(96, 128, 192, 256, 320), abr, { "$it kbps" }) { abr = it }
        ChipRow(listOf(1, 2), channels, { if (it == 1) "Mono" else "Stereo" }) { channels = it }
    }
    if (kind == ExportKind.PNG_SEQUENCE) ToggleRow(tr("خلفية شفافة (Alpha)", "Transparent background (alpha)"), transparent) { transparent = it }
    if (kind == ExportKind.GIF) PropertyRow("GIF fps", gifFps.toDouble(), 5.0..30.0, { "${it.toInt()}" }) { gifFps = it.toInt() }
    if (st.inPoint != null || st.outPoint != null) ToggleRow(tr("تصدير منطقة العمل فقط (In/Out)", "Export work area only (In/Out)"), range) { range = it }
    val dur = (if (range) (st.outPoint ?: seq.duration.toDouble()) - (st.inPoint ?: 0.0) else seq.duration.toDouble()).coerceAtLeast(0.0)
    val settings = ExportSettings(kind = kind, width = w, height = h, frameRate = fps, codec = codec, videoBitrate = (mbps * 1_000_000).toInt(),
        audioBitrate = abr * 1000, channels = channels, startSec = if (range) st.inPoint ?: 0.0 else 0.0, endSec = if (range) st.outPoint else null,
        reframe = reframe, transparent = transparent, gifFps = gifFps)
    if (kind == ExportKind.VIDEO) Text(tr("الحجم التقديري ≈ ", "Estimated size ≈ ") + "%.0f MB".format(Exporter.estimateBytes(settings, dur) / 1e6) + tr(" (تقدير)", " (estimate)"), color = BkColors.Muted)
    Hint(tr("بلا علامة مائية وبلا حد للمدة أو الدقة. يتحقق التطبيق من الملف الناتج قبل إعلان النجاح.", "No watermark, no length or resolution caps. The output file is verified before success is reported."))
    Button(onClick = { vm.enqueueExport("${kind.name} ${w}×$h", settings) }, modifier = Modifier.fillMaxWidth()) { Text(tr("إضافة إلى قائمة التصدير", "Add to export queue")) }
    if (st.exports.isNotEmpty()) SectionTitle(tr("قائمة التصدير", "Export queue"))
    for (j in st.exports.reversed()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(BkColors.Raised, RoundedCornerShape(10.dp)).padding(10.dp)) {
            Text(j.label + " · " + j.stage)
            if (!j.done) { LinearProgressIndicator(progress = { j.progress.toFloat() }, Modifier.fillMaxWidth()); TextButton(onClick = vm::cancelExport) { Text(tr("إلغاء", "Cancel")) } }
            j.error?.let { Text(it, color = BkColors.Danger, fontSize = 12.sp) }
            j.result?.let { r ->
                Text(tr("تم التحقق: ", "Verified: ") + "%.2fs · %d tracks".format(r.verifiedDurationSec, r.tracks) + (if (r.width > 0) " · ${r.width}×${r.height}" else ""), color = BkColors.Accent, fontSize = 12.sp)
                j.uri?.let { u ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(r.mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)) }) { Text(tr("مشاركة", "Share")) }
                        OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, r.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }) { Text(tr("فتح", "Open")) }
                    }
                }
            }
        }
    }
}

@Composable
fun ProjectPanel(st: EditorState, vm: EditorViewModel) {
    val p = st.project ?: return
    val seq = p.sequence
    var name by remember(p.id) { mutableStateOf(p.name) }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(tr("اسم المشروع", "Project name")) }, singleLine = true)
    TextButton(onClick = { vm.commit("rename") { it.copy(name = name) } }) { Text(tr("حفظ الاسم", "Save name")) }
    SectionTitle(tr("التسلسل", "Sequence"))
    Text("${seq.width}×${seq.height} · %.3f fps · ${seq.audioSampleRate} Hz".format(seq.frameRate.fps), color = BkColors.Muted)
    ChipRow(listOf(1080 to 1920, 1920 to 1080, 1080 to 1080, 1080 to 1350, 3840 to 2160), seq.width to seq.height, { "${it.first}×${it.second}" }) { (w, h) -> vm.updateSequence("resolution") { it.copy(width = w, height = h) } }
    ChipRow(FrameRate.STANDARD, seq.frameRate, { "%.3f".format(it.fps).trimEnd('0').trimEnd('.') }) { r -> vm.updateSequence("fps") { it.copy(frameRate = r) } }
    Text(tr("لون الخلفية", "Background colour"), color = BkColors.Muted)
    ColorSwatches(seq.backgroundArgb, PALETTE) { c -> vm.updateSequence("bg") { it.copy(backgroundArgb = c) } }
    SectionTitle(tr("الكاميرا (2.5D)", "Camera (2.5D)"))
    ToggleRow(tr("تفعيل الكاميرا", "Enable camera"), seq.camera.enabled) { v -> vm.updateSequence("camera") { it.copy(camera = it.camera.copy(enabled = v)) } }
    if (seq.camera.enabled) {
        val cam = seq.camera
        fun cp(label: String, ar: String, en: String, v: Animatable, r: ClosedFloatingPointRange<Double>, set: (app.bekocat.core.timeline.Camera, Animatable) -> app.bekocat.core.timeline.Camera) = Triple(ar to en, v, r) to set
        listOf(
            cp("z", "التقريب", "Zoom", cam.zoom, 0.2..4.0) { c, a -> c.copy(zoom = a) },
            cp("x", "تحريك X", "Pan X", cam.positionX, -2.0..2.0) { c, a -> c.copy(positionX = a) },
            cp("y", "تحريك Y", "Pan Y", cam.positionY, -2.0..2.0) { c, a -> c.copy(positionY = a) },
            cp("pz", "دفع Z", "Dolly Z", cam.positionZ, -2.0..2.0) { c, a -> c.copy(positionZ = a) },
            cp("ry", "دوران Y", "Orbit Y", cam.rotationY, -60.0..60.0) { c, a -> c.copy(rotationY = a) },
            cp("rx", "دوران X", "Tilt X", cam.rotationX, -60.0..60.0) { c, a -> c.copy(rotationX = a) },
        ).forEach { (info, set) ->
            val (names, a, r) = info
            val t = vm.playheadTime
            PropertyRow(tr(names.first, names.second), a.valueAt(t), r, keyframed = a.keyframes.isNotEmpty(), hasKeyframeHere = a.keyframes.any { it.time == t },
                onToggleKey = { vm.updateSequence("camera-key") { s -> s.copy(camera = set(s.camera, if (a.keyframes.any { it.time == t }) a.withoutKeyframeAt(t) else a.withKeyframe(app.bekocat.core.timeline.Keyframe(t, a.valueAt(t), app.bekocat.core.timeline.Interpolation.BEZIER)))) } }
            ) { v -> vm.updateSequence("camera") { s -> s.copy(camera = set(s.camera, a.set(v, t, st.autoKey || a.keyframes.isNotEmpty()))) } }
        }
        ToggleRow(tr("عمق الميدان (قابل للتعطيل لتحسين المعاينة)", "Depth of field (disable for faster preview)"), cam.depthOfField) { v -> vm.updateSequence("dof") { it.copy(camera = it.camera.copy(depthOfField = v)) } }
    }
    SectionTitle(tr("الهوية (Brand Kit)", "Brand kit"))
    ColorSwatches(p.brandKit.colors.firstOrNull() ?: 0xFF9B7CFF, PALETTE) { c -> vm.commit("brand") { it.copy(brandKit = it.brandKit.copy(colors = listOf(c) + it.brandKit.colors.drop(1))) } }
    Button(onClick = vm::applyBrandKit) { Text(tr("تطبيق ألوان وخط الهوية على كل النصوص", "Apply brand colours & font to all text")) }
    SectionTitle(tr("الحفظ والنقل", "Save & transfer"))
    val pkg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u -> u?.let { vm.exportPackage(it) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { pkg.launch("${p.name}.bekocat") }) { Text(tr("تصدير حزمة .bekocat", "Export .bekocat package")) }
        OutlinedButton(onClick = vm::saveAsTemplate) { Text(tr("حفظ كقالب خاص", "Save as my template")) }
    }
    SectionTitle(tr("النسخ الاحتياطية", "Backups"))
    val backups = remember(p.modifiedAtMs) { vm.repo.backups(p.id) }
    if (backups.isEmpty()) Hint(tr("تُنشأ نسخة احتياطية كل 5 دقائق أثناء التحرير.", "A backup is made every 5 minutes while editing."))
    for (b in backups) Row {
        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(b.name.removePrefix("project-").removeSuffix(".json").toLongOrNull() ?: 0)), Modifier.weight(1f))
        TextButton(onClick = { vm.restoreBackup(b) }) { Text(tr("استعادة", "Restore")) }
    }
    SectionTitle(tr("الخصوصية", "Privacy"))
    Hint(tr("المعالجة كلها على جهازك. لا تُرفع ملفاتك لأي خادم. الإنترنت يُستخدم فقط لتنزيل النماذج عندما تطلب ذلك.", "All processing is on your device. Your files are never uploaded. The internet is used only to download models when you ask."))
}
