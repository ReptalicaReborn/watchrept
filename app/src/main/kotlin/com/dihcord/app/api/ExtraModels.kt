package com.dihcord.app.api

import org.json.JSONArray
import org.json.JSONObject

/*
 * Models for the features added after the UI redesign: timestamp tags, bot components, activities,
 * relationships, the member list, scheduled events, soundboard sounds, invites and slash commands.
 */

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }

private inline fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let { runCatching { block(it) }.getOrNull() } }
}

/** Discord's `<t:unix:style>` tags, shown as dates and times the way Discord does. */
object DiscordTimestamp {
    val REGEX = Regex("<t:(-?\\d{1,13})(?::([tTdDfFR]))?>")

    fun format(
        epochSeconds: Long,
        style: Char?,
        nowMillis: Long = System.currentTimeMillis(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): String = try {
        formatOrThrow(epochSeconds, style, nowMillis, zone, locale)
    } catch (e: java.time.DateTimeException) {
        "a time" // A number past the calendar's range, from a message anyone could send.
    } catch (e: ArithmeticException) {
        "a time"
    }

    private fun formatOrThrow(epochSeconds: Long, style: Char?, nowMillis: Long, zone: java.time.ZoneId, locale: java.util.Locale): String {
        val at = java.time.Instant.ofEpochSecond(epochSeconds).atZone(zone)
        fun pattern(p: String) = at.format(java.time.format.DateTimeFormatter.ofPattern(p, locale))
        return when (style) {
            't' -> pattern("h:mm a")
            'T' -> pattern("h:mm:ss a")
            'd' -> pattern("M/d/yyyy")
            'D' -> pattern("MMMM d, yyyy")
            'F' -> pattern("EEEE, MMMM d, yyyy h:mm a")
            'R' -> relative(Math.multiplyExact(epochSeconds, 1000L), nowMillis)
            else -> pattern("MMMM d, yyyy h:mm a") // 'f', the default
        }
    }

    /** "in 2 hours", "3 days ago", "a minute ago"... */
    fun relative(atMillis: Long, nowMillis: Long): String {
        val diff = atMillis - nowMillis
        val future = diff > 0
        val secs = kotlin.math.abs(diff) / 1000
        val (n, unit) = when {
            secs < 45 -> return if (future) "in a few seconds" else "a few seconds ago"
            secs < 90 -> 1L to "minute"
            secs < 45 * 60 -> (secs + 30) / 60 to "minute"
            secs < 90 * 60 -> 1L to "hour"
            secs < 22 * 3600 -> (secs + 1800) / 3600 to "hour"
            secs < 36 * 3600 -> 1L to "day"
            secs < 26 * 86400 -> (secs + 43200) / 86400 to "day"
            secs < 46 * 86400 -> 1L to "month"
            secs < 320 * 86400 -> (secs + 15 * 86400) / (30 * 86400) to "month"
            secs < 548 * 86400 -> 1L to "year"
            else -> (secs + 182 * 86400) / (365 * 86400) to "year"
        }
        val amount = if (n == 1L) (if (unit == "hour") "an $unit" else "a $unit") else "$n ${unit}s"
        return if (future) "in $amount" else "$amount ago"
    }
}

/** A select-menu choice. */
data class SelectOption(val label: String, val value: String, val description: String?, val emoji: ReactionEmoji?, val default: Boolean)

/** A bot message component: buttons and menus in rows, and the newer text/section/container layout. */
sealed class MessageComponent {
    data class Row(val children: List<MessageComponent>) : MessageComponent()

    /** [style] 1 primary, 2 secondary, 3 success, 4 danger, 5 link (opens [url]), 6 premium. */
    data class Button(
        val style: Int,
        val label: String?,
        val emoji: ReactionEmoji?,
        val customId: String?,
        val url: String?,
        val disabled: Boolean,
    ) : MessageComponent() {
        val isLink: Boolean get() = style == 5 && url != null
    }

    /** A dropdown; [type] 3 is text options, 5-8 pick users/roles/mentionables/channels (shown, not offered). */
    data class Select(
        val type: Int,
        val customId: String,
        val placeholder: String?,
        val options: List<SelectOption>,
        val minValues: Int,
        val maxValues: Int,
        val disabled: Boolean,
    ) : MessageComponent()

    /** Markdown text (components v2 "text display"). */
    data class Text(val content: String) : MessageComponent()

    /** Text with a button beside it (components v2 "section"). */
    data class Section(val texts: List<String>, val accessory: MessageComponent?) : MessageComponent()

    /** A boxed group with an optional accent colour (components v2 "container"). */
    data class Container(val children: List<MessageComponent>, val accentColor: Int?) : MessageComponent()

