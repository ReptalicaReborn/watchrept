package com.dihcord.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.dihcord.app.api.DiscordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class DihcordApp : Application(), ImageLoaderFactory {

    /**
     * One image loader for the whole app (screens get it via `context.imageLoader`), so avatars, icons and
     * emoji decoded on one screen are instantly reused on the next, and survive restarts via the disk cache.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                // In lite mode GIFs (animated emoji, avatars, attachments) fall back to the platform
                // decoder, which shows the first frame only: no per-frame decoding on weak CPUs.
                if (!Perf.lite) add(ImageDecoderDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (Perf.lite) 0.15 else 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(if (Perf.lite) 32L * 1024 * 1024 else 64L * 1024 * 1024)
                    .build()
            }
            // Discord CDN URLs are content-addressed, so cached copies never go stale.
            .respectCacheHeaders(false)
            .crossfade(!Perf.lite)
            .build()

    private val _repositoryFlow = MutableStateFlow<DiscordRepository?>(null)

    /** The current repository; observe this to react to logins/logouts after the UI was built. */
    val repositoryFlow: StateFlow<DiscordRepository?> = _repositoryFlow.asStateFlow()

    val repository: DiscordRepository?
        get() = _repositoryFlow.value

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var summaryJob: Job? = null
    private var recentDmsJob: Job? = null
    private var callScope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        Perf.init(this)
        // The voice tile follows the call: joined, muted, left.
        appScope.launch {
            com.dihcord.app.voice.VoiceManager.call
                .map { c -> c?.let { Triple(it.status, it.muted, it.channelName) } }
                .distinctUntilChanged()
                .collect { runCatching { androidx.wear.tiles.TileService.getUpdater(this@DihcordApp).requestUpdate(VoiceTileService::class.java) } }
        }
        // Lite mode on Auto follows battery saver.
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) = Perf.refresh(context)
        }, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        AppTheme.init(this)
        val screenFilter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                when (intent.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> AppState.onScreenOff(this@DihcordApp)
                    android.content.Intent.ACTION_SCREEN_ON -> AppState.onScreenOn(this@DihcordApp)
                }
            }
        }, screenFilter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun initRepository(token: String) {
        repository?.close()
        val repo = DiscordRepository(token, context = this)
        repo.connect()
        _repositoryFlow.value = repo
        callScope?.cancel()
        callScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
            com.dihcord.app.voice.CallRinger.watch(this, repo, it)
        }
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
        recentDmsJob?.cancel()
        recentDmsJob = appScope.launch {
            // The Recent DMs tile reads this snapshot, so it draws without the app or a connection.
            combine(repo.readState, repo.dmChannels) { read, dms ->
                val hidden = SetupPreferences.getHiddenDms(this@DihcordApp)
                dms.filter { it.id !in hidden }
                    .sortedByDescending { it.lastMessageId?.toLongOrNull() ?: 0L }
                    .take(4)
                    .map { dm ->
                        val rs = read[dm.id]
                        RecentDmsSummary.Entry(
                            channelId = dm.id,
                            name = dm.displayName,
                            mentions = rs?.mentionCount ?: 0,
                            unread = dm.lastMessageId != null && rs != null &&
                                com.dihcord.app.api.compareSnowflakes(dm.lastMessageId, rs.lastMessageId) > 0
                        )
                    }
            }.collect { RecentDmsSummary.publish(this@DihcordApp, it) }
        }
        // Logging in again (or a token change) must bring notifications back if the user wants them;
        // the service re-reads the repository on every start.
        if (SetupPreferences.getNotificationsEnabled(this)) {
            NotificationService.start(this)
        }
    }

    /** Puts a ready-made repository in place without connecting anything, for the screenshot tests. */
    internal fun useRepositoryForTests(repo: DiscordRepository?) {
        _repositoryFlow.value = repo
    }

    fun clearRepository() {
        summaryJob?.cancel()
        recentDmsJob?.cancel()
        callScope?.cancel()
        callScope = null
        com.dihcord.app.voice.VoiceManager.leave()
        UnreadSummary.reset(this)
        RecentDmsSummary.reset(this)
        NotificationService.stop(this)
        // Saved chats, bookmarks, mutes and alert levels belong to the account that's leaving.
        repository?.clearOfflineCache()
        FeaturePreferences.clearAccountData(this)
        repository?.close()
        _repositoryFlow.value = null
    }

    override fun onTerminate() {
        super.onTerminate()
        repository?.close()
    }
}

val android.content.Context.discordApp: DihcordApp
    get() = applicationContext as DihcordApp
