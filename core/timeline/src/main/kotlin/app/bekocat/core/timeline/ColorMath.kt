package app.bekocat.core.timeline

/** Colour helpers shared by the GPU engine (LUT parsing, curve tables). Pure and unit-tested. */
object ColorMath {

    data class Lut3d(val size: Int, val data: FloatArray, val title: String) // RGB triplets, red fastest

    /** Parses an Adobe/Resolve .cube 3D LUT. Domain must be 0..1 (DOMAIN_MIN/MAX respected). */
    fun parseCube(text: String): Lut3d {
        var size = 0; var title = ""
        var dmin = floatArrayOf(0f, 0f, 0f); var dmax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> title = line.substringAfter(' ').trim().trim('"')
                "LUT_3D_SIZE" -> size = parts[1].toInt()
                "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs are not supported; use a 3D .cube")
                "DOMAIN_MIN" -> dmin = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                "DOMAIN_MAX" -> dmax = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                "LUT_3D_INPUT_RANGE" -> { dmin = FloatArray(3) { parts[1].toFloat() }; dmax = FloatArray(3) { parts[2].toFloat() } }
                else -> if (parts.size >= 3 && parts[0].toFloatOrNull() != null) {
                    for (i in 0 until 3) values += ((parts[i].toFloat() - dmin[i]) / (dmax[i] - dmin[i])).coerceIn(0f, 1f)
                }
            }
        }
        require(size in 2..128) { "Invalid LUT_3D_SIZE $size" }
        require(values.size == size * size * size * 3) { "Expected ${size * size * size} entries, found ${values.size / 3}" }
        return Lut3d(size, values.toFloatArray(), title)
    }

    /** Identity LUT of [size]. */
    fun identity(size: Int) = Lut3d(size, FloatArray(size * size * size * 3).also { d ->
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            d[i++] = r / (size - 1f); d[i++] = g / (size - 1f); d[i++] = b / (size - 1f)
        }
    }, "identity")

    /**
     * 256-entry table for a curve through control points (x,y flattened, 0..1), using
     * monotone cubic (Fritsch–Carlson) interpolation so curves never overshoot.
     */
    fun curveTable(points: List<Double>): IntArray {
        val pts = points.chunked(2).filter { it.size == 2 }.map { it[0].coerceIn(0.0, 1.0) to it[1].coerceIn(0.0, 1.0) }.sortedBy { it.first }
        if (pts.size < 2) return IntArray(256) { it }
        val n = pts.size
        val xs = pts.map { it.first }; val ys = pts.map { it.second }
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]).coerceAtLeast(1e-9) }
        val m = DoubleArray(n) { i -> if (i == 0) d[0] else if (i == n - 1) d[n - 2] else if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2 }
        for (i in 0 until n - 1) {
            if (d[i] == 0.0) { m[i] = 0.0; m[i + 1] = 0.0; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9) { val t = 3 / Math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        return IntArray(256) { k ->
            val x = k / 255.0
            val y = when {
                x <= xs.first() -> ys.first()
                x >= xs.last() -> ys.last()
                else -> {
                    val i = xs.indexOfLast { it <= x }.coerceAtMost(n - 2)
                    val h = xs[i + 1] - xs[i]; val t = (x - xs[i]) / h
                    val h00 = 2 * t * t * t - 3 * t * t + 1; val h10 = t * t * t - 2 * t * t + t
                    val h01 = -2 * t * t * t + 3 * t * t; val h11 = t * t * t - t * t
                    h00 * ys[i] + h10 * h * m[i] + h01 * ys[i + 1] + h11 * h * m[i + 1]
                }
            }
            (y.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        }
    }

    /** RGBA byte table for the GPU: R,G,B channel curves and master curve in A. */
    fun curvesRgba(c: ColorAdjust): ByteArray {
        val r = curveTable(c.curveRed); val g = curveTable(c.curveGreen); val b = curveTable(c.curveBlue); val m = curveTable(c.curveMaster)
        return ByteArray(256 * 4) { i -> when (i % 4) { 0 -> r[i / 4]; 1 -> g[i / 4]; 2 -> b[i / 4]; else -> m[i / 4] }.toByte() }
    }

    fun hasCurves(c: ColorAdjust) = c.curveMaster.size >= 4 || c.curveRed.size >= 4 || c.curveGreen.size >= 4 || c.curveBlue.size >= 4
    fun hasHsl(c: ColorAdjust) = c.hslHue.any { it != 0.0 } || c.hslSat.any { it != 0.0 } || c.hslLum.any { it != 0.0 }

    /** Average colour of a region expressed as a ColorAdjust that matches [source] to [target] (shot matching). */
    fun matchStats(srcMean: DoubleArray, srcStd: DoubleArray, dstMean: DoubleArray, dstStd: DoubleArray): ColorAdjust {
        // Per-channel gain/lift so that mean and spread of luma-normalised channels match (Reinhard-style).
        val gain = List(3) { i -> ((dstStd[i] / srcStd[i].coerceAtLeast(1e-3)) - 1.0).coerceIn(-0.5, 0.5) }
        val lift = List(3) { i -> (dstMean[i] - srcMean[i] * (1 + gain[i])).coerceIn(-0.3, 0.3) }
        return ColorAdjust(gain = gain, lift = lift)
    }
}
