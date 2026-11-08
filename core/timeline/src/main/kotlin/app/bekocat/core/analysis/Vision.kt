package app.bekocat.core.analysis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Classical computer-vision algorithms on small luma frames (pure Kotlin, unit-tested):
 * scene-cut detection, template tracking (NCC with sub-pixel refinement), global motion
 * estimation for stabilisation, trajectory smoothing, and temporal background fill for
 * object removal with a static camera.
 */
class Gray(val w: Int, val h: Int, val px: FloatArray) {
    operator fun get(x: Int, y: Int) = px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    fun bilinear(x: Double, y: Double): Float {
        val x0 = x.toInt().coerceIn(0, w - 2); val y0 = y.toInt().coerceIn(0, h - 2)
        val fx = (x - x0).toFloat().coerceIn(0f, 1f); val fy = (y - y0).toFloat().coerceIn(0f, 1f)
        val a = this[x0, y0]; val b = this[x0 + 1, y0]; val c = this[x0, y0 + 1]; val d = this[x0 + 1, y0 + 1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }
}

object SceneDetect {
    /** 32-bin luma histogram. */
    fun histogram(g: Gray): FloatArray {
        val h = FloatArray(32)
        for (v in g.px) h[(v * 31.999f).toInt().coerceIn(0, 31)]++
        val n = g.px.size.toFloat(); for (i in h.indices) h[i] /= n
        return h
    }

    /**
     * Returns indices i where a cut occurs between frame i-1 and i, using an adaptive threshold
     * on the histogram chi-square distance combined with mean absolute pixel difference.
     */
    fun cuts(frames: List<Gray>, sensitivity: Double = 0.5, minGap: Int = 6): List<Int> {
        if (frames.size < 2) return emptyList()
        val hists = frames.map(::histogram)
        val d = DoubleArray(frames.size)
        for (i in 1 until frames.size) {
            var chi = 0.0
            for (k in 0 until 32) { val a = hists[i - 1][k]; val b = hists[i][k]; if (a + b > 0) chi += (a - b) * (a - b) / (a + b) }
            var mad = 0.0
            val p = frames[i - 1].px; val q = frames[i].px
            for (k in p.indices step 3) mad += abs(p[k] - q[k])
            mad /= (p.size / 3.0)
            d[i] = chi * 0.6 + mad * 2.0
        }
        val out = ArrayList<Int>()
        val win = 12
        for (i in 1 until frames.size) {
            val lo = max(1, i - win); val hi = min(frames.size - 1, i + win)
            val local = (lo..hi).filter { it != i }.map { d[it] }
            val mean = local.average(); val sd = sqrt(local.map { (it - mean) * (it - mean) }.average())
            val thr = mean + (4.5 - 3.0 * sensitivity) * sd + 0.08
            if (d[i] > thr && (out.isEmpty() || i - out.last() >= minGap)) out += i
        }
        return out
    }
}

object Tracker {
    data class Result(val x: Double, val y: Double, val confidence: Double)

    /**
     * Tracks a square template of half-size [r] (pixels) starting at (x0,y0) across frames with
     * normalised cross-correlation in a search window, parabolic sub-pixel refinement and
     * template update when confidence is high (drift-resistant).
     */
    fun track(frames: List<Gray>, x0: Double, y0: Double, r: Int, search: Int): List<Result> {
        val out = ArrayList<Result>()
        var tx = x0; var ty = y0
        var tmpl = patch(frames[0], tx, ty, r)
        out += Result(tx, ty, 1.0)
        for (i in 1 until frames.size) {
            val f = frames[i]
            var best = -2.0; var bx = 0; var by = 0
            val scores = HashMap<Long, Double>()
            for (dy in -search..search) for (dx in -search..search) {
                val s = ncc(tmpl, patch(f, tx + dx, ty + dy, r))
                scores[(dx.toLong() shl 32) or (dy.toLong() and 0xFFFFFFFFL)] = s
                if (s > best) { best = s; bx = dx; by = dy }
            }
            fun sc(dx: Int, dy: Int) = scores[(dx.toLong() shl 32) or (dy.toLong() and 0xFFFFFFFFL)] ?: best
            val sx = sub(sc(bx - 1, by), best, sc(bx + 1, by)); val sy = sub(sc(bx, by - 1), best, sc(bx, by + 1))
            tx += bx + sx; ty += by + sy
            out += Result(tx, ty, best.coerceIn(0.0, 1.0))
            if (best > 0.85) { val nt = patch(f, tx, ty, r); for (k in tmpl.indices) tmpl[k] = tmpl[k] * 0.7f + nt[k] * 0.3f }
        }
        return out
    }

