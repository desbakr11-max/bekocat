package app.bekocat.core.timeline

import kotlinx.serialization.Serializable

/**
 * Immutable project model — the single source of truth. The UI and the render
 * engine read it; only edit operations produce new versions. Serialised by
 * [ProjectCodec] into the versioned .bekocat schema (times as exact "num/den").
 */

const val CURRENT_SCHEMA_VERSION = 3

@Serializable enum class AssetKind { VIDEO, AUDIO, IMAGE, FONT, LUT }

@Serializable
data class Asset(
    val id: String,
    val uri: String,
    val kind: AssetKind,
    val displayName: String = "",
    /** Null for still images, fonts and LUTs (unbounded / not time-based). */
    val duration: Time? = null,
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = false,
    val frameRate: FrameRate? = null,
    val sizeBytes: Long = 0,
    val contentHash: String = "",
    val rotationDegrees: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val colorTransfer: String = "sdr", // sdr | hlg | pq
    val variableFrameRate: Boolean = false,
    val hasAlpha: Boolean = false,
)

@Serializable enum class TrackKind { VIDEO, OVERLAY, AUDIO, TEXT }

/** Audio role used for automatic ducking (music ducks under dialogue). */
@Serializable enum class AudioRole { DIALOGUE, MUSIC, SFX, OTHER }

@Serializable
enum class BlendMode {
    NORMAL, MULTIPLY, SCREEN, OVERLAY, SOFT_LIGHT, HARD_LIGHT, DARKEN, LIGHTEN, ADD,
    DIFFERENCE, EXCLUSION, COLOR_DODGE, COLOR_BURN, HUE, SATURATION, COLOR, LUMINOSITY
}

@Serializable enum class Interpolation { HOLD, LINEAR, BEZIER }

/**
 * A keyframe on a clip-local time axis. For BEZIER segments the easing between
 * A and B is cubic-bezier(A.outX, A.outY, B.inX, B.inY) on normalised (time, value).
 * Spatial tangents (sx/sy) shape motion paths for position pairs.
 */
@Serializable
data class Keyframe(
    val time: Time,
    val value: Double,
    val interpolation: Interpolation = Interpolation.LINEAR,
    val outX: Double = 0.33, val outY: Double = 0.0,
    val inX: Double = 0.67, val inY: Double = 1.0,
    val spatialOutX: Double = 0.0, val spatialOutY: Double = 0.0,
    val spatialInX: Double = 0.0, val spatialInY: Double = 0.0,
)

/** A property that is static, keyframed, and/or driven by a safe expression. */
@Serializable
data class Animatable(
    val base: Double,
    val keyframes: List<Keyframe> = emptyList(),
    /** Optional expression (see [Expression]): e.g. "value + wiggle(2, 0.05)". */
    val expression: String? = null,
) {
    val isAnimated get() = keyframes.isNotEmpty() || expression != null

    fun valueAt(localTime: Time): Double {
        val v = KeyframeEvaluator.evaluate(this, localTime)
        return if (expression == null) v else Expression.evaluateOr(expression, v, localTime.toDouble(), this)
    }

    fun withKeyframe(k: Keyframe): Animatable =
        copy(keyframes = (keyframes.filterNot { it.time == k.time } + k).sortedBy { it.time })

    fun withoutKeyframeAt(t: Time): Animatable = copy(keyframes = keyframes.filterNot { it.time == t })

    fun set(v: Double, at: Time?, autoKey: Boolean): Animatable =
        if (autoKey && at != null) withKeyframe(Keyframe(at, v, keyframes.firstOrNull { it.time == at }?.interpolation ?: Interpolation.BEZIER))
        else if (keyframes.isEmpty()) copy(base = v)
        else copy(keyframes = keyframes.map { it.copy(value = it.value + (v - base)) }, base = v)

    fun shifted(delta: Time) = if (keyframes.isEmpty()) this else copy(keyframes = keyframes.map { it.copy(time = it.time + delta) })

    companion object {
        fun of(v: Double) = Animatable(v)
    }
}

