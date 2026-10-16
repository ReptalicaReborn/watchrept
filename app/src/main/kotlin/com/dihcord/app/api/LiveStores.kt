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
    /** Threads by id -> server. They don't light the server dot, but "mark read" must reach them. */
    private val threadGuild = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var myId: String? = null
    @Volatile private var settings: Map<String, NotifySettings> = emptyMap()

    private val _unreadGuilds = MutableStateFlow<Set<String>>(emptySet())
    /** Servers with at least one unread channel. */
    val unreadGuilds: StateFlow<Set<String>> = _unreadGuilds.asStateFlow()

    fun onReady(userId: String, ready: List<ReadyGuild>, notify: Map<String, NotifySettings>, readState: Map<String, ChannelUnreadState>) {
        myId = userId
        settings = notify
        ready.forEach { put(it) }
        recompute(readState)
    }

    /** A server joined while connected: it comes with its channels, roles and our member, like in READY. */
    fun onGuildJoined(guild: ReadyGuild, readState: Map<String, ChannelUnreadState>) {
        put(guild)
        recomputeGuild(guild.id, readState)
    }

    private fun put(g: ReadyGuild) {
        guilds[g.id] = GuildInfo(g.ownerId, g.roles, g.myRoleIds, g.channels.associateBy { it.id })
        g.channels.forEach { ch ->
            channelGuild[ch.id] = g.id
            ch.lastMessageId?.let { lastIds[ch.id] = it }
        }
        g.threads.forEach { t ->
            threadGuild[t.id] = g.id
            t.lastMessageId?.let { lastIds[t.id] = it }
        }
    }

    fun onSettings(guildId: String, value: NotifySettings, readState: Map<String, ChannelUnreadState>) {
        settings = settings + (guildId to value)
        recompute(readState)
    }

    /** A message arrived: its channel's newest message moves on. A server message in a channel we don't know is a thread. */
    fun onMessage(channelId: String, messageId: String, readState: Map<String, ChannelUnreadState>, guildId: String? = null) {
        val known = lastIds[channelId]
        if (known == null || compareSnowflakes(messageId, known) > 0) lastIds[channelId] = messageId
        if (guildId != null && !channelGuild.containsKey(channelId)) threadGuild[channelId] = guildId
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

    /** A channel was deleted: it can't be unread any more. */
    fun onChannelDelete(guildId: String, channelId: String, readState: Map<String, ChannelUnreadState>) {
        guilds[guildId]?.let { guilds[guildId] = it.copy(channels = it.channels - channelId) }
        lastIds.remove(channelId)
        channelGuild.remove(channelId)
        threadGuild.remove(channelId)
        recomputeGuild(guildId, readState)
    }

    fun onRoles(guildId: String, myRoleIds: List<String>, readState: Map<String, ChannelUnreadState>) {
        guilds[guildId]?.let { guilds[guildId] = it.copy(myRoleIds = myRoleIds) }
        recompute(readState)
    }

    fun forgetGuild(guildId: String, readState: Map<String, ChannelUnreadState>) {
        guilds.remove(guildId)
        threadGuild.values.removeAll { it == guildId }
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

    /**
     * (channel id, newest message id) for each unread channel of [guildId]. Muted ones don't count for the dot,
     * but [includeMuted] lists them too, for marking everything read.
     */
    fun unreadChannels(
        guildId: String,
        readState: Map<String, ChannelUnreadState>,
        stopAtFirst: Boolean = false,
        includeMuted: Boolean = false,
    ): List<Pair<String, String>> {
        val info = guilds[guildId] ?: return emptyList()
        val guildSettings = settings[guildId].takeUnless { includeMuted }
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

    /** (thread id, newest message id) for each thread of [guildId] with messages past our read marker. */
    fun unreadThreads(guildId: String, readState: Map<String, ChannelUnreadState>): List<Pair<String, String>> =
        threadGuild.entries.mapNotNull { (threadId, g) ->
            if (g != guildId) return@mapNotNull null
            val last = lastIds[threadId] ?: return@mapNotNull null
            val read = readState[threadId] ?: return@mapNotNull null
            if (compareSnowflakes(last, read.lastMessageId) > 0) threadId to last else null
        }

    /** [channelId]'s category, from the server data READY (or a join) gave us. */
    fun parentOf(channelId: String): String? = channelGuild[channelId]?.let { guilds[it]?.channels?.get(channelId)?.parentId }

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
data class MemberList(val memberCount: Int, val onlineCount: Int, val rows: List<MemberListItem?>, val listId: String = "")

class MemberListStore {
    private val _lists = MutableStateFlow<Map<String, MemberList>>(emptyMap())
    /** By guild id. */
    val lists: StateFlow<Map<String, MemberList>> = _lists.asStateFlow()

    fun apply(event: GatewayEvent.MemberListUpdate) {
        _lists.update { all ->
            // Each channel's list (who can see it) is its own; never mix one into another.
            val old = all[event.guildId]?.takeIf { it.listId == event.listId }
            val rows = MemberListOp.apply(old?.rows.orEmpty(), event.ops)
            all + (event.guildId to MemberList(event.memberCount, event.onlineCount, rows.take(MAX_ROWS), event.listId))
        }
    }

    /** Drops [guildId]'s list, so the next one shown is the one just asked for. */
    fun reset(guildId: String) = _lists.update { it - guildId }

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
    /** Set by [clear] (sign-out): a page still on its way must not write the old account back to disk. */
    @Volatile private var closed = false

    fun save(channelId: String, rawPage: String) {
        val d = dir ?: return
        synchronized(LOCK) {
            if (closed) return
            runCatching {
                d.mkdirs()
                java.io.File(d, "$channelId.json").writeText(rawPage)
                val files = d.listFiles().orEmpty().sortedByDescending { it.lastModified() }
                files.drop(MAX_CHANNELS).forEach { it.delete() }
            }
        }
    }

    /** Oldest first, like the chat holds them; null when nothing is saved. */
    fun load(channelId: String): List<DiscordMessage>? {
        val f = java.io.File(dir ?: return null, "$channelId.json")
        if (!f.exists()) return null
        return runCatching { DiscordMessage.listFromJson(org.json.JSONArray(f.readText())).reversed() }.getOrNull()
    }

    fun clear() = synchronized(LOCK) {
        closed = true
        dir?.deleteRecursively()
    }

    companion object {
        const val MAX_CHANNELS = 30
        /** One lock for every instance: sign-out clears with its own instance while the repository's may save. */
        private val LOCK = Any()
        fun dirFor(context: android.content.Context) = java.io.File(context.filesDir, "message_cache")
    }
}
