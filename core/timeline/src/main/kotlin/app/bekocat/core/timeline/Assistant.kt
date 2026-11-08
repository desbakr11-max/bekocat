package app.bekocat.core.timeline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull

/**
 * Assistant intents: the complete, closed vocabulary the assistant may produce. Pure edits
 * map to [Operation]s; jobs (captions, silence removal, separation, matting) are executed by the
 * app and their results are committed together with the edits as ONE undoable transaction.
 */
sealed class Intent {
    abstract val ar: String
    data class Edit(val op: Operation) : Intent() { override val ar get() = op.describeAr() }
    data class Aspect(val w: Int, val h: Int) : Intent() { override val ar get() = "تحويل المقاس إلى ${w}×$h" }
    data class RemoveSilence(val thresholdDb: Double = -40.0, val minSilence: Double = 0.5, val padding: Double = 0.12) : Intent() { override val ar get() = "حذف الصمت" }
    data class Captions(val language: String?, val style: String) : Intent() { override val ar get() = "كابشن " + (if (language == "ar") "عربي" else language ?: "تلقائي") + " بنمط $style" }
    data class CaptionColor(val argb: Long?) : Intent() { override val ar get() = "تغيير لون الكابشن فقط (التوقيت كما هو)" }
    data class DuckMusic(val depthDb: Double = -12.0) : Intent() { override val ar get() = "خفض الموسيقى أثناء الكلام" }
    data class Look(val lookId: String) : Intent() { override val ar get() = "تطبيق مظهر ${LookCatalog.find(lookId)?.ar ?: lookId}" }
    data class Transitions(val kind: TransitionKind, val duration: Double) : Intent() { override val ar get() = "انتقالات ${TransitionCatalog.names[kind]?.first} مدتها ${"%.2f".format(duration)} ث" }
    data class IsolateVocals(val keep: String) : Intent() { override val ar get() = if (keep == "vocals") "عزل الغناء وإبقاؤه وحده" else "عزل الغناء وإبقاء الموسيقى وحدها" }
    data class RemoveBackground(val useOtherClipAsBackground: Boolean) : Intent() { override val ar get() = "إزالة الخلفية" + if (useOtherClipAsBackground) " ووضع الفيديو الآخر خلف الشخص" else "" }
    data class AnimateIn(val from: String, val easing: String, val target: String) : Intent() { override val ar get() = "تحريك ${if (target == "logo") "الشعار" else "العنصر"} من ${mapOf("bottom" to "الأسفل", "top" to "الأعلى", "left" to "اليسار", "right" to "اليمين")[from] ?: from} ($easing)" }
    data class CopyAnimationToTitles(val delay: Double) : Intent() { override val ar get() = "نسخ الحركة إلى العناوين بتأخير ${"%.2f".format(delay)} ث" }
    object Undo : Intent() { override val ar get() = "تراجع خطوة واحدة فقط" }
}

data class AssistantPlan(val intents: List<Intent>, val question: String? = null, val alternatives: List<String> = emptyList()) {
    val summaryAr get() = intents.joinToString("، ") { it.ar }
}

object AssistantParser {

    private fun has(n: String, vararg w: String) = w.any { it in n }

