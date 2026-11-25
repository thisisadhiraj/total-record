package com.callrecorder.lite

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class CallRecorderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_RECORDING_ID,
            "Call Recording Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when a cellular or VoIP call is actively being recorded."
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RECORDING_ID = "call_recording_active"
    }
}
