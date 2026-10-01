package com.screencapture.app.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.genymobile.scrcpy.agent.AgentInput
import com.genymobile.scrcpy.agent.SilentScreenStreamer
import com.screencapture.app.CaptureConsentActivity
import com.screencapture.app.FrameBridge
import com.screencapture.app.ProjectionResultHolder
import com.screencapture.app.R

/**
 * Foreground MediaProjection service. Holds the screen-cast token and
 * starts SilentScreenStreamer JPEG capture.
 *
 * Android 14+: getMediaProjection must run only after this service has entered
 * foreground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION. A persisted
 * "granted once" preference is never enough to capture after reboot.
 */
class RemoteScreenService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Android 14 invalidates a MediaProjection once its VirtualDisplay
                // is torn down: a second createVirtualDisplay on the same token
                // throws and forces a fresh consent, which is why reopening the
                // monitor was slow and needed a tap. Keep capture alive and only
                // stop forwarding frames; a real teardown happens in onDestroy.
                FrameBridge.setStreaming(false)
                return START_STICKY
            }
            else -> {
                if (!ProjectionResultHolder.canCaptureWithoutDialog()) {
                    // A streamer left "live" without a live projection (the RK3588
                    // projection can die right after grant) must be torn down here,
                    // or every start() keeps seeing isCapturingLive and the consent
                    // path ping-pongs into a startForegroundService storm.
                    if (SilentScreenStreamer.isCapturingLive()) {
                        SilentScreenStreamer.stop()
                    }
                    if (ProjectionResultHolder.shouldPrompt(this)) {
                        CaptureConsentActivity.launch(this)
                    }
                    if (!foregroundStarted) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    return START_STICKY
                }
                enterForeground()
                startCapture()
                return START_STICKY
            }
        }
    }

    private fun startCapture() {
        AgentInput.ensureAccessibility(this)
        acquireWakeLock()
        // Streaming (JPEG to the relay) is armed only by screen_monitor_start.
        // Keeping the VirtualDisplay warm without sending avoids a pre-viewer
        // backlog that makes the first remote window look unresponsive.
        SilentScreenStreamer.start(this)
        // Success = a live projection is now driving capture. Use isReady() (a live
        // MediaProjection), NOT isCapturingLive() (streamer running): a stale
        // streamer with a dead projection would otherwise look like success and
        // leave a black session, or feed the consent-retry loop.
        if (ProjectionResultHolder.isReady()) {
            return
        }
        ProjectionResultHolder.clearConsent(this)
        if (ProjectionResultHolder.shouldPrompt(this)) {
            CaptureConsentActivity.launch(this)
        }
    }

    private fun enterForeground() {
        if (foregroundStarted) {
            return
        }
        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (t: Throwable) {
            // Background FGS start restriction (Android 12+) or a media-projection
            // FGS-type requirement can throw here. Since we were started with
            // startForegroundService, not entering the foreground would trip the
            // platform "did not call startForeground in time" crash — stop cleanly
            // instead. Consent-driven starts run in an exempt context, so this is
            // a rare safety net; the next consent/monitor start retries capture.
            try {
                stopSelf()
            } catch (_: Throwable) {
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notification_channel_desc)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_capturing, 0))
            .setSmallIcon(R.drawable.ic_capture)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RemoteScreen:CaptureHold"
            ).also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        SilentScreenStreamer.stop()
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.screencapture.app.action.START"
        const val ACTION_STOP = "com.screencapture.app.action.STOP"
        const val ACTION_ENSURE_READY = "com.screencapture.app.action.ENSURE_PROJECTION"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val app = context.applicationContext
            ProjectionResultHolder.load(app)
            if (ProjectionResultHolder.canCaptureWithoutDialog()) {
                val intent = Intent(app, RemoteScreenService::class.java).setAction(ACTION_START)
                try {
                    ContextCompat.startForegroundService(app, intent)
                } catch (t: Throwable) {
                    try {
                        app.startService(intent)
                    } catch (inner: Throwable) {
                    }
                }
                return
            }
            // Prefs may still say "granted once" after reboot, but the MediaProjection
            // token is gone. A streamer left "live" without a live projection (the
            // RK3588 projection can die right after grant) must be dropped, or this
            // guard used to dispatch FGS on isCapturingLive() while the ACTION_START
            // handler re-launched consent, ping-ponging forever.
            if (SilentScreenStreamer.isCapturingLive()) {
                SilentScreenStreamer.stop()
            }
            if (ProjectionResultHolder.prompting) {
                return
            }
            CaptureConsentActivity.launch(app)
        }

        fun ensureReady(context: Context) {
            start(context)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, RemoteScreenService::class.java).setAction(ACTION_STOP)
            try {
                app.startService(intent)
            } catch (t: Throwable) {
            }
        }
    }
}
