package app.bekocat.core.timeline

class EditException(message: String) : IllegalStateException(message)

/** Generates unique IDs; injectable so tests and AI plans are deterministic. */
fun interface IdGenerator { fun next(prefix: String): String }

class CounterIds(private var n: Long = 0) : IdGenerator {
    override fun next(prefix: String) = "$prefix-${++n}"
}

/**
 * Pure, non-destructive timeline edit operations. Each returns a new [Sequence]
 * and never touches source media. Clips on one track never overlap; every op
 * validates that invariant before returning.
 */
class TimelineEditor(private val assets: Map<String, Asset>, private val ids: IdGenerator) {

    // ---------- helpers ----------
    private fun Sequence.replaceTrack(track: Track): Sequence =
        copy(tracks = tracks.map { if (it.id == track.id) track.sorted() else it })

    private fun Sequence.requireTrack(id: String): Track =
        track(id) ?: throw EditException("Track $id not found")

    private fun Sequence.requireClip(id: String): Pair<Track, Clip> =
        findClip(id) ?: throw EditException("Clip $id not found")

    private fun Track.requireUnlocked(): Track { if (locked) throw EditException("Track $name is locked"); return this }

    private fun maxSourceOut(c: Clip): Time? = c.assetId?.let { assets[it]?.duration }

    private fun validate(track: Track): Track {
        val s = track.clips.sortedBy { it.timelineStart }
        for (c in s) if (c.timelineStart.isNegative) throw EditException("Clip ${c.id} starts before 0")
        for (i in 1 until s.size) {
            if (s[i].timelineStart < s[i - 1].timelineEnd) throw EditException("Clips ${s[i - 1].id} and ${s[i].id} overlap")
        }
        return track.copy(clips = s)
    }

    private fun shiftAfter(track: Track, from: Time, delta: Time, exceptId: String? = null): Track =
        track.copy(clips = track.clips.map {
            if (it.id != exceptId && it.timelineStart >= from) it.copy(timelineStart = it.timelineStart + delta) else it
        })

    // ---------- insertion ----------
    fun append(seq: Sequence, trackId: String, clip: Clip): Sequence {
        val t = seq.requireTrack(trackId).requireUnlocked()
        return seq.replaceTrack(validate(t.copy(clips = t.clips + clip.copy(timelineStart = t.end))))
    }

    /** Insert edit: splits a clip under [at] if needed, then ripples later clips right. */
    fun insert(seq: Sequence, trackId: String, clip: Clip, at: Time): Sequence {
        var s = seq
        val under = s.requireTrack(trackId).clips.firstOrNull { it.contains(at) && it.timelineStart != at }
        if (under != null) s = split(s, under.id, at)
        val t = s.requireTrack(trackId).requireUnlocked()
        val placed = clip.copy(timelineStart = at)
        val shifted = shiftAfter(t, at, placed.duration)
        return s.replaceTrack(validate(shifted.copy(clips = shifted.clips + placed)))
    }

    /** Overwrite edit: clears [at, at+duration) on the track then places the clip. */
    fun overwrite(seq: Sequence, trackId: String, clip: Clip, at: Time): Sequence {
        val placed = clip.copy(timelineStart = at)
        val cleared = clearRange(seq, trackId, at, placed.timelineEnd)
        val t = cleared.requireTrack(trackId)
        return cleared.replaceTrack(validate(t.copy(clips = t.clips + placed)))
    }

    /** Removes everything in [from, to) on a track without rippling (lift). */
    fun clearRange(seq: Sequence, trackId: String, from: Time, to: Time): Sequence {
        var s = seq
        s.requireTrack(trackId).clips.firstOrNull { it.contains(from) && it.timelineStart != from }?.let { s = split(s, it.id, from) }
        s.requireTrack(trackId).clips.firstOrNull { it.contains(to) && it.timelineStart != to }?.let { s = split(s, it.id, to) }
        val t = s.requireTrack(trackId).requireUnlocked()
        return s.replaceTrack(t.copy(clips = t.clips.filterNot { it.timelineStart >= from && it.timelineEnd <= to }))
    }

