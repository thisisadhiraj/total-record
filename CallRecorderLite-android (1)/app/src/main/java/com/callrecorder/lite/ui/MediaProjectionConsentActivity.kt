package com.callrecorder.lite.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.callrecorder.lite.core.ConsentBridge
import com.callrecorder.lite.model.CallSource
import com.callrecorder.lite.service.CallRecorderService

class MediaProjectionConsentActivity : AppCompatActivity() {

    private var completed = false
    private var callSourceName: String = CallSource.PHONE.name
    private val mainHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = Runnable {
        if (!completed) {
            completed = true
            ConsentBridge.disarm()
            startRecordingService(Activity.RESULT_CANCELED, null)
            finishSilently()
        }
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (completed) return@registerForActivityResult
        completed = true
        mainHandler.removeCallbacks(watchdogRunnable)
        ConsentBridge.disarm()

        startRecordingService(result.resultCode, result.data)
        finishSilently()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callSourceName = intent?.getStringExtra(CallRecorderService.EXTRA_CALL_SOURCE)
            ?: CallSource.PHONE.name

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        if (projectionManager == null) {
            ConsentBridge.disarm()
            startRecordingService(Activity.RESULT_CANCELED, null)
            finishSilently()
            return
        }

        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }

        // Re-arm for 5 seconds right before launching the system dialog
        ConsentBridge.arm(CONSENT_TIMEOUT_MS)
        mainHandler.postDelayed(watchdogRunnable, CONSENT_TIMEOUT_MS)

        try {
            consentLauncher.launch(captureIntent)
        } catch (_: Exception) {
            completed = true
            mainHandler.removeCallbacks(watchdogRunnable)
            ConsentBridge.disarm()
            startRecordingService(Activity.RESULT_CANCELED, null)
            finishSilently()
        }
    }

    private fun startRecordingService(resultCode: Int, data: Intent?) {
        val serviceIntent = Intent(this, CallRecorderService::class.java).apply {
            action = CallRecorderService.ACTION_START_RECORDING
            putExtra(CallRecorderService.EXTRA_CALL_SOURCE, callSourceName)
            if (resultCode == Activity.RESULT_OK && data != null) {
                putExtra(CallRecorderService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CallRecorderService.EXTRA_RESULT_DATA, data)
            }
        }
        ContextCompat.startForegroundService(applicationContext, serviceIntent)
    }

    private fun finishSilently() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(watchdogRunnable)
        super.onDestroy()
    }

    companion object {
        private const val CONSENT_TIMEOUT_MS = 5_000L
    }
}
