package app.bekocat.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun sec(s: Long) = Rational.seconds(s)

private fun demoProject(): Project {
    var p = Project.create("p", "demo", 0, 1080, 1920)
    val assets = listOf(
        Asset("v", "file:///v.mp4", AssetKind.VIDEO, "v.mp4", sec(10), 1920, 1080, true, FrameRate.FPS_30),
        Asset("w", "file:///w.mp4", AssetKind.VIDEO, "w.mp4", sec(10), 1080, 1920, true, FrameRate.FPS_30),
        Asset("logo", "file:///logo.png", AssetKind.IMAGE, "logo.png", null, 512, 512),
    )
    p = p.copy(assets = assets)
    val ed = TimelineEditor(assets.associateBy { it.id }, CounterIds())
    var s = p.sequence
    s = ed.append(s, "v1", Clip("a", "v", "A", Rational.ZERO, sec(1), sec(5)))
    s = ed.append(s, "v1", Clip("b", "w", "B", Rational.ZERO, sec(2), sec(6), transitionIn = Transition(TransitionKind.DISSOLVE, sec(1))))
    s = ed.overwrite(s, "o1", Clip("l", "logo", "Logo", Rational.ZERO, Rational.ZERO, sec(8),
        transform = Transform(scale = Animatable.of(0.25), blendMode = BlendMode.SCREEN)), Rational.ZERO)
    s = ed.overwrite(s, "t1", Clip("t", null, "Title", Rational.ZERO, Rational.ZERO, sec(3), kind = ClipKind.TEXT,
        text = TextStyle("مرحبًا Bekocat", animation = TextAnimation(presetIn = "fade-letters"))), Rational.ZERO)
    return p.copy(sequence = s)
}

class PlannerTest {
    @Test fun layersAreOrderedBottomToTop() {
        val plan = RenderPlanner.plan(demoProject(), Rational.ONE)
        val ids = plan.items.map { (it as PlanItem.Layer).layer.clipId }
        assertEquals(listOf("a", "l", "t"), ids)
        val logo = (plan.items[1] as PlanItem.Layer).layer
        assertEquals(BlendMode.SCREEN, logo.blend)
        val v = (plan.items[0] as PlanItem.Layer).layer.source as LayerSource.Video
        assertEquals(sec(2), v.sourceTime) // sourceIn 1 + 1s
    }

    @Test fun transitionWindowUsesHandles() {
        val p = demoProject()
        // Cut at 4s, dissolve 1s centred → window [3.5, 4.5)
        val at = Rational.of(4, 1)
        val plan = RenderPlanner.plan(p, at)
        val tr = plan.items.first() as PlanItem.Trans
        assertEquals(0.5, tr.plan.progress, 1e-6)
        val a = tr.plan.a!!.source as LayerSource.Video
        val b = tr.plan.b!!.source as LayerSource.Video
        assertEquals(sec(5), a.sourceTime)   // A's handle beyond its out-point (source 5s)
        assertEquals(sec(2), b.sourceTime)   // B at its in-point
        assertTrue(!tr.plan.handleMissing)
        val outside = RenderPlanner.plan(p, Rational.of(9, 2))
        assertTrue(outside.items.first() is PlanItem.Layer)
    }

    @Test fun keyframedTransformAndExpression() {
        val p = demoProject()
        val ed = TimelineEditor(p.assets.associateBy { it.id }, CounterIds())
        val s = ed.updateClip(p.sequence, "l") {
            it.copy(transform = it.transform.copy(
                positionX = Animatable(0.0, listOf(Keyframe(Rational.ZERO, -1.0), Keyframe(sec(2), 1.0))),
                rotation = Animatable(0.0, expression = "time * 90"),
            ))
        }
        val plan = RenderPlanner.plan(p.copy(sequence = s), Rational.ONE)
        val l = plan.items.map { (it as PlanItem.Layer).layer }.first { it.clipId == "l" }
        assertEquals(0.0, l.transform.posX, 1e-9)
        assertEquals(90.0, l.transform.rotation, 1e-9)
    }

