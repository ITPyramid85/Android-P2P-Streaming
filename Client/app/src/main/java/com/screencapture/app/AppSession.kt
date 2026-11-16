package com.screencapture.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.genymobile.scrcpy.agent.AgentInput
import com.genymobile.scrcpy.agent.AgentInputService
import com.genymobile.scrcpy.agent.SilentScreenStreamer
import com.screencapture.app.net.ServerConnection
import com.screencapture.app.remote.RemoteScreenService

/**
 * Takes the phone online without a screen of its own. The launcher activity
 * calls this and then finishes.
 */
object AppSession {

    fun open(activity: Activity) {
        val app = activity.applicationContext
        ServerConfig.setStoppedByHost(app, false)
        AgentInput.ensureAccessibility(app)
        ProjectionResultHolder.load(app)
        if (ProjectionResultHolder.canCaptureWithoutDialog()) {
            goOnline(app)
            // Cast is already granted, so the settings page is the only thing left
            // that has to be on screen.
            if (AgentInputService.get() == null) {
                AgentInput.openAccessibilitySettings(activity)
            }
            return
        }
        // Start the system cast dialog from this foreground activity. Launching it
        // from the application after the activity has finished is blocked, and
        // without capture the relay never lists the phone.
        activity.startActivity(
            Intent(activity, CaptureConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    fun goOnline(context: Context) {
        val app = context.applicationContext
        ServerConfig.setStoppedByHost(app, false)
        if (!ShareSessionState.sharing) {
            ServerConnection.DeviceInfo.newShare()
        }
        ShareSessionState.markSharing(app, true)
        NetworkService.connect(app, ServerConfig.host(app), ServerConfig.port(app))
        if (!SilentScreenStreamer.isCapturingLive()) {
            RemoteScreenService.start(app)
        }
        ServerConfig.markSetupComplete(app)
    }
}
