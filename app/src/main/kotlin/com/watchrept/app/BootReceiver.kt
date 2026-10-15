package com.watchrept.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the notification service after the watch reboots, if the user turned notifications on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (SetupPreferences.getNotificationsEnabled(context) && SetupPreferences.getToken(context) != null) {
            NotificationService.start(context)
        }
    }
}
