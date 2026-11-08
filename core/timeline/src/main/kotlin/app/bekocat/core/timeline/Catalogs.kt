package app.bekocat.core.timeline

/** Parameter of an effect; drives generic inspector UI and validation. */
data class ParamSpec(val key: String, val ar: String, val en: String, val min: Double, val max: Double, val default: Double)

data class EffectSpec(
    val type: String, val ar: String, val en: String, val category: String,
    val params: List<ParamSpec>, val audio: Boolean = false,
) {
    fun instance(id: String) = EffectInstance(id, type, params = params.associate { it.key to Animatable.of(it.default) })
}

private fun p(key: String, ar: String, en: String, min: Double, max: Double, def: Double) = ParamSpec(key, ar, en, min, max, def)
private val AMOUNT = p("amount", "الشدة", "Amount", 0.0, 1.0, 0.5)

/** All visual and audio effects implemented by the engine. Every entry is rendered by a real shader/DSP stage. */
object EffectCatalog {
    val video: List<EffectSpec> = listOf(
        EffectSpec("gaussian_blur", "ضبابية", "Gaussian Blur", "blur", listOf(p("radius", "نصف القطر", "Radius", 0.0, 64.0, 8.0))),
        EffectSpec("directional_blur", "ضبابية اتجاهية", "Directional Blur", "blur", listOf(p("length", "الطول", "Length", 0.0, 0.2, 0.03), p("angle", "الزاوية", "Angle", 0.0, 360.0, 0.0))),
        EffectSpec("radial_blur", "ضبابية دائرية", "Radial Blur", "blur", listOf(p("angle", "الزاوية", "Angle", 0.0, 30.0, 5.0), p("cx", "المركز X", "Center X", 0.0, 1.0, 0.5), p("cy", "المركز Y", "Center Y", 0.0, 1.0, 0.5))),
        EffectSpec("zoom_blur", "ضبابية تقريب", "Zoom Blur", "blur", listOf(p("strength", "القوة", "Strength", 0.0, 0.5, 0.1), p("cx", "المركز X", "Center X", 0.0, 1.0, 0.5), p("cy", "المركز Y", "Center Y", 0.0, 1.0, 0.5))),
        EffectSpec("lens_blur", "ضبابية عدسة", "Lens Blur", "blur", listOf(p("radius", "نصف القطر", "Radius", 0.0, 40.0, 10.0), p("highlights", "إبراز النقاط", "Highlights", 0.0, 1.0, 0.3))),
        EffectSpec("motion_blur", "ضبابية حركة", "Motion Blur (shutter)", "blur", listOf(p("shutter", "زاوية الغالق", "Shutter angle", 0.0, 360.0, 180.0))),
        EffectSpec("sharpen", "حدّة", "Sharpen", "detail", listOf(p("amount", "الشدة", "Amount", 0.0, 3.0, 0.6), p("radius", "نصف القطر", "Radius", 0.5, 4.0, 1.0))),
        EffectSpec("denoise", "إزالة التشويش", "Denoise", "detail", listOf(p("strength", "القوة", "Strength", 0.0, 1.0, 0.4))),
        EffectSpec("glow", "توهج", "Glow", "light", listOf(p("threshold", "العتبة", "Threshold", 0.0, 1.0, 0.6), p("radius", "نصف القطر", "Radius", 0.0, 64.0, 16.0), p("intensity", "الشدة", "Intensity", 0.0, 3.0, 1.0))),
        EffectSpec("bloom", "إشعاع", "Bloom", "light", listOf(p("threshold", "العتبة", "Threshold", 0.0, 1.0, 0.75), p("radius", "نصف القطر", "Radius", 0.0, 96.0, 32.0), p("intensity", "الشدة", "Intensity", 0.0, 2.0, 0.6))),
        EffectSpec("halation", "هالة فيلمية", "Halation", "film", listOf(p("threshold", "العتبة", "Threshold", 0.0, 1.0, 0.7), p("radius", "نصف القطر", "Radius", 0.0, 64.0, 20.0), p("intensity", "الشدة", "Intensity", 0.0, 2.0, 0.6))),
        EffectSpec("vignette", "تظليل الأطراف", "Vignette", "lens", listOf(p("amount", "الشدة", "Amount", -1.0, 1.0, 0.4), p("size", "الحجم", "Size", 0.0, 1.0, 0.6), p("softness", "النعومة", "Softness", 0.0, 1.0, 0.5))),
        EffectSpec("film_grain", "حبيبات فيلم", "Film Grain", "film", listOf(p("amount", "الشدة", "Amount", 0.0, 1.0, 0.25), p("size", "الحجم", "Size", 0.5, 4.0, 1.2), p("seed", "البذرة", "Seed", 0.0, 9999.0, 7.0))),
        EffectSpec("chromatic_aberration", "انحراف لوني", "Chromatic Aberration", "lens", listOf(p("amount", "الشدة", "Amount", 0.0, 0.03, 0.006))),
        EffectSpec("lens_distortion", "تشويه العدسة", "Lens Distortion", "lens", listOf(p("k", "الانحناء", "Curvature", -1.0, 1.0, 0.2), p("zoom", "التقريب", "Zoom", 0.5, 2.0, 1.0))),
        EffectSpec("diffusion", "انتشار ناعم", "Diffusion", "film", listOf(p("amount", "الشدة", "Amount", 0.0, 1.0, 0.35), p("radius", "نصف القطر", "Radius", 0.0, 48.0, 14.0))),
        EffectSpec("light_leak", "تسرب ضوئي", "Light Leak", "light", listOf(p("amount", "الشدة", "Amount", 0.0, 1.0, 0.5), p("hue", "اللون", "Hue", 0.0, 360.0, 25.0), p("speed", "السرعة", "Speed", 0.0, 2.0, 0.3), p("seed", "البذرة", "Seed", 0.0, 9999.0, 3.0))),
        EffectSpec("anamorphic_flare", "وهج أنامورفي", "Anamorphic Flare", "light", listOf(p("threshold", "العتبة", "Threshold", 0.0, 1.0, 0.8), p("length", "الطول", "Length", 0.0, 1.0, 0.5), p("intensity", "الشدة", "Intensity", 0.0, 2.0, 0.8))),
        EffectSpec("scanlines", "خطوط المسح", "Scanlines", "retro", listOf(p("amount", "الشدة", "Amount", 0.0, 1.0, 0.3), p("density", "الكثافة", "Density", 50.0, 1000.0, 360.0))),
        EffectSpec("vhs", "شريط VHS", "VHS", "retro", listOf(AMOUNT, p("seed", "البذرة", "Seed", 0.0, 9999.0, 11.0))),
        EffectSpec("rgb_split", "فصل RGB", "RGB Split", "digital", listOf(p("amount", "الإزاحة", "Offset", 0.0, 0.05, 0.01), p("angle", "الزاوية", "Angle", 0.0, 360.0, 0.0))),
        EffectSpec("glitch", "تشويش رقمي", "Glitch", "digital", listOf(AMOUNT, p("rate", "المعدل", "Rate", 0.0, 30.0, 8.0), p("seed", "البذرة", "Seed", 0.0, 9999.0, 5.0))),
        EffectSpec("pixelate", "بكسلة", "Pixelate", "digital", listOf(p("size", "حجم البكسل", "Cell size", 1.0, 128.0, 16.0))),
        EffectSpec("mosaic", "فسيفساء", "Mosaic", "digital", listOf(p("size", "الحجم", "Cell size", 2.0, 128.0, 24.0))),
        EffectSpec("displacement", "إزاحة", "Displacement", "warp", listOf(p("amount", "الشدة", "Amount", 0.0, 0.1, 0.02), p("scale", "المقياس", "Scale", 0.5, 20.0, 4.0), p("speed", "السرعة", "Speed", 0.0, 4.0, 0.5))),
        EffectSpec("turbulent", "تشويه مضطرب", "Turbulent Distortion", "warp", listOf(p("amount", "الشدة", "Amount", 0.0, 0.1, 0.03), p("scale", "المقياس", "Scale", 0.5, 20.0, 6.0), p("speed", "السرعة", "Speed", 0.0, 4.0, 1.0), p("seed", "البذرة", "Seed", 0.0, 9999.0, 2.0))),
        EffectSpec("drop_shadow", "ظل", "Drop Shadow", "stylize", listOf(p("distance", "المسافة", "Distance", 0.0, 0.1, 0.015), p("angle", "الزاوية", "Angle", 0.0, 360.0, 135.0), p("blur", "الضبابية", "Softness", 0.0, 32.0, 8.0), p("opacity", "الشفافية", "Opacity", 0.0, 1.0, 0.6))),
        EffectSpec("reflection", "انعكاس", "Reflection", "stylize", listOf(p("opacity", "الشفافية", "Opacity", 0.0, 1.0, 0.35), p("falloff", "التلاشي", "Falloff", 0.0, 1.0, 0.5))),
        EffectSpec("outline", "إطار حول العنصر", "Outline", "stylize", listOf(p("width", "السماكة", "Width", 0.0, 20.0, 4.0), p("r", "أحمر", "Red", 0.0, 1.0, 1.0), p("g", "أخضر", "Green", 0.0, 1.0, 1.0), p("b", "أزرق", "Blue", 0.0, 1.0, 1.0))),
        EffectSpec("edge_detect", "حواف", "Edge Detect", "stylize", listOf(AMOUNT)),
        EffectSpec("duotone", "لونان", "Duotone", "color", listOf(p("shadowHue", "لون الظلال", "Shadow hue", 0.0, 360.0, 260.0), p("highlightHue", "لون الإضاءة", "Highlight hue", 0.0, 360.0, 160.0), AMOUNT.copy(default = 1.0))),
        EffectSpec("gradient_map", "خريطة تدرج", "Gradient Map", "color", listOf(p("hueA", "اللون 1", "Hue A", 0.0, 360.0, 230.0), p("hueB", "اللون 2", "Hue B", 0.0, 360.0, 30.0), AMOUNT.copy(default = 1.0))),
        EffectSpec("posterize", "تدرجات محدودة", "Posterize", "color", listOf(p("levels", "المستويات", "Levels", 2.0, 32.0, 6.0))),
        EffectSpec("invert", "عكس الألوان", "Invert", "color", listOf(AMOUNT.copy(default = 1.0))),
        EffectSpec("letterbox", "شريط سينمائي", "Letterbox", "frame", listOf(p("ratio", "النسبة", "Aspect ratio", 1.0, 3.0, 2.39), p("opacity", "الشفافية", "Opacity", 0.0, 1.0, 1.0))),
        EffectSpec("film_gate", "إطار فيلم", "Film Gate", "frame", listOf(p("roundness", "الاستدارة", "Roundness", 0.0, 1.0, 0.3), p("weave", "اهتزاز", "Gate weave", 0.0, 1.0, 0.3), p("seed", "البذرة", "Seed", 0.0, 9999.0, 1.0))),
        EffectSpec("particles", "جزيئات", "Particles", "light", listOf(p("density", "الكثافة", "Density", 0.0, 1.0, 0.4), p("size", "الحجم", "Size", 0.5, 6.0, 2.0), p("speed", "السرعة", "Speed", 0.0, 2.0, 0.3), p("seed", "البذرة", "Seed", 0.0, 9999.0, 9.0))),
        EffectSpec("mirror", "مرآة", "Mirror", "warp", listOf(p("axis", "المحور (0 أفقي، 1 رأسي)", "Axis (0 h, 1 v)", 0.0, 1.0, 0.0))),
        EffectSpec("flicker_reduce", "تقليل الوميض", "Flicker Reduction", "detail", listOf(p("strength", "القوة", "Strength", 0.0, 1.0, 0.6))),
        EffectSpec("luma_key", "مفتاح الإضاءة", "Luma Key", "key", listOf(p("threshold", "العتبة", "Threshold", 0.0, 1.0, 0.1), p("softness", "النعومة", "Softness", 0.0, 0.5, 0.05), p("invert", "عكس", "Invert", 0.0, 1.0, 0.0))),
        EffectSpec("lut", "جدول ألوان LUT", "LUT", "color", listOf(p("intensity", "الشدة", "Intensity", 0.0, 1.0, 1.0))),
    )