@Serializable enum class FitMode { FIT, FILL, STRETCH }

@Serializable
data class Transform(
    /** Position of the anchor in output space: 0 = centre, ±1 = half frame width/height. */
    val positionX: Animatable = Animatable.of(0.0),
    val positionY: Animatable = Animatable.of(0.0),
    val positionZ: Animatable = Animatable.of(0.0),   // 2.5D depth (px-equivalent units of output height)
    val scale: Animatable = Animatable.of(1.0),
    val scaleX: Animatable = Animatable.of(1.0),      // multiplied with scale
    val scaleY: Animatable = Animatable.of(1.0),
    val rotation: Animatable = Animatable.of(0.0),    // degrees around Z
    val rotationX: Animatable = Animatable.of(0.0),   // 2.5D tilt
    val rotationY: Animatable = Animatable.of(0.0),
    val skewX: Animatable = Animatable.of(0.0),       // degrees
    val opacity: Animatable = Animatable.of(1.0),
    val anchorX: Double = 0.0,                        // layer-local, 0 = centre, ±1 = edge
    val anchorY: Double = 0.0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val fit: FitMode = FitMode.FIT,
    /** Crop fractions of the source (0..0.5 each side). */
    val cropLeft: Animatable = Animatable.of(0.0),
    val cropTop: Animatable = Animatable.of(0.0),
    val cropRight: Animatable = Animatable.of(0.0),
    val cropBottom: Animatable = Animatable.of(0.0),
    /** Corner pin offsets (TL, TR, BR, BL) in output-normalised units, applied after the affine transform. */
    val cornerPin: List<Animatable>? = null,
    /** Motion blur: shutter angle in degrees (0 = off) and sample count. */
    val motionBlurShutter: Double = 0.0,
    val motionBlurSamples: Int = 8,
)

/** Primary + secondary colour correction (not animated except via effects). */
@Serializable
data class ColorAdjust(
    val exposure: Double = 0.0,     // stops, -4..4
    val contrast: Double = 0.0,     // -1..1
    val highlights: Double = 0.0,   // -1..1
    val shadows: Double = 0.0,
    val whites: Double = 0.0,
    val blacks: Double = 0.0,
    val saturation: Double = 0.0,   // -1..1
    val vibrance: Double = 0.0,
    val temperature: Double = 0.0,  // -1..1 (warm > 0)
    val tint: Double = 0.0,         // -1..1 (magenta > 0)
    val lift: List<Double> = listOf(0.0, 0.0, 0.0),
    val gamma: List<Double> = listOf(0.0, 0.0, 0.0),
    val gain: List<Double> = listOf(0.0, 0.0, 0.0),
    /** Curves as control points (x,y pairs flattened) in 0..1; empty = identity. */
    val curveMaster: List<Double> = emptyList(),
    val curveRed: List<Double> = emptyList(),
    val curveGreen: List<Double> = emptyList(),
    val curveBlue: List<Double> = emptyList(),
    /** HSL per 8 hue bands (red, orange, yellow, green, aqua, blue, purple, magenta): hue shift deg, sat, lum (-1..1). */
    val hslHue: List<Double> = List(8) { 0.0 },
    val hslSat: List<Double> = List(8) { 0.0 },
    val hslLum: List<Double> = List(8) { 0.0 },
    val lutAssetId: String? = null,
    val lutIntensity: Double = 1.0,
) {
    val isIdentity get() = this == ColorAdjust()
}

@Serializable
data class EffectInstance(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val params: Map<String, Animatable> = emptyMap(),
    val options: Map<String, String> = emptyMap(),
)

@Serializable enum class MaskShape { RECT, ELLIPSE, POLYGON, BEZIER }
@Serializable enum class MaskMode { ADD, SUBTRACT, INTERSECT }

/** Vertex of a mask path in layer-normalised coordinates (0..1), with bezier handles relative to the vertex. */
@Serializable
data class MaskVertex(val x: Double, val y: Double, val inX: Double = 0.0, val inY: Double = 0.0, val outX: Double = 0.0, val outY: Double = 0.0)

