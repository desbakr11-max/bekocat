package app.bekocat.core.audio

import app.bekocat.core.timeline.Animatable
import app.bekocat.core.timeline.Asset
import app.bekocat.core.timeline.AssetKind
import app.bekocat.core.timeline.AudioSettings
import app.bekocat.core.timeline.Clip
import app.bekocat.core.timeline.CounterIds
import app.bekocat.core.timeline.EffectCatalog
import app.bekocat.core.timeline.Project
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.TimelineEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private const val FS = 48000.0

private fun sine(freq: Double, sec: Double, amp: Double = 0.5): FloatArray {
    val n = (sec * FS).toInt()
    return FloatArray(n * 2) { val f = it / 2; (amp * sin(2 * PI * freq * f / FS)).toFloat() }
}

private fun rmsDb(b: FloatArray, from: Int = 0, to: Int = b.size / 2): Double {
    var s = 0.0
    for (f in from until to) s += (b[2 * f] * b[2 * f]).toDouble()
    return gainToDb(kotlin.math.sqrt(s / (to - from)))
}

/** Dominant frequency by zero crossings of the left channel. */
private fun zeroCrossFreq(b: FloatArray): Double {
    var z = 0
    for (f in 1 until b.size / 2) if ((b[2 * f - 2] < 0) != (b[2 * f] < 0)) z++
    return z / 2.0 / (b.size / 2 / FS)
}

class DspTest {
    @Test fun biquadResponses() {
        val pk = Biquad.peaking(FS, 1000.0, 1.0, 6.0)
        assertEquals(6.0, pk.responseDb(1000.0, FS), 0.01)
        assertEquals(0.0, pk.responseDb(20.0, FS), 0.1)
        val hp = Biquad.highPass(FS, 100.0)
        assertTrue(hp.responseDb(20.0, FS) < -25)
        assertEquals(0.0, hp.responseDb(5000.0, FS), 0.05)
        assertEquals(9.0, Biquad.lowShelf(FS, 200.0, 9.0).responseDb(20.0, FS), 0.3)
    }

    @Test fun fftRoundTrip() {
        val r = Random(1)
        val re = DoubleArray(256) { r.nextDouble() - 0.5 }; val orig = re.copyOf(); val im = DoubleArray(256)
        Fft.transform(re, im); Fft.transform(re, im, inverse = true)
        for (i in orig.indices) assertEquals(orig[i], re[i], 1e-9)
    }

    @Test fun compressorAndLimiter() {
        val loud = sine(440.0, 1.0, 0.9)
        val c = Compressor(FS, -20.0, 4.0, 1.0, 50.0, 0.0, 0.0)
        c.process(loud)
        // Input peak ≈ -0.9 dB: 19.1 dB over → ~14.3 dB reduction at 4:1.
        assertEquals(14.3, c.maxReductionDb, 0.8)
        val hot = sine(440.0, 1.0, 2.0)
        Limiter(FS, -1.0, 50.0).process(hot)
        assertTrue(Loudness.samplePeakDb(hot) <= -0.99)
    }

    @Test fun gateClosesOnNoise() {
        val r = Random(2)
        val quiet = FloatArray(48000 * 2) { (r.nextDouble() * 0.002 - 0.001).toFloat() }
        NoiseGate(FS, -40.0, 1.0, 20.0, -60.0).process(quiet)
        assertTrue(rmsDb(quiet, 24000, 48000) < -100)
    }

    @Test fun loudnessMatchesReference() {
        // 1 kHz sine at -20 dBFS peak on both channels ≈ -20 LUFS (BS.1770 calibration).
        val s = sine(1000.0, 5.0, 0.1)
        assertEquals(-20.0, Loudness.integrated(s, FS)!!, 0.3)
        val g = Loudness.normalizationGainDb(s, FS, -14.0)
        assertEquals(6.0, g, 0.3)
        assertEquals(null, Loudness.integrated(FloatArray(48000 * 2), FS))
    }

    @Test fun denoiseImprovesSnr() {
        val r = Random(3)
        val tone = sine(800.0, 2.0, 0.3)
        val noisy = FloatArray(tone.size) { tone[it] + ((r.nextDouble() - 0.5) * 0.08).toFloat() }
        // Leading 0.5 s of noise only so the profile is measurable.
        for (f in 0 until 24000) { noisy[2 * f] = ((r.nextDouble() - 0.5) * 0.08).toFloat(); noisy[2 * f + 1] = noisy[2 * f] }
        val before = rmsDb(noisy, 0, 24000)
        SpectralDenoise.process(noisy, 20.0, 0.5)
        val after = rmsDb(noisy, 2000, 22000)
        assertTrue("noise floor reduced ${before} → ${after}", after < before - 10)
        val toneAfter = rmsDb(noisy, 48000, 90000)
        assertEquals(rmsDb(tone, 48000, 90000), toneAfter, 1.5)
    }

    @Test fun wsolaKeepsPitchWhenStretching() {
        val src = ArrayPcmSource(sine(440.0, 1.0))
        val out = Wsola.render(src, 96000, FS) { it / 2.0 } // 2× slower
        assertEquals(440.0, zeroCrossFreq(out.copyOfRange(9600, out.size - 9600)), 8.0)
        val fast = Wsola.render(src, 24000, FS) { it * 2.0 }
        assertEquals(440.0, zeroCrossFreq(fast.copyOfRange(4800, fast.size - 4800)), 10.0)
    }

