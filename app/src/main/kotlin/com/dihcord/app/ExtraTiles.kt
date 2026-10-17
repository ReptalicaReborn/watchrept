package com.dihcord.app

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.dihcord.app.voice.VoiceCall
import com.dihcord.app.voice.VoiceManager
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

/** The few newest DMs, kept for the Recent DMs tile so it can draw without the app running. */
object RecentDmsSummary {
    data class Entry(val channelId: String, val name: String, val mentions: Int, val unread: Boolean)

    private const val PREFS = "recent_dms_summary"

    fun read(context: Context): List<Entry> = runCatching {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("id"), o.getString("name"), o.optInt("m"), o.optBoolean("u"))
        }
    }.getOrDefault(emptyList())

    /**
     * Goes up on every [reset] (sign-out). A publisher passes the value it started with, so one still running
     * for the account that left can't write its DMs back after the reset.
     */
    @Volatile var generation = 0
        private set

    fun publish(context: Context, entries: List<Entry>, forGeneration: Int) {
        synchronized(this) {
            if (forGeneration != generation) return
            write(context, entries)
        }
    }

    fun reset(context: Context) {
        synchronized(this) {
            generation++
            write(context, emptyList())
        }
    }

    private fun write(context: Context, entries: List<Entry>) {
        if (entries == read(context)) return
        val arr = JSONArray(entries.map { JSONObject().put("id", it.channelId).put("name", it.name).put("m", it.mentions).put("u", it.unread) })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
        runCatching { TileService.getUpdater(context).requestUpdate(RecentDmsTileService::class.java) }
    }
}

private fun <T> immediate(value: T): ListenableFuture<T> =
    CallbackToFutureAdapter.getFuture { completer -> completer.set(value); "immediate" }

private fun tileText(value: String, sp: Float, color: Int, bold: Boolean = false) =
    LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setMaxLines(1)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sp))
                .setColor(argb(color))
                .apply { if (bold) setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD) }
                .build()
        )
        .build()

private fun spacer(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

private fun openApp(packageName: String, extras: Map<String, String> = emptyMap()): ActionBuilders.Action {
    val activity = ActionBuilders.AndroidActivity.Builder()
        .setPackageName(packageName)
        .setClassName(MainActivity::class.java.name)
    extras.forEach { (k, v) -> activity.addKeyToExtraMapping(k, ActionBuilders.AndroidStringExtra.Builder().setValue(v).build()) }
    return ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()
}

private fun clickable(id: String, action: ActionBuilders.Action) =
    ModifiersBuilders.Modifiers.Builder()
        .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(action).build())

/** A round face with the first letter on a stable colour, and a mention count or unread dot. */
private fun face(entry: RecentDmsSummary.Entry, packageName: String): LayoutElementBuilders.LayoutElement {
    val palette = listOf(0xFF5865F2, 0xFF3BA55C, 0xFFFAA61A, 0xFFED4245, 0xFFEB459E, 0xFF747F8D)
    val color = palette[(entry.name.hashCode() and 0x7FFFFFFF) % palette.size].toInt()
    val circle = LayoutElementBuilders.Box.Builder()
        .setWidth(dp(44f)).setHeight(dp(44f))
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(color)).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(22f)).build()).build())
                .setBorder(
                    if (entry.mentions > 0 || entry.unread) ModifiersBuilders.Border.Builder().setWidth(dp(2f)).setColor(argb(if (entry.mentions > 0) 0xFFF23F43.toInt() else 0xFFFFFFFF.toInt())).build()
                    else ModifiersBuilders.Border.Builder().setWidth(dp(0f)).setColor(argb(0)).build()
                )
                .build()
        )
        .addContent(tileText(entry.name.firstOrNull()?.uppercase() ?: "?", 18f, 0xFFFFFFFF.toInt(), bold = true))
        .build()
    val label = if (entry.mentions > 0) "${entry.name.substringBefore(' ').take(7)} · ${entry.mentions}" else entry.name.substringBefore(' ').take(9)
    return LayoutElementBuilders.Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .setModifiers(
            clickable("dm:" + entry.channelId, openApp(packageName, mapOf(Notifier.EXTRA_CHANNEL_ID to entry.channelId, Notifier.EXTRA_CHANNEL_NAME to entry.name)))
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(3f)).setEnd(dp(3f)).build())
                .build()
        )
        .addContent(circle)
        .addContent(spacer(3f))
        .addContent(tileText(label, 11f, if (entry.mentions > 0) 0xFFFF8A8C.toInt() else 0xFFB5B9C4.toInt()))
        .build()
}

