package com.dihcord.app

import android.os.Handler
import android.os.Looper

/**
 * Tracks whether the UI is visible. Notifications are suppressed while the user is looking at the app, and
 * when background notifications are off the gateway connection is dropped shortly after the app is left.
 */
object AppState {
    private const val IDLE_DISCONNECT_MS = 2 * 60 * 1000L

    @Volatile
    var inForeground = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

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
}
