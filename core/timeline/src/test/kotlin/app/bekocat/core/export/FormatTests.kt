package app.bekocat.core.export

import app.bekocat.core.audio.Loudness
import app.bekocat.core.timeline.CaptionSegment
import app.bekocat.core.timeline.CaptionWord
import app.bekocat.core.timeline.ColorMath
import app.bekocat.core.timeline.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FormatTest {
    private val segs = listOf(
        CaptionSegment("1", Rational.ZERO, Rational.of(3, 2), listOf(CaptionWord("مرحبًا", Rational.ZERO, Rational.of(1, 2)), CaptionWord("Bekocat", Rational.of(1, 2), Rational.of(3, 2)))),
        CaptionSegment("2", Rational.of(2), Rational.of(4), listOf(CaptionWord("سطر", Rational.of(2), Rational.of(3)), CaptionWord("ثانٍ", Rational.of(3), Rational.of(4))), translation = "Second line"),
    )

    @Test fun srtVttAssRoundTrip() {
        val srt = CaptionIO.toSrt(segs)
        assertTrue(srt.contains("00:00:01,500"))
        val back = CaptionIO.parse(srt)
        assertEquals(2, back.size)
        assertEquals("مرحبًا Bekocat", back[0].text)
        assertEquals(Rational.of(3, 2), back[0].end)
        val vtt = CaptionIO.toVtt(segs)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("<00:00:00.500>Bekocat"))
        assertEquals("سطر ثانٍ", CaptionIO.parse(vtt)[1].text)
        val ass = CaptionIO.toAss(segs, 1080, 1920)
        assertTrue(ass.contains("{\\k50}مرحبًا"))
        assertEquals(Rational.of(4), CaptionIO.parse(ass)[1].end)
        assertTrue(CaptionIO.toSrt(segs, useTranslation = true).contains("Second line"))
    }

    @Test fun resegmentRespectsLimits() {
        val words = (0 until 20).map { CaptionWord("w$it", Rational.of(it.toLong(), 2), Rational.of(it + 1L, 2)) }
        val out = CaptionIO.resegment(listOf(CaptionSegment("s", Rational.ZERO, Rational.of(10), words)), 6, 40)
        assertTrue(out.all { it.words.size <= 6 })
        assertEquals(20, out.sumOf { it.words.size })
    }

    @Test fun gifIsDecodable() {
        val w = 64; val h = 48
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(bos, w, h, 5)
        repeat(3) { f -> enc.addFrame(IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or (((x * 4 + f * 40) and 0xFF) shl 16) or (((y * 5) and 0xFF) shl 8) or 0x80 }) }
        enc.finish()
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(3, reader.getNumImages(true))
        val img = reader.read(1)
        assertEquals(w, img.width)
        val px = img.getRGB(10, 10)
        assertTrue(abs(((px shr 16) and 0xFF) - ((10 * 4 + 40) and 0xFF)) <= 24)
        assertTrue(abs(((px shr 8) and 0xFF) - 50) <= 24)
    }

    @Test fun wavHeaderAndStreamingLoudness() {
        val fs = 48000
        val pcm = FloatArray(fs * 2 * 4) { val f = it / 2; (0.1 * sin(2 * PI * 1000 * f / fs)).toFloat() }
        val bos = ByteArrayOutputStream(); Wav.write(bos, pcm, fs, 2, 16)
        val b = bos.toByteArray()
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4))
        assertEquals(44 + pcm.size * 2, b.size)
        val m = LoudnessMeter(fs.toDouble())
        var i = 0
        while (i < pcm.size) { val n = minOf(9600, pcm.size - i); m.add(pcm.copyOfRange(i, i + n)); i += n }
        assertEquals(Loudness.integrated(pcm, fs.toDouble())!!, m.integrated()!!, 0.2)
    }

    @Test fun cubeLutAndCurves() {
        val cube = "TITLE \"t\"\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n"
        val lut = ColorMath.parseCube(cube)
        assertEquals(2, lut.size); assertEquals("t", lut.title)
        assertEquals(ColorMath.identity(2).data.toList(), lut.data.toList())
        val id = ColorMath.curveTable(listOf(0.0, 0.0, 1.0, 1.0))
        assertEquals(128, id[128])
        val s = ColorMath.curveTable(listOf(0.0, 0.0, 0.25, 0.15, 0.75, 0.85, 1.0, 1.0))
        assertTrue(s[64] < 64 && s[192] > 192)
        assertTrue((1 until 256).all { s[it] >= s[it - 1] })
    }
}