    // ---------- cutting ----------
    fun split(seq: Sequence, clipId: String, at: Time): Sequence {
        val (t, c) = seq.requireClip(clipId)
        t.requireUnlocked()
        if (at <= c.timelineStart || at >= c.timelineEnd) throw EditException("Split point outside clip")
        val leftDur = at - c.timelineStart
        val left: Clip
        val right: Clip
        if (c.timeRemap != null) {
            left = c.copy(remapDuration = leftDur, audio = c.audio.copy(fadeOut = Rational.ZERO))
            right = c.shiftedLocal(-leftDur).copy(id = ids.next("clip"), timelineStart = at, remapDuration = c.duration - leftDur,
                transitionIn = null, audio = c.shiftedLocal(-leftDur).audio.copy(fadeIn = Rational.ZERO))
        } else {
            val cutSource = if (c.reversed) c.sourceOut - leftDur * c.speed else c.sourceTimeAt(at)
            val l0 = if (!c.reversed) c.copy(sourceOut = cutSource) else c.copy(sourceIn = cutSource)
            left = l0.copy(audio = l0.audio.copy(fadeOut = Rational.ZERO))
            val r0 = c.shiftedLocal(-leftDur)
            val r1 = if (!c.reversed) r0.copy(sourceIn = cutSource) else r0.copy(sourceOut = cutSource)
            right = r1.copy(id = ids.next("clip"), timelineStart = at, transitionIn = null, audio = r1.audio.copy(fadeIn = Rational.ZERO))
        }
        return seq.replaceTrack(validate(t.copy(clips = t.clips.filterNot { it.id == clipId } + left + right)))
    }

    /** Splits every unlocked clip that crosses [at] (blade-all). */
    fun splitAll(seq: Sequence, at: Time): Sequence {
        var s = seq
        for (t in seq.tracks) if (!t.locked) for (c in t.clips) if (c.contains(at) && c.timelineStart != at) s = split(s, c.id, at)
        return s
    }

    fun delete(seq: Sequence, clipIds: Set<String>, ripple: Boolean): Sequence {
        var s = seq
        // Delete right-most first so ripple shifts don't affect pending positions.
        val targets = clipIds.map { s.requireClip(it) }.sortedByDescending { it.second.timelineStart }
        for ((t0, c) in targets) {
            val t = s.requireTrack(t0.id).requireUnlocked()
            var nt = t.copy(clips = t.clips.filterNot { it.id == c.id })
            if (ripple) nt = shiftAfter(nt, c.timelineEnd, -c.duration)
            s = s.replaceTrack(validate(nt))
        }
        return s
    }

    // ---------- trimming ----------
    /** Moves the clip's in-point to timeline time [newStart]. Ripple keeps the clip's end anchored to the left. */
    fun trimStart(seq: Sequence, clipId: String, newStart: Time, ripple: Boolean): Sequence {
        val (t, c) = seq.requireClip(clipId)
        t.requireUnlocked()
        if (newStart >= c.timelineEnd) throw EditException("Trim would make clip empty")
        val delta = newStart - c.timelineStart
        val trimmed = if (c.timeRemap != null) {
            c.shiftedLocal(-delta).copy(timelineStart = newStart, remapDuration = c.duration - delta)
        } else {
            val newIn = c.sourceIn + delta * c.speed
            if (newIn.isNegative) throw EditException("Not enough source media before in-point")
            if (c.reversed) c.shiftedLocal(-delta).copy(sourceOut = c.sourceOut - delta * c.speed, timelineStart = newStart)
            else c.shiftedLocal(-delta).copy(sourceIn = newIn, timelineStart = newStart)
        }
        var nt = t.copy(clips = t.clips.map { if (it.id == clipId) trimmed else it })
        if (ripple) {
            // Ripple trim: close the gap by moving this clip back and everything after it.
            nt = nt.copy(clips = nt.clips.map { if (it.id == clipId) it.copy(timelineStart = c.timelineStart) else it })
            nt = shiftAfter(nt, c.timelineEnd, -delta, exceptId = clipId)
        }
        return seq.replaceTrack(validate(nt))
    }

