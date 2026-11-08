package app.bekocat.core.timeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/*
 * The render plan is the bridge between the project model and the GPU engine.
 * For any timeline time it lists exactly what to draw, bottom → top, with every
 * animated value already evaluated. Preview and export both render FramePlans,
 * so they cannot diverge. It is pure Kotlin and fully unit-tested.
 */

data class TransformFrame(
    val posX: Double = 0.0, val posY: Double = 0.0, val posZ: Double = 0.0,
    val scaleX: Double = 1.0, val scaleY: Double = 1.0,
    val rotation: Double = 0.0, val rotX: Double = 0.0, val rotY: Double = 0.0, val skewX: Double = 0.0,
    val opacity: Double = 1.0, val anchorX: Double = 0.0, val anchorY: Double = 0.0,
    val flipH: Boolean = false, val flipV: Boolean = false, val fit: FitMode = FitMode.FIT,
    val cropL: Double = 0.0, val cropT: Double = 0.0, val cropR: Double = 0.0, val cropB: Double = 0.0,
    val cornerPin: DoubleArray? = null,
) {
    override fun equals(other: Any?) = other is TransformFrame && toString() == other.toString() && cornerPin.contentEqualsNullable(other.cornerPin)
    override fun hashCode() = toString().hashCode()
}

private fun DoubleArray?.contentEqualsNullable(o: DoubleArray?) = if (this == null) o == null else o != null && this.contentEquals(o)

data class MaskFrame(
    val shape: MaskShape, val mode: MaskMode,
    val cx: Double, val cy: Double, val w: Double, val h: Double, val rotation: Double,
    val vertices: List<MaskVertex>, val feather: Double, val expansion: Double, val opacity: Double, val inverted: Boolean,
)

data class EffectFrame(val type: String, val params: Map<String, Double>, val options: Map<String, String>) {
    fun p(key: String): Double = params[key] ?: EffectCatalog.find(type)?.params?.firstOrNull { it.key == key }?.default ?: 0.0
}

data class ShapeFrame(
    val style: ShapeStyle, val width: Double, val height: Double, val cornerRadius: Double, val strokeWidth: Double,
    val trimStart: Double, val trimEnd: Double, val trimOffset: Double, val vertices: List<MaskVertex>,
)

data class CaptionWordFrame(val text: String, val active: Boolean, val progress: Double, val speaker: Int, val past: Boolean)

data class CaptionFrame(
    val trackId: String,
    val lines: List<List<CaptionWordFrame>>,
    val preset: CaptionStylePreset,
    val style: TextStyle,
    val positionY: Double,
    val speakerColors: List<Long>,
    val translation: String?,
    val segmentProgress: Double,
)

sealed class LayerSource {
    data class Video(val assetId: String, val uri: String, val sourceTime: Time, val frameBlend: FrameBlend, val speed: Double, val assetDuration: Time?) : LayerSource()
    data class Image(val assetId: String, val uri: String) : LayerSource()
    data class Text(val style: TextStyle, val states: List<UnitState>) : LayerSource()
    data class Shape(val shape: ShapeFrame) : LayerSource()
    data class Solid(val argb: Long) : LayerSource()
    data class Nested(val plan: FramePlan) : LayerSource()
    object Adjustment : LayerSource()
}

data class MattePlan(val layer: LayerPlan, val type: MatteType)

data class AiMatteFrame(val cacheDir: String, val frameIndex: Long, val edgeSoftness: Double, val choke: Double, val view: KeyView,
                        val brush: List<BrushStroke>, val contactShadow: Double)

data class LayerPlan(
    val clipId: String,
    val source: LayerSource,
    val transform: TransformFrame,
    val parents: List<TransformFrame>,
    val contentW: Int, val contentH: Int,          // 0 = intrinsic (text/shape measured by the renderer)
    val blend: BlendMode,
    val opacity: Double,
    val color: ColorAdjust,
    val effects: List<EffectFrame>,
    val masks: List<MaskFrame>,
    val chroma: ChromaKey?,
    val aiMatte: AiMatteFrame?,
    val matte: MattePlan?,
    /** Additional sub-frame transforms for motion blur (excluding the main one). */
    val motionSamples: List<Pair<TransformFrame, List<TransformFrame>>> = emptyList(),
    val depthBlur: Double = 0.0,
    val lutUri: String? = null,
    /** Object-removal patch frame: (cacheDir, frame index). */
    val patch: Pair<String, Long>? = null,
)

