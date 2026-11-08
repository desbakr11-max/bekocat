package app.bekocat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bekocat.ui.theme.BkColors

/** The Bekocat mark drawn natively (same geometry as design/brand/bekocat-mark.svg). */
@Composable
fun BekocatLogo(size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 100f
        fun p(x: Float, y: Float) = Offset(x * k, y * k)
        val frame = Path().apply {
            moveTo(19 * k, 76 * k); lineTo(19 * k, 36 * k); lineTo(27 * k, 15 * k); lineTo(41 * k, 29 * k)
            lineTo(59 * k, 29 * k); lineTo(73 * k, 15 * k); lineTo(81 * k, 36 * k); lineTo(81 * k, 76 * k)
            quadraticTo(81 * k, 84 * k, 73 * k, 84 * k); lineTo(27 * k, 84 * k)
            quadraticTo(19 * k, 84 * k, 19 * k, 76 * k); close()
        }
        drawPath(frame, BkColors.Primary, style = Stroke(width = 7 * k, join = StrokeJoin.Round))
        val play = Path().apply { moveTo(p(44f, 46f).x, p(44f, 46f).y); lineTo(44 * k, 68 * k); lineTo(62 * k, 57 * k); close() }
        drawPath(play, BkColors.Text)
        drawPath(play, BkColors.Text, style = Stroke(width = 3 * k, join = StrokeJoin.Round))
        val key = Path().apply { moveTo(50 * k, 77 * k); lineTo(57 * k, 84 * k); lineTo(50 * k, 91 * k); lineTo(43 * k, 84 * k); close() }
        drawPath(key, BkColors.Accent)
    }
}
