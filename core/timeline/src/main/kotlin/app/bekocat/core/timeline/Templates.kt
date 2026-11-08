package app.bekocat.core.timeline

/**
 * Original layered project templates. Every template is a real editable project:
 * media placeholders (replace keeping timing), text layers with animation presets,
 * shape layers, transitions and a colour look — never a flattened video.
 */
data class TemplateInfo(val id: String, val ar: String, val en: String, val category: String, val width: Int, val height: Int)

object TemplateCatalog {

    private class B(val id: String, val w: Int, val h: Int, val fps: FrameRate = FrameRate.FPS_30) {
        val ids = CounterIds()
        val clips = HashMap<String, MutableList<Clip>>()
        var look: LookPreset? = null
        var name = ""
        /** Adds a clip; if it overlaps something on that track it spills onto an extra track of the same kind. */
        fun at(track: String, c: Clip) {
            var tid = track
            while (clips[tid]?.any { it.timelineStart < c.timelineEnd && c.timelineStart < it.timelineEnd } == true) tid += "+"
            clips.getOrPut(tid) { mutableListOf() } += c
        }

        /** Media placeholder slots on V1 (solid colour until replaced). */
        fun slots(vararg secs: Double, transition: TransitionKind? = TransitionKind.DISSOLVE, startAt: Double = 0.0) {
            var t = startAt
            secs.forEachIndexed { i, d ->
                at("v1", Clip(ids.next("slot"), null, "placeholder:${i + 1}", Rational.fromDouble(t, 1000), Rational.ZERO, Rational.fromDouble(d, 1000),
                    kind = ClipKind.SOLID, solidArgb = listOf(0xFF2A2F3D, 0xFF3A3150, 0xFF24423C, 0xFF4A3A2A)[i % 4],
                    transitionIn = if (i > 0 && transition != null) Transition(transition, Rational.of(1, 2)) else null,
                    transform = Transform(fit = FitMode.FILL)))
                t += d
            }
        }

        fun text(s: String, start: Double, dur: Double, y: Double = 0.0, size: Double = 64.0, preset: String? = "fade-letters", out: String? = "fade-out",
                 loop: String? = null, weight: Int = 800, color: Long = 0xFFF4F7FB, bg: Long = 0L, stroke: Double = 0.0, align: String = "center",
                 track: String = "t1", x: Double = 0.0, highlight: List<Int> = emptyList()) {
            at(track, Clip(ids.next("text"), null, s.take(24), Rational.fromDouble(start, 1000), Rational.ZERO, Rational.fromDouble(dur, 1000), kind = ClipKind.TEXT,
                text = TextStyle(s, sizeSp = size, weight = weight, colorArgb = color, backgroundArgb = bg, strokeArgb = if (stroke > 0) 0xFF000000 else 0L,
                    strokeWidth = stroke, alignment = align, highlightWords = highlight,
                    animation = TextAnimation(presetIn = preset, presetOut = out, presetLoop = loop)),
                transform = Transform(positionX = Animatable.of(x), positionY = Animatable.of(y))))
        }

        fun shape(type: ShapeType, start: Double, dur: Double, w: Double, h: Double, color: Long, y: Double = 0.0, x: Double = 0.0,
                  trimAnim: Boolean = false, track: String = "o1", stroke: Double = 0.0, strokeColor: Long = 0L, corner: Double = 0.0) {
            val trim = if (trimAnim) Animatable(1.0, listOf(Keyframe(Rational.ZERO, 0.0, Interpolation.BEZIER), Keyframe(Rational.of(3, 4), 1.0))) else Animatable.of(1.0)
            at(track, Clip(ids.next("shape"), null, type.name.lowercase(), Rational.fromDouble(start, 1000), Rational.ZERO, Rational.fromDouble(dur, 1000), kind = ClipKind.SHAPE,
                shape = ShapeStyle(type, width = Animatable.of(w), height = Animatable.of(h), fillArgb = color, strokeArgb = strokeColor,
                    strokeWidth = Animatable.of(stroke), trimEnd = trim, cornerRadius = Animatable.of(corner)),
                transform = Transform(positionX = Animatable.of(x), positionY = Animatable.of(y))))
        }