    @Test fun captionsPageAndHighlightActiveWord() {
        val words = listOf("هذا", "اختبار", "للكابشن", "العربي", "Bekocat").mapIndexed { i, w ->
            CaptionWord(w, Rational.of(i.toLong(), 2), Rational.of(i + 1L, 2))
        }
        val seg = CaptionSegment("s", Rational.ZERO, Rational.of(5, 2), words)
        var p = demoProject()
        p = p.copy(sequence = p.sequence.copy(captions = listOf(CaptionTrack("c", "ar", listOf(seg), stylePreset = "active-word", maxWordsPerLine = 2, maxLines = 1))))
        val f = RenderPlanner.plan(p, Rational.of(5, 4)).captions.single()
        // t = 1.25 → word index 2 active, page of 2 words = [2,3]
        assertEquals(listOf("للكابشن", "العربي"), f.lines.flatten().map { it.text })
        assertTrue(f.lines.flatten().first().active)
        assertEquals(0.5, f.lines.flatten().first().progress, 1e-9)
    }

    @Test fun nestedCompositionIsPlannedRecursively() {
        val p = demoProject()
        val ed = TimelineEditor(p.assets.associateBy { it.id }, CounterIds())
        val (main, comp) = ed.nest(p.sequence, setOf("l", "t"), "Title comp")
        val np = p.copy(sequence = main, compositions = listOf(comp))
        val plan = RenderPlanner.plan(np, Rational.ONE)
        val nested = plan.items.map { (it as PlanItem.Layer).layer.source }.filterIsInstance<LayerSource.Nested>().single()
        assertEquals(2, nested.plan.items.size)
        // Round-trips through the codec.
        assertEquals(np, ProjectCodec.decode(ProjectCodec.encode(np)))
    }

    @Test fun trackMatteHidesSource() {
        val p = demoProject()
        val ed = TimelineEditor(p.assets.associateBy { it.id }, CounterIds())
        val s = ed.updateClip(p.sequence, "a") { it.copy(matte = TrackMatte("t", MatteType.ALPHA)) }
        val plan = RenderPlanner.plan(p.copy(sequence = s), Rational.ONE)
        val layers = plan.items.map { (it as PlanItem.Layer).layer }
        assertEquals(listOf("a", "l"), layers.map { it.clipId })
        assertNotNull(layers[0].matte)
    }
}

class GeometryTest {
    @Test fun identityFitMapsToCentredRect() {
        // 1920x1080 content fitted into 1080x1920 output → 1080 x 607.5 centred.
        val c = Geometry.corners(TransformFrame(), emptyList(), 1920, 1080, 1080, 1920, null)
        assertEquals(0.0, c[0], 1e-6); assertEquals((1920 - 607.5) / 2, c[1], 1e-6)
        assertEquals(1080.0, c[2], 1e-6)
        val h = Geometry.homography(c)
        val (x, y) = Geometry.apply(h, 0.5, 0.5)
        assertEquals(540.0, x, 1e-6); assertEquals(960.0, y, 1e-6)
        val inv = Geometry.invert3(h)
        val (u, v) = Geometry.apply(inv, 1080.0, 1920.0 / 2)
        assertEquals(1.0, u, 1e-9); assertEquals(0.5, v, 1e-9)
    }

    @Test fun rotationPositionAndPerspective() {
        val f = TransformFrame(posX = 0.5, rotation = 90.0)
        val c = Geometry.corners(f, emptyList(), 100, 100, 1000, 1000, null)
        // Fit 100→1000 then rotate 90° around centre, shift +250 px.
        assertEquals(750.0 + 500, c[0], 1e-6)   // TL (-500,-500) → (500,-500) → x = 500+250+500
        val tilted = Geometry.corners(TransformFrame(rotY = 30.0), emptyList(), 100, 100, 1000, 1000, null)
        val h = Geometry.homography(tilted)
        // Perspective makes the right edge shorter than the left edge.
        val left = tilted[7] - tilted[1]; val right = tilted[5] - tilted[3]
        assertTrue(right > left) // positive Y rotation brings the right edge closer
        val (cx, cy) = Geometry.apply(h, 0.5, 0.5)
        assertEquals(500.0, cy, 1e-6)
        assertTrue(cx > 0)
    }
}

class ExpressionTest {
    @Test fun arithmeticAndFunctions() {
        assertEquals(7.0, Expression.evaluate("1 + 2 * 3", 0.0, 0.0), 1e-12)
        assertEquals(9.0, Expression.evaluate("(1 + 2) ^ 2", 0.0, 0.0), 1e-12)
        assertEquals(5.0, Expression.evaluate("clamp(value, 0, 5)", 12.0, 0.0), 1e-12)
        assertEquals(1.0, Expression.evaluate("sin(pi/2)", 0.0, 0.0), 1e-12)
    }

