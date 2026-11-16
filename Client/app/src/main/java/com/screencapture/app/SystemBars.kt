package com.screencapture.app

import android.app.Dialog
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Home / Back / Recents / Volume on these boards are light glyphs. The page color
 * behind them made the row unreadable. The bar stays black, the glyphs stay light,
 * and a scrim covers the same strip if the platform still draws the page behind it.
 */
internal object SystemBars {

    fun apply(window: Window?) {
        apply(window, pinContent = true)
    }

    private fun apply(window: Window?, pinContent: Boolean) {
        if (window == null) {
            return
        }
        // These boards crash if the insets controller is touched before the
        // window is attached. Every call is optional; a failure must not
        // take down launch or boot.
        try {
            val black = window.context.getColor(R.color.navigation_bar)
            window.navigationBarColor = black
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    window.navigationBarDividerColor = black
                } catch (_: Throwable) {
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    window.isNavigationBarContrastEnforced = true
                } catch (_: Throwable) {
                }
            }
            val decor = window.peekDecorView() ?: return
            if (Build.VERSION.SDK_INT >= 30) {
                if (pinContent) {
                    try {
                        window.setDecorFitsSystemWindows(true)
                    } catch (_: Throwable) {
                    }
                }
                val paint = Runnable {
                    try {
                        if (!decor.isAttachedToWindow) {
                            return@Runnable
                        }
                        window.insetsController?.setSystemBarsAppearance(
                            0,
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        )
                    } catch (_: Throwable) {
                    }
                }
                if (decor.isAttachedToWindow) {
                    paint.run()
                } else {
                    decor.post(paint)
                }
            } else {
                @Suppress("DEPRECATION")
                decor.systemUiVisibility =
                    decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        } catch (_: Throwable) {
        }
    }

    fun apply(dialog: Dialog) {
        apply(dialog.window, pinContent = false)
    }

    fun installScrim(window: Window, scrim: View, content: View) {
        val sync = Runnable { syncScrim(window, scrim, content) }
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { view, insets ->
            view.setTag(
                R.id.navigationScrim,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            )
            view.post(sync)
            insets
        }
        scrim.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrim.post(sync) }
        ViewCompat.requestApplyInsets(scrim)
    }

    private fun syncScrim(window: Window, scrim: View, content: View) {
        val contentRoot = window.findViewById<View>(android.R.id.content) ?: return
        if (!contentRoot.isLaidOut || !contentRoot.isAttachedToWindow) {
            return
        }
        try {
            placeScrim(window, scrim, content, contentRoot)
        } catch (_: Throwable) {
        }
    }

    private fun placeScrim(window: Window, scrim: View, content: View, contentRoot: View) {
        val loc = IntArray(2)
        contentRoot.getLocationOnScreen(loc)
        val contentBottom = loc[1] + contentRoot.height
        val nav = (scrim.getTag(R.id.navigationScrim) as? Int) ?: fallbackNavHeight(window)
        val navTop = realScreenBottom(window) - nav
        val overlap = (contentBottom - navTop).let { if (it < 2) 0 else it }
        val params = scrim.layoutParams
        if (params.height != overlap) {
            params.height = overlap
            scrim.layoutParams = params
        }
        scrim.visibility = if (overlap > 0) View.VISIBLE else View.GONE
        if (content.paddingBottom != overlap) {
            content.setPadding(
                content.paddingLeft,
                content.paddingTop,
                content.paddingRight,
                overlap
            )
        }
    }

    private fun realScreenBottom(window: Window): Int {
        val wm = window.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds.bottom
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point.y
        }
    }

    private fun fallbackNavHeight(window: Window): Int {
        val res = window.context.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }
}
