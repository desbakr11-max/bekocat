package app.bekocat.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.opengl.GLES30
import app.bekocat.core.timeline.BlendMode
import app.bekocat.core.timeline.CaptionFrame
import app.bekocat.core.timeline.ColorMath
import app.bekocat.core.timeline.EffectFrame
import app.bekocat.core.timeline.FrameBlend
import app.bekocat.core.timeline.FramePlan
import app.bekocat.core.timeline.Geometry
import app.bekocat.core.timeline.KeyView
import app.bekocat.core.timeline.LayerPlan
import app.bekocat.core.timeline.LayerSource
import app.bekocat.core.timeline.MatteType
import app.bekocat.core.timeline.PlanItem
import app.bekocat.core.timeline.TransformFrame
import app.bekocat.core.timeline.TransitionKind
import app.bekocat.core.timeline.TransitionPlan
import app.bekocat.engine.decode.VideoFrameSource
import app.bekocat.engine.gl.Fbo
import app.bekocat.engine.gl.Program
import app.bekocat.engine.gl.Quad
import app.bekocat.engine.gl.Shaders
import app.bekocat.engine.gl.Texture
import app.bekocat.engine.text.FontResolver
import app.bekocat.engine.text.MaskRasterizer
import app.bekocat.engine.text.ShapeRenderer
import app.bekocat.engine.text.TextRenderer
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Renders [FramePlan]s on the GPU. One instance per GL context. Used by the preview
 * renderer and the exporter, so both produce identical pixels for the same plan.
 */
class Compositor(private val context: Context) {

    private val layerProg = Program(Shaders.VERTEX, Shaders.LAYER)
    private val blendProg = Program(Shaders.VERTEX, Shaders.BLEND)
    private val transProg = Program(Shaders.VERTEX, Shaders.TRANSITION)
    private val blurProg = Program(Shaders.VERTEX, Shaders.BLUR)
    private val fxProg = Program(Shaders.VERTEX, Shaders.FX)
    private val flowProg = Program(Shaders.VERTEX, Shaders.FLOW)
    private val mixProg = Program(Shaders.VERTEX, Shaders.MIX)
    val presentProg = Program(Shaders.VERTEX, Shaders.PRESENT)
    private val copyOes = VideoFrameSource.shaders()

    val fonts = FontResolver(context)
    private val text = TextRenderer(fonts)

    private val videos = LinkedHashMap<String, VideoFrameSource>(8, 0.75f, true)
    private val images = HashMap<String, Texture>()
    private val bitmapTex = LinkedHashMap<Int, Texture>(64, 0.75f, true)
    private val luts = HashMap<String, Int>()
    private val curves = HashMap<Int, Int>()
    private val solid = HashMap<Long, Texture>()
    private val flicker = HashMap<String, Double>()
    private val pool = HashMap<Long, ArrayDeque<Fbo>>()
    private val maxVideos = 6

    /** Resolves an asset id → URI (fonts, LUTs). */
    var assetUri: (String) -> String? = { null }
        set(v) { field = v; fonts.assetUri = v }