    val audio: List<EffectSpec> = listOf(
        EffectSpec("eq", "معادل (EQ)", "Parametric EQ", "eq", listOf(
            p("lowGain", "منخفض dB", "Low dB", -18.0, 18.0, 0.0), p("lowFreq", "تردد منخفض", "Low Hz", 40.0, 400.0, 120.0),
            p("midGain", "متوسط dB", "Mid dB", -18.0, 18.0, 0.0), p("midFreq", "تردد متوسط", "Mid Hz", 200.0, 6000.0, 1200.0), p("midQ", "Q", "Q", 0.3, 8.0, 1.0),
            p("highGain", "مرتفع dB", "High dB", -18.0, 18.0, 0.0), p("highFreq", "تردد مرتفع", "High Hz", 2000.0, 16000.0, 8000.0),
            p("highpass", "قطع منخفض Hz", "High-pass Hz", 0.0, 400.0, 0.0)), audio = true),
        EffectSpec("compressor", "ضاغط", "Compressor", "dynamics", listOf(
            p("threshold", "العتبة dB", "Threshold dB", -60.0, 0.0, -18.0), p("ratio", "النسبة", "Ratio", 1.0, 20.0, 3.0),
            p("attack", "الهجوم ms", "Attack ms", 0.1, 100.0, 10.0), p("release", "التحرير ms", "Release ms", 10.0, 1000.0, 120.0),
            p("makeup", "تعويض dB", "Makeup dB", 0.0, 24.0, 3.0), p("knee", "الركبة dB", "Knee dB", 0.0, 12.0, 4.0)), audio = true),
        EffectSpec("limiter", "محدد", "Limiter", "dynamics", listOf(p("ceiling", "السقف dB", "Ceiling dB", -12.0, 0.0, -1.0), p("release", "التحرير ms", "Release ms", 10.0, 500.0, 60.0)), audio = true),
        EffectSpec("gate", "بوابة ضجيج", "Noise Gate", "dynamics", listOf(p("threshold", "العتبة dB", "Threshold dB", -90.0, 0.0, -50.0), p("attack", "الهجوم ms", "Attack ms", 0.1, 50.0, 2.0), p("release", "التحرير ms", "Release ms", 10.0, 1000.0, 150.0), p("range", "المدى dB", "Range dB", -90.0, 0.0, -40.0)), audio = true),
        EffectSpec("deesser", "مزيل السين", "De-esser", "dynamics", listOf(p("threshold", "العتبة dB", "Threshold dB", -60.0, 0.0, -28.0), p("freq", "التردد", "Frequency Hz", 3000.0, 10000.0, 6500.0), p("reduction", "التخفيض dB", "Max reduction dB", 0.0, 24.0, 8.0)), audio = true),
        EffectSpec("denoise", "إزالة الضجيج", "Denoise (spectral)", "repair", listOf(p("reduction", "التخفيض dB", "Reduction dB", 0.0, 40.0, 12.0), p("sensitivity", "الحساسية", "Sensitivity", 0.0, 1.0, 0.5)), audio = true),
        EffectSpec("dereverb", "إزالة الصدى", "De-reverb", "repair", listOf(p("amount", "الشدة", "Amount", 0.0, 1.0, 0.4)), audio = true),
        EffectSpec("reverb", "صدى", "Reverb", "space", listOf(p("mix", "المزج", "Mix", 0.0, 1.0, 0.2), p("size", "الحجم", "Room size", 0.0, 1.0, 0.5), p("damping", "التخميد", "Damping", 0.0, 1.0, 0.5)), audio = true),
    )

