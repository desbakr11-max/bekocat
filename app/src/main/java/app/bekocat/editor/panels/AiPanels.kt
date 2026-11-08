@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.bekocat.editor.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bekocat.ai.ModelManager
import app.bekocat.core.timeline.CaptionSegment
import app.bekocat.core.timeline.CaptionStyleCatalog
import app.bekocat.core.timeline.Clip
import app.bekocat.core.timeline.KeyView
import app.bekocat.core.timeline.Plan
import app.bekocat.core.timeline.Rational
import app.bekocat.editor.EditorState
import app.bekocat.editor.EditorViewModel
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

/** Model requirement row: shows install state and lets the user download (with size) before running. */
@Composable
fun ModelGate(vm: EditorViewModel, modelId: String, content: @Composable () -> Unit) {
    val states by vm.ai.models.states.collectAsState()
    val spec = vm.ai.models.spec(modelId) ?: run { Hint("Unknown model $modelId"); return }
    val s = states[modelId]
    val ar = isArabic()
    if (s?.installed == true) { content(); return }
    Column(Modifier.fillMaxWidth().background(BkColors.Raised, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text((if (ar) spec.ar else spec.en) + " · " + ModelManager.human(spec.totalBytes), color = BkColors.Text)
        Text(tr("الترخيص: ", "License: ") + spec.licenseCode + " / " + spec.licenseWeights, color = BkColors.Muted, fontSize = 12.sp)
        if (s?.progress != null) {
            LinearProgressIndicator(progress = { s.progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            TextButton(onClick = { vm.ai.models.cancel(modelId) }) { Text(tr("إلغاء التنزيل", "Cancel download")) }
        } else {
            s?.error?.let { Text(it, color = BkColors.Danger, fontSize = 12.sp) }
            Button(onClick = { vm.downloadModel(modelId) }) { Text(tr("تنزيل النموذج (مرة واحدة، يعمل بعدها دون إنترنت)", "Download model (once; works offline afterwards)")) }
        }
    }
}

@Composable
fun CaptionPanel(st: EditorState, vm: EditorViewModel) {
    val p = st.project ?: return
    val ar = isArabic()
    val ct = p.sequence.captions.firstOrNull()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.importCaptions(it) } }
    SectionTitle(tr("توليد الكابشن من الكلام", "Generate captions from speech"))
    var lang by remember { mutableStateOf<String?>("ar") }
    var diarize by remember { mutableStateOf(false) }
    ChipRow(listOf<String?>(null, "ar", "en"), lang, { when (it) { null -> tr("كشف تلقائي", "Auto-detect"); "ar" -> "العربية"; else -> "English" } }) { lang = it }
    ModelGate(vm, ModelManager.ASR) {
        ToggleRow(tr("فصل المتحدثين", "Speaker diarization"), diarize) { diarize = it }
        if (diarize) ModelGate(vm, ModelManager.DIARIZATION) {}
        Button(onClick = { vm.generateCaptions(lang, diarize) }, enabled = st.busy == null) { Text(tr("استخراج الكابشن", "Transcribe")) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text(tr("استيراد SRT/VTT/ASS", "Import SRT/VTT/ASS")) }
    }
    if (ct == null) return
    SectionTitle(tr("النمط", "Style") + " (${CaptionStyleCatalog.all.size})")
    ChipRow(CaptionStyleCatalog.all.map { it.id }, ct.stylePreset, { id -> CaptionStyleCatalog.find(id).let { if (ar) it.ar else it.en } }) { id -> vm.updateCaptions { it.copy(stylePreset = id) } }
    PropertyRow(tr("الموضع الرأسي", "Vertical position"), ct.positionY, 0.05..0.95, { "${(it * 100).toInt()}%" }, defaultValue = 0.78) { v -> vm.updateCaptions { it.copy(positionY = v) } }
    PropertyRow(tr("أقصى كلمات للسطر", "Max words per line"), ct.maxWordsPerLine.toDouble(), 1.0..12.0, { "${it.toInt()}" }) { v -> vm.updateCaptions { it.copy(maxWordsPerLine = v.toInt()) } }
    PropertyRow(tr("عدد الأسطر", "Lines"), ct.maxLines.toDouble(), 1.0..3.0, { "${it.toInt()}" }) { v -> vm.updateCaptions { it.copy(maxLines = v.toInt()) } }
    PropertyRow(tr("الحجم", "Size"), ct.style.sizeSp.takeIf { it != 48.0 } ?: CaptionStyleCatalog.find(ct.stylePreset).style.sizeSp, 16.0..140.0, { "${it.toInt()}" }) { v -> vm.updateCaptions { it.copy(style = it.style.copy(sizeSp = v)) } }
    Text(tr("لون النص", "Text colour"), color = BkColors.Muted); ColorSwatches(ct.style.colorArgb, PALETTE) { c -> vm.updateCaptions { it.copy(style = it.style.copy(colorArgb = c)) } }
    Text(tr("الخلفية", "Background"), color = BkColors.Muted); ColorSwatches(ct.style.backgroundArgb, PALETTE) { c -> vm.updateCaptions { it.copy(style = it.style.copy(backgroundArgb = c)) } }
    Text(tr("لون المتحدث 2", "Speaker 2 colour"), color = BkColors.Muted); ColorSwatches(ct.speakerColors.getOrElse(1) { 0xFF6EE7C8 }, PALETTE) { c -> vm.updateCaptions { it.copy(speakerColors = it.speakerColors.toMutableList().also { l -> while (l.size < 2) l.add(0xFFF4F7FB); l[1] = c }) } }
    ToggleRow(tr("حرق الكابشن داخل الفيديو", "Burn into video"), ct.burnIn) { v -> vm.updateCaptions { it.copy(burnIn = v) } }
    SectionTitle(tr("الترجمة", "Translation"))
    Hint(tr("الترجمة تُحفظ منفصلة ولا تستبدل النص الأصلي.", "Translations are stored separately and never overwrite the transcript."))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModelGate(vm, ModelManager.ASR) { OutlinedButton(onClick = { vm.translateCaptionsToEnglish() }) { Text(tr("ترجمة إلى الإنجليزية (من الصوت)", "Translate → English (from speech)")) } }
    }
    ToggleRow(tr("عرض الترجمة", "Show translation"), ct.showTranslation) { v -> vm.updateCaptions { it.copy(showTranslation = v) } }
    SectionTitle(tr("بحث واستبدال", "Find & replace"))
    var find by remember { mutableStateOf("") }; var rep by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(find, { find = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("بحث", "Find")) })
        OutlinedTextField(rep, { rep = it }, Modifier.weight(1f), singleLine = true, label = { Text(tr("استبدال", "Replace")) })
    }
    TextButton(onClick = { vm.captionReplace(find, rep) }, enabled = find.isNotBlank()) { Text(tr("استبدال الكل", "Replace all")) }
    TextButton(onClick = { vm.resegmentCaptions() }) { Text(tr("إعادة تقسيم الأسطر", "Re-split lines")) }
    SectionTitle(tr("المقاطع والكلمات", "Segments & words") + " (${ct.segments.size})")
    Hint(tr("الكلمات منخفضة الثقة باللون البرتقالي. انقر على كلمة لتعديلها.", "Low-confidence words are orange. Tap a word to edit it."))
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editText by remember { mutableStateOf("") }
    ct.segments.forEachIndexed { si, seg ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(BkColors.Raised, RoundedCornerShape(8.dp)).padding(8.dp)) {
            Row {
                Text("%.2f → %.2f".format(seg.start.toDouble(), seg.end.toDouble()) + if (seg.speaker > 0) " · S${seg.speaker + 1}" else "", color = BkColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f).clickable { vm.seek(seg.start.toDouble()) })
                TextButton(onClick = { vm.nudgeSegment(si, -0.1) }) { Text("−0.1") }
                TextButton(onClick = { vm.nudgeSegment(si, 0.1) }) { Text("+0.1") }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                seg.words.forEachIndexed { wi, w ->
                    Text(w.text, color = if (w.confidence < 0.6) Color(0xFFFFA24D) else BkColors.Text,
                        modifier = Modifier.clickable { editing = si to wi; editText = w.text }.padding(2.dp))
                }
            }
            seg.translation?.let { Text(it, color = BkColors.Accent, fontSize = 12.sp) }
        }
    }
    editing?.let { (si, wi) ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(editText, { editText = it }, Modifier.weight(1f), singleLine = true)
            Button(onClick = { vm.editCaptionWord(si, wi, editText); editing = null }) { Text("✓") }
        }
    }
    SectionTitle(tr("تصدير الكابشن منفصلًا", "Export captions separately"))
    Hint(tr("من مركز التصدير: SRT أو VTT أو ASS.", "From the Export center: SRT, VTT or ASS."))
}

