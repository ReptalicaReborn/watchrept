package com.dihcord.app

import android.app.ActivityManager
import android.content.Context

/**
 * "Lite mode" for weaker watch chips (e.g. Snapdragon Wear 4100/W5 Gen 1 class, Exynos W920/9110).
 * Decided once at startup; changing the setting takes effect after the app restarts.
 */
object Perf {
    enum class Mode { AUTO, ON, OFF }

    @Volatile
    var lite: Boolean = false
        private set

    /** Messages fetched per channel load. Fewer means less JSON parsing and less to lay out. */
    val messagePageSize: Int get() = if (lite) 25 else 50

    fun init(context: Context) {
        lite = when (SetupPreferences.getPerfMode(context)) {
            Mode.ON -> true
            Mode.OFF -> false
            Mode.AUTO -> isWeakDevice(context)
        }
    }

    /** Heuristic: low-RAM flag, at most 2 CPU cores, or under ~1.8 GB of RAM. */
    fun isWeakDevice(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice ||
            Runtime.getRuntime().availableProcessors() <= 2 ||
            mem.totalMem < 1_800_000_000L
    }
}