    fun find(type: String) = video.firstOrNull { it.type == type } ?: audio.firstOrNull { it.type == type }
}

/** Visual looks: real combinations of colour correction and effects (each is distinct). */
data class LookPreset(val id: String, val ar: String, val en: String, val color: ColorAdjust, val effects: List<Pair<String, Map<String, Double>>> = emptyList())

object LookCatalog {
    private fun c(exp: Double = 0.0, con: Double = 0.0, sat: Double = 0.0, temp: Double = 0.0, tint: Double = 0.0, hi: Double = 0.0, sh: Double = 0.0,
                  vib: Double = 0.0, lift: List<Double> = listOf(0.0, 0.0, 0.0), gain: List<Double> = listOf(0.0, 0.0, 0.0), blacks: Double = 0.0,
                  curve: List<Double> = emptyList()) =
        ColorAdjust(exposure = exp, contrast = con, saturation = sat, temperature = temp, tint = tint, highlights = hi, shadows = sh, vibrance = vib,
            lift = lift, gain = gain, blacks = blacks, curveMaster = curve)

    private val sCurve = listOf(0.0, 0.0, 0.25, 0.2, 0.75, 0.8, 1.0, 1.0)
    private val fade = listOf(0.0, 0.08, 0.5, 0.52, 1.0, 0.95)

