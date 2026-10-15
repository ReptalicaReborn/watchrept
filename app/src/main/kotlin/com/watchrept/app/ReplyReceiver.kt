package com.watchrept.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Sends the text typed or dictated into a notification's inline reply. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(Notifier.EXTRA_CHANNEL_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = appContext as WatchReptApp
                val repo = app.repository
                    ?: SetupPreferences.getToken(appContext)?.let { token ->
                        app.initRepository(token)
                        app.repository
                    }
                repo?.sendMessage(channelId, text)
                Notifier.clear(appContext, channelId)
            } finally {
                pending.finish()
            }
        }
    }
}
