package com.forgemanager.app.features.settings

import android.content.Context
import android.graphics.Color

/** Single source of truth for Forge Manager visual preferences. */
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

    // A balanced dark palette is the default. Pure AMOLED remains opt-in.
    fun amoled(context: Context): Boolean = prefs(context).getBoolean(KEY_AMOLED, false)
    fun compactRows(context: Context): Boolean = prefs(context).getBoolean(KEY_COMPACT, false)
    fun largeIcons(context: Context): Boolean = prefs(context).getBoolean(KEY_LARGE_ICONS, true)
    fun showHiddenDefault(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_HIDDEN, false)
    fun openArchivesInternally(context: Context): Boolean = prefs(context).getBoolean(KEY_ARCHIVE_INTERNAL, true)
    fun confirmDelete(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFIRM_DELETE, true)

    fun accentName(context: Context): String = prefs(context).getString(KEY_ACCENT, "blue") ?: "blue"
    fun accent(context: Context): Int = when (accentName(context)) {
        "cyan" -> Color.rgb(0, 200, 235)
        "purple" -> Color.rgb(151, 106, 255)
        "green" -> Color.rgb(63, 201, 126)
        "orange" -> Color.rgb(245, 151, 66)
        "red" -> Color.rgb(244, 82, 94)
        else -> Color.rgb(42, 143, 255)
    }

    fun background(context: Context): Int = if (amoled(context)) Color.BLACK else Color.rgb(16, 17, 20)
    fun surface(context: Context): Int = if (amoled(context)) Color.BLACK else Color.rgb(23, 24, 28)
    fun elevatedSurface(context: Context): Int = if (amoled(context)) Color.rgb(8, 8, 8) else Color.rgb(29, 31, 36)
    fun subtleSurface(context: Context): Int = if (amoled(context)) Color.rgb(13, 13, 13) else Color.rgb(34, 36, 42)
    fun divider(context: Context): Int = if (amoled(context)) Color.rgb(34, 34, 34) else Color.rgb(48, 51, 59)
    fun textPrimary(context: Context): Int = Color.rgb(241, 243, 247)
    fun textSecondary(context: Context): Int = Color.rgb(154, 160, 171)

    fun setAmoled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AMOLED, value).apply()
    fun setAccent(context: Context, value: String) = prefs(context).edit().putString(KEY_ACCENT, value).apply()
    fun setCompactRows(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_COMPACT, value).apply()
    fun setLargeIcons(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_LARGE_ICONS, value).apply()
    fun setShowHiddenDefault(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
    fun setOpenArchivesInternally(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ARCHIVE_INTERNAL, value).apply()
    fun setConfirmDelete(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_CONFIRM_DELETE, value).apply()
}
