package com.screencapture.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RETRY) return
        StartupController.kick(context, "alarm")
    }

    companion object {
        const val ACTION_RETRY = "com.screencapture.app.action.RETRY_START"
    }
}
