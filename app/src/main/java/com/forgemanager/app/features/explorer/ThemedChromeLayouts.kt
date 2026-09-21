package com.forgemanager.app.features.explorer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.forgemanager.app.features.settings.UiPreferences

/** Main explorer chrome: compact MT-style neutral dark header on every theme. */
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
        setBackgroundColor(Color.rgb(32, 32, 32))
    }
}

/** Secondary line remains readable on the permanent dark header. */
class ThemedTopInfoTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextView(context, attrs) {
    private var ready = false

    init {
        ready = true
        super.setTextColor(Color.rgb(190, 196, 205))
    }

    override fun setTextColor(color: Int) {
        if (!ready) super.setTextColor(color)
        else super.setTextColor(Color.rgb(190, 196, 205))
    }

    override fun setTextColor(colors: ColorStateList?) {
        if (!ready) super.setTextColor(colors)
        else super.setTextColor(ColorStateList.valueOf(Color.rgb(190, 196, 205)))
    }
}

/** Dynamic divider for the user-selected white/gray/dark theme. */
class ThemedDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setBackgroundColor(UiPreferences.divider(context))
    }
}

/** Keeps pane headers readable in white/gray/dark themes without giant dark blocks. */
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
            blend(UiPreferences.surface(context), UiPreferences.accent(context), 0.10f)
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