data class TransitionPlan(val transition: Transition, val progress: Double, val a: LayerPlan?, val b: LayerPlan?, val handleMissing: Boolean)

sealed class PlanItem {
    data class Layer(val layer: LayerPlan) : PlanItem()
    data class Trans(val plan: TransitionPlan) : PlanItem()
}

data class CameraFrame(val zoom: Double, val x: Double, val y: Double, val z: Double, val rotX: Double, val rotY: Double,
                       val focal: Double, val dof: Boolean, val focus: Double, val aperture: Double)

data class FramePlan(
    val time: Time,
    val width: Int, val height: Int,
    val backgroundArgb: Long,
    val backgroundUri: String?,
    val items: List<PlanItem>,
    val captions: List<CaptionFrame>,
    val camera: CameraFrame?,
)

object RenderPlanner {

    private const val MAX_NEST = 8

    fun plan(p: Project, t: Time): FramePlan = planSequence(p, p.sequence, t, 0)

    fun planSequence(p: Project, seq: Sequence, t: Time, depth: Int): FramePlan {
        val camera = if (seq.camera.enabled) evalCamera(seq.camera, t) else null
        val hiddenMatteSources = seq.tracks.flatMap { it.clips }.mapNotNull { it.matte }.filter { it.hideSource }.map { it.sourceClipId }.toSet()
        val items = ArrayList<PlanItem>()
        for (track in seq.tracks) {
            if (track.hidden || track.kind == TrackKind.AUDIO) continue
            val sorted = track.clips.filter { it.enabled }.sortedBy { it.timelineStart }
            var handledTransition = false
            for ((i, c) in sorted.withIndex()) {
                val prev = sorted.getOrNull(i - 1)
                val tr = c.transitionIn
                if (tr != null && prev != null && prev.timelineEnd == c.timelineStart) {
                    val (w0, w1) = transitionWindow(tr, c.timelineStart)
                    if (t >= w0 && t < w1) {
                        val d = (w1 - w0).toDouble().coerceAtLeast(1e-9)
                        val prog = Easing.bezier(tr.easing, (t - w0).toDouble() / d)
                        val a = layerFor(p, seq, prev, t, depth, allowOutside = true)
                        val b = layerFor(p, seq, c, t, depth, allowOutside = true)
                        val missing = handleMissing(p, prev, c, w0, w1)
                        items += PlanItem.Trans(TransitionPlan(tr, prog, a, b, missing))
                        handledTransition = true
                        break
                    }
                }
            }
            if (handledTransition) continue
            val c = sorted.firstOrNull { it.contains(t) } ?: continue
            if (c.id in hiddenMatteSources) continue
            layerFor(p, seq, c, t, depth, allowOutside = false)?.let { items += PlanItem.Layer(it) }
        }
        val ordered = if (camera != null) items.sortedByDescending { itemZ(it) } else items
        return FramePlan(
            time = t, width = seq.width, height = seq.height, backgroundArgb = seq.backgroundArgb,
            backgroundUri = p.asset(seq.backgroundAssetId)?.uri,
            items = ordered,
            captions = if (depth == 0) captionsAt(seq, t) else emptyList(),
            camera = camera,
        )
    }

    private fun itemZ(i: PlanItem) = when (i) {
        is PlanItem.Layer -> i.layer.transform.posZ
        is PlanItem.Trans -> i.plan.b?.transform?.posZ ?: i.plan.a?.transform?.posZ ?: 0.0
    }

    fun transitionWindow(tr: Transition, cut: Time): Pair<Time, Time> = when (tr.align) {
        TransitionAlign.CENTER -> (cut - tr.duration / Rational.of(2)) to (cut + tr.duration / Rational.of(2))
        TransitionAlign.BEFORE_CUT -> (cut - tr.duration) to cut
        TransitionAlign.AFTER_CUT -> cut to (cut + tr.duration)
    }

