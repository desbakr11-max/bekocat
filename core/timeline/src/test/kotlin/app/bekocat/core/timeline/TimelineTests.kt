package app.bekocat.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RationalTimeTest {
    @Test fun arithmeticIsExact() {
        val a = Rational.of(1, 3)
        assertEquals(Rational.ONE, a + a + a)
        assertEquals(Rational.of(1, 6), Rational.of(1, 2) - Rational.of(1, 3))
        assertEquals(Rational.of(2, 1), Rational.of(4, 3) / Rational.of(2, 3))
        assertTrue(Rational.of(1001, 24000) < Rational.of(1, 23))
    }

    @Test fun ntscFrameRatesDoNotDrift() {
        val r = FrameRate.FPS_23_976
        val oneHourFrames = r.frameAt(Rational.seconds(3600))
        assertEquals(86313L, oneHourFrames) // 3600 * 24000/1001 = 86313.68
        // Frame N always maps back to frame N, even after hours.
        for (f in listOf(0L, 1L, 1000L, 86313L, 2_000_000L)) assertEquals(f, r.frameAt(r.timeOf(f)))
        // Summing 10,000 frame durations equals the exact time of frame 10,000.
        var t = Rational.ZERO
        repeat(10_000) { t += r.frameDuration }
        assertEquals(r.timeOf(10_000), t)
        assertEquals(FrameRate.FPS_29_97.timeOf(30_000), Rational.seconds(1001))
    }

    @Test fun snapAndTimecode() {
        val r = FrameRate.FPS_30
        assertEquals(Rational.of(1, 30), r.snap(Rational.of(1, 25)))
        assertEquals("00:00:01:15", r.timecode(Rational.of(3, 2)))
        assertEquals(1_500_000L, Rational.of(3, 2).toMicros())
        assertEquals(Rational.of(24000, 1001), Rational.parse("24000/1001"))
    }

    @Test fun compareHandlesLargeValues() {
        val big = Rational.of(Long.MAX_VALUE / 3, 1_000_000_007)
        val bigger = Rational.of(Long.MAX_VALUE / 3 + 1, 1_000_000_007)
        assertTrue(big < bigger)
        assertTrue(Rational.of(-5, 2) < Rational.ZERO)
    }
}

class KeyframeTest {
    @Test fun linearHoldAndBezier() {
        val lin = Animatable(0.0, listOf(Keyframe(Rational.ZERO, 0.0), Keyframe(Rational.seconds(2), 10.0)))
        assertEquals(5.0, lin.valueAt(Rational.ONE), 1e-9)
        assertEquals(0.0, lin.valueAt(-Rational.ONE), 1e-9)
        assertEquals(10.0, lin.valueAt(Rational.seconds(5)), 1e-9)

        val hold = Animatable(0.0, listOf(Keyframe(Rational.ZERO, 1.0, Interpolation.HOLD), Keyframe(Rational.ONE, 3.0)))
        assertEquals(1.0, hold.valueAt(Rational.of(99, 100)), 1e-9)
        assertEquals(3.0, hold.valueAt(Rational.ONE), 1e-9)

        val ease = Animatable(0.0, listOf(Keyframe(Rational.ZERO, 0.0, Interpolation.BEZIER), Keyframe(Rational.ONE, 1.0)))
        val mid = ease.valueAt(Rational.of(1, 2))
        assertEquals(0.5, mid, 1e-6) // easy-ease is symmetric
        assertTrue(ease.valueAt(Rational.of(1, 10)) < 0.1) // slow start
    }

    @Test fun cubicBezierMatchesKnownCurve() {
        // CSS "ease" at x=0.5 ≈ 0.8024
        assertEquals(0.8024, CubicBezier.EASE.solve(0.5), 1e-3)
        assertEquals(0.0, CubicBezier.EASE_IN.solve(0.0), 1e-9)
        assertEquals(1.0, CubicBezier.EASE_IN.solve(1.0), 1e-9)
    }
}

class EditOpsTest {
    private val assets = mapOf("a" to Asset("a", "content://a", AssetKind.VIDEO, "a.mp4", Rational.seconds(10)))
    private fun clip(id: String, start: Long, inS: Long, outS: Long) =
        Clip(id, "a", id, Rational.seconds(start), Rational.seconds(inS), Rational.seconds(outS))

    private fun seq(vararg clips: Clip) = Project.create("p", "P", 0).sequence.let { s ->
        s.copy(tracks = s.tracks.map { if (it.id == "v1") it.copy(clips = clips.toList()) else it })
    }

    private fun v1(s: Sequence) = s.track("v1")!!.clips
    private fun ed() = TimelineEditor(assets, CounterIds())

    @Test fun splitProducesContiguousClips() {
        val s = ed().split(seq(clip("c1", 0, 0, 4)), "c1", Rational.of(3, 2))
        val c = v1(s)
        assertEquals(2, c.size)
        assertEquals(Rational.of(3, 2), c[0].sourceOut)
        assertEquals(Rational.of(3, 2), c[1].sourceIn)
        assertEquals(c[0].timelineEnd, c[1].timelineStart)
        assertEquals(Rational.seconds(4), s.duration)
    }

