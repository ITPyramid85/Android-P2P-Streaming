package com.screencapture.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.genymobile.scrcpy.agent.AgentInputService
import com.screencapture.app.remote.RemoteScreenService
import com.screencapture.app.screencapture.MediaProjectionHelper

/**
 * Fallback screen-cast prompt when MainActivity is not in the foreground
 * (boot / reconnect). Must not use singleInstance — that drops onActivityResult.
 */
class CaptureConsentActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var askedCast = false
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Proof to launch() that the platform really let this activity through.
        liveInstances.incrementAndGet()
        cancelConsentNotification(applicationContext)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // Already capturing with a live projection — nothing to ask. Finishing
        // immediately prevents the post-reboot launch storm from stacking cast
        // dialogs over a live stream.
        if (ProjectionResultHolder.isReady()) {
            RemoteScreenService.start(applicationContext)
            finishSoon(200)
            return
        }
        if (ProjectionResultHolder.canCaptureWithoutDialog()) {
            RemoteScreenService.start(applicationContext)
            waitUntilReadyThenFinish()
            return
        }
        requestCast()
    }

    private fun requestCast() {
        if (askedCast) {
            return
        }
        askedCast = true
        ProjectionResultHolder.markPrompting()
        try {
            AgentInputService.armConsentAutoAccept()
            startActivityForResult(MediaProjectionHelper.createCaptureIntent(this), REQ_CAPTURE)
        } catch (t: Throwable) {
            ProjectionResultHolder.clearPrompting()
            finishSoon(200)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        resultDelivered = true
        ProjectionResultHolder.clearPrompting()
        AgentInputService.disarmConsentAutoAccept()
        if (requestCode == REQ_CAPTURE && MediaProjectionHelper.isPermissionGranted(resultCode, data)) {
            ProjectionResultHolder.set(resultCode, data!!, this)
            RemoteScreenService.start(applicationContext)
            waitUntilReadyThenFinish()
        } else {
            askedCast = false
            finishSoon(200)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        liveInstances.decrementAndGet()
        // Only the result clears the prompt guard. If this activity dies first — killed
        // while the dialog was up, or the flow abandoned — nobody would clear it and
        // every later capture request stays suppressed until the guard times out.
        if (askedCast && !resultDelivered) {
            ProjectionResultHolder.clearPrompting()
            AgentInputService.disarmConsentAutoAccept()
        }
    }

    private fun waitUntilReadyThenFinish() {
        val deadline = SystemClock.uptimeMillis() + 8000L
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing) {
                    return
                }
                // A live projection is success. A merely-"live" streamer without a
                // projection is stale, not success, so don't count isCapturingLive.
                if (ProjectionResultHolder.isReady()) {
                    finishSoon(400)
                    return
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    // Capture never came up: wipe consent so the next attempt starts
                    // clean instead of trusting a dead "granted once" token.
                    ProjectionResultHolder.clearConsent(this@CaptureConsentActivity)
                    askedCast = false
                    finishSoon(200)
                    return
                }
                // Drive FGS start only when we already hold a usable token. Calling
                // start() without one re-launches this activity every 120 ms.
                if (ProjectionResultHolder.canCaptureWithoutDialog()) {
                    RemoteScreenService.start(applicationContext)
                }
                mainHandler.postDelayed(this, 120)
            }
        }
        mainHandler.post(poll)
    }

    private fun finishSoon(delayMs: Long = 1500) {
        mainHandler.postDelayed({
            if (isFinishing) {
                return@postDelayed
            }
            // moveTaskToBack(true) immediately followed by finish() makes this
            // board's WindowManager NPE inside Task.adjustFocusToNextFocusableTask
            // (there is no focusable task left to hand focus to). The binder
            // rethrows that system exception here and it killed the whole process
            // mid-stream, so finish on its own and never let it be fatal.
            try {
                finish()
                overridePendingTransition(0, 0)
            } catch (t: Throwable) {
                try {
                    moveTaskToBack(true)
                } catch (_: Throwable) {
                }
            }
        }, delayMs)
    }

    companion object {
        private const val REQ_CAPTURE = 91
        private const val REQ_FULL_SCREEN = 92
        /** Minimum gap between consent activity launches to stop stacking storms. */
        private const val LAUNCH_THROTTLE_MS = 2_500L

        /** Long enough for a launch that was allowed to reach onCreate. */
        private const val LAUNCH_VERIFY_MS = 900L

        private const val CONSENT_CHANNEL_ID = "rc_consent_prompt"
        private const val CONSENT_NOTIFICATION_ID = 4711

        @Volatile
        private var lastLaunchAt = 0L

        /** Non-zero while an instance of this activity exists. */
        private val liveInstances = java.util.concurrent.atomic.AtomicInteger(0)

        private val verifyHandler = Handler(Looper.getMainLooper())

        /**
         * Bring an already-outstanding cast dialog back to the front. On a fresh
         * install the prompt is raised while the user is still in the accessibility
         * settings list, so the dialog ends up buried behind Settings: it cannot be
         * confirmed, and because the prompt guard is set, every later capture request
         * is suppressed until that guard times out ~30 s later. Once accessibility is
         * bound we can raise the buried task and let auto-accept answer it.
         */
        @JvmStatic
        fun raiseOutstandingPrompt(context: Context) {
            if (!ProjectionResultHolder.prompting) {
                return
            }
            val intent = Intent(context.applicationContext, CaptureConsentActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        or Intent.FLAG_ACTIVITY_NO_ANIMATION
                        or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            // Only the a11y context can foreground this from a background service, and
            // reordering the existing task resumes the live instance rather than
            // creating a second one, so no duplicate dialog is stacked.
            if (AgentInputService.launchActivity(intent)) {
                lastLaunchAt = SystemClock.elapsedRealtime()
            }
        }

        private fun consentIntent(app: Context): Intent =
            Intent(app, CaptureConsentActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_NO_ANIMATION
                        or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )

        @JvmStatic
        fun launch(context: Context) {
            val app = context.applicationContext
            // Before the user unlocks (direct boot), non-directBootAware components
            // like this activity don't exist yet, so every launch throws
            // ActivityNotFoundException and spams the log. Consent needs an unlocked,
            // interactive user anyway. Skip while locked; the post-unlock kicks
            // (BOOT_COMPLETED, a11y reconnect, consent retries) re-attempt it.
            val um = app.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
            if (um != null && !um.isUserUnlocked) {
                return
            }
            ProjectionResultHolder.load(app)
            if (ProjectionResultHolder.isReady()
                || ProjectionResultHolder.canCaptureWithoutDialog()
            ) {
                RemoteScreenService.start(app)
                return
            }
            if (ProjectionResultHolder.prompting) {
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastLaunchAt < LAUNCH_THROTTLE_MS) {
                return
            }
            lastLaunchAt = now
            val intent = consentIntent(app)
            // Prefer the accessibility service: after a reboot the caller is a
            // background service, and a plain startActivity is blocked on Android
            // 12+. The enabled a11y service can launch it; fall back to the plain
            // context on devices/emulators that still allow background starts.
            if (AgentInputService.launchActivity(intent)) {
                return
            }
            try {
                app.startActivity(intent)
            } catch (t: Throwable) {
                raiseConsentNotification(app)
                return
            }
            // startActivity does NOT throw when Android 12+ refuses a background
            // launch: it returns normally, no activity is ever created, and so the
            // prompt guard is never set — which is why the retry loop hammered this
            // same wall every 3 s for over a minute after every reboot while nothing
            // appeared on screen. Confirm an instance actually came up, and if it did
            // not, escalate to a channel the platform does allow from the background.
            verifyHandler.postDelayed({
                if (liveInstances.get() == 0
                    && !ProjectionResultHolder.isReady()
                    && !ProjectionResultHolder.canCaptureWithoutDialog()
                ) {
                    raiseConsentNotification(app)
                }
            }, LAUNCH_VERIFY_MS)
        }

        /**
         * A full-screen intent is the one way an app with no foreground activity and
         * no bound accessibility service can still put the cast dialog on screen from
         * the background. Where the platform declines to open it full screen it stays
         * a heads-up notification, so the worst case is one tap instead of the user
         * having to find and open the app by hand.
         */
        private fun raiseConsentNotification(app: Context) {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CONSENT_CHANNEL_ID,
                            app.getString(R.string.consent_channel_name),
                            NotificationManager.IMPORTANCE_HIGH
                        )
                    )
                }
                val pending = PendingIntent.getActivity(
                    app,
                    REQ_FULL_SCREEN,
                    consentIntent(app),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(app, CONSENT_CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(app)
                }
                nm.notify(
                    CONSENT_NOTIFICATION_ID,
                    builder
                        .setContentTitle(app.getString(R.string.consent_notification_title))
                        .setContentText(app.getString(R.string.consent_notification_text))
                        .setSmallIcon(R.drawable.ic_capture)
                        .setContentIntent(pending)
                        .setFullScreenIntent(pending, true)
                        .setAutoCancel(true)
                        .build()
                )
            } catch (t: Throwable) {
            }
        }

        private fun cancelConsentNotification(app: Context) {
            try {
                app.getSystemService(NotificationManager::class.java)
                    ?.cancel(CONSENT_NOTIFICATION_ID)
            } catch (_: Throwable) {
            }
        }
    }
}
