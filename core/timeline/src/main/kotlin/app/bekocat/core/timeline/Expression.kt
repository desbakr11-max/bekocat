package app.bekocat.core.timeline

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.floor

/**
 * Safe property expressions. A tiny arithmetic language with no loops, no I/O,
 * no reflection and bounded size — so a project imported from anywhere cannot
 * execute code. Results are deterministic (seeded noise), so renders reproduce.
 *
 * Variables: time, value, pi, inPoint, outPoint (keyframe range)
 * Functions: sin cos tan asin acos atan atan2 abs min max clamp floor ceil round sqrt pow exp log
 *            lerp(a,b,t) step(e,x) smoothstep(a,b,x) noise(x[,seed]) random(seed)
 *            wiggle(freq, amp[, seed[, octaves]]) valueAt(t) loopCycle() loopPingPong()
 */
object Expression {
    private const val MAX_LEN = 512
    private const val MAX_DEPTH = 32
    private val cache = ConcurrentHashMap<String, Node>()

    class ExpressionException(msg: String) : IllegalArgumentException(msg)

    sealed class Node
    private data class Num(val v: Double) : Node()
    private data class Var(val name: String) : Node()
    private data class Unary(val op: Char, val a: Node) : Node()
    private data class Bin(val op: Char, val a: Node, val b: Node) : Node()
    private data class Call(val fn: String, val args: List<Node>) : Node()

    private val functions = setOf(
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "abs", "min", "max", "clamp", "floor", "ceil", "round",
        "sqrt", "pow", "exp", "log", "lerp", "step", "smoothstep", "noise", "random", "wiggle", "valueat", "loopcycle", "looppingpong",
    )

    fun parse(src: String): Node = cache.getOrPut(src) {
        if (src.length > MAX_LEN) throw ExpressionException("Expression too long")
        val p = P(src)
        val n = p.expr(0)
        p.ws()
        if (p.i != src.length) throw ExpressionException("Unexpected '${src[p.i]}' at ${p.i}")
        n
    }

    /** Validates without evaluating; returns an error message or null. */
    fun validate(src: String): String? = try { parse(src); null } catch (e: ExpressionException) { e.message }

    fun evaluate(src: String, value: Double, time: Double, prop: Animatable? = null): Double =
        Ctx(value, time, prop).eval(parse(src), 0)

    /** Evaluates; on any error returns [fallback] so a bad expression never breaks rendering. */
    fun evaluateOr(src: String, fallback: Double, time: Double, prop: Animatable?): Double = try {
        val r = evaluate(src, fallback, time, prop)
        if (r.isFinite()) r else fallback
    } catch (_: Exception) { fallback }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun err(m: String): Nothing = throw ExpressionException("$m at $i")

        fun expr(d: Int): Node {
            if (d > MAX_DEPTH) err("Too deep")
            var a = term(d + 1)
            while (true) {
                ws()
                if (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; a = Bin(op, a, term(d + 1)) } else return a
            }
        }

        fun term(d: Int): Node {
            var a = unary(d + 1)
            while (true) {
                ws()
                if (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) { val op = s[i++]; a = Bin(op, a, unary(d + 1)) } else return a
            }
        }

        fun unary(d: Int): Node {
            if (d > MAX_DEPTH) err("Too deep")
            ws()
            if (i < s.length && (s[i] == '-' || s[i] == '+')) { val op = s[i++]; return Unary(op, unary(d + 1)) }
            return power(d + 1)
        }

        fun power(d: Int): Node {
            val base = atom(d + 1)
            ws()
            if (i < s.length && s[i] == '^') { i++; return Bin('^', base, unary(d + 1)) }
            return base
        }

