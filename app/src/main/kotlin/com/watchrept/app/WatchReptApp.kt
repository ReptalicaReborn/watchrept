package com.watchrept.app

import android.app.Application
import com.watchrept.app.api.DiscordRepository

class WatchReptApp : Application() {
    var repository: DiscordRepository? = null
        private set

    override fun onCreate() {
        super.onCreate()
        UpdateChecker.start(this)
        UpdateChecker.checkNow(this)
    }

    fun initRepository(token: String) {
        repository?.disconnect()
        val repo = DiscordRepository(token, context = this)
        repo.connect()
        repository = repo
    }

    fun clearRepository() {
        NotificationService.stop(this)
        repository?.disconnect()
        repository = null
    }

    override fun onTerminate() {
        super.onTerminate()
        repository?.disconnect()
    }
}

val android.content.Context.discordApp: WatchReptApp
    get() = applicationContext as WatchReptApp