@Composable
fun AiMattePanel(st: EditorState, vm: EditorViewModel, c: Clip) {
    ModelGate(vm, ModelManager.MATTING) {
        val am = c.aiMatte
        if (am == null) Button(onClick = { vm.runMatting() }, enabled = st.busy == null) { Text(tr("فصل الشخص عن الخلفية", "Separate subject from background")) }
        else {
            ToggleRow(tr("تفعيل القناع", "Enable matte"), am.enabled) { v -> vm.updateSelected("ai-matte") { it.copy(aiMatte = am.copy(enabled = v)) } }
            PropertyRow(tr("نعومة الحواف", "Edge softness"), am.edgeSoftness, 0.0..0.6, defaultValue = 0.15) { v -> vm.updateSelected("ai-soft") { it.copy(aiMatte = am.copy(edgeSoftness = v)) } }
            PropertyRow("Choke", am.choke, -1.0..1.0, defaultValue = 0.0) { v -> vm.updateSelected("ai-choke") { it.copy(aiMatte = am.copy(choke = v)) } }
            PropertyRow(tr("ظل التماس", "Contact shadow"), am.contactShadow, 0.0..1.0, defaultValue = 0.0) { v -> vm.updateSelected("ai-shadow") { it.copy(aiMatte = am.copy(contactShadow = v), effects = vm.withContactShadow(it.effects, v)) } }
            ChipRow(listOf(KeyView.COMPOSITE, KeyView.MATTE), am.view, { if (it == KeyView.MATTE) tr("عرض القناع (Alpha)", "Show matte") else tr("النتيجة", "Composite") }) { v -> vm.updateSelected("ai-view") { it.copy(aiMatte = am.copy(view = v)) } }
            Hint(tr("فرشاة التصحيح: انقر في لوحة الأقنعة على المنطقة لإضافتها أو إزالتها عند المؤشر.", "Correction brush: tap areas in the frame below to add/remove at the playhead."))
            BrushRow(vm, c)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.runMatting() }) { Text(tr("إعادة الحساب", "Recompute")) }
                OutlinedButton(onClick = { vm.exportAlphaPng() }) { Text(tr("تصدير PNG بشفافية", "Export PNG + alpha")) }
                TextButton(onClick = { vm.updateSelected("ai-remove") { it.copy(aiMatte = null) } }) { Text(tr("إزالة", "Remove")) }
            }
        }
    }
}

