package com.screencapture.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * BOOT_COMPLETED on Android 14 cannot start an Activity from the background.
 * Start the persistent TCP foreground service instead, then retry with alarms/jobs.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in BOOT_ACTIONS) return

        val app = context.applicationContext
        val pending = goAsync()
        val wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteApp:boot")
            .apply {
                setReferenceCounted(false)
                acquire(45_000L)
            }

        Handler(Looper.getMainLooper()).post {
            try {
                StartupController.scheduleWatchdog(app)
                StartupController.scheduleRetryAlarms(app)
                StartupController.kick(app, action)
            } finally {
                try {
                    if (wakeLock.isHeld) wakeLock.release()
                } catch (_: Throwable) {
                }
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"

        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT
        )
    }
}
