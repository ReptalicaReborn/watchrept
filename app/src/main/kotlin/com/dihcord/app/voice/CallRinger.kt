package com.dihcord.app.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.dihcord.app.MainActivity
import com.dihcord.app.R
import com.dihcord.app.api.DiscordRepository
import com.dihcord.app.discordApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Rings for incoming DM/group calls: a call notification (Accept / Decline) for every call that is ringing us,
 * removed when the ringing stops (answered elsewhere, declined, or the caller hung up).
 */
object CallRinger {
    private const val CHANNEL_ID = "incoming_calls"
    const val EXTRA_CALL_CHANNEL = "call_channel_id"
    const val EXTRA_CALL_NAME = "call_channel_name"
    const val EXTRA_ANSWER = "call_answer"
    private const val ACTION_DECLINE = "com.dihcord.app.voice.DECLINE"

    private val _incoming = MutableStateFlow<Set<String>>(emptySet())
    /** DM/group channels whose call is ringing us right now. */
    val incoming: StateFlow<Set<String>> = _incoming.asStateFlow()

    private val shown = HashSet<String>()

    fun watch(context: Context, repo: DiscordRepository, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch(Dispatchers.Default) {
            combine(repo.calls, repo.currentUser) { calls, me ->
                val myId = me?.id ?: return@combine emptySet()
                calls.filterValues { myId in it }.keys
            }.collect { ringing ->
                // Already in that call (e.g. answered on this watch): nothing to ring.
                val now = ringing.filterNot { VoiceManager.isInCall && VoiceManager.call.value?.channelId == it }.toSet()
                _incoming.value = now
                synchronized(shown) {
                    (shown - now).forEach { cancel(app, it) }
                    (now - shown).forEach { post(app, repo, it) }
                    shown.clear()
                    shown.addAll(now)
                }
            }
        }
    }

    private fun post(context: Context, repo: DiscordRepository, channelId: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 600, 800, 600, 800, 600, 800)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                    )
                }
            )
        }
        val dm = repo.dmChannels.value.firstOrNull { it.id == channelId }
        val name = dm?.displayName ?: repo.channelNames.value[channelId] ?: "Discord call"
        val caller = repo.voiceStates.value.values
            .firstOrNull { it.guildId == null && it.channelId == channelId && it.userId != repo.currentUser.value?.id }
            ?.let { vs -> repo.getDisplayName(vs.userId) ?: dm?.recipients?.firstOrNull { it.id == vs.userId }?.displayName }

        fun open(answer: Boolean, code: Int) = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_CALL_CHANNEL, channelId)
                .putExtra(EXTRA_CALL_NAME, name)
                .putExtra(EXTRA_ANSWER, answer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val decline = PendingIntent.getBroadcast(
            context, requestCode(channelId, 2),
            Intent(context, DeclineReceiver::class.java).setAction(ACTION_DECLINE).putExtra(EXTRA_CALL_CHANNEL, channelId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.speaker)
            .setContentTitle(caller ?: name)
            .setContentText(if (caller != null && (dm?.recipients?.size ?: 0) > 1) "Calling $name" else "Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setContentIntent(open(false, requestCode(channelId, 0)))
            .setFullScreenIntent(open(false, requestCode(channelId, 3)), true)
            .addAction(0, "Decline", decline)
            .addAction(0, "Accept", open(true, requestCode(channelId, 1)))
            .build()
            .apply { flags = flags or android.app.Notification.FLAG_INSISTENT }
        runCatching { nm.notify(notificationId(channelId), notification) }
    }

    fun cancel(context: Context, channelId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(channelId))
    }

    private fun notificationId(channelId: String) = 8000 + (channelId.hashCode() and 0x0FFFFFF)
    private fun requestCode(channelId: String, kind: Int) = (channelId.hashCode() and 0x0FFFFFF) * 4 + kind

    private const val RING_TIMEOUT_MS = 60_000L

    /** Declines from the notification without opening the app. */
    class DeclineReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val channelId = intent.getStringExtra(EXTRA_CALL_CHANNEL) ?: return
            cancel(context, channelId)
            val repo = context.discordApp.repository ?: return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repo.declineCall(channelId) }
                pending.finish()
            }
        }
    }
}
