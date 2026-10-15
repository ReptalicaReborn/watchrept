package com.dihcord.app.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dihcord.app.SetupPreferences
import org.json.JSONArray
import org.json.JSONObject

/** What a notification for an incoming message should say. */
data class NotificationInfo(
    val channelId: String,
    val title: String,
    val sender: String,
    val text: String,
    val isDm: Boolean,
    val guildId: String? = null,
    /** Name used for the chat screen's title. */
    val channelName: String = "",
)

class DiscordRepository(token: String, private val context: Context? = null) {

    // An unexpected error in one background job must not take the whole app down.
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> android.util.Log.e("DiscordRepository", "Background job failed", e) }
    )
    val rest = DiscordRestClient(token)
    private val gateway = DiscordGateway(token)

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences("discord_wear_cache", Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow<DiscordUser?>(null)
    val currentUser: StateFlow<DiscordUser?> = _currentUser.asStateFlow()

    private val _isTokenInvalid = MutableStateFlow(false)
    val isTokenInvalid: StateFlow<Boolean> = _isTokenInvalid.asStateFlow()

    fun handleApiError(e: Throwable) {
        if (e is DiscordApiException && e.httpCode == 401) {
            _isTokenInvalid.value = true
        }
    }

    @Volatile
    private var currentUserId: String? = null

    private val _guilds = MutableStateFlow<List<Guild>>(loadCachedGuilds())
    val guilds: StateFlow<List<Guild>> = _guilds.asStateFlow()

    // Filled from the cache in init {} at the end: the caches it also fills aren't initialised yet here.
    private val _dmChannels = MutableStateFlow<List<Channel>>(emptyList())
    val dmChannels: StateFlow<List<Channel>> = _dmChannels.asStateFlow()

    private val _guildFolders = MutableStateFlow<List<GuildFolder>>(emptyList())
    /** The user's server folder layout from Discord; empty until known. */
    val guildFolders: StateFlow<List<GuildFolder>> = _guildFolders.asStateFlow()

    /** Fetches the folder layout over REST if the gateway hasn't provided it. */
    suspend fun refreshGuildFolders() {
        if (_guildFolders.value.isNotEmpty()) return
        rest.getGuildFolders().onSuccess { if (it.isNotEmpty()) _guildFolders.value = it }
    }

    private val _channelNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val channelNames: StateFlow<Map<String, String>> = _channelNames.asStateFlow()

    private val _channelGuilds = MutableStateFlow<Map<String, String>>(emptyMap())
    val channelGuilds: StateFlow<Map<String, String>> = _channelGuilds.asStateFlow()

    private val _guildChannelsMap = MutableStateFlow<Map<String, List<CategoryGroup>>>(emptyMap())
    val guildChannelsMap: StateFlow<Map<String, List<CategoryGroup>>> =
        _guildChannelsMap.asStateFlow()

    suspend fun refreshGuildChannels(
        guildId: String,
        filterInaccessible: Boolean = false
    ): Result<List<CategoryGroup>> {
        val hideInaccessible =
            context?.let { SetupPreferences.getHideInaccessibleChannels(it) } ?: filterInaccessible
        return rest.getGuildChannels(guildId, hideInaccessible).onSuccess { groups ->
            cacheChannelNames(groups)
            saveChannels(guildId, groups)
            _guildChannelsMap.update { current -> current + (guildId to groups) }
        }.onFailure { handleApiError(it) }
    }

    private val _messages = MutableStateFlow<Map<String, List<DiscordMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<DiscordMessage>>> = _messages.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    private val _pings = MutableStateFlow<List<Ping>>(emptyList())
    val pings: StateFlow<List<Ping>> = _pings.asStateFlow()

    private val _readState = MutableStateFlow<Map<String, ChannelUnreadState>>(emptyMap())
    val readState: StateFlow<Map<String, ChannelUnreadState>> = _readState.asStateFlow()

    // Filled for every server from READY (and lazily over REST), read from the UI: must be thread-safe.
    private val emojiCache = java.util.concurrent.ConcurrentHashMap<String, List<GuildEmoji>>()
    private val stickerCache = java.util.concurrent.ConcurrentHashMap<String, List<StickerItem>>()
    // These are touched from the gateway, background loads and the UI at once, so they must be thread-safe.
    private val channelNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val channelGuildCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val memberRolesCache: MutableMap<String, List<String>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<String>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, List<String>>?) =
                size > 100 // Max 100 user role lookups cached
        })
    private val guildRolesCache: MutableMap<String, List<GuildRole>> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<GuildRole>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, List<GuildRole>>?) =
                size > 20 // Max 20 guilds' roles cached
        })
    private val guildMemberCache: MutableMap<String, GuildMember> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, GuildMember>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, GuildMember>?) = size > 200
        })

    suspend fun getGuildMember(guildId: String, userId: String): GuildMember? {
        val cacheKey = "$guildId:$userId"
        guildMemberCache[cacheKey]?.let { return it }
        val member = rest.getGuildMember(guildId, userId).getOrNull()
        if (member != null) {
            guildMemberCache[cacheKey] = member
        }
        return member
    }

    private val typingJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val lastSentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun slowModeRemainingSeconds(channelId: String): Int {
        val ch = channelCache[channelId] ?: return 0
        val intervalMs = ch.slowModeSeconds * 1000L
        if (intervalMs <= 0) return 0
        val lastMs = lastSentAt[channelId] ?: return 0
        val elapsed = System.currentTimeMillis() - lastMs
        val remaining = intervalMs - elapsed
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    fun getSlowModeSeconds(channelId: String): Int =
        channelCache[channelId]?.slowModeSeconds ?: 0

    private val userDisplayNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val myRolesByGuild = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val channelCache = java.util.concurrent.ConcurrentHashMap<String, Channel>()
    private val _presences = MutableStateFlow<Map<String, UserPresence>>(emptyMap())
    val presences: StateFlow<Map<String, UserPresence>> = _presences.asStateFlow()

    fun getChannelNames(): Map<String, String> = channelNameCache.toMap()
    fun getChannelGuilds(): Map<String, String> = channelGuildCache.toMap()
    fun getMyRoles(guildId: String): List<String> = myRolesByGuild[guildId] ?: emptyList()

    private val guildOwnerCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * What the current user may post in [channelId] (text, files, voice messages), computed the way Discord does (base role
     * permissions, then @everyone / role / member overwrites; administrators and the guild owner
     * bypass everything). Fails open whenever something can't be determined, so the input box is only
     * hidden when we are sure the server would reject the message; the server stays the authority.
     */
    suspend fun channelAbilities(channelId: String): ChannelAbilities {
        val channel = channelCache[channelId] ?: return ChannelAbilities.ALL
        if (channel.type != ChannelType.GUILD_TEXT && channel.type != ChannelType.GUILD_NEWS) return ChannelAbilities.ALL
        val guildId = channel.guildId ?: return ChannelAbilities.ALL
        val myId = currentUserId ?: return ChannelAbilities.ALL

        val ownerId = guildOwnerCache[guildId]
            ?: (rest.getGuildOwnerId(guildId).getOrNull() ?: return ChannelAbilities.ALL).also { guildOwnerCache[guildId] = it }

        val roleIds = myRolesByGuild[guildId]
            ?: (rest.getGuildMember(guildId).getOrNull()?.roleIds
                ?: rest.getGuildMemberRoles(guildId, myId).getOrNull()
                ?: return ChannelAbilities.ALL)
                .also { myRolesByGuild[guildId] = it }
        val roles = getGuildRoles(guildId)
        // Without the @everyone role we can't know the base permissions: don't hide anything, let Discord decide.
        if (roles.none { it.id == guildId }) return ChannelAbilities.ALL
        return Permissions.abilities(
            member = GuildMember(userId = myId, roleIds = roleIds),
            guildId = guildId,
            ownerId = ownerId,
            roles = roles,
            channel = channel,
        )
    }

    fun connect() {
        gateway.connect()
        observeGatewayEvents()
        scope.launch { refreshCurrentUser() }
        scope.launch { refreshGuilds() }
        scope.launch { refreshDmChannels() }
        scope.launch {
            val cachedGuildIds = loadCachedGuilds().map { it.id }
            val initialMap = mutableMapOf<String, List<CategoryGroup>>()
            for (guildId in cachedGuildIds) {
                val groups = getCachedChannels(guildId, filterInaccessible = false) ?: continue
                initialMap[guildId] = groups
                groups.forEach { group ->
                    group.channels.forEach { ch ->
                        channelNameCache[ch.id] = ch.name
                        channelGuildCache[ch.id] = guildId
                    }
                }
            }
            _guildChannelsMap.value = initialMap
            _channelNames.value = channelNameCache.toMap()
            _channelGuilds.value = channelGuildCache.toMap()
        }
    }

    fun disconnect() = gateway.disconnect()

    /** Shuts this repository down for good (logout or a new login): disconnects and stops all its background work. */
    fun close() {
        gateway.shutdown()
        scope.cancel()
    }

    @Volatile private var paused = false

    /** Drops the gateway connection to save battery while the app is idle in the background. */
    fun pauseConnection() {
        paused = true
        gateway.pause()
    }

    /** Reconnects after [pauseConnection]; a no-op if the connection was never paused. */
    fun resumeConnection() {
        if (!paused) return
        paused = false
        gateway.connect()
    }

    private val memberChunks = MutableSharedFlow<GatewayEvent.MembersChunk>(extraBufferCapacity = 8)

    /**
     * People to suggest for "@[query]" in [channelId]: everyone seen in the loaded conversation (authors,
     * mentioned users, DM recipients), plus, in servers, a live member search over the gateway.
     */
    suspend fun mentionCandidates(channelId: String, guildId: String?, query: String): List<MentionCandidate> {
        val myId = currentUserId
        val local = buildList {
            _messages.value[channelId].orEmpty().asReversed().forEach { m ->
                add(MentionCandidate(m.author.id, m.author.displayName, m.author.username))
                m.mentionedUsers.forEach { add(MentionCandidate(it.id, it.displayName, it.username)) }
            }
            _dmChannels.value.firstOrNull { it.id == channelId }?.recipients?.forEach {
                add(MentionCandidate(it.id, it.displayName, it.username))
            }
        }
        val remote = if (guildId != null && query.isNotEmpty()) {
            val nonce = java.util.UUID.randomUUID().toString().take(16)
            kotlinx.coroutines.coroutineScope {
                val reply = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    memberChunks.first { it.nonce == nonce }
                }
                gateway.requestGuildMembers(guildId, query, limit = 10, nonce = nonce)
                val chunk = kotlinx.coroutines.withTimeoutOrNull(1_500) { reply.await() }
                reply.cancel()
                chunk?.members.orEmpty()
            }
        } else emptyList()
        return MentionInput.rank((local + remote).filter { it.id != myId }, query)
    }

    /** Channels of [guildId] matching "#[query]" that the user can see (loads the channel list if needed). */
    suspend fun channelCandidates(guildId: String, query: String): List<MentionCandidate> {
        val groups = _guildChannelsMap.value[guildId]
            ?: refreshGuildChannels(guildId).getOrNull()
            ?: return emptyList()
        val channels = groups.flatMap { g ->
            g.channels.filter { it.hasAccess }.map {
                MentionCandidate(it.id, it.name, g.category?.name.orEmpty(), MentionCandidate.Kind.CHANNEL)
            }
        }
        return MentionInput.rank(channels, query)
    }

    private val _newMessages = MutableSharedFlow<DiscordMessage>(extraBufferCapacity = 32)

    /** Every message received live over the gateway (including the user's own). */
    val newMessages: SharedFlow<DiscordMessage> = _newMessages.asSharedFlow()

    /**
     * Decides whether [msg] deserves a notification (a DM, or a mention of the user / their roles / @everyone,
     * never their own messages) and builds its text. Null when it should stay silent.
     */
    suspend fun notificationFor(msg: DiscordMessage): NotificationInfo? {
        val myId = currentUserId ?: return null
        if (msg.author.id == myId) return null
        val isDm = _dmChannels.value.any { it.id == msg.channelId }
        val guildId = msg.guildId ?: channelGuildCache[msg.channelId]
        val mentioned = if (isDm) true else {
            val roles = if (guildId != null && msg.mentionedRoleIds.isNotEmpty()) {
                myRolesByGuild[guildId]
                    ?: rest.getGuildMemberRoles(guildId, myId).getOrNull()?.also { myRolesByGuild[guildId] = it }
                    ?: emptyList()
            } else emptyList()
            msg.pingFor(myId, roles)
        }
        if (!mentioned) return null

        val text = msg.content
            .replace(Regex("<@!?(\\d+)>")) { m -> "@" + (userDisplayNames[m.groupValues[1]] ?: "user") }
            .replace(Regex("<#(\\d+)>")) { m -> "#" + (channelNameCache[m.groupValues[1]] ?: "channel") }
            .replace(Regex("<a?:(\\w+):\\d+>")) { m -> ":" + m.groupValues[1] + ":" }
            .ifBlank {
                when {
                    msg.attachments.isNotEmpty() -> "[attachment]"
                    msg.stickers.isNotEmpty() -> "[sticker]"
                    msg.poll != null -> "[poll] ${msg.poll.question}"
                    else -> "[message]"
                }
            }
        val title = if (isDm) {
            msg.author.displayName
        } else {
            val ch = channelNameCache[msg.channelId]?.let { "#$it" } ?: "a channel"
            val guild = guildId?.let { id -> _guilds.value.firstOrNull { it.id == id }?.name }
            if (guild != null) "$ch · $guild" else ch
        }
        return NotificationInfo(
            channelId = msg.channelId,
            title = title,
            sender = msg.author.displayName,
            text = text,
            isDm = isDm,
            guildId = if (isDm) null else guildId,
            channelName = if (isDm) {
                _dmChannels.value.firstOrNull { it.id == msg.channelId }?.displayName ?: msg.author.displayName
            } else {
                channelNameCache[msg.channelId] ?: msg.channelId
            },
        )
    }
    fun selectChannel(channelId: String, guildId: String?) {
        gateway.selectChannel(channelId, guildId)
    }

    fun refreshOnResume(activeChannelId: String?) {
        scope.launch {
            runCatching { refreshDmChannels() }
            if (activeChannelId != null) {
                runCatching { loadMessages(activeChannelId) }
            }
        }
    }

    suspend fun refreshCurrentUser() {
        rest.getCurrentUser().onSuccess {
            _currentUser.value = it
            currentUserId = it.id
        }.onFailure { handleApiError(it) }
    }

    suspend fun refreshGuilds() {
        rest.getGuilds().onSuccess { list ->
            _guilds.value = list
            saveGuilds(list)
        }.onFailure { handleApiError(it) }
    }

    suspend fun refreshDmChannels() {
        rest.getDmChannels().onSuccess { list ->
            _dmChannels.value = list
            list.forEach { ch ->
                channelNameCache[ch.id] = ch.displayName
                channelCache[ch.id] = ch
            }
            _channelNames.value = channelNameCache.toMap()
            saveDmChannels(list)
        }.onFailure { handleApiError(it) }
    }

    fun cacheChannelNames(groups: List<CategoryGroup>) {
        groups.forEach { g ->
            g.channels.forEach { ch ->
                channelNameCache[ch.id] = ch.name
                channelCache[ch.id] = ch
                ch.guildId?.let { channelGuildCache[ch.id] = it }
            }
        }
        _channelNames.value = channelNameCache.toMap()
        _channelGuilds.value = channelGuildCache.toMap()
    }

    private val fetchingChannels: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun ensureChannelCached(channelId: String) {
        if (channelNameCache.containsKey(channelId) || !fetchingChannels.add(channelId)) return
        scope.launch {
            try {
                fetchChannelIntoCache(channelId)
            } finally {
                fetchingChannels.remove(channelId)
            }
        }
    }

    private suspend fun fetchChannelIntoCache(channelId: String) {
        run {
            rest.getChannel(channelId).onSuccess { ch ->
                channelCache[ch.id] = ch
                channelNameCache[ch.id] = ch.displayName
                ch.guildId?.let { gId ->
                    channelGuildCache[ch.id] = gId
                }
                _channelNames.value = channelNameCache.toMap()
                _channelGuilds.value = channelGuildCache.toMap()
            }
        }
    }

    /** Returns the highest-position coloured role for a user, or null if none. */
    suspend fun getTopRoleForUser(guildId: String, userId: String): GuildRole? {
        val cacheKey = "$guildId/$userId"
        val roleIds = memberRolesCache.getOrPut(cacheKey) {
            rest.getGuildMemberRoles(guildId, userId).getOrNull() ?: return null
        }
        val roles = guildRolesCache.getOrPut(guildId) {
            rest.getGuildRoles(guildId).getOrNull() ?: return null
        }
        val roleMap = roles.associateBy { it.id }
        return roleIds
            .mapNotNull { roleMap[it] }
            .filter { it.color != 0 || it.unicodeEmoji != null || it.iconHash != null }
            .maxByOrNull { it.position }
    }

    suspend fun getRolesForUser(guildId: String, userId: String): List<GuildRole> {
        val cacheKey = "$guildId/$userId"
        val roleIds = memberRolesCache.getOrPut(cacheKey) {
            rest.getGuildMemberRoles(guildId, userId).getOrNull() ?: return emptyList()
        }
        val roles = guildRolesCache.getOrPut(guildId) {
            rest.getGuildRoles(guildId).getOrNull() ?: return emptyList()
        }
        val roleMap = roles.associateBy { it.id }
        return roleIds
            .mapNotNull { roleMap[it] }
            .sortedByDescending { it.position }
    }

    suspend fun getRoleWithIconForUser(guildId: String, userId: String): GuildRole? {
        val roles = getRolesForUser(guildId, userId)
        return roles.firstOrNull { !it.unicodeEmoji.isNullOrEmpty() || !it.iconHash.isNullOrEmpty() }
    }

    suspend fun getGuildRoles(guildId: String): List<GuildRole> {
        guildRolesCache[guildId]?.let { return it }
        // A failed fetch (e.g. rate limited while role colours load) must not be cached as "no roles": the
        // permission checks would then think the user has none of @everyone's permissions in this server.
        return rest.getGuildRoles(guildId).getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.also { guildRolesCache[guildId] = it }
            ?: emptyList()
    }

    @Deprecated("Use getTopRoleForUser instead")
    suspend fun getRoleColorForUser(guildId: String, userId: String): Int? =
        getTopRoleForUser(guildId, userId)?.color

    suspend fun getGuildEmojis(guildId: String): List<GuildEmoji> {
        emojiCache[guildId]?.let { return it }
        rest.getGuildEmojis(guildId).onSuccess { list ->
            emojiCache[guildId] = list
        }
        return emojiCache[guildId] ?: emptyList()
    }

    /** One server's emojis or stickers for the picker. */
    data class GuildSet<T>(val guildId: String, val guildName: String, val items: List<T>)

    /**
     * Emojis of every server, [firstGuildId] first, then in sidebar order. Servers missing from the READY data
     * are fetched over REST a few at a time.
     */
    suspend fun allGuildEmojis(firstGuildId: String?): List<GuildSet<GuildEmoji>> =
        allGuildSets(firstGuildId) { getGuildEmojis(it) }

    suspend fun allGuildStickers(firstGuildId: String?): List<GuildSet<StickerItem>> =
        allGuildSets(firstGuildId) { getGuildStickers(it) }

    private suspend fun <T> allGuildSets(
        firstGuildId: String?,
        load: suspend (String) -> List<T>
    ): List<GuildSet<T>> = kotlinx.coroutines.coroutineScope {
        val guilds = _guilds.value.sortedBy { if (it.id == firstGuildId) 0 else 1 }
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        guilds.map { g ->
            async { gate.withPermit { GuildSet(g.id, g.name, runCatching { load(g.id) }.getOrDefault(emptyList())) } }
        }.awaitAll().filter { it.items.isNotEmpty() }
    }

    suspend fun getGuildStickers(guildId: String): List<StickerItem> {
        stickerCache[guildId]?.let { return it }
        rest.getGuildStickers(guildId).onSuccess { list ->
            val displayable = list.filter { it.isDisplayable }
            stickerCache[guildId] = displayable
            return displayable
        }
        return emptyList()
    }

    fun getCachedChannels(guildId: String, filterInaccessible: Boolean): List<CategoryGroup>? =
        runCatching {
            val json = prefs?.getString("channels_$guildId", null) ?: return null
            val arr = JSONArray(json)
            val allChannels = Channel.listFromJson(arr)

            // Populate in-memory caches
            allChannels.forEach { ch ->
                channelCache[ch.id] = ch
                channelNameCache[ch.id] = ch.displayName
                channelGuildCache[ch.id] = guildId
            }

            val textChannels = allChannels.filter { it.isText }
            val categories = allChannels.filter { it.isCategory }.sortedBy { it.position }
            val byParent = textChannels.groupBy { it.parentId }
            val groups = mutableListOf<CategoryGroup>()
            val topLevel = byParent[null].orEmpty()
                .filter { !filterInaccessible || it.hasAccess }.sortedBy { it.position }
            if (topLevel.isNotEmpty()) groups.add(CategoryGroup(null, topLevel))
            for (cat in categories) {
                val children = byParent[cat.id].orEmpty()
                    .filter { !filterInaccessible || it.hasAccess }.sortedBy { it.position }
                if (children.isNotEmpty()) groups.add(CategoryGroup(cat, children))
            }
            groups.ifEmpty { null }
        }.getOrNull()

    fun saveChannels(guildId: String, groups: List<CategoryGroup>) = runCatching {
        val allChannels = groups.flatMap { g -> listOfNotNull(g.category) + g.channels }
        prefs?.edit()
            ?.putString("channels_$guildId", JSONArray(allChannels.map { it.toJson() }).toString())
            ?.apply()
    }

    private val loadedChannels = mutableSetOf<String>()

    fun markChannelRead(channelId: String, messageId: String) {
        val existing = _readState.value[channelId]
        if (existing != null && compareSnowflakes(messageId, existing.lastMessageId) <= 0) return
        _readState.update { current ->
            current + (channelId to ChannelUnreadState(lastMessageId = messageId, mentionCount = 0))
        }
        scope.launch { rest.ackChannel(channelId, messageId) }
    }

    suspend fun loadMessages(channelId: String) {
        // Fetch channel metadata if not cached
        if (!channelCache.containsKey(channelId)) {
            rest.getChannel(channelId).onSuccess { ch ->
                channelCache[channelId] = ch
                channelNameCache[channelId] = ch.displayName  // Use displayName for DMs
                ch.guildId?.let { guildId ->
                    channelGuildCache[channelId] = guildId
                }
            }
        }

        val pageSize = com.dihcord.app.Perf.messagePageSize
        rest.getMessages(channelId, limit = pageSize).onSuccess { fetched ->
            val fetchedList = fetched.reversed()
            fetchedList.forEach { userDisplayNames[it.author.id] = it.author.displayName }
            if (fetched.size < pageSize) reachedStart.add(channelId)
            _messages.update { current ->
                val existing = current[channelId].orEmpty()
                val merged = mergeLatestPage(existing, fetchedList)
                // The held history no longer connects to the latest page: start over from it and allow paging again.
                if (merged == null) reachedStart.remove(channelId)
                current + (channelId to (merged ?: fetchedList))
            }
            loadedChannels.add(channelId)
        }.onFailure { handleApiError(it) }
    }

    private val reachedStart: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val loadingOlder: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** False once the beginning of [channelId]'s history has been loaded. */
    fun hasMoreHistory(channelId: String): Boolean = channelId !in reachedStart

    /**
     * Loads the page of messages before the oldest one held for [channelId] and puts them in front.
     * Returns how many were added, 0 when there is nothing older (or a load is already running), -1 on error.
     */
    suspend fun loadOlderMessages(channelId: String): Int {
        if (channelId in reachedStart || !loadingOlder.add(channelId)) return 0
        try {
            val oldest = _messages.value[channelId]?.firstOrNull()?.id ?: return 0
            val pageSize = com.dihcord.app.Perf.messagePageSize
            val fetched = rest.getMessages(channelId, limit = pageSize, before = oldest)
                .getOrElse { handleApiError(it); return -1 }
            if (fetched.size < pageSize) reachedStart.add(channelId)
            val older = fetched.reversed()
            older.forEach { userDisplayNames[it.author.id] = it.author.displayName }
            var added = 0
            _messages.update { current ->
                val existing = current[channelId].orEmpty()
                val have = existing.mapTo(HashSet()) { it.id }
                val fresh = older.filter { it.id !in have }
                added = fresh.size
                current + (channelId to (fresh + existing))
            }
            return added
        } finally {
            loadingOlder.remove(channelId)
        }
    }

    fun getDisplayName(userId: String): String? = userDisplayNames[userId]
    suspend fun sendMessage(channelId: String, content: String): Result<DiscordMessage> {
        val result = rest.sendMessage(channelId, content)
        result.onSuccess { msg ->
            lastSentAt[channelId] = System.currentTimeMillis()
            _messages.update { current ->
                val list = current[channelId].orEmpty().toMutableList()
                if (list.none { it.id == msg.id }) list.add(msg)
                current + (channelId to list)
            }
        }
        return result
    }


    /** Votes for (or un-votes) [answerId]; the counts update from the gateway's vote events. */
    suspend fun votePoll(channelId: String, message: DiscordMessage, answerId: Int): Result<Unit> {
        val poll = message.poll ?: return Result.failure(IllegalStateException("Not a poll"))
        return rest.votePoll(channelId, message.id, poll.votesAfterTap(answerId))
    }

    suspend fun getStatus(): Result<Pair<String, String>> = rest.getStatus()

    /** Changes the online status / custom status everywhere (account settings) and on this session. */
    suspend fun setStatus(status: String, customText: String): Result<Unit> =
        rest.setStatus(status, customText).onSuccess { gateway.updatePresence(status, customText) }

    /** Everyone who reacted to a message with [reaction]'s emoji, normal and super reactions together. */
    suspend fun reactionUsers(channelId: String, messageId: String, reaction: Reaction): Result<List<DiscordUser>> {
        val key = reaction.emoji.apiKey
        val normal = if (reaction.count > reaction.burstCount || reaction.burstCount == 0)
            rest.getReactionUsers(channelId, messageId, key) else Result.success(emptyList())
        val burst = if (reaction.burstCount > 0)
            rest.getReactionUsers(channelId, messageId, key, burst = true) else Result.success(emptyList())
        if (normal.isFailure && burst.isFailure) return normal
        return Result.success((normal.getOrDefault(emptyList()) + burst.getOrDefault(emptyList())).distinctBy { it.id })
    }

    suspend fun sendFileAttachment(
        channelId: String,
        fileBytes: ByteArray,
        filename: String,
        mimeType: String,
        caption: String = ""
    ): Result<DiscordMessage> {
        val result = rest.sendFileAttachment(channelId, fileBytes, filename, mimeType, caption)
        result.onSuccess { msg ->
            lastSentAt[channelId] = System.currentTimeMillis()
            _messages.update { current ->
                val list = current[channelId].orEmpty().toMutableList()
                if (list.none { it.id == msg.id }) list.add(msg)
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun sendVoiceMessage(
        channelId: String,
        audioBytes: ByteArray,
        durationSecs: Double
    ): Result<DiscordMessage> {
        val result = rest.sendVoiceMessage(channelId, audioBytes, durationSecs)
        result.onSuccess { msg ->
            lastSentAt[channelId] = System.currentTimeMillis()
            _messages.update { current ->
                val list = current[channelId].orEmpty().toMutableList()
                if (list.none { it.id == msg.id }) list.add(msg)
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun sendSticker(channelId: String, stickerId: String): Result<DiscordMessage> {
        val result = rest.sendSticker(channelId, stickerId)
        result.onSuccess { msg ->
            lastSentAt[channelId] = System.currentTimeMillis()
            _messages.update { current ->
                val list = current[channelId].orEmpty().toMutableList()
                if (list.none { it.id == msg.id }) list.add(msg)
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun sendReply(
        channelId: String,
        content: String,
        replyToId: String,
        ping: Boolean = true
    ): Result<DiscordMessage> {
        val result = rest.sendReply(channelId, content, replyToId, ping)
        result.onSuccess { msg ->
            lastSentAt[channelId] = System.currentTimeMillis()
            _messages.update { current ->
                val list = current[channelId].orEmpty().toMutableList()
                if (list.none { it.id == msg.id }) list.add(msg)
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun editMessage(
        channelId: String,
        messageId: String,
        newContent: String
    ): Result<DiscordMessage> {
        val result = rest.editMessage(channelId, messageId, newContent)
        result.onSuccess { updated ->
            _messages.update { current ->
                val list = current[channelId]?.map { if (it.id == updated.id) updated else it }
                    ?: return@update current
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun deleteMessage(channelId: String, messageId: String): Result<Unit> {
        val result = rest.deleteMessage(channelId, messageId)
        result.onSuccess {
            _messages.update { current ->
                val list = current[channelId]?.filter { it.id != messageId }
                    ?: return@update current
                current + (channelId to list)
            }
        }
        return result
    }

    suspend fun fetchUserProfile(userId: String): Result<DiscordUser> =
        rest.getUserProfile(userId).onFailure { handleApiError(it) }

    /** Adds or removes the user's reaction; the shown state only changes once Discord accepted it. */
    suspend fun toggleReaction(
        channelId: String,
        messageId: String,
        emoji: ReactionEmoji,
        burst: Boolean = false
    ): Result<Unit> {
        val msg = _messages.value[channelId]?.firstOrNull { it.id == messageId } ?: return Result.success(Unit)
        val existing = msg.reactions.firstOrNull { it.emoji.apiKey == emoji.apiKey }
        val mine = if (burst) existing?.meBurst == true else existing?.me == true
        val result = if (mine) rest.removeReaction(channelId, messageId, emoji.apiKey, burst = burst)
        else rest.addReaction(channelId, messageId, emoji.apiKey, burst = burst)
        result.onSuccess { setMyReaction(channelId, messageId, emoji, burst, reacted = !mine) }
            .onFailure { handleApiError(it) }
        return result
    }

    /**
     * Marks the user's own reaction as present or not, changing the count only if that's news. Both the REST reply and
     * the gateway echo (or a reaction made on another device) land here, so neither double-counts.
     */
    private fun setMyReaction(channelId: String, messageId: String, emoji: ReactionEmoji, burst: Boolean, reacted: Boolean) {
        val msg = _messages.value[channelId]?.firstOrNull { it.id == messageId } ?: return
        val existing = msg.reactions.firstOrNull { it.emoji.apiKey == emoji.apiKey }
        val mine = if (burst) existing?.meBurst == true else existing?.me == true
        if (mine == reacted) return
        updateReactionLocally(channelId, messageId, emoji, if (reacted) +1 else -1, reacted, burst)
    }

    private fun updateReactionLocally(
        channelId: String, messageId: String,
        emoji: ReactionEmoji, delta: Int, me: Boolean?, burst: Boolean = false
    ) {
        _messages.update { current ->
            val list = current[channelId]?.map { msg ->
                if (msg.id != messageId) return@map msg
                val existing = msg.reactions.firstOrNull { it.emoji.apiKey == emoji.apiKey }
                val newReactions = if (existing != null) {
                    val newCount = existing.count + delta
                    val newMe = if (burst) existing.me else (me ?: existing.me)
                    val newMeBurst = if (burst) (me ?: existing.meBurst) else existing.meBurst
                    val newBurstCount =
                        if (burst) existing.burstCount + delta else existing.burstCount
                    if (newCount <= 0 && !newMe && !newMeBurst) {
                        msg.reactions.filter { it.emoji.apiKey != emoji.apiKey }
                    } else {
                        msg.reactions.map {
                            if (it.emoji.apiKey == emoji.apiKey) {
                                it.copy(
                                    count = maxOf(0, newCount),
                                    me = newMe,
                                    meBurst = newMeBurst,
                                    burstCount = maxOf(0, newBurstCount)
                                )
                            } else it
                        }
                    }
                } else {
                    if (delta > 0) {
                        msg.reactions + Reaction(
                            emoji = emoji,
                            count = delta,
                            me = if (burst) false else (me == true),
                            meBurst = if (burst) (me == true) else false,
                            burstCount = if (burst) delta else 0
                        )
                    } else msg.reactions
                }
                msg.copy(reactions = newReactions)
            } ?: return@update current
            current + (channelId to list)
        }
    }

    private fun observeGatewayEvents() {
        scope.launch {
            gateway.events.collect { event ->
                // One bad event must not end the collection (live updates would stop for good).
                runCatching { handleGatewayEvent(event) }
                    .onFailure { android.util.Log.e("DiscordRepository", "Failed to handle $event", it) }
            }
        }
    }

    private fun handleGatewayEvent(event: GatewayEvent) {
                when (event) {
                    is GatewayEvent.InvalidToken -> {
                        _isTokenInvalid.value = true
                    }

                    is GatewayEvent.MembersChunk -> {
                        event.members.forEach { userDisplayNames[it.id] = it.displayName }
                        memberChunks.tryEmit(event)
                    }

                    is GatewayEvent.Ready -> {
                        _currentUser.value = event.user
                        currentUserId = event.user.id
                        if (event.folders.isNotEmpty()) _guildFolders.value = event.folders
                        emojiCache.putAll(event.guildEmojis)
                        stickerCache.putAll(event.guildStickers)
                        if (event.readState.isNotEmpty()) {
                            _readState.value = event.readState
                            event.readState.filter { it.value.mentionCount > 0 }
                                .forEach { (chId, _) ->
                                    ensureChannelCached(chId)
                                }
                        }
                        if (event.presences.isNotEmpty()) {
                            _presences.value = event.presences
                                .filter { it.userId.isNotEmpty() }
                                .associateBy { it.userId }
                        }
                    }

                    is GatewayEvent.MessageCreate -> {
                        val msg = event.message
                        userDisplayNames[msg.author.id] = msg.author.displayName
                        val myId = currentUserId
                        if (myId != null && msg.author.id == myId &&
                            event.guildId != null && event.memberRoleIds.isNotEmpty()
                        ) {
                            myRolesByGuild[event.guildId] = event.memberRoleIds
                        }
                        _newMessages.tryEmit(msg)
                        // Only channels that were opened hold messages; the rest load fresh when opened.
                        _messages.update { current ->
                            val list = current[msg.channelId] ?: return@update current
                            if (list.any { it.id == msg.id }) return@update current
                            current + (msg.channelId to (list + msg))
                        }
                        val isDmChannel = _dmChannels.value.any { it.id == msg.channelId }
                        if (isDmChannel) {
                            _dmChannels.update { channels ->
                                channels.map { ch ->
                                    if (ch.id == msg.channelId &&
                                        (ch.lastMessageId == null || compareSnowflakes(msg.id, ch.lastMessageId) > 0)
                                    )
                                        ch.copy(lastMessageId = msg.id)
                                    else ch
                                }
                            }
                        }
                        msg.guildId?.let { channelGuildCache[msg.channelId] = it }
                        if (myId != null && msg.author.id != myId) {
                            val guildId = event.guildId ?: msg.guildId
                            val memberRoles = if (guildId != null) myRolesByGuild[guildId]
                                ?: emptyList() else emptyList()
                            if (msg.pingFor(myId, memberRoles)) {
                                val channelName = channelNameCache[msg.channelId] ?: msg.channelId
                                val guildName = msg.guildId?.let { gid ->
                                    _guilds.value.firstOrNull { it.id == gid }?.name
                                }
                                _pings.update { current ->
                                    (listOf(Ping(msg, channelName, guildName)) + current).take(5)
                                }
                            }
                        }
                        _typing.update { current ->
                            val users = current[msg.channelId].orEmpty() - msg.author.id
                            if (users.isEmpty()) current - msg.channelId
                            else current + (msg.channelId to users)
                        }
                        val typingKey = "${msg.channelId}:${msg.author.id}"
                        typingJobs[typingKey]?.cancel()
                        typingJobs.remove(typingKey)
                    }

                    is GatewayEvent.MessageUpdate -> {
                        _messages.update { current ->
                            val list = current[event.channelId]?.map {
                                if (it.id == event.id) mergeUpdate(it, event) else it
                            } ?: return@update current
                            current + (event.channelId to list)
                        }
                    }

                    is GatewayEvent.MessageDeleteBulk -> {
                        _messages.update { current ->
                            val list = current[event.channelId]?.filter { it.id !in event.ids }
                                ?: return@update current
                            current + (event.channelId to list)
                        }
                    }

                    is GatewayEvent.ReactionRemoveAll -> {
                        _messages.update { current ->
                            val list = current[event.channelId]?.map { msg ->
                                if (msg.id != event.messageId) msg
                                else msg.copy(reactions = if (event.emoji == null) emptyList()
                                    else msg.reactions.filter { it.emoji.apiKey != event.emoji.apiKey })
                            } ?: return@update current
                            current + (event.channelId to list)
                        }
                    }

                    is GatewayEvent.PollVote -> {
                        val mine = event.userId == currentUserId
                        _messages.update { current ->
                            val list = current[event.channelId]?.map {
                                val poll = it.poll
                                if (it.id == event.messageId && poll != null)
                                    it.copy(poll = poll.withVote(event.answerId, event.add, mine))
                                else it
                            } ?: return@update current
                            current + (event.channelId to list)
                        }
                    }

                    is GatewayEvent.MessageDelete -> {
                        _messages.update { current ->
                            val list = current[event.channelId]?.filter { it.id != event.id }
                                ?: return@update current
                            current + (event.channelId to list)
                        }
                    }

                    is GatewayEvent.ReactionAdd -> {
                        val isMe = event.userId == currentUserId
                        if (isMe) {
                            setMyReaction(event.channelId, event.messageId, event.emoji, event.burst, reacted = true)
                        } else {
                            updateReactionLocally(
                                event.channelId,
                                event.messageId,
                                event.emoji,
                                +1,
                                null,
                                burst = event.burst
                            )
                        }
                    }

                    is GatewayEvent.ReactionRemove -> {
                        val isMe = event.userId == currentUserId
                        if (isMe) {
                            setMyReaction(event.channelId, event.messageId, event.emoji, event.burst, reacted = false)
                        } else {
                            updateReactionLocally(
                                event.channelId,
                                event.messageId,
                                event.emoji,
                                -1,
                                null,
                                burst = event.burst
                            )
                        }
                    }

                    is GatewayEvent.TypingStart -> {
                        event.displayName?.let { userDisplayNames[event.userId] = it }
                        val updated = _typing.value.toMutableMap()
                        val users = (updated[event.channelId] ?: emptySet()) + event.userId
                        updated[event.channelId] = users
                        _typing.value = updated.toMap()
                        val key = "${event.channelId}:${event.userId}"
                        typingJobs[key]?.cancel()
                        typingJobs[key] = scope.launch {
                            delay(10_000)
                            _typing.update { current ->
                                val users = current[event.channelId].orEmpty() - event.userId
                                if (users.isEmpty()) current - event.channelId
                                else current + (event.channelId to users)
                            }
                            typingJobs.remove(key)
                        }
                    }

                    is GatewayEvent.PresenceUpdate -> {
                        val p = event.presence
                        if (p.userId.isNotEmpty()) {
                            _presences.update { current -> current + (p.userId to p) }
                        }
                    }

                    is GatewayEvent.ChannelCreate -> {
                        val ch = event.channel
                        if (ch.isDm) {
                            scope.launch { refreshDmChannels() }
                        } else if (ch.guildId != null) {
                            refreshGuildChannelsSoon(ch.guildId)
                        }
                    }

                    is GatewayEvent.ChannelUpdate -> {
                        val ch = event.channel
                        channelCache[ch.id] = ch
                        channelNameCache[ch.id] = ch.displayName
                        if (ch.guildId != null) {
                            channelGuildCache[ch.id] = ch.guildId
                            refreshGuildChannelsSoon(ch.guildId)
                        } else if (ch.isDm) {
                            scope.launch { refreshDmChannels() }
                        }
                    }

                    is GatewayEvent.ChannelDelete -> {
                        val ch = event.channel
                        channelCache.remove(ch.id)
                        channelNameCache.remove(ch.id)
                        channelGuildCache.remove(ch.id)
                        if (ch.guildId != null) {
                            refreshGuildChannelsSoon(ch.guildId)
                        } else if (ch.isDm) {
                            scope.launch { refreshDmChannels() }
                        }
                    }

                    is GatewayEvent.Unknown -> {}
                }
    }

    private val channelRefreshJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * Refreshes [guildId]'s channel list after channel events, once things settle: a server reshuffling channels sends
     * a burst of events, and refetching on each would hit the rate limit. Servers never opened are skipped.
     */
    private fun refreshGuildChannelsSoon(guildId: String) {
        if (guildId !in _guildChannelsMap.value) return
        channelRefreshJobs[guildId]?.cancel()
        channelRefreshJobs[guildId] = scope.launch {
            delay(1_500)
            refreshGuildChannels(guildId)
        }
    }

    /** Applies a MESSAGE_UPDATE to [old], keeping what the (possibly partial) payload leaves out. */
    private fun mergeUpdate(old: DiscordMessage, event: GatewayEvent.MessageUpdate): DiscordMessage {
        val raw = event.raw
        val full = event.message
        if (full != null) {
            // Updates carry no reactions, and a poll only when it changed: keep the ones we have.
            return full.copy(
                reactions = if (raw.has("reactions")) full.reactions else old.reactions,
                poll = if (raw.has("poll")) full.poll else old.poll,
            )
        }
        // Partial update, typically link previews arriving after the message was sent.
        val embeds = raw.optJSONArray("embeds")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> runCatching { Embed.fromJson(arr.getJSONObject(i)) }.getOrNull() }
        }
        return if (embeds != null) old.copy(embeds = embeds) else old
    }


    private fun loadCachedGuilds(): List<Guild> = runCatching {
        val json = prefs?.getString("guilds", null) ?: return emptyList()
        val arr = JSONArray(json)
        (0 until arr.length()).map { Guild.fromJson(arr.getJSONObject(it)) }
    }.getOrElse { emptyList() }

    private fun saveGuilds(list: List<Guild>) = runCatching {
        prefs?.edit()?.putString("guilds", JSONArray(list.map { it.toJson() }).toString())?.apply()
    }

    private fun loadCachedDmChannels(): List<Channel> = runCatching {
        val json = prefs?.getString("dm_channels", null) ?: return emptyList()
        val channels = Channel.listFromJson(JSONArray(json))
        // Populate in-memory cache
        channels.forEach { ch ->
            channelNameCache[ch.id] = ch.displayName
            channelCache[ch.id] = ch
        }
        channels
    }.getOrElse { emptyList() }

    private fun saveDmChannels(list: List<Channel>) = runCatching {
        prefs?.edit()?.putString("dm_channels", JSONArray(list.map { it.toJson() }).toString())
            ?.apply()
    }

    init {
        _dmChannels.value = loadCachedDmChannels()
    }
}

/**
 * Merges the latest page [fetched] (oldest first) into the [held] messages: messages inside the page's range are
 * replaced by the page (so deletions while away disappear), older and newer ones are kept. Returns null if
 * [held] doesn't reach the page (more than a page was missed), in which case the caller should start over.
 */
internal fun mergeLatestPage(held: List<DiscordMessage>, fetched: List<DiscordMessage>): List<DiscordMessage>? {
    if (held.isEmpty()) return fetched
    if (fetched.isEmpty()) return held
    val first = fetched.first().id
    val last = fetched.last().id
    val newestHeld = held.last().id
    if (compareSnowflakes(newestHeld, first) < 0) return null
    val older = held.filter { compareSnowflakes(it.id, first) < 0 }
    val newer = held.filter { compareSnowflakes(it.id, last) > 0 }
    return (older + fetched + newer).distinctBy { it.id }
}