    fun trimEnd(seq: Sequence, clipId: String, newEnd: Time, ripple: Boolean): Sequence {
        val (t, c) = seq.requireClip(clipId)
        t.requireUnlocked()
        if (newEnd <= c.timelineStart) throw EditException("Trim would make clip empty")
        val delta = newEnd - c.timelineEnd
        val trimmed = if (c.timeRemap != null) c.copy(remapDuration = c.duration + delta) else {
            val newOut = c.sourceOut + delta * c.speed
            maxSourceOut(c)?.let { if (newOut > it) throw EditException("Not enough source media after out-point") }
            c.copy(sourceOut = newOut)
        }
        var nt = t.copy(clips = t.clips.map { if (it.id == clipId) trimmed else it })
        if (ripple) nt = shiftAfter(nt, c.timelineEnd, delta, exceptId = clipId)
        return seq.replaceTrack(validate(nt))
    }

    // ---------- slip / slide / roll ----------
    /** Slip: changes which source frames are shown, keeping timeline position and duration. */
    fun slip(seq: Sequence, clipId: String, sourceDelta: Time): Sequence {
        val (t, c) = seq.requireClip(clipId)
        t.requireUnlocked()
        val ni = c.sourceIn + sourceDelta
        val no = c.sourceOut + sourceDelta
        if (ni.isNegative) throw EditException("Slip past media start")
        maxSourceOut(c)?.let { if (no > it) throw EditException("Slip past media end") }
        return seq.replaceTrack(validate(t.copy(clips = t.clips.map { if (it.id == clipId) c.copy(sourceIn = ni, sourceOut = no) else it })))
    }

    /** Slide: moves a clip between its neighbours, trimming the previous clip's end and the next clip's start. */
    fun slide(seq: Sequence, clipId: String, delta: Time): Sequence {
        val (t, c) = seq.requireClip(clipId)
        t.requireUnlocked()
        val sorted = t.clips.sortedBy { it.timelineStart }
        val i = sorted.indexOfFirst { it.id == clipId }
        val prev = sorted.getOrNull(i - 1)?.takeIf { it.timelineEnd == c.timelineStart }
        val next = sorted.getOrNull(i + 1)?.takeIf { it.timelineStart == c.timelineEnd }
        var s = seq
        if (delta.isNegative) {
            prev?.let { s = trimEnd(s, it.id, it.timelineEnd + delta, ripple = false) }
            s = moveClipUnchecked(s, clipId, c.timelineStart + delta)
            next?.let { s = trimStart(s, it.id, it.timelineStart + delta, ripple = false) }
        } else {
            next?.let { s = trimStart(s, it.id, it.timelineStart + delta, ripple = false) }
            s = moveClipUnchecked(s, clipId, c.timelineStart + delta)
            prev?.let { s = trimEnd(s, it.id, it.timelineEnd + delta, ripple = false) }
        }
        return s.replaceTrack(validate(s.requireTrack(t.id)))
    }

    /** Roll: moves the cut between two adjacent clips; total duration unchanged. */
    fun roll(seq: Sequence, leftClipId: String, delta: Time): Sequence {
        val (t, left) = seq.requireClip(leftClipId)
        val right = t.clips.firstOrNull { it.timelineStart == left.timelineEnd }
            ?: throw EditException("No adjacent clip to roll with")
        var s = seq
        if (delta.isNegative) {
            s = trimEnd(s, left.id, left.timelineEnd + delta, ripple = false)
            s = trimStart(s, right.id, right.timelineStart + delta, ripple = false)
        } else {
            s = trimStart(s, right.id, right.timelineStart + delta, ripple = false)
            s = trimEnd(s, left.id, left.timelineEnd + delta, ripple = false)
        }
        return s
    }

    private fun moveClipUnchecked(seq: Sequence, clipId: String, newStart: Time): Sequence {
        val (t, _) = seq.requireClip(clipId)
        return seq.copy(tracks = seq.tracks.map { tr ->
            if (tr.id != t.id) tr else tr.copy(clips = tr.clips.map { if (it.id == clipId) it.copy(timelineStart = newStart) else it })
        })
    }

    // ---------- moving / duplicating ----------
    fun move(seq: Sequence, clipId: String, newStart: Time, toTrackId: String? = null): Sequence {
        val (from, c) = seq.requireClip(clipId)
        from.requireUnlocked()
        val targetId = toTrackId ?: from.id
        var s = seq.replaceTrack(from.copy(clips = from.clips.filterNot { it.id == clipId }))
        val target = s.requireTrack(targetId).requireUnlocked()
        s = s.replaceTrack(validate(target.copy(clips = target.clips + c.copy(timelineStart = newStart))))
        return s
    }

