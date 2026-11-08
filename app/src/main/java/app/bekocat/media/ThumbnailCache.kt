package app.bekocat.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.bekocat.core.timeline.Asset
import app.bekocat.core.timeline.AssetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Filmstrip frames (one per second of source, small height) cached in memory.
 * Decoding is sequential per asset on an IO thread; the UI only reads results.
 */
class ThumbnailCache(private val context: Context) {

    private val cache = object : LruCache<String, List<ImageBitmap>>(32) {}
    private val lock = Mutex()

    fun peek(assetId: String): List<ImageBitmap>? = cache.get(assetId)

    suspend fun load(asset: Asset, heightPx: Int = 72): List<ImageBitmap> = lock.withLock {
        cache.get(asset.id)?.let { return it }
        val frames = withContext(Dispatchers.IO) {
            runCatching {
                if (asset.kind == AssetKind.IMAGE) {
                    context.contentResolver.openInputStream(Uri.parse(asset.uri))?.use {
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                        android.graphics.BitmapFactory.decodeStream(it, null, opts)
                    }?.let { listOf(scale(it, heightPx).asImageBitmap()) } ?: emptyList()
                } else if (asset.kind == AssetKind.AUDIO) emptyList()
                else {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, Uri.parse(asset.uri))
                        val sec = (asset.duration?.toDouble() ?: 0.0).toInt().coerceIn(1, 600)
                        val w = if (asset.height > 0) heightPx * asset.width / asset.height else heightPx * 16 / 9
                        (0 until sec).mapNotNull { s ->
                            r.getScaledFrameAtTime(s * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w.coerceAtLeast(8), heightPx)?.asImageBitmap()
                        }
                    } finally { r.release() }
                }
            }.getOrDefault(emptyList())
        }
        cache.put(asset.id, frames)
        frames
    }

    private fun scale(b: Bitmap, h: Int): Bitmap {
        val w = (b.width * h / b.height.coerceAtLeast(1)).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }
}
