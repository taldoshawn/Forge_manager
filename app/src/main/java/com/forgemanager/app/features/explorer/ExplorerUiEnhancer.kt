package com.forgemanager.app.features.explorer

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import com.forgemanager.app.MainActivity
import com.forgemanager.app.R
import com.forgemanager.app.features.settings.UiPreferences

/**
 * Applies the explorer's runtime palette after MainActivity has refreshed its
 * generic surfaces. Keeping this in one place lets the file manager use a
 * colorful top bar while still supporting white, gray and normal-dark content.
 */
object ExplorerUiEnhancer {
    fun apply(activity: Activity) {
        if (activity !is MainActivity) return
        runCatching {
            val accent = UiPreferences.accent(activity)
            val alt = UiPreferences.accentAlt(activity)
            val surface = UiPreferences.surface(activity)
            val text = UiPreferences.textPrimary(activity)

            activity.findViewById<android.view.View>(R.id.topBar)?.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(darken(accent, 0.82f), accent, alt)
            )
            activity.findViewById<android.view.View>(R.id.bottomBar)?.background = GradientDrawable().apply {
                setColor(surface)
                setStroke(dp(activity, 1), UiPreferences.divider(activity))
            }

            val white = ColorStateList.valueOf(Color.WHITE)
            activity.findViewById<ImageButton>(R.id.menuButton)?.imageTintList = white
            activity.findViewById<ImageButton>(R.id.moreButton)?.imageTintList = white
            activity.findViewById<TextView>(R.id.activePath)?.setTextColor(Color.WHITE)
            activity.findViewById<TextView>(R.id.folderInfo)?.setTextColor(Color.argb(225, 238, 247, 255))

            activity.findViewById<ImageButton>(R.id.backButton)?.imageTintList = ColorStateList.valueOf(text)
            activity.findViewById<ImageButton>(R.id.forwardButton)?.imageTintList = ColorStateList.valueOf(text)
            activity.findViewById<ImageButton>(R.id.upButton)?.imageTintList = ColorStateList.valueOf(text)
            activity.findViewById<ImageButton>(R.id.createButton)?.imageTintList = ColorStateList.valueOf(accent)
            activity.findViewById<ImageButton>(R.id.syncButton)?.imageTintList = ColorStateList.valueOf(alt)

            @Suppress("DEPRECATION")
            run {
                activity.window.statusBarColor = darken(accent, 0.76f)
                activity.window.navigationBarColor = surface
            }
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = UiPreferences.isLight(activity)
            }
        }
    }

    private fun darken(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) * amount).toInt().coerceIn(0, 255),
        (Color.green(color) * amount).toInt().coerceIn(0, 255),
        (Color.blue(color) * amount).toInt().coerceIn(0, 255)
    )

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
