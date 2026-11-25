package com.callrecorder.lite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.callrecorder.lite.core.CallStateCoordinator

class PhoneCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                CallStateCoordinator.onCellularOffhook(context.applicationContext)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallStateCoordinator.onCellularIdle(context.applicationContext)
            }
        }
    }
}