    // Matrices mapping our uv (v down) → texture coordinates.
    private val TEX_BITMAP = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private val TEX_FBO = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0, 1.0)

    // ---------------- pools ----------------
    fun obtain(w: Int, h: Int): Fbo {
        val q = pool[key(w, h)]
        return q?.removeLastOrNull() ?: Fbo(w, h)
    }
    fun recycle(f: Fbo) { pool.getOrPut(key(f.width, f.height)) { ArrayDeque() }.addLast(f) }
    private fun key(w: Int, h: Int) = (w.toLong() shl 32) or h.toLong()

    private class Src(val tex: Int, val texM: DoubleArray, val unpremul: Boolean, val w: Int, val h: Int, val intrinsic: Boolean, val temp: Fbo? = null, val anchorDy: Double = 0.0)

    /** Renders [plan] into a pooled FBO of the plan size and returns it (caller recycles). */
    fun render(plan: FramePlan, transparent: Boolean = false, timeSec: Double = plan.time.toDouble()): Fbo {
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val W = plan.width; val H = plan.height
        var accum = obtain(W, H)
        if (transparent) accum.clear() else {
            val bg = plan.backgroundArgb
            accum.clear(((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f, 1f)
            plan.backgroundUri?.let { uri ->
                imageTexture(uri)?.let { t ->
                    val layer = renderSourceLayer(Src(t.id, TEX_BITMAP, true, t.width, t.height, false), TransformFrame(fit = app.bekocat.core.timeline.FitMode.FILL), emptyList(), plan, null, null)
                    accum = blendInto(accum, layer, BlendMode.NORMAL); recycle(layer)
                }
            }
        }
        for (item in plan.items) {
            when (item) {
                is PlanItem.Layer -> {
                    val l = item.layer
                    val img = renderLayer(l, plan, accum, timeSec) ?: continue
                    accum = blendInto(accum, img, if (l.source is LayerSource.Adjustment) BlendMode.NORMAL else l.blend)
                    recycle(img)
                }
                is PlanItem.Trans -> {
                    val t = renderTransition(item.plan, plan, accum, timeSec) ?: continue
                    accum = blendInto(accum, t, item.plan.b?.blend ?: BlendMode.NORMAL)
                    recycle(t)
                }
            }
        }
        for (cf in plan.captions) {
            val img = renderCaption(cf, plan) ?: continue
            accum = blendInto(accum, img, BlendMode.NORMAL); recycle(img)
        }
        return accum
    }

    // ---------------- layers ----------------
    private fun renderLayer(l: LayerPlan, plan: FramePlan, accum: Fbo, timeSec: Double): Fbo? {
        val src = sourceFor(l, plan, accum, timeSec) ?: return null
        val matte = l.matte?.let { m -> renderLayer(m.layer, plan, accum, timeSec)?.let { it to m.type } }
        var img = renderSourceLayer(src, l.transform, l.parents, plan, l, matte)
        matte?.first?.let { recycle(it) }
        src.temp?.let { recycle(it) }
        img = applyEffects(img, l.effects, l.clipId, timeSec)
        if (l.depthBlur > 0.5) img = blur(img, l.depthBlur)
        return img
    }

    private fun sourceFor(l: LayerPlan, plan: FramePlan, accum: Fbo, timeSec: Double): Src? = when (val s = l.source) {
        is LayerSource.Video -> videoFrame(s)
        is LayerSource.Image -> imageTexture(s.uri)?.let { Src(it.id, TEX_BITMAP, true, it.width, it.height, false) }
        is LayerSource.Text -> {
            val k = listOf(s.style, s.states, plan.width, plan.height).hashCode()
            val t = bitmapTex[k] ?: text.render(s.style, s.style.text, s.states, plan.width, plan.height)?.bitmap?.let { b -> cacheBitmap(k, b) }
            t?.let { Src(it.id, TEX_BITMAP, true, it.width, it.height, true) }
        }
        is LayerSource.Shape -> {
            val k = listOf(s.shape, plan.width, plan.height).hashCode()
            val t = bitmapTex[k] ?: ShapeRenderer.render(s.shape, plan.width, plan.height)?.let { b -> cacheBitmap(k, b) }
            t?.let { Src(it.id, TEX_BITMAP, true, it.width, it.height, true) }
        }
        is LayerSource.Solid -> {
            val t = solid.getOrPut(s.argb) {
                val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888); b.eraseColor(s.argb.toInt()); Texture.fromBitmap(b).also { b.recycle() }
            }
            Src(t.id, TEX_BITMAP, true, plan.width, plan.height, false)
        }
        is LayerSource.Nested -> {
            val f = render(s.plan, transparent = true, timeSec = s.plan.time.toDouble())
            Src(f.texture.id, TEX_FBO, true, f.width, f.height, false, temp = f)
        }
        LayerSource.Adjustment -> {
            // Copy the composite below so it can be graded/filtered.
            val copy = obtain(accum.width, accum.height)
            copy.bind(); presentProg.use()
            presentProg.tex("uTex", 0, GLES30.GL_TEXTURE_2D, accum.texture.id)
            presentProg.v2("uOut", copy.width.toFloat(), copy.height.toFloat()); presentProg.i("uFlip", 0); presentProg.v4("uBg", 0f, 0f, 0f, 0f)
            Quad.draw()
            Src(copy.texture.id, TEX_FBO, true, plan.width, plan.height, false, temp = copy)
        }
    }

    private fun cacheBitmap(k: Int, b: Bitmap): Texture {
        val t = Texture.fromBitmap(b); b.recycle()
        bitmapTex[k] = t
        while (bitmapTex.size > 48) { val e = bitmapTex.entries.first(); e.value.release(); bitmapTex.remove(e.key) }
        return t
    }

    private fun videoFrame(s: LayerSource.Video): Src? {
        val vs = videoSource(s.uri) ?: return null
        val us = (s.sourceTime.toDouble() * 1_000_000).toLong()
        if (s.frameBlend == FrameBlend.NONE || s.speed >= 0.999) {
            val t = vs.frameAt(us) ?: return null
            return Src(t.id, TEX_FBO, false, vs.width, vs.height, false)
        }
        // Frame blending / optical flow for slow motion: blend the two neighbouring source frames.
        val fps = 30.0
        val f = s.sourceTime.toDouble() * fps
        val f0 = floor(f); val frac = f - f0
        val a = obtain(vs.width, vs.height); val b = obtain(vs.width, vs.height)
        copyTex(vs.frameAt((f0 / fps * 1e6).toLong()) ?: return null, a)
        copyTex(vs.frameAt(((f0 + 1) / fps * 1e6).toLong()) ?: return null, b)
        val out = obtain(vs.width, vs.height)
        out.bind()
        val prog = if (s.frameBlend == FrameBlend.OPTICAL_FLOW) flowProg else mixProg
        prog.use()
        prog.tex("uA", 0, GLES30.GL_TEXTURE_2D, a.texture.id); prog.tex("uB", 1, GLES30.GL_TEXTURE_2D, b.texture.id)
        prog.v2("uOut", out.width.toFloat(), out.height.toFloat()); prog.f("uT", frac.toFloat())
        Quad.draw()
        recycle(a); recycle(b)
        return Src(out.texture.id, TEX_FBO, false, vs.width, vs.height, false, temp = out)
    }

    private fun copyTex(t: Texture, into: Fbo) {
        into.bind(); presentProg.use()
        presentProg.tex("uTex", 0, GLES30.GL_TEXTURE_2D, t.id)
        presentProg.v2("uOut", into.width.toFloat(), into.height.toFloat()); presentProg.i("uFlip", 0); presentProg.v4("uBg", 0f, 0f, 0f, 0f)
        Quad.draw()
    }

    fun videoSource(uri: String): VideoFrameSource? {
        videos[uri]?.let { return it }
        val vs = runCatching { VideoFrameSource(context, Uri.parse(uri), copyOes) }.getOrNull() ?: return null
        videos[uri] = vs
        while (videos.size > maxVideos) { val e = videos.entries.first(); e.value.release(); videos.remove(e.key) }
        return vs
    }

    private fun imageTexture(uri: String): Texture? = images[uri] ?: runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > 4096) sample *= 2
        val bmp = context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPremultiplied = true }) } ?: return null
        val rot = runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { ExifInterface(it).let { e -> when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) { ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0 } } } ?: 0 }.getOrDefault(0)
        val oriented = if (rot != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true) else bmp
        Texture.fromBitmap(oriented).also { images[uri] = it; if (oriented !== bmp) oriented.recycle(); bmp.recycle() }
    }.getOrNull()

    private fun renderSourceLayer(src: Src, tf: TransformFrame, parents: List<TransformFrame>, plan: FramePlan, l: LayerPlan?, matte: Pair<Fbo, MatteType>?): Fbo {
        val W = plan.width; val H = plan.height
        val out = obtain(W, H)
        out.clear()
        val p = layerProg
        p.use()
        p.tex("uTex", 0, GLES30.GL_TEXTURE_2D, src.tex)
        p.mat3("uTexM", src.texM)
        p.v2("uOut", W.toFloat(), H.toFloat())
        val transforms = buildList {
            add(tf to parents)
            l?.motionSamples?.let { addAll(it) }
        }.let { if (it.size > 1) it.drop(1) else it }
        val inv = transforms.take(16).map { (t, par) ->
            Geometry.invert3(Geometry.homography(Geometry.corners(t, par, src.w, src.h, W, H, plan.camera, src.intrinsic)))
        }
        p.i("uSamples", inv.size)
        p.mat3v("uInv[0]", inv)
        p.v4("uCrop", tf.cropL.toFloat(), tf.cropT.toFloat(), tf.cropR.toFloat(), tf.cropB.toFloat())
        p.f("uOpacity", (l?.opacity ?: tf.opacity).toFloat())
        p.i("uUnpremul", if (src.unpremul) 1 else 0)
        // Colour
        val c = l?.color
        val grade = c != null && !c.isIdentity
        p.i("uGrade", if (grade) 1 else 0)
        if (c != null && grade) {
            p.f("uExposure", c.exposure); p.f("uContrast", c.contrast); p.f("uHighlights", c.highlights); p.f("uShadows", c.shadows)
            p.f("uWhites", c.whites); p.f("uBlacks", c.blacks); p.f("uSat", c.saturation); p.f("uVib", c.vibrance)
            p.f("uTemp", c.temperature); p.f("uTint", c.tint)
            p.v3("uLift", c.lift[0].toFloat(), c.lift[1].toFloat(), c.lift[2].toFloat())
            p.v3("uGamma", c.gamma[0].toFloat(), c.gamma[1].toFloat(), c.gamma[2].toFloat())
            p.v3("uGain", c.gain[0].toFloat(), c.gain[1].toFloat(), c.gain[2].toFloat())
            val useCurves = ColorMath.hasCurves(c)
            p.i("uUseCurves", if (useCurves) 1 else 0)
            if (useCurves) p.tex("uCurves", 2, GLES30.GL_TEXTURE_2D, curves.getOrPut(listOf(c.curveMaster, c.curveRed, c.curveGreen, c.curveBlue).hashCode()) { Texture.curve(ColorMath.curvesRgba(c)) })
            val useHsl = ColorMath.hasHsl(c)
            p.i("uUseHsl", if (useHsl) 1 else 0)
            if (useHsl) { p.fv("uHslH[0]", c.hslHue.map { it.toFloat() }.toFloatArray()); p.fv("uHslS[0]", c.hslSat.map { it.toFloat() }.toFloatArray()); p.fv("uHslL[0]", c.hslLum.map { it.toFloat() }.toFloatArray()) }
            val lutTex = l.lutUri?.let { lutTexture(it) }
            val lutFx = l.effects.firstOrNull { it.type == "lut" }
            p.i("uUseLut", if (lutTex != null) 1 else 0)
            if (lutTex != null) { p.tex("uLut", 3, GLES30.GL_TEXTURE_3D, lutTex); p.f("uLutMix", (lutFx?.p("intensity") ?: c.lutIntensity).toFloat()) }
        } else { p.i("uUseCurves", 0); p.i("uUseHsl", 0); p.i("uUseLut", 0) }
        // Keying
        val key = l?.chroma
        val lumaKey = key?.lumaKey == true
        p.i("uKey", if (key == null) 0 else if (lumaKey) 2 else 1)
        if (key != null) {
            val kc = key.keyColorArgb
            p.v3("uKeyColor", ((kc shr 16) and 0xFF) / 255f, ((kc shr 8) and 0xFF) / 255f, (kc and 0xFF) / 255f)
            val dc = key.despillColorArgb
            p.v3("uDespill", ((dc shr 16) and 0xFF) / 255f, ((dc shr 8) and 0xFF) / 255f, (dc and 0xFF) / 255f)
            p.f("uSim", key.similarity); p.f("uSmooth", key.smoothness); p.f("uSpill", key.spill); p.f("uChoke", key.chokeGrow); p.f("uShadow", key.shadowPreserve)
            p.i("uKeyView", when (key.view) { KeyView.MATTE -> 1; else -> 0 })
            p.f("uLumaThr", key.similarity); p.f("uLumaSoft", key.smoothness); p.f("uLumaInv", if (key.spill > 0.5) 1f else 0f)
            if (key.view == KeyView.ORIGINAL) p.i("uKey", 0)
        }
        // Masks
        val maskBmp = l?.masks?.takeIf { it.isNotEmpty() }?.let { ms ->
            val k = listOf(ms, src.w, src.h).hashCode()
            bitmapTex[k] ?: MaskRasterizer.render(ms, max(src.w, 16), max(src.h, 16))?.let { cacheBitmap(k, it) }
        }
        p.i("uUseMask", if (maskBmp != null) 1 else 0)
        if (maskBmp != null) p.tex("uMask", 4, GLES30.GL_TEXTURE_2D, maskBmp.id)
        // AI matte (per-frame mask images in a cache folder)
        val ai = l?.aiMatte?.let { am -> aiMatteTexture(am)?.let { am to it } }
        p.i("uUseAi", if (ai != null) 1 else 0)
        if (ai != null) {
            p.tex("uAi", 5, GLES30.GL_TEXTURE_2D, ai.second.id); p.f("uAiSoft", ai.first.edgeSoftness); p.f("uAiChoke", ai.first.choke)
            p.i("uAiView", if (ai.first.view == KeyView.MATTE) 1 else 0)
        }
        // Object-removal patch
        val patch = l?.patch?.let { (dir, idx) -> patchTexture(dir, idx) }
        p.i("uUsePatch", if (patch != null) 1 else 0)
        if (patch != null) p.tex("uPatch", 7, GLES30.GL_TEXTURE_2D, patch.id)
        // Track matte
        p.i("uMatteType", matte?.second?.let { it.ordinal + 1 } ?: 0)
        if (matte != null) p.tex("uMatte", 6, GLES30.GL_TEXTURE_2D, matte.first.texture.id)
        Quad.draw()
        return out
    }

    private fun aiMatteTexture(am: app.bekocat.core.timeline.AiMatteFrame): Texture? {
        val dir = File(am.cacheDir)
        val f = File(dir, "m_%06d.png".format(am.frameIndex)).takeIf { it.exists() }
            ?: dir.listFiles { x -> x.name.startsWith("m_") }?.minByOrNull { kotlin.math.abs((it.name.substring(2, 8).toLongOrNull() ?: 0) - am.frameIndex) }
            ?: return null
        val k = listOf(f.path, am.brush).hashCode()
        bitmapTex[k]?.let { return it }
        val b = BitmapFactory.decodeFile(f.path)?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        if (am.brush.isNotEmpty()) {
            val c = Canvas(b)
            for (s in am.brush) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (s.add) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                    strokeWidth = (s.radius * 2 * min(b.width, b.height)).toFloat(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
                }
                val pts = s.points.chunked(2)
                for (i in 1 until pts.size) c.drawLine((pts[i - 1][0] * b.width).toFloat(), (pts[i - 1][1] * b.height).toFloat(), (pts[i][0] * b.width).toFloat(), (pts[i][1] * b.height).toFloat(), paint)
            }
        }
        return cacheBitmap(k, b)
    }

    private fun patchTexture(dir: String, index: Long): Texture? {
        val f = File(dir, "f_%06d.png".format(index)).takeIf { it.exists() } ?: return null
        val k = f.path.hashCode()
        bitmapTex[k]?.let { return it }
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        return cacheBitmap(k, b)
    }

    private fun lutTexture(uri: String): Int? = luts[uri] ?: runCatching {
        val text = context.contentResolver.openInputStream(Uri.parse(uri))?.bufferedReader()?.use { it.readText() } ?: return null
        val lut = ColorMath.parseCube(text)
        Texture.lut3d(lut.size, lut.data).also { luts[uri] = it }
    }.getOrNull()

    // ---------------- blending ----------------
    private fun blendInto(back: Fbo, src: Fbo, mode: BlendMode): Fbo {
        val out = obtain(back.width, back.height)
        out.bind()
        blendProg.use()
        blendProg.tex("uBack", 0, GLES30.GL_TEXTURE_2D, back.texture.id)
        blendProg.tex("uSrc", 1, GLES30.GL_TEXTURE_2D, src.texture.id)
        blendProg.v2("uOut", out.width.toFloat(), out.height.toFloat())
        blendProg.i("uMode", mode.ordinal)
        Quad.draw()
        recycle(back)
        return out
    }

    // ---------------- transitions ----------------
    private fun transitionShaderId(k: TransitionKind): Int = when (k) {
        TransitionKind.DISSOLVE -> 0; TransitionKind.DIP_TO_BLACK -> 1; TransitionKind.DIP_TO_WHITE -> 2; TransitionKind.LUMA_FADE -> 3
        TransitionKind.WIPE_LEFT -> 4; TransitionKind.WIPE_RIGHT -> 5; TransitionKind.WIPE_UP -> 6; TransitionKind.WIPE_DOWN -> 7
        TransitionKind.SLIDE_LEFT -> 8; TransitionKind.SLIDE_RIGHT -> 9; TransitionKind.PUSH_LEFT -> 10; TransitionKind.PUSH_UP -> 11
        TransitionKind.WHIP_PAN -> 12; TransitionKind.ZOOM_IN -> 13; TransitionKind.ZOOM_OUT -> 14; TransitionKind.MATCH_ZOOM -> 15
        TransitionKind.SPIN -> 16; TransitionKind.PERSPECTIVE -> 17; TransitionKind.BLUR -> 18; TransitionKind.LIGHT_LEAK -> 19
        TransitionKind.FILM_BURN -> 20; TransitionKind.GLITCH -> 21; TransitionKind.RGB_SPLIT -> 22; TransitionKind.DISPLACEMENT -> 23
        TransitionKind.SHAPE_CIRCLE -> 24; TransitionKind.SHAPE_DIAMOND -> 25; TransitionKind.CAMERA_SHAKE -> 26; TransitionKind.PAGE_TURN -> 27
        TransitionKind.STRETCH -> 28; TransitionKind.MOSAIC -> 29; TransitionKind.FLASH -> 30; TransitionKind.SMOOTH_WARP -> 31
        TransitionKind.RADIAL -> 32; TransitionKind.SPLIT -> 33; TransitionKind.BEAT_CUT -> 34; TransitionKind.SEAMLESS_LOOP -> 35
        TransitionKind.CUSTOM_MASK -> 36
    }

    private fun renderTransition(t: TransitionPlan, plan: FramePlan, accum: Fbo, timeSec: Double): Fbo? {
        val a = t.a?.let { renderLayer(it, plan, accum, timeSec) }
        val b = t.b?.let { renderLayer(it, plan, accum, timeSec) }
        val W = plan.width; val H = plan.height
        val ta = a ?: obtain(W, H).also { it.clear() }
        val tb = b ?: obtain(W, H).also { it.clear() }
        val out = obtain(W, H)
        out.bind()
        transProg.use()
        transProg.tex("uA", 0, GLES30.GL_TEXTURE_2D, ta.texture.id)
        transProg.tex("uB", 1, GLES30.GL_TEXTURE_2D, tb.texture.id)
        val maskTex = t.transition.maskAssetId?.let { assetUri(it) }?.let { imageTexture(it) }
        transProg.i("uUseMaskTex", if (maskTex != null) 1 else 0)
        if (maskTex != null) transProg.tex("uMaskTex", 2, GLES30.GL_TEXTURE_2D, maskTex.id)
        transProg.v2("uOut", W.toFloat(), H.toFloat())
        transProg.f("uP", t.progress.toFloat()); transProg.f("uIntensity", t.transition.intensity.toFloat())
        transProg.f("uDir", t.transition.direction.toFloat()); transProg.f("uSeed", (t.transition.kind.ordinal * 13.37f))
        transProg.i("uKind", transitionShaderId(t.transition.kind))
        Quad.draw()
        recycle(ta); recycle(tb)
        return out
    }

    // ---------------- captions ----------------
    private fun renderCaption(f: CaptionFrame, plan: FramePlan): Fbo? {
        val k = f.hashCode() * 31 + plan.width
        val tex = bitmapTex[k] ?: text.renderCaption(f, plan.width, plan.height)?.bitmap?.let { cacheBitmap(k, it) } ?: return null
        val tf = TransformFrame(posY = f.positionY * 2 - 1)
        return renderSourceLayer(Src(tex.id, TEX_BITMAP, true, tex.width, tex.height, true), tf, emptyList(), plan, null, null)
    }

    // ---------------- effects ----------------
    fun blur(src: Fbo, radius: Double, dirOnly: Pair<Float, Float>? = null): Fbo {
        val h = obtain(src.width, src.height)
        pass(blurProg, h) { blurProg.tex("uTex", 0, GLES30.GL_TEXTURE_2D, src.texture.id); blurProg.v2("uDir", dirOnly?.first ?: 1f, dirOnly?.second ?: 0f); blurProg.f("uRadius", radius.toFloat()) }
        if (dirOnly != null) { recycle(src); return h }
        val v = obtain(src.width, src.height)
        pass(blurProg, v) { blurProg.tex("uTex", 0, GLES30.GL_TEXTURE_2D, h.texture.id); blurProg.v2("uDir", 0f, 1f); blurProg.f("uRadius", radius.toFloat()) }
        recycle(h); recycle(src)
        return v
    }

    private fun blurCopy(src: Fbo, radius: Double): Fbo {
        val c = obtain(src.width, src.height); copyTex(src.texture, c); return blur(c, radius)
    }

    private inline fun pass(p: Program, into: Fbo, setup: () -> Unit) {
        into.bind(); p.use(); p.v2("uOut", into.width.toFloat(), into.height.toFloat()); setup(); Quad.draw()
    }

    private fun fx(src: Fbo, id: Int, timeSec: Double, blurTex: Fbo? = null, vararg params: Double): Fbo {
        val out = obtain(src.width, src.height)
        pass(fxProg, out) {
            fxProg.tex("uTex", 0, GLES30.GL_TEXTURE_2D, src.texture.id)
            if (blurTex != null) fxProg.tex("uBlur", 1, GLES30.GL_TEXTURE_2D, blurTex.texture.id)
            fxProg.i("uFx", id); fxProg.f("uTime", timeSec.toFloat())
            val names = arrayOf("uP0", "uP1", "uP2", "uP3", "uP4")
            for (i in names.indices) fxProg.f(names[i], params.getOrElse(i) { 0.0 }.toFloat())
        }
        recycle(src)
        return out
    }

    private fun applyEffects(input: Fbo, effects: List<EffectFrame>, clipId: String, t: Double): Fbo {
        var img = input
        val scale = min(img.width, img.height) / 1080.0
        for (e in effects) {
            img = when (e.type) {
                "gaussian_blur" -> blur(img, e.p("radius") * scale)
                "directional_blur" -> fx(img, 1, t, null, e.p("length"), e.p("angle"))
                "radial_blur" -> fx(img, 2, t, null, e.p("angle"), e.p("cx"), e.p("cy"))
                "zoom_blur" -> fx(img, 3, t, null, e.p("strength"), e.p("cx"), e.p("cy"))
                "lens_blur" -> { val b = blurCopy(img, e.p("radius") * scale); fx(img, 36, t, b, e.p("highlights")).also { recycle(b) } }
                "sharpen" -> { val b = blurCopy(img, e.p("radius") * 2 * scale); fx(img, 4, t, b, e.p("amount")).also { recycle(b) } }
                "denoise" -> fx(img, 5, t, null, e.p("strength"))
                "glow", "bloom", "halation" -> {
                    val bright = obtain(img.width, img.height); copyTex(img.texture, bright)
                    val th = fx(bright, 33, t, null, e.p("threshold"))
                    val b = blur(th, e.p("radius") * scale)
                    val tint = if (e.type == "halation") doubleArrayOf(1.0, 0.35, 0.18) else doubleArrayOf(1.0, 1.0, 1.0)
                    fx(img, 6, t, b, e.p("intensity"), tint[0], tint[1], tint[2], if (e.type == "bloom") 2.0 else 1.0).also { recycle(b) }
                }
                "diffusion" -> { val b = blurCopy(img, e.p("radius") * scale); fx(img, 6, t, b, e.p("amount"), 1.0, 1.0, 1.0, 3.0).also { recycle(b) } }
                "vignette" -> fx(img, 7, t, null, e.p("amount"), e.p("size"), e.p("softness"))
                "film_grain" -> fx(img, 8, t, null, e.p("amount"), e.p("size"), e.p("seed"))
                "chromatic_aberration" -> fx(img, 9, t, null, e.p("amount"))
                "lens_distortion" -> fx(img, 10, t, null, e.p("k"), e.p("zoom"))
                "scanlines" -> fx(img, 11, t, null, e.p("amount"), e.p("density"))
                "vhs" -> fx(img, 12, t, null, e.p("amount"), e.p("seed"))
                "rgb_split" -> fx(img, 13, t, null, e.p("amount"), e.p("angle"))
                "glitch" -> fx(img, 14, t, null, e.p("amount"), e.p("rate"), e.p("seed"))
                "pixelate" -> fx(img, 15, t, null, e.p("size") * scale)
                "mosaic" -> fx(img, 16, t, null, e.p("size") * scale)
                "displacement" -> fx(img, 17, t, null, e.p("amount"), e.p("scale"), e.p("speed"))
                "turbulent" -> fx(img, 18, t, null, e.p("amount"), e.p("scale"), e.p("speed"), e.p("seed"))
                "drop_shadow" -> { val b = blurCopy(img, e.p("blur") * scale); fx(img, 19, t, b, e.p("distance"), e.p("angle"), e.p("opacity")).also { recycle(b) } }
                "reflection" -> fx(img, 20, t, null, e.p("opacity"), e.p("falloff"))
                "outline" -> fx(img, 21, t, null, e.p("width") * scale, e.p("r"), e.p("g"), e.p("b"))
                "edge_detect" -> fx(img, 22, t, null, e.p("amount"))
                "duotone" -> fx(img, 23, t, null, e.p("shadowHue"), e.p("highlightHue"), e.p("amount"))
                "gradient_map" -> fx(img, 24, t, null, e.p("hueA"), e.p("hueB"), e.p("amount"))
                "posterize" -> fx(img, 25, t, null, e.p("levels"))
                "invert" -> fx(img, 26, t, null, e.p("amount"))
                "letterbox" -> fx(img, 27, t, null, e.p("ratio"), e.p("opacity"))
                "film_gate" -> fx(img, 28, t, null, e.p("roundness"), e.p("weave"), e.p("seed"))
                "particles" -> fx(img, 29, t, null, e.p("density"), e.p("size"), e.p("speed"), e.p("seed"))
                "mirror" -> fx(img, 30, t, null, e.p("axis"))
                "light_leak" -> fx(img, 31, t, null, e.p("amount"), e.p("hue"), e.p("speed"), e.p("seed"))
                "anamorphic_flare" -> {
                    val bright = obtain(img.width, img.height); copyTex(img.texture, bright)
                    val th = fx(bright, 33, t, null, e.p("threshold"))
                    val streak = blur(th, 64.0 * e.p("length") * scale + 1, 1f to 0f)
                    fx(img, 32, t, streak, e.p("intensity")).also { recycle(streak) }
                }
                "luma_key" -> fx(img, 34, t, null, e.p("threshold"), e.p("softness"), e.p("invert"))
                "flicker_reduce" -> {
                    val mean = meanLuma(img)
                    val ema = flicker[clipId]?.let { it * 0.85 + mean * 0.15 } ?: mean
                    flicker[clipId] = ema
                    val gain = 1 + (ema / mean.coerceAtLeast(1e-3) - 1) * e.p("strength")
                    fx(img, 35, t, null, gain.coerceIn(0.7, 1.4))
                }
                else -> img
            }
        }
        return img
    }

    /** Mean luma of an image (16×16 downsample, read back). */
    fun meanLuma(img: Fbo): Double {
        val small = obtain(16, 16)
        copyTex(img.texture, small)
        val buf = java.nio.ByteBuffer.allocateDirect(16 * 16 * 4)
        small.bind()
        GLES30.glReadPixels(0, 0, 16, 16, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        recycle(small)
        var s = 0.0
        for (i in 0 until 256) { val r = buf.get(i * 4).toInt() and 0xFF; val g = buf.get(i * 4 + 1).toInt() and 0xFF; val b = buf.get(i * 4 + 2).toInt() and 0xFF; s += 0.2126 * r + 0.7152 * g + 0.0722 * b }
        return s / 256 / 255
    }

    /** Draws an FBO into the current default framebuffer area (letterboxed). */
    fun present(img: Fbo, viewportW: Int, viewportH: Int, bgArgb: Int = 0xFF000000.toInt()) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportW, viewportH)
        GLES30.glClearColor(((bgArgb shr 16) and 0xFF) / 255f, ((bgArgb shr 8) and 0xFF) / 255f, (bgArgb and 0xFF) / 255f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val s = min(viewportW.toFloat() / img.width, viewportH.toFloat() / img.height)
        val w = (img.width * s).toInt(); val h = (img.height * s).toInt()
        GLES30.glViewport((viewportW - w) / 2, (viewportH - h) / 2, w, h)
        presentProg.use()
        presentProg.tex("uTex", 0, GLES30.GL_TEXTURE_2D, img.texture.id)
        presentProg.v2("uOut", w.toFloat(), h.toFloat())
        presentProg.v2("uSrc", img.width.toFloat(), img.height.toFloat())
        presentProg.i("uFlip", 0)
        presentProg.v4("uBg", 0f, 0f, 0f, 1f)
        // gl_FragCoord is offset by the viewport origin; compensate via uOut-relative mapping.
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST); GLES30.glScissor((viewportW - w) / 2, (viewportH - h) / 2, w, h)
        presentOffset((viewportW - w) / 2f, (viewportH - h) / 2f)
        Quad.draw()
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        presentOffset(0f, 0f)
    }

    private fun presentOffset(x: Float, y: Float) { presentProg.v2("uOffset", x, y) }

    /** Releases decoders and textures not referenced by recent frames (call when the project changes a lot). */
    fun trim() {
        for (v in videos.values) v.release(); videos.clear()
        for (t in bitmapTex.values) t.release(); bitmapTex.clear()
    }

    fun release() {
        trim()
        images.values.forEach { it.release() }; images.clear()
        solid.values.forEach { it.release() }
        luts.values.forEach { GLES30.glDeleteTextures(1, intArrayOf(it), 0) }
        curves.values.forEach { GLES30.glDeleteTextures(1, intArrayOf(it), 0) }
        pool.values.forEach { q -> q.forEach { it.release() } }; pool.clear()
        listOf(layerProg, blendProg, transProg, blurProg, fxProg, flowProg, mixProg, presentProg, copyOes).forEach { it.release() }
        Quad.reset()
    }
}
