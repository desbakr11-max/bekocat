package app.bekocat.core.timeline

import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.sin

/** Animated state of one text unit (grapheme / word / line / whole text), offsets in em units. */
data class UnitState(
    val start: Int, val end: Int,           // char range in the logical string (never splits a grapheme cluster)
    val alpha: Double = 1.0, val dx: Double = 0.0, val dy: Double = 0.0,
    val scale: Double = 1.0, val rotation: Double = 0.0, val blur: Double = 0.0,
    val tracking: Double = 0.0,
    val visible: Boolean = true,             // typewriter visibility
    val reveal: Double = 1.0, val revealDir: String? = null,
    val highlight: Double = 0.0,             // 0..1 highlight amount (word highlight / karaoke fill)
)

object TextAnimator {

    /** Splits [text] into unit ranges; grapheme clusters keep Arabic letters + diacritics + emoji together. */
    fun units(text: String, unit: TextUnit): List<IntRange> = when (unit) {
        TextUnit.ALL -> if (text.isEmpty()) emptyList() else listOf(0 until text.length)
        TextUnit.GRAPHEME -> {
            val it = BreakIterator.getCharacterInstance()
            it.setText(text)
            val out = ArrayList<IntRange>()
            var s = it.first(); var e = it.next()
            while (e != BreakIterator.DONE) {
                if (!text.substring(s, e).isBlank()) {
                    // Older JDK BreakIterators split emoji modifiers / ZWJ sequences / variation selectors;
                    // merge them into the previous cluster so behaviour matches ICU (Android) on every JVM.
                    val cp = text.codePointAt(s)
                    val prev = out.lastOrNull()
                    val joins = cp in 0x1F3FB..0x1F3FF || cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0xE0020..0xE007F ||
                        (prev != null && prev.last + 1 == s && text[prev.last] == '\u200D')
                    if (joins && prev != null && prev.last + 1 == s) out[out.size - 1] = prev.first until e else out += s until e
                }
                s = e; e = it.next()
            }
            out
        }
        TextUnit.WORD -> Regex("\\S+").findAll(text).map { it.range }.toList()
        TextUnit.LINE -> {
            val out = ArrayList<IntRange>(); var s = 0
            for (i in text.indices) if (text[i] == '\n') { if (i > s) out += s until i; s = i + 1 }
            if (s < text.length) out += s until text.length
            out
        }
    }

    /**
     * Evaluates IN / OUT / LOOP presets at clip-local time [t] for a clip of [duration] seconds.
     * Deterministic: same inputs → same states (glitch jitter uses seeded noise).
     */
    fun states(text: String, anim: TextAnimation, t: Double, duration: Double): List<UnitState> {
        val pin = TextPresetCatalog.find(anim.presetIn)
        val pout = TextPresetCatalog.find(anim.presetOut)
        val ploop = TextPresetCatalog.find(anim.presetLoop)
        val unit = pin?.unit ?: pout?.unit ?: ploop?.unit ?: anim.unit
        val ranges = units(text, unit)
        if (ranges.isEmpty()) return emptyList()
        val n = ranges.size
        val order = if (anim.reverseOrder) ranges.indices.reversed().toList() else ranges.indices.toList()
        val stagger = anim.stagger.toDouble()
        val dIn = anim.durationIn.toDouble()
        val dOut = anim.durationOut.toDouble()

        return ranges.mapIndexed { idx, r ->
            val k = order.indexOf(idx)
            var st = UnitState(r.first, r.last + 1)
            if (pin != null) {
                val unitDur = if (pin.typewriter) stagger.coerceAtLeast(1e-3) else dIn
                val local = (t - k * stagger) / unitDur
                st = applyPhase(st, pin, local, true, anim.intensity, idx)
            }
            if (pout != null) {
                val outStart = duration - dOut - (n - 1 - k) * stagger
                val unitDur = if (pout.typewriter) stagger.coerceAtLeast(1e-3) else dOut
                val local = (t - outStart) / unitDur
                if (local > 0) st = applyPhase(st, pout, local, false, anim.intensity, idx)
            }
            if (ploop != null) st = applyLoop(st, ploop, t, idx, n, duration, anim.intensity)
            st
        }
    }

    private fun applyPhase(s: UnitState, p: TextPreset, local: Double, isIn: Boolean, intensity: Double, idx: Int): UnitState {
        val u = local.coerceIn(0.0, 1.0)
        if (p.typewriter) {
            val shown = if (isIn) local >= (if (p.easing == "hold") 0.0 else 0.0) && local > 0 else local < 1.0
            val a = if (p.easing == "linear") (if (isIn) u else 1 - u) else 1.0
            return s.copy(visible = shown && s.visible, alpha = s.alpha * a)
        }
        // progress from start-state (0) to rest (1) for IN; rest (1) → end-state (0) for OUT.
        val e = Easing.apply(p.easing, u)
        val w = if (isIn) 1 - e else e        // weight of the preset's off-rest state
        val k = w * intensity
        val jx = if (p.jitter > 0 && w > 0.01) (Noise.hash01(idx.toLong(), (local * 30).toLong()) - 0.5) * p.jitter * 2 * w else 0.0
        val jy = if (p.jitter > 0 && w > 0.01) (Noise.hash01(idx.toLong() + 77, (local * 30).toLong()) - 0.5) * p.jitter * w else 0.0
        val alphaTarget = if (p.reveal != null) 1.0 else p.alpha
        return s.copy(
            alpha = s.alpha * (1 - w + w * alphaTarget).coerceIn(0.0, 1.0),
            dx = s.dx + p.dx * k + jx,
            dy = s.dy + p.dy * k + jy,
            scale = s.scale * (1 + (p.scale - 1) * k),
            rotation = s.rotation + p.rotation * k,
            blur = s.blur + p.blur * k,
            tracking = s.tracking + p.tracking * k,
            reveal = if (p.reveal != null) (if (isIn) e else 1 - e) else s.reveal,
            revealDir = p.reveal ?: s.revealDir,
        )
    }

    private fun applyLoop(s: UnitState, p: TextPreset, t: Double, idx: Int, n: Int, duration: Double, intensity: Double): UnitState {
        val a = p.loopAmp * intensity
        val f = p.loopFreq
        return when (p.loop) {
            "wave" -> s.copy(dy = s.dy + a * sin(2 * PI * (f * t - idx * 0.08)))
            "pulse" -> s.copy(scale = s.scale * (1 + a * sin(2 * PI * f * t)))
            "shake" -> s.copy(dx = s.dx + a * Noise.smooth(t * f, idx.toLong()), dy = s.dy + a * Noise.smooth(t * f, idx + 999L))
            "float" -> s.copy(dy = s.dy + a * sin(2 * PI * (f * t + idx * 0.21)))
            "flicker" -> s.copy(alpha = s.alpha * (1 - a * (if (Noise.hash01(idx.toLong(), (t * f).toLong()) > 0.7) 1.0 else 0.0)))
            "wiggle" -> s.copy(rotation = s.rotation + a * Noise.smooth(t * f, idx + 31L))
            "highlight" -> {
                val pos = (t * f) % n
                s.copy(highlight = if (idx == pos.toInt()) 1.0 else 0.0)
            }
            "karaoke" -> {
                val per = duration / n
                s.copy(highlight = ((t - idx * per) / per).coerceIn(0.0, 1.0))
            }
            else -> s
        }
    }
}
