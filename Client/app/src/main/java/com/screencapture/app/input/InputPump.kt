package com.screencapture.app.input

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/** One thread receives and applies every click, drag, scroll, and key. */
object InputPump {
    private val thread = HandlerThread("InputRecv").apply { start() }
    val looper: Looper = thread.looper
    val handler: Handler = Handler(looper)

    fun post(block: () -> Unit) {
        if (Looper.myLooper() == looper) block() else handler.post(block)
    }
}
