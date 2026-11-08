package app.bekocat.core.timeline

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.abs

/** Serialises a [Rational] as the exact string "num/den". */
object RationalSerializer : KSerializer<Rational> {
    override val descriptor = PrimitiveSerialDescriptor("bekocat.Rational", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Rational) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Rational = Rational.parse(decoder.decodeString())
}

object FrameRateSerializer : KSerializer<FrameRate> {
    override val descriptor = PrimitiveSerialDescriptor("bekocat.FrameRate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: FrameRate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): FrameRate = FrameRate.parse(decoder.decodeString())
}

/**
 * Exact rational number used for every timeline time, duration and speed.
 * Floats are never the source of truth: 23.976 fps is 24000/1001 exactly,
 * so frame N lands on the same instant no matter how long the sequence is.
 */
@Serializable(with = RationalSerializer::class)
class Rational private constructor(val num: Long, val den: Long) : Comparable<Rational> {

    operator fun plus(o: Rational): Rational {
        val g = gcd(den, o.den)
        val l = den / g
        return of(Math.addExact(Math.multiplyExact(num, o.den / g), Math.multiplyExact(o.num, l)), Math.multiplyExact(l, o.den))
    }

    operator fun minus(o: Rational): Rational = this + (-o)
    operator fun unaryMinus(): Rational = Rational(-num, den)

    operator fun times(o: Rational): Rational {
        val g1 = gcd(abs(num), o.den).coerceAtLeast(1)
        val g2 = gcd(abs(o.num), den).coerceAtLeast(1)
        return of(Math.multiplyExact(num / g1, o.num / g2), Math.multiplyExact(den / g2, o.den / g1))
    }

    operator fun div(o: Rational): Rational {
        require(o.num != 0L) { "Division by zero" }
        return this * Rational.of(o.den, o.num)
    }

    operator fun times(k: Long): Rational = this * of(k, 1)

    override fun compareTo(other: Rational): Int {
        // Compare a/b with c/d using 128-bit safe path via Math.multiplyHigh.
        val lhsHi = Math.multiplyHigh(num, other.den)
        val rhsHi = Math.multiplyHigh(other.num, den)
        if (lhsHi != rhsHi) return lhsHi.compareTo(rhsHi)
        val lhsLo = num * other.den
        val rhsLo = other.num * den
        return java.lang.Long.compareUnsigned(lhsLo, rhsLo)
    }

    val isZero: Boolean get() = num == 0L
    val isNegative: Boolean get() = num < 0L

    fun toDouble(): Double = num.toDouble() / den.toDouble()

    /** Microseconds, rounded half away from zero (Media3 uses microseconds). */
    fun toMicros(): Long = roundDiv(Math.multiplyExact(num, 1_000_000L), den)
    fun toMillis(): Long = roundDiv(Math.multiplyExact(num, 1_000L), den)

    override fun equals(other: Any?): Boolean = other is Rational && other.num == num && other.den == den
    override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()
    override fun toString(): String = if (den == 1L) "$num" else "$num/$den"

    companion object {
        val ZERO = Rational(0, 1)
        val ONE = Rational(1, 1)

        fun of(num: Long, den: Long = 1): Rational {
            require(den != 0L) { "Zero denominator" }
            if (num == 0L) return ZERO
            val sign = if (den < 0) -1 else 1
            val g = gcd(abs(num), abs(den))
            return Rational(sign * num / g, sign * den / g)
        }

        fun seconds(s: Long) = of(s, 1)
        fun millis(ms: Long) = of(ms, 1000)
        fun micros(us: Long) = of(us, 1_000_000)

        /** Parses "n/d" or "n". */
        fun parse(text: String): Rational {
            val parts = text.trim().split('/')
            return when (parts.size) {
                1 -> of(parts[0].toLong(), 1)
                2 -> of(parts[0].toLong(), parts[1].toLong())
                else -> throw IllegalArgumentException("Bad rational: $text")
            }
        }

        /** Approximates a decimal (e.g. a UI slider value) with a bounded denominator. */
        fun fromDouble(value: Double, maxDen: Long = 1000): Rational {
            require(value.isFinite())
            return of(Math.round(value * maxDen), maxDen)
        }

        fun gcd(a: Long, b: Long): Long {
            var x = abs(a); var y = abs(b)
            while (y != 0L) { val t = x % y; x = y; y = t }
            return if (x == 0L) 1 else x
        }

        private fun roundDiv(n: Long, d: Long): Long {
            val q = n / d
            val r = n % d
            return if (abs(r) * 2 >= abs(d)) q + (if ((n < 0) xor (d < 0)) -1 else 1) else q
        }
    }
}

/** Timeline time in seconds, stored exactly. */
typealias Time = Rational

fun min(a: Rational, b: Rational) = if (a <= b) a else b
fun max(a: Rational, b: Rational) = if (a >= b) a else b

/** Frame rate as an exact ratio (frames per second). */
@Serializable(with = FrameRateSerializer::class)
data class FrameRate(val num: Long, val den: Long = 1) {
    init { require(num > 0 && den > 0) }

    val frameDuration: Rational get() = Rational.of(den, num)
    val fps: Double get() = num.toDouble() / den

    /** Exact start time of frame index [frame]. */
    fun timeOf(frame: Long): Time = Rational.of(Math.multiplyExact(frame, den), num)

    /** Frame index that contains time [t] (floor). */
    fun frameAt(t: Time): Long = Math.floorDiv(Math.multiplyExact(t.num, num), Math.multiplyExact(t.den, den))

    /** Snaps [t] to the nearest frame boundary. */
    fun snap(t: Time): Time {
        val exact = Rational.of(Math.multiplyExact(t.num, num), Math.multiplyExact(t.den, den))
        val floor = Math.floorDiv(exact.num, exact.den)
        val frac = exact - Rational.of(floor, 1)
        val frame = if (frac >= Rational.of(1, 2)) floor + 1 else floor
        return timeOf(frame)
    }

    /** SMPTE-style non-drop timecode HH:MM:SS:FF using the nominal rounded rate. */
    fun timecode(t: Time): String {
        val nominal = Math.round(fps).coerceAtLeast(1)
        val f = frameAt(t)
        val ff = f % nominal
        val totalSec = f / nominal
        return "%02d:%02d:%02d:%02d".format(totalSec / 3600, (totalSec / 60) % 60, totalSec % 60, ff)
    }

    override fun toString() = "$num/$den"

    companion object {
        val FPS_23_976 = FrameRate(24000, 1001)
        val FPS_24 = FrameRate(24)
        val FPS_25 = FrameRate(25)
        val FPS_29_97 = FrameRate(30000, 1001)
        val FPS_30 = FrameRate(30)
        val FPS_50 = FrameRate(50)
        val FPS_59_94 = FrameRate(60000, 1001)
        val FPS_60 = FrameRate(60)
        val STANDARD = listOf(FPS_23_976, FPS_24, FPS_25, FPS_29_97, FPS_30, FPS_50, FPS_59_94, FPS_60)

        fun parse(s: String): FrameRate {
            val r = Rational.parse(s)
            return FrameRate(r.num, r.den)
        }
    }
}
