package com.dihcord.app.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dihcord.app.MainActivity
import com.dihcord.app.R

/**
 * Keeps a voice call alive with the screen off: a microphone foreground service with a wake lock and a Wi-Fi lock,
 * plus a notification to mute or leave.
 */
class VoiceCallService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout") // released when the call ends
    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dihcord:Voice").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Dihcord:Voice")?.apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MUTE -> VoiceManager.toggleMute()
            ACTION_LEAVE -> {
                VoiceManager.leave()
                return START_NOT_STICKY
            }
        }
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Not allowed to record right now (e.g. permission revoked): give up on the call.
            VoiceManager.leave()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Voice calls", NotificationManager.IMPORTANCE_LOW))
        }
        val call = VoiceManager.call.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_VOICE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, VoiceCallService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.mic)
            .setContentTitle(call?.channelName?.let { "In voice: $it" } ?: "Voice call")
            .setContentText(if (call?.muted == true) "Muted" else "Tap to open")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(0, if (call?.muted == true) "Unmute" else "Mute", action(ACTION_MUTE, 1))
            .addAction(0, "Leave", action(ACTION_LEAVE, 2))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "voice_call"
        private const val NOTIFICATION_ID = 7001
        private const val ACTION_MUTE = "com.dihcord.app.voice.MUTE"
        private const val ACTION_LEAVE = "com.dihcord.app.voice.LEAVE"
        const val EXTRA_OPEN_VOICE = "open_voice"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java))
        }

        /** Re-posts the notification (e.g. after mute changed). */
        fun refresh(context: Context) {
            if (VoiceManager.isInCall) start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceCallService::class.java))
        }
    }
}