        fun atom(d: Int): Node {
            if (d > MAX_DEPTH) err("Too deep")
            ws()
            if (i >= s.length) err("Unexpected end")
            val c = s[i]
            if (c == '(') { i++; val e = expr(d + 1); ws(); if (i >= s.length || s[i++] != ')') err("Expected )"); return e }
            if (c.isDigit() || c == '.') {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                            ((s[i] == '-' || s[i] == '+') && i > st && (s[i - 1] == 'e' || s[i - 1] == 'E')))) i++
                return Num(s.substring(st, i).toDoubleOrNull() ?: err("Bad number"))
            }
            if (c.isLetter() || c == '_') {
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val name = s.substring(st, i).lowercase()
                ws()
                if (i < s.length && s[i] == '(') {
                    if (name !in functions) err("Unknown function '$name'")
                    i++
                    val args = ArrayList<Node>()
                    ws()
                    if (i < s.length && s[i] == ')') { i++; return Call(name, args) }
                    while (true) {
                        args += expr(d + 1)
                        ws()
                        if (i >= s.length) err("Unterminated call")
                        when (s[i++]) { ',' -> continue; ')' -> return Call(name, args); else -> err("Expected , or )") }
                    }
                }
                if (name !in setOf("time", "value", "pi", "inpoint", "outpoint")) err("Unknown variable '$name'")
                return Var(name)
            }
            err("Unexpected '$c'")
        }
    }

    private class Ctx(val value: Double, val time: Double, val prop: Animatable?) {
        fun eval(n: Node, depth: Int): Double {
            if (depth > 64) throw ExpressionException("Too deep")
            return when (n) {
                is Num -> n.v
                is Var -> when (n.name) {
                    "time" -> time; "value" -> value; "pi" -> PI
                    "inpoint" -> prop?.keyframes?.firstOrNull()?.time?.toDouble() ?: 0.0
                    "outpoint" -> prop?.keyframes?.lastOrNull()?.time?.toDouble() ?: 0.0
                    else -> 0.0
                }
                is Unary -> if (n.op == '-') -eval(n.a, depth + 1) else eval(n.a, depth + 1)
                is Bin -> {
                    val a = eval(n.a, depth + 1); val b = eval(n.b, depth + 1)
                    when (n.op) {
                        '+' -> a + b; '-' -> a - b; '*' -> a * b; '/' -> a / b
                        '%' -> if (b == 0.0) 0.0 else a - b * floor(a / b)
                        '^' -> Math.pow(a, b)
                        else -> 0.0
                    }
                }
                is Call -> call(n.fn, n.args.map { eval(it, depth + 1) })
            }
        }

        private fun arg(a: List<Double>, i: Int, d: Double = 0.0) = a.getOrElse(i) { d }

        private fun call(fn: String, a: List<Double>): Double = when (fn) {
            "sin" -> Math.sin(arg(a, 0)); "cos" -> Math.cos(arg(a, 0)); "tan" -> Math.tan(arg(a, 0))
            "asin" -> Math.asin(arg(a, 0)); "acos" -> Math.acos(arg(a, 0)); "atan" -> Math.atan(arg(a, 0))
            "atan2" -> Math.atan2(arg(a, 0), arg(a, 1))
            "abs" -> Math.abs(arg(a, 0))
            "min" -> a.minOrNull() ?: 0.0
            "max" -> a.maxOrNull() ?: 0.0
            "clamp" -> arg(a, 0).coerceIn(minOf(arg(a, 1), arg(a, 2)), maxOf(arg(a, 1), arg(a, 2)))
            "floor" -> floor(arg(a, 0)); "ceil" -> Math.ceil(arg(a, 0)); "round" -> Math.rint(arg(a, 0))
            "sqrt" -> Math.sqrt(arg(a, 0)); "pow" -> Math.pow(arg(a, 0), arg(a, 1))
            "exp" -> Math.exp(arg(a, 0)); "log" -> Math.log(arg(a, 0))
            "lerp" -> arg(a, 0) + (arg(a, 1) - arg(a, 0)) * arg(a, 2)
            "step" -> if (arg(a, 1) >= arg(a, 0)) 1.0 else 0.0
            "smoothstep" -> { val t = ((arg(a, 2) - arg(a, 0)) / (arg(a, 1) - arg(a, 0))).coerceIn(0.0, 1.0); t * t * (3 - 2 * t) }
            "noise" -> Noise.smooth(arg(a, 0), arg(a, 1).toLong())
            "random" -> Noise.hash01(arg(a, 0).toLong(), 0)
            "wiggle" -> {
                val freq = arg(a, 0, 1.0); val amp = arg(a, 1, 0.0); val seed = arg(a, 2).toLong()
                val oct = arg(a, 3, 1.0).toInt().coerceIn(1, 6)
                value + amp * Noise.fractal(time * freq, seed, oct)
            }
            "valueat" -> prop?.let { KeyframeEvaluator.evaluate(it, Rational.fromDouble(arg(a, 0), 1_000_000)) } ?: value
            "loopcycle" -> loop(pingPong = false)
            "looppingpong" -> loop(pingPong = true)
            else -> 0.0
        }

        private fun loop(pingPong: Boolean): Double {
            val p = prop ?: return value
            if (p.keyframes.size < 2) return value
            val s = p.keyframes.first().time.toDouble()
            val e = p.keyframes.last().time.toDouble()
            val len = e - s
            if (len <= 0 || time <= e) return value
            val k = (time - s) / len
            var u = k - floor(k)
            if (pingPong && floor(k).toLong() % 2L == 1L) u = 1 - u
            return KeyframeEvaluator.evaluate(p, Rational.fromDouble(s + u * len, 1_000_000))
        }
    }
}

/** Deterministic seeded noise used by expressions, wiggle, grain and glitch effects. */
object Noise {
    fun hash01(x: Long, seed: Long): Double {
        var h = x * -0x61c8864680b583ebL + seed * 0x5851f42d4c957f2dL
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /** Smooth value noise in [-1, 1]. */
    fun smooth(x: Double, seed: Long): Double {
        val i = floor(x).toLong()
        val f = x - i
        val u = f * f * (3 - 2 * f)
        val a = hash01(i, seed) * 2 - 1
        val b = hash01(i + 1, seed) * 2 - 1
        return a + (b - a) * u
    }

    fun fractal(x: Double, seed: Long, octaves: Int): Double {
        var sum = 0.0; var amp = 1.0; var freq = 1.0; var norm = 0.0
        repeat(octaves) { o ->
            sum += amp * smooth(x * freq, seed + o * 7919L)
            norm += amp; amp *= 0.5; freq *= 2.0
        }
        return sum / norm
    }
}
