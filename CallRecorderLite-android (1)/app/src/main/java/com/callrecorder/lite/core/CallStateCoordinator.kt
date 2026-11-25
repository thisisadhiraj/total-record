package com.callrecorder.lite.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.callrecorder.lite.model.CallSource
import com.callrecorder.lite.service.CallRecorderService
import com.callrecorder.lite.ui.MediaProjectionConsentActivity

object CallStateCoordinator {

    private const val SOURCE_UPGRADE_WINDOW_MS = 5_000L

    private val lock = Any()
    private val activeVoipCalls = mutableMapOf<String, CallSource>()

    private var isCellularOffhook = false
    private var suppressCellularUntilIdle = false
    private var isRecording = false
    private var currentSource: CallSource? = null
    private var recordingStartedAtElapsedMs = 0L

    fun onVoipCallStarted(context: Context, notificationKey: String, source: CallSource) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (activeVoipCalls.containsKey(notificationKey)) return
            activeVoipCalls[notificationKey] = source

            if (!isRecording) {
                startRecordingLocked(appContext, source)
            } else if (currentSource == CallSource.PHONE &&
                (SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs) < SOURCE_UPGRADE_WINDOW_MS
            ) {
                // Cellular OFFHOOK often fires milliseconds before a VoIP call notification appears.
                // Upgrade the active recording's label so the file is properly named after the VoIP app.
                currentSource = source
                ConsentBridge.updateRecordingState(true, source)
                val upgradeIntent = Intent(appContext, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_UPDATE_SOURCE
                    putExtra(CallRecorderService.EXTRA_CALL_SOURCE, source.name)
                }
                appContext.startService(upgradeIntent)
            }
        }
    }

    fun onVoipCallEnded(context: Context, notificationKey: String) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (activeVoipCalls.remove(notificationKey) == null) return

            if (activeVoipCalls.isEmpty() && isRecording) {
                if (currentSource != CallSource.PHONE) {
                    if (isCellularOffhook) {
                        suppressCellularUntilIdle = true
                    }
                    stopRecordingLocked(appContext)
                } else if (!isCellularOffhook) {
                    stopRecordingLocked(appContext)
                }
            }
        }
    }

    fun onCellularOffhook(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            isCellularOffhook = true
            if (suppressCellularUntilIdle) return
            if (activeVoipCalls.isNotEmpty() || isRecording) return
            startRecordingLocked(appContext, CallSource.PHONE)
        }
    }

    fun onCellularIdle(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            isCellularOffhook = false
            suppressCellularUntilIdle = false
            if (activeVoipCalls.isEmpty() && isRecording) {
                stopRecordingLocked(appContext)
            }
        }
    }

    fun onManualStopRequested(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            activeVoipCalls.clear()
            suppressCellularUntilIdle = isCellularOffhook
            if (isRecording) {
                stopRecordingLocked(appContext)
            }
        }
    }

    fun onServiceStoppedUnexpectedly() {
        synchronized(lock) {
            isRecording = false
            currentSource = null
            ConsentBridge.disarm()
            ConsentBridge.updateRecordingState(false, null)
        }
    }

    private fun startRecordingLocked(context: Context, source: CallSource) {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) return

        isRecording = true
        currentSource = source
        recordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
        ConsentBridge.updateRecordingState(true, source)

        if (SetupHealthChecker.isAccessibilityServiceEnabled(context)) {
            // Arm the 5-second auto-consent window and request MediaProjection via transparent Activity
            ConsentBridge.arm(5_000L)
            val consentIntent = Intent(context, MediaProjectionConsentActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(CallRecorderService.EXTRA_CALL_SOURCE, source.name)
            }
            try {
                context.startActivity(consentIntent)
            } catch (_: Exception) {
                ConsentBridge.disarm()
                startServiceMicFallback(context, source)
            }
        } else {
            // Accessibility helper is not enabled: immediately record using microphone fallback
            // without interrupting the active call screen with a system dialog.
            startServiceMicFallback(context, source)
        }
    }

    private fun startServiceMicFallback(context: Context, source: CallSource) {
        val serviceIntent = Intent(context, CallRecorderService::class.java).apply {
            action = CallRecorderService.ACTION_START_RECORDING
            putExtra(CallRecorderService.EXTRA_CALL_SOURCE, source.name)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    private fun stopRecordingLocked(context: Context) {
        isRecording = false
        currentSource = null
        ConsentBridge.disarm()
        ConsentBridge.updateRecordingState(false, null)

        val stopIntent = Intent(context, CallRecorderService::class.java).apply {
            action = CallRecorderService.ACTION_STOP_RECORDING
        }
        try {
            context.startService(stopIntent)
        } catch (_: Exception) {
            // Service may not be started yet if stopped during rapid hangup
        }
    }
}
