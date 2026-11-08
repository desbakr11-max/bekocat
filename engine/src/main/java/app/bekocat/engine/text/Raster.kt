package app.bekocat.engine.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import app.bekocat.core.timeline.CaptionFrame
import app.bekocat.core.timeline.MaskFrame
import app.bekocat.core.timeline.MaskMode
import app.bekocat.core.timeline.MaskShape
import app.bekocat.core.timeline.MaskVertex
import app.bekocat.core.timeline.ShapeFrame
import app.bekocat.core.timeline.ShapeType
import app.bekocat.core.timeline.TextStyle
import app.bekocat.core.timeline.UnitState
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun argb(v: Long) = v.toInt()

/** Resolves font families: built-ins and imported TTF/OTF assets (cached). */
class FontResolver(private val context: Context) {
    private val cache = HashMap<String, Typeface>()
    var assetUri: (String) -> String? = { null }

    fun resolve(family: String, weight: Int, italic: Boolean): Typeface {
        val base = cache.getOrPut(family) {
            when (family) {
                "default", "" -> Typeface.DEFAULT
                "serif" -> Typeface.SERIF
                "mono" -> Typeface.MONOSPACE
                else -> runCatching {
                    val uri = assetUri(family) ?: return@runCatching Typeface.DEFAULT
                    val f = File(context.cacheDir, "fonts/${family.hashCode()}.ttf")
                    if (!f.exists()) { f.parentFile?.mkdirs(); context.contentResolver.openInputStream(Uri.parse(uri))?.use { i -> f.outputStream().use { i.copyTo(it) } } }
                    Typeface.createFromFile(f)
                }.getOrDefault(Typeface.DEFAULT)
            }
        }
        return Typeface.create(base, weight.coerceIn(100, 1000), italic)
    }
}

/**
 * Renders a text layer with per-unit animation states into a bitmap.
 * Shaping and bidi come from Android's StaticLayout (HarfBuzz/ICU), so Arabic joins,
 * diacritics and mixed Arabic/English stay correct. Animated units are drawn by
 * clipping the full shaped line to each unit's selection region — glyphs are never
 * re-shaped individually, so joining forms are preserved during animation.
 */
class TextRenderer(private val fonts: FontResolver) {

    data class Result(val bitmap: Bitmap, val anchorOffsetX: Float, val anchorOffsetY: Float)