    /** True when either side lacks real media for the transition overlap (it will hold the edge frame). */
    fun handleMissing(p: Project, a: Clip, b: Clip, w0: Time, w1: Time): Boolean {
        val aDur = p.asset(a.assetId)?.duration
        val needA = a.sourceTimeAt(w1)
        val bIn = b.sourceTimeAt(w0)
        val aMissing = a.kind == ClipKind.MEDIA && aDur != null && needA > aDur
        val bMissing = b.kind == ClipKind.MEDIA && p.asset(b.assetId)?.kind == AssetKind.VIDEO && bIn.isNegative
        return aMissing || bMissing
    }

    fun evalTransform(tf: Transform, lt: Time): TransformFrame = TransformFrame(
        posX = tf.positionX.valueAt(lt), posY = tf.positionY.valueAt(lt), posZ = tf.positionZ.valueAt(lt),
        scaleX = tf.scale.valueAt(lt) * tf.scaleX.valueAt(lt), scaleY = tf.scale.valueAt(lt) * tf.scaleY.valueAt(lt),
        rotation = tf.rotation.valueAt(lt), rotX = tf.rotationX.valueAt(lt), rotY = tf.rotationY.valueAt(lt), skewX = tf.skewX.valueAt(lt),
        opacity = tf.opacity.valueAt(lt).coerceIn(0.0, 1.0), anchorX = tf.anchorX, anchorY = tf.anchorY,
        flipH = tf.flipH, flipV = tf.flipV, fit = tf.fit,
        cropL = tf.cropLeft.valueAt(lt).coerceIn(0.0, 0.49), cropT = tf.cropTop.valueAt(lt).coerceIn(0.0, 0.49),
        cropR = tf.cropRight.valueAt(lt).coerceIn(0.0, 0.49), cropB = tf.cropBottom.valueAt(lt).coerceIn(0.0, 0.49),
        cornerPin = tf.cornerPin?.takeIf { it.size == 8 }?.map { it.valueAt(lt) }?.toDoubleArray(),
    )

