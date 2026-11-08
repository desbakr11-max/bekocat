package app.bekocat.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class VisionTest {
    private val W = 96; private val H = 64

    /** Textured background (smooth noise) shifted by (ox, oy), with an optional bright square. */
    private fun frame(ox: Double, oy: Double, seed: Int = 1, square: Pair<Int, Int>? = null): Gray {
        val px = FloatArray(W * H) { i ->
            val x = i % W + ox; val y = i / W + oy
            (0.5 + 0.2 * sin(x * 0.37 + seed) * cos(y * 0.29) + 0.15 * sin((x + y) * 0.13 + seed * 2)).toFloat()
        }
        square?.let { (sx, sy) -> for (y in sy until sy + 8) for (x in sx until sx + 8) if (x in 0 until W && y in 0 until H) px[y * W + x] = 1f }
        return Gray(W, H, px)
    }

    @Test fun detectsHardCuts() {
        val frames = (0 until 30).map { frame(it * 0.3, 0.0, seed = if (it < 15) 1 else 9) }
        assertEquals(listOf(15), SceneDetect.cuts(frames))
    }

    @Test fun tracksMovingSquare() {
        val frames = (0 until 20).map { i -> frame(0.0, 0.0, square = (20 + i * 2) to (20 + i)) }
        val r = Tracker.track(frames, 24.0, 24.0, 6, 5)
        assertEquals(24.0 + 19 * 2, r.last().x, 1.0)
        assertEquals(24.0 + 19, r.last().y, 1.0)
    }

    @Test fun stabilizationCancelsJitter() {
        val rnd = Random(4)
        val offs = (0 until 40).map { (rnd.nextDouble() - 0.5) * 4 to (rnd.nextDouble() - 0.5) * 4 }
        val frames = offs.map { (x, y) -> frame(x, y) }
        val m = Stabilizer.motions(frames)
        // Measured inter-frame motion equals the true offset delta (frame content moves opposite to ox).
        for (i in 1 until 40) assertEquals(-(offs[i].first - offs[i - 1].first), m[i].dx, 1.01)
        val c = Stabilizer.corrections(m, 0.6)
        val residual = (1 until 40).map { i -> var cx = 0.0; for (k in 1..i) cx += m[k].dx; abs(cx + c[i].dx) }
        val raw = (1 until 40).map { i -> var cx = 0.0; for (k in 1..i) cx += m[k].dx; abs(cx - (1 until 40).map { j -> var s = 0.0; for (k in 1..j) s += m[k].dx; s }.average()) }
        assertTrue(residual.average() < raw.average() * 1.0 + 1.0)
        assertTrue(Stabilizer.cropScale(c, W, H) >= 1.0)
    }

    @Test fun temporalFillRemovesMovingObject() {
        val n = 12
        val frames = (0 until n).map { i -> IntArray(W * H) { 0xFF336699.toInt() }.also { a -> for (y in 10 until 18) for (x in 5 + i * 6 until 13 + i * 6) if (x < W) a[y * W + x] = 0xFFFF0000.toInt() } }
        val masks = (0 until n).map { i -> BooleanArray(W * H) { p -> val x = p % W; val y = p / W; y in 10 until 18 && x in 5 + i * 6 until 13 + i * 6 } }
        val out = TemporalFill.fill(frames, masks)
        assertTrue(out.all { f -> f.none { it == 0xFFFF0000.toInt() } })
        assertEquals(0xFF336699.toInt(), out[5][12 * W + 40])
    }

    @Test fun reframeFollowsSubjectSmoothly() {
        val c = (0 until 60).map { i -> (if (i < 30) 0.3 else 0.7) to 0.5 }
        val p = Reframe.path(c)
        assertTrue(abs(p[29].first - 0.3) < 0.08)
        assertTrue(abs(p[59].first - 0.7) < 0.08)
        assertTrue(p.zipWithNext { a, b -> abs(b.first - a.first) }.max() < 0.08) // no jumps
        val alpha = FloatArray(100) { if (it % 10 >= 6) 1f else 0f }
        assertEquals(0.75, Reframe.centroid(alpha, 10, 10)!!.first, 0.06)
    }
}
