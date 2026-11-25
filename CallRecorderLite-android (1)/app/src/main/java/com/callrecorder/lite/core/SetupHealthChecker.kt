package com.callrecorder.lite.core

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.callrecorder.lite.service.AutoConsentAccessibilityService
import com.callrecorder.lite.service.CallNotificationListenerService

object SetupHealthChecker {

    fun requiredRuntimePermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.toTypedArray()
    }

    fun hasBasicPermissions(context: Context): Boolean {
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val phoneGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return audioGranted && phoneGranted && notifGranted
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        if (NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            return true
        }
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val expected = ComponentName(context, CallNotificationListenerService::class.java).flattenToString()
        val shortName = "${context.packageName}/.${CallNotificationListenerService::class.java.simpleName}"
        return flat.split(':').any { it.equals(expected, ignoreCase = true) || it.equals(shortName, ignoreCase = true) }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledServices = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (enabledServices != null) {
            val matched = enabledServices.any { info ->
                val si = info.resolveInfo?.serviceInfo ?: return@any false
                si.packageName == context.packageName && si.name == AutoConsentAccessibilityService::class.java.name
            }
            if (matched) return true
        }

        val enabledSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected = ComponentName(context, AutoConsentAccessibilityService::class.java).flattenToString()
        return enabledSetting.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isAllSetupComplete(context: Context): Boolean {
        return hasBasicPermissions(context) &&
            isNotificationListenerEnabled(context) &&
            isAccessibilityServiceEnabled(context) &&
            isIgnoringBatteryOptimizations(context)
    }
}
