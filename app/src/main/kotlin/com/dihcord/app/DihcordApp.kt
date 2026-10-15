package com.dihcord.app

import android.app.Application
import com.dihcord.app.api.DiscordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DihcordApp : Application() {
    var repository: DiscordRepository? = null
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var summaryJob: Job? = null

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
        summaryJob?.cancel()
        summaryJob = appScope.launch {
            combine(repo.readState, repo.dmChannels) { read, dms ->
                val dmIds = dms.map { it.id }.toSet()
                val mentions = read.filter { it.value.mentionCount > 0 }
                UnreadSummary.Counts(
                    dm = mentions.filter { it.key in dmIds }.values.sumOf { it.mentionCount },
                    server = mentions.filter { it.key !in dmIds }.values.sumOf { it.mentionCount },
                )
            }.collect { UnreadSummary.publish(this@DihcordApp, it) }
        }
    }

    fun clearRepository() {
        summaryJob?.cancel()
        UnreadSummary.reset(this)
        NotificationService.stop(this)
        repository?.disconnect()
        repository = null
    }

    override fun onTerminate() {
        super.onTerminate()
        repository?.disconnect()
    }
}

val android.content.Context.discordApp: DihcordApp
    get() = applicationContext as DihcordApp
