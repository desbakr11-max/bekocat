package app.bekocat.core.timeline

/**
 * The closed set of operations the assistant (rule fast-path today, on-device
 * language model later) is allowed to emit. Nothing outside this list can run:
 * no shell, no scripts, no file deletion. A plan is applied as ONE history
 * transaction so it can be undone/redone as a unit.
 */
sealed class Operation {
    abstract fun describeAr(): String

    data class SetSpeed(val clipId: String, val speed: Rational) : Operation() {
        override fun describeAr() = "تغيير السرعة إلى ×${"%.2f".format(speed.toDouble())}"
    }
    data class Split(val at: Time) : Operation() {
        override fun describeAr() = "قص عند ${"%.2f".format(at.toDouble())} ث"
    }
    data class SetColor(val clipIds: List<String>, val color: ColorAdjust) : Operation() {
        override fun describeAr() = "تطبيق تصحيح لوني على ${clipIds.size} مقطع"
    }
    data class SetVolume(val clipIds: List<String>, val volume: Double) : Operation() {
        override fun describeAr() = "ضبط مستوى الصوت إلى ${(volume * 100).toInt()}%"
    }
    data class AddTransition(val clipIds: List<String>, val kind: TransitionKind, val duration: Time) : Operation() {
        override fun describeAr() = "إضافة انتقال ${kind.name} إلى ${clipIds.size} قطع"
    }
    data class ZoomRamp(val clipId: String, val from: Time, val to: Time, val fromScale: Double, val toScale: Double) : Operation() {
        override fun describeAr() = "تقريب تدريجي من ${"%.1f".format(from.toDouble())} إلى ${"%.1f".format(to.toDouble())} ث"
    }
    data class DeleteClips(val clipIds: Set<String>, val ripple: Boolean) : Operation() {
        override fun describeAr() = "حذف ${clipIds.size} مقطع" + if (ripple) " مع سد الفراغ" else ""
    }
}

data class Plan(val operations: List<Operation>, val summaryAr: String)

object PlanApplier {
    fun apply(p: Project, plan: Plan, ids: IdGenerator): Project {
        val ed = TimelineEditor(p.assets.associateBy { it.id }, ids)
        var s = p.sequence
        for (op in plan.operations) {
            s = when (op) {
                is Operation.SetSpeed -> ed.setSpeed(s, op.clipId, op.speed)
                is Operation.Split -> ed.splitAll(s, op.at)
                is Operation.SetColor -> op.clipIds.fold(s) { acc, id -> ed.updateClip(acc, id) { it.copy(color = op.color) } }
                is Operation.SetVolume -> op.clipIds.fold(s) { acc, id -> ed.updateClip(acc, id) { it.copy(audio = it.audio.copy(volume = Animatable.of(op.volume))) } }
                is Operation.AddTransition -> op.clipIds.fold(s) { acc, id -> ed.updateClip(acc, id) { it.copy(transitionIn = Transition(op.kind, op.duration)) } }
                is Operation.ZoomRamp -> ed.updateClip(s, op.clipId) { c ->
                    val a = op.from - c.timelineStart
                    val b = op.to - c.timelineStart
                    val sc = c.transform.scale
                        .withKeyframe(Keyframe(a, op.fromScale, Interpolation.BEZIER))
                        .withKeyframe(Keyframe(b, op.toScale, Interpolation.BEZIER))
                    c.copy(transform = c.transform.copy(scale = sc))
                }
                is Operation.DeleteClips -> ed.delete(s, op.clipIds, op.ripple)
            }
        }
        return p.copy(sequence = s)
    }
}

/**
 * Deterministic fast path for direct Arabic/English commands. This is NOT the
 * assistant's language understanding — it handles unambiguous phrasing quickly
 * and returns null otherwise so the request can go to the on-device model.
 */
object DirectCommandParser {

    private val arabicDigits = mapOf('٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4', '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9', '٫' to '.')

    fun normalize(s: String): String = s.map { arabicDigits[it] ?: it }.joinToString("")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
        .replace(Regex("[ً-ْ]"), "") // strip tashkeel for matching only
        .lowercase()

    private fun nums(s: String) = Regex("\\d+(?:\\.\\d+)?").findAll(s).map { it.value.toDouble() }.toList()

    fun parse(command: String, seq: Sequence, selectedClipId: String?, playhead: Time): Plan? {
        val n = normalize(command)
        val videoClips = seq.tracks.filter { it.kind == TrackKind.VIDEO || it.kind == TrackKind.OVERLAY }.flatMap { it.clips }
        val target = selectedClipId ?: videoClips.firstOrNull { it.contains(playhead) }?.id
        val all = videoClips.map { it.id }
        val ops = mutableListOf<Operation>()

        val warm = listOf("دافي", "دافئ", "warm").any { it in n }
        val cool = listOf("بارد", "cool", "cold").any { it in n }
        val cinematic = listOf("سينمائي", "cinematic").any { it in n }
        if (warm || cool || cinematic) {
            ops += Operation.SetColor(all, ColorAdjust(
                contrast = if (cinematic) 0.15 else 0.05,
                saturation = if (cinematic) -0.1 else 0.0,
                temperature = if (warm) 0.25 else if (cool) -0.25 else 0.0,
            ))
        }

        if (("سرع" in n || "speed" in n || "بطي" in n || "slow" in n) && target != null) {
            val x = nums(n).firstOrNull() ?: if ("بطي" in n || "slow" in n) 0.5 else 2.0
            val speed = if (("بطي" in n || "slow" in n) && x > 1) 1.0 / x else x
            if (speed in 0.05..16.0) ops += Operation.SetSpeed(target, Rational.fromDouble(speed))
        }

        val words = n.split(Regex("[\\s،,.]+")).toSet()
        if (words.any { it in setOf("قص", "اقطع", "قطع", "split", "cut", "blade") } && "صمت" !in n) {
            val at = nums(n).firstOrNull()?.let { Rational.fromDouble(it) } ?: playhead
            ops += Operation.Split(at)
        }

        if (("تقريب" in n || "زوم" in n || "zoom" in n) && target != null) {
            val ns = nums(n)
            if (ns.size >= 2) ops += Operation.ZoomRamp(target, Rational.fromDouble(ns[0]), Rational.fromDouble(ns[1]), 1.0, 1.25)
        }

        if ("انتقال" in n || "transition" in n) {
            val kind = when {
                "ابيض" in n || "white" in n -> TransitionKind.DIP_TO_WHITE
                "اسود" in n || "black" in n -> TransitionKind.DIP_TO_BLACK
                "زوم" in n || "zoom" in n -> TransitionKind.ZOOM_IN
                else -> TransitionKind.DISSOLVE
            }
            val primary = seq.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips.orEmpty()
            ops += Operation.AddTransition(primary.drop(1).map { it.id }, kind, Rational.of(1, 2))
        }

        if (("خفض" in n || "خفف" in n || "quieter" in n || "lower" in n) && ("صوت" in n || "volume" in n || "موسيقي" in n)) {
            val audio = seq.tracks.filter { it.kind == TrackKind.AUDIO }.flatMap { it.clips }.map { it.id }
            if (audio.isNotEmpty()) ops += Operation.SetVolume(audio, 0.3)
        }

        if (ops.isEmpty()) return null
        return Plan(ops, ops.joinToString("، ") { it.describeAr() })
    }
}