    companion object {
        fun listFromJson(arr: JSONArray?): List<MessageComponent> = arr.mapObjects { fromJson(it) }

        fun fromJson(o: JSONObject): MessageComponent? = when (o.optInt("type")) {
            1 -> Row(listFromJson(o.optJSONArray("components")))
            2 -> Button(
                style = o.optInt("style", 2),
                label = o.str("label"),
                emoji = o.optJSONObject("emoji")?.let { runCatching { ReactionEmoji.fromJson(it) }.getOrNull() },
                customId = o.str("custom_id"),
                url = o.str("url"),
                disabled = o.optBoolean("disabled", false),
            )
            3, 5, 6, 7, 8 -> Select(
                type = o.optInt("type"),
                customId = o.str("custom_id") ?: "",
                placeholder = o.str("placeholder"),
                options = o.optJSONArray("options").mapObjects { opt ->
                    SelectOption(
                        label = opt.optString("label"),
                        value = opt.optString("value"),
                        description = opt.str("description"),
                        emoji = opt.optJSONObject("emoji")?.let { runCatching { ReactionEmoji.fromJson(it) }.getOrNull() },
                        default = opt.optBoolean("default", false),
                    )
                },
                minValues = o.optInt("min_values", 1),
                maxValues = o.optInt("max_values", 1),
                disabled = o.optBoolean("disabled", false),
            )
            10 -> o.str("content")?.let { Text(it) }
            9 -> Section(
                texts = o.optJSONArray("components").mapObjects { it.str("content") },
                accessory = o.optJSONObject("accessory")?.let { fromJson(it) }?.takeIf { it is Button },
            )
            17 -> Container(
                children = listFromJson(o.optJSONArray("components")),
                accentColor = if (o.isNull("accent_color") || !o.has("accent_color")) null else o.optInt("accent_color"),
            )
            else -> null // media galleries, files, separators and thumbnails aren't drawn
        }
    }
}

/** What someone is doing: playing, listening, watching... (custom statuses are [UserPresence.customStatusText]). */
data class Activity(val type: Int, val name: String, val details: String?, val state: String?) {
    /** "Playing Minecraft", "Listening to Spotify"; null for custom statuses and unknown kinds. */
    val label: String?
        get() = when (type) {
            0 -> "Playing $name"
            1 -> "Streaming ${details ?: name}"
            2 -> "Listening to $name"
            3 -> "Watching $name"
            5 -> "Competing in $name"
            else -> null
        }

    companion object {
        fun listFromJson(arr: JSONArray?): List<Activity> = arr.mapObjects { a ->
            Activity(a.optInt("type"), a.optString("name"), a.str("details"), a.str("state"))
        }.filter { it.name.isNotEmpty() }
    }
}

/** A friend, a block, or a pending friend request. */
data class Relationship(val id: String, val type: Int, val user: DiscordUser) {
    val isFriend get() = type == FRIEND
    val isBlocked get() = type == BLOCKED
    val isIncoming get() = type == INCOMING
    val isOutgoing get() = type == OUTGOING

    companion object {
        const val FRIEND = 1
        const val BLOCKED = 2
        const val INCOMING = 3
        const val OUTGOING = 4

        fun fromJson(o: JSONObject): Relationship? {
            val user = o.optJSONObject("user")?.let { DiscordUser.fromJson(it) } ?: return null
            return Relationship(o.str("id") ?: user.id, o.optInt("type"), user)
        }

        fun listFromJson(arr: JSONArray?): List<Relationship> = arr.mapObjects { fromJson(it) }
    }
}

/** One row of a server's member list: a group header ("Online — 12", a hoisted role) or a member. */
sealed class MemberListItem {
    data class Group(val id: String, val count: Int) : MemberListItem()
    data class Member(
        val user: DiscordUser,
        val nick: String?,
        val roleIds: List<String>,
        val status: OnlineStatus,
        val activities: List<Activity>,
    ) : MemberListItem() {
        val name: String get() = nick ?: user.displayName
    }

