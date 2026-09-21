package com.forgemanager.app.features.settings

import android.content.Context
import android.graphics.Color

/** Single source of truth for Forge Manager visual preferences. */
object UiPreferences {
    private const val PREFS = "forge_ui_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_AMOLED = "amoled" // legacy migration only
    private const val KEY_ACCENT = "accent"
    private const val KEY_COMPACT = "compact_rows"
    private const val KEY_LARGE_ICONS = "large_icons"
    private const val KEY_SHOW_HIDDEN = "show_hidden_default"
    private const val KEY_ARCHIVE_INTERNAL = "archives_internal"
    private const val KEY_CONFIRM_DELETE = "confirm_delete"

    const val THEME_WHITE = "white"
    const val THEME_GRAY = "gray"
    const val THEME_DARK = "dark"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Default is a clean white file-manager surface. Gray and a normal dark
     * theme remain selectable. Pure AMOLED black is intentionally not used.
     */
    fun themeMode(context: Context): String {
        val preferences = prefs(context)
        val saved = preferences.getString(KEY_THEME, null)
        if (saved in setOf(THEME_WHITE, THEME_GRAY, THEME_DARK)) return saved!!
        return if (preferences.getBoolean(KEY_AMOLED, false)) THEME_DARK else THEME_WHITE
    }

    fun isLight(context: Context): Boolean = themeMode(context) != THEME_DARK
    fun compactRows(context: Context): Boolean = prefs(context).getBoolean(KEY_COMPACT, true)
    fun largeIcons(context: Context): Boolean = prefs(context).getBoolean(KEY_LARGE_ICONS, false)
    fun showHiddenDefault(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_HIDDEN, false)
    fun openArchivesInternally(context: Context): Boolean = prefs(context).getBoolean(KEY_ARCHIVE_INTERNAL, true)
    fun confirmDelete(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFIRM_DELETE, true)

    fun accentName(context: Context): String = prefs(context).getString(KEY_ACCENT, "azure") ?: "azure"

    fun accent(context: Context): Int = when (accentName(context)) {
        "cyan" -> Color.rgb(0, 174, 214)
        "violet" -> Color.rgb(126, 87, 194)
        "green" -> Color.rgb(29, 160, 103)
        "orange" -> Color.rgb(238, 123, 45)
        "red" -> Color.rgb(224, 73, 83)
        "pink" -> Color.rgb(214, 68, 139)
        else -> Color.rgb(31, 126, 232)
    }

    fun accentAlt(context: Context): Int = when (accentName(context)) {
        "cyan" -> Color.rgb(17, 109, 219)
        "violet" -> Color.rgb(215, 75, 151)
        "green" -> Color.rgb(0, 169, 184)
        "orange" -> Color.rgb(220, 72, 83)
        "red" -> Color.rgb(165, 73, 196)
        "pink" -> Color.rgb(119, 77, 207)
        else -> Color.rgb(0, 184, 202)
    }

    fun background(context: Context): Int = when (themeMode(context)) {
        THEME_GRAY -> Color.rgb(229, 233, 239)
        THEME_DARK -> Color.rgb(24, 26, 31)
        else -> Color.rgb(248, 249, 251)
    }

    fun surface(context: Context): Int = when (themeMode(context)) {
        THEME_GRAY -> Color.rgb(240, 242, 246)
        THEME_DARK -> Color.rgb(31, 34, 40)
        else -> Color.WHITE
    }

    fun elevatedSurface(context: Context): Int = when (themeMode(context)) {
        THEME_GRAY -> Color.rgb(218, 223, 230)
        THEME_DARK -> Color.rgb(39, 43, 51)
        else -> Color.rgb(238, 242, 247)
    }

    fun subtleSurface(context: Context): Int = when (themeMode(context)) {
        THEME_GRAY -> Color.rgb(207, 213, 222)
        THEME_DARK -> Color.rgb(47, 51, 60)
        else -> Color.rgb(230, 235, 242)
    }

    fun divider(context: Context): Int = when (themeMode(context)) {
        THEME_GRAY -> Color.rgb(190, 197, 208)
        THEME_DARK -> Color.rgb(62, 67, 78)
        else -> Color.rgb(214, 220, 229)
    }

    fun textPrimary(context: Context): Int = if (isLight(context)) Color.rgb(25, 30, 39) else Color.rgb(242, 244, 248)
    fun textSecondary(context: Context): Int = if (isLight(context)) Color.rgb(92, 101, 116) else Color.rgb(166, 173, 185)

    fun setThemeMode(context: Context, value: String) {
        require(value in setOf(THEME_WHITE, THEME_GRAY, THEME_DARK))
        prefs(context).edit().putString(KEY_THEME, value).remove(KEY_AMOLED).apply()
    }

    fun amoled(context: Context): Boolean = themeMode(context) == THEME_DARK
    fun setAmoled(context: Context, value: Boolean) = setThemeMode(context, if (value) THEME_DARK else THEME_WHITE)

    fun setAccent(context: Context, value: String) = prefs(context).edit().putString(KEY_ACCENT, value).apply()
    fun setCompactRows(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_COMPACT, value).apply()
    fun setLargeIcons(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_LARGE_ICONS, value).apply()
    fun setShowHiddenDefault(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
    fun setOpenArchivesInternally(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ARCHIVE_INTERNAL, value).apply()
    fun setConfirmDelete(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_CONFIRM_DELETE, value).apply()
}
