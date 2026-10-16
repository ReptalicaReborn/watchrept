package com.dihcord.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the gateway connection alive and posts a notification for DMs and mentions
 * that arrive while the app isn't on screen.
 */
class NotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val notification = Notifier.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Notifier.SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifier.SERVICE_NOTIFICATION_ID, notification)
        }

        val app = application as DihcordApp
        val repo = app.repository
            ?: SetupPreferences.getToken(this)?.let { token ->
                app.initRepository(token)
                app.repository
            }
        if (repo == null || !SetupPreferences.getNotificationsEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        repo.resumeConnection()

        collector?.cancel()
        collector = scope.launch {
            repo.newMessages.collect { msg ->
                if (AppState.inForeground) return@collect
                if (SetupPreferences.isQuietNow(this@NotificationService)) return@collect
                val ctx = this@NotificationService
                if (FeaturePreferences.isChatMuted(ctx, msg.channelId)) return@collect
                val guildId = msg.guildId ?: repo.getChannelGuilds()[msg.channelId]
                if (guildId != null && guildId in SetupPreferences.getMutedServers(ctx)) return@collect
                val level = repo.alertLevelFor(
                    guildId, msg.channelId,
                    guildId?.let { FeaturePreferences.alertLevel(ctx, it) } ?: FeaturePreferences.AlertLevel.DISCORD
                )
                // DMs always notify unless muted; servers follow their alert level.
                if (level == FeaturePreferences.AlertLevel.NOTHING) return@collect
                val info = repo.notificationFor(msg, notifyAll = guildId != null && level == FeaturePreferences.AlertLevel.ALL)
                    ?: return@collect
                Notifier.show(this@NotificationService, info)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotificationService::class.java))
        }
    }
}
