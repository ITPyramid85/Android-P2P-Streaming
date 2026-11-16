package com.screencapture.app.input

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock

/** One thread receives and applies every click, drag, scroll, and key. */
object InputPump {
    private val thread = HandlerThread("InputRecv").apply { start() }
    val looper: Looper = thread.looper
    val handler: Handler = Handler(looper)

    fun post(block: () -> Unit) {
        if (Looper.myLooper() == looper) block() else handler.post(block)
    }

    /**
     * Queue work of which only the newest copy matters, replacing any earlier one
     * that has not run yet. For a pointer position an older update is not merely
     * redundant, it is wrong: running it paints the circle somewhere the mouse has
     * already left, and a queue of them makes the circle trail the real cursor by
     * however long the backlog takes to drain.
     *
     * [token] groups what counts as the same update. Work posted under one token
     * never cancels another's.
     */
    fun postLatest(token: Any, block: () -> Unit) {
        handler.removeCallbacksAndMessages(token)
        if (Looper.myLooper() == looper) {
            block()
            return
        }
        handler.postAtTime(block, token, SystemClock.uptimeMillis())
    }

    /**
     * Throw away input that has been accepted but not yet applied. Called when a
     * controller's session ends: anything still queued belongs to that controller
     * and must not land on the screen after it has lost control.
     */
    fun clear() {
        handler.removeCallbacksAndMessages(null)
    }
}
