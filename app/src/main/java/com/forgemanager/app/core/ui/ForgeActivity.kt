package com.forgemanager.app.core.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.forgemanager.app.features.settings.UiPreferences

/**
 * Base for Forge Manager tool screens.
 *
 * The explorer keeps its own pane-aware inset handling; internal tools use this
 * class so their toolbars, editors and bottom controls never sit underneath
 * status/navigation/cutout/IME areas.
 */
abstract class ForgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        applySystemBarAppearance()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        view?.let(::installSafeInsets)
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        view?.let(::installSafeInsets)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        findViewById<View>(android.R.id.content)?.let(::installSafeInsets)
    }

    protected fun refreshSystemBars() = applySystemBarAppearance()

    private fun applySystemBarAppearance() {
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = UiPreferences.surface(this@ForgeActivity)
            window.navigationBarColor = UiPreferences.surface(this@ForgeActivity)
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun installSafeInsets(root: View) {
        if (root.getTag(SAFE_INSETS_TAG) == true) return
        root.setTag(SAFE_INSETS_TAG, true)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val safe = Insets.of(
                maxOf(bars.left, cutout.left),
                maxOf(bars.top, cutout.top),
                maxOf(bars.right, cutout.right),
                maxOf(bars.bottom, cutout.bottom, ime.bottom)
            )
            view.setPadding(
                initialLeft + safe.left,
                initialTop + safe.top,
                initialRight + safe.right,
                initialBottom + safe.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private companion object {
        // Negative app-private key to avoid colliding with resource IDs.
        const val SAFE_INSETS_TAG = -0x4653471
    }
}
