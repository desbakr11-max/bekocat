package app.bekocat.core.export

import app.bekocat.core.timeline.CaptionSegment
import app.bekocat.core.timeline.CaptionWord
import app.bekocat.core.timeline.Rational
import app.bekocat.core.timeline.Time
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/** WAV (RIFF) writer for interleaved float PCM → 16/24-bit integer or 32-bit float. */
object Wav {
    fun write(out: OutputStream, pcm: FloatArray, sampleRate: Int, channels: Int = 2, bits: Int = 24) {
        val bytesPer = bits / 8
        val dataLen = pcm.size.toLong() * bytesPer
        val fmtTag = if (bits == 32) 3 else 1
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.write("RIFF".toByteArray()); out.write(le32(36 + dataLen)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(fmtTag)); out.write(le16(channels))
        out.write(le32(sampleRate.toLong())); out.write(le32(sampleRate.toLong() * channels * bytesPer)); out.write(le16(channels * bytesPer)); out.write(le16(bits))
        out.write("data".toByteArray()); out.write(le32(dataLen))
        val buf = ByteArray(minOf(pcm.size, 65536) * bytesPer)
        var i = 0
        while (i < pcm.size) {
            val n = minOf(pcm.size - i, buf.size / bytesPer)
            for (k in 0 until n) {
                val v = pcm[i + k].coerceIn(-1f, 1f)
                when (bits) {
                    16 -> { val s = (v * 32767).roundToInt(); buf[k * 2] = s.toByte(); buf[k * 2 + 1] = (s shr 8).toByte() }
                    24 -> { val s = (v * 8388607).roundToInt(); buf[k * 3] = s.toByte(); buf[k * 3 + 1] = (s shr 8).toByte(); buf[k * 3 + 2] = (s shr 16).toByte() }
                    else -> { val b = java.lang.Float.floatToIntBits(v); buf[k * 4] = b.toByte(); buf[k * 4 + 1] = (b shr 8).toByte(); buf[k * 4 + 2] = (b shr 16).toByte(); buf[k * 4 + 3] = (b shr 24).toByte() }
                }
            }
            out.write(buf, 0, n * bytesPer)
            i += n
        }
    }
}

/** SRT / WebVTT / ASS import and export. Times keep millisecond precision. */
object CaptionIO {
    private fun ms(t: Time) = (t.toDouble() * 1000).roundToInt().toLong()
    private fun srtTime(t: Time): String { val m = ms(t); return "%02d:%02d:%02d,%03d".format(m / 3600000, m / 60000 % 60, m / 1000 % 60, m % 1000) }
    private fun vttTime(t: Time): String = srtTime(t).replace(',', '.')
    private fun assTime(t: Time): String { val cs = (t.toDouble() * 100).roundToInt(); return "%d:%02d:%02d.%02d".format(cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100) }

    fun toSrt(segs: List<CaptionSegment>, useTranslation: Boolean = false) = buildString {
        segs.forEachIndexed { i, s ->
            append(i + 1).append('\n').append(srtTime(s.start)).append(" --> ").append(srtTime(s.end)).append('\n')
            append(if (useTranslation && s.translation != null) s.translation else s.text).append("\n\n")
        }
    }

    fun toVtt(segs: List<CaptionSegment>, useTranslation: Boolean = false, wordTimings: Boolean = true) = buildString {
        append("WEBVTT\n\n")
        segs.forEach { s ->
            append(vttTime(s.start)).append(" --> ").append(vttTime(s.end)).append('\n')
            if (wordTimings && !useTranslation && s.words.size > 1) {
                s.words.forEachIndexed { i, w -> if (i > 0) append(" <").append(vttTime(w.start)).append(">"); append(w.text) }
            } else append(if (useTranslation && s.translation != null) s.translation else s.text)
            append("\n\n")
        }
    }

