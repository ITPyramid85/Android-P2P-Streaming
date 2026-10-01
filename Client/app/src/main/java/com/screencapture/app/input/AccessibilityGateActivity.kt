package com.screencapture.app.input

import android.app.Activity
import android.os.Bundle
import com.genymobile.scrcpy.agent.AgentInput
import com.genymobile.scrcpy.agent.AgentInputService

/**
 * Shown when ZX5M / OEM accessibility settings open this service's gear icon.
 */
class AccessibilityGateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AgentInputService.get() == null) {
            AgentInput.promptAccessibilitySettings(this)
        }
        finish()
    }
}
