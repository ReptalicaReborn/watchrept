package com.dihcord.app

import android.content.Context
import androidx.core.content.edit
import com.dihcord.app.api.SavedMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings and small stores for the features added after the redesign: timed chat mutes, alert levels
 * per server, quick replies, link handling, lite mode with battery saver, saved messages and events.
 */
object FeaturePreferences {
    private const val PREFS = "dihcord_features"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Timed chat mutes ----

    private const val KEY_MUTE_UNTIL = "mute_until"

    /** Chat id -> epoch ms the mute ends (Long.MAX_VALUE for "until I unmute"). */
    fun mutedUntil(context: Context): Map<String, Long> {
        val o = runCatching { JSONObject(prefs(context).getString(KEY_MUTE_UNTIL, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        return o.keys().asSequence().associateWith { o.optLong(it) }.filterValues { it > now }
    }

    /** Mutes a DM or channel for [durationMs] (null: until unmuted); 0 unmutes. */
    fun muteChat(context: Context, channelId: String, durationMs: Long?) {
        val all = mutedUntil(context).toMutableMap()
        when (durationMs) {
            0L -> all.remove(channelId)
            null -> all[channelId] = Long.MAX_VALUE
            else -> all[channelId] = System.currentTimeMillis() + durationMs
        }
        prefs(context).edit { putString(KEY_MUTE_UNTIL, JSONObject(all.mapValues { it.value }).toString()) }
        // The old permanent list (notification "Mute" button) is part of the same answer.
        if (durationMs == 0L) {
            val old = SetupPreferences.getMutedChannels(context)
            if (channelId in old) {
                SetupPreferences.clearMutedChannels(context)
                (old - channelId).forEach { SetupPreferences.muteChannel(context, it) }
            }
        }
    }

    /** Whether notifications from [channelId] are silenced right now (timed mute or the old permanent one). */
    fun isChatMuted(context: Context, channelId: String): Boolean =
        channelId in mutedUntil(context) || channelId in SetupPreferences.getMutedChannels(context)

    // ---- Alert level per server ----

    enum class AlertLevel(val label: String) { DISCORD("Same as Discord"), ALL("All messages"), MENTIONS("Only @mentions"), NOTHING("Nothing") }

    private const val KEY_ALERT_LEVELS = "alert_levels"

    fun alertLevel(context: Context, guildId: String): AlertLevel {
        val o = runCatching { JSONObject(prefs(context).getString(KEY_ALERT_LEVELS, "{}") ?: "{}") }.getOrDefault(JSONObject())
        return o.optString(guildId).let { name -> AlertLevel.entries.firstOrNull { it.name == name } } ?: AlertLevel.DISCORD
    }

    fun setAlertLevel(context: Context, guildId: String, level: AlertLevel) {
        val o = runCatching { JSONObject(prefs(context).getString(KEY_ALERT_LEVELS, "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (level == AlertLevel.DISCORD) o.remove(guildId) else o.put(guildId, level.name)
        prefs(context).edit { putString(KEY_ALERT_LEVELS, o.toString()) }
    }

    // ---- Quick replies ----

    val DEFAULT_QUICK_REPLIES = listOf("ok", "on my way", "brb", "👍", "later", "thanks!")
    private const val KEY_QUICK_REPLIES_ON = "quick_replies_on"

    fun quickRepliesOn(context: Context): Boolean = prefs(context).getBoolean(KEY_QUICK_REPLIES_ON, true)
    fun setQuickRepliesOn(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_QUICK_REPLIES_ON, on) }

    // ---- Links ----

    private const val KEY_LINKS_ON_PHONE = "links_on_phone"

    /** Web links open on the paired phone (true) or in a browser on the watch. */
    fun linksOnPhone(context: Context): Boolean = prefs(context).getBoolean(KEY_LINKS_ON_PHONE, true)
    fun setLinksOnPhone(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_LINKS_ON_PHONE, on) }

    // ---- Lite mode follows battery saver ----

    private const val KEY_LITE_WITH_SAVER = "lite_with_battery_saver"

    fun liteWithBatterySaver(context: Context): Boolean = prefs(context).getBoolean(KEY_LITE_WITH_SAVER, true)
    fun setLiteWithBatterySaver(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_LITE_WITH_SAVER, on) }

    // ---- Saved messages ----

    private const val KEY_SAVED = "saved_messages"
    private const val MAX_SAVED = 100

    fun saved(context: Context): List<SavedMessage> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_SAVED, "[]"))
        (0 until arr.length()).mapNotNull { runCatching { SavedMessage.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun isSaved(context: Context, messageId: String) = saved(context).any { it.messageId == messageId }

    /** Saves or un-saves; returns whether it is saved now. */
    fun toggleSaved(context: Context, message: SavedMessage): Boolean {
        val all = saved(context)
        val now = if (all.any { it.messageId == message.messageId }) all.filterNot { it.messageId == message.messageId }
        else (listOf(message) + all).take(MAX_SAVED)
        prefs(context).edit { putString(KEY_SAVED, JSONArray(now.map { it.toJson() }).toString()) }
        return now.any { it.messageId == message.messageId }
    }

    // ---- Events ----

    private const val KEY_INTERESTED = "interested_events"

    fun interestedEvents(context: Context): Set<String> = prefs(context).getStringSet(KEY_INTERESTED, emptySet()) ?: emptySet()

    fun setInterested(context: Context, eventId: String, on: Boolean) {
        val now = interestedEvents(context).let { if (on) it + eventId else it - eventId }
        prefs(context).edit { putStringSet(KEY_INTERESTED, now) }
    }

    /** Everything here is per account: forget it on log-out. */
    fun clear(context: Context) = prefs(context).edit { clear() }
}