    companion object {
        fun fromJson(o: JSONObject): MemberListItem? {
            o.optJSONObject("group")?.let { return Group(it.optString("id"), it.optInt("count")) }
            val m = o.optJSONObject("member") ?: return null
            val user = m.optJSONObject("user")?.let { DiscordUser.fromJson(it) } ?: return null
            val presence = m.optJSONObject("presence")
            val rolesArr = m.optJSONArray("roles")
            return Member(
                user = user,
                nick = m.str("nick"),
                roleIds = (0 until (rolesArr?.length() ?: 0)).map { rolesArr!!.getString(it) },
                status = when (presence?.optString("status")) {
                    "online" -> OnlineStatus.ONLINE
                    "idle" -> OnlineStatus.IDLE
                    "dnd" -> OnlineStatus.DND
                    else -> OnlineStatus.OFFLINE
                },
                activities = Activity.listFromJson(presence?.optJSONArray("activities")),
            )
        }
    }
}

/**
 * One change to a member list from GUILD_MEMBER_LIST_UPDATE: SYNC replaces a range, INSERT/UPDATE/DELETE
 * touch one row, INVALIDATE drops a range.
 */
sealed class MemberListOp {
    /** [end] is the last position the range covers; rows in it that [items] don't fill are gone. */
    data class Sync(val start: Int, val items: List<MemberListItem>, val end: Int = start + items.size - 1) : MemberListOp()
    data class Insert(val index: Int, val item: MemberListItem) : MemberListOp()
    data class Update(val index: Int, val item: MemberListItem) : MemberListOp()
    data class Delete(val index: Int) : MemberListOp()
    data class Invalidate(val start: Int, val end: Int) : MemberListOp()

