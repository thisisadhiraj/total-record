package com.callrecorder.lite.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.callrecorder.lite.CallRecorderApp
import com.callrecorder.lite.R
import com.callrecorder.lite.audio.DualAudioRecorder
import com.callrecorder.lite.audio.MediaStoreAudioWriter
import com.callrecorder.lite.core.CallStateCoordinator
import com.callrecorder.lite.model.CallSource
import com.callrecorder.lite.ui.MainActivity

class CallRecorderService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioWriter: MediaStoreAudioWriter? = null
    private var dualRecorder: DualAudioRecorder? = null
    private var currentSource: CallSource = CallSource.PHONE
    private var isStopping = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // System or user revoked MediaProjection from status bar; stop projection cleanly
            mediaProjection = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                if (dualRecorder != null) {
                    return START_NOT_STICKY
                }
                val sourceName = intent.getStringExtra(EXTRA_CALL_SOURCE)
                currentSource = sourceName?.let {
                    runCatching { CallSource.valueOf(it) }.getOrNull()
                } ?: CallSource.PHONE

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

                startForegroundAndRecord(resultCode, resultData)
            }

            ACTION_UPDATE_SOURCE -> {
                val sourceName = intent.getStringExtra(EXTRA_CALL_SOURCE)
                val newSource = sourceName?.let {
                    runCatching { CallSource.valueOf(it) }.getOrNull()
                }
                if (newSource != null && newSource != currentSource) {
                    currentSource = newSource
                    audioWriter?.updateSourceName(newSource)
                    updateNotification(newSource)
                }
            }

            ACTION_STOP_RECORDING -> {
                stopRecordingAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundAndRecord(resultCode: Int, resultData: Intent?) {
        val hasProjectionGrant = resultCode == Activity.RESULT_OK && resultData != null
        val notification = buildNotification(currentSource)

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasProjectionGrant) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        } catch (_: SecurityException) {
            // Fallback to microphone-only foreground type if projection grant expired
            val micOnlyType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, micOnlyType)
            } catch (_: SecurityException) {
                CallStateCoordinator.onServiceStoppedUnexpectedly()
                stopSelf()
                return
            }
        }

        if (hasProjectionGrant && resultData != null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = runCatching {
                mpm?.getMediaProjection(resultCode, resultData)?.also { proj ->
                    proj.registerCallback(projectionCallback, mainHandler)
                }
            }.getOrNull()
        }

        val writer = MediaStoreAudioWriter(applicationContext, currentSource)
        val fd = writer.open(currentSource)
        if (fd == null) {
            cleanupProjection()
            CallStateCoordinator.onServiceStoppedUnexpectedly()
            stopSelf()
            return
        }
        audioWriter = writer

        isStopping = false
        dualRecorder = DualAudioRecorder(
            fileDescriptor = fd,
            mediaProjection = mediaProjection
        ) { durationMs, hasAudioData ->
            writer.finalizeOrDiscard(durationMs, hasAudioData)
            mainHandler.post {
                cleanupProjection()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.apply {
            start()
        }
    }

    private fun stopRecordingAndSelf() {
        if (isStopping) return
        isStopping = true

        val recorder = dualRecorder
        dualRecorder = null
        if (recorder != null) {
            // DualAudioRecorder.onFinished handles finalizing MediaStore, stopping foreground, and stopSelf()
            Thread({
                recorder.stop()
            }, "StopRecorderThread").start()
        } else {
            cleanupProjection()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanupProjection() {
        val proj = mediaProjection
        mediaProjection = null
        if (proj != null) {
            runCatching { proj.unregisterCallback(projectionCallback) }
            runCatching { proj.stop() }
        }
    }

    private fun updateNotification(source: CallSource) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(source))
    }

    private fun buildNotification(source: CallSource): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CallRecorderService::class.java).apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CallRecorderApp.CHANNEL_RECORDING_ID)
            .setSmallIcon(R.drawable.ic_mic_record)
            .setContentTitle("Recording ${source.displayName}")
            .setContentText("Saving audio to Music/CallRecordings")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.btn_stop_recording), stopIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isStopping && dualRecorder != null) {
            dualRecorder?.stop()
            dualRecorder = null
            CallStateCoordinator.onServiceStoppedUnexpectedly()
        }
        cleanupProjection()
    }

    companion object {
        const val ACTION_START_RECORDING = "com.callrecorder.lite.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.callrecorder.lite.action.STOP_RECORDING"
        const val ACTION_UPDATE_SOURCE = "com.callrecorder.lite.action.UPDATE_SOURCE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CALL_SOURCE = "extra_call_source"

        private const val NOTIFICATION_ID = 1001
    }
}