    @Test fun rippleDeleteClosesGap() {
        val s = ed().delete(seq(clip("c1", 0, 0, 2), clip("c2", 2, 0, 3), clip("c3", 5, 0, 1)), setOf("c2"), ripple = true)
        assertEquals(listOf("c1", "c3"), v1(s).map { it.id })
        assertEquals(Rational.seconds(2), v1(s)[1].timelineStart)
        val lift = ed().delete(seq(clip("c1", 0, 0, 2), clip("c2", 2, 0, 3), clip("c3", 5, 0, 1)), setOf("c2"), ripple = false)
        assertEquals(Rational.seconds(5), v1(lift)[1].timelineStart)
    }

    @Test fun insertSplitsAndRipples() {
        val s = ed().insert(seq(clip("c1", 0, 0, 4)), "v1", clip("n", 0, 0, 2), Rational.ONE)
        val c = v1(s)
        assertEquals(listOf(Rational.ZERO, Rational.ONE, Rational.seconds(3)), c.map { it.timelineStart })
        assertEquals(Rational.seconds(6), s.duration)
    }

    @Test fun overwriteReplacesRange() {
        val s = ed().overwrite(seq(clip("c1", 0, 0, 4)), "v1", clip("n", 0, 5, 6), Rational.ONE)
        val c = v1(s)
        assertEquals(3, c.size)
        assertEquals("n", c[1].id)
        assertEquals(Rational.seconds(4), s.duration)
    }

    @Test fun trimRippleAndBounds() {
        val e = ed()
        val s = e.trimEnd(seq(clip("c1", 0, 0, 4), clip("c2", 4, 0, 2)), "c1", Rational.seconds(3), ripple = true)
        assertEquals(Rational.seconds(3), v1(s)[1].timelineStart)
        try { e.trimEnd(seq(clip("c1", 0, 0, 4)), "c1", Rational.seconds(11), ripple = true); fail() } catch (_: EditException) {}
        val ts = e.trimStart(seq(clip("c1", 0, 0, 4), clip("c2", 4, 0, 2)), "c1", Rational.ONE, ripple = true)
        assertEquals(Rational.ZERO, v1(ts)[0].timelineStart)
        assertEquals(Rational.ONE, v1(ts)[0].sourceIn)
        assertEquals(Rational.seconds(3), v1(ts)[1].timelineStart)
    }

    @Test fun slipSlideRoll() {
        val e = ed()
        val base = seq(clip("c1", 0, 0, 3), clip("c2", 3, 2, 5), clip("c3", 6, 0, 3))
        val slipped = e.slip(base, "c2", Rational.ONE)
        assertEquals(Rational.seconds(3), v1(slipped)[1].sourceIn)
        assertEquals(Rational.seconds(3), v1(slipped)[1].timelineStart)

        val rolled = e.roll(base, "c1", Rational.ONE)
        assertEquals(Rational.seconds(4), v1(rolled)[0].timelineEnd)
        assertEquals(Rational.seconds(4), v1(rolled)[1].timelineStart)
        assertEquals(base.duration, rolled.duration)

        val slid = e.slide(base, "c2", Rational.ONE)
        assertEquals(Rational.seconds(4), v1(slid)[0].timelineEnd)
        assertEquals(Rational.seconds(4), v1(slid)[1].timelineStart)
        assertEquals(Rational.seconds(7), v1(slid)[2].timelineStart)
        assertEquals(base.duration, slid.duration)
    }

    @Test fun speedChangesDurationAndRipples() {
        val s = ed().setSpeed(seq(clip("c1", 0, 0, 4), clip("c2", 4, 0, 1)), "c1", Rational.of(2))
        assertEquals(Rational.seconds(2), v1(s)[0].duration)
        assertEquals(Rational.seconds(2), v1(s)[1].timelineStart)
        assertEquals(Rational.seconds(3), v1(s)[0].sourceTimeAt(Rational.of(3, 2)))
    }

    @Test fun overlapIsRejectedAndLockRespected() {
        val e = ed()
        try { e.move(seq(clip("c1", 0, 0, 4), clip("c2", 4, 0, 2)), "c2", Rational.seconds(3)); fail() } catch (_: EditException) {}
        val locked = seq(clip("c1", 0, 0, 4)).let { s -> s.copy(tracks = s.tracks.map { if (it.id == "v1") it.copy(locked = true) else it }) }
        try { e.split(locked, "c1", Rational.ONE); fail() } catch (_: EditException) {}
    }
}

class HistoryAndCodecTest {
    @Test fun undoRedoIsTransactional() {
        val p = Project.create("p1", "Demo", 1)
        val h = History(p)
        h.commit("rename", 2) { it.copy(name = "A") }
        h.commit("rename", 3) { it.copy(name = "B") }
        assertEquals("B", h.current.name)
        assertEquals("A", h.undo()!!.name)
        assertEquals("B", h.redo()!!.name)
        h.undo(); h.commit("rename", 4) { it.copy(name = "C") }
        assertFalse(h.canRedo)
        assertNull(History(p).undo())
    }