    companion object {
        fun listFromJson(arr: JSONArray?): List<MemberListOp> = arr.mapObjects { o ->
            when (o.optString("op")) {
                "SYNC" -> {
                    val start = o.optJSONArray("range")?.optInt(0) ?: 0
                    val items = o.optJSONArray("items").mapObjects { MemberListItem.fromJson(it) }
                    Sync(start, items, o.optJSONArray("range")?.optInt(1) ?: (start + items.size - 1))
                }
                "INSERT" -> o.optJSONObject("item")?.let { MemberListItem.fromJson(it) }?.let { Insert(o.optInt("index"), it) }
                "UPDATE" -> o.optJSONObject("item")?.let { MemberListItem.fromJson(it) }?.let { Update(o.optInt("index"), it) }
                "DELETE" -> Delete(o.optInt("index"))
                "INVALIDATE" -> Invalidate(o.optJSONArray("range")?.optInt(0) ?: 0, o.optJSONArray("range")?.optInt(1) ?: 0)
                else -> null
            }
        }

        /** Applies [ops] to [rows] (positions in the list; gaps are null). */
        fun apply(rows: List<MemberListItem?>, ops: List<MemberListOp>): List<MemberListItem?> {
            val out = rows.toMutableList()
            fun ensure(size: Int) { while (out.size < size) out.add(null) }
            for (op in ops) when (op) {
                is Sync -> {
                    ensure(op.start + op.items.size)
                    for (i in op.start..minOf(op.end, out.size - 1)) if (i >= 0) out[i] = null
                    op.items.forEachIndexed { i, item -> out[op.start + i] = item }
                }
                is Insert -> if (op.index <= out.size) out.add(op.index, op.item) else { ensure(op.index); out.add(op.item) }
                is Update -> { ensure(op.index + 1); out[op.index] = op.item }
                is Delete -> if (op.index in out.indices) out.removeAt(op.index)
                is Invalidate -> for (i in op.start..minOf(op.end, out.size - 1)) if (i >= 0) out[i] = null
            }
            return out
        }
    }
}

/** A server's scheduled event. [status] 1 scheduled, 2 active, 3 completed, 4 canceled. */
data class ScheduledEvent(
    val id: String,
    val guildId: String,
    val channelId: String?,
    val name: String,
    val description: String?,
    val startMillis: Long?,
    val status: Int,
    val location: String?,
    val userCount: Int,
) {
    val isLive: Boolean get() = status == 2

    companion object {
        fun listFromJson(arr: JSONArray?): List<ScheduledEvent> = arr.mapObjects { o ->
            ScheduledEvent(
                id = o.getString("id"),
                guildId = o.optString("guild_id"),
                channelId = o.str("channel_id"),
                name = o.optString("name"),
                description = o.str("description"),
                startMillis = o.str("scheduled_start_time")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
                status = o.optInt("status", 1),
                location = o.optJSONObject("entity_metadata")?.str("location"),
                userCount = o.optInt("user_count", 0),
            )
        }.filter { it.status == 1 || it.status == 2 }
    }
}

/** A soundboard sound; [guildId] is null for Discord's default sounds. */
data class SoundboardSound(
    val id: String,
    val name: String,
    val emoji: String?,
    val volume: Double,
    val guildId: String?,
    val available: Boolean,
) {
    val url: String get() = "https://cdn.discordapp.com/soundboard-sounds/$id"

    companion object {
        fun listFromJson(arr: JSONArray?): List<SoundboardSound> = arr.mapObjects { o ->
            SoundboardSound(
                id = o.getString("sound_id"),
                name = o.optString("name"),
                emoji = o.str("emoji_name"),
                volume = o.optDouble("volume", 1.0),
                guildId = o.str("guild_id"),
                available = o.optBoolean("available", true),
            )
        }.filter { it.available }
    }
}

/** What an invite leads to, before joining. */
data class InvitePreview(
    val code: String,
    val guild: Guild?,
    val channelName: String?,
    val memberCount: Int?,
    val onlineCount: Int?,
) {
    companion object {
        fun fromJson(o: JSONObject): InvitePreview = InvitePreview(
            code = o.optString("code"),
            guild = o.optJSONObject("guild")?.let { runCatching { Guild.fromJson(it) }.getOrNull() },
            channelName = o.optJSONObject("channel")?.str("name"),
            memberCount = o.optInt("approximate_member_count", -1).takeIf { it >= 0 },
            onlineCount = o.optInt("approximate_presence_count", -1).takeIf { it >= 0 },
        )

        private val CODE = Regex("(?:https?://)?(?:www\\.)?(?:discord\\.gg|discord(?:app)?\\.com/invite)/([A-Za-z0-9-]{2,32})")

        /** The code in an invite link ("discord.gg/abc", "discord.com/invite/abc") or a bare code; null otherwise. */
        fun codeFrom(text: String): String? {
            val t = text.trim()
            CODE.find(t)?.let { return it.groupValues[1] }
            return t.takeIf { it.matches(Regex("[A-Za-z0-9-]{2,32}")) }
        }
    }
}

/** A slash command option. [type] 3 string, 4 integer, 5 boolean, 10 number (others are listed but not offered). */
data class CommandOption(
    val type: Int,
    val name: String,
    val description: String,
    val required: Boolean,
    val choices: List<Pair<String, Any>>,
) {
    val supported: Boolean get() = type in setOf(3, 4, 5, 10)

    companion object {
        fun listFromJson(arr: JSONArray?): List<CommandOption> = arr.mapObjects { o ->
            CommandOption(
                type = o.optInt("type"),
                name = o.optString("name"),
                description = o.optString("description"),
                required = o.optBoolean("required", false),
                choices = o.optJSONArray("choices").mapObjects { c -> c.optString("name") to c.opt("value") },
            )
        }
    }
}

/** A slash command a bot offers here. [raw] is sent back with the interaction, as Discord's own client does. */
data class ApplicationCommand(
    val id: String,
    val applicationId: String,
    val version: String,
    val name: String,
    val description: String,
    val options: List<CommandOption>,
    val appName: String?,
    val raw: JSONObject,
) {
    /** Only commands whose required options we can fill in are offered. */
    val usable: Boolean get() = options.none { it.required && !it.supported } && options.none { it.type == 1 || it.type == 2 }

    companion object {
        /** From an `application-command-index` reply: chat-input commands with their app's name. */
        fun indexFromJson(o: JSONObject): List<ApplicationCommand> {
            val apps = o.optJSONArray("applications").mapObjects { it.optString("id") to it.optString("name") }.toMap()
            return o.optJSONArray("application_commands").mapObjects { c ->
                if (c.optInt("type", 1) != 1) return@mapObjects null
                ApplicationCommand(
                    id = c.getString("id"),
                    applicationId = c.getString("application_id"),
                    version = c.optString("version"),
                    name = c.optString("name"),
                    description = c.optString("description"),
                    options = CommandOption.listFromJson(c.optJSONArray("options")),
                    appName = apps[c.optString("application_id")],
                    raw = c,
                )
            }
        }
    }
}

/** A message the user bookmarked on this watch. */
data class SavedMessage(
    val messageId: String,
    val channelId: String,
    val guildId: String?,
    val channelName: String,
    val author: String,
    val preview: String,
    val savedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("m", messageId).put("c", channelId).put("g", guildId ?: "").put("n", channelName)
        .put("a", author).put("p", preview).put("t", savedAt)

    companion object {
        fun fromJson(o: JSONObject) = SavedMessage(
            o.getString("m"), o.getString("c"), o.optString("g").takeIf { it.isNotEmpty() },
            o.optString("n"), o.optString("a"), o.optString("p"), o.optLong("t")
        )
    }
}

/** A Discord link to a message or channel ("discord.com/channels/guild-or-@me/channel/message"). */
data class DiscordLink(val guildId: String?, val channelId: String, val messageId: String?) {
    companion object {
        private val LINK = Regex("^https?://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/channels/(@me|\\d+)/(\\d+)(?:/(\\d+))?/?$")

        fun parse(url: String): DiscordLink? {
            val m = LINK.find(url.trim()) ?: return null
            val (guild, channel, message) = m.destructured
            return DiscordLink(guild.takeIf { it != "@me" }, channel, message.takeIf { it.isNotEmpty() })
        }
    }
}
