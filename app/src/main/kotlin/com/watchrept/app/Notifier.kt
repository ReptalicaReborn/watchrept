package com.watchrept.app

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
import com.watchrept.app.api.NotificationInfo

/** Builds and posts the foreground-service and message notifications. */
object Notifier {
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_SERVICE = "service"
    private const val GROUP = "watchrept_messages"
    const val SERVICE_NOTIFICATION_ID = 1
    const val KEY_REPLY = "reply_text"
    const val EXTRA_CHANNEL_ID = "channel_id"
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
                description = "Keeps WatchRept connected so notifications can arrive"
                setShowBadge(false)
            }
        )
    }

    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.send)
            .setContentTitle("WatchRept")
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

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.send)
            .setStyle(style)
            .setContentTitle(info.title)
            .setContentText(info.text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openAppIntent(context, id))
            .addAction(replyAction)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun clear(context: Context, channelId: String) {
        history.remove(channelId)
        NotificationManagerCompat.from(context).cancel(notificationId(channelId))
    }

    private fun notificationId(channelId: String) = 1000 + (channelId.hashCode() and 0x0FFFFFFF)

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
