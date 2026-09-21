package com.forgemanager.app.core.ui

import android.app.Activity
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
import java.util.WeakHashMap

/**
 * Base for Forge Manager tool screens.
 *
 * Keep startup deliberately conservative: file viewers/editors are opened very
 * frequently and must not crash because an OEM rejects an inset/window call.
 */
abstract class ForgeActivity : Activity() {
    private val insetRoots = WeakHashMap<View, Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { WindowCompat.setDecorFitsSystemWindows(window, false) }
        runCatching {
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
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
        runCatching {
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
    }

    private fun installSafeInsets(root: View) {
        // Do not use View#setTag(int, …) with a synthetic key. Some OEM builds
        // enforce resource-id ownership more strictly and can throw while a tool
        // Activity is being opened. A weak identity map avoids that whole class
        // of startup crash without retaining destroyed views.
        if (insetRoots.put(root, Unit) != null) return

        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            runCatching {
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
            }
            insets
        }
        runCatching { ViewCompat.requestApplyInsets(root) }
    }
}