    fun render(style: TextStyle, text: String, states: List<UnitState>?, outW: Int, outH: Int,
               activeColors: Map<IntRange, Int> = emptyMap(), pills: Map<IntRange, Int> = emptyMap(), scaleUnits: Map<IntRange, Float> = emptyMap()): Result? {
        if (text.isBlank()) return null
        val scale = min(outW, outH) / 1080f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fonts.resolve(style.fontFamily, style.weight, style.italic)
            textSize = (style.sizeSp * scale).toFloat().coerceAtLeast(4f)
            letterSpacing = style.letterSpacing.toFloat()
            color = argb(style.colorArgb)
        }
        style.path?.takeIf { it.size >= 4 }?.let { return renderOnPath(style, text, states, outW, outH, paint, scale, it) }
        val maxW = (style.boxWidth * outW).toInt().coerceAtLeast(16)
        val dir = when (style.rtl) { true -> TextDirectionHeuristics.RTL; false -> TextDirectionHeuristics.LTR; null -> TextDirectionHeuristics.FIRSTSTRONG_LTR }
        val align = when (style.alignment) { "start" -> Layout.Alignment.ALIGN_NORMAL; "end" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER }
        // Measure natural width so short text gets a tight box.
        val natural = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxW).setTextDirection(dir).setAlignment(align)
            .setLineSpacing(0f, style.lineHeight.toFloat()).setIncludePad(true).build()
        var w = 1
        for (i in 0 until natural.lineCount) w = max(w, kotlin.math.ceil(natural.getLineWidth(i).toDouble()).toInt())
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w).setTextDirection(dir).setAlignment(align)
            .setLineSpacing(0f, style.lineHeight.toFloat()).setIncludePad(true).build()
        // layout.paint IS `paint`; keep an untouched copy to restore after each styled pass.
        val pristine = TextPaint(paint)

        val pad = (style.backgroundPadding * scale + style.strokeWidth * scale + style.shadowBlur * scale + style.glowRadius * scale +
                (abs(style.shadowDx) + abs(style.shadowDy)) * scale + paint.textSize * 0.6f).toInt() + 4
        val bw = layout.width + pad * 2; val bh = layout.height + pad * 2
        if (bw <= 0 || bh <= 0 || bw * bh > 8192 * 4096) return null
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(pad.toFloat(), pad.toFloat())

        val unitPaths = HashMap<Int, Path>()
        fun regionPath(s: Int, e: Int): Path = unitPaths.getOrPut(s * 100_000 + e) {
            val p = Path(); layout.getSelectionPath(s, e, p)
            // Extend vertically to full line box so ascenders/diacritics and stroke are not clipped.
            val b = RectF(); p.computeBounds(b, true)
            val grow = paint.textSize * 0.35f + (style.strokeWidth * scale).toFloat()
            Path().apply { addRect(b.left - grow * 0.15f, b.top - grow, b.right + grow * 0.15f, b.bottom + grow, Path.Direction.CW) }
        }

        // Backgrounds per line.
        if ((style.backgroundArgb ushr 24) != 0L) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(style.backgroundArgb) }
            val bp = (style.backgroundPadding * scale).toFloat(); val r = (style.backgroundRadius * scale).toFloat()
            for (i in 0 until layout.lineCount) {
                val l = layout.getLineLeft(i); val rr = layout.getLineRight(i)
                if (rr - l < 1f) continue
                c.drawRoundRect(RectF(l - bp, layout.getLineTop(i) - bp * 0.4f, rr + bp, layout.getLineBottom(i) + bp * 0.4f), r, r, bg)
            }
        }
        for ((range, col) in pills) {
            val pp = Path(); layout.getSelectionPath(range.first, range.last + 1, pp)
            val b = RectF(); pp.computeBounds(b, true)
            val g = paint.textSize * 0.18f
            c.drawRoundRect(RectF(b.left - g, b.top + g * 0.3f, b.right + g, b.bottom - g * 0.3f), paint.textSize * 0.3f, paint.textSize * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        }

        val drawFull: (Canvas, Float) -> Unit = { cv, alpha -> drawStyled(cv, layout, pristine, style, scale, alpha) }
        val animated = states != null && states.any { it.alpha != 1.0 || it.dx != 0.0 || it.dy != 0.0 || it.scale != 1.0 || it.rotation != 0.0 || it.blur != 0.0 || !it.visible || it.reveal < 1.0 || it.highlight > 0.0 || it.tracking != 0.0 }
        if (!animated && scaleUnits.isEmpty()) {
            drawFull(c, 1f)
        } else {
            val list = states ?: emptyList()
            for (st in list) {
                if (!st.visible || st.alpha <= 0.001) continue
                val region = regionPath(st.start, st.end)
                val b = RectF(); region.computeBounds(b, true)
                c.save()
                val em = paint.textSize
                c.translate((st.dx * em).toFloat(), (st.dy * em).toFloat())
                val unitScale = (st.scale * (scaleUnits.entries.firstOrNull { st.start in it.key }?.value ?: 1f)).toFloat()
                c.rotate(st.rotation.toFloat(), b.centerX(), b.centerY())
                c.scale(unitScale, unitScale, b.centerX(), b.bottom - (b.height() * 0.3f))
                if (st.tracking != 0.0) {
                    val mid = layout.width / 2f
                    c.translate(((b.centerX() - mid) * st.tracking).toFloat(), 0f)
                }
                c.clipPath(region)
                if (st.reveal < 1.0) {
                    val r = st.reveal.toFloat()
                    val rtlLine = layout.getParagraphDirection(layout.getLineForOffset(st.start)) == Layout.DIR_RIGHT_TO_LEFT
                    when (st.revealDir) {
                        "up" -> c.clipRect(b.left, b.bottom - b.height() * r, b.right, b.bottom)
                        "down" -> c.clipRect(b.left, b.top, b.right, b.top + b.height() * r)
                        else -> if (rtlLine) c.clipRect(b.right - b.width() * r, b.top, b.right, b.bottom) else c.clipRect(b.left, b.top, b.left + b.width() * r, b.bottom)
                    }
                }
                if (st.blur > 0.3) {
                    val tmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    val tc = Canvas(tmp); tc.translate(pad.toFloat(), pad.toFloat())
                    drawStyled(tc, layout, pristine, style, scale, st.alpha.toFloat())
                    c.translate(-pad.toFloat(), -pad.toFloat())
                    c.drawBitmap(tmp, 0f, 0f, Paint().apply { maskFilter = BlurMaskFilter((st.blur * scale).toFloat().coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL) })
                    tmp.recycle()
                } else drawFull(c, st.alpha.toFloat())
                c.restore()
                if (st.highlight > 0.0) {
                    c.save()
                    c.translate((st.dx * em).toFloat(), (st.dy * em).toFloat())
                    val rtlLine = layout.getParagraphDirection(layout.getLineForOffset(st.start)) == Layout.DIR_RIGHT_TO_LEFT
                    val f = st.highlight.toFloat()
                    c.clipPath(region)
                    if (rtlLine) c.clipRect(b.right - b.width() * f, b.top, b.right, b.bottom) else c.clipRect(b.left, b.top, b.left + b.width() * f, b.bottom)
                    val hp = TextPaint(pristine).apply { color = argb(style.highlightArgb); shader = null }
                    layout.paint.set(hp)
                    layout.draw(c)
                    layout.paint.set(pristine)
                    c.restore()
                }
            }
        }
        // Active-word colour overlays (captions).
        for ((range, col) in activeColors) {
            val region = regionPath(range.first, range.last + 1)
            val b = RectF(); region.computeBounds(b, true)
            val sc = scaleUnits.entries.firstOrNull { range.first in it.key }?.value ?: 1f
            c.save(); c.scale(sc, sc, b.centerX(), b.bottom - b.height() * 0.3f); c.clipPath(region)
            val hp = TextPaint(pristine).apply { color = col; shader = null }
            if (style.strokeWidth > 0) {
                val sp = TextPaint(pristine).apply { this.style = Paint.Style.STROKE; strokeWidth = (style.strokeWidth * scale).toFloat(); color = argb(style.strokeArgb); strokeJoin = Paint.Join.ROUND; shader = null }
                layout.paint.set(sp); layout.draw(c)
            }
            layout.paint.set(hp); layout.draw(c); layout.paint.set(pristine)
            c.restore()
        }
        // Words highlighted statically by index.
        if (style.highlightWords.isNotEmpty()) {
            val words = Regex("\\S+").findAll(text).map { it.range }.toList()
            for (wi in style.highlightWords) words.getOrNull(wi)?.let { r ->
                c.save(); c.clipPath(regionPath(r.first, r.last + 1))
                layout.paint.set(TextPaint(pristine).apply { color = argb(style.highlightArgb); shader = null }); layout.draw(c); layout.paint.set(pristine)
                c.restore()
            }
        }
        return Result(bmp, 0f, 0f)
    }

    private fun abs(v: Double) = kotlin.math.abs(v)

    /**
     * Text on a path. Points are (x, y) offsets from the layer centre in output-frame units
     * (x × width, y × height). Canvas.drawTextOnPath shapes the whole run (bidi + Arabic joining)
     * and bends it along a smoothed curve; per-unit animation collapses to the overall opacity/reveal.
     */
    private fun renderOnPath(style: TextStyle, text: String, states: List<UnitState>?, outW: Int, outH: Int, paint: TextPaint, scale: Float, pts: List<Double>): Result? {
        val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }.map { (it * outW).toFloat() }
        val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }.map { (it * outH).toFloat() }
        val n = minOf(xs.size, ys.size); if (n < 2) return null
        val pad = (paint.textSize * 1.6f + style.strokeWidth * scale + style.shadowBlur * scale + style.glowRadius * scale + (abs(style.shadowDx) + abs(style.shadowDy)) * scale).toInt() + 4
        val hx = (0 until n).maxOf { kotlin.math.abs(xs[it]) }.toInt() + pad
        val hy = (0 until n).maxOf { kotlin.math.abs(ys[it]) }.toInt() + pad
        if (hx * 2 * hy * 2 > 8192 * 4096) return null
        val bmp = Bitmap.createBitmap(hx * 2, hy * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.translate(hx.toFloat(), hy.toFloat())
        val path = Path().apply {
            moveTo(xs[0], ys[0])
            if (n == 2) lineTo(xs[1], ys[1])
            else { for (i in 1 until n - 1) quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2, (ys[i] + ys[i + 1]) / 2); lineTo(xs[n - 1], ys[n - 1]) }
        }
        val len = android.graphics.PathMeasure(path, false).length
        val tw = paint.measureText(text)
        val h0 = when (style.alignment) { "start" -> 0f; "end" -> len - tw; else -> (len - tw) / 2 }.coerceAtLeast(0f)
        val visible = states?.filter { it.visible }
        val alpha = if (states == null) 1f else (visible?.maxOfOrNull { it.alpha } ?: 0.0).toFloat()
        val reveal = if (states == null) 1f else (visible?.count { it.reveal >= 1.0 && it.alpha > 0.01 } ?: 0).toFloat() / states.size.coerceAtLeast(1)
        if (alpha <= 0.001f) return Result(bmp, 0f, 0f)
        val a = (alpha * 255).toInt()
        val vo = paint.textSize * 0.35f // baseline sits slightly below the curve so glyphs centre on it
        fun draw(p: Paint) {
            if (reveal < 1f) { c.save(); val clip = Path(); android.graphics.PathMeasure(path, false).getSegment(0f, h0 + tw * reveal, clip, true)
                val b = RectF(); clip.computeBounds(b, true); val g = paint.textSize * 1.5f; c.clipRect(b.left - g, b.top - g, b.right + g, b.bottom + g) }
            c.drawTextOnPath(text, path, h0, vo, p)
            if (reveal < 1f) c.restore()
        }
        if (style.glowRadius > 0 && (style.glowArgb ushr 24) != 0L) draw(TextPaint(paint).apply { color = argb(style.glowArgb); maskFilter = BlurMaskFilter((style.glowRadius * scale).toFloat(), BlurMaskFilter.Blur.NORMAL); this.alpha = this.alpha * a / 255 })
        if ((style.shadowArgb ushr 24) != 0L) { c.save(); c.translate((style.shadowDx * scale).toFloat(), (style.shadowDy * scale).toFloat())
            draw(TextPaint(paint).apply { color = argb(style.shadowArgb); if (style.shadowBlur > 0) maskFilter = BlurMaskFilter((style.shadowBlur * scale).toFloat(), BlurMaskFilter.Blur.NORMAL); this.alpha = this.alpha * a / 255 }); c.restore() }
        if (style.strokeWidth > 0 && (style.strokeArgb ushr 24) != 0L) draw(TextPaint(paint).apply { this.style = Paint.Style.STROKE; strokeWidth = (style.strokeWidth * scale * 2).toFloat(); strokeJoin = Paint.Join.ROUND; color = argb(style.strokeArgb); this.alpha = this.alpha * a / 255 })
        draw(TextPaint(paint).apply { if (style.gradientArgb != null) shader = LinearGradient(0f, -hy.toFloat(), 0f, hy.toFloat(), argb(style.colorArgb), argb(style.gradientArgb!!), Shader.TileMode.CLAMP); this.alpha = this.alpha * a / 255 })
        return Result(bmp, 0f, 0f)
    }

    private fun drawStyled(c: Canvas, layout: StaticLayout, base: TextPaint, s: TextStyle, scale: Float, alpha: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        fun withAlpha(p: TextPaint) { p.alpha = (p.alpha * a / 255) }
        if (s.glowRadius > 0 && (s.glowArgb ushr 24) != 0L) {
            val gp = TextPaint(base).apply { color = argb(s.glowArgb); maskFilter = BlurMaskFilter((s.glowRadius * scale).toFloat(), BlurMaskFilter.Blur.NORMAL); shader = null }
            withAlpha(gp); layout.paint.set(gp); layout.draw(c); layout.draw(c)
        }
        if ((s.shadowArgb ushr 24) != 0L) {
            val sp = TextPaint(base).apply { color = argb(s.shadowArgb); shader = null
                if (s.shadowBlur > 0) maskFilter = BlurMaskFilter((s.shadowBlur * scale).toFloat(), BlurMaskFilter.Blur.NORMAL) }
            withAlpha(sp)
            c.save(); c.translate((s.shadowDx * scale).toFloat(), (s.shadowDy * scale).toFloat()); layout.paint.set(sp); layout.draw(c); c.restore()
        }
        if (s.strokeWidth > 0 && (s.strokeArgb ushr 24) != 0L) {
            val sp = TextPaint(base).apply { style = Paint.Style.STROKE; strokeWidth = (s.strokeWidth * scale * 2).toFloat(); strokeJoin = Paint.Join.ROUND; color = argb(s.strokeArgb); shader = null }
            withAlpha(sp); layout.paint.set(sp); layout.draw(c)
        }
        val fp = TextPaint(base)
        if (s.gradientArgb != null) fp.shader = LinearGradient(0f, 0f, 0f, layout.height.toFloat(), argb(s.colorArgb), argb(s.gradientArgb!!), Shader.TileMode.CLAMP)
        withAlpha(fp)
        layout.paint.set(fp); layout.draw(c); layout.paint.set(base)
    }

    /** Captions: builds text + active-word styling from a CaptionFrame and renders it. */
    fun renderCaption(f: CaptionFrame, outW: Int, outH: Int): Result? {
        val sb = StringBuilder()
        val ranges = ArrayList<Pair<IntRange, app.bekocat.core.timeline.CaptionWordFrame>>()
        f.lines.forEachIndexed { li, line ->
            if (li > 0) sb.append('\n')
            line.forEachIndexed { wi, w ->
                if (wi > 0) sb.append(' ')
                val s = sb.length; sb.append(w.text); ranges += (s until sb.length) to w
            }
        }
        if (f.translation != null) sb.append('\n').append(f.translation)
        val text = sb.toString()
        val p = f.preset
        val active = HashMap<IntRange, Int>(); val pills = HashMap<IntRange, Int>(); val scales = HashMap<IntRange, Float>()
        val states = ArrayList<UnitState>()
        for ((r, w) in ranges) {
            val speakerCol = f.speakerColors.getOrNull(w.speaker)?.toInt()
            if (w.active) {
                p.activeColorArgb?.let { active[r] = it.toInt() }
                p.activePillArgb?.let { pills[r] = it.toInt() }
                if (p.activeScale != 1.0) scales[r] = p.activeScale.toFloat()
            } else if (speakerCol != null && w.speaker > 0) active[r] = speakerCol
            var st = UnitState(r.first, r.last + 1)
            if (p.karaoke) st = st.copy(highlight = if (w.past) 1.0 else if (w.active) w.progress else 0.0)
            if (p.animIn != null && w.active) {
                val k = (w.progress * 4).coerceAtMost(1.0)
                st = when (p.animIn) {
                    "pop", "kinetic-punch" -> st.copy(scale = 0.7 + 0.3 * app.bekocat.core.timeline.Easing.apply("back", k))
                    "bounce" -> st.copy(dy = -0.3 * (1 - app.bekocat.core.timeline.Easing.apply("bounce", k)))
                    "typewriter" -> st
                    else -> st
                }
            }
            states += st
        }
        val style = if (p.karaoke) f.style.copy(highlightArgb = p.activeColorArgb ?: 0xFFFFFFFF) else f.style
        return render(style, text, states, outW, outH, active, pills, scales)
    }
}

