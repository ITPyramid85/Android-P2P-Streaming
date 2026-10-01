package com.screencapture.app

import android.app.Application
import com.genymobile.scrcpy.agent.AgentInputService
import com.screencapture.app.remote.RemoteScreenService
import com.screencapture.app.screencapture.HiddenApi

class ClientApp : Application() {
    override fun onCreate() {
        super.onCreate()
        step("screen-id") {
        }
        step("unseal") { HiddenApi.unseal() }
        step("load-consent") { ProjectionResultHolder.load(this) }
        ProjectionResultHolder.setStopListener {
            // Projection died — restart only through the mediaProjection FGS path
            // (or re-prompt). Never call SilentScreenStreamer without a live FGS.
            if (!ServerConfig.isStoppedByHost(this)) {
                RemoteScreenService.start(this)
            }
        }
        // Drop leftover boot/job/alarm auto-start from older installs.
        step("cancel-watchdog") { StartupController.cancelWatchdog(this) }
        AgentInputService.setConnectedListener {
            if (ServerConfig.isStoppedByHost(this)) {
                return@setConnectedListener
            }
            if (ProjectionResultHolder.prompting) {
                CaptureConsentActivity.raiseOutstandingPrompt(this)
                AgentInputService.armConsentAutoAccept()
            }
        }
    }

    // Run a startup step so a failure on any one device/version never takes down
    // the whole app (which would leave the desktop waiting forever).
    private inline fun step(name: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
        }
    }
}