    private fun parentsOf(seq: Sequence, c: Clip, t: Time): List<TransformFrame> {
        val out = ArrayList<TransformFrame>()
        var cur = c.parentClipId
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur) && out.size < 8) {
            val pc = seq.findClip(cur)?.second ?: break
            out += evalTransform(pc.transform, t - pc.timelineStart)
            cur = pc.parentClipId
        }
        return out // innermost parent first
    }

    private fun evalMask(m: Mask, lt: Time, p: Project, clip: Clip): MaskFrame {
        var verts = if (m.pathKeys.size >= 2) interpolatePath(m.pathKeys, lt) else if (m.pathKeys.size == 1) m.pathKeys[0].vertices else m.vertices
        var cx = m.centerX.valueAt(lt); var cy = m.centerY.valueAt(lt)
        m.trackId?.let { tid ->
            p.tracking.firstOrNull { it.id == tid }?.let { tr ->
                val (ox, oy) = trackOffset(tr, clip.sourceTimeAt(clip.timelineStart + lt))
                cx += ox; cy += oy
                verts = verts.map { v -> v.copy(x = v.x + ox, y = v.y + oy) }
            }
        }
        return MaskFrame(m.shape, m.mode, cx, cy, m.width.valueAt(lt), m.height.valueAt(lt), m.rotation.valueAt(lt), verts,
            m.feather.valueAt(lt), m.expansion.valueAt(lt), m.opacity.valueAt(lt).coerceIn(0.0, 1.0), m.inverted)
    }

    /** Offset of a tracked point relative to its first sample, at source time [srcT]. */
    fun trackOffset(tr: MotionTrack, srcT: Time): Pair<Double, Double> {
        if (tr.times.isEmpty()) return 0.0 to 0.0
        val (x, y) = trackPosition(tr, srcT)
        return (x - tr.xs[0]) to (y - tr.ys[0])
    }

    fun trackPosition(tr: MotionTrack, srcT: Time): Pair<Double, Double> {
        val ts = tr.times
        if (srcT <= ts.first()) return tr.xs.first() to tr.ys.first()
        if (srcT >= ts.last()) return tr.xs.last() to tr.ys.last()
        var i = ts.indexOfLast { it <= srcT }.coerceAtMost(ts.size - 2)
        if (i < 0) i = 0
        val u = ((srcT - ts[i]).toDouble() / (ts[i + 1] - ts[i]).toDouble()).coerceIn(0.0, 1.0)
        return (tr.xs[i] + (tr.xs[i + 1] - tr.xs[i]) * u) to (tr.ys[i] + (tr.ys[i + 1] - tr.ys[i]) * u)
    }

    fun interpolatePath(keys: List<MaskPathKey>, t: Time): List<MaskVertex> {
        if (t <= keys.first().time) return keys.first().vertices
        if (t >= keys.last().time) return keys.last().vertices
        val i = keys.indexOfLast { it.time <= t }
        val a = keys[i]; val b = keys[i + 1]
        val raw = (t - a.time).toDouble() / (b.time - a.time).toDouble()
        val u = when (a.interpolation) { Interpolation.HOLD -> 0.0; Interpolation.LINEAR -> raw; Interpolation.BEZIER -> CubicBezier.EASY_EASE.solve(raw) }
        if (a.vertices.size != b.vertices.size) return if (u < 0.5) a.vertices else b.vertices
        return a.vertices.zip(b.vertices) { va, vb ->
            MaskVertex(va.x + (vb.x - va.x) * u, va.y + (vb.y - va.y) * u, va.inX + (vb.inX - va.inX) * u, va.inY + (vb.inY - va.inY) * u,
                va.outX + (vb.outX - va.outX) * u, va.outY + (vb.outY - va.outY) * u)
        }
    }

    private fun evalEffects(list: List<EffectInstance>, lt: Time) =
        list.filter { it.enabled }.map { e -> EffectFrame(e.type, e.params.mapValues { it.value.valueAt(lt) }, e.options) }

    private fun evalShape(s: ShapeStyle, lt: Time) = ShapeFrame(
        s, s.width.valueAt(lt), s.height.valueAt(lt), s.cornerRadius.valueAt(lt), s.strokeWidth.valueAt(lt),
        s.trimStart.valueAt(lt).coerceIn(0.0, 1.0), s.trimEnd.valueAt(lt).coerceIn(0.0, 1.0), s.trimOffset.valueAt(lt),
        if (s.pathKeys.size >= 2) interpolatePath(s.pathKeys, lt) else s.path,
    )

    fun layerFor(p: Project, seq: Sequence, c: Clip, t: Time, depth: Int, allowOutside: Boolean): LayerPlan? {
        // Keyframes are evaluated in clip-local time; outside the clip (transition handles) they hold.
        val lt = (t - c.timelineStart).let { if (it.isNegative) Rational.ZERO else if (it > c.duration) c.duration else it }
        val source: LayerSource = when (c.kind) {
            ClipKind.MEDIA -> {
                val a = p.asset(c.assetId) ?: return null
                when (a.kind) {
                    AssetKind.VIDEO -> {
                        var st = c.sourceTimeAt(t)
                        if (st.isNegative) st = Rational.ZERO
                        a.duration?.let { d -> val last = d - (a.frameRate ?: seq.frameRate).frameDuration; if (st > last) st = if (last.isNegative) Rational.ZERO else last }
                        LayerSource.Video(a.id, a.uri, st, c.frameBlend, abs(c.speedAt(t)), a.duration)
                    }
                    AssetKind.IMAGE -> LayerSource.Image(a.id, a.uri)
                    else -> return null
                }
            }
            ClipKind.TEXT -> {
                val ts = c.text ?: return null
                LayerSource.Text(ts, TextAnimator.states(ts.text, ts.animation, lt.toDouble(), c.duration.toDouble()))
            }
            ClipKind.SHAPE -> LayerSource.Shape(evalShape(c.shape ?: return null, lt))
            ClipKind.SOLID -> LayerSource.Solid(c.solidArgb)
            ClipKind.ADJUSTMENT -> LayerSource.Adjustment
            ClipKind.NULL_OBJECT -> return null
            ClipKind.COMPOUND -> {
                if (depth >= MAX_NEST) return null
                val comp = p.composition(c.compoundSequenceId) ?: return null
                val nestedT = c.sourceTimeAt(t)
                LayerSource.Nested(planSequence(p, comp, nestedT, depth + 1))
            }
        }
        val (cw, ch) = when (source) {
            is LayerSource.Video, is LayerSource.Image -> p.asset(c.assetId)!!.let { it.width to it.height }
            is LayerSource.Nested -> source.plan.width to source.plan.height
            is LayerSource.Solid, LayerSource.Adjustment -> seq.width to seq.height
            else -> 0 to 0
        }
        val tf = evalTransform(c.transform, lt)
        val parents = parentsOf(seq, c, t)
        val matte = c.matte?.let { m ->
            seq.findClip(m.sourceClipId)?.second?.takeIf { it.id != c.id }?.let { src ->
                layerFor(p, seq, src.copy(matte = null), t, depth, allowOutside = true)?.let { MattePlan(it, m.type) }
            }
        }
        val fxShutter = c.effects.firstOrNull { it.enabled && it.type == "motion_blur" }?.params?.get("shutter")?.valueAt(lt) ?: 0.0
        val shutterDeg = maxOf(c.transform.motionBlurShutter, fxShutter)
        val motion = if (shutterDeg > 0.0) {
            val fd = seq.frameRate.frameDuration.toDouble()
            val shutter = shutterDeg / 360.0 * fd
            val n = c.transform.motionBlurSamples.coerceIn(2, 16)
            (0 until n).map { k ->
                val off = Rational.fromDouble(-shutter / 2 + shutter * k / (n - 1), 1_000_000)
                val st = t + off
                evalTransform(c.transform, (st - c.timelineStart).let { if (it.isNegative) Rational.ZERO else it }) to parentsOf(seq, c, st)
            }
        } else emptyList()
        val cam = if (seq.camera.enabled) evalCamera(seq.camera, t) else null
        val dof = if (cam != null && cam.dof) (abs(tf.posZ - cam.focus) * cam.aperture * 24).coerceAtMost(40.0) else 0.0
        val opacity = tf.opacity * (if (c.kind == ClipKind.MEDIA) fadeOpacity(c, t) else 1.0)
        return LayerPlan(
            clipId = c.id, source = source, transform = tf, parents = parents, contentW = cw, contentH = ch,
            blend = c.transform.blendMode, opacity = opacity, color = c.color, effects = evalEffects(c.effects, lt),
            masks = c.masks.map { evalMask(it, lt, p, c) }, chroma = c.chroma?.takeIf { it.enabled },
            aiMatte = c.aiMatte?.takeIf { it.enabled }?.let { am ->
                val fps = (p.asset(c.assetId)?.frameRate ?: seq.frameRate)
                AiMatteFrame(am.cacheDir, fps.frameAt(c.sourceTimeAt(t)), am.edgeSoftness, am.choke, am.view, am.brush.filter { lt >= it.start && lt < it.end }, am.contactShadow)
            },
            matte = matte, motionSamples = motion, depthBlur = dof,
            lutUri = p.asset(c.color.lutAssetId)?.uri,
            patch = c.fillPatch?.takeIf { it.enabled }?.let { fp -> fp.cacheDir to (p.asset(c.assetId)?.frameRate ?: seq.frameRate).frameAt(c.sourceTimeAt(t)) },
        )
    }

    /** Visual fade derived from the clip's (linked) audio fade settings is NOT applied; video fades use opacity keyframes. */
    private fun fadeOpacity(c: Clip, t: Time): Double = 1.0

    private fun evalCamera(cam: Camera, t: Time) = CameraFrame(
        cam.zoom.valueAt(t), cam.positionX.valueAt(t), cam.positionY.valueAt(t), cam.positionZ.valueAt(t),
        cam.rotationX.valueAt(t), cam.rotationY.valueAt(t), cam.focalLength, cam.depthOfField, cam.focusDistance.valueAt(t), cam.aperture,
    )

    // ---------------- captions ----------------
    fun captionsAt(seq: Sequence, t: Time): List<CaptionFrame> = seq.captions.filter { it.burnIn }.mapNotNull { ct ->
        val seg = ct.segments.firstOrNull { t >= it.start && t < it.end } ?: return@mapNotNull null
        val preset = CaptionStyleCatalog.find(ct.stylePreset)
        val perLine = minOf(ct.maxWordsPerLine, preset.wordsPerLine).coerceAtLeast(1)
        val words = seg.words
        if (words.isEmpty()) return@mapNotNull null
        val activeIdx = words.indexOfLast { t >= it.start }.coerceAtLeast(0)
        val pageSize = perLine * ct.maxLines.coerceAtLeast(1)
        val page = activeIdx / pageSize
        val pageWords = words.drop(page * pageSize).take(pageSize)
        val frames = pageWords.mapIndexed { i, w ->
            val gi = page * pageSize + i
            val active = gi == activeIdx && t < w.end
            val prog = ((t - w.start).toDouble() / (w.end - w.start).toDouble().coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            CaptionWordFrame(if (preset.uppercase) w.text.uppercase() else w.text, active, prog, w.speaker, gi < activeIdx || (gi == activeIdx && prog >= 1.0))
        }
        val visible = if (preset.wordByWord) frames.filterIndexed { i, _ -> page * pageSize + i == activeIdx } else frames
        val style = mergeCaptionStyle(preset.style, ct.style)
        CaptionFrame(ct.id, visible.chunked(perLine), preset, style, ct.positionY, ct.speakerColors,
            if (ct.showTranslation) seg.translation else null,
            ((t - seg.start).toDouble() / (seg.end - seg.start).toDouble()).coerceIn(0.0, 1.0))
    }

    /** User overrides (non-default fields of [user]) win over the preset. */
    fun mergeCaptionStyle(preset: TextStyle, user: TextStyle): TextStyle {
        val d = TextStyle("")
        return preset.copy(
            fontFamily = if (user.fontFamily != d.fontFamily) user.fontFamily else preset.fontFamily,
            sizeSp = if (user.sizeSp != d.sizeSp) user.sizeSp else preset.sizeSp,
            colorArgb = if (user.colorArgb != d.colorArgb) user.colorArgb else preset.colorArgb,
            backgroundArgb = if (user.backgroundArgb != d.backgroundArgb) user.backgroundArgb else preset.backgroundArgb,
            strokeArgb = if (user.strokeArgb != d.strokeArgb) user.strokeArgb else preset.strokeArgb,
            strokeWidth = if (user.strokeWidth != d.strokeWidth) user.strokeWidth else preset.strokeWidth,
            weight = if (user.weight != d.weight) user.weight else preset.weight,
        )
    }
}

