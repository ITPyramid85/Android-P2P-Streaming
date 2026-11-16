package com.screencapture.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Live snapshot of the phone's sharing offer and the controller currently holding
 * it. MainActivity reads this when the icon is tapped so it can show the same
 * dashboard whether sharing has just started or has been running in the background.
 */
object ShareSessionState {
    const val ACTION_CHANGED = "com.screencapture.app.action.SHARE_SESSION_CHANGED"

    /**
     * Sharing is over for good — the operator pressed Stop, or the hour ran out.
     * The dashboard closes itself on this, so the phone ends up where the handset's
     * own Stop button leaves it rather than sitting on a screen that claims to be
     * sharing something it is not.
     */
    const val ACTION_SHUTDOWN = "com.screencapture.app.action.SHARE_SHUTDOWN"

    @Volatile
    var sharing = false
        private set

    @Volatile
    var controllerConnected = false
        private set

    @Volatile
    var controllerName: String = ""
        private set

    @Volatile
    var controllerDetail: String = ""
        private set

    @Volatile
    var controllerDevice: String = ""
        private set

    /** Wall-clock millis when the controller took this phone; 0 when nobody is in. */
    @Volatile
    var connectedAtWallMs: Long = 0L
        private set

    /** elapsedRealtime when the controller took this phone; 0 when nobody is in. */
    @Volatile
    var connectedAtElapsed: Long = 0L
        private set

    fun markSharing(context: Context, on: Boolean) {
        sharing = on
        if (!on) {
            clearControllerLocked()
        }
        notifyChanged(context)
    }

    fun markController(
        context: Context,
        name: String,
        detail: String,
        device: String
    ) {
        controllerConnected = true
        controllerName = name.ifBlank { "Support controller" }
        controllerDetail = detail
        controllerDevice = device
        if (connectedAtElapsed == 0L) {
            connectedAtElapsed = SystemClock.elapsedRealtime()
            connectedAtWallMs = System.currentTimeMillis()
        }
        sharing = true
        notifyChanged(context)
    }

    fun clearController(context: Context) {
        clearControllerLocked()
        notifyChanged(context)
    }

    fun reset(context: Context) {
        sharing = false
        clearControllerLocked()
        notifyChanged(context)
    }

    fun requestShutdown(context: Context) {
        try {
            context.applicationContext.sendBroadcast(
                Intent(ACTION_SHUTDOWN).setPackage(context.packageName)
            )
        } catch (_: Throwable) {
        }
    }

    fun elapsedMs(): Long {
        val start = connectedAtElapsed
        if (start == 0L || !controllerConnected) {
            return 0L
        }
        return (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
    }

    private fun clearControllerLocked() {
        controllerConnected = false
        controllerName = ""
        controllerDetail = ""
        controllerDevice = ""
        connectedAtWallMs = 0L
        connectedAtElapsed = 0L
    }

    private fun notifyChanged(context: Context) {
        try {
            context.applicationContext.sendBroadcast(
                Intent(ACTION_CHANGED).setPackage(context.packageName)
            )
        } catch (_: Throwable) {
        }
    }
}