    @Test fun roundTripPreservesEverything() {
        val ed = TimelineEditor(emptyMap(), CounterIds())
        var p = Project.create("p1", "مشروع تجريبي \"quoted\"", 10, rate = FrameRate.FPS_29_97)
        p = p.copy(assets = listOf(Asset("a", "content://media/1", AssetKind.VIDEO, "فيديو.mp4", Rational.of(30030, 1000), 1920, 1080, true, FrameRate.FPS_29_97)))
        val c = Clip("c1", "a", "Clip", Rational.ZERO, Rational.of(1001, 30000), Rational.seconds(5), speed = Rational.of(3, 2),
            transform = Transform(scale = Animatable(1.0).withKeyframe(Keyframe(Rational.ZERO, 1.0, Interpolation.BEZIER)).withKeyframe(Keyframe(Rational.ONE, 1.3))),
            color = ColorAdjust(contrast = 0.2), transitionIn = Transition(TransitionKind.DISSOLVE, Rational.of(1, 2)),
            text = TextStyle("مرحبا Bekocat 2027", rtl = true))
        p = p.copy(sequence = ed.append(p.sequence, "v1", c))
        val json = ProjectCodec.encode(p)
        val back = ProjectCodec.decode(json)
        assertEquals(p, back)
    }

    @Test fun migratesV1DecimalSpeed() {
        val v1 = """{"schemaVersion":1,"id":"p","name":"old","assets":[],"sequence":{"id":"s","width":1080,"height":1920,"frameRate":"30/1",
            "tracks":[{"id":"v1","kind":"VIDEO","clips":[{"id":"c","assetId":null,"timelineStart":"0","sourceIn":"0","sourceOut":"2","speed":1.5}]}]}}"""
        val p = ProjectCodec.decode(v1)
        assertEquals(Rational.of(3, 2), p.sequence.track("v1")!!.clips[0].speed)
        try { ProjectCodec.decode("""{"schemaVersion":999,"id":"p","name":"x","sequence":{}}"""); fail() } catch (_: JsonException) {}
    }

    @Test fun rejectsMaliciousJson() {
        val deep = "[".repeat(200) + "]".repeat(200)
        try { Json.parse(deep); fail() } catch (_: JsonException) {}
        try { Json.parse("{\"a\":1} trailing"); fail() } catch (_: JsonException) {}
    }

    @Test fun storeIsAtomicWithBackup() {
        val dir = createTempDir()
        try {
            val store = ProjectStore(dir)
            val p = Project.create("p1", "One", 1)
            store.save(p)
            store.save(p.copy(name = "Two", modifiedAtMs = 2))
            java.io.File(dir, "p1/project.json").writeText("{corrupt")
            assertEquals("One", store.load("p1").name) // falls back to last good backup
            try { store.dirOf("../escape"); fail() } catch (_: IllegalArgumentException) {}
        } finally { dir.deleteRecursively() }
    }

    @Suppress("DEPRECATION")
    private fun createTempDir() = kotlin.io.createTempDir("bekocat")
}

class CommandParserTest {
    private fun project(): Project {
        val ed = TimelineEditor(emptyMap(), CounterIds())
        var p = Project.create("p", "P", 0)
        var s = p.sequence
        s = ed.append(s, "v1", Clip("c1", null, "1", Rational.ZERO, Rational.ZERO, Rational.seconds(6)))
        s = ed.append(s, "v1", Clip("c2", null, "2", Rational.ZERO, Rational.ZERO, Rational.seconds(4)))
        s = ed.append(s, "a1", Clip("m1", null, "music", Rational.ZERO, Rational.ZERO, Rational.seconds(10)))
        return p.copy(sequence = s)
    }

    @Test fun arabicCommandBecomesUndoableTransaction() {
        val p = project()
        val plan = DirectCommandParser.parse("حط لون سينمائي دافئ وانتقالات ناعمة وخفّض صوت الموسيقى", p.sequence, null, Rational.ZERO)
        assertNotNull(plan)
        val h = History(p)
        h.commit("AI", 1) { PlanApplier.apply(it, plan!!, CounterIds()) }
        val c1 = h.current.sequence.findClip("c1")!!.second
        assertTrue(c1.color.temperature > 0)
        assertNotNull(h.current.sequence.findClip("c2")!!.second.transitionIn)
        assertEquals(0.3, h.current.sequence.findClip("m1")!!.second.volume.base, 1e-9)
        h.undo()
        assertEquals(p, h.current)
    }

    @Test fun zoomRampWithArabicDigits() {
        val p = project()
        val plan = DirectCommandParser.parse("سوّ تقريبًا تدريجيًا من الثانية ٢ إلى ٥", p.sequence, "c1", Rational.ZERO)!!
        val out = PlanApplier.apply(p, plan, CounterIds())
        val sc = out.sequence.findClip("c1")!!.second.transform.scale
        assertEquals(2, sc.keyframes.size)
        assertEquals(1.25, sc.valueAt(Rational.seconds(5)), 1e-9)
    }

    @Test fun unknownCommandFallsThrough() {
        assertNull(DirectCommandParser.parse("اكتب لي قصيدة", project().sequence, null, Rational.ZERO))
    }
}