    private fun sub(a: Double, b: Double, c: Double): Double { val d = a - 2 * b + c; return if (abs(d) < 1e-9) 0.0 else (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) }

    fun patch(g: Gray, cx: Double, cy: Double, r: Int): FloatArray {
        val n = 2 * r + 1
        return FloatArray(n * n) { i -> g.bilinear(cx + (i % n) - r, cy + (i / n) - r) }
    }

    fun ncc(a: FloatArray, b: FloatArray): Double {
        var ma = 0.0; var mb = 0.0
        for (i in a.indices) { ma += a[i]; mb += b[i] }
        ma /= a.size; mb /= b.size
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; num += x * y; da += x * x; db += y * y }
        return if (da < 1e-9 || db < 1e-9) 0.0 else num / sqrt(da * db)
    }
}

object Stabilizer {
    data class Motion(val dx: Double, val dy: Double, val rot: Double)

    /**
     * Global inter-frame motion from a grid of tracked blocks (median translation, rotation from
     * block displacements about the centre). Robust to a few moving objects.
     */
    fun motions(frames: List<Gray>): List<Motion> {
        val out = ArrayList<Motion>(); out += Motion(0.0, 0.0, 0.0)
        for (i in 1 until frames.size) {
            val a = frames[i - 1]; val b = frames[i]
            val pts = ArrayList<DoubleArray>()
            val gx = 6; val gy = 6; val r = max(3, min(a.w, a.h) / 24); val s = max(3, min(a.w, a.h) / 16)
            for (iy in 1..gy) for (ix in 1..gx) {
                val cx = a.w * ix / (gx + 1.0); val cy = a.h * iy / (gy + 1.0)
                val tm = Tracker.patch(a, cx, cy, r)
                var best = -2.0; var bx = 0; var by = 0
                for (dy in -s..s step 1) for (dx in -s..s step 1) { val sc = Tracker.ncc(tm, Tracker.patch(b, cx + dx, cy + dy, r)); if (sc > best) { best = sc; bx = dx; by = dy } }
                if (best > 0.6) pts += doubleArrayOf(cx, cy, bx.toDouble(), by.toDouble())
            }
            if (pts.size < 4) { out += Motion(0.0, 0.0, 0.0); continue }
            val mdx = pts.map { it[2] }.sorted()[pts.size / 2]; val mdy = pts.map { it[3] }.sorted()[pts.size / 2]
            val cx = a.w / 2.0; val cy = a.h / 2.0
            val rots = pts.mapNotNull { p ->
                val rx = p[0] - cx; val ry = p[1] - cy; val len = sqrt(rx * rx + ry * ry)
                if (len < a.w * 0.15) null else (atan2(ry + p[3] - mdy, rx + p[2] - mdx) - atan2(ry, rx))
            }.sorted()
            out += Motion(mdx, mdy, if (rots.isEmpty()) 0.0 else rots[rots.size / 2])
        }
        return out
    }