@Serializable
data class MaskPathKey(val time: Time, val vertices: List<MaskVertex>, val interpolation: Interpolation = Interpolation.BEZIER)

@Serializable
data class Mask(
    val id: String,
    val shape: MaskShape,
    val mode: MaskMode = MaskMode.ADD,
    /** For RECT/ELLIPSE: centre and size in layer-normalised coords. */
    val centerX: Animatable = Animatable.of(0.5),
    val centerY: Animatable = Animatable.of(0.5),
    val width: Animatable = Animatable.of(0.5),
    val height: Animatable = Animatable.of(0.5),
    val rotation: Animatable = Animatable.of(0.0),
    /** For POLYGON/BEZIER: static path, or animated path keys. */
    val vertices: List<MaskVertex> = emptyList(),
    val pathKeys: List<MaskPathKey> = emptyList(),
    val feather: Animatable = Animatable.of(0.0),     // fraction of layer min dimension
    val expansion: Animatable = Animatable.of(0.0),
    val opacity: Animatable = Animatable.of(1.0),
    val inverted: Boolean = false,
    /** Optional link to a motion-tracking result; vertices follow the tracked point. */
    val trackId: String? = null,
)

@Serializable enum class MatteType { ALPHA, ALPHA_INVERTED, LUMA, LUMA_INVERTED }

@Serializable
data class TrackMatte(val sourceClipId: String, val type: MatteType = MatteType.ALPHA, val hideSource: Boolean = true)

@Serializable enum class KeyView { COMPOSITE, MATTE, ORIGINAL }

@Serializable
data class ChromaKey(
    val enabled: Boolean = true,
    val keyColorArgb: Long = 0xFF00B140,
    val similarity: Double = 0.40,
    val smoothness: Double = 0.08,
    val spill: Double = 0.5,
    val chokeGrow: Double = 0.0,      // -1 choke .. +1 grow
    val despillColorArgb: Long = 0xFF808080,
    val shadowPreserve: Double = 0.0,
    val view: KeyView = KeyView.COMPOSITE,
    val lumaKey: Boolean = false,     // luma key instead of chroma
)

/** Result of AI segmentation stored as a mask sequence on disk (non-destructive). */
@Serializable
data class AiMatte(
    val enabled: Boolean = true,
    val jobId: String,
    val modelId: String,
    val modelVersion: String,
    val cacheDir: String,
    val edgeSoftness: Double = 0.15,
    val temporalSmoothing: Double = 0.5,
    val choke: Double = 0.0,
    val view: KeyView = KeyView.COMPOSITE,
    /** Manual brush corrections: strokes in layer-normalised coords with clip-local time ranges. */
    val brush: List<BrushStroke> = emptyList(),
    val contactShadow: Double = 0.0,
)

/** Object-removal patch frames (PNG with alpha, in source frame space) composited over the clip. */
@Serializable
data class FillPatch(val cacheDir: String, val enabled: Boolean = true, val jobId: String = "")

@Serializable
data class BrushStroke(val add: Boolean, val radius: Double, val points: List<Double>, val start: Time, val end: Time)

@Serializable
enum class TransitionKind(val family: String) {
    DISSOLVE("dissolve"), DIP_TO_BLACK("dip"), DIP_TO_WHITE("dip"), LUMA_FADE("dissolve"),
    WIPE_LEFT("wipe"), WIPE_RIGHT("wipe"), WIPE_UP("wipe"), WIPE_DOWN("wipe"),
    SLIDE_LEFT("slide"), SLIDE_RIGHT("slide"), PUSH_LEFT("push"), PUSH_UP("push"),
    WHIP_PAN("motion"), ZOOM_IN("zoom"), ZOOM_OUT("zoom"), MATCH_ZOOM("zoom"), SPIN("motion"),
    PERSPECTIVE("3d"), BLUR("blur"), LIGHT_LEAK("light"), FILM_BURN("light"), GLITCH("digital"),
    RGB_SPLIT("digital"), DISPLACEMENT("warp"), SHAPE_CIRCLE("shape"), SHAPE_DIAMOND("shape"),
    CAMERA_SHAKE("motion"), PAGE_TURN("3d"), STRETCH("warp"), MOSAIC("digital"), SMOOTH_WARP("warp"),
    FLASH("light"), RADIAL("wipe"), SPLIT("wipe"), SEAMLESS_LOOP("zoom"), BEAT_CUT("motion"),
    CUSTOM_MASK("shape");
}