    fun duplicate(seq: Sequence, clipId: String): Sequence {
        val (t, c) = seq.requireClip(clipId)
        return insert(seq, t.id, c.copy(id = ids.next("clip")), c.timelineEnd)
    }

    // ---------- speed ----------
    /** Constant speed change; ripple keeps following clips attached. */
    fun setSpeed(seq: Sequence, clipId: String, speed: Rational, ripple: Boolean = true): Sequence {
        val (t, c) = seq.requireClip(clipId)
        t.requireUnlocked()
        if (speed.isZero || speed.isNegative) throw EditException("Speed must be positive")
        val updated = c.copy(speed = speed)
        var nt = t.copy(clips = t.clips.map { if (it.id == clipId) updated else it })
        if (ripple) nt = shiftAfter(nt, c.timelineEnd, updated.duration - c.duration, exceptId = clipId)
        return seq.replaceTrack(validate(nt))
    }

    // ---------- generic property update ----------
    fun updateClip(seq: Sequence, clipId: String, f: (Clip) -> Clip): Sequence {
        val (t, c) = seq.requireClip(clipId)
        t.requireUnlocked()
        val n = f(c)
        if (n.id != c.id) throw EditException("Clip id is immutable")
        return seq.replaceTrack(validate(t.copy(clips = t.clips.map { if (it.id == clipId) n else it })))
    }

    fun updateTrack(seq: Sequence, trackId: String, f: (Track) -> Track): Sequence {
        val t = seq.requireTrack(trackId)
        return seq.replaceTrack(validate(f(t)))
    }


    // ---------- replace / attributes ----------
    /** Replace a clip's media keeping timing; optionally keep its effects, colour, transform and audio settings. */
    fun replaceMedia(seq: Sequence, clipId: String, newAsset: Asset, keepAttributes: Boolean): Sequence = updateClip(seq, clipId) { c ->
        val avail = newAsset.duration
        val srcLen = c.sourceDuration
        val out = if (avail == null) srcLen else min(srcLen, avail)
        val base = c.copy(assetId = newAsset.id, name = newAsset.displayName, sourceIn = Rational.ZERO, sourceOut = out, kind = ClipKind.MEDIA,
            remapDuration = if (avail != null && out < srcLen && c.timeRemap == null) null else c.remapDuration)
        if (keepAttributes) base else base.copy(transform = Transform(), color = ColorAdjust(), effects = emptyList(), masks = emptyList(), chroma = null)
    }

    /** Copies visual/audio attributes from [fromId] to every id in [toIds] (Paste Attributes). */
    fun pasteAttributes(seq: Sequence, fromId: String, toIds: Collection<String>, transform: Boolean = true, color: Boolean = true,
                        effects: Boolean = true, audio: Boolean = true, masks: Boolean = false): Sequence {
        val (_, src) = seq.requireClip(fromId)
        return toIds.fold(seq) { s, id ->
            updateClip(s, id) { c ->
                c.copy(
                    transform = if (transform) src.transform else c.transform,
                    color = if (color) src.color else c.color,
                    effects = if (effects) src.effects.map { it.copy(id = ids.next("fx")) } else c.effects,
                    audio = if (audio) src.audio.copy(stems = c.audio.stems) else c.audio,
                    masks = if (masks) src.masks.map { it.copy(id = ids.next("mask")) } else c.masks,
                    chroma = if (effects) src.chroma else c.chroma,
                )
            }
        }
    }

    /** Copies clips (with relative timing) to a track at [at]; returns the new sequence. */
    fun paste(seq: Sequence, clips: List<Clip>, trackId: String, at: Time, insertMode: Boolean): Sequence {
        if (clips.isEmpty()) return seq
        val origin = clips.minOf { it.timelineStart }
        var s = seq
        for (c in clips.sortedBy { it.timelineStart }) {
            val n = c.copy(id = ids.next("clip"), groupId = null, parentClipId = null)
            val pos = at + (c.timelineStart - origin)
            s = if (insertMode) insert(s, trackId, n, pos) else overwrite(s, trackId, n, pos)
        }
        return s
    }

