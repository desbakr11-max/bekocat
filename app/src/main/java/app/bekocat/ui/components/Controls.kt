package app.bekocat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bekocat.ui.theme.BkColors

/** Bilingual label helper: Arabic is the default UI language; English is complete. Pro terms keep the English name. */
@Composable
fun tr(ar: String, en: String): String = if (isArabic()) ar else en

@Composable
fun isArabic(): Boolean = LocalConfiguration.current.locales[0]?.language != "en"

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 12.dp, bottom = 6.dp))
}

/**
 * A numeric property row: live slider (preview while dragging, one undo step on release),
 * tap the value to type an exact number, ◆ toggles a keyframe at the playhead,
 * long-press the label to reset.
 */
@Composable
fun PropertyRow(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    format: (Double) -> String = { "%.2f".format(it) },
    keyframed: Boolean? = null,
    hasKeyframeHere: Boolean = false,
    defaultValue: Double? = null,
    onToggleKey: (() -> Unit)? = null,
    onPreview: (Double) -> Unit = {},
    onCommit: (Double) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (keyframed != null && onToggleKey != null) {
                Box(
                    Modifier.size(28.dp).clickable(onClickLabel = "keyframe") { onToggleKey() }
                        .semantics { contentDescription = "Keyframe"; stateDescription = if (hasKeyframeHere) "on" else "off" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (hasKeyframeHere) "◆" else "◇", color = if (keyframed) BkColors.Accent else BkColors.Muted, fontSize = 16.sp)
                }
            }
            Text(label, Modifier.weight(1f).let { m -> if (defaultValue != null) m.clickable(onClickLabel = "reset") { onCommit(defaultValue) } else m },
                style = MaterialTheme.typography.bodyMedium)
            Text(format(local.toDouble()), color = BkColors.Accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { editing = true }.background(BkColors.Raised, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Slider(
            value = local.coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { local = it; onPreview(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            onValueChangeFinished = { onCommit(local.toDouble()) },
            modifier = Modifier.height(32.dp),
        )
    }
    if (editing) NumberDialog(label, local.toDouble(), onDismiss = { editing = false }) { v -> editing = false; local = v.toFloat(); onCommit(v) }
}

@Composable
fun NumberDialog(title: String, initial: Double, onDismiss: () -> Unit, onOk: (Double) -> Unit) {
    var text by remember { mutableStateOf(if (initial == Math.rint(initial)) initial.toLong().toString() else "%.4f".format(initial).trimEnd('0')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
        confirmButton = { TextButton(onClick = { text.replace('٫', '.').replace(',', '.').map { c -> if (c in '٠'..'٩') ('0' + (c - '٠')) else c }.joinToString("").toDoubleOrNull()?.let(onOk) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("إلغاء", "Cancel")) } },
        containerColor = BkColors.Surface,
    )
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChipRow(items: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { it -> FilterChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text(label(it)) }) }
    }
}

@Composable
fun ColorSwatches(selected: Long, colors: List<Long>, onPick: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            Box(
                Modifier.size(36.dp).background(Color(c), CircleShape)
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) BkColors.Accent else BkColors.Outline, CircleShape)
                    .clickable { onPick(c) }.semantics { contentDescription = "#" + java.lang.Long.toHexString(c) }
            )
        }
    }
}

val PALETTE = listOf(
    0xFFF4F7FB, 0xFF0B0D12, 0xFF9B7CFF, 0xFF6EE7C8, 0xFFFFC857, 0xFFFF6577, 0xFF3BA3FF, 0xFF00B140,
    0xFFFF8A00, 0xFFE91E63, 0xFF8BC34A, 0xFF795548, 0x00000000L, 0x99000000, 0xCCFFFFFF,
)

@Composable
fun Hint(text: String) {
    Text(text, color = BkColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int = 8) = Spacer(Modifier.width(w.dp))