    /** Gaussian-smoothed cumulative trajectory; returns per-frame correction (smooth − raw). */
    fun corrections(m: List<Motion>, smoothness: Double): List<Motion> {
        val n = m.size
        val cx = DoubleArray(n); val cy = DoubleArray(n); val cr = DoubleArray(n)
        for (i in 1 until n) { cx[i] = cx[i - 1] + m[i].dx; cy[i] = cy[i - 1] + m[i].dy; cr[i] = cr[i - 1] + m[i].rot }
        val sigma = 2.0 + smoothness * 30.0
        val rad = (sigma * 3).toInt()
        fun smooth(a: DoubleArray) = DoubleArray(n) { i ->
            var s = 0.0; var w = 0.0
            for (k in max(0, i - rad)..min(n - 1, i + rad)) { val g = exp(-0.5 * (k - i) * (k - i) / (sigma * sigma)); s += a[k] * g; w += g }
            s / w
        }
        val sx = smooth(cx); val sy = smooth(cy); val sr = smooth(cr)
        return List(n) { Motion(sx[it] - cx[it], sy[it] - cy[it], sr[it] - cr[it]) }
    }

    /** Uniform scale needed so the corrected frame always covers the output (no black borders). */
    fun cropScale(c: List<Motion>, w: Int, h: Int): Double {
        val mx = c.maxOfOrNull { abs(it.dx) } ?: 0.0; val my = c.maxOfOrNull { abs(it.dy) } ?: 0.0
        val mr = c.maxOfOrNull { abs(it.rot) } ?: 0.0
        return max(1.0 + 2 * mx / w + mr, 1.0 + 2 * my / h + mr).coerceAtMost(1.6)
    }
}

object TemporalFill {
    /**
     * Removes a moving object inside [mask] (true = remove) by replacing each masked pixel with the
     * temporal median of that pixel over frames where it is not masked. Works when the camera is
     * static (or pre-stabilised) — the honest classical alternative to generative inpainting.
     * Returns new ARGB frames.
     */
    fun fill(frames: List<IntArray>, masks: List<BooleanArray>, radius: Int = 30): List<IntArray> {
        val n = frames.size
        return List(n) { i ->
            val out = frames[i].copyOf()
            val m = masks[i]
            for (p in out.indices) {
                if (!m[p]) continue
                val cand = ArrayList<Int>()
                for (k in max(0, i - radius)..min(n - 1, i + radius)) if (!masks[k][p]) cand += frames[k][p]
                if (cand.isEmpty()) continue
                val r = cand.map { (it shr 16) and 255 }.sorted()[cand.size / 2]
                val g = cand.map { (it shr 8) and 255 }.sorted()[cand.size / 2]
                val b = cand.map { it and 255 }.sorted()[cand.size / 2]
                out[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            out
        }
    }
}

object Reframe {
    /**
     * Smooth horizontal (and vertical) centre path for reframing from per-frame subject centroids
     * (from a matte), with a dead-zone and critically damped follow so the camera feels operated.
     */
    fun path(centroids: List<Pair<Double, Double>?>, deadZone: Double = 0.06, stiffness: Double = 0.15): List<Pair<Double, Double>> {
        var x = centroids.firstNotNullOfOrNull { it }?.first ?: 0.5
        var y = centroids.firstNotNullOfOrNull { it }?.second ?: 0.5
        var vx = 0.0; var vy = 0.0
        return centroids.map { c ->
            val tx = c?.first ?: x; val ty = c?.second ?: y
            val ex = if (abs(tx - x) > deadZone) tx - x - deadZone * kotlin.math.sign(tx - x) else 0.0
            val ey = if (abs(ty - y) > deadZone) ty - y - deadZone * kotlin.math.sign(ty - y) else 0.0
            vx = vx * (1 - 2 * sqrt(stiffness)) + ex * stiffness; vy = vy * (1 - 2 * sqrt(stiffness)) + ey * stiffness
            x += vx; y += vy
            x to y
        }
    }

    /** Centroid (0..1) of alpha mass above a threshold; null when nothing is detected. */
    fun centroid(alpha: FloatArray, w: Int, h: Int, threshold: Float = 0.5f): Pair<Double, Double>? {
        var sx = 0.0; var sy = 0.0; var n = 0.0
        for (y in 0 until h) for (x in 0 until w) { val a = alpha[y * w + x]; if (a > threshold) { sx += x * a; sy += y * a; n += a } }
        return if (n < w * h * 0.002) null else (sx / n / w) to (sy / n / h)
    }
}
