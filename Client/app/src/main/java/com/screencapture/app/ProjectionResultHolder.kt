package com.screencapture.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.screencapture.app.screencapture.MediaProjectionHelper

/**
 * One-shot MediaProjection consent for the process lifetime.
 *
 * Never copy the result Intent. Never stop MediaProjection on normal screen-stop.
 */
object ProjectionResultHolder {

    private const val PREFS = "projection_consent"
    private const val KEY_GRANTED = "granted_once"

    // A consent activity can be killed by the system before it ever delivers a
    // result (or clears the flag), which would otherwise leave `prompting` stuck
    // true for the whole process and permanently suppress "Start Video". Treat a
    // prompt as abandoned after this window so re-consent can be requested again.
    private const val PROMPT_TIMEOUT_MS = 30_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var resultCode: Int = 0
        private set

    @Volatile
    var data: Intent? = null
        private set

    @Volatile
    private var mediaProjection: MediaProjection? = null

    // Generation of the live MediaProjection. Creating a new projection makes some
    // platforms (RK3588 / Android 12) stop the previous one, and that stop callback
    // lands a few ms AFTER the new projection is already cached. A single shared,
    // identity-blind callback then wiped the brand-new projection's state, so every
    // recovery attempt died ~20 ms after "streamer prepared" and the app looped
    // through consent forever. Each projection now carries its own epoch and a
    // callback only clears state while it still owns the current generation.
    @Volatile
    private var projectionEpoch = 0

    @Volatile
    private var activeCallback: MediaProjection.Callback? = null

    @Volatile
    private var tokenConsumed = false

    @Volatile
    private var stopListener: (() -> Unit)? = null

    @Volatile
    private var grantedOnce = false

    @Volatile
    private var promptingFlag: Boolean = false

    @Volatile
    private var promptingSince: Long = 0L

    /** True only while a consent prompt is genuinely in flight (self-heals). */
    val prompting: Boolean
        get() {
            if (!promptingFlag) return false
            if (SystemClock.elapsedRealtime() - promptingSince > PROMPT_TIMEOUT_MS) {
                promptingFlag = false
                return false
            }
            return true
        }

    private fun newProjectionCallback(epoch: Int) = object : MediaProjection.Callback() {
        override fun onStop() {
            if (epoch != projectionEpoch) {
                // Late stop from a projection we already replaced. Acting on it
                // would null out the live projection and restart the consent loop.
                return
            }
            projectionEpoch++
            activeCallback = null
            mediaProjection = null
            resultCode = 0
            data = null
            tokenConsumed = false
            stopListener?.invoke()
        }
    }

    @JvmStatic
    fun load(context: Context) {
        if (prefs(context).getBoolean(KEY_GRANTED, false)) {
            grantedOnce = true
        }
    }

    @JvmStatic
    fun wasGranted(context: Context): Boolean {
        load(context)
        return grantedOnce || canCaptureWithoutDialog()
    }

    @JvmStatic
    fun shouldPrompt(context: Context): Boolean {
        // A MediaProjection token dies with the process, so a persisted "granted
        // once" flag cannot revive capture after a reboot or process restart.
        // Prompt whenever there is neither a live projection nor a still-valid
        // token; grantedOnce must not suppress re-consent once the token is gone.
        if (prompting || canCaptureWithoutDialog()) {
            return false
        }
        return true
    }

    @JvmStatic
    fun markPrompting() {
        promptingFlag = true
        promptingSince = SystemClock.elapsedRealtime()
    }

    @JvmStatic
    fun clearPrompting() {
        promptingFlag = false
    }

    private fun prefs(context: Context): SharedPreferences {
        // Must survive direct boot: this is read on LOCKED_BOOT_COMPLETED before the
        // user unlocks, when credential-encrypted storage is unavailable and a plain
        // getSharedPreferences throws IllegalStateException (crashing the app on
        // every reboot). Device-protected storage is always available, matching
        // ServerConfig. The consent token itself dies with the process anyway, so
        // the persisted "granted once" hint living in DE storage is fine.
        val app = context.applicationContext
        val ctx = try {
            app.createDeviceProtectedStorageContext()
        } catch (_: Throwable) {
            app
        }
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun setStopListener(listener: (() -> Unit)?) {
        stopListener = listener
    }

    fun set(resultCode: Int, data: Intent, context: Context? = null) {
        // A second consent grant while a live MediaProjection is already driving
        // capture must NOT tear the old one down. Doing so (shutdownProjectionOnly)
        // leaves SilentScreenStreamer holding a dead VirtualDisplay that it still
        // thinks is "already capturing", after which RemoteScreenService clears the
        // brand-new consent and the app enters an infinite cast-prompt loop —
        // exactly the "works after install, broken after reboot / restart" failure.
        if (mediaProjection != null) {
            promptingFlag = false
            grantedOnce = true
            context?.let {
                prefs(it).edit().putBoolean(KEY_GRANTED, true).apply()
            }
            return
        }
        this.resultCode = resultCode
        this.data = data
        this.tokenConsumed = false
        promptingFlag = false
        grantedOnce = true
        context?.let {
            prefs(it).edit().putBoolean(KEY_GRANTED, true).apply()
        }
    }

    @JvmStatic
    fun hasConsent(): Boolean {
        return data != null && resultCode == Activity.RESULT_OK
    }

    @JvmStatic
    fun isReady(): Boolean = mediaProjection != null

    @JvmStatic
    fun canCaptureWithoutDialog(): Boolean {
        if (mediaProjection != null) return true
        return hasConsent() && !tokenConsumed
    }

    @JvmStatic
    @Synchronized
    fun obtainProjection(serviceContext: Context): MediaProjection? {
        mediaProjection?.let { return it }
        val token = data
        if (token == null || resultCode != Activity.RESULT_OK) {
            return null
        }
        if (tokenConsumed) {
            return mediaProjection
        }
        return try {
            val projection = MediaProjectionHelper.getManager(serviceContext)
                .getMediaProjection(resultCode, token)
            if (projection == null) {
                return null
            }
            val epoch = ++projectionEpoch
            val callback = newProjectionCallback(epoch)
            projection.registerCallback(callback, mainHandler)
            activeCallback = callback
            mediaProjection = projection
            tokenConsumed = true
            grantedOnce = true
            projection
        } catch (t: Throwable) {
            null
        }
    }

    /** Drop a dead/invalid token so [shouldPrompt] shows Start Video again. */
    @JvmStatic
    @Synchronized
    fun clearConsent(context: Context? = null) {
        shutdownProjectionOnly()
        resultCode = 0
        data = null
        tokenConsumed = false
        promptingFlag = false
        grantedOnce = false
        // The persisted flag outlives the process; wipe it too so a stale
        // "granted once" cannot suppress the Start Video prompt after reboot.
        context?.let {
            try {
                prefs(it).edit().putBoolean(KEY_GRANTED, false).apply()
            } catch (_: Throwable) {
            }
        }
    }

    private fun shutdownProjectionOnly() {
        // Bump first: any stop callback already queued for this projection must be
        // treated as stale so it cannot clobber a projection created right after.
        projectionEpoch++
        val callback = activeCallback
        try {
            if (callback != null) {
                mediaProjection?.unregisterCallback(callback)
            }
        } catch (_: Throwable) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        activeCallback = null
        mediaProjection = null
    }
}
