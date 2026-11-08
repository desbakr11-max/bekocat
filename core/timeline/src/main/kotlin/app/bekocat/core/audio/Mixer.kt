package app.bekocat.core.audio

import app.bekocat.core.timeline.AudioRole
import app.bekocat.core.timeline.Clip
import app.bekocat.core.timeline.ClipKind
import app.bekocat.core.timeline.EffectInstance
import app.bekocat.core.timeline.Project
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.Sequence
import app.bekocat.core.timeline.TrackKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Random-access source of conformed audio (interleaved stereo float at the sequence rate). */
interface PcmSource {
    val frames: Long
    /** Reads [count] frames starting at [start] into [out] (stereo interleaved); out-of-range frames are silence. */
    fun read(start: Long, out: FloatArray, count: Int)
}

class ArrayPcmSource(private val data: FloatArray) : PcmSource {
    override val frames: Long get() = (data.size / 2).toLong()
    override fun read(start: Long, out: FloatArray, count: Int) {
        for (i in 0 until count) {
            val f = start + i
            if (f < 0 || f >= frames) { out[2 * i] = 0f; out[2 * i + 1] = 0f } else { out[2 * i] = data[(2 * f).toInt()]; out[2 * i + 1] = data[(2 * f + 1).toInt()] }
        }
    }
}

/**
 * WSOLA time-scale modification driven by a source-position function. For each output hop the
 * analysis frame is taken near the desired source position, aligned by cross-correlation to the
 * natural continuation of the previous frame, then overlap-added with a Hann window. This keeps
 * pitch while following constant speed, speed ramps, or time remaps.
 */
object Wsola {
    fun render(src: PcmSource, outFrames: Int, fs: Double, sourcePosAt: (Int) -> Double): FloatArray {
        val win = (0.040 * fs).toInt().let { it - it % 2 }.coerceAtLeast(64)
        val hop = win / 2
        val tol = (0.012 * fs).toInt()
        val w = Fft.hann(win)
        val out = FloatArray(outFrames * 2)
        val norm = FloatArray(outFrames)
        val frame = FloatArray(win * 2)
        val cand = FloatArray((win + 2 * tol) * 2)
        var prevNatural = -1.0
        var o = 0
        while (o < outFrames) {
            val target = sourcePosAt(o)
            var start = floor(target - win / 2.0).toLong()
            if (prevNatural >= 0) {
                // Search around target for the best match with the previous frame's natural continuation.
                src.read(prevNatural.toLong(), frame, win)
                src.read(start - tol, cand, win + 2 * tol)
                var best = Double.NEGATIVE_INFINITY; var bestOff = tol
                var off = 0
                while (off <= 2 * tol) {
                    var s = 0.0
                    var k = 0
                    while (k < hop) { s += (frame[2 * k] * cand[2 * (k + off)] + frame[2 * k + 1] * cand[2 * (k + off) + 1]).toDouble(); k += 4 }
                    if (s > best) { best = s; bestOff = off }
                    off += 2
                }
                start = start - tol + bestOff
            }
            src.read(start, frame, win)
            for (k in 0 until win) {
                val oi = o - hop + k
                if (oi < 0 || oi >= outFrames) continue
                out[2 * oi] += (frame[2 * k] * w[k]).toFloat(); out[2 * oi + 1] += (frame[2 * k + 1] * w[k]).toFloat()
                norm[oi] += w[k].toFloat()
            }
            prevNatural = (start + hop).toDouble()
            o += hop
        }
        for (i in 0 until outFrames) if (norm[i] > 1e-3f) { out[2 * i] /= norm[i]; out[2 * i + 1] /= norm[i] }
        return out
    }
}

