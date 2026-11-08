package app.bekocat.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.bekocat.core.timeline.Asset
import app.bekocat.core.timeline.AssetKind
import app.bekocat.core.timeline.FrameRate
import app.bekocat.core.timeline.IdGenerator
import app.bekocat.core.timeline.Project
import app.bekocat.core.timeline.ProjectStore
import app.bekocat.core.timeline.Rational
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ProjectRepository(private val context: Context) {

    private val store = ProjectStore(File(context.filesDir, "projects"))

    val ids = IdGenerator { prefix -> "$prefix-${UUID.randomUUID().toString().take(8)}" }

    suspend fun list(): List<Project> = withContext(Dispatchers.IO) { store.list() }
    suspend fun load(id: String): Project = withContext(Dispatchers.IO) { store.load(id) }
    suspend fun save(p: Project) = withContext(Dispatchers.IO) { store.save(p) }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { store.delete(id) }

    suspend fun pendingJournal(id: String): List<String> = withContext(Dispatchers.IO) { store.pendingJournal(id) }
    fun journal(id: String, label: String) = store.appendJournal(id, label, System.currentTimeMillis())
    fun periodicBackup(p: Project) = store.periodicBackup(p, System.currentTimeMillis())
    fun backups(id: String) = store.backups(id)

    /** Writes a portable .bekocat package (project + media) to a user-chosen document. */
    fun exportPackage(p: Project, dest: Uri) {
        context.contentResolver.openOutputStream(dest)?.use { out ->
            app.bekocat.core.timeline.ProjectPackage.export(p, out) { a -> runCatching { context.contentResolver.openInputStream(Uri.parse(a.uri)) }.getOrNull() }
        } ?: throw java.io.IOException("Cannot write package")
    }

    /** Imports a .bekocat package as a new project (media extracted into app storage). */
    suspend fun importPackage(src: Uri): Project = withContext(Dispatchers.IO) {
        val id = newProjectId()
        val dir = File(store.dirOf(id), "media-root")
        val p = context.contentResolver.openInputStream(src)?.use { app.bekocat.core.timeline.ProjectPackage.import(it, dir, id) }
            ?: throw java.io.IOException("Cannot read package")
        store.save(p.copy(modifiedAtMs = System.currentTimeMillis()))
        p
    }

    suspend fun duplicate(p: Project): Project = withContext(Dispatchers.IO) {
        val n = p.copy(id = newProjectId(), name = p.name + " (2)", createdAtMs = System.currentTimeMillis(), modifiedAtMs = System.currentTimeMillis())
        store.save(n); n
    }

    fun newProjectId(): String = "p" + UUID.randomUUID().toString().replace("-", "").take(16)

    /** Reads metadata for a picked document and keeps read permission across restarts. */
    suspend fun probe(uri: Uri): Asset = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val mime = context.contentResolver.getType(uri) ?: ""
        var name = uri.lastPathSegment ?: "media"
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                size = c.getLong(1)
            }
        }
        val lower = name.lowercase()
        val kind = when {
            lower.endsWith(".ttf") || lower.endsWith(".otf") || mime.contains("font") -> AssetKind.FONT
            lower.endsWith(".cube") -> AssetKind.LUT
            mime.startsWith("image/") -> AssetKind.IMAGE
            mime.startsWith("audio/") -> AssetKind.AUDIO
            else -> AssetKind.VIDEO
        }
        var duration: Rational? = null
        var w = 0
        var h = 0
        var hasAudio = false
        var rate: FrameRate? = null
        if (kind == AssetKind.FONT || kind == AssetKind.LUT) {
            // Validate before accepting.
            if (kind == AssetKind.LUT) context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { app.bekocat.core.timeline.ColorMath.parseCube(it.readText()) }
            return@withContext Asset(ids.next("asset"), uri.toString(), kind, name, null, sizeBytes = size)
        }
        if (kind == AssetKind.IMAGE) {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            w = opts.outWidth; h = opts.outHeight
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                duration = Rational.millis(ms)
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { w = vh; h = vw } else { w = vw; h = vh }
                hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" || kind == AssetKind.AUDIO
                val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                if (frames != null && ms > 0) rate = nearestStandardRate(frames * 1000.0 / ms)
            } finally {
                r.release()
            }
            if (duration == null || duration!!.isZero) throw IllegalArgumentException("Unreadable media: $name")
        }
        Asset(
            id = ids.next("asset"), uri = uri.toString(), kind = kind, displayName = name, duration = duration,
            width = w, height = h, hasAudio = hasAudio, frameRate = rate, sizeBytes = size, contentHash = quickHash(uri, size),
        )
    }

    /** Is the asset still readable? Used to flag media that needs relinking. */
    fun isReachable(a: Asset): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(a.uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun nearestStandardRate(fps: Double): FrameRate =
        FrameRate.STANDARD.minByOrNull { kotlin.math.abs(it.fps - fps) }!!

    /** Hash of size + first 1 MiB: a cheap stable identity for relinking, not a security hash. */
    private fun quickHash(uri: Uri, size: Long): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(size.toString().toByteArray())
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < 1024 * 1024) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n); total += n
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }.getOrDefault("")
}