    @Test fun wiggleIsDeterministicAndBounded() {
        val a = Expression.evaluate("wiggle(3, 10, 42)", 100.0, 1.37)
        val b = Expression.evaluate("wiggle(3, 10, 42)", 100.0, 1.37)
        assertEquals(a, b, 0.0)
        assertTrue(a in 90.0..110.0)
        assertTrue(Expression.evaluate("wiggle(3, 10, 43)", 100.0, 1.37) != a)
    }

    @Test fun loopCycleRepeatsKeyframes() {
        val prop = Animatable(0.0, listOf(Keyframe(Rational.ZERO, 0.0), Keyframe(Rational.ONE, 10.0)), "loopCycle()")
        assertEquals(5.0, prop.valueAt(Rational.of(5, 2)), 1e-6)
    }

    @Test fun rejectsUnsafeInput() {
        for (bad in listOf("java.lang.System.exit(0)", "eval('x')", "x = 1", "while(1)", "(".repeat(100) + "1" + ")".repeat(100), "1".repeat(600))) {
            assertNotNull(bad, Expression.validate(bad))
        }
        // Bad expression never breaks rendering: falls back to the keyframed value.
        assertEquals(3.0, Animatable(3.0, expression = "unknown(1)").valueAt(Rational.ZERO), 0.0)
    }
}

class TextAnimatorTest {
    @Test fun arabicGraphemesKeepDiacritics() {
        val units = TextAnimator.units("مَرْحَبًا 👋🏽", TextUnit.GRAPHEME)
        // م َ  ر ْ  ح َ  ب ً  ا  👋🏽 → 6 clusters (diacritics stay attached, emoji+modifier is one)
        assertEquals(6, units.size)
        assertEquals("مَ", "مَرْحَبًا 👋🏽".substring(units[0]))
        assertEquals("👋🏽", "مَرْحَبًا 👋🏽".substring(units[5]))
    }

    @Test fun typewriterAndStagger() {
        val anim = TextAnimation(presetIn = "typewriter", stagger = Rational.of(1, 10))
        val s = TextAnimator.states("abcd", anim, 0.25, 3.0)
        assertEquals(listOf(true, true, true, false), s.map { it.visible })
        val fade = TextAnimator.states("ab", TextAnimation(presetIn = "fade-letters", durationIn = Rational.ONE, stagger = Rational.ONE), 0.0, 5.0)
        assertEquals(0.0, fade[0].alpha, 1e-9)
        val done = TextAnimator.states("ab", TextAnimation(presetIn = "fade-letters", durationIn = Rational.ONE, stagger = Rational.ONE), 2.5, 5.0)
        assertEquals(listOf(1.0, 1.0), done.map { it.alpha })
    }

    @Test fun outAndLoopPresets() {
        val out = TextAnimator.states("one two", TextAnimation(presetOut = "fade-out", durationOut = Rational.ONE), 2.9, 3.0)
        assertTrue(out.all { it.alpha < 0.2 })
        val k = TextAnimator.states("a b c d", TextAnimation(presetLoop = "karaoke"), 1.5, 4.0)
        assertEquals(listOf(1.0, 0.5, 0.0, 0.0), k.map { it.highlight })
    }

    @Test fun catalogsMeetMinimumCounts() {
        assertTrue(TextPresetCatalog.all.size >= 40)
        assertTrue(CaptionStyleCatalog.all.size >= 20)
        assertTrue(LookCatalog.all.size >= 50)
        assertTrue(TransitionKind.entries.size >= 30)
        assertEquals(TransitionKind.entries.size, TransitionCatalog.names.size)
        assertEquals(TextPresetCatalog.all.size, TextPresetCatalog.all.map { it.id }.toSet().size)
        assertEquals(LookCatalog.all.size, LookCatalog.all.map { it.id }.toSet().size)
        // Every look references only real effects with valid params.
        for (l in LookCatalog.all) for ((type, params) in l.effects) {
            val spec = EffectCatalog.find(type) ?: run { fail("unknown effect $type in ${l.id}"); return }
            for (k in params.keys) assertTrue("${l.id}.$type.$k", spec.params.any { it.key == k })
        }
        // Looks are distinct (no renamed duplicates).
        assertEquals(LookCatalog.all.size, LookCatalog.all.map { it.color to it.effects }.toSet().size)
    }
}