        fun build(projectId: String, now: Long): Project {
            var p = Project.create(projectId, name, now, w, h, fps)
            val base = p.sequence.tracks
            val extra = clips.keys.filter { k -> base.none { it.id == k } }.sorted().map { k ->
                val parent = base.first { it.id == k.trimEnd('+') }
                Track(k.replace("+", "x"), parent.kind, parent.name + "·" + k.count { it == '+' })
            }
            // Extra text/overlay tracks are inserted right above their parent track.
            val ordered = ArrayList<Track>()
            for (t in base) { ordered += t; ordered += extra.filter { it.id.trimEnd('x') == t.id } }
            val tracks = ordered.map { t -> t.copy(clips = (clips[t.id.replace("x", "+")] ?: emptyList<Clip>()).sortedBy { it.timelineStart }) }
            val styled = tracks.map { t ->
                if (t.id != "v1" || look == null) t else t.copy(clips = t.clips.map { c -> c.copy(color = look!!.color, effects = look!!.effects.map { (type, params) ->
                    EffectInstance(ids.next("fx"), type, params = (EffectCatalog.find(type)?.params?.associate { it.key to Animatable.of(params[it.key] ?: it.default) } ?: emptyMap()))
                }) })
            }
            p = p.copy(sequence = p.sequence.copy(tracks = styled), templateId = id)
            return p
        }
    }

    private val V = 1080 to 1920
    private val H = 1920 to 1080
    private val S = 1080 to 1080