@Serializable enum class TransitionAlign { CENTER, BEFORE_CUT, AFTER_CUT }

/** Transition at the *start* of the owning clip (cut with the previous clip on the same track). */
@Serializable
data class Transition(
    val kind: TransitionKind,
    val duration: Time,
    val align: TransitionAlign = TransitionAlign.CENTER,
    val intensity: Double = 1.0,
    val direction: Double = 0.0,       // degrees, for directional families
    val easing: List<Double> = listOf(0.42, 0.0, 0.58, 1.0),
    val motionBlur: Boolean = true,
    /** For CUSTOM_MASK: an image asset used as a luma gradient. */
    val maskAssetId: String? = null,
    val linkAudio: Boolean = true,
)

@Serializable enum class TextUnit { GRAPHEME, WORD, LINE, ALL }

@Serializable
data class TextAnimation(
    val presetIn: String? = null,
    val presetOut: String? = null,
    val presetLoop: String? = null,
    val unit: TextUnit = TextUnit.GRAPHEME,
    val durationIn: Time = Rational.of(1, 2),
    val durationOut: Time = Rational.of(1, 2),
    val stagger: Time = Rational.of(1, 25),
    val easing: List<Double> = listOf(0.33, 0.0, 0.2, 1.0),
    val intensity: Double = 1.0,
    val reverseOrder: Boolean = false,
)

@Serializable
data class TextStyle(
    val text: String,
    val fontFamily: String = "default",   // "default" | "serif" | "mono" | asset id of an imported font
    val weight: Int = 700,
    val italic: Boolean = false,
    val sizeSp: Double = 48.0,             // size in output pixels at 1080 short side
    val colorArgb: Long = 0xFFF4F7FB,
    val gradientArgb: Long? = null,        // second colour for a vertical gradient fill
    val strokeArgb: Long = 0L,
    val strokeWidth: Double = 0.0,
    val shadowArgb: Long = 0L,
    val shadowBlur: Double = 0.0,
    val shadowDx: Double = 0.0,
    val shadowDy: Double = 0.0,
    val glowArgb: Long = 0L,
    val glowRadius: Double = 0.0,
    val backgroundArgb: Long = 0L,
    val backgroundPadding: Double = 12.0,
    val backgroundRadius: Double = 10.0,
    val alignment: String = "center",      // start | center | end
    val letterSpacing: Double = 0.0,       // em
    val lineHeight: Double = 1.2,
    val boxWidth: Double = 0.85,           // fraction of output width used for wrapping
    val rtl: Boolean? = null,              // null = detect from content (Unicode bidi)
    val animation: TextAnimation = TextAnimation(),
    /** Optional path (layer-normalised x,y pairs) for text-on-path. */
    val path: List<Double>? = null,
    /** Word indices (by whitespace split) to highlight with [highlightArgb]. */
    val highlightWords: List<Int> = emptyList(),
    val highlightArgb: Long = 0xFF6EE7C8,
)

@Serializable enum class ShapeType { RECT, ELLIPSE, STAR, POLYGON, LINE, PATH }

