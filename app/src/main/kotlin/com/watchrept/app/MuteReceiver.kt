package com.watchrept.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the notification's mute actions: silences that channel/DM, or the whole server. */
class MuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(Notifier.EXTRA_CHANNEL_ID) ?: return
        val guildId = intent.getStringExtra(Notifier.EXTRA_GUILD_ID)
        if (intent.getBooleanExtra(Notifier.EXTRA_MUTE_SERVER, false) && guildId != null) {
            if (guildId !in SetupPreferences.getMutedServers(context)) {
                SetupPreferences.toggleMutedServer(context, guildId)
            }
        } else {
            SetupPreferences.muteChannel(context, channelId)
        }
        Notifier.clear(context, channelId)
    }
}
