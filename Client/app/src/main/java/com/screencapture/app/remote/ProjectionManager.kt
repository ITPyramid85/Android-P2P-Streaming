package com.screencapture.app.remote

import android.content.Context
import android.media.projection.MediaProjection
import com.screencapture.app.ProjectionResultHolder

/** Owns the MediaProjection token for the remote-screen pipeline. */
class ProjectionManager(private val context: Context) {
    fun hasConsent(): Boolean =
        ProjectionResultHolder.canCaptureWithoutDialog() || ProjectionResultHolder.hasConsent()

    fun obtain(): MediaProjection? = ProjectionResultHolder.obtainProjection(context)
}
