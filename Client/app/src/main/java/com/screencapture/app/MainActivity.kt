package com.screencapture.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.screencapture.app.screencapture.HiddenApi

/**
 * Launcher with no window. [android.R.style.Theme_NoDisplay] crashes unless
 * finish() runs before onResume, so this activity never waits around for a
 * result. It hands the system cast dialog to [CaptureConsentActivity], which
 * is what actually puts the phone on the relay once capture is granted.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HiddenApi.unseal()
        applyScreenIdFromLaunchIntent(intent)
        AppSession.open(this)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyScreenIdFromLaunchIntent(intent)
    }

    private fun applyScreenIdFromLaunchIntent(intent: Intent?) {
        val raw = screenIdExtra(intent) ?: return
        if (ServerConfig.applyExternalScreenId(this, raw)) {
            NetworkService.reregister(this)
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

    companion object {
        const val EXTRA_NEED_PROJECTION = "extra_need_projection"
        const val EXTRA_NEED_ACCESSIBILITY = "extra_need_accessibility"
        const val EXTRA_SCREEN_ID = "SCREEN_ID"
    }
}
