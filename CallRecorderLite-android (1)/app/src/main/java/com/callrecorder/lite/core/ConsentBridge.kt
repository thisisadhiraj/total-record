package com.callrecorder.lite.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.callrecorder.lite.model.CallSource
import java.util.concurrent.CopyOnWriteArraySet

object ConsentBridge {

    private const val DEFAULT_TIMEOUT_MS = 5_000L

    @Volatile
    private var armedUntilElapsedMs: Long = 0L

    @Volatile
    var activeRecordingSource: CallSource? = null
        private set

    @Volatile
    var isRecordingActive: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun arm(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        armedUntilElapsedMs = SystemClock.elapsedRealtime() + timeoutMs
    }

    fun disarm() {
        armedUntilElapsedMs = 0L
    }

    fun isArmed(): Boolean {
        return SystemClock.elapsedRealtime() < armedUntilElapsedMs
    }

    fun updateRecordingState(recording: Boolean, source: CallSource?) {
        isRecordingActive = recording
        activeRecordingSource = source
        mainHandler.post {
            listeners.forEach { it.invoke() }
        }
    }

    fun addStateListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeStateListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
