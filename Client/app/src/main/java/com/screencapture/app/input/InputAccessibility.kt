package com.screencapture.app.input

import com.genymobile.scrcpy.agent.AgentInputService

/**
 * Accessibility service entry used in the manifest.
 * Some Android 14 OEM settings (ZX5M) only list services whose class lives
 * in the app namespace, and only if the label is InputAccessibility.
 */
class InputAccessibility : AgentInputService()
