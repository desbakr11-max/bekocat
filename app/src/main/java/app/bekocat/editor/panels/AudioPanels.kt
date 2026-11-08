package app.bekocat.editor.panels

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.bekocat.core.timeline.Animatable
import app.bekocat.core.timeline.AudioRole
import app.bekocat.core.timeline.EffectCatalog
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
import app.bekocat.ui.components.isArabic
import app.bekocat.ui.components.tr
import app.bekocat.ui.theme.BkColors

@Composable
fun ClipAudioPanel(st: EditorState, vm: EditorViewModel) = NeedsSelection(st) { c ->
    val ar = isArabic()
    AnimRow(vm, c, Props.volume, { "%.0f%% (%.1f dB)".format(it * 100, 20 * kotlin.math.log10(it.coerceAtLeast(1e-4))) })
    AnimRow(vm, c, Props.pan, { if (it < -0.01) "L %.0f".format(-it * 100) else if (it > 0.01) "R %.0f".format(it * 100) else "C" })
    ToggleRow(tr("كتم المقطع", "Mute clip"), c.audio.muted) { v -> vm.updateSelected("mute") { it.copy(audio = it.audio.copy(muted = v)) } }
    PropertyRow(tr("تلاشي دخول", "Fade in"), c.audio.fadeIn.toDouble(), 0.0..5.0, { "%.2fs".format(it) }, defaultValue = 0.0) { v -> vm.updateSelected("fade-in") { it.copy(audio = it.audio.copy(fadeIn = Rational.fromDouble(v, 1000))) } }
    PropertyRow(tr("تلاشي خروج", "Fade out"), c.audio.fadeOut.toDouble(), 0.0..5.0, { "%.2fs".format(it) }, defaultValue = 0.0) { v -> vm.updateSelected("fade-out") { it.copy(audio = it.audio.copy(fadeOut = Rational.fromDouble(v, 1000))) } }
    PropertyRow(tr("طبقة الصوت (Pitch)", "Pitch shift"), c.audio.pitchSemitones, -12.0..12.0, { "%+.1f st".format(it) }, defaultValue = 0.0) { v -> vm.updateSelected("pitch") { it.copy(audio = it.audio.copy(pitchSemitones = v)) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = vm::detachAudio) { Text(tr("فصل الصوت عن الفيديو", "Detach audio")) }
    }
    SectionTitle(tr("معالجة الصوت", "Audio processing"))
    c.audio.effects.forEach { e ->
        val spec = EffectCatalog.find(e.type) ?: return@forEach
        Row(Modifier.fillMaxWidth()) {
            Text((if (ar) spec.ar else spec.en) + if (!e.enabled) " (Bypass)" else "", Modifier.weight(1f))
            TextButton(onClick = { vm.updateSelected("afx-bypass") { cl -> cl.copy(audio = cl.audio.copy(effects = cl.audio.effects.map { if (it.id == e.id) it.copy(enabled = !it.enabled) else it })) } }) { Text(if (e.enabled) "⏻" else "○") }
            TextButton(onClick = { vm.updateSelected("afx-remove") { cl -> cl.copy(audio = cl.audio.copy(effects = cl.audio.effects.filterNot { it.id == e.id })) } }) { Text("✕") }
        }
        for (ps in spec.params) {
            val prop = Prop("afx:${e.id}:${ps.key}", ps.ar, ps.en, ps.min, ps.max, ps.default,
                { cl -> cl.audio.effects.firstOrNull { it.id == e.id }?.params?.get(ps.key) ?: Animatable.of(ps.default) },
                { cl, a -> cl.copy(audio = cl.audio.copy(effects = cl.audio.effects.map { if (it.id == e.id) it.copy(params = it.params + (ps.key to a)) else it })) })
            PropertyRow(if (ar) ps.ar else ps.en, prop.get(c).base, ps.min..ps.max, defaultValue = ps.default) { v -> vm.updateSelected("afx") { cl -> prop.set(cl, Animatable.of(v)) } }
        }
    }
    ChipRow(EffectCatalog.audio, null, { if (ar) it.ar else it.en }) { spec -> vm.updateSelected("afx-add") { cl -> cl.copy(audio = cl.audio.copy(effects = cl.audio.effects + spec.instance(vm.repo.ids.next("afx")))) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.applyVoicePreset() }) { Text(tr("تنظيف الصوت (حوار)", "Clean voice preset")) }
    }
    SectionTitle(tr("فصل المصادر بالذكاء الاصطناعي", "AI source separation"))
    StemPanel(st, vm, c)
}