    val all: List<LookPreset> = listOf(
        LookPreset("warm-cinema", "سينمائي دافئ", "Warm Cinema", c(con = 0.15, temp = 0.25, sat = -0.05, curve = sCurve), listOf("vignette" to mapOf("amount" to 0.3))),
        LookPreset("teal-orange", "أزرق-برتقالي", "Teal & Orange", c(con = 0.18, lift = listOf(-0.02, 0.01, 0.04), gain = listOf(0.06, 0.02, -0.04), sat = 0.1)),
        LookPreset("cool-night", "ليل بارد", "Cool Night", c(exp = -0.3, temp = -0.35, tint = 0.05, sat = -0.15, con = 0.1)),
        LookPreset("bleach", "تبييض", "Bleach Bypass", c(con = 0.35, sat = -0.55, hi = -0.1)),
        LookPreset("mono", "أبيض وأسود", "Mono", c(sat = -1.0, con = 0.15)),
        LookPreset("noir", "نوار", "Noir", c(sat = -1.0, con = 0.45, sh = -0.2, curve = sCurve), listOf("vignette" to mapOf("amount" to 0.55), "film_grain" to mapOf("amount" to 0.3))),
        LookPreset("film-fade", "فيلم باهت", "Faded Film", c(curve = fade, sat = -0.2, temp = 0.08), listOf("film_grain" to mapOf("amount" to 0.2))),
        LookPreset("kodak-warm", "دفء كلاسيكي", "Classic Warm Stock", c(temp = 0.2, tint = 0.04, con = 0.1, sat = 0.08, curve = fade), listOf("halation" to mapOf("intensity" to 0.4), "film_grain" to mapOf("amount" to 0.18))),
        LookPreset("vivid", "حيوي", "Vivid", c(sat = 0.3, vib = 0.3, con = 0.12)),
        LookPreset("muted", "هادئ", "Muted", c(sat = -0.35, con = -0.1, curve = fade)),
        LookPreset("desert-gold", "ذهب الصحراء", "Desert Gold", c(temp = 0.4, tint = -0.03, con = 0.12, sat = 0.1, hi = -0.15)),
        LookPreset("sea-breeze", "نسيم البحر", "Sea Breeze", c(temp = -0.15, tint = -0.05, sat = 0.15, exp = 0.1)),
        LookPreset("moody-green", "أخضر غامق", "Moody Green", c(lift = listOf(-0.02, 0.02, -0.01), con = 0.2, sat = -0.2, exp = -0.15)),
        LookPreset("pastel", "باستيل", "Pastel", c(exp = 0.2, con = -0.2, sat = -0.1, curve = fade, tint = 0.05)),
        LookPreset("high-key", "مضيء", "High Key", c(exp = 0.45, con = -0.1, hi = -0.2, sh = 0.3)),
        LookPreset("low-key", "معتم", "Low Key", c(exp = -0.4, con = 0.3, sh = -0.25), listOf("vignette" to mapOf("amount" to 0.5))),
        LookPreset("matte", "مطفي", "Matte", c(blacks = 0.12, con = -0.05, curve = listOf(0.0, 0.1, 1.0, 0.92))),
        LookPreset("crisp", "حاد ونظيف", "Crisp", c(con = 0.2, vib = 0.2), listOf("sharpen" to mapOf("amount" to 0.6))),
        LookPreset("dreamy", "حالم", "Dreamy", c(exp = 0.15, con = -0.1, temp = 0.1), listOf("diffusion" to mapOf("amount" to 0.5), "glow" to mapOf("intensity" to 0.4))),
        LookPreset("neon-night", "نيون", "Neon Night", c(sat = 0.35, tint = 0.15, con = 0.2), listOf("bloom" to mapOf("intensity" to 0.8), "chromatic_aberration" to mapOf("amount" to 0.004))),
        LookPreset("cyber", "سايبر", "Cyber", c(temp = -0.3, tint = 0.25, con = 0.25), listOf("rgb_split" to mapOf("amount" to 0.006), "scanlines" to mapOf("amount" to 0.2))),
        LookPreset("retro-vhs", "VHS قديم", "Retro VHS", c(sat = -0.1, con = -0.05, temp = 0.05), listOf("vhs" to mapOf("amount" to 0.6), "scanlines" to mapOf("amount" to 0.25))),
        LookPreset("super8", "سوبر 8", "Super 8", c(temp = 0.25, sat = -0.1, curve = fade), listOf("film_grain" to mapOf("amount" to 0.45, "size" to 2.0), "film_gate" to mapOf("weave" to 0.6), "vignette" to mapOf("amount" to 0.45))),
        LookPreset("polaroid", "بولارويد", "Instant Film", c(curve = fade, temp = 0.12, tint = 0.06, sat = -0.15)),
        LookPreset("golden-hour", "الساعة الذهبية", "Golden Hour", c(temp = 0.45, exp = 0.1, sat = 0.12), listOf("light_leak" to mapOf("amount" to 0.3, "hue" to 30.0))),
        LookPreset("blue-hour", "الساعة الزرقاء", "Blue Hour", c(temp = -0.4, exp = -0.1, sat = 0.05, con = 0.1)),
        LookPreset("arctic", "قطبي", "Arctic", c(temp = -0.5, sat = -0.3, exp = 0.15)),
        LookPreset("sepia", "بني قديم", "Sepia", c(sat = -0.85, temp = 0.5, tint = 0.05)),
        LookPreset("cross-process", "معالجة متقاطعة", "Cross Process", c(lift = listOf(0.0, 0.03, 0.06), gain = listOf(0.08, 0.06, -0.08), con = 0.2, sat = 0.15)),
        LookPreset("duo-purple", "ثنائي بنفسجي", "Purple Duotone", c(), listOf("duotone" to mapOf("shadowHue" to 265.0, "highlightHue" to 165.0))),
        LookPreset("duo-sunset", "ثنائي الغروب", "Sunset Duotone", c(), listOf("duotone" to mapOf("shadowHue" to 330.0, "highlightHue" to 45.0))),
        LookPreset("gradient-map-ocean", "خريطة المحيط", "Ocean Map", c(), listOf("gradient_map" to mapOf("hueA" to 220.0, "hueB" to 180.0, "amount" to 0.7))),
        LookPreset("anamorphic", "أنامورفي", "Anamorphic", c(con = 0.15, temp = -0.1), listOf("anamorphic_flare" to mapOf("intensity" to 0.8), "letterbox" to mapOf("ratio" to 2.39))),
        LookPreset("documentary", "وثائقي", "Documentary", c(con = 0.08, sat = -0.12, temp = 0.05)),
        LookPreset("product-clean", "منتج نظيف", "Product Clean", c(exp = 0.15, con = 0.1, vib = 0.15), listOf("sharpen" to mapOf("amount" to 0.4))),
        LookPreset("food-warm", "طعام شهي", "Appetizing", c(temp = 0.2, sat = 0.2, vib = 0.15, con = 0.1)),
        LookPreset("real-estate", "عقاري مضيء", "Bright Interior", c(exp = 0.25, sh = 0.35, hi = -0.25, vib = 0.1)),
        LookPreset("car-gloss", "لمعان السيارات", "Car Gloss", c(con = 0.25, vib = 0.2, temp = -0.05), listOf("bloom" to mapOf("intensity" to 0.35, "threshold" to 0.85))),
        LookPreset("fashion", "أزياء", "Fashion", c(con = 0.12, sat = -0.15, curve = fade, tint = 0.04)),
        LookPreset("sports", "رياضي", "Sports Punch", c(con = 0.3, sat = 0.2, vib = 0.2), listOf("sharpen" to mapOf("amount" to 0.5))),
        LookPreset("horror", "رعب", "Horror", c(sat = -0.6, temp = -0.15, tint = -0.1, exp = -0.3, con = 0.3), listOf("film_grain" to mapOf("amount" to 0.4), "vignette" to mapOf("amount" to 0.7))),
        LookPreset("music-video", "فيديو كليب", "Music Video", c(con = 0.25, sat = 0.25, tint = 0.12), listOf("glow" to mapOf("intensity" to 0.5), "chromatic_aberration" to mapOf("amount" to 0.005))),
        LookPreset("travel", "سفر", "Travel", c(vib = 0.3, temp = 0.08, con = 0.08)),
        LookPreset("wedding", "زفاف", "Wedding Soft", c(exp = 0.15, temp = 0.1, sat = -0.05, con = -0.05), listOf("diffusion" to mapOf("amount" to 0.3))),
        LookPreset("vintage-green", "أخضر عتيق", "Vintage Green", c(lift = listOf(0.0, 0.04, 0.02), sat = -0.25, curve = fade), listOf("film_grain" to mapOf("amount" to 0.25))),
        LookPreset("magenta-pop", "وردي جريء", "Magenta Pop", c(tint = 0.3, sat = 0.2, con = 0.15)),
        LookPreset("silver", "فضي", "Silver", c(sat = -0.7, con = 0.25, temp = -0.1, hi = 0.1)),
        LookPreset("glitch-art", "فن التشويش", "Glitch Art", c(con = 0.2), listOf("glitch" to mapOf("amount" to 0.5), "rgb_split" to mapOf("amount" to 0.01))),
        LookPreset("posterize-pop", "بوب آرت", "Pop Art", c(sat = 0.4, con = 0.3), listOf("posterize" to mapOf("levels" to 5.0))),
        LookPreset("soft-skin", "بشرة ناعمة", "Soft Portrait", c(temp = 0.08, con = -0.05, sat = -0.05), listOf("denoise" to mapOf("strength" to 0.5), "diffusion" to mapOf("amount" to 0.2))),
    )