    /** Rule-based understanding of direct Arabic/English requests (fast path, also used offline without the LLM). */
    fun parse(command: String, seq: Sequence, selected: String?, playhead: Time): AssistantPlan? {
        val n = DirectCommandParser.normalize(command)
        val out = ArrayList<Intent>()
        var question: String? = null
        var alternatives = emptyList<String>()

        if (has(n, "ارجع خطوه", "تراجع", "undo", "رجع خطوه")) return AssistantPlan(listOf(Intent.Undo))

        if (has(n, "ريلز", "reel", "عمودي", "vertical", "تيك توك", "tiktok", "ستوري", "story")) out += Intent.Aspect(1080, 1920)
        else if (has(n, "افقي", "landscape", "يوتيوب", "youtube")) out += Intent.Aspect(1920, 1080)
        else if (has(n, "مربع", "square")) out += Intent.Aspect(1080, 1080)

        if (has(n, "احذف الصمت", "حذف الصمت", "شيل الصمت", "remove silence", "cut silence", "الفراغات الصامته")) out += Intent.RemoveSilence()

        val captionColorOnly = has(n, "لون الكابشن", "caption color", "لون الترجمه") && has(n, "فقط", "only", "توقيته", "timing")
        if (captionColorOnly) {
            val c = colorIn(n)
            out += Intent.CaptionColor(c)
            if (c == null) { question = "أي لون تفضّل للكابشن؟"; alternatives = listOf("أصفر", "أبيض", "نعناعي", "بنفسجي") }
        } else if (has(n, "كابشن", "caption", "ترجمه نصيه", "subtitles", "ترجمه")) {
            val lang = when { has(n, "عربي", "arabic") -> "ar"; has(n, "انجليزي", "english") -> "en"; else -> null }
            val style = when { has(n, "بارز", "بارزه", "highlight", "active") -> "active-word"; has(n, "كاريوكي", "karaoke") -> "karaoke"; has(n, "كلمه كلمه", "word by word") -> "word-by-word"; else -> "subtitle" }
            out += Intent.Captions(lang, style)
        }

        if (has(n, "خفض الموسيقي اثناء الكلام", "خفض الموسيقي وقت الكلام", "خفف الموسيقي اثناء", "duck", "ducking") ||
            (has(n, "خفض", "خفف", "lower") && has(n, "الموسيقي", "music") && has(n, "الكلام", "speech", "الحوار"))) out += Intent.DuckMusic()

        val look = when {
            has(n, "سينمائي") && has(n, "دافي", "دافئ", "warm") -> "warm-cinema"
            has(n, "cinematic") && has(n, "warm") -> "warm-cinema"
            has(n, "ابيض واسود", "black and white", "mono") -> "mono"
            has(n, "بارد", "cool", "cold") -> "cool-night"
            has(n, "دافي", "دافئ", "warm") -> "golden-hour"
            has(n, "سينمائي", "cinematic") -> "anamorphic"
            has(n, "حيوي", "vivid") -> "vivid"
            has(n, "قديم", "vintage", "retro") -> "film-fade"
            else -> null
        }
        look?.let { out += Intent.Look(it) }

        if (has(n, "انتقال", "transition")) {
            val soft = has(n, "ناعم", "smooth", "soft", "بدون مبالغه", "subtle")
            val kind = when {
                has(n, "ابيض", "white") -> TransitionKind.DIP_TO_WHITE; has(n, "اسود", "black") -> TransitionKind.DIP_TO_BLACK
                has(n, "زوم", "zoom") -> TransitionKind.ZOOM_IN; has(n, "سحب", "whip") -> TransitionKind.WHIP_PAN
                has(n, "تشويش", "glitch") -> TransitionKind.GLITCH
                else -> TransitionKind.DISSOLVE
            }
            out += Intent.Transitions(kind, if (soft || has(n, "بدون مبالغه")) 0.4 else 0.6)
        }

        if (has(n, "اعزل الغناء", "عزل الغناء", "isolate vocals", "remove vocals", "شيل الغناء", "شيل الصوت البشري")) {
            val keepMusic = has(n, "الموسيقي لوحدها", "الموسيقي وحدها", "music only", "instrumental", "شيل الغناء", "remove vocals")
            out += Intent.IsolateVocals(if (keepMusic) "accompaniment" else "vocals")
        }
        if (has(n, "شيل الخلفيه", "ازل الخلفيه", "ازاله الخلفيه", "remove background", "remove the background")) {
            out += Intent.RemoveBackground(has(n, "خلف الشخص", "behind", "خلفه"))
        }
        if ((has(n, "حرك", "animate", "move")) && has(n, "الشعار", "logo", "العنصر", "element")) {
            val from = when { has(n, "اسفل", "تحت", "bottom") -> "bottom"; has(n, "اعلي", "فوق", "top") -> "top"; has(n, "يسار", "left") -> "left"; has(n, "يمين", "right") -> "right"; else -> "bottom" }
            val easing = when { has(n, "ارتداد", "bounce") -> if (has(n, "خفيف", "light", "slight")) "back" else "bounce"; has(n, "مرن", "elastic") -> "elastic"; else -> "out" }
            out += Intent.AnimateIn(from, easing, if (has(n, "الشعار", "logo")) "logo" else "selected")
        }
        if (has(n, "نفس الحركه", "same animation", "same motion") && has(n, "العناوين", "titles", "النصوص")) {
            val delay = when { has(n, "ربع ثانيه", "quarter") -> 0.25; has(n, "نص ثانيه", "نصف ثانيه", "half") -> 0.5
                else -> Regex("\\d+(?:\\.\\d+)?").find(n)?.value?.toDoubleOrNull() ?: 0.25 }
            out += Intent.CopyAnimationToTitles(delay)
        }
        // Remaining direct edits (speed, split, zoom ramp, volume) via the operation parser.
        DirectCommandParser.parse(command, seq, selected, playhead)?.operations
            ?.filterNot { op -> (op is Operation.SetColor && out.any { it is Intent.Look }) || (op is Operation.AddTransition && out.any { it is Intent.Transitions }) ||
                (op is Operation.SetVolume && out.any { it is Intent.DuckMusic }) }
            ?.forEach { out += Intent.Edit(it) }
        if (out.isEmpty()) return null
        return AssistantPlan(out, question, alternatives)
    }