@Serializable
data class ShapeStyle(
    val type: ShapeType,
    val width: Animatable = Animatable.of(0.4),     // fraction of output width
    val height: Animatable = Animatable.of(0.4),
    val cornerRadius: Animatable = Animatable.of(0.0),
    val points: Int = 5,
    val innerRadius: Double = 0.5,
    val path: List<MaskVertex> = emptyList(),
    val pathKeys: List<MaskPathKey> = emptyList(),
    val fillArgb: Long = 0xFF9B7CFF,
    val fillGradientArgb: Long? = null,
    val fillGradientAngle: Double = 90.0,
    val strokeArgb: Long = 0L,
    val strokeWidth: Animatable = Animatable.of(0.0),
    val dash: List<Double> = emptyList(),
    val trimStart: Animatable = Animatable.of(0.0),
    val trimEnd: Animatable = Animatable.of(1.0),
    val trimOffset: Animatable = Animatable.of(0.0),
    val repeatCount: Int = 1,
    val repeatOffsetX: Double = 0.0,
    val repeatOffsetY: Double = 0.0,
    val repeatRotation: Double = 0.0,
    val repeatScale: Double = 1.0,
    val repeatOpacityEnd: Double = 1.0,
)

@Serializable enum class ClipKind { MEDIA, TEXT, SHAPE, SOLID, ADJUSTMENT, NULL_OBJECT, COMPOUND }

@Serializable enum class FrameBlend { NONE, BLEND, OPTICAL_FLOW }

@Serializable
data class AudioSettings(
    val volume: Animatable = Animatable.of(1.0),   // linear gain
    val pan: Animatable = Animatable.of(0.0),      // -1 left .. 1 right
    val fadeIn: Time = Rational.ZERO,
    val fadeOut: Time = Rational.ZERO,
    val pitchSemitones: Double = 0.0,
    val preservePitch: Boolean = true,
    val effects: List<EffectInstance> = emptyList(), // eq, compressor, gate, deesser, denoise, dereverb, limiter
    /** If set, plays this separated stem cache instead of the original audio (non-destructive). */
    val stems: StemMix? = null,
    val muted: Boolean = false,
    val linkedToVideo: Boolean = true,
)

@Serializable
data class StemMix(
    val jobId: String,
    val modelId: String,
    val cacheDir: String,
    /** Stem name → gain (0 mutes). Typical names: vocals, drums, bass, other, accompaniment. */
    val gains: Map<String, Double>,
    val solo: String? = null,
)

@Serializable
data class Clip(
    val id: String,
    val assetId: String?,               // null for generated clips (text, shape, solid, adjustment, null)
    val name: String = "",
    val timelineStart: Time,
    val sourceIn: Time,
    val sourceOut: Time,
    val speed: Rational = Rational.ONE,
    val kind: ClipKind = ClipKind.MEDIA,
    val transform: Transform = Transform(),
    val color: ColorAdjust = ColorAdjust(),
    val audio: AudioSettings = AudioSettings(),
    val effects: List<EffectInstance> = emptyList(),
    val masks: List<Mask> = emptyList(),
    val chroma: ChromaKey? = null,
    val aiMatte: AiMatte? = null,
    val fillPatch: FillPatch? = null,
    val matte: TrackMatte? = null,
    val transitionIn: Transition? = null,
    val text: TextStyle? = null,
    val shape: ShapeStyle? = null,
    val solidArgb: Long = 0xFF000000,
    val compoundSequenceId: String? = null,
    val parentClipId: String? = null,
    val reversed: Boolean = false,
    /** Time remapping: clip-local seconds → source seconds offset from sourceIn. Overrides [speed] when set. */
    val timeRemap: Animatable? = null,
    val remapDuration: Time? = null,
    val frameBlend: FrameBlend = FrameBlend.NONE,
    val stabilize: Stabilization? = null,
    val groupId: String? = null,
    val enabled: Boolean = true,
    val label: Long = 0,
) {
    init {
        require(sourceOut > sourceIn) { "Clip $id has non-positive source range" }
        require(!speed.isNegative && !speed.isZero) { "Clip $id speed must be > 0" }
        require(remapDuration == null || !remapDuration.isNegative) { "Clip $id remap duration negative" }
    }

    val sourceDuration: Time get() = sourceOut - sourceIn
    val duration: Time get() = remapDuration ?: (sourceDuration / speed)
    val timelineEnd: Time get() = timelineStart + duration

    /** Back-compat accessors. */
    val volume: Animatable get() = audio.volume

    fun contains(t: Time) = t >= timelineStart && t < timelineEnd

    /** Maps a timeline time to source media time (seconds, exact unless remapped). */
    fun sourceTimeAt(t: Time): Time {
        val localT = t - timelineStart
        timeRemap?.let { r ->
            val s = r.valueAt(localT)
            return sourceIn + Rational.fromDouble(s.coerceIn(0.0, sourceDuration.toDouble()), 1_000_000)
        }
        val local = localT * speed
        return if (reversed) sourceOut - local else sourceIn + local
    }

    /** Instantaneous playback speed at timeline time t (for audio/time-stretch and motion blur). */
    fun speedAt(t: Time): Double {
        val r = timeRemap ?: return if (reversed) -speed.toDouble() else speed.toDouble()
        val lt = (t - timelineStart).toDouble()
        val h = 1.0 / 240
        return (r.valueAt(Rational.fromDouble(lt + h, 1_000_000)) - r.valueAt(Rational.fromDouble(lt - h, 1_000_000))) / (2 * h)
    }

    fun localTime(t: Time): Time = t - timelineStart
}