/**
 * Layer geometry: maps a layer's unit UV square to output pixels through the full
 * chain fit → anchor → skew → scale → 3D rotation → position → parents → camera →
 * perspective → corner pin. Returned as the 4 projected corners plus the homography.
 */
object Geometry {

    data class Vec3(val x: Double, val y: Double, val z: Double)

    private fun rotate(p: Vec3, rx: Double, ry: Double, rz: Double): Vec3 {
        val ax = rx * PI / 180; val ay = ry * PI / 180; val az = rz * PI / 180
        // X
        var y = p.y * cos(ax) - p.z * sin(ax); var z = p.y * sin(ax) + p.z * cos(ax); var x = p.x
        // Y
        val x2 = x * cos(ay) + z * sin(ay); val z2 = -x * sin(ay) + z * cos(ay); x = x2; z = z2
        // Z
        val x3 = x * cos(az) - y * sin(az); val y3 = x * sin(az) + y * cos(az)
        return Vec3(x3, y3, z)
    }

    private fun applyFrame(p: Vec3, f: TransformFrame, outW: Int, outH: Int, withPosition: Boolean = true): Vec3 {
        var x = p.x + tan(f.skewX * PI / 180) * p.y
        var q = Vec3(x * f.scaleX, p.y * f.scaleY, p.z)
        q = rotate(q, f.rotX, f.rotY, f.rotation)
        return if (withPosition) Vec3(q.x + f.posX * outW / 2.0, q.y + f.posY * outH / 2.0, q.z + f.posZ * outH) else q
    }