@Composable
fun MixerPanel(st: EditorState, vm: EditorViewModel) {
    val p = st.project ?: return
    val ctx = LocalContext.current
    SectionTitle(tr("المسارات", "Tracks"))
    for (t in p.sequence.tracks.filter { tr -> tr.kind == TrackKind.AUDIO || tr.clips.any { p.asset(it.assetId)?.hasAudio == true } }) {
        Text(t.name + " · " + t.role.name.lowercase(), color = BkColors.Muted)
        PropertyRow(tr("المستوى", "Level"), t.volume, 0.0..2.0, { "%.1f dB".format(20 * kotlin.math.log10(it.coerceAtLeast(1e-4))) }, defaultValue = 1.0) { v -> vm.updateTrack(t.id) { it.copy(volume = v) } }
        PropertyRow("Pan", t.pan, -1.0..1.0, defaultValue = 0.0) { v -> vm.updateTrack(t.id) { it.copy(pan = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.updateTrack(t.id) { it.copy(muted = !it.muted) } }) { Text(if (t.muted) "🔇" else "M") }
            OutlinedButton(onClick = { vm.updateTrack(t.id) { it.copy(solo = !it.solo) } }) { Text(if (t.solo) "S●" else "S") }
            if (t.kind == TrackKind.AUDIO) OutlinedButton(onClick = { vm.updateTrack(t.id) { it.copy(duckUnderDialogue = !it.duckUnderDialogue) } }) { Text((if (t.duckUnderDialogue) "✓ " else "") + "Ducking") }
        }
        if (t.kind == TrackKind.AUDIO) ChipRow(AudioRole.entries, t.role, { it.name.lowercase() }) { r -> vm.updateTrack(t.id) { it.copy(role = r) } }
        if (t.duckUnderDialogue) PropertyRow(tr("عمق الخفض", "Duck depth"), t.duckAmountDb, -30.0..0.0, { "%.0f dB".format(it) }, defaultValue = -12.0) { v -> vm.updateTrack(t.id) { it.copy(duckAmountDb = v) } }
    }
    SectionTitle(tr("الماستر", "Master"))
    val m = p.sequence.master
    ChipRow(listOf<Double?>(null, -9.0, -14.0, -16.0, -23.0), m.loudnessTargetLufs, { it?.let { v -> "%.0f LUFS".format(v) } ?: tr("بدون تطبيع", "No normalisation") }) { v -> vm.updateSequence("loudness") { it.copy(master = it.master.copy(loudnessTargetLufs = v)) } }
    PropertyRow(tr("سقف المحدد", "Limiter ceiling"), m.limiterCeilingDb, -6.0..0.0, { "%.1f dBFS".format(it) }, defaultValue = -1.0) { v -> vm.updateSequence("ceiling") { it.copy(master = it.master.copy(limiterCeilingDb = v)) } }
    PropertyRow(tr("مستوى الماستر", "Master level"), m.volume, 0.0..2.0, defaultValue = 1.0) { v -> vm.updateSequence("master") { it.copy(master = it.master.copy(volume = v)) } }
    Button(onClick = vm::measureLoudness) { Text(tr("قياس Loudness والذروة", "Measure loudness & peak")) }
    SectionTitle(tr("الإيقاع والصمت", "Beats & silence"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = vm::detectBeats) { Text(tr("كشف الإيقاع (Beat markers)", "Detect beats")) }
        OutlinedButton(onClick = vm::cutOnBeats) { Text(tr("قص على الإيقاع", "Cut on beats")) }
    }
    var thr by remember { mutableDoubleStateOf(-40.0) }
    var minS by remember { mutableDoubleStateOf(0.6) }
    var padS by remember { mutableDoubleStateOf(0.12) }
    PropertyRow(tr("عتبة الصمت", "Silence threshold"), thr, -70.0..-15.0, { "%.0f dB".format(it) }) { thr = it }
    PropertyRow(tr("أقل مدة صمت", "Min silence"), minS, 0.2..3.0, { "%.2fs".format(it) }) { minS = it }
    PropertyRow(tr("الهامش", "Padding"), padS, 0.0..0.5, { "%.2fs".format(it) }) { padS = it }
    Button(onClick = { vm.removeSilence(thr, minS, padS) }) { Text(tr("حذف الصمت (قابل للتراجع)", "Remove silence (undoable)")) }
    SectionTitle(tr("تسجيل تعليق صوتي", "Voice-over"))
    val micMsg = tr("مطلوب إذن الميكروفون", "Microphone permission required")
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.toggleVoiceOver() else vm.message(micMsg) }
    Button(onClick = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.toggleVoiceOver()
        else perm.launch(Manifest.permission.RECORD_AUDIO)
    }) { Text(if (vm.recording) tr("⏹ إيقاف التسجيل وإضافته", "⏹ Stop & insert") else tr("⏺ تسجيل من المؤشر", "⏺ Record at playhead")) }
}
