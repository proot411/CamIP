package com.camip.app.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.camip.app.service.StreamService

/**
 * Trampoline for Android's mandatory screen-capture consent dialog.
 *
 * MediaProjection consent can only be requested from an Activity, and the
 * resulting (resultCode, Intent) token has to reach StreamService while the
 * user's decision is still fresh. This activity shows no UI of its own — it
 * launches the system prompt, forwards an "OK" result to the service
 * ([StreamService.ACTION_START_SCREEN]) and finishes, so it is used by both
 * the app's "Screen share" button and the dashboard's API-triggered path.
 */
class ScreenConsentActivity : ComponentActivity() {

    private val requestConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startService(
                Intent(this, StreamService::class.java).apply {
                    action = StreamService.ACTION_START_SCREEN
                    putExtra(StreamService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(StreamService.EXTRA_RESULT_DATA, data)
                }
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        requestConsent.launch(manager.createScreenCaptureIntent())
    }
}
