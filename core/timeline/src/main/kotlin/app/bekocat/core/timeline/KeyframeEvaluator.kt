package app.bekocat.core.timeline

import kotlin.math.abs

object KeyframeEvaluator {

    fun evaluate(a: Animatable, t: Time): Double {
        val ks = a.keyframes
        if (ks.isEmpty()) return a.base
        if (t <= ks.first().time) return ks.first().value
        if (t >= ks.last().time) return ks.last().value
        // Binary search for the segment [k0, k1) containing t.
        var lo = 0
        var hi = ks.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (ks[mid].time <= t) lo = mid else hi = mid
        }
        val k0 = ks[lo]
        val k1 = ks[hi]
        val span = (k1.time - k0.time).toDouble()
        val u = ((t - k0.time).toDouble() / span).coerceIn(0.0, 1.0)
        val eased = when (k0.interpolation) {
            Interpolation.HOLD -> 0.0
            Interpolation.LINEAR -> u
            Interpolation.BEZIER -> CubicBezier(k0.outX, k0.outY, k1.inX, k1.inY).solve(u)
        }
        return k0.value + (k1.value - k0.value) * eased
    }
}

/**
 * CSS-style cubic-bezier easing with P0=(0,0) and P3=(1,1).
 * x control points are clamped to [0,1] so the curve is a function of time.
 */
class CubicBezier(x1: Double, val y1: Double, x2: Double, val y2: Double) {
    private val x1 = x1.coerceIn(0.0, 1.0)
    private val x2 = x2.coerceIn(0.0, 1.0)

    private fun bx(s: Double) = 3 * (1 - s) * (1 - s) * s * x1 + 3 * (1 - s) * s * s * x2 + s * s * s
    private fun by(s: Double) = 3 * (1 - s) * (1 - s) * s * y1 + 3 * (1 - s) * s * s * y2 + s * s * s
    private fun dbx(s: Double) = 3 * (1 - s) * (1 - s) * x1 + 6 * (1 - s) * s * (x2 - x1) + 3 * s * s * (1 - x2)

    /** Returns eased progress y for time progress x in [0,1]. */
    fun solve(x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        var s = x
        repeat(8) {
            val err = bx(s) - x
            if (abs(err) < 1e-7) return by(s)
            val d = dbx(s)
            if (abs(d) < 1e-6) return@repeat
            s -= err / d
        }
        // Bisection fallback guarantees convergence.
        var lo = 0.0
        var hi = 1.0
        s = x
        repeat(60) {
            val v = bx(s)
            if (abs(v - x) < 1e-9) return by(s)
            if (v < x) lo = s else hi = s
            s = (lo + hi) / 2
        }
        return by(s)
    }

    companion object {
        val EASE = CubicBezier(0.25, 0.1, 0.25, 1.0)
        val EASE_IN = CubicBezier(0.42, 0.0, 1.0, 1.0)
        val EASE_OUT = CubicBezier(0.0, 0.0, 0.58, 1.0)
        val EASY_EASE = CubicBezier(0.33, 0.0, 0.67, 1.0)
    }
}