/** Windowed-sinc (Lanczos a=8) variable-rate resampler: output frame i reads source position pos(i). */
object Resampler {
    private const val A = 8
    fun render(src: PcmSource, outFrames: Int, sourcePosAt: (Int) -> Double, lowpass: Double = 1.0): FloatArray {
        val out = FloatArray(outFrames * 2)
        val tmp = FloatArray((2 * A + 1) * 2)
        val cutoff = lowpass.coerceIn(0.05, 1.0)
        for (i in 0 until outFrames) {
            val p = sourcePosAt(i)
            val base = floor(p).toLong()
            src.read(base - A, tmp, 2 * A + 1)
            var l = 0.0; var r = 0.0; var ws = 0.0
            for (k in -A..A) {
                val x = (p - (base + k)) * cutoff
                val wgt = if (abs(x) < 1e-9) 1.0 else if (abs(x) >= A) 0.0 else A * sin(PI * x) * sin(PI * x / A) / (PI * PI * x * x)
                l += tmp[2 * (k + A)] * wgt; r += tmp[2 * (k + A) + 1] * wgt; ws += wgt
            }
            if (abs(ws) > 1e-9) { l /= ws; r /= ws }
            out[2 * i] = l.toFloat(); out[2 * i + 1] = r.toFloat()
        }
        return out
    }
}

/** Applies a clip's audio effect chain (EffectInstances of audio types) offline. */
object AudioFx {
    fun apply(buf: FloatArray, fx: List<EffectInstance>, fs: Double) {
        for (e in fx) {
            if (!e.enabled) continue
            fun p(k: String, d: Double) = e.params[k]?.base ?: d
            when (e.type) {
                "eq" -> {
                    if (p("highpass", 0.0) > 0) Biquad.highPass(fs, p("highpass", 0.0)).processInterleaved(buf)
                    if (p("lowGain", 0.0) != 0.0) Biquad.lowShelf(fs, p("lowFreq", 120.0), p("lowGain", 0.0)).processInterleaved(buf)
                    if (p("midGain", 0.0) != 0.0) Biquad.peaking(fs, p("midFreq", 1200.0), p("midQ", 1.0), p("midGain", 0.0)).processInterleaved(buf)
                    if (p("highGain", 0.0) != 0.0) Biquad.highShelf(fs, p("highFreq", 8000.0), p("highGain", 0.0)).processInterleaved(buf)
                }
                "compressor" -> Compressor(fs, p("threshold", -18.0), p("ratio", 3.0), p("attack", 10.0), p("release", 120.0), p("makeup", 3.0), p("knee", 4.0)).process(buf)
                "limiter" -> Limiter(fs, p("ceiling", -1.0), p("release", 60.0)).process(buf)
                "gate" -> NoiseGate(fs, p("threshold", -50.0), p("attack", 2.0), p("release", 150.0), p("range", -40.0)).process(buf)
                "deesser" -> DeEsser(fs, p("threshold", -28.0), p("freq", 6500.0), p("reduction", 8.0)).process(buf)
                "denoise" -> SpectralDenoise.process(buf, p("reduction", 12.0), p("sensitivity", 0.5))
                "dereverb" -> Dereverb.process(buf, p("amount", 0.4), fs)
                "reverb" -> Reverb(fs, p("mix", 0.2), p("size", 0.5), p("damping", 0.5)).process(buf)
            }
        }
    }
}

/**
 * Renders the complete mix of a sequence: per-clip conform (speed / remap / pitch / effects),
 * volume & pan automation, fades, track gain/pan/mute/solo, automatic ducking under dialogue,
 * master loudness normalisation and a final limiter. Identical for preview and export.
 */
class AudioMixer(private val project: Project, private val sources: (Clip) -> PcmSource?, private val fs: Int = project.sequence.audioSampleRate) {

    private val clipCache = HashMap<String, Pair<Int, FloatArray>>() // clip id → (settings hash, rendered clip audio)

    data class Stems(val mix: FloatArray, val byTrack: Map<String, FloatArray>)

    private fun audible(seq: Sequence): List<Pair<app.bekocat.core.timeline.Track, Clip>> {
        val anySolo = seq.tracks.any { it.solo }
        return seq.tracks.filter { !it.muted && (!anySolo || it.solo) }.flatMap { t ->
            t.clips.filter { c ->
                c.enabled && !c.audio.muted && (c.kind == ClipKind.MEDIA || c.kind == ClipKind.COMPOUND) &&
                    (t.kind == TrackKind.AUDIO || project.asset(c.assetId)?.hasAudio == true)
            }.map { t to it }
        }
    }