    /** Fit scale of content (cw×ch) into the output. Intrinsic content (cw==0) is drawn at its pixel size. */
    fun fitScale(fit: FitMode, cw: Int, ch: Int, outW: Int, outH: Int): Pair<Double, Double> {
        if (cw <= 0 || ch <= 0) return 1.0 to 1.0
        val sx = outW.toDouble() / cw; val sy = outH.toDouble() / ch
        return when (fit) {
            FitMode.FIT -> minOf(sx, sy).let { it to it }
            FitMode.FILL -> maxOf(sx, sy).let { it to it }
            FitMode.STRETCH -> sx to sy
        }
    }

    /**
     * Output-pixel corners (TL, TR, BR, BL) of the layer's UV square.
     * [cw]/[ch] are content pixel sizes (for intrinsic content pass measured size with fit ignored).
     */
    fun corners(f: TransformFrame, parents: List<TransformFrame>, cw: Int, ch: Int, outW: Int, outH: Int,
                camera: CameraFrame?, intrinsic: Boolean = false): DoubleArray {
        val (fx, fy) = if (intrinsic) 1.0 to 1.0 else fitScale(f.fit, cw, ch, outW, outH)
        val w = cw * fx; val h = ch * fy
        val uv = arrayOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
        val out = DoubleArray(8)
        val focal = (camera?.focal ?: 1.6) * outH
        for ((i, c) in uv.withIndex()) {
            var u = c.first; var v = c.second
            if (f.flipH) u = 1 - u
            if (f.flipV) v = 1 - v
            var p = Vec3((u - 0.5) * w - f.anchorX * w / 2, (v - 0.5) * h - f.anchorY * h / 2, 0.0)
            p = applyFrame(p, f, outW, outH)
            for (par in parents) p = applyFrame(p, par, outW, outH)
            if (camera != null) {
                p = Vec3(p.x - camera.x * outW / 2, p.y - camera.y * outH / 2, p.z - camera.z * outH)
                p = rotate(p, -camera.rotX, -camera.rotY, 0.0)
                p = Vec3(p.x * camera.zoom, p.y * camera.zoom, p.z)
            }
            val z = p.z.coerceAtLeast(-focal * 0.95)
            val k = focal / (focal + z)
            out[i * 2] = p.x * k + outW / 2.0
            out[i * 2 + 1] = p.y * k + outH / 2.0
        }
        f.cornerPin?.let { cp -> for (i in 0 until 4) { out[i * 2] += cp[i * 2] * outW / 2.0; out[i * 2 + 1] += cp[i * 2 + 1] * outH / 2.0 } }
        return out
    }