/** A tile with the newest DMs as faces; tapping one opens that chat. */
class RecentDmsTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        immediate(
            TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(packageName, RecentDmsSummary.read(this))))
                .build()
        )

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion("1").build())

    companion object {
        fun layout(packageName: String, dms: List<RecentDmsSummary.Entry>): LayoutElementBuilders.LayoutElement {
            val column = LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(tileText("Dihcord", 13f, 0xFFB5BAFF.toInt(), bold = true))
                .addContent(spacer(8f))
            if (dms.isEmpty()) {
                column.addContent(tileText("No DMs yet", 15f, 0xFFFFFFFF.toInt(), bold = true))
                column.addContent(tileText("Open the app to sign in", 12f, 0xFFB5B9C4.toInt()))
            } else {
                // Two rows of two fit the round tile better than one row of four.
                dms.take(4).chunked(2).forEachIndexed { i, pair ->
                    if (i > 0) column.addContent(spacer(6f))
                    val row = LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
                    pair.forEach { row.addContent(face(it, packageName)) }
                    column.addContent(row.build())
                }
            }
            return LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
                .setModifiers(clickable("open", openApp(packageName)).build())
                .addContent(column.build())
                .build()
        }
    }
}

/** A tile for the voice call in progress: who/where, mute and leave. */
class VoiceTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        when (requestParams.currentState.lastClickableId) {
            "mute" -> VoiceManager.toggleMute()
            "leave" -> VoiceManager.leave()
        }
        val call = VoiceManager.call.value?.takeIf { VoiceManager.isInCall }
        return immediate(
            TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(packageName, call)))
                .build()
        )
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion("1").build())

    companion object {
        private fun pill(id: String, label: String, color: Int, wide: Boolean) = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(if (wide) 64f else 52f)).setHeight(dp(44f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build())
                    .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(color)).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(22f)).build()).build())
                    .build()
            )
            .addContent(tileText(label, 12f, 0xFFFFFFFF.toInt(), bold = true))
            .build()

        fun layout(packageName: String, call: VoiceCall?): LayoutElementBuilders.LayoutElement {
            val column = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            if (call == null) {
                column.addContent(tileText("Dihcord voice", 13f, 0xFFB5BAFF.toInt(), bold = true))
                column.addContent(spacer(8f))
                column.addContent(tileText("Not in a call", 16f, 0xFFFFFFFF.toInt(), bold = true))
                column.addContent(tileText("Join one from a voice channel", 12f, 0xFFB5B9C4.toInt()))
                return LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
                    .setModifiers(clickable("open", openApp(packageName)).build())
                    .addContent(column.build())
                    .build()
            }
            val connecting = call.status == VoiceCall.Status.CONNECTING
            column.addContent(tileText(if (connecting) "Connecting…" else "● In a call", 12f, 0xFF23A55A.toInt(), bold = true))
            column.addContent(spacer(2f))
            column.addContent(tileText(call.channelName, 17f, 0xFFFFFFFF.toInt(), bold = true))
            column.addContent(tileText(if (call.muted) "You're muted" else if (call.deafened) "Deafened" else "Mic on", 12f, 0xFFB5B9C4.toInt()))
            column.addContent(spacer(10f))
            column.addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(pill("mute", if (call.muted) "Unmute" else "Mute", 0xFF2A2C36.toInt(), wide = call.muted))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
                    .addContent(pill("leave", "Leave", 0xFFDA373C.toInt(), wide = false))
                    .build()
            )
            return LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
                .addContent(column.build())
                .build()
        }
    }
}
