package com.forgemanager.app.features.explorer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.forgemanager.app.features.settings.UiPreferences
import java.io.ByteArrayOutputStream

/**
 * Theme-aware atlas built from the action icons supplied by the user.
 *
 * The original pack contains independent artwork for the black and white app
 * themes. The compact WebP sheets bundled under assets/sidebar are byte-split
 * only to keep repository writes manageable; at runtime they are concatenated
 * back into the exact sheet before decoding. No icon is redrawn here.
 */
object SidebarIconAtlas {
    private const val COLUMNS = 5
    private const val TILE_PX = 36
    private const val SLOT_COUNT = 20

    private val sheetCache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val iconCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun drawable(context: Context, slot: Int): Drawable? {
        if (slot !in 0 until SLOT_COUNT) return null
        val theme = if (UiPreferences.isLight(context)) "light" else "dark"
        val key = "$theme:$slot"
        val bitmap = synchronized(iconCache) { iconCache.get(key) } ?: run {
            val sheet = loadSheet(context, theme) ?: return null
            val left = (slot % COLUMNS) * TILE_PX
            val top = (slot / COLUMNS) * TILE_PX
            if (left + TILE_PX > sheet.width || top + TILE_PX > sheet.height) return null
            Bitmap.createBitmap(sheet, left, top, TILE_PX, TILE_PX).also {
                synchronized(iconCache) { iconCache.put(key, it) }
            }
        }
        return BitmapDrawable(context.resources, bitmap).apply {
            setTargetDensity(context.resources.displayMetrics)
        }
    }

    private fun loadSheet(context: Context, theme: String): Bitmap? {
        synchronized(sheetCache) { sheetCache.get(theme) }?.let { return it }
        val chunks = runCatching {
            context.assets.list("sidebar").orEmpty()
                .filter { it.startsWith("${theme}_") && it.endsWith(".bin") }
                .sorted()
        }.getOrDefault(emptyList())
        if (chunks.isEmpty()) return null

        val bytes = runCatching {
            ByteArrayOutputStream().use { output ->
                chunks.forEach { name ->
                    context.assets.open("sidebar/$name").use { input -> input.copyTo(output, 8 * 1024) }
                }
                output.toByteArray()
            }
        }.getOrNull() ?: return null

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        synchronized(sheetCache) { sheetCache.put(theme, decoded) }
        return decoded
    }
}