    /**
     * Homography H (row-major 3×3) mapping UV (0..1) → output pixels for the given corners.
     * Solved from 4 correspondences with Gaussian elimination.
     */
    fun homography(corners: DoubleArray): DoubleArray {
        val src = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i * 2]; val y = src[i * 2 + 1]; val X = corners[i * 2]; val Y = corners[i * 2 + 1]
            a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * X, -y * X, X)
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * Y, -y * Y, Y)
        }
        for (col in 0 until 8) {
            var piv = col
            for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            val d = a[col][col]
            if (abs(d) < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            for (k in col until 9) a[col][k] /= d
            for (r in 0 until 8) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (k in col until 9) a[r][k] -= f * a[col][k]
            }
        }
        return doubleArrayOf(a[0][8], a[1][8], a[2][8], a[3][8], a[4][8], a[5][8], a[6][8], a[7][8], 1.0)
    }

    fun invert3(m: DoubleArray): DoubleArray {
        val (a, b, c) = Triple(m[0], m[1], m[2]); val (d, e, f) = Triple(m[3], m[4], m[5]); val (g, h, i) = Triple(m[6], m[7], m[8])
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        if (abs(det) < 1e-18) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val inv = doubleArrayOf(
            A, -(b * i - c * h), b * f - c * e,
            B, a * i - c * g, -(a * f - c * d),
            C, -(a * h - b * g), a * e - b * d,
        )
        return DoubleArray(9) { inv[it] / det }
    }

    fun apply(h: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val w = h[6] * x + h[7] * y + h[8]
        return ((h[0] * x + h[1] * y + h[2]) / w) to ((h[3] * x + h[4] * y + h[5]) / w)
    }
}