    // ---------- groups / multi-select ----------
    fun group(seq: Sequence, clipIds: Collection<String>): Sequence {
        if (clipIds.size < 2) throw EditException("Select at least two clips to group")
        val gid = ids.next("group")
        return clipIds.fold(seq) { s, id -> updateClip(s, id) { it.copy(groupId = gid) } }
    }

    fun ungroup(seq: Sequence, clipIds: Collection<String>): Sequence =
        clipIds.fold(seq) { s, id -> updateClip(s, id) { it.copy(groupId = null) } }

    /** Expands a selection to whole groups. */
    fun expandToGroups(seq: Sequence, clipIds: Set<String>): Set<String> {
        val groups = clipIds.mapNotNull { seq.findClip(it)?.second?.groupId }.toSet()
        if (groups.isEmpty()) return clipIds
        return clipIds + seq.tracks.flatMap { it.clips }.filter { it.groupId in groups }.map { it.id }
    }

    /** Moves several clips by the same delta (validated all-or-nothing). */
    fun moveMany(seq: Sequence, clipIds: Collection<String>, delta: Time): Sequence {
        val idsSet = expandToGroups(seq, clipIds.toSet())
        val tracks = seq.tracks.map { t ->
            if (t.clips.none { it.id in idsSet }) t else {
                t.requireUnlocked()
                validate(t.copy(clips = t.clips.map { if (it.id in idsSet) it.copy(timelineStart = it.timelineStart + delta) else it }))
            }
        }
        return seq.copy(tracks = tracks)
    }

    // ---------- time ----------
    /** Freeze frame: splits at [at] and inserts a still hold of [holdDuration] showing that frame. */
    fun freezeFrame(seq: Sequence, clipId: String, at: Time, holdDuration: Time): Sequence {
        val (t, c) = seq.requireClip(clipId)
        val src = c.sourceTimeAt(at) - c.sourceIn
        val hold = c.copy(
            id = ids.next("clip"), name = c.name + " (freeze)", timelineStart = at, transitionIn = null,
            timeRemap = Animatable.of(src.toDouble()), remapDuration = holdDuration,
            audio = c.audio.copy(muted = true),
        )
        var s = if (at > c.timelineStart && at < c.timelineEnd) split(seq, clipId, at) else seq
        s = insert(s, t.id, hold, at)
        return s
    }

    fun setReversed(seq: Sequence, clipId: String, reversed: Boolean): Sequence = updateClip(seq, clipId) { it.copy(reversed = reversed) }

    /**
     * Speed ramp from (time, speed) control points in clip-local seconds. Integrates the
     * speed curve into a time-remap curve so source frames stay continuous, and sets the
     * new clip duration so the whole source range is used.
     */
    fun speedRamp(seq: Sequence, clipId: String, points: List<Pair<Double, Double>>, ripple: Boolean = true): Sequence {
        require(points.size >= 2) { "Need at least two speed points" }
        val (t, c) = seq.requireClip(clipId)
        t.requireUnlocked()
        val srcLen = c.sourceDuration.toDouble()
        val pts = points.sortedBy { it.first }.map { it.first.coerceAtLeast(0.0) to it.second.coerceIn(0.05, 20.0) }
        // Integrate speed over output time until the source is consumed.
        val dt = 1.0 / 240
        var out = 0.0; var src = 0.0
        val keys = ArrayList<Keyframe>()
        fun speedAt(x: Double): Double {
            if (x <= pts.first().first) return pts.first().second
            if (x >= pts.last().first) return pts.last().second
            val i = pts.indexOfLast { it.first <= x }
            val (x0, s0) = pts[i]; val (x1, s1) = pts[i + 1]
            val u = (x - x0) / (x1 - x0)
            val e = u * u * (3 - 2 * u) // smooth ramp between points
            return s0 + (s1 - s0) * e
        }
        var nextKey = 0.0
        while (src < srcLen && out < 3600.0) {
            if (out >= nextKey) { keys += Keyframe(Rational.fromDouble(out, 1000), src); nextKey += 1.0 / 12 }
            src += speedAt(out) * dt
            out += dt
        }
        keys += Keyframe(Rational.fromDouble(out, 1000), srcLen)
        val newDur = Rational.fromDouble(out, 1000)
        val updated = c.copy(timeRemap = Animatable(0.0, keys.distinctBy { it.time }), remapDuration = newDur, speed = Rational.ONE, reversed = false)
        var nt = t.copy(clips = t.clips.map { if (it.id == clipId) updated else it })
        if (ripple) nt = shiftAfter(nt, c.timelineEnd, updated.duration - c.duration, exceptId = clipId)
        return seq.replaceTrack(validate(nt))
    }

