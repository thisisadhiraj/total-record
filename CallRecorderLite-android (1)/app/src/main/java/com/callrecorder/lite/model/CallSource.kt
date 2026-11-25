package com.callrecorder.lite.model

import com.callrecorder.lite.R

enum class CallSource(
    val displayName: String,
    val filePrefix: String,
    val packageName: String?,
    val iconRes: Int
) {
    PHONE("Phone Call", "Phone", null, R.drawable.ic_phone_call),
    WHATSAPP("WhatsApp", "WhatsApp", "com.whatsapp", R.drawable.ic_voip_whatsapp),
    WHATSAPP_BUSINESS("WhatsApp Business", "WhatsAppBusiness", "com.whatsapp.w4b", R.drawable.ic_voip_whatsapp),
    TELEGRAM("Telegram", "Telegram", "org.telegram.messenger", R.drawable.ic_voip_telegram),
    MESSENGER("Messenger", "Messenger", "com.facebook.orca", R.drawable.ic_voip_messenger);

    companion object {
        fun fromPackage(pkg: String?): CallSource? {
            if (pkg.isNullOrBlank()) return null
            return entries.firstOrNull { it.packageName == pkg }
        }

        fun fromFileName(fileName: String): CallSource {
            return when {
                fileName.startsWith("WhatsAppBusiness_") -> WHATSAPP_BUSINESS
                fileName.startsWith("WhatsApp_") -> WHATSAPP
                fileName.startsWith("Telegram_") -> TELEGRAM
                fileName.startsWith("Messenger_") -> MESSENGER
                else -> PHONE
            }
        }
    }
}
