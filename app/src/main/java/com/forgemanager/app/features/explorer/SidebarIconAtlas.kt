package com.forgemanager.app.features.explorer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.forgemanager.app.R
import com.forgemanager.app.features.settings.UiPreferences

/**
 * Theme-aware atlas for the action icons supplied by the user.
 *
 * The source pack has independent artwork for black-card and white-card themes.
 * We keep both atlases lossless and select them from Forge's own theme setting,
 * rather than relying on the device's system night-mode qualifier.
 */
object SidebarIconAtlas {
    private const val COLUMNS = 6
    private const val TILE_PX = 100
    private const val SLOT_COUNT = 24

    private val sheetCache = object : LruCache<Int, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }
    private val iconCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun drawable(context: Context, slot: Int): Drawable? {
        if (slot !in 0 until SLOT_COUNT) return null
        val light = UiPreferences.isLight(context)
        val resId = if (light) R.drawable.fm_sidebar_icons_light else R.drawable.fm_sidebar_icons_dark
        val key = "$resId:$slot"
        val cached = synchronized(iconCache) { iconCache.get(key) }
        val bitmap = cached ?: run {
            val sheet = synchronized(sheetCache) { sheetCache.get(resId) }
                ?: BitmapFactory.decodeResource(context.resources, resId)?.also {
                    synchronized(sheetCache) { sheetCache.put(resId, it) }
                }
                ?: return null
            val left = (slot % COLUMNS) * TILE_PX
            val top = (slot / COLUMNS) * TILE_PX
            if (left + TILE_PX > sheet.width || top + TILE_PX > sheet.height) return null
            Bitmap.createBitmap(sheet, left, top, TILE_PX, TILE_PX).also {
                synchronized(iconCache) { iconCache.put(key, it) }
            }
        }
        return BitmapDrawable(context.resources, bitmap)
    }
}
