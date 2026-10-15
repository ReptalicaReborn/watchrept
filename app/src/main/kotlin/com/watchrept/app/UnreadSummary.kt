package com.watchrept.app

import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Last known unread-mention counts, persisted so the tile and complication can render without the app
 * process (or a gateway connection) being alive. Counts are refreshed whenever the app is connected.
 */
object UnreadSummary {
    private const val PREFS = "unread_summary"
    private const val KEY_DM = "dm"
    private const val KEY_SERVER = "server"

    data class Counts(val dm: Int, val server: Int) {
        val total get() = dm + server
    }

    fun read(context: Context): Counts {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Counts(p.getInt(KEY_DM, 0), p.getInt(KEY_SERVER, 0))
    }

    /** Saves new counts and, only if they changed, asks the tile and complications to redraw. */
    fun publish(context: Context, counts: Counts) {
        if (read(context) == counts) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_DM, counts.dm)
            putInt(KEY_SERVER, counts.server)
        }
        refreshSurfaces(context)
    }

    fun reset(context: Context) = publish(context, Counts(0, 0))

    private fun refreshSurfaces(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(UnreadTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, UnreadComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