    @Test fun resamplerShiftsPitch() {
        val src = ArrayPcmSource(sine(440.0, 1.0))
        val out = Resampler.render(src, 24000, { it * 2.0 })
        assertEquals(880.0, zeroCrossFreq(out.copyOfRange(100, out.size - 100)), 6.0)
    }

    @Test fun silenceAndBeats() {
        val buf = FloatArray((4 * FS).toInt() * 2)
        val tone = sine(300.0, 1.0)
        System.arraycopy(tone, 0, buf, 0, tone.size)                     // 0–1 s speech
        System.arraycopy(tone, 0, buf, (3 * FS).toInt() * 2, tone.size)  // 3–4 s speech
        val sil = Analysis.silences(buf, FS, -45.0, 0.5, 0.1)
        assertEquals(1, sil.size)
        assertEquals(1.1, sil[0].first, 0.03); assertEquals(2.9, sil[0].second, 0.03)

        // Click track at 120 BPM.
        val clicks = FloatArray((10 * FS).toInt() * 2)
        var t = 0.0
        while (t < 10.0) { val f = (t * FS).toInt(); for (k in 0 until 400) { val v = (sin(2 * PI * 2000 * k / FS) * (1 - k / 400.0)).toFloat(); clicks[2 * (f + k)] = v; clicks[2 * (f + k) + 1] = v }; t += 0.5 }
        val (bpm, beats) = Analysis.beats(clicks, FS)
        assertEquals(120.0, bpm, 3.0)
        assertTrue(beats.size in 16..22)
        assertTrue(beats.zipWithNext { a, b -> b - a }.all { abs(it - 0.5) < 0.05 })
    }

    @Test fun mixerDucksMusicUnderDialogueAndAppliesPan() {
        var p = Project.create("p", "P", 0)
        val assets = listOf(Asset("m", "u", AssetKind.AUDIO, "music", Rational.seconds(4), hasAudio = true),
            Asset("d", "u", AssetKind.AUDIO, "voice", Rational.seconds(2), hasAudio = true))
        p = p.copy(assets = assets)
        val ed = TimelineEditor(assets.associateBy { it.id }, CounterIds())
        var s = ed.append(p.sequence, "a1", Clip("music", "m", "music", Rational.ZERO, Rational.ZERO, Rational.seconds(4)))
        s = ed.overwrite(s, "a2", Clip("voice", "d", "voice", Rational.ZERO, Rational.ZERO, Rational.seconds(2)), Rational.seconds(2))
        p = p.copy(sequence = s)
        val music = sine(220.0, 4.0, 0.3); val voice = sine(1000.0, 2.0, 0.3)
        val mixer = AudioMixer(p, { c -> ArrayPcmSource(if (c.assetId == "m") music else voice) })
        val out = mixer.mix(0.0, 4.0, applyMaster = false)
        // Music alone in first second; during speech the 220 Hz music is ducked by ~12 dB.
        val musicOnly = rmsDb(out, 4800, 48000)
        val bp = Biquad.lowPass(FS, 400.0, 0.7)
        val lowBand = out.copyOf(); bp.processInterleaved(lowBand)
        val duckedMusic = rmsDb(lowBand, (2.8 * FS).toInt(), (3.8 * FS).toInt())
        assertTrue("ducked ${musicOnly} → ${duckedMusic}", duckedMusic < musicOnly - 9)

        // Pan hard left silences the right channel.
        val panned = p.copy(sequence = ed.updateClip(p.sequence, "music") { it.copy(audio = AudioSettings(pan = Animatable.of(-1.0))) })
        val o2 = AudioMixer(panned, { ArrayPcmSource(music) }).mix(0.0, 1.0, applyMaster = false)
        var rightEnergy = 0.0; for (f in 0 until 48000) rightEnergy += abs(o2[2 * f + 1].toDouble())
        assertTrue(rightEnergy < 1e-3)
    }

    @Test fun speedChangeRendersExpectedLength() {
        var p = Project.create("p", "P", 0)
        val a = Asset("m", "u", AssetKind.AUDIO, "music", Rational.seconds(4), hasAudio = true)
        p = p.copy(assets = listOf(a))
        val ed = TimelineEditor(mapOf("m" to a), CounterIds())
        p = p.copy(sequence = ed.append(p.sequence, "a1", Clip("c", "m", "c", Rational.ZERO, Rational.ZERO, Rational.seconds(4), speed = Rational.of(2))))
        val buf = AudioMixer(p, { ArrayPcmSource(sine(440.0, 4.0)) }).renderClip(p.sequence.findClip("c")!!.second)
        assertNotNull(buf)
        assertEquals(96000, buf!!.size / 2)
        assertEquals(440.0, zeroCrossFreq(buf.copyOfRange(9600, buf.size - 9600)), 10.0) // pitch preserved
    }

    @Test fun audioEffectCatalogIsWired() {
        val buf = sine(500.0, 1.0)
        for (spec in EffectCatalog.audio) AudioFx.apply(buf, listOf(spec.instance("x")), FS)
        assertTrue(buf.all { it.isFinite() })
    }
}
