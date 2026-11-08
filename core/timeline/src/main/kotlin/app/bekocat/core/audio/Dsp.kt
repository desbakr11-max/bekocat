package app.bekocat.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Pure-Kotlin audio DSP used by both preview and export. Buffers are interleaved
 * stereo float (L,R,L,R…) at the sequence sample rate (48 kHz by default).
 */

fun dbToGain(db: Double) = 10.0.pow(db / 20.0)
fun gainToDb(g: Double) = if (g <= 1e-12) -240.0 else 20 * log10(g)

/** RBJ audio-EQ-cookbook biquad, transposed direct form II, per channel state. */
class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var z1 = DoubleArray(2); private var z2 = DoubleArray(2)

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    fun processInterleaved(buf: FloatArray, channels: Int = 2) {
        var i = 0
        while (i < buf.size) { for (c in 0 until channels) buf[i + c] = process(buf[i + c].toDouble(), c).toFloat(); i += channels }
    }

    /** Magnitude response in dB at frequency [f] (for tests and EQ curves in UI). */
    fun responseDb(f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs
        val cr = cos(w); val ci = -sin(w); val c2r = cos(2 * w); val c2i = -sin(2 * w)
        val nr = b0 + b1 * cr + b2 * c2r; val ni = b1 * ci + b2 * c2i
        val dr = 1 + a1 * cr + a2 * c2r; val di = a1 * ci + a2 * c2i
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun peaking(fs: Double, f0: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / fs; val alpha = sin(w) / (2 * q)
            return make(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
        }

        fun lowShelf(fs: Double, f0: Double, gainDb: Double, s: Double = 1.0): Biquad {
            val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / fs
            val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / s - 1) + 2); val c = cos(w); val sa = 2 * sqrt(a) * alpha
            return make(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
        }

        fun highShelf(fs: Double, f0: Double, gainDb: Double, s: Double = 1.0): Biquad {
            val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / fs
            val alpha = sin(w) / 2 * sqrt((a + 1 / a) * (1 / s - 1) + 2); val c = cos(w); val sa = 2 * sqrt(a) * alpha
            return make(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
        }

        fun highPass(fs: Double, f0: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * f0 / fs; val alpha = sin(w) / (2 * q); val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun lowPass(fs: Double, f0: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * f0 / fs; val alpha = sin(w) / (2 * q); val c = cos(w)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun bandPass(fs: Double, f0: Double, q: Double): Biquad {
            val w = 2 * PI * f0 / fs; val alpha = sin(w) / (2 * q); val c = cos(w)
            return make(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
        }
    }
}

/** Radix-2 in-place complex FFT. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    fun hann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
}

/** Feed-forward compressor with soft knee and smoothed gain. */
class Compressor(fs: Double, private val thresholdDb: Double, private val ratio: Double, attackMs: Double, releaseMs: Double,
                 private val makeupDb: Double, private val kneeDb: Double) {
    private val att = exp(-1.0 / (fs * attackMs / 1000)); private val rel = exp(-1.0 / (fs * releaseMs / 1000))
    private var env = 0.0 // gain reduction in dB (≥0)
    var maxReductionDb = 0.0; private set

    private fun staticCurve(inDb: Double): Double {
        val over = inDb - thresholdDb
        return when {
            2 * over < -kneeDb -> 0.0
            2 * abs(over) <= kneeDb && kneeDb > 0 -> (1 / ratio - 1) * (over + kneeDb / 2).pow(2) / (2 * kneeDb)
            else -> (1 / ratio - 1) * over
        }.let { -it }
    }

    fun process(buf: FloatArray) {
        val mk = dbToGain(makeupDb)
        var i = 0
        while (i < buf.size) {
            val peak = max(abs(buf[i].toDouble()), abs(buf.getOrElse(i + 1) { 0f }.toDouble()))
            val target = staticCurve(gainToDb(peak))
            env = if (target > env) att * env + (1 - att) * target else rel * env + (1 - rel) * target
            if (env > maxReductionDb) maxReductionDb = env
            val g = dbToGain(-env) * mk
            buf[i] = (buf[i] * g).toFloat(); if (i + 1 < buf.size) buf[i + 1] = (buf[i + 1] * g).toFloat()
            i += 2
        }
    }
}

/** Look-ahead brick-wall peak limiter (stereo-linked). */
class Limiter(fs: Double, ceilingDb: Double, releaseMs: Double, lookaheadMs: Double = 5.0) {
    private val ceiling = dbToGain(ceilingDb)
    private val la = max(1, (fs * lookaheadMs / 1000).toInt())
    private val rel = exp(-1.0 / (fs * releaseMs / 1000))
    private var g = 1.0 // persists across calls so chunked rendering has no gain jumps

    fun process(buf: FloatArray) {
        val frames = buf.size / 2
        if (frames == 0) return
        // Required gain per frame, then a sliding minimum over the look-ahead window, then smoothed release.
        val need = DoubleArray(frames) { f ->
            val p = max(abs(buf[2 * f].toDouble()), abs(buf[2 * f + 1].toDouble()))
            if (p > ceiling) ceiling / p else 1.0
        }
        val minAhead = DoubleArray(frames)
        val dq = ArrayDeque<Int>()
        for (f in frames - 1 downTo 0) {
            while (dq.isNotEmpty() && dq.first() > f + la) dq.removeFirst()
            while (dq.isNotEmpty() && need[dq.last()] >= need[f]) dq.removeLast()
            dq.addLast(f)
            minAhead[f] = need[dq.first()]
        }
        for (f in 0 until frames) {
            val target = minAhead[f]
            g = if (target < g) target else rel * g + (1 - rel) * target
            val gg = min(g, need[f])
            buf[2 * f] = (buf[2 * f] * gg).toFloat(); buf[2 * f + 1] = (buf[2 * f + 1] * gg).toFloat()
        }
    }
}

/** Downward expander/gate with range, attack, hold and release. */
class NoiseGate(fs: Double, private val thresholdDb: Double, attackMs: Double, releaseMs: Double, private val rangeDb: Double, holdMs: Double = 30.0) {
    private val att = exp(-1.0 / (fs * attackMs / 1000)); private val rel = exp(-1.0 / (fs * releaseMs / 1000))
    private val hold = (fs * holdMs / 1000).toInt()
    private val envCoef = exp(-1.0 / (fs * 0.005))
    private var env = 0.0; private var g = 0.0; private var holdLeft = 0

    fun process(buf: FloatArray) {
        val floor = dbToGain(rangeDb)
        val thr = dbToGain(thresholdDb)
        var i = 0
        while (i < buf.size) {
            val x = max(abs(buf[i].toDouble()), abs(buf.getOrElse(i + 1) { 0f }.toDouble()))
            env = max(x, envCoef * env)
            val open = env >= thr
            if (open) holdLeft = hold
            val target = if (open || holdLeft-- > 0) 1.0 else floor
            g = if (target > g) att * g + (1 - att) * target else rel * g + (1 - rel) * target
            buf[i] = (buf[i] * g).toFloat(); if (i + 1 < buf.size) buf[i + 1] = (buf[i + 1] * g).toFloat()
            i += 2
        }
    }
}

/** Split-band de-esser: sibilance band detected with a band-pass; only the high band is attenuated. */
class DeEsser(private val fs: Double, private val thresholdDb: Double, freq: Double, private val maxReductionDb: Double) {
    private val detect = Biquad.bandPass(fs, freq, 2.0)
    private val split = Biquad.highPass(fs, freq * 0.7)
    private val envCoef = exp(-1.0 / (fs * 0.003)); private val relCoef = exp(-1.0 / (fs * 0.06))
    private var env = 0.0

    fun process(buf: FloatArray) {
        var i = 0
        while (i < buf.size) {
            val l = buf[i].toDouble(); val r = buf.getOrElse(i + 1) { 0f }.toDouble()
            val d = abs(detect.process((l + r) / 2, 0))
            env = if (d > env) envCoef * env + (1 - envCoef) * d else relCoef * env + (1 - relCoef) * d
            val over = gainToDb(env) - thresholdDb
            val red = if (over > 0) min(maxReductionDb, over * 0.75) else 0.0
            val hg = dbToGain(-red)
            val hl = split.process(l, 0); val hr = split.process(r, 1)
            buf[i] = (l - hl + hl * hg).toFloat(); if (i + 1 < buf.size) buf[i + 1] = (r - hr + hr * hg).toFloat()
            i += 2
        }
    }
}

/**
 * Spectral noise reduction (STFT, Hann, 75 % overlap). The noise profile is the per-bin
 * 15th-percentile magnitude over the clip (robust to speech). Gains are Wiener-style,
 * floored at -[reductionDb] and smoothed over time to limit musical noise.
 */
object SpectralDenoise {
    fun process(buf: FloatArray, reductionDb: Double, sensitivity: Double, n: Int = 1024) {
        for (ch in 0 until 2) {
            val frames = buf.size / 2
            val x = DoubleArray(frames) { buf[2 * it + ch].toDouble() }
            val y = stftProcess(x, n) { mags, all -> noiseGains(mags, all, reductionDb, sensitivity) }
            for (f in 0 until frames) buf[2 * f + ch] = y[f].toFloat()
        }
    }

    private fun noiseGains(frameMags: Array<DoubleArray>, @Suppress("UNUSED_PARAMETER") all: Boolean, reductionDb: Double, sens: Double): Array<DoubleArray> {
        val bins = frameMags.firstOrNull()?.size ?: return frameMags
        val floor = dbToGain(-reductionDb)
        val noise = DoubleArray(bins) { b ->
            val col = DoubleArray(frameMags.size) { frameMags[it][b] }
            // 15th percentile of a Rayleigh-distributed noise magnitude ≈ 0.45 × its RMS → scale by 2.2.
            col.sort(); col[(col.size * 0.15).toInt().coerceIn(0, col.size - 1)] * 2.2
        }
        val over = 1.5 + 2.0 * sens
        val out = Array(frameMags.size) { DoubleArray(bins) }
        val prev = DoubleArray(bins) { 1.0 }
        for (f in frameMags.indices) for (b in 0 until bins) {
            val s = frameMags[f][b]
            val snr = (s * s) / ((noise[b] * over).pow(2) + 1e-20)
            var g = max(floor, 1 - 1 / max(snr, 1e-9))
            g = 0.6 * prev[b] + 0.4 * g
            prev[b] = g
            out[f][b] = g
        }
        return out
    }

    /** Generic STFT → per-bin gain → overlap-add ISTFT (sqrt-Hann analysis/synthesis, perfect reconstruction at hop n/4). */
    fun stftProcess(x: DoubleArray, n: Int, gainFn: (Array<DoubleArray>, Boolean) -> Array<DoubleArray>): DoubleArray {
        val hop = n / 4
        val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
        val padded = DoubleArray(x.size + 2 * n).also { System.arraycopy(x, 0, it, n, x.size) }
        val frames = (padded.size - n) / hop + 1
        val specRe = Array(frames) { DoubleArray(n) }; val specIm = Array(frames) { DoubleArray(n) }
        val mags = Array(frames) { DoubleArray(n / 2 + 1) }
        for (f in 0 until frames) {
            val re = specRe[f]; val im = specIm[f]
            for (i in 0 until n) re[i] = padded[f * hop + i] * win[i]
            Fft.transform(re, im)
            for (b in 0..n / 2) mags[f][b] = sqrt(re[b] * re[b] + im[b] * im[b])
        }
        val gains = gainFn(mags, true)
        val out = DoubleArray(padded.size)
        val norm = DoubleArray(padded.size)
        for (f in 0 until frames) {
            val re = specRe[f]; val im = specIm[f]
            for (b in 0..n / 2) {
                val g = gains[f][b]
                re[b] *= g; im[b] *= g
                if (b in 1 until n / 2) { re[n - b] = re[b]; im[n - b] = -im[b] }
            }
            Fft.transform(re, im, inverse = true)
            for (i in 0 until n) { out[f * hop + i] += re[i] * win[i]; norm[f * hop + i] += win[i] * win[i] }
        }
        return DoubleArray(x.size) { val k = it + n; if (norm[k] > 1e-9) out[k] / norm[k] else 0.0 }
    }
}

/** Late-reverb suppression by spectral subtraction of an exponentially decayed past spectrum (Lebart-style). */
object Dereverb {
    fun process(buf: FloatArray, amount: Double, fs: Double, rt60: Double = 0.5) {
        val n = 1024; val hop = n / 4
        val delayFrames = max(1, (0.05 * fs / hop).toInt())
        val decay = exp(-6.9 * hop / (rt60 * fs))
        for (ch in 0 until 2) {
            val frames = buf.size / 2
            val x = DoubleArray(frames) { buf[2 * it + ch].toDouble() }
            val y = SpectralDenoise.stftProcess(x, n) { mags, _ ->
                Array(mags.size) { f ->
                    DoubleArray(mags[f].size) { b ->
                        val past = if (f >= delayFrames) mags[f - delayFrames][b] * decay.pow(delayFrames) else 0.0
                        val s = mags[f][b]
                        max(0.15, 1 - amount * (past * past) / (s * s + 1e-20))
                    }
                }
            }
            for (f in 0 until frames) buf[2 * f + ch] = y[f].toFloat()
        }
    }
}

/** Freeverb-style stereo reverb (8 combs + 4 allpasses per channel). */
class Reverb(fs: Double, private val mix: Double, size: Double, damping: Double) {
    private val scale = fs / 44100.0
    private val combL = listOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb((it * scale).toInt(), 0.7 + 0.28 * size, damping * 0.4) }
    private val combR = listOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb(((it + 23) * scale).toInt(), 0.7 + 0.28 * size, damping * 0.4) }
    private val apL = listOf(556, 441, 341, 225).map { Allpass((it * scale).toInt()) }
    private val apR = listOf(579, 464, 364, 248).map { Allpass((it * scale).toInt()) }

    private class Comb(n: Int, val fb: Double, val damp: Double) {
        val b = DoubleArray(max(1, n)); var i = 0; var store = 0.0
        fun p(x: Double): Double { val y = b[i]; store = y * (1 - damp) + store * damp; b[i] = x + store * fb; i = (i + 1) % b.size; return y }
    }
    private class Allpass(n: Int) {
        val b = DoubleArray(max(1, n)); var i = 0
        fun p(x: Double): Double { val bo = b[i]; val y = -x + bo; b[i] = x + bo * 0.5; i = (i + 1) % b.size; return y }
    }

    fun process(buf: FloatArray) {
        var i = 0
        while (i + 1 < buf.size) {
            val inp = (buf[i] + buf[i + 1]) * 0.015
            var l = combL.sumOf { it.p(inp) }; var r = combR.sumOf { it.p(inp) }
            for (a in apL) l = a.p(l); for (a in apR) r = a.p(r)
            buf[i] = (buf[i] * (1 - mix) + l * mix).toFloat(); buf[i + 1] = (buf[i + 1] * (1 - mix) + r * mix).toFloat()
            i += 2
        }
    }
}

/** ITU-R BS.1770-4 / EBU R128 loudness meter (integrated, gated). */
object Loudness {
    private fun kFilters(fs: Double): Pair<Biquad, Biquad> {
        // Pre-filter (high shelf ~ +4 dB above 1.5 kHz) and RLB high-pass, derived for any sample rate.
        val shelf = Biquad.highShelf(fs, 1681.974450955533, 3.999843853973347, 1.0)
        val hp = Biquad.highPass(fs, 38.13547087602444, 0.5003270373238773)
        return shelf to hp
    }

    /** Integrated loudness in LUFS of an interleaved stereo buffer; null if fully silent/gated. */
    fun integrated(buf: FloatArray, fs: Double): Double? {
        val (s, h) = kFilters(fs)
        val frames = buf.size / 2
        val filtered = DoubleArray(buf.size)
        for (f in 0 until frames) for (c in 0..1) filtered[2 * f + c] = h.process(s.process(buf[2 * f + c].toDouble(), c), c)
        val block = (0.4 * fs).toInt(); val step = (0.1 * fs).toInt()
        if (frames < block) return blockLoudness(filtered, 0, frames)?.let { if (it > -70) it else null }
        val blocks = ArrayList<Double>()
        var st = 0
        while (st + block <= frames) { blocks += meanSquare(filtered, st, block); st += step }
        val abs = blocks.filter { lufs(it) > -70.0 }
        if (abs.isEmpty()) return null
        val relThr = lufs(abs.average()) - 10.0
        val rel = abs.filter { lufs(it) > relThr }
        if (rel.isEmpty()) return null
        return lufs(rel.average())
    }

    private fun meanSquare(x: DoubleArray, start: Int, len: Int): Double {
        var sum = 0.0
        for (f in start until start + len) sum += x[2 * f] * x[2 * f] + x[2 * f + 1] * x[2 * f + 1]
        return sum / len
    }

    private fun blockLoudness(x: DoubleArray, start: Int, len: Int): Double? = if (len <= 0) null else lufs(meanSquare(x, start, len))
    private fun lufs(ms: Double) = -0.691 + 10 * log10(ms + 1e-20)

    fun samplePeakDb(buf: FloatArray): Double = gainToDb(buf.maxOfOrNull { abs(it.toDouble()) } ?: 0.0)

    /** Gain (dB) that brings [buf] to [targetLufs], limited so that the result doesn't exceed +20 dB of boost. */
    fun normalizationGainDb(buf: FloatArray, fs: Double, targetLufs: Double): Double {
        val l = integrated(buf, fs) ?: return 0.0
        return (targetLufs - l).coerceIn(-40.0, 20.0)
    }
}

/** Short-time energy envelope (RMS per window) and detectors built on it. */
object Analysis {
    /** Mono RMS per window of [windowSec]. */
    fun rmsEnvelope(buf: FloatArray, fs: Double, windowSec: Double): DoubleArray {
        val win = max(1, (windowSec * fs).toInt())
        val frames = buf.size / 2
        val n = (frames + win - 1) / win
        return DoubleArray(n) { w ->
            var s = 0.0; var c = 0
            for (f in w * win until min(frames, (w + 1) * win)) { val m = (buf[2 * f] + buf[2 * f + 1]) / 2.0; s += m * m; c++ }
            if (c == 0) 0.0 else sqrt(s / c)
        }
    }

    /**
     * Silence ranges (seconds) below [thresholdDb] lasting at least [minSilence], shrunk by [padding]
     * on each side so speech edges are kept.
     */
    fun silences(buf: FloatArray, fs: Double, thresholdDb: Double, minSilence: Double, padding: Double): List<Pair<Double, Double>> {
        val w = 0.02
        val env = rmsEnvelope(buf, fs, w)
        val thr = dbToGain(thresholdDb)
        val out = ArrayList<Pair<Double, Double>>()
        var start = -1
        for (i in 0..env.size) {
            val quiet = i < env.size && env[i] < thr
            if (quiet && start < 0) start = i
            if (!quiet && start >= 0) {
                val s = start * w; val e = i * w
                if (e - s >= minSilence) {
                    val ps = if (start == 0) 0.0 else s + padding
                    val pe = if (i == env.size) e else e - padding
                    if (pe > ps) out += ps to pe
                }
                start = -1
            }
        }
        return out
    }

    /** Spectral-flux onset strength at ~100 Hz frame rate. */
    fun onsetEnvelope(buf: FloatArray, fs: Double): Pair<DoubleArray, Double> {
        val n = 1024; val hop = (fs / 100).toInt().coerceAtLeast(64)
        val frames = buf.size / 2
        val mono = DoubleArray(frames) { (buf[2 * it] + buf[2 * it + 1]) / 2.0 }
        val win = Fft.hann(n)
        val count = max(0, (frames - n) / hop)
        val out = DoubleArray(count)
        var prev = DoubleArray(n / 2 + 1)
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (f in 0 until count) {
            for (i in 0 until n) { re[i] = mono[f * hop + i] * win[i]; im[i] = 0.0 }
            Fft.transform(re, im)
            val mag = DoubleArray(n / 2 + 1) { b -> ln(1 + 100 * sqrt(re[b] * re[b] + im[b] * im[b])) }
            var flux = 0.0
            for (b in mag.indices) flux += max(0.0, mag[b] - prev[b])
            out[f] = flux; prev = mag
        }
        return out to fs / hop
    }

    /** Tempo (BPM) by autocorrelation of the onset envelope, searched in [minBpm, maxBpm]. */
    fun tempo(onset: DoubleArray, frameRate: Double, minBpm: Double = 60.0, maxBpm: Double = 200.0): Double {
        if (onset.size < 16) return 0.0
        val mean = onset.average()
        val x = DoubleArray(onset.size) { onset[it] - mean }
        var best = 0.0; var bestLag = 0
        val minLag = (60.0 / maxBpm * frameRate).toInt().coerceAtLeast(1)
        val maxLag = (60.0 / minBpm * frameRate).toInt().coerceAtMost(x.size - 1)
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in 0 until x.size - lag) s += x[i] * x[i + lag]
            // Mild preference for ~120 BPM to resolve octave ambiguity.
            val bpm = 60.0 * frameRate / lag
            val w = exp(-0.5 * (ln(bpm / 120.0) / 0.9).pow(2))
            if (s * w > best) { best = s * w; bestLag = lag }
        }
        if (bestLag == 0) return 0.0
        // Parabolic refinement
        fun ac(l: Int): Double { var s = 0.0; for (i in 0 until x.size - l) s += x[i] * x[i + l]; return s }
        val a = ac(bestLag - 1); val b = ac(bestLag); val c = ac(bestLag + 1)
        val d = (a - c) / (2 * (a - 2 * b + c)).let { if (abs(it) < 1e-12) 1e12 else it }
        return 60.0 * frameRate / (bestLag + d.coerceIn(-0.5, 0.5))
    }

    /** Beat times (seconds) by dynamic-programming beat tracking (Ellis 2007). */
    fun beats(buf: FloatArray, fs: Double): Pair<Double, List<Double>> {
        val (onset, fr) = onsetEnvelope(buf, fs)
        val bpm = tempo(onset, fr)
        if (bpm <= 0) return 0.0 to emptyList()
        val period = 60.0 / bpm * fr
        val n = onset.size
        val score = DoubleArray(n); val back = IntArray(n) { -1 }
        val alpha = 100.0
        for (i in 0 until n) {
            var best = 0.0; var bi = -1
            val lo = (i - 2 * period).toInt().coerceAtLeast(0); val hi = (i - period / 2).toInt()
            for (j in lo..hi) {
                if (j < 0) continue
                val pen = -alpha * ln((i - j) / period).pow(2)
                val s = score[j] + pen
                if (bi < 0 || s > best) { best = s; bi = j }
            }
            score[i] = onset[i] + (if (bi >= 0) best else 0.0)
            back[i] = bi
        }
        var i = (n - period.toInt()).coerceAtLeast(0).let { s -> (s until n).maxByOrNull { score[it] } ?: (n - 1) }
        val out = ArrayList<Double>()
        while (i >= 0) { out += i / fr; i = back[i] }
        return bpm to out.reversed()
    }

    /** Min/max peaks per bucket for waveform display. */
    fun peaks(buf: FloatArray, buckets: Int): FloatArray {
        val frames = buf.size / 2
        val out = FloatArray(buckets * 2)
        if (frames == 0) return out
        for (b in 0 until buckets) {
            val s = (b.toLong() * frames / buckets).toInt(); val e = max(s + 1, ((b + 1).toLong() * frames / buckets).toInt())
            var lo = 0f; var hi = 0f
            for (f in s until min(e, frames)) { val m = (buf[2 * f] + buf[2 * f + 1]) / 2; if (m < lo) lo = m; if (m > hi) hi = m }
            out[2 * b] = lo; out[2 * b + 1] = hi
        }
        return out
    }
}
