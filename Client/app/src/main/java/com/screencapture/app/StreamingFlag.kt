package com.screencapture.app


/**
 * Desktop-controlled latch for JPEG forwarding.
 *
 * Only a RemoteController [screen_monitor_start] turns this on, and only
 * [screen_monitor_stop] (or a dead socket) turns it off. Capture can stay
 * warm while the flag is off so the next start does not need a new consent.
 */
object StreamingFlag {
    @Volatile
    private var on: Boolean = false

    @JvmStatic
    fun isOn(): Boolean = on

    @JvmStatic
    fun setOn(enabled: Boolean) {
        if (on == enabled) {
            return
        }
        on = enabled
    }
}