    /** Renders one clip's processed audio for its whole duration (cached by settings). */
    fun renderClip(c: Clip): FloatArray? {
        val key = listOf(c.assetId, c.sourceIn, c.sourceOut, c.speed, c.reversed, c.timeRemap, c.remapDuration, c.audio.pitchSemitones,
            c.audio.preservePitch, c.audio.effects, c.audio.stems).hashCode()
        clipCache[c.id]?.let { if (it.first == key) return it.second }
        val src = sources(c) ?: return null
        val outFrames = (c.duration.toDouble() * fs).toInt().coerceAtLeast(0)
        val pitch = 2.0.pow(c.audio.pitchSemitones / 12.0)
        val startS = c.timelineStart
        fun srcPos(i: Int): Double = c.sourceTimeAt(startS + Rational.fromDouble(i.toDouble() / fs, fs.toLong())).toDouble() * fs
        val constantUnity = c.timeRemap == null && !c.reversed && c.speed == Rational.ONE
        var buf: FloatArray = when {
            constantUnity && pitch == 1.0 -> FloatArray(outFrames * 2).also { src.read((c.sourceIn.toDouble() * fs).toLong(), it, outFrames) }
            c.audio.preservePitch || pitch != 1.0 -> {
                // Time-stretch to the target duration while keeping pitch, then resample for pitch shift.
                if (pitch == 1.0) Wsola.render(src, outFrames, fs.toDouble(), ::srcPos)
                else {
                    val stretchedLen = (outFrames * pitch).toInt()
                    val stretched = Wsola.render(src, stretchedLen, fs.toDouble()) { i -> srcPos((i / pitch).toInt()) }
                    Resampler.render(ArrayPcmSource(stretched), outFrames, { i -> i * pitch }, lowpass = if (pitch > 1) 1 / pitch else 1.0)
                }
            }
            else -> Resampler.render(src, outFrames, ::srcPos, lowpass = (1.0 / abs(c.speed.toDouble())).coerceAtMost(1.0))
        }
        AudioFx.apply(buf, c.audio.effects, fs.toDouble())
        clipCache[c.id] = key to buf
        return buf
    }

    /** Mix [startSec, startSec+durSec) of the main sequence (stereo interleaved). */
    fun mix(startSec: Double, durSec: Double, applyMaster: Boolean = true, loudnessGainDb: Double? = null): FloatArray {
        val seq = project.sequence
        val n = (durSec * fs).toInt().coerceAtLeast(0)
        val out = FloatArray(n * 2)
        val dialogue = FloatArray(n * 2)
        val duckable = ArrayList<Pair<app.bekocat.core.timeline.Track, FloatArray>>()
        for ((track, c) in audible(seq)) {
            val cs = c.timelineStart.toDouble(); val ce = c.timelineEnd.toDouble()
            if (ce <= startSec || cs >= startSec + durSec) continue
            val audio = renderClip(c) ?: continue
            val tb = if (track.duckUnderDialogue) FloatArray(n * 2) else out
            val from = max(0, ((cs - startSec) * fs).toInt()); val to = min(n, ((ce - startSec) * fs).toInt())
            val fadeIn = c.audio.fadeIn.toDouble(); val fadeOut = c.audio.fadeOut.toDouble(); val dur = ce - cs
            val volAnimated = c.audio.volume.isAnimated; val panAnimated = c.audio.pan.isAnimated
            var vol = c.audio.volume.base; var pan = c.audio.pan.base
            for (i in from until to) {
                val local = (startSec + i.toDouble() / fs) - cs
                val ci = (local * fs).toInt()
                if (ci < 0 || 2 * ci + 1 >= audio.size) continue
                if ((volAnimated || panAnimated) && i % 64 == 0) {
                    val lt = Rational.fromDouble(local, 48000)
                    if (volAnimated) vol = c.audio.volume.valueAt(lt)
                    if (panAnimated) pan = c.audio.pan.valueAt(lt)
                }
                var g = vol * track.volume
                if (fadeIn > 0 && local < fadeIn) g *= sin(PI / 2 * local / fadeIn).pow(2)
                if (fadeOut > 0 && local > dur - fadeOut) g *= sin(PI / 2 * ((dur - local) / fadeOut).coerceIn(0.0, 1.0)).pow(2)
                val p = (pan + track.pan).coerceIn(-1.0, 1.0)
                val gl = g * cos((p + 1) * PI / 4) * sqrt(2.0); val gr = g * sin((p + 1) * PI / 4) * sqrt(2.0)
                val l = audio[2 * ci] * gl; val r = audio[2 * ci + 1] * gr
                tb[2 * i] += l.toFloat(); tb[2 * i + 1] += r.toFloat()
                if (track.role == AudioRole.DIALOGUE) { dialogue[2 * i] += l.toFloat(); dialogue[2 * i + 1] += r.toFloat() }
            }
            if (track.duckUnderDialogue) duckable += track to tb
        }
        if (duckable.isNotEmpty()) {
            val env = Ducking.gainCurve(dialogue, fs.toDouble())
            for ((track, tb) in duckable) {
                val depth = track.duckAmountDb
                for (i in 0 until n) {
                    val g = dbToGain(depth * env[i])
                    out[2 * i] += tb[2 * i] * g.toFloat(); out[2 * i + 1] += tb[2 * i + 1] * g.toFloat()
                }
            }
        }
        if (applyMaster) {
            val m = seq.master
            val norm = loudnessGainDb ?: 0.0
            val g = dbToGain(norm) * m.volume
            if (g != 1.0) for (i in out.indices) out[i] = (out[i] * g).toFloat()
            Limiter(fs.toDouble(), m.limiterCeilingDb, 80.0).process(out)
        }
        return out
    }