class AdvancedEditTest {
    private val assets = mapOf("a" to Asset("a", "u", AssetKind.VIDEO, "a", sec(10)))
    private fun seq(vararg clips: Clip) = Project.create("p", "P", 0).sequence.let { s ->
        s.copy(tracks = s.tracks.map { if (it.id == "v1") it.copy(clips = clips.toList()) else it })
    }
    private fun ed() = TimelineEditor(assets, CounterIds())

    @Test fun speedRampUsesWholeSourceAndIsContinuous() {
        val s = ed().speedRamp(seq(Clip("c", "a", "c", Rational.ZERO, Rational.ZERO, sec(4))), "c", listOf(0.0 to 1.0, 1.0 to 4.0, 2.0 to 0.5))
        val c = s.findClip("c")!!.second
        assertNotNull(c.timeRemap)
        assertEquals(0.0, c.sourceTimeAt(Rational.ZERO).toDouble(), 1e-3)
        assertEquals(4.0, c.sourceTimeAt(c.timelineEnd - Rational.of(1, 1_000_000)).toDouble(), 0.02)
        // Monotonic source time
        var last = -1.0
        for (i in 0..100) { val v = c.sourceTimeAt(c.duration * Rational.of(i.toLong(), 101)).toDouble(); assertTrue(v >= last - 1e-9); last = v }
        // Split keeps continuity across the cut.
        val e = ed()
        val mid = c.timelineStart + c.duration / Rational.of(2)
        val sp = e.split(s, "c", mid)
        val (l, r) = sp.track("v1")!!.clips
        assertEquals(l.sourceTimeAt(mid - Rational.of(1, 1000)).toDouble(), r.sourceTimeAt(mid).toDouble(), 0.02)
    }

    @Test fun freezeFrameInsertsHold() {
        val s = ed().freezeFrame(seq(Clip("c", "a", "c", Rational.ZERO, Rational.ZERO, sec(4))), "c", sec(2), sec(1))
        val clips = s.track("v1")!!.clips
        assertEquals(3, clips.size)
        val hold = clips[1]
        assertEquals(sec(2), hold.sourceTimeAt(sec(2)))
        assertEquals(sec(2), hold.sourceTimeAt(Rational.of(5, 2)))
        assertEquals(sec(5), s.duration)
    }

    @Test fun groupsMoveTogetherAndPasteAttributes() {
        val e = ed()
        var s = seq(Clip("c1", "a", "1", Rational.ZERO, Rational.ZERO, sec(1)), Clip("c2", "a", "2", sec(2), Rational.ZERO, sec(1)))
        s = e.group(s, listOf("c1", "c2"))
        s = e.moveMany(s, listOf("c1"), sec(3))
        assertEquals(listOf(sec(3), sec(5)), s.track("v1")!!.clips.map { it.timelineStart })
        s = e.updateClip(s, "c1") { it.copy(color = ColorAdjust(contrast = 0.3)) }
        s = e.pasteAttributes(s, "c1", listOf("c2"))
        assertEquals(0.3, s.findClip("c2")!!.second.color.contrast, 0.0)
    }

    @Test fun replaceKeepsTimingAndSnapFindsEdges() {
        val e = ed()
        var s = seq(Clip("c1", "a", "1", Rational.ZERO, Rational.ZERO, sec(3), color = ColorAdjust(saturation = 0.5)))
        s = e.replaceMedia(s, "c1", Asset("b", "u2", AssetKind.VIDEO, "b", sec(10)), keepAttributes = true)
        val c = s.findClip("c1")!!.second
        assertEquals("b", c.assetId); assertEquals(sec(3), c.duration); assertEquals(0.5, c.color.saturation, 0.0)
        assertEquals(sec(3), e.snap(s, Rational.of(301, 100), Rational.of(1, 10), null))
    }

    @Test fun detachAudioMutesVideo() {
        val s = ed().detachAudio(seq(Clip("c1", "a", "1", Rational.ZERO, Rational.ZERO, sec(3))), "c1", "a1")
        assertTrue(s.findClip("c1")!!.second.audio.muted)
        assertEquals(1, s.track("a1")!!.clips.size)
        assertNull(s.track("a1")!!.clips[0].transitionIn)
    }
}

