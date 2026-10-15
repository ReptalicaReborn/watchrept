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
import kotlinx.coroutines.flow.combine
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
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            // Discord CDN URLs are content-addressed, so cached copies never go stale.
            .respectCacheHeaders(false)
            .crossfade(!Perf.lite)
            .build()

    var repository: DiscordRepository? = null
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var summaryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Perf.init(this)
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