    fun clearTimeRemap(seq: Sequence, clipId: String): Sequence = updateClip(seq, clipId) { it.copy(timeRemap = null, remapDuration = null) }

    // ---------- audio ----------
    /** Detaches a video clip's audio to an audio track as its own clip; the video clip is muted. */
    fun detachAudio(seq: Sequence, clipId: String, audioTrackId: String): Sequence {
        val (_, c) = seq.requireClip(clipId)
        val a = c.copy(id = ids.next("clip"), name = c.name + " (audio)", transform = Transform(), effects = emptyList(), masks = emptyList(),
            chroma = null, aiMatte = null, transitionIn = null, audio = c.audio.copy(muted = false, linkedToVideo = false))
        var s = updateClip(seq, clipId) { it.copy(audio = it.audio.copy(muted = true)) }
        s = overwrite(s, audioTrackId, a, c.timelineStart)
        return s
    }

    // ---------- tracks ----------
    fun addTrack(seq: Sequence, kind: TrackKind, name: String, index: Int? = null): Sequence {
        val t = Track(ids.next("track"), kind, name, role = if (kind == TrackKind.AUDIO) AudioRole.OTHER else AudioRole.OTHER)
        val list = seq.tracks.toMutableList()
        list.add((index ?: list.size).coerceIn(0, list.size), t)
        return seq.copy(tracks = list)
    }

    fun moveTrack(seq: Sequence, trackId: String, newIndex: Int): Sequence {
        val list = seq.tracks.toMutableList()
        val i = list.indexOfFirst { it.id == trackId }
        if (i < 0) throw EditException("Track $trackId not found")
        val t = list.removeAt(i)
        list.add(newIndex.coerceIn(0, list.size), t)
        return seq.copy(tracks = list)
    }

    fun removeTrack(seq: Sequence, trackId: String): Sequence {
        val t = seq.requireTrack(trackId)
        if (t.clips.isNotEmpty()) throw EditException("Track is not empty")
        return seq.copy(tracks = seq.tracks.filterNot { it.id == trackId })
    }

    // ---------- markers ----------
    fun removeMarker(seq: Sequence, markerId: String): Sequence = seq.copy(markers = seq.markers.filterNot { it.id == markerId })
    fun updateMarker(seq: Sequence, markerId: String, f: (Marker) -> Marker): Sequence =
        seq.copy(markers = seq.markers.map { if (it.id == markerId) f(it) else it }.sortedBy { it.time })

    fun addBeatMarkers(seq: Sequence, beats: List<Time>): Sequence =
        seq.copy(markers = (seq.markers.filterNot { it.kind == "beat" } + beats.map { Marker(ids.next("beat"), it, "", 0xFF6EE7C8, kind = "beat") }).sortedBy { it.time })

    /** Snap target times: clip edges on all tracks, markers and the playhead. */
    fun snapTargets(seq: Sequence, playhead: Time?, exceptClipIds: Set<String> = emptySet()): List<Time> {
        val out = ArrayList<Time>()
        seq.tracks.flatMap { it.clips }.filter { it.id !in exceptClipIds }.forEach { out += it.timelineStart; out += it.timelineEnd }
        seq.markers.forEach { out += it.time }
        playhead?.let { out += it }
        out += Rational.ZERO
        return out.distinct().sorted()
    }

    fun snap(seq: Sequence, t: Time, tolerance: Time, playhead: Time?, exceptClipIds: Set<String> = emptySet()): Time {
        val best = snapTargets(seq, playhead, exceptClipIds).minByOrNull { (it - t).let { d -> if (d.isNegative) -d else d } } ?: return t
        val d = best - t
        return if ((if (d.isNegative) -d else d) <= tolerance) best else seq.frameRate.snap(t)
    }

