package com.dihcord.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.dihcord.app.api.NotificationInfo

/** Builds and posts the foreground-service and message notifications. */
object Notifier {
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_SERVICE = "service"
    private const val GROUP = "dihcord_messages"
    const val SERVICE_NOTIFICATION_ID = 1
    const val KEY_REPLY = "reply_text"
    const val EXTRA_CHANNEL_ID = "channel_id"
    const val EXTRA_CHANNEL_NAME = "channel_name"
    const val EXTRA_GUILD_ID = "guild_id"
    const val EXTRA_MUTE_SERVER = "mute_server"
    private const val MAX_HISTORY = 6

    private class Line(val sender: String, val text: String, val time: Long)

    private val history = mutableMapOf<String, ArrayDeque<Line>>()

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "DMs and mentions"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps Dihcord connected so notifications can arrive"
                setShowBadge(false)
            }
        )
    }

    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.send)
            .setContentTitle("Dihcord")
            .setContentText("Listening for messages")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent(context, 0))
            .build()

    fun canPostNotifications(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun show(context: Context, info: NotificationInfo) {
        if (!canPostNotifications(context)) return
        val id = notificationId(info.channelId)
        val lines = history.getOrPut(info.channelId) { ArrayDeque() }
        lines.addLast(Line(info.sender, info.text, System.currentTimeMillis()))
        while (lines.size > MAX_HISTORY) lines.removeFirst()

        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(info.title)
        if (!info.isDm) style.isGroupConversation = true
        lines.forEach { l ->
            style.addMessage(l.text, l.time, Person.Builder().setName(l.sender).build())
        }

        val replyInput = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        val replyIntent = Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_CHANNEL_ID, info.channelId)
        val replyPending = PendingIntent.getBroadcast(
            context, id, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.reply, "Reply", replyPending)
            .addRemoteInput(replyInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()

        val muteIntent = Intent(context, MuteReceiver::class.java).putExtra(EXTRA_CHANNEL_ID, info.channelId)
        val mutePending = PendingIntent.getBroadcast(
            context, id, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteAction = NotificationCompat.Action.Builder(
            R.drawable.mute, if (info.isDm) "Mute" else "Mute channel", mutePending
        ).build()
        val muteServerAction = info.guildId?.let { guildId ->
            val intent = Intent(context, MuteReceiver::class.java)
                .putExtra(EXTRA_CHANNEL_ID, info.channelId)
                .putExtra(EXTRA_GUILD_ID, guildId)
                .putExtra(EXTRA_MUTE_SERVER, true)
            val pending = PendingIntent.getBroadcast(
                context, id + 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(R.drawable.mute, "Mute server", pending).build()
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.send)
            .setStyle(style)
            .setContentTitle(info.title)
            .setContentText(info.text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openAppIntent(context, id, info))
            .addAction(replyAction)
            .addAction(muteAction)
            .apply { muteServerAction?.let { addAction(it) } }
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun clear(context: Context, channelId: String) {
        history.remove(channelId)
        NotificationManagerCompat.from(context).cancel(notificationId(channelId))
    }

    private fun notificationId(channelId: String) = 1000 + (channelId.hashCode() and 0x0FFFFFFF)

    /** Opens the app, straight into the conversation when [info] is given. */
    private fun openAppIntent(context: Context, requestCode: Int, info: NotificationInfo? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (info != null) {
            intent.putExtra(EXTRA_CHANNEL_ID, info.channelId)
                .putExtra(EXTRA_CHANNEL_NAME, info.channelName)
                .putExtra(EXTRA_GUILD_ID, info.guildId)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
