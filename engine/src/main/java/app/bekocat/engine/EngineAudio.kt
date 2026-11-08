package app.bekocat.engine

import android.content.Context
import app.bekocat.core.audio.ArrayPcmSource
import app.bekocat.core.audio.AudioMixer
import app.bekocat.core.audio.PcmSource
import app.bekocat.core.timeline.Clip
import app.bekocat.core.timeline.ClipKind
import app.bekocat.core.timeline.Project
import app.bekocat.engine.audio.AudioConform
import app.bekocat.engine.audio.FilePcmSource
import app.bekocat.engine.audio.StemPcmSource
import java.io.File

/** Maps clips to conformed PCM: original media, separated-stem mixes, or nested-composition mixes. */
class EngineAudio(context: Context, private val sampleRate: Int = 48_000) {
    val conform = AudioConform(context, sampleRate)
    private val sources = HashMap<String, PcmSource?>()

    fun sourceFor(project: Project, clip: Clip): PcmSource? {
        clip.audio.stems?.let { sm ->
            val list = sm.gains.mapNotNull { (name, g) ->
                val f = File(sm.cacheDir, "$name.f32")
                if (!f.exists()) null else FilePcmSource(f) to (if (sm.solo != null) (if (sm.solo == name) 1f else 0f) else g.toFloat())
            }
            if (list.isNotEmpty()) return StemPcmSource(list)
        }
        if (clip.kind == ClipKind.COMPOUND) {
            val comp = project.composition(clip.compoundSequenceId) ?: return null
            val nested = project.copy(sequence = comp)
            val mixer = AudioMixer(nested, { c -> sourceFor(nested, c) }, sampleRate)
            return ArrayPcmSource(mixer.mix(0.0, comp.duration.toDouble(), applyMaster = false))
        }
        val asset = project.asset(clip.assetId) ?: return null
        if (!asset.hasAudio) return null
        return synchronized(sources) { sources.getOrPut(asset.uri) { runCatching { conform.source(asset.uri) }.getOrNull() } }
    }

    fun mixer(project: Project) = AudioMixer(project, { c -> sourceFor(project, c) }, sampleRate)
}