    private fun colorIn(n: String): Long? = when {
        has(n, "اصفر", "yellow") -> 0xFFFFC857; has(n, "ابيض", "white") -> 0xFFF4F7FB; has(n, "احمر", "red") -> 0xFFFF6577
        has(n, "ازرق", "blue") -> 0xFF3BA3FF; has(n, "اخضر", "green", "نعناعي", "mint") -> 0xFF6EE7C8; has(n, "بنفسجي", "purple") -> 0xFF9B7CFF
        else -> null
    }

    /** JSON schema the language model must follow (validated; anything else is rejected). */
    const val SCHEMA = """{"intents":[{"type":"aspect","w":1080,"h":1920}|{"type":"remove_silence"}|{"type":"captions","lang":"ar|en|auto","style":"subtitle|active-word|karaoke|word-by-word|pill|bold-social"}|{"type":"caption_color","color":"#RRGGBB"}|{"type":"duck_music"}|{"type":"look","id":"warm-cinema|teal-orange|cool-night|mono|vivid|film-fade|golden-hour|anamorphic|documentary|muted"}|{"type":"transitions","kind":"DISSOLVE|DIP_TO_BLACK|ZOOM_IN|WHIP_PAN|SLIDE_LEFT|GLITCH","duration":0.5}|{"type":"isolate_vocals","keep":"vocals|accompaniment"}|{"type":"remove_background","other_clip_behind":true}|{"type":"animate_in","from":"bottom|top|left|right","easing":"out|back|bounce|elastic","target":"logo|selected"}|{"type":"copy_animation_to_titles","delay":0.25}|{"type":"speed","x":2.0}|{"type":"split","at":3.0}|{"type":"zoom_ramp","from":2.0,"to":5.0,"scale":1.25}|{"type":"undo"}],"question":null}"""

