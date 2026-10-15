package com.dihcord.app

import android.os.Handler
import android.os.Looper

/**
 * Tracks whether the UI is visible. Notifications are suppressed while the user is looking at the app, and
 * when background notifications are off the gateway connection is dropped shortly after the app is left, or
 * shortly after the screen goes off. The pause keeps the session resumable, so turning the screen back on
 * reconnects quickly and Discord replays the messages that arrived meanwhile.
 */
object AppState {
    private const val IDLE_DISCONNECT_MS = 2 * 60 * 1000L
    private const val SCREEN_OFF_DISCONNECT_MS = 10 * 1000L

    @Volatile
    var inForeground = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var screenOffTask: Runnable? = null

    fun onStart(app: DihcordApp) {
        inForeground = true
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        app.repository?.resumeConnection()
    }

    fun onStop(app: DihcordApp) {
        inForeground = false
        if (SetupPreferences.getNotificationsEnabled(app)) return
        val task = Runnable { app.repository?.pauseConnection() }
        pending = task
        handler.postDelayed(task, IDLE_DISCONNECT_MS)
    }

    fun onScreenOff(app: DihcordApp) {
        if (!SetupPreferences.getDisconnectOnScreenOff(app)) return
        if (SetupPreferences.getNotificationsEnabled(app)) return // notifications need the live connection
        screenOffTask?.let { handler.removeCallbacks(it) }
        val task = Runnable { app.repository?.pauseConnection() }
        screenOffTask = task
        handler.postDelayed(task, SCREEN_OFF_DISCONNECT_MS)
    }

    fun onScreenOn(app: DihcordApp) {
        screenOffTask?.let { handler.removeCallbacks(it) }
        screenOffTask = null
        if (inForeground) app.repository?.resumeConnection()
    }
}