    fun find(id: String) = all.firstOrNull { it.id == id }
}

/** Text animation preset: per-unit start state that animates to rest (IN), rest → end state (OUT), or a loop. */
data class TextPreset(
    val id: String, val ar: String, val en: String, val phase: String, // in | out | loop
    val unit: TextUnit = TextUnit.GRAPHEME,
    val alpha: Double = 0.0, val dx: Double = 0.0, val dy: Double = 0.0, val scale: Double = 1.0,
    val rotation: Double = 0.0, val blur: Double = 0.0, val tracking: Double = 0.0,
    val easing: String = "out",
    val typewriter: Boolean = false, val reveal: String? = null, val jitter: Double = 0.0,
    val loop: String? = null, val loopAmp: Double = 0.0, val loopFreq: Double = 1.0,
)

object TextPresetCatalog {
    val all: List<TextPreset> = listOf(
        // ---- IN (24)
        TextPreset("fade-in", "ظهور تدريجي", "Fade In", "in", TextUnit.ALL),
        TextPreset("fade-letters", "ظهور حرفًا حرفًا", "Fade Letters", "in"),
        TextPreset("fade-words", "ظهور كلمةً كلمة", "Fade Words", "in", TextUnit.WORD),
        TextPreset("slide-up", "انزلاق للأعلى", "Slide Up", "in", TextUnit.WORD, dy = 0.6),
        TextPreset("slide-down", "انزلاق للأسفل", "Slide Down", "in", TextUnit.WORD, dy = -0.6),
        TextPreset("slide-start", "انزلاق من البداية", "Slide From Start", "in", TextUnit.LINE, dx = 1.0),
        TextPreset("slide-end", "انزلاق من النهاية", "Slide From End", "in", TextUnit.LINE, dx = -1.0),
        TextPreset("scale-up", "تكبير", "Scale Up", "in", TextUnit.GRAPHEME, scale = 0.2),
        TextPreset("scale-down", "تصغير", "Scale Down", "in", TextUnit.WORD, scale = 2.2),
        TextPreset("pop", "قفزة", "Pop", "in", TextUnit.WORD, scale = 0.3, easing = "back"),
        TextPreset("bounce", "ارتداد", "Bounce", "in", TextUnit.GRAPHEME, dy = -1.2, easing = "bounce"),
        TextPreset("elastic", "مطاطي", "Elastic", "in", TextUnit.WORD, scale = 0.0, easing = "elastic"),
        TextPreset("spring-drop", "سقوط زنبركي", "Spring Drop", "in", TextUnit.WORD, dy = -1.5, easing = "spring"),
        TextPreset("rotate-in", "دوران", "Rotate In", "in", TextUnit.GRAPHEME, rotation = -45.0, scale = 0.6),
        TextPreset("blur-in", "وضوح من ضبابية", "Blur In", "in", TextUnit.WORD, blur = 12.0),
        TextPreset("typewriter", "آلة كاتبة", "Typewriter", "in", TextUnit.GRAPHEME, alpha = 0.0, typewriter = true, easing = "hold"),
        TextPreset("typewriter-soft", "آلة كاتبة ناعمة", "Soft Typewriter", "in", TextUnit.GRAPHEME, typewriter = true, easing = "linear"),
        TextPreset("reveal-up", "كشف بقناع للأعلى", "Mask Reveal Up", "in", TextUnit.LINE, reveal = "up", alpha = 1.0, dy = 0.5),
        TextPreset("reveal-side", "كشف بقناع جانبي", "Mask Reveal Side", "in", TextUnit.LINE, reveal = "start", alpha = 1.0),
        TextPreset("tracking-in", "تباعد يضيق", "Tracking In", "in", TextUnit.ALL, tracking = 0.6, blur = 4.0),
        TextPreset("drop-in", "إسقاط", "Drop In", "in", TextUnit.GRAPHEME, dy = -2.0, easing = "bounce"),
        TextPreset("zoom-out-in", "تقريب عكسي", "Zoom From Camera", "in", TextUnit.ALL, scale = 3.0, blur = 6.0),
        TextPreset("glitch-in", "ظهور متشوش", "Glitch In", "in", TextUnit.GRAPHEME, jitter = 0.4, easing = "linear"),
        TextPreset("kinetic-punch", "لكمة حركية", "Kinetic Punch", "in", TextUnit.WORD, scale = 1.8, alpha = 0.0, easing = "overshoot"),
        // ---- OUT (8)
        TextPreset("fade-out", "اختفاء تدريجي", "Fade Out", "out", TextUnit.ALL),
        TextPreset("slide-out-down", "خروج للأسفل", "Slide Out Down", "out", TextUnit.WORD, dy = 0.6),
        TextPreset("slide-out-up", "خروج للأعلى", "Slide Out Up", "out", TextUnit.WORD, dy = -0.6),
        TextPreset("scale-out", "تصغير للخروج", "Scale Out", "out", TextUnit.GRAPHEME, scale = 0.0),
        TextPreset("blur-out", "ضبابية للخروج", "Blur Out", "out", TextUnit.WORD, blur = 12.0),
        TextPreset("erase", "مسح عكسي", "Typewriter Erase", "out", TextUnit.GRAPHEME, typewriter = true, easing = "hold"),
        TextPreset("pop-out", "قفزة للخروج", "Pop Out", "out", TextUnit.WORD, scale = 1.6, easing = "in"),
        TextPreset("mask-hide", "إخفاء بقناع", "Mask Hide", "out", TextUnit.LINE, reveal = "down", alpha = 1.0),
        // ---- LOOP (8)
        TextPreset("wave", "موجة", "Wave", "loop", TextUnit.GRAPHEME, loop = "wave", loopAmp = 0.15, loopFreq = 1.2),
        TextPreset("pulse", "نبض", "Pulse", "loop", TextUnit.ALL, loop = "pulse", loopAmp = 0.06, loopFreq = 1.5),
        TextPreset("shake", "اهتزاز", "Shake", "loop", TextUnit.GRAPHEME, loop = "shake", loopAmp = 0.05, loopFreq = 12.0),
        TextPreset("float", "طفو", "Float", "loop", TextUnit.WORD, loop = "float", loopAmp = 0.08, loopFreq = 0.5),
        TextPreset("flicker", "وميض", "Flicker", "loop", TextUnit.GRAPHEME, loop = "flicker", loopAmp = 0.6, loopFreq = 10.0),
        TextPreset("wiggle", "تذبذب", "Wiggle", "loop", TextUnit.WORD, loop = "wiggle", loopAmp = 6.0, loopFreq = 2.0),
        TextPreset("highlight-sweep", "إبراز متنقل", "Word Highlight Sweep", "loop", TextUnit.WORD, loop = "highlight", loopFreq = 1.0),
        TextPreset("karaoke", "كاريوكي", "Karaoke Fill", "loop", TextUnit.WORD, loop = "karaoke"),
    )

