package com.screencapture.app.screencapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

/** Helper around [MediaProjectionManager] for requesting screen-capture consent. */
object MediaProjectionHelper {
    fun getManager(context: Context): MediaProjectionManager {
        return context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    fun createCaptureIntent(context: Context): Intent {
        return getManager(context).createScreenCaptureIntent()
    }

    fun isPermissionGranted(resultCode: Int, data: Intent?): Boolean {
        return resultCode == Activity.RESULT_OK && data != null
    }
}