    private val defs: List<Pair<TemplateInfo, (B) -> Unit>> = listOf(
        TemplateInfo("reels-hook", "ريلز بخطّاف قوي", "Reels Hook", "social", V.first, V.second) to { b ->
            b.slots(1.5, 2.0, 2.0, 2.5, transition = TransitionKind.WHIP_PAN)
            b.text("انتظر النهاية!", 0.0, 1.6, y = -0.55, size = 84.0, preset = "kinetic-punch", out = "pop-out", stroke = 8.0)
            b.text("اكتب فكرتك هنا", 1.6, 6.4, y = 0.62, size = 56.0, preset = "slide-up", bg = 0xCC0B0D12)
            b.look = LookCatalog.find("vivid")
        },
        TemplateInfo("reels-3cuts", "ريلز ٣ لقطات على الإيقاع", "3-Cut Beat Reel", "social", V.first, V.second) to { b ->
            b.slots(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, transition = TransitionKind.BEAT_CUT)
            b.text("BEKOCAT", 0.0, 6.0, size = 96.0, preset = "tracking-in", loop = "pulse", weight = 900)
            b.look = LookCatalog.find("music-video")
        },
        TemplateInfo("product-ad", "إعلان منتج", "Product Ad", "commerce", V.first, V.second) to { b ->
            b.slots(2.0, 2.0, 2.0, 3.0, transition = TransitionKind.SLIDE_LEFT)
            b.shape(ShapeType.RECT, 0.3, 8.7, 0.8, 0.12, 0xE69B7CFF, y = 0.7, corner = 0.4)
            b.text("اسم المنتج", 0.3, 4.0, y = 0.7, size = 64.0, preset = "reveal-up")
            b.text("السعر · العرض", 4.3, 4.7, y = 0.7, size = 56.0, preset = "pop")
            b.look = LookCatalog.find("product-clean")
        },
        TemplateInfo("product-spin", "عرض منتج دوّار", "Product Spin", "commerce", S.first, S.second) to { b ->
            b.slots(3.0, 3.0, transition = TransitionKind.SPIN)
            b.shape(ShapeType.ELLIPSE, 0.0, 6.0, 0.6, 0.6, 0x00000000L, stroke = 6.0, strokeColor = 0xFF6EE7C8, trimAnim = true)
            b.text("جديد", 0.5, 5.5, y = -0.75, size = 60.0, preset = "drop-in")
            b.look = LookCatalog.find("crisp")
        },
        TemplateInfo("car-showcase", "استعراض سيارة", "Car Showcase", "automotive", H.first, H.second) to { b ->
            b.slots(2.5, 2.5, 2.5, 3.5, transition = TransitionKind.MATCH_ZOOM)
            b.text("الطراز 2027", 0.5, 4.0, y = 0.65, x = -0.55, size = 58.0, preset = "slide-start", align = "start")
            b.text("قوة · أناقة · أداء", 5.0, 6.0, y = 0.65, size = 50.0, preset = "fade-words", highlight = listOf(1))
            b.look = LookCatalog.find("car-gloss")
        },
        TemplateInfo("car-specs", "مواصفات سيارة", "Car Specs", "automotive", V.first, V.second) to { b ->
            b.slots(3.0, 3.0, 3.0, transition = TransitionKind.PUSH_UP)
            listOf("المحرك", "التسارع", "السعر").forEachIndexed { i, s ->
                b.shape(ShapeType.RECT, i * 3.0 + 0.2, 2.6, 0.7, 0.1, 0xCC0B0D12, y = 0.55, corner = 0.5)
                b.text("$s: ……", i * 3.0 + 0.2, 2.6, y = 0.55, size = 48.0, preset = "reveal-side")
            }
            b.look = LookCatalog.find("teal-orange")
        },
        TemplateInfo("real-estate", "جولة عقارية", "Real Estate Tour", "real-estate", H.first, H.second) to { b ->
            b.slots(3.0, 3.0, 3.0, 3.0, transition = TransitionKind.DISSOLVE)
            b.text("فيلا للبيع", 0.3, 3.5, y = -0.6, size = 72.0, preset = "blur-in")
            b.text("٤ غرف · مسبح · حديقة", 3.5, 8.5, y = 0.7, size = 48.0, preset = "fade-words", bg = 0xB3000000)
            b.look = LookCatalog.find("real-estate")
        },
        TemplateInfo("interior-before-after", "تصميم داخلي قبل/بعد", "Interior Before/After", "real-estate", V.first, V.second) to { b ->
            b.slots(3.0, 3.0, transition = TransitionKind.WIPE_RIGHT)
            b.text("قبل", 0.2, 2.6, y = -0.75, size = 64.0, preset = "pop")
            b.text("بعد", 3.2, 2.6, y = -0.75, size = 64.0, preset = "pop", color = 0xFF6EE7C8)
            b.look = LookCatalog.find("high-key")
        },
        TemplateInfo("portfolio", "معرض أعمال مصمم", "Designer Portfolio", "portfolio", H.first, H.second) to { b ->
            b.slots(2.0, 2.0, 2.0, 2.0, 2.0, transition = TransitionKind.PERSPECTIVE)
            b.text("اسمك · مصمم", 0.0, 3.0, size = 72.0, preset = "tracking-in", out = "blur-out")
            b.text("behance.net/…", 7.0, 3.0, y = 0.6, size = 44.0, preset = "typewriter")
            b.look = LookCatalog.find("muted")
        },
        TemplateInfo("podcast-clip", "مقطع بودكاست", "Podcast Clip", "podcast", V.first, V.second) to { b ->
            b.slots(15.0, transition = null)
            b.shape(ShapeType.RECT, 0.0, 15.0, 1.0, 0.18, 0xE60B0D12, y = -0.82)
            b.text("اسم الضيف — اسم البودكاست", 0.0, 15.0, y = -0.82, size = 44.0, preset = "slide-down")
            b.look = LookCatalog.find("documentary")
        },
        TemplateInfo("documentary", "مقدمة وثائقية", "Documentary Intro", "film", H.first, H.second) to { b ->
            b.slots(4.0, 4.0, transition = TransitionKind.DIP_TO_BLACK)
            b.text("عنوان الفيلم", 1.0, 5.0, size = 80.0, preset = "fade-in", weight = 300)
            b.text("قصة حقيقية", 4.0, 3.5, y = 0.3, size = 40.0, preset = "fade-letters", weight = 300)
            b.look = LookCatalog.find("bleach")
        },
        TemplateInfo("travel", "مونتاج سفر", "Travel Montage", "travel", V.first, V.second) to { b ->
            b.slots(1.5, 1.5, 1.5, 1.5, 1.5, 1.5, transition = TransitionKind.ZOOM_IN)
            b.text("العُلا", 0.2, 3.0, size = 110.0, preset = "zoom-out-in", weight = 900)
            b.text("رحلة لا تُنسى", 5.0, 4.0, y = 0.55, size = 52.0, preset = "wave".let { "fade-words" }, loop = "float")
            b.look = LookCatalog.find("golden-hour")
        },
        TemplateInfo("cinematic-title", "عنوان سينمائي", "Cinematic Title", "titles", H.first, H.second) to { b ->
            b.slots(6.0, transition = null)
            b.text("العنوان الكبير", 0.5, 5.0, size = 96.0, preset = "tracking-in", out = "blur-out", weight = 200)
            b.look = LookCatalog.find("anamorphic")
        },
        TemplateInfo("logo-reveal", "كشف شعار", "Logo Reveal", "branding", S.first, S.second) to { b ->
            b.shape(ShapeType.ELLIPSE, 0.0, 4.0, 0.5, 0.5, 0x00000000L, stroke = 8.0, strokeColor = 0xFF9B7CFF, trimAnim = true)
            b.shape(ShapeType.RECT, 0.6, 3.4, 0.25, 0.25, 0xFF6EE7C8, corner = 0.2, track = "o2")
            b.text("اسم العلامة", 1.4, 2.6, y = 0.55, size = 56.0, preset = "reveal-up")
            b.at("v1", Clip(b.ids.next("bg"), null, "background", Rational.ZERO, Rational.ZERO, Rational.of(4), kind = ClipKind.SOLID, solidArgb = 0xFF0B0D12))
        },
        TemplateInfo("kinetic-typo", "تايبوغرافي حركي", "Kinetic Typography", "titles", V.first, V.second) to { b ->
            b.at("v1", Clip(b.ids.next("bg"), null, "background", Rational.ZERO, Rational.ZERO, Rational.of(8), kind = ClipKind.SOLID, solidArgb = 0xFF141821))
            listOf("كل", "فكرة", "تستحق", "حركة").forEachIndexed { i, w ->
                b.text(w, i * 2.0, 2.0, size = 140.0, preset = listOf("kinetic-punch", "slide-up", "spring-drop", "elastic")[i], out = "scale-out", weight = 900,
                    color = if (i % 2 == 0) 0xFFF4F7FB else 0xFF9B7CFF)
            }
        },
        TemplateInfo("before-after", "مقارنة قبل/بعد", "Before / After Split", "compare", S.first, S.second) to { b ->
            b.slots(5.0, transition = null)
            b.shape(ShapeType.RECT, 0.0, 5.0, 0.006, 1.0, 0xFFF4F7FB, track = "o2")
            b.text("قبل", 0.0, 5.0, x = -0.5, y = -0.85, size = 44.0, preset = "fade-in")
            b.text("بعد", 0.0, 5.0, x = 0.5, y = -0.85, size = 44.0, preset = "fade-in", color = 0xFF6EE7C8)
        },
        TemplateInfo("vertical-story", "قصة عمودية", "Vertical Story", "social", V.first, V.second) to { b ->
            b.slots(5.0, 5.0, 5.0, transition = TransitionKind.SLIDE_LEFT)
            b.text("١/٣", 0.0, 15.0, y = -0.88, x = 0.75, size = 36.0, preset = null, out = null)
            b.text("اكتب نص القصة", 0.3, 14.5, y = 0.5, size = 60.0, preset = "pop", bg = 0xE6FFFFFF, color = 0xFF0B0D12)
        },
        TemplateInfo("landscape-promo", "عرض أفقي ترويجي", "Landscape Promo", "commerce", H.first, H.second) to { b ->
            b.slots(2.5, 2.5, 2.5, 2.5, transition = TransitionKind.LIGHT_LEAK)
            b.text("عرض خاص", 0.3, 4.0, size = 90.0, preset = "pop", weight = 900, stroke = 6.0)
            b.text("لفترة محدودة", 4.5, 5.0, y = 0.4, size = 54.0, preset = "slide-up")
            b.look = LookCatalog.find("warm-cinema")
        },
        TemplateInfo("loop-social", "حلقة حركية للتواصل", "Looping Social Motion", "social", S.first, S.second) to { b ->
            b.at("v1", Clip(b.ids.next("bg"), null, "background", Rational.ZERO, Rational.ZERO, Rational.of(4), kind = ClipKind.SOLID, solidArgb = 0xFF1C2230))
            b.shape(ShapeType.STAR, 0.0, 4.0, 0.4, 0.4, 0xFF9B7CFF)
            b.text("LOOP", 0.0, 4.0, y = 0.6, size = 80.0, preset = null, out = null, loop = "wave", weight = 900)
        },
        TemplateInfo("quote", "بطاقة اقتباس", "Quote Card", "titles", S.first, S.second) to { b ->
            b.at("v1", Clip(b.ids.next("bg"), null, "background", Rational.ZERO, Rational.ZERO, Rational.of(6), kind = ClipKind.SOLID, solidArgb = 0xFF0B0D12))
            b.text("«اكتب الاقتباس هنا»", 0.3, 5.4, size = 60.0, preset = "fade-words", weight = 500)
            b.text("— القائل", 1.5, 4.2, y = 0.4, size = 40.0, preset = "fade-in", color = 0xFF98A3B7)
        },
        TemplateInfo("countdown", "عدّ تنازلي لحدث", "Event Countdown", "events", V.first, V.second) to { b ->
            b.slots(4.0, transition = null)
            listOf("٣", "٢", "١").forEachIndexed { i, n -> b.text(n, i.toDouble(), 1.0, size = 220.0, preset = "pop", out = "scale-out", weight = 900) }
            b.text("الآن!", 3.0, 1.0, size = 140.0, preset = "kinetic-punch", weight = 900, color = 0xFF6EE7C8)
        },
        TemplateInfo("sale", "إعلان تخفيضات", "Sale Announcement", "commerce", V.first, V.second) to { b ->
            b.slots(3.0, 3.0, transition = TransitionKind.GLITCH)
            b.shape(ShapeType.STAR, 0.0, 6.0, 0.5, 0.5, 0xFFFF6577, y = -0.45)
            b.text("٥٠٪", 0.0, 6.0, y = -0.45, size = 120.0, preset = "pop", loop = "pulse", weight = 900)
            b.text("خصم على كل شيء", 0.5, 5.5, y = 0.5, size = 60.0, preset = "slide-up")
        },
        TemplateInfo("recipe", "خطوات وصفة", "Recipe Steps", "howto", V.first, V.second) to { b ->
            b.slots(3.0, 3.0, 3.0, 3.0, transition = TransitionKind.PUSH_LEFT)
            (1..4).forEach { i -> b.text("الخطوة $i", (i - 1) * 3.0, 3.0, y = -0.75, size = 56.0, preset = "reveal-side", bg = 0xCC0B0D12) }
            b.look = LookCatalog.find("food-warm")
        },
        TemplateInfo("tutorial", "شرح تعليمي", "Tutorial Steps", "howto", H.first, H.second) to { b ->
            b.slots(4.0, 4.0, 4.0, transition = TransitionKind.DISSOLVE)
            b.shape(ShapeType.RECT, 0.0, 12.0, 0.36, 0.1, 0xE69B7CFF, y = -0.8, x = -0.6, corner = 0.5)
            b.text("الدرس ١", 0.0, 12.0, y = -0.8, x = -0.6, size = 44.0, preset = "fade-in")
        },
        TemplateInfo("testimonial", "رأي عميل (نص قابل للتعديل)", "Testimonial Layout", "commerce", S.first, S.second) to { b ->
            b.slots(6.0, transition = null)
            b.shape(ShapeType.RECT, 0.3, 5.7, 0.86, 0.36, 0xE6FFFFFF, y = 0.4, corner = 0.12)
            b.text("اكتب رأي العميل الحقيقي هنا", 0.5, 5.5, y = 0.35, size = 46.0, preset = "fade-words", color = 0xFF0B0D12, weight = 500)
            b.text("اسم العميل", 1.0, 5.0, y = 0.52, size = 36.0, preset = "fade-in", color = 0xFF7B5CF0)
        },
    )

    val all: List<TemplateInfo> = defs.map { it.first }

    fun build(id: String, projectId: String, nowMs: Long, name: String? = null): Project {
        val (info, fn) = defs.first { it.first.id == id }
        val b = B(info.id, info.width, info.height)
        b.name = name ?: info.ar
        fn(b)
        return b.build(projectId, nowMs)
    }

    fun placeholders(p: Project): List<Clip> = p.sequence.tracks.flatMap { it.clips }.filter { it.name.startsWith("placeholder:") }
}