@Composable
private fun BrushRow(vm: EditorViewModel, c: Clip) {
    var add by remember { mutableStateOf(true) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow(listOf(true, false), add, { if (it) tr("إضافة", "Add") else tr("حذف", "Erase") }) { add = it }
    }
    FramePicker(vm, onTap = { u, v, _ -> vm.addBrushStroke(add, u, v) })
}

@Composable
fun StemPanel(st: EditorState, vm: EditorViewModel, c: Clip) {
    ModelGate(vm, ModelManager.SEPARATION) {
        val sm = c.audio.stems
        if (sm == null) {
            Button(onClick = { vm.runSeparation() }, enabled = st.busy == null) { Text(tr("فصل الغناء عن الموسيقى", "Separate vocals / accompaniment")) }
            Hint(tr("الفصل ليس مثاليًا دائمًا؛ استمع للمقارنة A/B وعدّل مستوى كل جزء.", "Separation isn't always perfect; compare A/B and adjust each stem."))
        } else {
            for ((name, g) in sm.gains) {
                PropertyRow(name, g, 0.0..2.0, { "%.0f%%".format(it * 100) }, defaultValue = 1.0) { v -> vm.updateSelected("stem-gain") { it.copy(audio = it.audio.copy(stems = sm.copy(gains = sm.gains + (name to v)))) } }
            }
            ChipRow(listOf<String?>(null) + sm.gains.keys, sm.solo, { it ?: tr("بدون Solo", "No solo") }) { s -> vm.updateSelected("stem-solo") { it.copy(audio = it.audio.copy(stems = sm.copy(solo = s))) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.toggleStemAB() }) { Text("A/B") }
                OutlinedButton(onClick = { vm.exportStemFiles() }) { Text(tr("تصدير كل جزء", "Export each stem")) }
                TextButton(onClick = { vm.updateSelected("stems-remove") { it.copy(audio = it.audio.copy(stems = null)) } }) { Text(tr("إزالة الفصل", "Remove")) }
            }
        }
    }
}

