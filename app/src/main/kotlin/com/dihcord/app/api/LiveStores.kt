package com.dihcord.app.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which servers and channels have messages we haven't read, worked out the way Discord does: a text
 * channel is unread when its newest message is past our read marker, if we can see it and it isn't
 * muted. Channels we have never opened (no read marker) count as read, so a big server doesn't light
 * up for channels the user ignores.
 */
class UnreadTracker {
    private data class GuildInfo(
        val ownerId: String?,
        val roles: List<GuildRole>,
        val myRoleIds: List<String>?,
        val channels: Map<String, Channel>,
    )

    private val guilds = java.util.concurrent.ConcurrentHashMap<String, GuildInfo>()
    private val lastIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Which server each channel belongs to, so a message only re-checks its own server. */
    private val channelGuild = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var myId: String? = null
    @Volatile private var settings: Map<String, NotifySettings> = emptyMap()

    private val _unreadGuilds = MutableStateFlow<Set<String>>(emptySet())
    /** Servers with at least one unread channel. */
    val unreadGuilds: StateFlow<Set<String>> = _unreadGuilds.asStateFlow()

    fun onReady(userId: String, ready: List<ReadyGuild>, notify: Map<String, NotifySettings>, readState: Map<String, ChannelUnreadState>) {
        myId = userId
        settings = notify
        ready.forEach { g ->
            guilds[g.id] = GuildInfo(g.ownerId, g.roles, g.myRoleIds, g.channels.associateBy { it.id })
            g.channels.forEach { ch ->
                channelGuild[ch.id] = g.id
                ch.lastMessageId?.let { lastIds[ch.id] = it }
            }
        }
        recompute(readState)
    }

    fun onSettings(guildId: String, value: NotifySettings, readState: Map<String, ChannelUnreadState>) {
        settings = settings + (guildId to value)
        recompute(readState)
    }

    /** A message arrived: its channel's newest message moves on. */
    fun onMessage(channelId: String, messageId: String, readState: Map<String, ChannelUnreadState>) {
        val known = lastIds[channelId]
        if (known == null || compareSnowflakes(messageId, known) > 0) lastIds[channelId] = messageId
        channelGuild[channelId]?.let { recomputeGuild(it, readState) }
    }

    /** Fresher channel data (e.g. after the channel list loaded, or a CHANNEL_UPDATE). */
    fun onChannels(guildId: String, channels: List<Channel>, readState: Map<String, ChannelUnreadState>) {
        val info = guilds[guildId] ?: GuildInfo(null, emptyList(), null, emptyMap())
        guilds[guildId] = info.copy(channels = info.channels + channels.associateBy { it.id })
        channels.forEach { ch ->
            channelGuild[ch.id] = guildId
            val id = ch.lastMessageId ?: return@forEach
            val known = lastIds[ch.id]
            if (known == null || compareSnowflakes(id, known) > 0) lastIds[ch.id] = id
        }
        recomputeGuild(guildId, readState)
    }

    fun onRoles(guildId: String, myRoleIds: List<String>, readState: Map<String, ChannelUnreadState>) {
        guilds[guildId]?.let { guilds[guildId] = it.copy(myRoleIds = myRoleIds) }
        recompute(readState)
    }

    fun forgetGuild(guildId: String, readState: Map<String, ChannelUnreadState>) {
        guilds.remove(guildId)
        recompute(readState)
    }

    fun recompute(readState: Map<String, ChannelUnreadState>) {
        _unreadGuilds.value = guilds.keys.filterTo(HashSet()) { hasUnread(it, readState) }
    }

    /** Re-checks one server only (a message there, or its channels changed). */
    private fun recomputeGuild(guildId: String, readState: Map<String, ChannelUnreadState>) {
        val now = hasUnread(guildId, readState)
        if ((guildId in _unreadGuilds.value) != now) {
            _unreadGuilds.update { if (now) it + guildId else it - guildId }
        }
    }

    private fun hasUnread(guildId: String, readState: Map<String, ChannelUnreadState>) = unreadChannels(guildId, readState, stopAtFirst = true).isNotEmpty()

