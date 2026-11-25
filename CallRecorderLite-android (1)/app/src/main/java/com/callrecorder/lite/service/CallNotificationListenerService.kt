package com.callrecorder.lite.service

import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.callrecorder.lite.core.CallStateCoordinator
import com.callrecorder.lite.model.CallSource
import java.util.concurrent.ConcurrentHashMap

class CallNotificationListenerService : NotificationListenerService() {

    private val activeVoipKeys = ConcurrentHashMap.newKeySet<String>()

    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var legacyPhoneStateListener: PhoneStateListener? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerCellularCallObserver()
        scanExistingNotifications()
    }

    override fun onListenerDisconnected() {
        unregisterCellularCallObserver()
        activeVoipKeys.clear()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregisterCellularCallObserver()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val source = CallSource.fromPackage(sbn.packageName) ?: return

        if (isActiveOngoingCall(sbn)) {
            activeVoipKeys.add(sbn.key)
            CallStateCoordinator.onVoipCallStarted(applicationContext, sbn.key, source)
        } else if (activeVoipKeys.remove(sbn.key)) {
            // Notification updated in-place to "Call ended" or "Missed call"
            CallStateCoordinator.onVoipCallEnded(applicationContext, sbn.key)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (CallSource.fromPackage(sbn.packageName) == null) return

        if (activeVoipKeys.remove(sbn.key)) {
            CallStateCoordinator.onVoipCallEnded(applicationContext, sbn.key)
        }
    }

    private fun scanExistingNotifications() {
        val current = runCatching { activeNotifications }.getOrNull() ?: return
        for (sbn in current) {
            onNotificationPosted(sbn)
        }
    }

    private fun isActiveOngoingCall(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false

        val isOngoing = sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!isOngoing) return false

        val extras = notification.extras
        val isCallCategory = notification.category == Notification.CATEGORY_CALL
        val usesChronometer = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true
        val template = extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val isCallStyle = template.contains("CallStyle", ignoreCase = true)

        if (!isCallCategory && !usesChronometer && !isCallStyle) {
            return false
        }

        // Android 12+ CallStyle explicitly distinguishes INCOMING (1) vs ONGOING (2)
        val callType = extras?.getInt("android.callType", -1) ?: -1
        if (callType == 1) {
            return false
        }
        if (callType == 2 || usesChronometer) {
            return true
        }

        // Fallback inspection of action button labels for API 29-30 or custom VoIP notifications
        val actionTitles = notification.actions
            ?.mapNotNull { it.title?.toString()?.lowercase() }
            .orEmpty()

        val looksLikeIncomingRing = actionTitles.any {
            it.contains("answer") || it.contains("accept")
        } && actionTitles.any {
            it.contains("decline") || it.contains("reject")
        }
        if (looksLikeIncomingRing) return false

        val hasOngoingCallAction = actionTitles.any {
            it.contains("hang up") || it.contains("end call") || it.contains("mute") || it.contains("speaker")
        }

        return isCallCategory && (hasOngoingCallAction || actionTitles.size <= 2)
    }

    private fun registerCellularCallObserver() {
        val hasPhoneStatePerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneStatePerm) return

        val tm = getSystemService(TelephonyManager::class.java) ?: return
        telephonyManager = tm

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerApi31TelephonyCallback(tm)
            } else {
                registerLegacyPhoneListener(tm)
            }
        } catch (_: SecurityException) {
            // Ignored if permission was revoked concurrently
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31TelephonyCallback(tm: TelephonyManager) {
        if (telephonyCallback != null) return
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCellularState(state)
            }
        }
        tm.registerTelephonyCallback(mainExecutor, cb)
        telephonyCallback = cb
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyPhoneListener(tm: TelephonyManager) {
        if (legacyPhoneStateListener != null) return
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCellularState(state)
            }
        }
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacyPhoneStateListener = listener
    }

    private fun handleCellularState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                CallStateCoordinator.onCellularOffhook(applicationContext)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                CallStateCoordinator.onCellularIdle(applicationContext)
            }
        }
    }

    private fun unregisterCellularCallObserver() {
        val tm = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = telephonyCallback as? TelephonyCallback
            if (cb != null) {
                runCatching { tm.unregisterTelephonyCallback(cb) }
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            val listener = legacyPhoneStateListener
            if (listener != null) {
                @Suppress("DEPRECATION")
                runCatching { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
            }
            legacyPhoneStateListener = null
        }
    }
}