    fun find(id: String?) = all.firstOrNull { it.id == id }
}

/** Caption look presets. Each changes real rendering parameters (not just a name). */
data class CaptionStylePreset(
    val id: String, val ar: String, val en: String,
    val style: TextStyle,
    val activeColorArgb: Long? = null,
    val activeScale: Double = 1.0,
    val activePillArgb: Long? = null,
    val karaoke: Boolean = false,
    val wordByWord: Boolean = false,
    val uppercase: Boolean = false,
    val wordsPerLine: Int = 7,
    val animIn: String? = null,
)

object CaptionStyleCatalog {
    private val base = TextStyle("", sizeSp = 54.0, weight = 700, boxWidth = 0.86)
    val all: List<CaptionStylePreset> = listOf(
        CaptionStylePreset("minimal", "بسيط", "Minimal", base.copy(weight = 500, sizeSp = 46.0, shadowArgb = 0x99000000, shadowBlur = 6.0)),
        CaptionStylePreset("subtitle", "ترجمة كلاسيكية", "Subtitle", base.copy(weight = 600, sizeSp = 44.0, backgroundArgb = 0xB3000000, backgroundRadius = 6.0)),
        CaptionStylePreset("documentary", "وثائقي", "Documentary", base.copy(weight = 400, sizeSp = 42.0, fontFamily = "serif", shadowArgb = 0xCC000000, shadowBlur = 4.0)),
        CaptionStylePreset("podcast", "بودكاست", "Podcast", base.copy(sizeSp = 58.0, strokeArgb = 0xFF000000, strokeWidth = 6.0), activeColorArgb = 0xFFFFC857),
        CaptionStylePreset("bold-social", "جريء للتواصل", "Bold Social", base.copy(sizeSp = 72.0, weight = 900, strokeArgb = 0xFF000000, strokeWidth = 10.0), activeColorArgb = 0xFF6EE7C8, uppercase = true, wordsPerLine = 3, animIn = "pop"),
        CaptionStylePreset("word-by-word", "كلمة بكلمة", "Word by Word", base.copy(sizeSp = 80.0, weight = 900, strokeArgb = 0xFF000000, strokeWidth = 8.0), wordByWord = true, wordsPerLine = 1, animIn = "pop"),
        CaptionStylePreset("karaoke", "كاريوكي", "Karaoke", base.copy(sizeSp = 60.0, colorArgb = 0x99FFFFFF, strokeArgb = 0xFF000000, strokeWidth = 5.0), activeColorArgb = 0xFFFFFFFF, karaoke = true),
        CaptionStylePreset("active-word", "الكلمة النشطة", "Active Word", base.copy(sizeSp = 62.0, strokeArgb = 0xFF000000, strokeWidth = 7.0), activeColorArgb = 0xFF9B7CFF, activeScale = 1.12),
        CaptionStylePreset("pill", "كبسولة", "Pill", base.copy(sizeSp = 56.0), activePillArgb = 0xFF9B7CFF),
        CaptionStylePreset("outline", "محدد", "Outline", base.copy(sizeSp = 64.0, colorArgb = 0x00FFFFFFL, strokeArgb = 0xFFFFFFFF, strokeWidth = 4.0), activeColorArgb = 0xFFFFFFFF),
        CaptionStylePreset("neon", "نيون", "Neon", base.copy(sizeSp = 62.0, colorArgb = 0xFFFDF2FF, glowArgb = 0xFFB026FF, glowRadius = 18.0), activeColorArgb = 0xFF6EE7C8),
        CaptionStylePreset("box-dark", "صندوق داكن", "Dark Box", base.copy(sizeSp = 52.0, backgroundArgb = 0xE6141821, backgroundRadius = 16.0, backgroundPadding = 18.0), activeColorArgb = 0xFF6EE7C8),
        CaptionStylePreset("box-light", "صندوق فاتح", "Light Box", base.copy(sizeSp = 52.0, colorArgb = 0xFF0B0D12, backgroundArgb = 0xF2FFFFFF, backgroundRadius = 16.0, backgroundPadding = 18.0), activeColorArgb = 0xFF7B5CF0),
        CaptionStylePreset("gradient", "متدرج", "Gradient", base.copy(sizeSp = 66.0, weight = 900, colorArgb = 0xFF9B7CFF, gradientArgb = 0xFF6EE7C8, strokeArgb = 0xFF0B0D12, strokeWidth = 6.0)),
        CaptionStylePreset("cinematic", "سينمائي", "Cinematic", base.copy(sizeSp = 40.0, weight = 300, letterSpacing = 0.08, shadowArgb = 0xCC000000, shadowBlur = 10.0), uppercase = true),
        CaptionStylePreset("news", "إخباري", "News Bar", base.copy(sizeSp = 46.0, backgroundArgb = 0xFFB3122E, backgroundRadius = 0.0, backgroundPadding = 14.0, alignment = "start")),
        CaptionStylePreset("handwritten", "ناعم", "Soft Rounded", base.copy(sizeSp = 58.0, weight = 600, shadowArgb = 0x66000000L, shadowDy = 4.0, shadowBlur = 0.0), activeColorArgb = 0xFFFFC857, animIn = "bounce"),
        CaptionStylePreset("highlighter", "قلم تحديد", "Highlighter", base.copy(sizeSp = 58.0, colorArgb = 0xFF0B0D12), activePillArgb = 0xFFFFE45E),
        CaptionStylePreset("typewriter", "آلة كاتبة", "Typewriter", base.copy(sizeSp = 48.0, fontFamily = "mono", backgroundArgb = 0xCC000000), animIn = "typewriter"),
        CaptionStylePreset("stacked", "مكدّس", "Stacked Impact", base.copy(sizeSp = 84.0, weight = 900, strokeArgb = 0xFF000000, strokeWidth = 10.0, lineHeight = 1.0), activeColorArgb = 0xFFFF6577, wordsPerLine = 2, animIn = "kinetic-punch"),
    )

