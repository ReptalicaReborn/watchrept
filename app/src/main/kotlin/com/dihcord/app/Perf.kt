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

    /** What the hardware looks like to the app; shown in Settings so a wrong Auto decision can be diagnosed. */
    data class Hardware(val cores: Int, val totalMemBytes: Long, val lowRamFlag: Boolean) {
        val summary: String
            get() = "$cores cores, ${"%.1f".format(totalMemBytes / 1_073_741_824.0)} GB RAM" +
                if (lowRamFlag) ", low-RAM flag" else ""
    }

    /** Why Auto turns lite mode on, or null when the hardware is fine. */
    fun weaknessReason(hw: Hardware): String? = when {
        hw.cores <= 2 -> "only ${hw.cores} CPU cores"
        hw.totalMemBytes < LOW_MEMORY_BYTES -> "under 1.2 GB of RAM"
        // The low-RAM flag is deliberately ignored: Wear OS builds routinely set it, even on fast watches.
        else -> null
    }

    fun hardware(context: Context): Hardware {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return Hardware(
            cores = installedCores(),
            totalMemBytes = mem.totalMem,
            lowRamFlag = am?.isLowRamDevice ?: false,
        )
    }

    fun isWeakDevice(context: Context): Boolean = weaknessReason(hardware(context)) != null

    /**
     * Cores the chip has, not the ones currently online: Runtime.availableProcessors() drops cores the watch has
     * switched off to save power, which made fast watches look like 1-2 core devices.
     */
    private fun installedCores(): Int {
        val fromSysfs = runCatching {
            parseCpuRange(java.io.File("/sys/devices/system/cpu/possible").readText())
        }.getOrNull()
        return fromSysfs ?: Runtime.getRuntime().availableProcessors()
    }

    /** "0-4" -> 5, "0-3,6-7" -> 6, "0" -> 1; null if unparsable. */
    internal fun parseCpuRange(text: String): Int? {
        val parts = text.trim().split(",").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var total = 0
        for (part in parts) {
            val bounds = part.trim().split("-")
            val a = bounds.getOrNull(0)?.toIntOrNull() ?: return null
            val b = if (bounds.size == 2) bounds[1].toIntOrNull() ?: return null else a
            if (b < a) return null
            total += b - a + 1
        }
        return total
    }

    private const val LOW_MEMORY_BYTES = 1_200_000_000L
}
