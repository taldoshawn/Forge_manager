package com.forgemanager.app.features.explorer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.LinearLayout
import com.forgemanager.app.features.settings.UiPreferences

/** Keeps the main toolbar colorful even when MainActivity reapplies a surface tint. */
class ThemedTopBarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private var ready = false

    init {
        ready = true
        post(::applyForgeBackground)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyForgeBackground()
    }

    override fun setBackgroundTintList(tint: ColorStateList?) {
        if (!ready) {
            super.setBackgroundTintList(tint)
            return
        }
        applyForgeBackground()
    }

    private fun applyForgeBackground() {
        super.setBackgroundTintList(null)
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(UiPreferences.accentAlt(context), UiPreferences.accent(context))
        )
    }
}

/**
 * MainActivity historically darkened the active pane header by multiplying the
 * accent by 0.22. That is fine on a dark theme but produces a nearly black block
 * inside the new light themes. This layout remaps only that very dark active
 * tint to a pale accent surface while keeping the existing controller logic.
 */
class ThemedPaneHeaderLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private var ready = false

    init {
        ready = true
    }

    override fun setBackgroundTintList(tint: ColorStateList?) {
        if (!ready) {
            super.setBackgroundTintList(tint)
            return
        }
        val requested = tint?.defaultColor ?: UiPreferences.elevatedSurface(context)
        val color = if (UiPreferences.isLight(context) && luminance(requested) < 95) {
            blend(UiPreferences.surface(context), UiPreferences.accent(context), 0.13f)
        } else requested
        super.setBackgroundTintList(ColorStateList.valueOf(color))
    }

    private fun luminance(color: Int): Int =
        ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000)

    private fun blend(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) * (1f - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1f - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1f - t) + Color.blue(b) * t).toInt()
        )
    }
}