@Serializable
data class Stabilization(val smoothness: Double = 0.5, val cropToFit: Boolean = true, val analysisId: String? = null)

@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val name: String = "",
    val clips: List<Clip> = emptyList(),
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val role: AudioRole = AudioRole.OTHER,
    val volume: Double = 1.0,
    val pan: Double = 0.0,
    /** Ducking: this track is lowered while any DIALOGUE track has signal. */
    val duckUnderDialogue: Boolean = false,
    val duckAmountDb: Double = -12.0,
    val heightDp: Int = 46,
) {
    val end: Time get() = clips.maxOfOrNull { it.timelineEnd } ?: Rational.ZERO
    fun sorted() = copy(clips = clips.sortedBy { it.timelineStart })
}

@Serializable
data class Marker(
    val id: String,
    val time: Time,
    val label: String,
    val colorArgb: Long = 0xFF9B7CFF,
    val comment: String = "",
    val duration: Time = Rational.ZERO,
    val kind: String = "note", // note | beat | chapter
)

@Serializable
data class CaptionWord(
    val text: String,
    val start: Time,
    val end: Time,
    val confidence: Double = 1.0,
    val speaker: Int = 0,
)

@Serializable
data class CaptionSegment(
    val id: String,
    val start: Time,
    val end: Time,
    val words: List<CaptionWord>,
    val speaker: Int = 0,
    /** Translation is kept separately and never overwrites the transcript. */
    val translation: String? = null,
) {
    val text: String get() = words.joinToString(" ") { it.text }
}

@Serializable
data class CaptionTrack(
    val id: String,
    val language: String,
    val segments: List<CaptionSegment>,
    val stylePreset: String = "subtitle",
    val style: TextStyle = TextStyle(""),
    val positionY: Double = 0.78,          // 0 top .. 1 bottom
    val maxWordsPerLine: Int = 7,
    val maxLines: Int = 2,
    val speakerColors: List<Long> = listOf(0xFFF4F7FB, 0xFF6EE7C8, 0xFFFFC857, 0xFF9B7CFF),
    val showTranslation: Boolean = false,
    val burnIn: Boolean = true,
    val modelId: String? = null,
)

@Serializable
data class Camera(
    val enabled: Boolean = false,
    val zoom: Animatable = Animatable.of(1.0),
    val positionX: Animatable = Animatable.of(0.0),
    val positionY: Animatable = Animatable.of(0.0),
    val positionZ: Animatable = Animatable.of(0.0),
    val rotationY: Animatable = Animatable.of(0.0),
    val rotationX: Animatable = Animatable.of(0.0),
    val focalLength: Double = 1.6,               // in output-height units: perspective strength
    val depthOfField: Boolean = false,
    val focusDistance: Animatable = Animatable.of(0.0),
    val aperture: Double = 0.5,
)

