package app.bekocat.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bekocat.core.timeline.Animatable
import app.bekocat.core.timeline.Clip
import app.bekocat.editor.EditorState
import app.bekocat.editor.EditorViewModel
import app.bekocat.editor.Panel
import app.bekocat.editor.Prop
import app.bekocat.ui.components.Hint
import app.bekocat.ui.components.PropertyRow
import app.bekocat.ui.components.tr
import app.bekocat.ui.theme.BkColors

/** Inline panel (bottom on phones, side on tablets) — the preview stays visible while editing. */
@Composable
fun PanelHost(st: EditorState, vm: EditorViewModel, modifier: Modifier) {
    val title = when (st.panel) {
        Panel.MEDIA -> tr("الوسائط والقوالب", "Media & templates"); Panel.INSPECTOR -> tr("التحويل (Transform)", "Transform")
        Panel.KEYFRAMES -> tr("المنحنيات (Graph Editor)", "Graph Editor"); Panel.TEXT -> tr("استوديو النص", "Text Studio")
        Panel.CAPTIONS -> tr("استوديو الكابشن", "Caption Studio"); Panel.COLOR -> tr("استوديو اللون", "Color Studio")
        Panel.EFFECTS -> tr("المؤثرات (Effects)", "Effects"); Panel.TRANSITION -> tr("الانتقالات", "Transitions")
        Panel.MASK -> tr("الأقنعة والمطابقة (Masks / Mattes)", "Masks & mattes"); Panel.KEY -> tr("الخلفية والكروما", "Background & keying")
        Panel.AUDIO -> tr("صوت المقطع", "Clip audio"); Panel.MIXER -> tr("المكسر الصوتي", "Audio mixer")
        Panel.AI -> tr("الذكاء الاصطناعي والمساعد", "AI & assistant"); Panel.EXPORT -> tr("مركز التصدير", "Export center")
        Panel.PROJECT -> tr("المشروع", "Project"); Panel.TRACKS -> tr("المسار", "Track")
        Panel.SPEED -> tr("السرعة والزمن", "Speed & time"); Panel.SHAPES -> tr("الأشكال", "Shapes")
        Panel.NONE -> ""
    }
    Column(modifier.background(BkColors.Surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { vm.openPanel(Panel.NONE) }) { Icon(Icons.Filled.Close, tr("إغلاق", "Close")) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
            when (st.panel) {
                Panel.MEDIA -> MediaPanel(st, vm)
                Panel.INSPECTOR -> InspectorPanel(st, vm)
                Panel.KEYFRAMES -> GraphPanel(st, vm)
                Panel.TEXT -> TextPanel(st, vm)
                Panel.CAPTIONS -> CaptionPanel(st, vm)
                Panel.COLOR -> ColorPanel(st, vm)
                Panel.EFFECTS -> EffectsPanel(st, vm)
                Panel.TRANSITION -> TransitionPanel(st, vm)
                Panel.MASK -> MaskPanel(st, vm)
                Panel.KEY -> KeyPanel(st, vm)
                Panel.AUDIO -> ClipAudioPanel(st, vm)
                Panel.MIXER -> MixerPanel(st, vm)
                Panel.AI -> AiPanel(st, vm)
                Panel.EXPORT -> ExportPanel(st, vm)
                Panel.PROJECT -> ProjectPanel(st, vm)
                Panel.TRACKS -> TrackPanel(st, vm)
                Panel.SPEED -> SpeedPanel(st, vm)
                Panel.SHAPES -> ShapePanel(st, vm)
                Panel.NONE -> {}
            }
        }
    }
}

@Composable
fun NeedsSelection(st: EditorState, content: @Composable (Clip) -> Unit) {
    val c = st.selected
    if (c == null) Hint(tr("اختر مقطعًا أو طبقة من الخط الزمني أو بالنقر على المعاينة.", "Select a clip or layer on the timeline or by tapping the preview."))
    else content(c)
}

/** Renders a keyframe-able property row bound to the selected clip(s). */
@Composable
fun AnimRow(vm: EditorViewModel, c: Clip, prop: Prop, format: (Double) -> String = { "%.2f".format(it) }) {
    val a: Animatable = prop.get(c)
    val lt = vm.localTime(c)
    PropertyRow(
        label = tr(prop.ar, prop.en), value = a.valueAt(lt), range = prop.min..prop.max, format = format,
        keyframed = a.keyframes.isNotEmpty(), hasKeyframeHere = a.keyframes.any { it.time == lt }, defaultValue = prop.def,
        onToggleKey = { vm.toggleKey(prop) },
        onPreview = { vm.setProp(prop, it, commit = false) },
        onCommit = { vm.setProp(prop, it, commit = true) },
    )
}
