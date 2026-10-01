package com.screencapture.app.input

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Forwards desktop mouse/keyboard to the phone.
 * Port of RemoteApp AgentInput: InputManager inject first, then Accessibility.
 */
object AgentInput {

    @Volatile
    private var downTime: Long = 0

    @Volatile
    private var injectMethod: Method? = null

    fun touch(context: Context, action: String, nx: Float, ny: Float) {
        val (w, h) = ScreenMetrics.size(context)
        val x = clamp((nx * w).toInt(), 0, w - 1)
        val y = clamp((ny * h).toInt(), 0, h - 1)
        RemoteInputService.ensureAccessibility(context)
        val service = RemoteInputService.instance
        if (service != null) {
            service.touch(action, x.toFloat(), y.toFloat())
            return
        }
        tryInjectMotion(context, action, x, y)
    }

    fun scroll(context: Context, nx: Float, ny: Float, vertical: Float) {
        val (w, h) = ScreenMetrics.size(context)
        val x = clamp((nx * w).toInt(), 0, w - 1)
        val y = clamp((ny * h).toInt(), 0, h - 1)
        RemoteInputService.ensureAccessibility(context)
        RemoteInputService.instance?.scroll(x.toFloat(), y.toFloat(), vertical)
    }

    fun key(context: Context, keyCode: Int, down: Boolean) {
        if (tryInjectKey(context, keyCode, down)) {
            return
        }
        if (down) {
            return
        }
        if (shellKey(keyCode)) {
            return
        }
        RemoteInputService.ensureAccessibility(context)
        RemoteInputService.instance?.handleAndroidKey(keyCode)
    }

    fun text(context: Context, value: String?) {
        if (value.isNullOrEmpty()) {
            return
        }
        if (tryInjectText(context, value)) {
            return
        }
        if (shellText(value)) {
            return
        }
        RemoteInputService.ensureAccessibility(context)
        RemoteInputService.instance?.typeText(value)
    }

    private fun shellKey(keyCode: Int): Boolean {
        return runShell(arrayOf("input", "keyevent", keyCode.toString()))
    }

    private fun shellText(value: String): Boolean {
        val escaped = escapeInputText(value)
        if (escaped.isEmpty()) {
            return false
        }
        return runShell(arrayOf("input", "text", escaped))
    }

    private fun escapeInputText(value: String): String {
        val out = StringBuilder(value.length * 2)
        for (c in value) {
            when {
                c == ' ' -> out.append("%s")
                c == '%' || c == '"' || c == '\'' || c == '\\'
                    || c == '&' || c == '|' || c == ';' || c == '<'
                    || c == '>' || c == '(' || c == ')' -> {
                    out.append('\\').append(c)
                }
                c.code >= 32 -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun tryInjectText(context: Context, value: String): Boolean {
        var all = true
        for (c in value) {
            val keyCode = charToKeyCode(c)
            if (keyCode == 0) {
                all = false
                continue
            }
            if (!tryInjectKey(context, keyCode, true) || !tryInjectKey(context, keyCode, false)) {
                all = false
            }
        }
        return all && value.isNotEmpty()
    }

    private fun charToKeyCode(c: Char): Int {
        return when {
            c in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
            c in 'A'..'Z' -> KeyEvent.KEYCODE_A + (c - 'A')
            c in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
            c == ' ' -> KeyEvent.KEYCODE_SPACE
            c == '\n' -> KeyEvent.KEYCODE_ENTER
            else -> 0
        }
    }

    private fun runShell(cmd: Array<String>): Boolean {
        return try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun tryInjectMotion(context: Context, action: String, x: Int, y: Int): Boolean {
        val motion = motionAction(action)
        if (motion < 0) {
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (motion == MotionEvent.ACTION_DOWN) {
            downTime = now
        }
        if (downTime == 0L) {
            downTime = now
        }
        val event = MotionEvent.obtain(downTime, now, motion, x.toFloat(), y.toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        val ok = inject(context, event)
        event.recycle()
        if (motion == MotionEvent.ACTION_UP) {
            downTime = 0
        }
        return ok
    }

    private fun tryInjectKey(context: Context, keyCode: Int, down: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now,
            now,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode,
            0
        )
        event.source = InputDevice.SOURCE_KEYBOARD
        return inject(context, event)
    }

    private fun inject(context: Context, event: InputEvent): Boolean {
        return try {
            val manager = context.getSystemService(Context.INPUT_SERVICE) ?: return false
            var method = injectMethod
            if (method == null) {
                method = manager.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Integer.TYPE
                )
                injectMethod = method
            }
            val result = method?.invoke(manager, event, 0)
            result == true
        } catch (e: Exception) {
            false
        }
    }

    private fun motionAction(action: String): Int {
        return when (action) {
            "down" -> MotionEvent.ACTION_DOWN
            "move" -> MotionEvent.ACTION_MOVE
            "up" -> MotionEvent.ACTION_UP
            else -> -1
        }
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        if (value < min) return min
        if (value > max) return max
        return value
    }
}
