package com.example.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.FocusManager

class FocusNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Check if Focus mode is active
        if (FocusManager.isFocusActive.value) {
            val packageName = sbn.packageName
            // If the notification belongs to a blocked package, mute/cancel it!
            if (FocusBlockerAccessibilityService.BLOCKED_PACKAGES.contains(packageName)) {
                Log.d(TAG, "Dismissing notification from: $packageName")
                cancelNotification(sbn.key)
                FocusManager.incrementNotificationsMuted(applicationContext)
            }
        }
    }

    companion object {
        private const val TAG = "NotifListenerBlocker"
    }
}