    /** (channel id, newest message id) for each unread channel of [guildId]. */
    fun unreadChannels(guildId: String, readState: Map<String, ChannelUnreadState>, stopAtFirst: Boolean = false): List<Pair<String, String>> {
        val info = guilds[guildId] ?: return emptyList()
        val guildSettings = settings[guildId]
        if (guildSettings?.mutedNow() == true) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (ch in info.channels.values) {
            // Voice channels have a text chat too.
            if (!ch.isText && !ch.isVoice) continue
            val last = lastIds[ch.id] ?: continue
            val read = readState[ch.id] ?: continue
            // The cheap checks first; permissions only for channels that really have something new.
            if (compareSnowflakes(last, read.lastMessageId) <= 0) continue
            if (guildSettings?.channels?.get(ch.id)?.mutedNow() == true) continue
            if (ch.parentId != null && guildSettings?.channels?.get(ch.parentId)?.mutedNow() == true) continue
            if (!canView(guildId, info, ch)) continue
            out += ch.id to last
            if (stopAtFirst) break
        }
        return out
    }

    /** The newest message we know of in [channelId]. */
    fun lastMessageId(channelId: String): String? = lastIds[channelId]

    private fun canView(guildId: String, info: GuildInfo, channel: Channel): Boolean {
        val me = myId ?: return false
        if (info.ownerId == me) return true
        // Without our roles, judge by @everyone alone: it may hide a channel we can see, never the reverse.
        if (info.roles.none { it.id == guildId }) return false
        val perms = Permissions.effectiveForMember(
            member = GuildMember(userId = me, roleIds = info.myRoleIds.orEmpty()),
            guildId = guildId,
            everyoneRoleId = guildId,
            roles = info.roles,
            channel = channel,
        )
        return Permissions.has(perms, Permissions.VIEW_CHANNEL)
    }
}

/** A server's sidebar member list, as Discord streams it (GUILD_MEMBER_LIST_UPDATE). */
data class MemberList(val memberCount: Int, val onlineCount: Int, val rows: List<MemberListItem?>)

class MemberListStore {
    private val _lists = MutableStateFlow<Map<String, MemberList>>(emptyMap())
    /** By guild id. */
    val lists: StateFlow<Map<String, MemberList>> = _lists.asStateFlow()

    fun apply(event: GatewayEvent.MemberListUpdate) {
        _lists.update { all ->
            val old = all[event.guildId]
            val rows = MemberListOp.apply(old?.rows.orEmpty(), event.ops)
            all + (event.guildId to MemberList(event.memberCount, event.onlineCount, rows.take(MAX_ROWS)))
        }
    }

    fun clear() { _lists.value = emptyMap() }

    private companion object { const val MAX_ROWS = 200 }
}

/** Friends, blocks and pending requests, by user id. */
class RelationshipStore {
    private val _all = MutableStateFlow<Map<String, Relationship>>(emptyMap())
    val all: StateFlow<Map<String, Relationship>> = _all.asStateFlow()

    fun set(list: List<Relationship>) { _all.value = list.associateBy { it.user.id } }
    fun put(r: Relationship) = _all.update { it + (r.user.id to r) }
    fun remove(userId: String) = _all.update { it - userId }

    fun isBlocked(userId: String): Boolean = _all.value[userId]?.isBlocked == true
}

/**
 * The newest page of recent chats kept on disk (as Discord sent it), so they can be read with no
 * connection. Only the most recent channels are kept; everything is wiped on log-out.
 */
class MessageCache(private val dir: java.io.File?) {
    fun save(channelId: String, rawPage: String) {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            java.io.File(d, "$channelId.json").writeText(rawPage)
            val files = d.listFiles().orEmpty().sortedByDescending { it.lastModified() }
            files.drop(MAX_CHANNELS).forEach { it.delete() }
        }
    }

    /** Oldest first, like the chat holds them; null when nothing is saved. */
    fun load(channelId: String): List<DiscordMessage>? {
        val f = java.io.File(dir ?: return null, "$channelId.json")
        if (!f.exists()) return null
        return runCatching { DiscordMessage.listFromJson(org.json.JSONArray(f.readText())).reversed() }.getOrNull()
    }

    fun clear() { dir?.deleteRecursively() }

    companion object {
        const val MAX_CHANNELS = 30
        fun dirFor(context: android.content.Context) = java.io.File(context.filesDir, "message_cache")
    }
}