    /** Two-pass master render for export: measure integrated loudness, then normalise and limit. */
    fun renderMaster(durationSec: Double): FloatArray {
        val target = project.sequence.master.loudnessTargetLufs
        val raw = mix(0.0, durationSec, applyMaster = false)
        val gain = if (target != null) Loudness.normalizationGainDb(raw, fs.toDouble(), target) else 0.0
        val g = dbToGain(gain) * project.sequence.master.volume
        for (i in raw.indices) raw[i] = (raw[i] * g).toFloat()
        Limiter(fs.toDouble(), project.sequence.master.limiterCeilingDb, 80.0).process(raw)
        return raw
    }

    /** Per-track stems (pre-master) for "export stems". */
    fun renderStems(durationSec: Double): Map<String, FloatArray> = project.sequence.tracks.filter { t ->
        t.clips.isNotEmpty() && (t.kind == TrackKind.AUDIO || t.clips.any { project.asset(it.assetId)?.hasAudio == true })
    }.associate { t ->
        val solo = project.copy(sequence = project.sequence.copy(tracks = project.sequence.tracks.map { it.copy(solo = it.id == t.id) }))
        t.name to AudioMixer(solo, sources, fs).mix(0.0, durationSec, applyMaster = false)
    }
}

/** Sidechain envelope (0 = no ducking, 1 = full depth) following dialogue level with attack/release. */
object Ducking {
    fun gainCurve(dialogue: FloatArray, fs: Double, thresholdDb: Double = -40.0, attackMs: Double = 80.0, releaseMs: Double = 400.0): DoubleArray {
        val n = dialogue.size / 2
        val out = DoubleArray(n)
        val att = kotlin.math.exp(-1.0 / (fs * attackMs / 1000)); val rel = kotlin.math.exp(-1.0 / (fs * releaseMs / 1000))
        val rmsCoef = kotlin.math.exp(-1.0 / (fs * 0.05))
        var ms = 0.0; var env = 0.0
        val thr = dbToGain(thresholdDb)
        for (i in 0 until n) {
            val m = (dialogue[2 * i] + dialogue[2 * i + 1]) / 2.0
            ms = rmsCoef * ms + (1 - rmsCoef) * m * m
            val target = if (sqrt(ms) > thr) 1.0 else 0.0
            env = if (target > env) att * env + (1 - att) * target else rel * env + (1 - rel) * target
            out[i] = env
        }
        return out
    }
}
