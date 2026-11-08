package app.bekocat.core.timeline

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Named easing curves used by text presets, transitions and motion presets. All map [0,1] → ~[0,1]. */
object Easing {
    fun apply(name: String, x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return when (name) {
            "linear" -> t
            "in" -> t * t * t
            "out" -> 1 - (1 - t).pow(3)
            "inout", "easy" -> CubicBezier.EASY_EASE.solve(t)
            "expo" -> if (t >= 1.0) 1.0 else 1 - 2.0.pow(-10 * t)
            "back" -> backOut(t, 1.70158)
            "overshoot" -> backOut(t, 2.6)
            "elastic" -> elasticOut(t)
            "bounce" -> bounceOut(t)
            "spring" -> spring(t, 0.45, 14.0)
            "spring-soft" -> spring(t, 0.7, 9.0)
            "hold" -> if (t >= 1.0) 1.0 else 0.0
            else -> if (name.startsWith("cubic(")) {
                val v = name.removePrefix("cubic(").removeSuffix(")").split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (v.size == 4) CubicBezier(v[0], v[1], v[2], v[3]).solve(t) else t
            } else CubicBezier.EASY_EASE.solve(t)
        }
    }

    fun bezier(c: List<Double>, x: Double): Double =
        if (c.size == 4) CubicBezier(c[0], c[1], c[2], c[3]).solve(x.coerceIn(0.0, 1.0)) else x

    fun backOut(t: Double, s: Double): Double { val u = t - 1; return u * u * ((s + 1) * u + s) + 1 }

    fun elasticOut(t: Double): Double {
        if (t <= 0.0) return 0.0
        if (t >= 1.0) return 1.0
        val p = 0.3
        return 2.0.pow(-10 * t) * sin((t - p / 4) * (2 * PI) / p) + 1
    }

    fun bounceOut(t: Double): Double {
        val n1 = 7.5625; val d1 = 2.75
        return when {
            t < 1 / d1 -> n1 * t * t
            t < 2 / d1 -> { val u = t - 1.5 / d1; n1 * u * u + 0.75 }
            t < 2.5 / d1 -> { val u = t - 2.25 / d1; n1 * u * u + 0.9375 }
            else -> { val u = t - 2.625 / d1; n1 * u * u + 0.984375 }
        }
    }

    /** Damped harmonic oscillator step response normalised to settle at 1 at t = 1. */
    fun spring(t: Double, damping: Double, stiffness: Double): Double {
        if (t >= 1.0) return 1.0
        val w = stiffness
        val z = damping.coerceIn(0.05, 0.99)
        val wd = w * sqrt(1 - z * z)
        val e = exp(-z * w * t)
        return 1 - e * (cos(wd * t) + (z * w / wd) * sin(wd * t))
    }
}
