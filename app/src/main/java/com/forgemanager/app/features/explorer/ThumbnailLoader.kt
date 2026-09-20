package com.forgemanager.app.features.explorer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.forgemanager.app.core.file.AccessResolver
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.FileNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.WeakHashMap
import kotlin.math.max

/** Lightweight thumbnail pipeline for file rows. */
class ThumbnailLoader(private val resolver: AccessResolver) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = WeakHashMap<ImageView, Job>()
    private val cacheBytes = (Runtime.getRuntime().maxMemory() / 16L)
        .coerceIn(4L * 1024 * 1024, 32L * 1024 * 1024)
        .toInt()
    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    fun load(view: ImageView, node: FileNode, targetPx: Int, fallback: () -> Unit) {
        cancel(view)
        val key = "${node.location.displayPath}|${node.modified}|${node.size}|$targetPx"
        view.tag = key
        fallback()
        cache.get(key)?.let {
            view.setImageBitmap(it)
            view.imageTintList = null
            return
        }
        if (node.size <= 0 || node.size > MAX_SOURCE_BYTES || node.isDirectory) return

        val job = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { decode(node.location, targetPx) }.getOrNull()
            }
            if (bitmap != null && view.tag == key) {
                cache.put(key, bitmap)
                view.setImageBitmap(bitmap)
                view.imageTintList = null
            }
        }
        jobs[view] = job
    }

    fun cancel(view: ImageView) {
        jobs.remove(view)?.cancel()
        view.tag = null
    }

    fun clear() = cache.evictAll()

    fun close() {
        jobs.values.toList().forEach(Job::cancel)
        jobs.clear()
        cache.evictAll()
        scope.cancel()
    }

    private suspend fun decode(location: FileLocation, targetPx: Int): Bitmap? {
        val requested = targetPx.coerceIn(32, 320)
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (direct != null && direct.isFile && direct.canRead()) {
            BitmapFactory.decodeFile(direct.path, bounds)
        } else {
            resolver.backendFor(location).openInput(location).use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > requested * 2) {
            if (sample >= 64) break
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = if (direct != null && direct.isFile && direct.canRead()) {
            BitmapFactory.decodeFile(direct.path, options)
        } else {
            resolver.backendFor(location).openInput(location).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } ?: return null

        val maxSide = max(decoded.width, decoded.height)
        if (maxSide <= requested * 2) return decoded
        val scale = (requested * 2f) / maxSide.toFloat()
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, width, height, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
    }
}
