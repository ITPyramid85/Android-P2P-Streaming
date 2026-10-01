package com.screencapture.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import com.genymobile.scrcpy.agent.AgentInput
import com.genymobile.scrcpy.agent.AgentInputService
import com.screencapture.app.remote.RemoteScreenService
import com.screencapture.app.screencapture.HiddenApi
import com.screencapture.app.screencapture.MediaProjectionHelper

/**
 * Shows the system Start Video dialog whenever there is no live MediaProjection
 * token (including after reboot). Keeps this activity alive until the
 * mediaProjection FGS has created the projection — Android 14 rejects
 * getMediaProjection if the granting activity is already gone.
 */
class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var askedCast = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HiddenApi.unseal()
        ServerConfig.setStoppedByHost(this, false)
        applyScreenIdFromLaunchIntent(intent)
        if (ServerConfig.deviceName(this) == null) {
            // Ask before anything else and let nothing else start yet. Both of the
            // steps below take over the whole display — the system cast dialog and
            // the accessibility settings list — and a prompt raised next to them
            // ends up buried where it cannot be answered.
            askDeviceName()
            return
        }
        startup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyScreenIdFromLaunchIntent(intent)
    }

    /**
     * Default app launches us with putExtra("SCREEN_ID", …). Apply it before
     * anything registers so the relay lists this device under that id.
     */
    private fun applyScreenIdFromLaunchIntent(intent: Intent?) {
        val raw = screenIdExtra(intent) ?: return
        if (ServerConfig.applyExternalScreenId(this, raw)) {
            NetworkService.reregister(this)
        } else {
        }
    }

    private fun screenIdExtra(intent: Intent?): String? {
        val extras = intent?.extras ?: return null
        if (!extras.containsKey(EXTRA_SCREEN_ID)) {
            return null
        }
        return when (val value = extras.get(EXTRA_SCREEN_ID)) {
            is String -> value
            is Int -> value.toString()
            is Long -> value.toString()
            else -> value?.toString()
        }
    }

    private fun startup() {
        ProjectionResultHolder.load(this)
        AgentInput.ensureAccessibility(this)
        NetworkService.connect(this, ServerConfig.host(this), ServerConfig.port(this))
        // Input, navigation and hands-free screen-cast consent all require the
        // accessibility service. If it is not bound, give an auto-enabling device a
        // brief moment to bind, then open its settings page so the user can turn it
        // on — that is the screen the user expects when tapping the icon.
        if (AgentInputService.get() != null) {
            proceedWithProjection()
        } else {
            waitForAccessibilityThenProceed()
        }
    }

    /**
     * One-time prompt for the name the desktop lists this device under. Asked only
     * while nothing is stored, so it never appears again after a reboot.
     */
    private fun askDeviceName() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setHint(R.string.device_name_hint)
            // Prefill the build name: on a TV box with only a remote, confirming a
            // sensible default has to be possible without typing.
            setText(ServerConfig.deviceModelId())
            setSelection(0, text.length)
        }
        val pad = (resources.displayMetrics.density * 20f).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.device_name_title)
            .setMessage(R.string.device_name_message)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        // The button is wired after show() so an empty name can keep the dialog up
        // instead of dismissing it, which the Builder's own listener always does.
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun syncEnabled() {
                ok.isEnabled = ServerConfig.sanitizeDeviceName(input.text.toString()) != null
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = syncEnabled()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            syncEnabled()
            ok.setOnClickListener {
                val name = ServerConfig.sanitizeDeviceName(input.text.toString())
                    ?: return@setOnClickListener
                ServerConfig.setDeviceName(this, name)
                dialog.dismiss()
                // A socket opened at process start already registered under the
                // build name, and the name only travels in the register frame.
                NetworkService.reregister(this)
                startup()
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun waitForAccessibilityThenProceed() {
        val deadline = SystemClock.uptimeMillis() + ACCESSIBILITY_WAIT_MS
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing) {
                    return
                }
                if (AgentInputService.get() != null) {
                    proceedWithProjection()
                    return
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    // Settings covers the cast dialog that app startup already raised,
                    // so its result never arrives and nothing clears the prompt guard.
                    // Left latched, every later screen_monitor_start only logged
                    // "waiting for cast prompt" and the desktop sat on the waiting
                    // screen until the guard timed out. Release it here so the kick
                    // that follows accessibility binding can prompt again.
                    ProjectionResultHolder.clearPrompting()
                    AgentInputService.disarmConsentAutoAccept()
                    AgentInput.openAccessibilitySettings(this@MainActivity)
                    finish()
                    overridePendingTransition(0, 0)
                    return
                }
                mainHandler.postDelayed(this, 150)
            }
        }
        mainHandler.post(poll)
    }

    private fun proceedWithProjection() {
        if (ProjectionResultHolder.canCaptureWithoutDialog()) {
            RemoteScreenService.start(this)
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
            // Let the accessibility service confirm the system dialog automatically
            // so capture resumes in the background after reboot.
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
        if (requestCode != REQ_CAPTURE) {
            return
        }
        ProjectionResultHolder.clearPrompting()
        AgentInputService.disarmConsentAutoAccept()
        if (!MediaProjectionHelper.isPermissionGranted(resultCode, data)) {
            askedCast = false
            finishSoon(200)
            return
        }
        ProjectionResultHolder.set(resultCode, data!!, this)
        RemoteScreenService.start(this)
        waitUntilReadyThenFinish()
    }

    private fun waitUntilReadyThenFinish() {
        val deadline = SystemClock.uptimeMillis() + 8000L
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing) {
                    return
                }
                if (ProjectionResultHolder.isReady()) {
                    if (AgentInputService.get() == null) {
                        AgentInput.promptAccessibilitySettings(this@MainActivity)
                    }
                    finish()
                    overridePendingTransition(0, 0)
                    return
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    if (ProjectionResultHolder.isReady()) {
                        finish()
                        overridePendingTransition(0, 0)
                        return
                    }
                    ProjectionResultHolder.clearConsent(this@MainActivity)
                    askedCast = false
                    if (AgentInputService.get() == null) {
                        AgentInput.promptAccessibilitySettings(this@MainActivity)
                    }
                    // Offer Start Video again instead of leaving a dead session.
                    requestCast()
                    return
                }
                if (ProjectionResultHolder.canCaptureWithoutDialog()) {
                    RemoteScreenService.start(this@MainActivity)
                }
                mainHandler.postDelayed(this, 120)
            }
        }
        mainHandler.post(poll)
    }

    private fun finishSoon(delayMs: Long) {
        mainHandler.postDelayed({
            if (!isFinishing) {
                finish()
                overridePendingTransition(0, 0)
            }
        }, delayMs)
    }

    companion object {
        private const val REQ_CAPTURE = 91
        private const val ACCESSIBILITY_WAIT_MS = 1500L
        const val EXTRA_NEED_PROJECTION = "extra_need_projection"
        const val EXTRA_NEED_ACCESSIBILITY = "extra_need_accessibility"
        const val EXTRA_SCREEN_ID = "SCREEN_ID"
    }
}