@Composable
fun AiPanel(st: EditorState, vm: EditorViewModel) {
    val ar = isArabic()
    SectionTitle(tr("المساعد", "Assistant"))
    var cmd by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(cmd, { cmd = it; plan = null; note = null }, Modifier.fillMaxWidth(), minLines = 2,
        placeholder = { Text(tr("مثال: سوّ ريلز عمودي، احذف الصمت، كابشن عربي، خفّض الموسيقى أثناء الكلام، لون سينمائي دافئ وانتقالات ناعمة", "e.g. vertical reel, remove silence, Arabic captions, duck music under speech, warm cinematic look, soft transitions")) })
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.planAssistant(cmd) { p, n -> plan = p; note = n } }, enabled = cmd.isNotBlank() && st.busy == null) { Text(tr("تحليل الأمر", "Analyse")) }
        plan?.let { Button(onClick = { vm.applyPlan(it); plan = null }) { Text(tr("تطبيق كتعديل واحد قابل للتراجع", "Apply as one undoable edit")) } }
    }
    note?.let { Text(it, color = BkColors.Muted) }
    plan?.let { p -> Column { p.operations.forEach { Text("• " + it.describeAr(), color = BkColors.Text) } } }
    ModelGate(vm, ModelManager.PLANNER) { Hint(tr("المساعد يفهم الطلبات المركّبة بنموذج لغوي محلي ويحوّلها إلى عمليات محددة فقط.", "The assistant understands compound requests with an on-device language model and only emits defined operations.")) }
    SectionTitle(tr("أدوات التحرير الذكية", "Smart editing tools"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = vm::detectScenes) { Text(tr("اكتشاف المشاهد", "Detect scenes")) }
        OutlinedButton(onClick = { vm.autoMontage() }) { Text(tr("مونتاج تلقائي", "Auto montage")) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.suggestShorts() }) { Text(tr("اقتراح مقاطع قصيرة", "Suggest short clips")) }
        OutlinedButton(onClick = { vm.matchColorAll() }) { Text(tr("مطابقة ألوان اللقطات", "Match colours across shots")) }
    }
    SectionTitle(tr("تتبع وتثبيت وإعادة تأطير", "Tracking, stabilisation & reframe"))
    NeedsSelection(st) { c ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.trackPointAtCenter() }) { Text(tr("تتبع نقطة (وسط الطبقة)", "Track point (layer centre)")) }
            OutlinedButton(onClick = { vm.stabilize() }) { Text(tr("تثبيت الاهتزاز", "Stabilise")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.attachSelectedToTrack() }) { Text(tr("ربط عنصر بالتتبع", "Attach layer to track")) }
            ModelGate(vm, ModelManager.MATTING) { OutlinedButton(onClick = { vm.autoReframe() }) { Text(tr("إعادة تأطير تلقائية (Auto Reframe)", "Auto reframe")) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.removeObjectTemporal() }) { Text(tr("إزالة عنصر متحرك (من الإطارات المجاورة)", "Remove moving object (temporal fill)")) }
        }
        Hint(tr("إزالة العناصر تستخدم قناع الطبقة الأول كمنطقة الإزالة وتملؤها من إطارات أخرى؛ تعمل جيدًا مع الكاميرا الثابتة.", "Object removal uses the layer's first mask as the region and fills it from other frames; works best with a locked-off camera."))
    }
    SectionTitle(tr("مدير النماذج", "Model manager"))
    ModelManagerList(vm)
}

@Composable
fun ModelManagerList(vm: EditorViewModel) = ModelManagerList(vm.ai.models)

/** Model manager used by the editor, onboarding and settings (shared download state). */
@Composable
fun ModelManagerList(models: ModelManager, only: Set<String>? = null) {
    val states by models.states.collectAsState()
    val ar = isArabic()
    Text(tr("المساحة المتاحة: ", "Free space: ") + ModelManager.human(models.freeBytes()), color = BkColors.Muted)
    for (m in models.catalog) {
        if (only != null && m.id !in only) continue
        val s = states[m.id]
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(BkColors.Raised, RoundedCornerShape(10.dp)).padding(10.dp)) {
            Text((if (ar) m.ar else m.en) + " · " + ModelManager.human(m.totalBytes))
            Text(m.task + " · RAM ≈ " + ModelManager.human(m.ramBytes) + " · " + m.licenseCode + " / " + m.licenseWeights, color = BkColors.Muted, fontSize = 12.sp)
            Text(if (ar) m.notesAr else m.notesEn, color = BkColors.Muted, fontSize = 12.sp)
            when {
                s?.progress != null -> { LinearProgressIndicator(progress = { s.progress ?: 0f }, Modifier.fillMaxWidth()); TextButton(onClick = { models.cancel(m.id) }) { Text(tr("إلغاء", "Cancel")) } }
                s?.installed == true -> TextButton(onClick = { models.delete(m.id) }) { Text(tr("✓ مثبت — حذف النموذج (لا يحذف المشاريع)", "✓ Installed — delete model (projects are kept)")) }
                else -> { s?.error?.let { Text(it, color = BkColors.Danger, fontSize = 12.sp) }; TextButton(onClick = { models.startDownload(m.id) }) { Text(tr("تنزيل", "Download")) } }
            }
        }
    }
}
