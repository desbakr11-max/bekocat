package app.bekocat.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bekocat.core.timeline.ClipKind
import app.bekocat.core.timeline.Project
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.TrackKind
import app.bekocat.ui.theme.BkColors
import kotlin.math.abs
import kotlin.math.roundToInt

private val RULER = 24.dp
private val HEADER_W = 64.dp

/**
 * Multi-track timeline. Time always runs left→right (documented convention; the rest of the
 * UI follows RTL). Gestures: tap = select, long-press = add to selection, drag a clip = move
 * (also across compatible tracks), drag an edge = trim (ripple when enabled), drag the ruler =
 * scrub, pinch = zoom. Snapping to edges, markers and the playhead when enabled.
 */
@Composable
fun TimelineView(
    project: Project,
    playhead: Double,
    playing: Boolean,
    selection: Set<String>,
    pxPerSecond: Float,
    thumbs: (String) -> List<ImageBitmap>?,
    peaks: (String) -> FloatArray?,
    version: Int,
    inPoint: Double?, outPoint: Double?,
    onSeek: (Double) -> Unit,
    onSelect: (String?, Boolean) -> Unit,
    onZoom: (Float) -> Unit,
    onMove: (String, String, Double) -> Unit,
    onTrim: (String, Boolean, Double) -> Unit,
    onTrackHeader: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val density = LocalDensity.current
        val scroll = rememberScrollState()
        val vscroll = rememberScrollState()
        val measurer = rememberTextMeasurer()
        val seq = project.sequence
        val tracks = seq.tracks
        val totalSec = seq.duration.toDouble() + 8.0
        val pad = with(density) { 16.dp.toPx() }
        val rulerPx = with(density) { RULER.toPx() }
        val trackHeights = tracks.map { with(density) { (if (it.kind == TrackKind.AUDIO) 52 else it.heightDp).dp.toPx() } }
        val tops = trackHeights.runningFold(rulerPx) { acc, h -> acc + h + 2f }
        val contentH = with(density) { tops.last().toDp() }
        val contentW = with(density) { (totalSec * pxPerSecond + pad * 2).toFloat().toDp() }
        val zoom by rememberUpdatedState(pxPerSecond)
        val zoomCb by rememberUpdatedState(onZoom)
        val slop = LocalViewConfiguration.current.touchSlop
        var drag by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
        var trim by remember { mutableStateOf<Triple<String, Boolean, Float>?>(null) }

        LaunchedEffect(playhead, playing) {
            if (playing) {
                val x = (pad + playhead * pxPerSecond).roundToInt()
                if (x > scroll.value + scroll.viewportSize * 0.85 || x < scroll.value) scroll.scrollTo((x - scroll.viewportSize / 6).coerceAtLeast(0))
            }
        }

        Row(modifier.fillMaxWidth().background(BkColors.Background)) {
            Column(Modifier.width(HEADER_W).fillMaxHeight().verticalScroll(vscroll)) {
                Box(Modifier.height(RULER))
                tracks.forEachIndexed { i, t ->
                    Box(
                        Modifier.width(HEADER_W).height(with(density) { (trackHeights[i] + 2f).toDp() }).background(BkColors.Surface)
                            .clickable(onClickLabel = t.name) { onTrackHeader(t.id) }.padding(4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            Text(t.name, color = BkColors.Text, fontSize = 12.sp, maxLines = 1)
                            Text(buildString {
                                if (t.locked) append("🔒"); if (t.hidden) append("◌"); if (t.muted) append("🔇"); if (t.solo) append("S")
                                if (t.duckUnderDialogue) append("↘")
                            }, color = BkColors.Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                if (ev.changes.count { it.pressed } >= 2) {
                                    val z = ev.calculateZoom()
                                    if (z != 1f) zoomCb(zoom * z)
                                    ev.changes.forEach { it.consume() }
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    }
                    .horizontalScroll(scroll)
                    .verticalScroll(vscroll)
                    .semantics { contentDescription = "Timeline" }
            ) {
                Canvas(
                    Modifier.width(contentW).height(contentH)
                        .pointerInput(project, pxPerSecond, selection) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val p0 = down.position
                                val t0 = ((p0.x - pad) / pxPerSecond).toDouble()
                                if (p0.y < rulerPx) {
                                    onSeek(t0.coerceAtLeast(0.0)); down.consume()
                                    do {
                                        val ev = awaitPointerEvent()
                                        val c = ev.changes.first()
                                        if (c.pressed) { onSeek(((c.position.x - pad) / pxPerSecond).toDouble().coerceAtLeast(0.0)); c.consume() }
                                    } while (ev.changes.any { it.pressed })
                                    return@awaitEachGesture
                                }
                                val row = tops.indexOfLast { it <= p0.y }.coerceIn(0, tracks.size - 1)
                                val track = tracks[row]
                                val time = Rational.fromDouble(t0.coerceAtLeast(0.0), 1000)
                                val hit = track.clips.firstOrNull { it.contains(time) }
                                    ?: track.clips.firstOrNull { abs(it.timelineEnd.toDouble() - t0) * pxPerSecond < 12 }
                                if (hit == null) {
                                    var moved = false; var up = false
                                    while (!up) {
                                        val ev = awaitPointerEvent()
                                        if ((ev.changes.first().position - p0).getDistance() > slop) moved = true
                                        up = ev.changes.none { it.pressed }
                                    }
                                    if (!moved) { onSelect(null, false); onSeek(t0.coerceAtLeast(0.0)) }
                                    return@awaitEachGesture
                                }
                                val l = (pad + hit.timelineStart.toDouble() * pxPerSecond).toFloat()
                                val r = (pad + hit.timelineEnd.toDouble() * pxPerSecond).toFloat()
                                val edge = 14.dp.toPx()
                                val onStart = p0.x - l < edge && r - l > edge * 3
                                val onEnd = r - p0.x < edge && r - l > edge * 3
                                var total = Offset.Zero; var moved = false
                                val longPressAt = System.currentTimeMillis() + viewConfiguration.longPressTimeoutMillis
                                var longPressed = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.first()
                                    if (!c.pressed) break
                                    total += c.positionChange()
                                    if (!moved && total.getDistance() > slop) moved = true
                                    if (!moved && System.currentTimeMillis() > longPressAt && !longPressed) { longPressed = true; onSelect(hit.id, true) }
                                    if (moved && !longPressed && (hit.id in selection || onStart || onEnd)) {
                                        if (onStart || onEnd) trim = Triple(hit.id, onStart, total.x) else drag = Triple(hit.id, total.x, total.y)
                                        c.consume()
                                    }
                                }
                                val dr = drag; val tr = trim
                                when {
                                    !moved && !longPressed -> { onSelect(hit.id, false); onSeek(t0.coerceAtLeast(0.0)) }
                                    tr != null -> onTrim(tr.first, tr.second, (if (tr.second) hit.timelineStart else hit.timelineEnd).toDouble() + tr.third / pxPerSecond)
                                    dr != null -> {
                                        val targetRow = tops.indexOfLast { it <= p0.y + dr.third }.coerceIn(0, tracks.size - 1)
                                        val target = tracks[targetRow]
                                        val compatible = (target.kind == TrackKind.AUDIO) == (track.kind == TrackKind.AUDIO)
                                        onMove(hit.id, if (compatible) target.id else track.id, hit.timelineStart.toDouble() + dr.second / pxPerSecond)
                                    }
                                }
                                drag = null; trim = null
                            }
                        }
                ) {
                    if (version < 0) return@Canvas
                    fun xOf(t: Double) = pad + (t * pxPerSecond).toFloat()
                    drawRect(BkColors.Surface, Offset.Zero, Size(size.width, rulerPx))
                    val step = listOf(1.0 / 30, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 300.0).first { it * pxPerSecond >= 56 }
                    var s = 0.0
                    while (s <= totalSec) {
                        val x = xOf(s)
                        drawLine(BkColors.Outline, Offset(x, rulerPx - 8), Offset(x, rulerPx), 1.5f)
                        val label = if (step < 1) "%.2f".format(s) else "${s.toInt() / 60}:%02d".format(s.toInt() % 60)
                        drawText(measurer, label, Offset(x + 3, 3f), style = TextStyle(color = BkColors.Muted, fontSize = 10.sp))
                        s += step
                    }
                    if (inPoint != null || outPoint != null) {
                        val a = xOf(inPoint ?: 0.0); val b = xOf(outPoint ?: seq.duration.toDouble())
                        drawRect(BkColors.Primary.copy(alpha = 0.25f), Offset(a, 0f), Size((b - a).coerceAtLeast(1f), rulerPx))
                    }
                    for (m in seq.markers) {
                        val x = xOf(m.time.toDouble())
                        val col = Color(m.colorArgb)
                        if (m.kind == "beat") drawLine(col.copy(alpha = 0.6f), Offset(x, rulerPx - 10), Offset(x, rulerPx), 2f)
                        else drawPath(Path().apply { moveTo(x - 5, rulerPx - 12); lineTo(x + 5, rulerPx - 12); lineTo(x, rulerPx - 4); close() }, col)
                    }
                    tracks.forEachIndexed { i, track ->
                        val top = tops[i]; val th = trackHeights[i]
                        drawRect(BkColors.Surface.copy(alpha = 0.55f), Offset(0f, top), Size(size.width, th))
                        val base = when (track.kind) {
                            TrackKind.VIDEO -> BkColors.TrackVideo; TrackKind.OVERLAY -> BkColors.TrackOverlay
                            TrackKind.TEXT -> BkColors.TrackText; TrackKind.AUDIO -> BkColors.TrackAudio
                        }
                        for (c in track.clips) {
                            var l = xOf(c.timelineStart.toDouble()); var r = xOf(c.timelineEnd.toDouble())
                            var y = top
                            drag?.let { d -> if (d.first == c.id) { l += d.second; r += d.second; y += d.third } }
                            trim?.let { tr -> if (tr.first == c.id) { if (tr.second) l += tr.third else r += tr.third } }
                            if (r < 0 || l > size.width) continue
                            val selected = c.id in selection
                            val col = when (c.kind) { ClipKind.ADJUSTMENT -> Color(0xFF5A4A20); ClipKind.COMPOUND -> Color(0xFF2E5A6B); ClipKind.NULL_OBJECT -> Color(0xFF444444); else -> base }
                            drawRoundRect(col, Offset(l, y + 2), Size((r - l - 1).coerceAtLeast(2f), th - 4), CornerRadius(8f))
                            clipRect(l, y + 2, r - 1, y + th - 2) {
                                val frames = c.assetId?.let(thumbs)
                                if (!frames.isNullOrEmpty() && track.kind != TrackKind.AUDIO) {
                                    val h = (th - 4).toInt()
                                    val w = (frames[0].width * h / frames[0].height.coerceAtLeast(1)).coerceAtLeast(8)
                                    var x = l
                                    while (x < r) {
                                        val tl = ((x - pad) / pxPerSecond).toDouble().coerceAtLeast(0.0)
                                        val src = c.sourceTimeAt(Rational.fromDouble(tl, 1000)).toDouble()
                                        val f = frames[src.toInt().coerceIn(0, frames.size - 1)]
                                        drawImage(f, IntOffset.Zero, IntSize(f.width, f.height), IntOffset(x.toInt(), (y + 2).toInt()), IntSize(w, h), alpha = 0.8f)
                                        x += w
                                    }
                                }
                                val pk = c.assetId?.let(peaks)
                                if (pk != null && pk.size >= 2) {
                                    val buckets = pk.size / 2
                                    val audioTrack = track.kind == TrackKind.AUDIO
                                    val mid = if (audioTrack) y + th / 2 else y + th - 10
                                    val amp = if (audioTrack) th / 2 - 4 else 8f
                                    val vol = c.audio.volume.base.toFloat().coerceIn(0f, 2f)
                                    var x = l
                                    while (x < r) {
                                        val tl = ((x - pad) / pxPerSecond).toDouble()
                                        val src = c.sourceTimeAt(Rational.fromDouble(tl.coerceAtLeast(0.0), 1000)).toDouble()
                                        val bi = (src * 100).toInt().coerceIn(0, buckets - 1)
                                        val lo = pk[2 * bi] * vol; val hi = pk[2 * bi + 1] * vol
                                        drawLine(BkColors.Accent.copy(alpha = if (c.audio.muted) 0.2f else 0.7f), Offset(x, mid - hi * amp), Offset(x, mid - lo * amp), 1.2f)
                                        x += 2f
                                    }
                                }
                                val keyTimes = listOf(c.transform.positionX, c.transform.positionY, c.transform.scale, c.transform.rotation, c.transform.opacity, c.audio.volume)
                                    .flatMap { it.keyframes }.map { it.time.toDouble() }.distinct()
                                for (k in keyTimes) {
                                    val kx = xOf(c.timelineStart.toDouble() + k)
                                    drawPath(Path().apply { moveTo(kx, y + th - 14); lineTo(kx + 5, y + th - 9); lineTo(kx, y + th - 4); lineTo(kx - 5, y + th - 9); close() }, Color(0xFFFFC857))
                                }
                            }
                            c.transitionIn?.let { tr ->
                                val w = (tr.duration.toDouble() * pxPerSecond).toFloat()
                                drawRoundRect(BkColors.Accent.copy(alpha = 0.55f), Offset(l - w / 2, y + 4), Size(w, 10f), CornerRadius(5f))
                            }
                            val badge = buildString {
                                if (c.speed != Rational.ONE) append("×%.2f ".format(c.speed.toDouble()))
                                if (c.timeRemap != null) append("⤳ ")
                                if (c.reversed) append("⇆ ")
                                if (c.effects.isNotEmpty()) append("fx ")
                                if (c.masks.isNotEmpty()) append("◐ ")
                                if (c.chroma != null || c.aiMatte != null) append("✂ ")
                                if (c.groupId != null) append("⧉ ")
                            }
                            if (r - l > 28) {
                                drawText(measurer, (badge + c.name).ifBlank { c.kind.name }, Offset(l + 6, y + 4), style = TextStyle(color = BkColors.Text, fontSize = 11.sp),
                                    overflow = TextOverflow.Ellipsis, maxLines = 1, size = Size((r - l - 12).coerceAtLeast(1f), 16.dp.toPx()))
                            }
                            if (selected) {
                                drawRoundRect(BkColors.Primary, Offset(l, y + 2), Size((r - l - 1).coerceAtLeast(2f), th - 4), CornerRadius(8f), style = Stroke(3f))
                                drawRect(BkColors.Primary, Offset(l, y + 2), Size(6f, th - 4))
                                drawRect(BkColors.Primary, Offset(r - 7, y + 2), Size(6f, th - 4))
                            }
                        }
                        if (track.locked) drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, top), Size(size.width, th))
                        if (track.hidden || track.muted) drawRect(Color.Black.copy(alpha = 0.2f), Offset(0f, top), Size(size.width, th))
                    }
                    val px = xOf(playhead)
                    drawLine(BkColors.Accent, Offset(px, 0f), Offset(px, size.height), 2.5f)
                    drawCircle(BkColors.Accent, 7f, Offset(px, 8f))
                }
            }
        }
    }
}
