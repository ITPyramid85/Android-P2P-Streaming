package com.screencapture.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import com.screencapture.app.remote.RemoteScreenService

/**
 * Default app ends this session with:
 *   Intent("com.remoteapp.rc.ACTION_CLOSE").setPackage("com.remoteapp.rc")
 */
class CloseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CLOSE) {
            return
        }
        ServerConfig.setStoppedByHost(context, true)
        StartupController.cancelWatchdog(context)
        try {
            RemoteScreenService.stop(context)
        } catch (_: Throwable) {
        }
        try {
            NetworkService.disconnect(context)
        } catch (_: Throwable) {
        }
        try {
            context.applicationContext.stopService(
                Intent(context.applicationContext, RemoteScreenService::class.java)
            )
            context.applicationContext.stopService(
                Intent(context.applicationContext, NetworkService::class.java)
            )
        } catch (_: Throwable) {
        }
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val ACTION_CLOSE = "com.remoteapp.rc.ACTION_CLOSE"
    }
}
