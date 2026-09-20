package com.forgemanager.app.features.settings

import android.content.Context
import android.graphics.Color

object UiPreferences {
    private const val PREFS = "forge_ui_settings"
    private const val KEY_AMOLED = "amoled"
    private const val KEY_ACCENT = "accent"
    private const val KEY_COMPACT = "compact_rows"
    private const val KEY_LARGE_ICONS = "large_icons"
    private const val KEY_SHOW_HIDDEN = "show_hidden_default"
    private const val KEY_ARCHIVE_INTERNAL = "archives_internal"
    private const val KEY_CONFIRM_DELETE = "confirm_delete"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun amoled(context: Context): Boolean = prefs(context).getBoolean(KEY_AMOLED, true)
    fun compactRows(context: Context): Boolean = prefs(context).getBoolean(KEY_COMPACT, false)
    fun largeIcons(context: Context): Boolean = prefs(context).getBoolean(KEY_LARGE_ICONS, true)
    fun showHiddenDefault(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_HIDDEN, false)
    fun openArchivesInternally(context: Context): Boolean = prefs(context).getBoolean(KEY_ARCHIVE_INTERNAL, true)
    fun confirmDelete(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFIRM_DELETE, true)

    fun accentName(context: Context): String = prefs(context).getString(KEY_ACCENT, "blue") ?: "blue"
    fun accent(context: Context): Int = when (accentName(context)) {
        "cyan" -> Color.rgb(0, 214, 255)
        "purple" -> Color.rgb(154, 103, 255)
        "green" -> Color.rgb(62, 214, 132)
        "orange" -> Color.rgb(255, 154, 61)
        "red" -> Color.rgb(255, 80, 92)
        else -> Color.rgb(24, 139, 255)
    }

    fun surface(context: Context): Int = if (amoled(context)) Color.BLACK else Color.rgb(9, 14, 22)
    fun elevatedSurface(context: Context): Int = if (amoled(context)) Color.rgb(5, 5, 5) else Color.rgb(14, 22, 34)

    fun setAmoled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AMOLED, value).apply()
    fun setAccent(context: Context, value: String) = prefs(context).edit().putString(KEY_ACCENT, value).apply()
    fun setCompactRows(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_COMPACT, value).apply()
    fun setLargeIcons(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_LARGE_ICONS, value).apply()
    fun setShowHiddenDefault(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
    fun setOpenArchivesInternally(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ARCHIVE_INTERNAL, value).apply()
    fun setConfirmDelete(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_CONFIRM_DELETE, value).apply()
}