@Serializable
data class MasterAudio(
    val loudnessTargetLufs: Double? = -14.0,
    val limiterCeilingDb: Double = -1.0,
    val volume: Double = 1.0,
)

@Serializable
data class Sequence(
    val id: String,
    val name: String = "Main",
    val width: Int,
    val height: Int,
    val frameRate: FrameRate,
    val tracks: List<Track>,
    val markers: List<Marker> = emptyList(),
    val backgroundArgb: Long = 0xFF000000,
    val backgroundAssetId: String? = null,
    val audioSampleRate: Int = 48_000,
    val captions: List<CaptionTrack> = emptyList(),
    val camera: Camera = Camera(),
    val master: MasterAudio = MasterAudio(),
    val workAreaStart: Time? = null,
    val workAreaEnd: Time? = null,
) {
    val duration: Time get() = tracks.maxOfOrNull { it.end } ?: Rational.ZERO

    fun track(id: String) = tracks.firstOrNull { it.id == id }
    fun findClip(clipId: String): Pair<Track, Clip>? {
        for (t in tracks) for (c in t.clips) if (c.id == clipId) return t to c
        return null
    }
}

@Serializable
data class BrandKit(
    val colors: List<Long> = listOf(0xFF9B7CFF, 0xFF6EE7C8, 0xFFF4F7FB, 0xFF0B0D12),
    val fontFamily: String = "default",
    val logoAssetId: String? = null,
    val captionPreset: String = "active-word",
)

@Serializable
data class MotionTrack(
    val id: String,
    val clipId: String,
    /** Tracked centre (layer-normalised) per sample time, plus scale and rotation. */
    val times: List<Time>,
    val xs: List<Double>,
    val ys: List<Double>,
    val scales: List<Double> = emptyList(),
    val rotations: List<Double> = emptyList(),
    val confidence: List<Double> = emptyList(),
)

@Serializable
data class AiJob(
    val id: String,
    val type: String,            // asr | matting | stems | translate | scenes | silence | track | stabilize
    val modelId: String,
    val modelVersion: String,
    val params: Map<String, String> = emptyMap(),
    val createdAtMs: Long,
    val status: String = "done",
)

@Serializable
data class Project(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtMs: Long = 0,
    val modifiedAtMs: Long = 0,
    val assets: List<Asset> = emptyList(),
    val sequence: Sequence,
    /** Nested compositions referenced by COMPOUND clips. */
    val compositions: List<Sequence> = emptyList(),
    val brandKit: BrandKit = BrandKit(),
    val tracking: List<MotionTrack> = emptyList(),
    val aiJobs: List<AiJob> = emptyList(),
    val templateId: String? = null,
) {
    fun asset(id: String?) = assets.firstOrNull { it.id == id }
    fun composition(id: String?) = compositions.firstOrNull { it.id == id }

    companion object {
        fun create(id: String, name: String, nowMs: Long, width: Int = 1080, height: Int = 1920, rate: FrameRate = FrameRate.FPS_30) =
            Project(
                id = id, name = name, createdAtMs = nowMs, modifiedAtMs = nowMs, assets = emptyList(),
                sequence = Sequence(
                    id = "seq-main", name = "Main", width = width, height = height, frameRate = rate,
                    tracks = listOf(
                        Track("v1", TrackKind.VIDEO, "V1"),
                        Track("o1", TrackKind.OVERLAY, "V2"),
                        Track("o2", TrackKind.OVERLAY, "V3"),
                        Track("t1", TrackKind.TEXT, "T1"),
                        Track("a1", TrackKind.AUDIO, "A1", role = AudioRole.MUSIC, duckUnderDialogue = true),
                        Track("a2", TrackKind.AUDIO, "A2", role = AudioRole.DIALOGUE),
                    ),
                ),
            )
    }
}
