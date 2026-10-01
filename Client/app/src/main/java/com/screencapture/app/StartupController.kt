package com.screencapture.app

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.genymobile.scrcpy.agent.AgentInput
/**
 * Starts NetworkService after boot, unlock, job ticks, and process start.
 * Auto-start is disabled: only an explicit MainActivity launch (icon or
 * default-app Intent) connects and captures.
 */
object StartupController {
    private const val AUTO_START_ENABLED = false
    private const val JOB_PERIODIC = 1101
    private const val JOB_BOOT = 1102
    private const val CONSENT_RETRY_INTERVAL_MS = 3_000L
    private const val CONSENT_RETRY_MAX_INTERVAL_MS = 30_000L
    /**
     * Consent can legitimately take a while after a reboot: the accessibility service
     * has to bind before the dialog can be raised, and until then the only route is a
     * notification the user has to notice. Giving up after 30 s left the phone with no
     * capture token and the desktop on a waiting screen for good.
     */
    private const val CONSENT_RETRY_WINDOW_MS = 5 * 60_000L

    fun kick(context: Context, reason: String) {
        if (!AUTO_START_ENABLED) {
            return
        }
        val app = context.applicationContext
        if (ServerConfig.isStoppedByHost(app)) {
            return
        }
        if (!ServerConfig.isConfigured(app)) {
            return
        }
        try {
            AgentInput.ensureAccessibility(app)
            NetworkService.connect(app, ServerConfig.host(app), ServerConfig.port(app))
            ensureProjectionConsent(app)
        } catch (t: Throwable) {
            scheduleRetryAlarms(app)
        }
    }

    /**
     * After a reboot the MediaProjection token is gone, so capture cannot start
     * until the user re-grants it. To keep the app fully hands-free, proactively
     * launch the consent flow (armed for auto-accept by the accessibility service)
     * so the token is re-acquired without anyone opening the app or tapping "Start
     * Video". If capture is already live this is a no-op. The retry alarms re-run
     * kick, so a first attempt made before the a11y service has bound is retried.
     */
    private fun ensureProjectionConsent(app: Context) {
        ProjectionResultHolder.load(app)
        if (ProjectionResultHolder.canCaptureWithoutDialog()) {
            return
        }
        if (ProjectionResultHolder.prompting) {
            return
        }
        CaptureConsentActivity.launch(app)
        scheduleConsentRetries(app)
    }

    /**
     * The first background consent attempt can be missed on a fresh install (the
     * dialog draws with no further a11y events, or the background-launched activity
     * never comes to the foreground), which left the user having to open the app by
     * hand to get the first frame. Re-launch the consent flow every few seconds
     * until a live token is acquired, bounded so it can't loop forever. The prompt
     * guard and canCaptureWithoutDialog check keep this from stacking dialogs.
     */
    private val consentRetryHandler = Handler(Looper.getMainLooper())
    private var consentRetry: Runnable? = null

    private fun scheduleConsentRetries(app: Context) {
        consentRetry?.let { consentRetryHandler.removeCallbacks(it) }
        val deadline = SystemClock.elapsedRealtime() + CONSENT_RETRY_WINDOW_MS
        val r = object : Runnable {
            private var interval = CONSENT_RETRY_INTERVAL_MS

            override fun run() {
                ProjectionResultHolder.load(app)
                if (ProjectionResultHolder.canCaptureWithoutDialog()) {
                    return
                }
                if (SystemClock.elapsedRealtime() > deadline) {
                    return
                }
                if (!ProjectionResultHolder.prompting) {
                    CaptureConsentActivity.launch(app)
                }
                // Back off: while the dialog cannot be raised at all, retrying every
                // 3 s only fills the log — the notification raised on the first
                // blocked attempt is what the user acts on.
                consentRetryHandler.postDelayed(this, interval)
                interval = minOf(CONSENT_RETRY_MAX_INTERVAL_MS, interval * 2)
            }
        }
        consentRetry = r
        consentRetryHandler.postDelayed(r, CONSENT_RETRY_INTERVAL_MS)
    }

    fun cancelWatchdog(context: Context) {
        consentRetry?.let { consentRetryHandler.removeCallbacks(it) }
        consentRetry = null
        val app = context.applicationContext
        try {
            app.getSystemService(JobScheduler::class.java)?.apply {
                cancel(JOB_PERIODIC)
                cancel(JOB_BOOT)
            }
        } catch (_: Throwable) {
        }
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        for (index in 1..3) {
            val intent = Intent(app, RestartReceiver::class.java).setAction(RestartReceiver.ACTION_RETRY)
            val pi = PendingIntent.getBroadcast(
                app,
                index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.cancel(pi)
            } catch (_: Throwable) {
            }
        }
    }

    fun scheduleWatchdog(context: Context) {
        if (!AUTO_START_ENABLED) {
            cancelWatchdog(context)
            return
        }
        val app = context.applicationContext
        if (ServerConfig.isStoppedByHost(app)) return
        if (!ServerConfig.isConfigured(app)) return
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(app, KeepAliveJobService::class.java)
        try {
            scheduler.schedule(
                JobInfo.Builder(JOB_PERIODIC, component)
                    .setPersisted(true)
                    .setPeriodic(15 * 60 * 1000L)
                    .build()
            )
            scheduler.schedule(
                JobInfo.Builder(JOB_BOOT, component)
                    .setPersisted(true)
                    .setMinimumLatency(15_000L)
                    .setOverrideDeadline(60_000L)
                    .build()
            )
        } catch (t: Throwable) {
        }
    }

    fun scheduleRetryAlarms(context: Context) {
        if (!AUTO_START_ENABLED) return
        val app = context.applicationContext
        if (ServerConfig.isStoppedByHost(app)) return
        if (!ServerConfig.isConfigured(app)) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val delays = longArrayOf(8_000L, 25_000L, 60_000L)
        delays.forEachIndexed { index, delayMs ->
            val intent = Intent(app, RestartReceiver::class.java).setAction(RestartReceiver.ACTION_RETRY)
            val pi = PendingIntent.getBroadcast(
                app,
                index + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    pi
                )
            } catch (t: Throwable) {
            }
        }
    }

    fun requestUnrestrictedBackground(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (ServerConfig.batteryPromptShown(activity)) return
        val pm = activity.getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) {
            ServerConfig.markBatteryPromptShown(activity)
            return
        }
        ServerConfig.markBatteryPromptShown(activity)
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
        } catch (_: Throwable) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
            }
        }
    }
}
