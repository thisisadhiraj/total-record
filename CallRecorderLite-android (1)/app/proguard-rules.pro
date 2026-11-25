# Keep ViewBinding classes
-keepclassmembers class * extends androidx.viewbinding.ViewBinding {
    public static * inflate(...);
}

# Keep AccessibilityService, NotificationListenerService, and BroadcastReceiver entry points
-keep public class com.callrecorder.lite.service.** { *; }
-keep public class com.callrecorder.lite.receiver.** { *; }