    // ---------- nesting ----------
    /**
     * Precompose: moves the given clips into a new nested composition and replaces them with a single
     * COMPOUND clip on the top-most source track. Returns (newMainSequence, newComposition).
     */
    fun nest(seq: Sequence, clipIds: Set<String>, name: String): Pair<Sequence, Sequence> {
        if (clipIds.isEmpty()) throw EditException("Nothing selected")
        val picked = seq.tracks.flatMap { t -> t.clips.filter { it.id in clipIds }.map { t to it } }
        val start = picked.minOf { it.second.timelineStart }
        val end = picked.maxOf { it.second.timelineEnd }
        val compId = ids.next("comp")
        val compTracks = seq.tracks.filter { t -> t.clips.any { it.id in clipIds } }.map { t ->
            t.copy(id = ids.next("track"), clips = t.clips.filter { it.id in clipIds }.map { it.copy(timelineStart = it.timelineStart - start) })
        }
        val comp = seq.copy(id = compId, name = name, tracks = compTracks, markers = emptyList(), captions = emptyList())
        val hostTrack = picked.first().first
        var s = seq.copy(tracks = seq.tracks.map { t -> t.copy(clips = t.clips.filterNot { it.id in clipIds }) })
        val compound = Clip(ids.next("clip"), null, name, start, Rational.ZERO, end - start, kind = ClipKind.COMPOUND, compoundSequenceId = compId)
        val ht = s.requireTrack(hostTrack.id)
        s = s.replaceTrack(validate(ht.copy(clips = ht.clips + compound)))
        return s to comp
    }

    fun addMarker(seq: Sequence, at: Time, label: String): Sequence =
        seq.copy(markers = (seq.markers + Marker(ids.next("marker"), at, label)).sortedBy { it.time })
}

fun Transform.shiftKeyframes(delta: Time): Transform = copy(
    positionX = positionX.shifted(delta), positionY = positionY.shifted(delta), positionZ = positionZ.shifted(delta),
    scale = scale.shifted(delta), scaleX = scaleX.shifted(delta), scaleY = scaleY.shifted(delta),
    rotation = rotation.shifted(delta), rotationX = rotationX.shifted(delta), rotationY = rotationY.shifted(delta),
    skewX = skewX.shifted(delta), opacity = opacity.shifted(delta),
    cropLeft = cropLeft.shifted(delta), cropTop = cropTop.shifted(delta), cropRight = cropRight.shifted(delta), cropBottom = cropBottom.shifted(delta),
    cornerPin = cornerPin?.map { it.shifted(delta) },
)

private fun Mask.shifted(d: Time) = copy(
    centerX = centerX.shifted(d), centerY = centerY.shifted(d), width = width.shifted(d), height = height.shifted(d),
    rotation = rotation.shifted(d), feather = feather.shifted(d), expansion = expansion.shifted(d), opacity = opacity.shifted(d),
    pathKeys = pathKeys.map { it.copy(time = it.time + d) },
)

private fun EffectInstance.shifted(d: Time) = copy(params = params.mapValues { it.value.shifted(d) })

/** Shifts every clip-local animation (keyframes, masks, effects, remap) by [d]; used by split and trim-in. */
fun Clip.shiftedLocal(d: Time): Clip = copy(
    transform = transform.shiftKeyframes(d),
    audio = audio.copy(volume = audio.volume.shifted(d), pan = audio.pan.shifted(d), effects = audio.effects.map { it.shifted(d) }),
    effects = effects.map { it.shifted(d) },
    masks = masks.map { it.shifted(d) },
    timeRemap = timeRemap?.shifted(d),
    shape = shape?.let { sh -> sh.copy(width = sh.width.shifted(d), height = sh.height.shifted(d), cornerRadius = sh.cornerRadius.shifted(d),
        strokeWidth = sh.strokeWidth.shifted(d), trimStart = sh.trimStart.shifted(d), trimEnd = sh.trimEnd.shifted(d), trimOffset = sh.trimOffset.shifted(d),
        pathKeys = sh.pathKeys.map { it.copy(time = it.time + d) }) },
)