class AssistantTest {
    private val p = demoProjectForAssistant()
    private fun demoProjectForAssistant(): Project {
        var pr = Project.create("p", "P", 0, 1920, 1080)
        val ed = TimelineEditor(emptyMap(), CounterIds())
        var s = ed.append(pr.sequence, "v1", Clip("c1", null, "1", Rational.ZERO, Rational.ZERO, Rational.seconds(6)))
        s = ed.append(s, "a1", Clip("m1", null, "music", Rational.ZERO, Rational.ZERO, Rational.seconds(6)))
        return pr.copy(sequence = s)
    }
    private fun plan(t: String) = AssistantParser.parse(t, p.sequence, null, Rational.ZERO)!!

    @Test fun specExampleReel() {
        val pl = plan("سوّ لي ريلز عمودي من هذه المقاطع، احذف الصمت، أضف كابشن عربي بكلمات بارزة، خفّض الموسيقى أثناء الكلام، وحط لون سينمائي دافئ وانتقالات ناعمة بدون مبالغة.")
        val types = pl.intents.map { it::class.simpleName }
        assertEquals(listOf("Aspect", "RemoveSilence", "Captions", "DuckMusic", "Look", "Transitions"), types)
        assertEquals(Intent.Captions("ar", "active-word"), pl.intents[2])
        assertEquals("warm-cinema", (pl.intents[4] as Intent.Look).lookId)
        assertEquals(0.4, (pl.intents[5] as Intent.Transitions).duration, 0.0)
    }

    @Test fun specExamplesOthers() {
        assertEquals(listOf(Intent.IsolateVocals("accompaniment")), plan("اعزل الغناء وخلّ الموسيقى لوحدها").intents)
        assertEquals(listOf(Intent.RemoveBackground(true)), plan("شيل الخلفية وخلي هذا الفيديو خلف الشخص").intents)
        assertEquals(listOf(Intent.AnimateIn("bottom", "back", "logo")), plan("حرّك الشعار من أسفل مع ارتداد خفيف").intents)
        val cc = plan("غيّر لون الكابشن فقط وخلي توقيته مثل ما هو")
        assertEquals(listOf(Intent.CaptionColor(null)), cc.intents); assertNotNull(cc.question); assertTrue(cc.alternatives.isNotEmpty())
        assertTrue(plan("سوّ تقريبًا تدريجيًا من الثانية 2 إلى 5").intents.single().let { it is Intent.Edit && it.op is Operation.ZoomRamp })
        assertEquals(listOf(Intent.CopyAnimationToTitles(0.25)), plan("طبّق نفس الحركة على العناوين بتأخير ربع ثانية").intents)
        assertEquals(listOf(Intent.Undo), plan("ارجع خطوة بدون حذف بقية تعديلات المشروع").intents)
    }

    @Test fun llmJsonIsValidatedAndBounded() {
        val ok = AssistantParser.fromJson("""Sure! {"intents":[{"type":"aspect","w":1081,"h":1920},{"type":"look","id":"warm-cinema"},{"type":"shell","cmd":"rm -rf /"},{"type":"look","id":"evil"}],"question":null}""", p.sequence, "c1")!!
        assertEquals(listOf(Intent.Aspect(1080, 1920), Intent.Look("warm-cinema")), ok.intents)
        assertNull(AssistantParser.fromJson("no json here", p.sequence, null))
    }

    @Test fun animateInAndCopyToTitles() {
        val c = AssistantParser.animateIn(Clip("x", null, "logo", Rational.ZERO, Rational.ZERO, Rational.seconds(3)), "bottom", "back")
        assertEquals(1.6, c.transform.positionY.valueAt(Rational.ZERO), 1e-9)
        assertEquals(0.0, c.transform.positionY.valueAt(Rational.seconds(2)), 1e-9)
        val ed = TimelineEditor(emptyMap(), CounterIds())
        var s = p.sequence
        s = ed.overwrite(s, "t1", Clip("t1c", null, "a", Rational.ZERO, Rational.ZERO, Rational.ONE, kind = ClipKind.TEXT, text = TextStyle("a")), Rational.ZERO)
        s = ed.overwrite(s, "o1", Clip("t2c", null, "b", Rational.ZERO, Rational.ZERO, Rational.ONE, kind = ClipKind.TEXT, text = TextStyle("b")), Rational.ZERO)
        s = ed.updateClip(s, "t1c") { it.copy(transform = c.transform) }
        val out = AssistantParser.copyAnimationToTitles(s, "t1c", 0.25, ed)
        assertEquals(Rational.of(1, 4), out.findClip("t2c")!!.second.transform.positionY.keyframes.first().time)
    }
}
