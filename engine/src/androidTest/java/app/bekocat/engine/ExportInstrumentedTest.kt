package app.bekocat.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bekocat.core.export.CaptionIO
import app.bekocat.core.timeline.Asset
import app.bekocat.core.timeline.AssetKind
import app.bekocat.core.timeline.CaptionTrack
import app.bekocat.core.timeline.Clip
import app.bekocat.core.timeline.ClipKind
import app.bekocat.core.timeline.CounterIds
import app.bekocat.core.timeline.FrameRate
import app.bekocat.core.timeline.Project
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.RenderPlanner
import app.bekocat.core.timeline.TextStyle
import app.bekocat.core.timeline.TimelineEditor
import app.bekocat.core.timeline.Transition
import app.bekocat.core.timeline.TransitionKind
import app.bekocat.engine.export.ExportKind
import app.bekocat.engine.export.ExportSettings
import app.bekocat.engine.export.Exporter
import app.bekocat.engine.gl.EglCore
import app.bekocat.engine.render.Compositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end engine checks on a device/emulator: decode → GPU composite → encode → mux → verify.
 * Test media (clip1: 6 s testsrc2 + 440 Hz, clip2: 4 s SMPTE bars + 660 Hz) were generated with ffmpeg.
 */
@RunWith(AndroidJUnit4::class)
class ExportInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val out = File(ctx.cacheDir, "test-exports").apply { deleteRecursively(); mkdirs() }

    private fun media(name: String): File = File(ctx.filesDir, name).also { f ->
        if (!f.exists()) testCtx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    private fun project(): Project {
        val a1 = Asset("a1", Uri.fromFile(media("clip1.mp4")).toString(), AssetKind.VIDEO, "clip1", Rational.seconds(6), 1280, 720, true, FrameRate.FPS_30)
        val a2 = Asset("a2", Uri.fromFile(media("clip2.mp4")).toString(), AssetKind.VIDEO, "clip2", Rational.seconds(4), 1280, 720, true, FrameRate.FPS_30)
        var p = Project.create("t", "اختبار", 0, 720, 1280, FrameRate.FPS_30)
        p = p.copy(assets = listOf(a1, a2))
        val ed = TimelineEditor(p.assets.associateBy { it.id }, CounterIds())
        var s = p.sequence
        s = ed.append(s, "v1", Clip("c1", "a1", "clip1", Rational.ZERO, Rational.ZERO, Rational.seconds(3)))
        s = ed.append(s, "v1", Clip("c2", "a2", "clip2", Rational.seconds(3), Rational.ZERO, Rational.seconds(3),
            transitionIn = Transition(TransitionKind.DISSOLVE, Rational.of(1, 2))))
        s = ed.append(s, "t1", Clip("t", null, "title", Rational.ZERO, Rational.ZERO, Rational.seconds(2), kind = ClipKind.TEXT, text = TextStyle("مرحبًا Bekocat")))
        val srt = "1\n00:00:00,500 --> 00:00:02,000\nأهلًا وسهلًا\n\n2\n00:00:02,500 --> 00:00:04,000\nhello world\n"
        s = s.copy(captions = listOf(CaptionTrack("cap", "ar", CaptionIO.parse(srt))))
        return p.copy(sequence = s)
    }

    private fun durationOf(f: File): Pair<Double, Int> {
        val ex = MediaExtractor(); ex.setDataSource(f.path)
        var d = 0L
        for (i in 0 until ex.trackCount) d = maxOf(d, ex.getTrackFormat(i).getLong(MediaFormat.KEY_DURATION))
        val n = ex.trackCount; ex.release()
        return d / 1e6 to n
    }

    @Test fun exportsMp4WithVideoAndAudio() {
        val p = project()
        assertEquals(6.0, p.sequence.duration.toDouble(), 0.01)
        val r = Exporter(ctx).run(p, ExportSettings(width = 432, height = 768, frameRate = FrameRate.FPS_30, videoBitrate = 4_000_000), out) { _, _ -> }
        val (d, tracks) = durationOf(r.file)
        assertEquals(2, tracks)
        assertEquals(6.0, d, 0.15)
        assertEquals(432, r.width); assertEquals(768, r.height)
    }

    @Test fun exportsGifWavSrt() {
        val p = project()
        val ex = Exporter(ctx)
        val gif = ex.run(p, ExportSettings(ExportKind.GIF, 270, 480, FrameRate.FPS_30, endSec = 2.0), out) { _, _ -> }
        assertTrue(gif.file.readBytes().copyOfRange(0, 6).decodeToString() == "GIF89a")
        val wav = ex.run(p, ExportSettings(ExportKind.WAV, 720, 1280, FrameRate.FPS_30), out) { _, _ -> }
        assertEquals(6.0, wav.verifiedDurationSec, 0.05)
        val srt = ex.run(p, ExportSettings(ExportKind.CAPTIONS_SRT, 720, 1280, FrameRate.FPS_30), out) { _, _ -> }
        assertTrue(srt.file.readText().contains("أهلًا وسهلًا"))
    }

    @Test fun compositorRendersFrameWithText() {
        val p = project()
        val egl = EglCore(); val pb = egl.pbuffer(); egl.makeCurrent(pb)
        val comp = Compositor(ctx)
        try {
            val img = comp.render(RenderPlanner.plan(p, Rational.seconds(1)), transparent = false, timeSec = 1.0)
            val bmp = img.toBitmap()
            val plan = RenderPlanner.plan(p, Rational.seconds(1))
            android.util.Log.i("BKTEST", "items=${plan.items.size} ${plan.items.map { it.toString().take(80) }} caps=${plan.captions.size} err=${android.opengl.GLES30.glGetError()}")
            File(ctx.filesDir, "frame.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(720, bmp.width); assertEquals(1280, bmp.height)
            val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val distinct = px.asSequence().map { it and 0xF0F0F0 }.distinct().take(50).count()
            assertTrue("frame looks empty ($distinct colours)", distinct >= 20)
        } finally { comp.release(); egl.releaseSurface(pb); egl.release() }
    }
}
