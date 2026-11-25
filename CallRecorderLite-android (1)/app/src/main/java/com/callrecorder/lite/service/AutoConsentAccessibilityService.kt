package com.callrecorder.lite.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.callrecorder.lite.core.ConsentBridge

class AutoConsentAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable {
        if (ConsentBridge.isArmed()) {
            tryClickConsentDialog()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ConsentBridge.isArmed()) return
        if (event?.packageName?.toString() != SYSTEM_UI_PACKAGE) return

        if (!tryClickConsentDialog()) {
            // SystemUI dialog buttons may finish inflating a frame after TYPE_WINDOW_STATE_CHANGED
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, 150L)
        }
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacks(retryRunnable)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(retryRunnable)
        super.onDestroy()
    }

    private fun tryClickConsentDialog(): Boolean {
        if (!ConsentBridge.isArmed()) return false
        val root = rootInActiveWindow ?: return false

        try {
            // 1. Check known SystemUI MediaProjection positive button resource IDs
            for (viewId in TARGET_VIEW_IDS) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        if (clickNodeOrClickableParent(node)) {
                            ConsentBridge.disarm()
                            mainHandler.removeCallbacks(retryRunnable)
                            return true
                        }
                    }
                }
            }

            // 2. Fallback: traverse tree for positive confirmation button labels
            val matchedNode = findPositiveButtonByText(root)
            if (matchedNode != null && clickNodeOrClickableParent(matchedNode)) {
                ConsentBridge.disarm()
                mainHandler.removeCallbacks(retryRunnable)
                return true
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
        return false
    }

    private fun findPositiveButtonByText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()?.lowercase()
        if (!text.isNullOrEmpty() && text in POSITIVE_BUTTON_TEXTS) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findPositiveButtonByText(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isEnabled && current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            current = current.parent
            depth++
        }
        return false
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        private val TARGET_VIEW_IDS = listOf(
            "com.android.systemui:id/media_projection_dialog_button",
            "android:id/button1"
        )

        private val POSITIVE_BUTTON_TEXTS = setOf(
            "start now",
            "start recording",
            "share screen",
            "accept",
            "allow",
            "start"
        )
    }
}