/** Renders shape layers (rect/ellipse/star/polygon/line/path) with trim paths, dashes, gradients and repeater. */
object ShapeRenderer {
    fun path(f: ShapeFrame, w: Float, h: Float): Path {
        val s = f.style
        val p = Path()
        when (s.type) {
            ShapeType.RECT -> { val r = (f.cornerRadius * min(w, h)).toFloat(); p.addRoundRect(RectF(-w / 2, -h / 2, w / 2, h / 2), r, r, Path.Direction.CW) }
            ShapeType.ELLIPSE -> p.addOval(RectF(-w / 2, -h / 2, w / 2, h / 2), Path.Direction.CW)
            ShapeType.STAR, ShapeType.POLYGON -> {
                val n = s.points.coerceIn(3, 64)
                val steps = if (s.type == ShapeType.STAR) n * 2 else n
                for (i in 0 until steps) {
                    val ang = -PI / 2 + 2 * PI * i / steps
                    val rr = if (s.type == ShapeType.STAR && i % 2 == 1) s.innerRadius else 1.0
                    val x = (cos(ang) * rr * w / 2).toFloat(); val y = (sin(ang) * rr * h / 2).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            ShapeType.LINE -> { p.moveTo(-w / 2, 0f); p.lineTo(w / 2, 0f) }
            ShapeType.PATH -> bezierPath(f.vertices, w, h, closed = true, into = p)
        }
        return p
    }

    fun bezierPath(v: List<MaskVertex>, w: Float, h: Float, closed: Boolean, into: Path = Path(), originCenter: Boolean = true): Path {
        if (v.isEmpty()) return into
        val ox = if (originCenter) -w / 2 else 0f; val oy = if (originCenter) -h / 2 else 0f
        fun X(x: Double) = (x * w).toFloat() + ox
        fun Y(y: Double) = (y * h).toFloat() + oy
        into.moveTo(X(v[0].x), Y(v[0].y))
        val n = if (closed) v.size else v.size - 1
        for (i in 0 until n) {
            val a = v[i]; val b = v[(i + 1) % v.size]
            if (a.outX == 0.0 && a.outY == 0.0 && b.inX == 0.0 && b.inY == 0.0) into.lineTo(X(b.x), Y(b.y))
            else into.cubicTo(X(a.x + a.outX), Y(a.y + a.outY), X(b.x + b.inX), Y(b.y + b.inY), X(b.x), Y(b.y))
        }
        if (closed) into.close()
        return into
    }

    fun render(f: ShapeFrame, outW: Int, outH: Int): Bitmap? {
        val s = f.style
        val w = (f.width * outW).toFloat().coerceAtLeast(1f); val h = (f.height * outH).toFloat().coerceAtLeast(1f)
        val stroke = (f.strokeWidth * min(outW, outH) / 1080.0).toFloat()
        var path = path(f, w, h)
        if (f.trimStart > 0.0 || f.trimEnd < 1.0 || f.trimOffset != 0.0) {
            val pm = PathMeasure(path, s.type != ShapeType.LINE)
            val len = pm.length
            val trimmed = Path()
            var a = ((f.trimStart + f.trimOffset) % 1.0 + 1.0) % 1.0; var b = a + (f.trimEnd - f.trimStart).coerceAtLeast(0.0)
            if (b > 1.0) { pm.getSegment((a * len).toFloat(), len, trimmed, true); pm.getSegment(0f, ((b - 1.0) * len).toFloat(), trimmed, true) }
            else pm.getSegment((a * len).toFloat(), (b * len).toFloat(), trimmed, true)
            path = trimmed
        }
        val reps = s.repeatCount.coerceIn(1, 64)
        val extentX = w + stroke * 2 + kotlin.math.abs(s.repeatOffsetX * outW).toFloat() * reps
        val extentY = h + stroke * 2 + kotlin.math.abs(s.repeatOffsetY * outH).toFloat() * reps
        val side = max(extentX, extentY) * (if (s.repeatRotation != 0.0 || s.repeatScale != 1.0) 1.6f else 1f)
        val bw = (side + 8).toInt().coerceAtMost(4096); val bh = bw
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = argb(s.fillArgb)
            s.fillGradientArgb?.let { g ->
                val ang = s.fillGradientAngle * PI / 180
                shader = LinearGradient((-cos(ang) * w / 2).toFloat(), (-sin(ang) * h / 2).toFloat(), (cos(ang) * w / 2).toFloat(), (sin(ang) * h / 2).toFloat(), argb(s.fillArgb), argb(g), Shader.TileMode.CLAMP)
            }
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = argb(s.strokeArgb); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            if (s.dash.size >= 2) pathEffect = DashPathEffect(s.dash.map { (it * min(outW, outH) / 1080.0).toFloat() }.toFloatArray(), 0f)
        }
        val trimmedOnly = f.trimStart > 0.0 || f.trimEnd < 1.0
        for (i in 0 until reps) {
            c.save()
            c.translate(bw / 2f + (s.repeatOffsetX * outW * i).toFloat() - (s.repeatOffsetX * outW * (reps - 1) / 2).toFloat(),
                bh / 2f + (s.repeatOffsetY * outH * i).toFloat() - (s.repeatOffsetY * outH * (reps - 1) / 2).toFloat())
            c.rotate((s.repeatRotation * i).toFloat())
            val sc = Math.pow(s.repeatScale, i.toDouble()).toFloat(); c.scale(sc, sc)
            val op = if (reps > 1) (1.0 + (s.repeatOpacityEnd - 1.0) * i / (reps - 1)).toFloat() else 1f
            fill.alpha = ((fill.color ushr 24) * op).toInt().coerceIn(0, 255)
            strokePaint.alpha = ((strokePaint.color ushr 24) * op).toInt().coerceIn(0, 255)
            if (s.type != ShapeType.LINE && !trimmedOnly && (s.fillArgb ushr 24) != 0L) c.drawPath(path, fill)
            if (stroke > 0 && (s.strokeArgb ushr 24) != 0L) c.drawPath(path, strokePaint)
            c.restore()
        }
        return bmp
    }
}

/** Rasterises a layer's masks (in layer UV space) to an alpha bitmap; feather via blur, expansion via stroke. */
object MaskRasterizer {
    fun render(masks: List<MaskFrame>, contentW: Int, contentH: Int): Bitmap? {
        if (masks.isEmpty()) return null
        val k = 1024f / max(contentW, contentH).coerceAtLeast(1)
        val w = (contentW * min(1f, k)).toInt().coerceAtLeast(16); val h = (contentH * min(1f, k)).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val first = masks.first()
        if (first.mode != MaskMode.ADD || first.inverted) c.drawColor(if (first.mode == MaskMode.ADD && first.inverted) Color.WHITE else if (first.mode == MaskMode.ADD) Color.TRANSPARENT else Color.WHITE)
        for (m in masks) {
            val p = when (m.shape) {
                MaskShape.RECT, MaskShape.ELLIPSE -> Path().apply {
                    val r = RectF(((m.cx - m.w / 2) * w).toFloat(), ((m.cy - m.h / 2) * h).toFloat(), ((m.cx + m.w / 2) * w).toFloat(), ((m.cy + m.h / 2) * h).toFloat())
                    if (m.shape == MaskShape.RECT) addRect(r, Path.Direction.CW) else addOval(r, Path.Direction.CW)
                    if (m.rotation != 0.0) transform(Matrix().apply { postRotate(m.rotation.toFloat(), r.centerX(), r.centerY()) })
                }
                MaskShape.POLYGON, MaskShape.BEZIER -> ShapeRenderer.bezierPath(m.vertices, w.toFloat(), h.toFloat(), closed = true, originCenter = false)
            }
            val feather = (m.feather * min(w, h)).toFloat()
            val exp = (m.expansion * min(w, h)).toFloat()
            val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layer)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = (m.opacity * 255).toInt().coerceIn(0, 255) }
            if (exp > 0) { paint.style = Paint.Style.FILL_AND_STROKE; paint.strokeWidth = exp * 2; paint.strokeJoin = Paint.Join.ROUND }
            if (feather > 0.5f) paint.maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
            lc.drawPath(p, paint)
            if (exp < 0) lc.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = -exp * 2; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
            if (m.inverted) { val inv = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val ic = Canvas(inv); ic.drawColor(Color.WHITE)
                ic.drawBitmap(layer, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }); layer.recycle()
                drawMode(c, inv, m.mode); inv.recycle() }
            else { drawMode(c, layer, m.mode); layer.recycle() }
        }
        return bmp
    }

    private fun drawMode(c: Canvas, layer: Bitmap, mode: MaskMode) {
        val p = Paint().apply {
            xfermode = PorterDuffXfermode(when (mode) { MaskMode.ADD -> PorterDuff.Mode.SRC_OVER; MaskMode.SUBTRACT -> PorterDuff.Mode.DST_OUT; MaskMode.INTERSECT -> PorterDuff.Mode.DST_IN })
        }
        c.drawBitmap(layer, 0f, 0f, p)
    }
}
