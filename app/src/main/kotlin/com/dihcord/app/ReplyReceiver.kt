package com.dihcord.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.dihcord.app.api.DiscordRestClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends the text typed or dictated into a notification's inline reply, and forgets a conversation's
 * notification history when its notification is swiped away.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(Notifier.EXTRA_CHANNEL_ID) ?: return
        if (intent.action == Notifier.ACTION_DISMISSED) {
            Notifier.forget(channelId)
            return
        }
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val error: Throwable? = try {
                    // Use the live connection if there is one; otherwise a plain REST call is enough to send
                    // a message — no need to bring up a whole gateway session from a cold process.
                    val repo = (appContext as? DihcordApp)?.repository
                    val token = if (repo == null) SetupPreferences.getToken(appContext) else null
                    val result = withTimeoutOrNull(SEND_TIMEOUT_MS) {
                        when {
                            repo != null -> repo.sendMessage(channelId, text)
                            token != null -> DiscordRestClient(token).sendMessage(channelId, text)
                            else -> null
                        }
                    }
                    when {
                        repo == null && token == null -> IllegalStateException("Not logged in")
                        result == null -> java.io.IOException("Timed out")
                        else -> result.exceptionOrNull()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }

                if (error == null) {
                    Notifier.clear(appContext, channelId)
                } else {
                    Log.w(TAG, "Couldn't send reply to $channelId", error)
                    runCatching { Notifier.showReplyFailed(appContext, channelId, text) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReplyReceiver"
        const val SEND_TIMEOUT_MS = 25_000L
    }
}