    /** Parses and validates model output; unknown types or bad values are dropped, never executed. */
    fun fromJson(text: String, seq: Sequence, selected: String?): AssistantPlan? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val arr = obj["intents"] as? JsonArray ?: return null
        val out = ArrayList<Intent>()
        val target = selected ?: seq.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips?.firstOrNull()?.id
        for (e in arr.take(12)) {
            val o = e as? JsonObject ?: continue
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            when (s("type")) {
                "aspect" -> { val w = d("w")?.toInt() ?: continue; val h = d("h")?.toInt() ?: continue; if (w in 144..8192 && h in 144..8192) out += Intent.Aspect(w / 2 * 2, h / 2 * 2) }
                "remove_silence" -> out += Intent.RemoveSilence()
                "captions" -> out += Intent.Captions(s("lang")?.takeIf { it == "ar" || it == "en" }, s("style")?.takeIf { id -> CaptionStyleCatalog.all.any { it.id == id } } ?: "subtitle")
                "caption_color" -> out += Intent.CaptionColor(s("color")?.removePrefix("#")?.toLongOrNull(16)?.let { it or 0xFF000000 })
                "duck_music" -> out += Intent.DuckMusic()
                "look" -> s("id")?.takeIf { LookCatalog.find(it) != null }?.let { out += Intent.Look(it) }
                "transitions" -> runCatching { TransitionKind.valueOf(s("kind") ?: "DISSOLVE") }.getOrNull()?.let { out += Intent.Transitions(it, (d("duration") ?: 0.5).coerceIn(0.1, 3.0)) }
                "isolate_vocals" -> out += Intent.IsolateVocals(if (s("keep") == "vocals") "vocals" else "accompaniment")
                "remove_background" -> out += Intent.RemoveBackground((o["other_clip_behind"] as? JsonPrimitive)?.booleanOrNull ?: false)
                "animate_in" -> out += Intent.AnimateIn(s("from")?.takeIf { it in setOf("bottom", "top", "left", "right") } ?: "bottom",
                    s("easing")?.takeIf { it in setOf("out", "back", "bounce", "elastic") } ?: "out", if (s("target") == "logo") "logo" else "selected")
                "copy_animation_to_titles" -> out += Intent.CopyAnimationToTitles((d("delay") ?: 0.25).coerceIn(0.0, 5.0))
                "speed" -> { val x = d("x") ?: continue; if (target != null && x in 0.05..16.0) out += Intent.Edit(Operation.SetSpeed(target, Rational.fromDouble(x))) }
                "split" -> d("at")?.takeIf { it >= 0 }?.let { out += Intent.Edit(Operation.Split(Rational.fromDouble(it))) }
                "zoom_ramp" -> { val a = d("from") ?: continue; val b = d("to") ?: continue; if (target != null && b > a) out += Intent.Edit(Operation.ZoomRamp(target, Rational.fromDouble(a), Rational.fromDouble(b), 1.0, (d("scale") ?: 1.25).coerceIn(1.0, 4.0))) }
                "undo" -> out += Intent.Undo
            }
        }
        if (out.isEmpty()) return null
        return AssistantPlan(out, (obj["question"] as? JsonPrimitive)?.contentOrNull)
    }

    // ---------------- pure intent application ----------------

    /** Animates a clip in from an edge with the given easing (keyframes at clip start). */
    fun animateIn(c: Clip, from: String, easing: String, dur: Time = Rational.of(4, 5)): Clip {
        val (dx, dy) = when (from) { "top" -> 0.0 to -1.6; "left" -> -1.6 to 0.0; "right" -> 1.6 to 0.0; else -> 0.0 to 1.6 }
        val ease = when (easing) { "back" -> listOf(0.34, 1.4, 0.64, 1.0); "bounce" -> listOf(0.34, 1.8, 0.5, 1.0); "elastic" -> listOf(0.2, 2.2, 0.4, 1.0); else -> listOf(0.0, 0.0, 0.2, 1.0) }
        fun anim(a: Animatable, off: Double): Animatable {
            val rest = a.valueAt(dur)
            return a.withKeyframe(Keyframe(Rational.ZERO, rest + off, Interpolation.BEZIER, outX = ease[0], outY = ease[1]))
                .withKeyframe(Keyframe(dur, rest, Interpolation.BEZIER, inX = ease[2], inY = ease[3]))
        }
        val tf = c.transform
        return c.copy(transform = tf.copy(
            positionX = if (dx != 0.0) anim(tf.positionX, dx) else tf.positionX,
            positionY = if (dy != 0.0) anim(tf.positionY, dy) else tf.positionY,
            opacity = tf.opacity.withKeyframe(Keyframe(Rational.ZERO, 0.0, Interpolation.LINEAR)).withKeyframe(Keyframe(Rational.of(1, 5), tf.opacity.valueAt(dur))),
        ))
    }

    /** Copies the transform/animation of [source] to every text clip with an increasing delay. */
    fun copyAnimationToTitles(seq: Sequence, sourceId: String, delay: Double, ed: TimelineEditor): Sequence {
        val src = seq.findClip(sourceId)?.second ?: return seq
        val titles = seq.tracks.flatMap { it.clips }.filter { it.kind == ClipKind.TEXT && it.id != sourceId }.sortedBy { it.timelineStart }
        var s = seq
        titles.forEachIndexed { i, t ->
            val off = Rational.fromDouble(delay * (i + 1), 1000)
            s = ed.updateClip(s, t.id) { c -> c.copy(transform = src.transform.shiftKeyframes(off), text = c.text?.copy(animation = src.text?.animation ?: c.text.animation)) }
        }
        return s
    }
}
