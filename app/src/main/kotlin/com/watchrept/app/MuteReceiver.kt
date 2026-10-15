package com.watchrept.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the notification's "Mute" action: silences that channel or DM until muted channels are cleared. */
class MuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(Notifier.EXTRA_CHANNEL_ID) ?: return
        SetupPreferences.muteChannel(context, channelId)
        Notifier.clear(context, channelId)
    }
}
