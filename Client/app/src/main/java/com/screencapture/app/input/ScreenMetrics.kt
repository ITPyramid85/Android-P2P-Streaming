package com.screencapture.app.input

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * Physical default-display size in the **current rotation**.
 *
 * Do not mix independent max() of two size sources: that squares the buffer
 * or swaps portrait/landscape. Application window metrics can stay portrait
 * after the first launch even when the phone is already landscape.
 */
object ScreenMetrics {
    @Suppress("DEPRECATION")
    fun size(context: Context): Pair<Int, Int> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 1 && metrics.heightPixels > 1) {
                return metrics.widthPixels to metrics.heightPixels
            }
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            return bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        }
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }
}
