package app.bekocat.core.timeline

/**
 * Small dependency-free JSON tree + parser/serialiser. Kept local so the project
 * schema has no reflection or code-generation step and parsing of untrusted
 * .bekocat packages is bounded (depth and size limits).
 */
sealed class JsonValue {
    data class Obj(val map: LinkedHashMap<String, JsonValue>) : JsonValue() {
        operator fun get(k: String) = map[k]
        fun str(k: String) = (map[k] as? Str)?.v ?: throw JsonException("Missing string '$k'")
        fun strOr(k: String, d: String?) = (map[k] as? Str)?.v ?: d
        fun num(k: String) = (map[k] as? Num)?.v ?: throw JsonException("Missing number '$k'")
        fun numOr(k: String, d: Double) = (map[k] as? Num)?.v ?: d
        fun bool(k: String, d: Boolean = false) = (map[k] as? Bool)?.v ?: d
        fun arr(k: String) = (map[k] as? Arr)?.items ?: emptyList()
        fun obj(k: String) = map[k] as? Obj
    }
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val v: String) : JsonValue()
    data class Num(val v: Double) : JsonValue()
    data class Bool(val v: Boolean) : JsonValue()
    object Null : JsonValue()
}

class JsonException(msg: String) : RuntimeException(msg)

fun jsonObj(vararg pairs: Pair<String, JsonValue?>): JsonValue.Obj {
    val m = LinkedHashMap<String, JsonValue>()
    for ((k, v) in pairs) if (v != null) m[k] = v
    return JsonValue.Obj(m)
}

fun String.js() = JsonValue.Str(this)
fun Number.js() = JsonValue.Num(this.toDouble())
fun Boolean.js() = JsonValue.Bool(this)
fun List<JsonValue>.js() = JsonValue.Arr(this)

object Json {
    const val MAX_DEPTH = 64
    const val MAX_LENGTH = 64 * 1024 * 1024

    fun write(v: JsonValue, pretty: Boolean = true): String = StringBuilder().also { write(v, it, if (pretty) 0 else -1) }.toString()

    private fun write(v: JsonValue, sb: StringBuilder, indent: Int) {
        val nl = indent >= 0
        fun pad(n: Int) { if (nl) { sb.append('\n'); repeat(n) { sb.append("  ") } } }
        when (v) {
            is JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(v.v)
            is JsonValue.Num -> {
                val d = v.v
                if (d == Math.rint(d) && kotlin.math.abs(d) < 9.0e15) sb.append(d.toLong()) else sb.append(d)
            }
            is JsonValue.Str -> quote(v.v, sb)
            is JsonValue.Arr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.items.forEachIndexed { i, it ->
                    if (i > 0) sb.append(',')
                    pad(indent + 1); write(it, sb, if (nl) indent + 1 else -1)
                }
                pad(indent); sb.append(']')
            }
            is JsonValue.Obj -> {
                if (v.map.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v.map) {
                    if (!first) sb.append(',')
                    first = false
                    pad(indent + 1); quote(k, sb); sb.append(if (nl) ": " else ":")
                    write(value, sb, if (nl) indent + 1 else -1)
                }
                pad(indent); sb.append('}')
            }
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    fun parse(text: String): JsonValue {
        if (text.length > MAX_LENGTH) throw JsonException("JSON too large")
        val p = Parser(text)
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw JsonException("Trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun err(m: String): Nothing = throw JsonException("$m at $i")

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) err("Too deep")
            ws()
            if (i >= s.length) err("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonValue.Str(str())
                't' -> lit("true", JsonValue.Bool(true))
                'f' -> lit("false", JsonValue.Bool(false))
                'n' -> lit("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) num() else err("Unexpected '$c'")
            }
        }

        fun lit(w: String, v: JsonValue): JsonValue {
            if (!s.startsWith(w, i)) err("Expected $w")
            i += w.length; return v
        }

        fun num(): JsonValue {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return JsonValue.Num(s.substring(st, i).toDoubleOrNull() ?: err("Bad number"))
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) err("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) err("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) err("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> err("Bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(depth: Int): JsonValue {
            i++
            val items = ArrayList<JsonValue>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return JsonValue.Arr(items) }
            while (true) {
                items += value(depth + 1)
                ws()
                if (i >= s.length) err("Unterminated array")
                when (s[i++]) { ',' -> continue; ']' -> return JsonValue.Arr(items); else -> err("Expected , or ]") }
            }
        }

        fun obj(depth: Int): JsonValue {
            i++
            val m = LinkedHashMap<String, JsonValue>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return JsonValue.Obj(m) }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') err("Expected key")
                val k = str()
                ws()
                if (i >= s.length || s[i++] != ':') err("Expected :")
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) err("Unterminated object")
                when (s[i++]) { ',' -> continue; '}' -> return JsonValue.Obj(m); else -> err("Expected , or }") }
            }
        }
    }
}