    fun toAss(segs: List<CaptionSegment>, width: Int, height: Int, fontName: String = "Noto Sans Arabic", karaoke: Boolean = true) = buildString {
        append("[Script Info]\nScriptType: v4.00+\nPlayResX: $width\nPlayResY: $height\nWrapStyle: 0\n\n")
        append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        append("Style: Default,$fontName,${height / 24},&H00FFFFFF,&H0000FFFF,&H00000000,&H64000000,-1,0,0,0,100,100,0,0,1,3,1,2,40,40,${height / 12},1\n\n")
        append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        segs.forEach { s ->
            val text = if (karaoke && s.words.isNotEmpty()) s.words.joinToString(" ") { w -> "{\\k${max(1, ((w.end.toDouble() - w.start.toDouble()) * 100).roundToInt())}}${w.text}" } else s.text
            append("Dialogue: 0,${assTime(s.start)},${assTime(s.end)},Default,spk${s.speaker},0,0,0,,${text.replace("\n", "\\N")}\n")
        }
    }

    private val tsSrt = Regex("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
    private fun parseTs(s: String): Time? {
        val m = tsSrt.find(s) ?: Regex("(\\d{2}):(\\d{2})[.](\\d{3})").find(s)?.let { mm ->
            return Rational.millis(mm.groupValues[1].toLong() * 60000 + mm.groupValues[2].toLong() * 1000 + mm.groupValues[3].toLong())
        } ?: return null
        val (h, mi, se, f) = m.destructured
        val msPart = f.padEnd(3, '0').take(3).toLong()
        return Rational.millis(h.toLong() * 3600000 + mi.toLong() * 60000 + se.toLong() * 1000 + msPart)
    }

    /** Parses SRT or WebVTT (auto-detected). Word timings are distributed by character length when absent. */
    fun parse(text: String): List<CaptionSegment> {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
        if (clean.trimStart().startsWith("[Script Info]")) return parseAss(clean)
        val blocks = clean.split(Regex("\n\\s*\n"))
        val out = ArrayList<CaptionSegment>()
        for (b in blocks) {
            val lines = b.lines().filter { it.isNotBlank() }
            val ti = lines.indexOfFirst { "-->" in it }
            if (ti < 0) continue
            val (a, z) = lines[ti].split("-->").map { it.trim() }
            val start = parseTs(a) ?: continue; val end = parseTs(z) ?: continue
            val body = lines.drop(ti + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
            if (body.isEmpty()) continue
            out += CaptionSegment("seg-${out.size + 1}", start, end, distributeWords(body, start, end))
        }
        return out
    }

    private fun parseAss(text: String): List<CaptionSegment> {
        val out = ArrayList<CaptionSegment>()
        fun t(s: String): Time { val p = s.trim().split(':', '.'); return Rational.millis(p[0].toLong() * 3600000 + p[1].toLong() * 60000 + p[2].toLong() * 1000 + p[3].toLong() * 10) }
        for (l in text.lines()) if (l.startsWith("Dialogue:")) {
            val parts = l.removePrefix("Dialogue:").split(',', limit = 10)
            if (parts.size < 10) continue
            val body = parts[9].replace(Regex("\\{[^}]*}"), "").replace("\\N", "\n").trim()
            out += CaptionSegment("seg-${out.size + 1}", t(parts[1]), t(parts[2]), distributeWords(body, t(parts[1]), t(parts[2])))
        }
        return out
    }

    fun distributeWords(body: String, start: Time, end: Time): List<CaptionWord> {
        val words = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val total = words.sumOf { it.length }.coerceAtLeast(1)
        var acc = 0
        val dur = end - start
        return words.map { w ->
            val s = start + dur * Rational.of(acc.toLong(), total.toLong())
            acc += w.length
            val e = start + dur * Rational.of(acc.toLong(), total.toLong())
            CaptionWord(w, s, e, confidence = 1.0)
        }
    }

    /** Splits long segments so each line has at most [maxWords] words and [maxChars] characters. */
    fun resegment(segs: List<CaptionSegment>, maxWords: Int, maxChars: Int): List<CaptionSegment> {
        val out = ArrayList<CaptionSegment>()
        for (s in segs) {
            var cur = ArrayList<CaptionWord>(); var chars = 0
            fun flush() { if (cur.isNotEmpty()) { out += s.copy(id = "${s.id}.${out.size}", start = cur.first().start, end = cur.last().end, words = cur, translation = null); cur = ArrayList(); chars = 0 } }
            for (w in s.words) {
                if (cur.size >= maxWords || (chars + w.text.length > maxChars && cur.isNotEmpty())) flush()
                cur += w; chars += w.text.length + 1
                if (w.text.endsWith('.') || w.text.endsWith('؟') || w.text.endsWith('?') || w.text.endsWith('!') || w.text.endsWith('،')) { if (cur.size >= 3) flush() }
            }
            flush()
        }
        return out
    }
}

/**
 * Animated GIF encoder: per-frame median-cut palette (256), Floyd–Steinberg dithering,
 * LZW compression, infinite loop. Frames are ARGB int arrays.
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int, private val delayCs: Int, private val dither: Boolean = true) {
    private var first = true

    fun addFrame(argb: IntArray) {
        require(argb.size == width * height)
        val (palette, indices) = quantize(argb)
        if (first) {
            out.write("GIF89a".toByteArray())
            w16(width); w16(height)
            out.write(0x70) // no global colour table, 8-bit colour resolution
            out.write(0); out.write(0)
            // NETSCAPE loop extension
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray()); out.write(byteArrayOf(3, 1, 0, 0, 0))
            first = false
        }
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04)); w16(delayCs); out.write(0); out.write(0)
        out.write(0x2C); w16(0); w16(0); w16(width); w16(height)
        out.write(0x87) // local colour table, 256 entries
        for (i in 0 until 256) { val c = palette.getOrElse(i) { 0 }; out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF) }
        lzw(indices)
    }

    fun finish() { out.write(0x3B); out.flush() }

    private fun w16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    private fun quantize(px: IntArray): Pair<IntArray, ByteArray> {
        // Median cut on 5-bit-per-channel histogram.
        val hist = IntArray(32768)
        for (c in px) hist[((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)]++
        data class Box(val colors: MutableList<Int>)
        var boxes = mutableListOf(Box(hist.indices.filter { hist[it] > 0 }.toMutableList()))
        while (boxes.size < 256) {
            val b = boxes.filter { it.colors.size > 1 }.maxByOrNull { bx -> bx.colors.sumOf { hist[it] } } ?: break
            val ranges = (0..2).map { ch -> val v = b.colors.map { (it shr (10 - 5 * ch)) and 31 }; (v.maxOrNull()!! - v.minOrNull()!!) to ch }
            val ch = ranges.maxByOrNull { it.first }!!.second
            b.colors.sortBy { (it shr (10 - 5 * ch)) and 31 }
            var acc = 0; val total = b.colors.sumOf { hist[it] }; var split = 0
            for ((i, c) in b.colors.withIndex()) { acc += hist[c]; if (acc >= total / 2) { split = max(1, i); break } }
            if (split >= b.colors.size) split = b.colors.size - 1
            boxes.remove(b)
            boxes += Box(b.colors.subList(0, split).toMutableList()); boxes += Box(b.colors.subList(split, b.colors.size).toMutableList())
        }
        val palette = IntArray(256)
        val lookup = IntArray(32768) { -1 }
        boxes.forEachIndexed { i, b ->
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            for (c in b.colors) { val w = hist[c].toLong(); r += ((c shr 10) and 31) * w; g += ((c shr 5) and 31) * w; bl += (c and 31) * w; n += w }
            if (n > 0) palette[i] = (((r / n) * 255 / 31).toInt() shl 16) or (((g / n) * 255 / 31).toInt() shl 8) or ((bl / n) * 255 / 31).toInt()
        }
        fun nearest(r: Int, g: Int, b: Int): Int {
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            if (lookup[key] >= 0) return lookup[key]
            var best = 0; var bd = Int.MAX_VALUE
            for (i in 0 until boxes.size) { val c = palette[i]; val dr = ((c shr 16) and 0xFF) - r; val dg = ((c shr 8) and 0xFF) - g; val db = (c and 0xFF) - b
                val d = dr * dr * 2 + dg * dg * 4 + db * db * 3; if (d < bd) { bd = d; best = i } }
            lookup[key] = best; return best
        }
        val idx = ByteArray(px.size)
        val err = if (dither) Array(2) { IntArray((width + 2) * 3) } else null
        for (y in 0 until height) {
            if (err != null) { val t = err[0]; err[0] = err[1]; err[1] = t; java.util.Arrays.fill(err[1], 0) }
            for (x in 0 until width) {
                val c = px[y * width + x]
                var r = (c shr 16) and 0xFF; var g = (c shr 8) and 0xFF; var b = c and 0xFF
                if (err != null) { r = (r + err[0][(x + 1) * 3] / 16).coerceIn(0, 255); g = (g + err[0][(x + 1) * 3 + 1] / 16).coerceIn(0, 255); b = (b + err[0][(x + 1) * 3 + 2] / 16).coerceIn(0, 255) }
                val i = nearest(r, g, b)
                idx[y * width + x] = i.toByte()
                if (err != null) {
                    val p = palette[i]; val er = r - ((p shr 16) and 0xFF); val eg = g - ((p shr 8) and 0xFF); val eb = b - (p and 0xFF)
                    fun add(row: Int, xx: Int, w: Int) { val o = (xx + 1) * 3; err[row][o] += er * w; err[row][o + 1] += eg * w; err[row][o + 2] += eb * w }
                    if (x + 1 < width) add(0, x + 1, 7)
                    if (x > 0) add(1, x - 1, 3)
                    add(1, x, 5)
                    if (x + 1 < width) add(1, x + 1, 1)
                }
            }
        }
        return palette to idx
    }

    private fun lzw(indices: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val buf = ByteArrayOutputStream()
        var bitBuf = 0; var bitCount = 0
        val block = ByteArray(255); var blockLen = 0
        fun flushBlock() { if (blockLen > 0) { buf.write(blockLen); buf.write(block, 0, blockLen); blockLen = 0 } }
        fun emit(code: Int, size: Int) {
            bitBuf = bitBuf or (code shl bitCount); bitCount += size
            while (bitCount >= 8) { block[blockLen++] = (bitBuf and 0xFF).toByte(); bitBuf = bitBuf ushr 8; bitCount -= 8; if (blockLen == 255) flushBlock() }
        }
        val clear = 1 shl minCode; val eoi = clear + 1
        var codeSize = minCode + 1
        var next = eoi + 1
        val dict = HashMap<Int, Int>(8192)
        emit(clear, codeSize)
        if (indices.isEmpty()) { emit(eoi, codeSize) } else {
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val found = dict[key]
                if (found != null) { prefix = found; continue }
                emit(prefix, codeSize)
                if (next < 4096) {
                    dict[key] = next++
                    if (next > (1 shl codeSize) && codeSize < 12) codeSize++
                } else { emit(clear, codeSize); dict.clear(); codeSize = minCode + 1; next = eoi + 1 }
                prefix = k
            }
            emit(prefix, codeSize)
            emit(eoi, codeSize)
        }
        if (bitCount > 0) { block[blockLen++] = (bitBuf and 0xFF).toByte(); if (blockLen == 255) flushBlock() }
        flushBlock()
        buf.write(0)
        out.write(buf.toByteArray())
    }
}

/** Streaming BS.1770 integrated loudness (keeps 100 ms sub-block energies). */
class LoudnessMeter(private val fs: Double) {
    private val shelf = app.bekocat.core.audio.Biquad.highShelf(fs, 1681.974450955533, 3.999843853973347, 1.0)
    private val hp = app.bekocat.core.audio.Biquad.highPass(fs, 38.13547087602444, 0.5003270373238773)
    private val sub = (0.1 * fs).toInt()
    private var acc = 0.0; private var n = 0
    private val subs = ArrayList<Double>()
    var peak = 0.0; private set

    fun add(buf: FloatArray) {
        var i = 0
        while (i + 1 < buf.size) {
            val l = hp.process(shelf.process(buf[i].toDouble(), 0), 0); val r = hp.process(shelf.process(buf[i + 1].toDouble(), 1), 1)
            acc += l * l + r * r; n++
            peak = max(peak, max(abs(buf[i].toDouble()), abs(buf[i + 1].toDouble())))
            if (n == sub) { subs += acc / sub; acc = 0.0; n = 0 }
            i += 2
        }
    }

    fun integrated(): Double? {
        if (subs.size < 4) return null
        val blocks = (0..subs.size - 4).map { (subs[it] + subs[it + 1] + subs[it + 2] + subs[it + 3]) / 4 }
        fun l(ms: Double) = -0.691 + 10 * log10(ms + 1e-20)
        val abs = blocks.filter { l(it) > -70 }
        if (abs.isEmpty()) return null
        val rel = l(abs.average()) - 10
        val g = abs.filter { l(it) > rel }
        return if (g.isEmpty()) null else l(g.average())
    }
}
