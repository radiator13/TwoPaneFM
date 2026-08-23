package com.twopane.fm.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.twopane.fm.model.FileEntry
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Async image/video thumbnails with an in-memory LRU cache.
 * Decoding happens off the main thread on a tiny worker pool.
 */
object ThumbnailCache {
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024
    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            var total = 0L
            for (b in values) total += b.byteCount
            return total > MAX_CACHE_BYTES
        }
    }
    private val executor = Executors.newFixedThreadPool(2)

    fun get(path: String): Bitmap? = synchronized(cache) { cache[path] }

    fun loadAsync(path: String, targetPx: Int, onLoaded: () -> Unit) {
        executor.execute {
            val bmp = decode(path, targetPx)
            if (bmp != null) synchronized(cache) { cache[path] = bmp }
            android.os.Handler(Looper.getMainLooper()).post(onLoaded)
        }
    }

    private fun decode(path: String, targetPx: Int): Bitmap? = try {
        val f = File(path)
        when {
            f.extension.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm") -> videoFrame(path, targetPx)
            else -> {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                while (max(opts.outWidth, opts.outHeight) / sample > targetPx * 2) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: Exception) { null }

    private fun videoFrame(path: String, targetPx: Int): Bitmap? = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(path)
        val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        mmr.release()
        frame?.let { full ->
            val w = targetPx.coerceAtMost(full.width)
            val scale = w.toFloat() / full.width
            Bitmap.createScaledBitmap(full, w, max(1, (full.height * scale).toInt()), true)
        }
    } catch (_: Exception) { null }
}

/**
 * Shows a decoded thumbnail if available; falls back to the given icon.
 */
@Composable
fun FileThumbnail(entry: FileEntry, size: Dp, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val targetPx = with(density) { size.roundToPx() }
    var bitmap by remember(entry.path + "@" + entry.lastModified) { mutableStateOf(ThumbnailCache.get(entry.path)) }

    LaunchedEffect(entry.path, entry.lastModified, targetPx) {
        if (bitmap == null) {
            ThumbnailCache.loadAsync(entry.path, targetPx) {
                bitmap = ThumbnailCache.get(entry.path)
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = if (entry.name.endsWith(".mp4", true) ||
                    entry.name.endsWith(".mkv", true) ||
                    entry.name.endsWith(".avi", true) ||
                    entry.name.endsWith(".mov", true) ||
                    entry.name.endsWith(".webm", true))
                    Icons.Default.VideoFile else Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
