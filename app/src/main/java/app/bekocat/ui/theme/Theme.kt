package app.bekocat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens — mirrored in design/tokens.json. */
object BkColors {
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF141821)
    val Raised = Color(0xFF1C2230)
    val Outline = Color(0xFF2A3142)
    val Primary = Color(0xFF9B7CFF)
    val PrimaryDim = Color(0xFF4B3E80)
    val Accent = Color(0xFF6EE7C8)
    val Text = Color(0xFFF4F7FB)
    val Muted = Color(0xFF98A3B7)
    val Danger = Color(0xFFFF6577)
    val TrackVideo = Color(0xFF3A3170)
    val TrackOverlay = Color(0xFF2E4A6B)
    val TrackText = Color(0xFF6B4A2E)
    val TrackAudio = Color(0xFF1F5A4C)
}

object BkSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val touch = 48.dp
}

object BkRadius {
    val s = 6.dp
    val m = 10.dp
    val l = 16.dp
}

private val scheme = darkColorScheme(
    primary = BkColors.Primary,
    onPrimary = Color(0xFF120B2E),
    primaryContainer = BkColors.PrimaryDim,
    onPrimaryContainer = BkColors.Text,
    secondary = BkColors.Accent,
    onSecondary = Color(0xFF04261E),
    background = BkColors.Background,
    onBackground = BkColors.Text,
    surface = BkColors.Surface,
    onSurface = BkColors.Text,
    surfaceVariant = BkColors.Raised,
    onSurfaceVariant = BkColors.Muted,
    surfaceContainer = BkColors.Surface,
    surfaceContainerHigh = BkColors.Raised,
    surfaceContainerHighest = BkColors.Raised,
    outline = BkColors.Outline,
    error = BkColors.Danger,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(
    small = RoundedCornerShape(BkRadius.s),
    medium = RoundedCornerShape(BkRadius.m),
    large = RoundedCornerShape(BkRadius.l),
)

@Composable
fun BekocatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