    fun find(id: String) = all.firstOrNull { it.id == id } ?: all[1]
}

object TransitionCatalog {
    val names: Map<TransitionKind, Pair<String, String>> = mapOf(
        TransitionKind.DISSOLVE to ("ذوبان" to "Dissolve"), TransitionKind.DIP_TO_BLACK to ("تلاشي للأسود" to "Dip to Black"),
        TransitionKind.DIP_TO_WHITE to ("تلاشي للأبيض" to "Dip to White"), TransitionKind.LUMA_FADE to ("تلاشي بالإضاءة" to "Luma Fade"),
        TransitionKind.WIPE_LEFT to ("مسح لليسار" to "Wipe Left"), TransitionKind.WIPE_RIGHT to ("مسح لليمين" to "Wipe Right"),
        TransitionKind.WIPE_UP to ("مسح للأعلى" to "Wipe Up"), TransitionKind.WIPE_DOWN to ("مسح للأسفل" to "Wipe Down"),
        TransitionKind.SLIDE_LEFT to ("انزلاق لليسار" to "Slide Left"), TransitionKind.SLIDE_RIGHT to ("انزلاق لليمين" to "Slide Right"),
        TransitionKind.PUSH_LEFT to ("دفع أفقي" to "Push Left"), TransitionKind.PUSH_UP to ("دفع رأسي" to "Push Up"),
        TransitionKind.WHIP_PAN to ("سحب سريع" to "Whip Pan"), TransitionKind.ZOOM_IN to ("تقريب" to "Zoom In"),
        TransitionKind.ZOOM_OUT to ("تبعيد" to "Zoom Out"), TransitionKind.MATCH_ZOOM to ("تقريب متطابق" to "Match Zoom"),
        TransitionKind.SPIN to ("دوران" to "Spin"), TransitionKind.PERSPECTIVE to ("منظور ثلاثي" to "Perspective"),
        TransitionKind.BLUR to ("ضبابية" to "Blur"), TransitionKind.LIGHT_LEAK to ("تسرب ضوئي" to "Light Leak"),
        TransitionKind.FILM_BURN to ("احتراق فيلم" to "Film Burn"), TransitionKind.GLITCH to ("تشويش" to "Glitch"),
        TransitionKind.RGB_SPLIT to ("فصل ألوان" to "RGB Split"), TransitionKind.DISPLACEMENT to ("إزاحة" to "Displacement"),
        TransitionKind.SHAPE_CIRCLE to ("كشف دائري" to "Circle Reveal"), TransitionKind.SHAPE_DIAMOND to ("كشف معيّن" to "Diamond Reveal"),
        TransitionKind.CAMERA_SHAKE to ("اهتزاز كاميرا" to "Camera Shake"), TransitionKind.PAGE_TURN to ("قلب صفحة" to "Page Turn"),
        TransitionKind.STRETCH to ("تمدد" to "Stretch"), TransitionKind.MOSAIC to ("فسيفساء" to "Mosaic"),
        TransitionKind.SMOOTH_WARP to ("التواء ناعم" to "Smooth Warp"), TransitionKind.FLASH to ("وميض" to "Flash"),
        TransitionKind.RADIAL to ("مسح دائري" to "Radial Wipe"), TransitionKind.SPLIT to ("انقسام" to "Split"),
        TransitionKind.SEAMLESS_LOOP to ("حلقة سلسة" to "Seamless Loop"), TransitionKind.BEAT_CUT to ("قفزة على الإيقاع" to "Beat Punch"),
        TransitionKind.CUSTOM_MASK to ("قناع مخصص" to "Custom Mask"),
    )
}
