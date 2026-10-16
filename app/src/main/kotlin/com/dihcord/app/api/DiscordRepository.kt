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
import kotlinx.coroutines.flow.debounce
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

class DiscordRepository(
    token: String,
    private val context: Context? = null,
    apiBase: String = "https://discord.com/api/v9",
) {

    // An unexpected error in one background job must not take the whole app down.
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> android.util.Log.e("DiscordRepository", "Background job failed", e) }
    )
    val rest = DiscordRestClient(token, apiBase)
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
            unread.onChannels(guildId, groups.flatMap { it.channels }, _readState.value)
        }.onFailure { handleApiError(it) }
    }

    private val _messages = MutableStateFlow<Map<String, List<DiscordMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<DiscordMessage>>> = _messages.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val typing: StateFlow<Map<String, Set<String>>> = _typing.asStateFlow()

    /** The newest message of each DM we know of, for the previews in the DM list and on Home. */
    private val _lastMessages = MutableStateFlow<Map<String, DiscordMessage>>(emptyMap())
    val lastMessages: StateFlow<Map<String, DiscordMessage>> = _lastMessages.asStateFlow()
    /** "channel/message" keys whose preview is settled (fetched, or its message was deleted). */
    private val previewSettled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val previewInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val previewFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Keeps a held preview in step with an edit, or drops it when its message is deleted. */
    private fun syncPreview(channelId: String, deleted: Set<String> = emptySet(), edited: ((DiscordMessage) -> DiscordMessage)? = null) {
        _lastMessages.update { current ->
            val held = current[channelId] ?: return@update current
            when {
                held.id in deleted -> {
                    // Don't fetch it again: the channel's "last message" still points at the deleted one.
                    previewSettled.add("$channelId/${held.id}")
                    current - channelId
                }
                edited != null -> current + (channelId to edited(held))
                else -> current
            }
        }
    }

    private val _pings = MutableStateFlow<List<Ping>>(emptyList())
    val pings: StateFlow<List<Ping>> = _pings.asStateFlow()

    /** Unread servers and channels (white dots), from READY and live messages. */
    val unread = UnreadTracker()
    val unreadGuilds: StateFlow<Set<String>> get() = unread.unreadGuilds

    private val memberListStore = MemberListStore()
    /** Server member lists by guild id, filled after [subscribeMemberList]. */
    val memberLists: StateFlow<Map<String, MemberList>> get() = memberListStore.lists

    private val relationshipStore = RelationshipStore()
    /** Friends, blocks and requests by user id. */
    val relationships: StateFlow<Map<String, Relationship>> get() = relationshipStore.all
    fun isBlocked(userId: String): Boolean = relationshipStore.isBlocked(userId)

    private val _notifySettings = MutableStateFlow<Map<String, NotifySettings>>(emptyMap())
    /** Discord's own per-server notification settings ("" for DMs). */
    val notifySettings: StateFlow<Map<String, NotifySettings>> = _notifySettings.asStateFlow()

    /** Whether live data is flowing; false shows chats from the offline cache. */
    val online: StateFlow<Boolean> get() = gateway.online

    private val messageCache = MessageCache(context?.let { MessageCache.dirFor(it) })
    private val _fromCache = MutableStateFlow<Set<String>>(emptySet())
    /** Channels whose messages are, for now, the saved copy from disk. */
    val fromCache: StateFlow<Set<String>> = _fromCache.asStateFlow()

    private val _interactionResults = MutableSharedFlow<GatewayEvent.InteractionResult>(extraBufferCapacity = 8)
    /** Answers to bot buttons and slash commands, by nonce. */
    val interactionResults: SharedFlow<GatewayEvent.InteractionResult> = _interactionResults.asSharedFlow()

    private val _soundboardPlays = MutableSharedFlow<GatewayEvent.SoundboardPlay>(extraBufferCapacity = 8)
    /** Soundboard sounds played in voice channels, for the call to play aloud. */
    val soundboardPlays: SharedFlow<GatewayEvent.SoundboardPlay> = _soundboardPlays.asSharedFlow()

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
    /** Everyone's voice connection, keyed by "guildId|userId" ("dm|userId" for calls). */
    private val _voiceStates = MutableStateFlow<Map<String, VoiceState>>(emptyMap())
    val voiceStates: StateFlow<Map<String, VoiceState>> = _voiceStates.asStateFlow()

    /** Voice events for the voice call manager (our own state, voice server handoff). */
    private val _voiceEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 16)
    val voiceEvents: SharedFlow<GatewayEvent> = _voiceEvents

    /** Set while in a voice call: the gateway must stay connected or Discord drops us from the channel. */
    @Volatile var keepConnected = false

    fun updateVoiceState(guildId: String?, channelId: String?, selfMute: Boolean, selfDeaf: Boolean) =
        gateway.updateVoiceState(guildId, channelId, selfMute, selfDeaf)

    private fun voiceKey(state: VoiceState) = "${state.guildId ?: "dm"}|${state.userId}"

    /** Names and avatars of people seen in voice, by user id. */
    private val _voiceProfiles = MutableStateFlow<Map<String, VoiceProfile>>(emptyMap())
    val voiceProfiles: StateFlow<Map<String, VoiceProfile>> = _voiceProfiles.asStateFlow()
    private val requestedProfiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Makes sure [voiceProfiles] covers [userIds]: voice states from READY carry no member data, so ask the gateway
     * for those members (servers) or use the DM's recipients (calls).
     */
    fun loadVoiceProfiles(guildId: String?, userIds: Collection<String>) {
        val known = _voiceProfiles.value
        val missing = userIds.filter { it !in known }
        if (missing.isEmpty()) return
        val local = missing.mapNotNull { id ->
            val user = _currentUser.value?.takeIf { it.id == id }
                ?: _dmChannels.value.asSequence().flatMap { it.recipients }.firstOrNull { it.id == id }
            user?.let { id to VoiceProfile(userDisplayNames[id] ?: it.displayName, it.avatarUrl(128)) }
        }.toMap()
        if (local.isNotEmpty()) _voiceProfiles.update { it + local }
        if (guildId == null) return
        val ask = missing.filter { it !in local && requestedProfiles.add("$guildId|$it") }
        if (ask.isNotEmpty()) {
            ask.chunked(100).forEach { gateway.requestGuildMembersByIds(guildId, it, "voice${System.nanoTime()}") }
        }
    }

    /** DM/group calls in progress, by channel id: who is being rung. */
    private val _calls = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val calls: StateFlow<Map<String, Set<String>>> = _calls.asStateFlow()

    suspend fun ringCall(channelId: String) = rest.ringCall(channelId)

    /** Declines an incoming call: stop ringing us. */
    suspend fun declineCall(channelId: String) {
        val me = currentUserId ?: _currentUser.value?.id ?: return
        _calls.update { c -> c[channelId]?.let { c + (channelId to (it - me)) } ?: c }
        rest.stopRinging(channelId, listOf(me))
    }

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
        if (keepConnected) return
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
     * How loud [channelId] in [guildId] should be: the user's choice on this watch, or else Discord's own
     * settings (a muted server or channel says nothing; "default" means mentions only).
     */
    fun alertLevelFor(guildId: String?, channelId: String, local: com.dihcord.app.FeaturePreferences.AlertLevel): com.dihcord.app.FeaturePreferences.AlertLevel {
        if (local != com.dihcord.app.FeaturePreferences.AlertLevel.DISCORD) return local
        val settings = _notifySettings.value[guildId ?: ""]
        val channel = settings?.channels?.get(channelId)
        // Channels never opened aren't in channelCache; READY's server data still knows their category.
        val parent = (channelCache[channelId]?.parentId ?: unread.parentOf(channelId))?.let { settings?.channels?.get(it) }
        if (settings?.mutedNow() == true || channel?.mutedNow() == true || parent?.mutedNow() == true) return com.dihcord.app.FeaturePreferences.AlertLevel.NOTHING
        val level = channel?.level?.takeIf { it != 3 } ?: parent?.level?.takeIf { it != 3 } ?: settings?.level?.takeIf { it != 3 }
        return when (level) {
            0 -> com.dihcord.app.FeaturePreferences.AlertLevel.ALL
            2 -> com.dihcord.app.FeaturePreferences.AlertLevel.NOTHING
            else -> com.dihcord.app.FeaturePreferences.AlertLevel.MENTIONS
        }
    }

    /**
     * Decides whether [msg] deserves a notification (a DM, or a mention of the user / their roles / @everyone,
     * never their own messages) and builds its text. Null when it should stay silent.
     */
    suspend fun notificationFor(msg: DiscordMessage, notifyAll: Boolean = false): NotificationInfo? {
        val myId = currentUserId ?: return null
        if (msg.author.id == myId) return null
        // Like Discord: nothing from people you blocked, and "@silent" messages never buzz.
        if (relationshipStore.isBlocked(msg.author.id)) return null
        if (msg.flags and SUPPRESS_NOTIFICATIONS_FLAG != 0) return null
        val isDm = _dmChannels.value.any { it.id == msg.channelId }
        val guildId = msg.guildId ?: channelGuildCache[msg.channelId]
        val mentioned = if (isDm || notifyAll) true else {
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
        // This also subscribes to the channel's member list (op 14).
        if (guildId != null) noteMemberListChannel(guildId, channelId)
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

    suspend fun getFriends(): Result<List<DiscordUser>> = rest.getFriends()

    /**
     * Fetches the newest message of each DM in [channelIds] whose preview we don't have yet (one small
     * request each, a moment apart, once per message). Opened chats and live messages fill it for free.
     */
    suspend fun loadLastMessages(channelIds: List<String>) {
        for (id in channelIds) {
            val want = _dmChannels.value.firstOrNull { it.id == id }?.lastMessageId ?: continue
            if (_lastMessages.value[id]?.id == want) continue
            val held = _messages.value[id]?.lastOrNull()
            if (held?.id == want) {
                _lastMessages.update { it + (id to held) }
                continue
            }
            val key = "$id/$want"
            if (key in previewSettled || (previewFailures[key] ?: 0) >= 3 || !previewInFlight.add(key)) continue
            try {
                rest.getMessages(id, limit = 1).onSuccess { page ->
                    previewSettled.add(key)
                    page.firstOrNull()?.let { msg -> _lastMessages.update { it + (id to msg) } }
                }.onFailure {
                    previewFailures.merge(key, 1, Int::plus)
                    handleApiError(it)
                }
            } finally {
                // Also on cancellation (the list closed mid-fetch), so the next visit can try again.
                previewInFlight.remove(key)
            }
            kotlinx.coroutines.delay(150)
        }
    }

    /** The DM with [userId], opening one on Discord if there isn't one yet. */
    suspend fun openDm(userId: String): Result<Channel> {
        _dmChannels.value.firstOrNull { ch -> ch.recipients.size == 1 && ch.recipients[0].id == userId }
            ?.let { return Result.success(it) }
        return rest.openDm(userId).onSuccess { ch ->
            channelNameCache[ch.id] = ch.displayName
            channelCache[ch.id] = ch
            _channelNames.value = channelNameCache.toMap()
            _dmChannels.update { list -> if (list.any { it.id == ch.id }) list else listOf(ch) + list }
        }
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

            val textChannels = allChannels.filter { it.isText || it.isForum || it.isVoice }
            val categories = allChannels.filter { it.isCategory }.sortedBy { it.position }
            val byParent = textChannels.groupBy { it.parentId }
            val groups = mutableListOf<CategoryGroup>()
            val topLevel = byParent[null].orEmpty()
                .filter { !filterInaccessible || it.hasAccess }.sortedWith(compareBy({ it.isVoice }, { it.position }))
            if (topLevel.isNotEmpty()) groups.add(CategoryGroup(null, topLevel))
            for (cat in categories) {
                val children = byParent[cat.id].orEmpty()
                    .filter { !filterInaccessible || it.hasAccess }.sortedWith(compareBy({ it.isVoice }, { it.position }))
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

    /**
     * Mentions in [channelId] swiped away on the home screen: drop its pings and mark the channel read up to its
     * newest message, so the mention count clears here and on Discord.
     */
    fun dismissMentions(channelId: String) {
        _pings.update { list -> list.filterNot { it.message.channelId == channelId } }
        _readState.update { current ->
            current[channelId]?.let { current + (channelId to it.copy(mentionCount = 0)) } ?: current
        }
        scope.launch {
            // Held messages can be stale for channels that aren't open, so ask Discord for the newest one.
            val latest = rest.getMessages(channelId, limit = 1).getOrNull()?.firstOrNull()?.id
                ?: _messages.value[channelId]?.lastOrNull()?.id
                ?: return@launch
            markChannelRead(channelId, latest)
        }
    }

    fun markChannelRead(channelId: String, messageId: String) {
        _pings.update { list ->
            list.filterNot { it.message.channelId == channelId && compareSnowflakes(it.message.id, messageId) <= 0 }
        }
        val existing = _readState.value[channelId]
        if (existing != null && existing.mentionCount == 0 && compareSnowflakes(messageId, existing.lastMessageId) <= 0) return
        _readState.update { current ->
            val newest = existing?.lastMessageId
                ?.takeIf { compareSnowflakes(it, messageId) > 0 } ?: messageId
            current + (channelId to ChannelUnreadState(lastMessageId = newest, mentionCount = 0))
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
        // Show the saved copy straight away (and when offline); the live page replaces it below.
        if (_messages.value[channelId].isNullOrEmpty()) {
            messageCache.load(channelId)?.let { saved ->
                // Remembered until a live page replaces them, even across failed (offline) tries.
                cachedIdsByChannel[channelId] = saved.mapTo(HashSet()) { it.id }
                _messages.update { it + (channelId to saved) }
                _fromCache.update { it + channelId }
            }
        }
        rest.getMessagesRaw(channelId, limit = pageSize).mapCatching { raw ->
            DiscordMessage.listFromJson(JSONArray(raw)).also { messageCache.save(channelId, raw) }
        }.onSuccess { fetched ->
            _fromCache.update { it - channelId }
            val cachedIds = cachedIdsByChannel.remove(channelId).orEmpty()
            val fetchedList = fetched.reversed()
            fetchedList.forEach { userDisplayNames[it.author.id] = it.author.displayName }
            if (fetched.size < pageSize) reachedStart.add(channelId)
            _messages.update { current ->
                val held = current[channelId].orEmpty()
                // The live page is the truth: the saved copy (or an emptied channel) may hold messages since deleted.
                // Of the saved copy, only messages that arrived live while it loaded stay.
                val existing = when {
                    fetchedList.isEmpty() -> emptyList()
                    cachedIds.isNotEmpty() -> held.filter { it.id !in cachedIds }
                    else -> held
                }
                val merged = mergeLatestPage(existing, fetchedList)
                // The held history no longer connects to the latest page: start over from it and allow paging again.
                if (merged == null) reachedStart.remove(channelId)
                current + (channelId to (merged ?: fetchedList))
            }
            loadedChannels.add(channelId)
        }.onFailure { handleApiError(it) }
    }

    private val reachedStart: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Ids of messages shown from the disk copy that no live page has replaced yet, by channel. */
    private val cachedIdsByChannel = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
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

    // ---- Typing ----

    private val typingSentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Lets the others see we're typing; at most once every 8 s per channel, like Discord's client. */
    fun typingInChannel(channelId: String) {
        val now = System.currentTimeMillis()
        val last = typingSentAt[channelId] ?: 0L
        if (now - last < 8_000) return
        typingSentAt[channelId] = now
        scope.launch { rest.sendTyping(channelId) }
    }

    /** After sending, the next keystroke may say "typing" again straight away. */
    private fun typingDone(channelId: String) { typingSentAt.remove(channelId) }

    // ---- Read state ----

    /** Marks every unread channel of [guildId] read (mentions too), here and on Discord. */
    suspend fun markGuildRead(guildId: String): Result<Unit> {
        val rs = _readState.value
        val reads = (unread.unreadChannels(guildId, rs, includeMuted = true) + unread.unreadThreads(guildId, rs)).toMutableList()
        rs.forEach { (channelId, state) ->
            if (state.mentionCount > 0 && channelGuildCache[channelId] == guildId && reads.none { it.first == channelId }) {
                (unread.lastMessageId(channelId) ?: state.lastMessageId).let { reads += channelId to it }
            }
        }
        return ackLocallyAndRemotely(reads)
    }

    /** Marks one channel read up to [messageId], here and on Discord; undone here if Discord doesn't take it. */
    suspend fun markChannelReadChecked(channelId: String, messageId: String): Result<Unit> =
        ackLocallyAndRemotely(listOf(channelId to messageId))

    /** Marks everything read: every server's unread channels and every DM. */
    suspend fun markAllRead(): Result<Unit> {
        val rs = _readState.value
        val reads = mutableListOf<Pair<String, String>>()
        _guilds.value.forEach { g -> reads += unread.unreadChannels(g.id, rs, includeMuted = true) + unread.unreadThreads(g.id, rs) }
        _dmChannels.value.forEach { dm ->
            val last = dm.lastMessageId ?: return@forEach
            val state = rs[dm.id]
            if (state == null || compareSnowflakes(last, state.lastMessageId) > 0 || state.mentionCount > 0) reads += dm.id to last
        }
        rs.forEach { (channelId, state) ->
            if (state.mentionCount > 0 && reads.none { it.first == channelId }) {
                reads += channelId to (unread.lastMessageId(channelId) ?: state.lastMessageId)
            }
        }
        return ackLocallyAndRemotely(reads.distinctBy { it.first })
    }

    /** A mark-read waiting for Discord: the furthest read Discord reported (another device) per channel meanwhile. */
    private class AckWatch(val channels: Set<String>) {
        val furthest = java.util.concurrent.ConcurrentHashMap<String, ChannelUnreadState>()
    }
    private val ackWatches = java.util.concurrent.CopyOnWriteArrayList<AckWatch>()

    private suspend fun ackLocallyAndRemotely(reads: List<Pair<String, String>>): Result<Unit> {
        if (reads.isEmpty()) return Result.success(Unit)
        val ids = reads.map { it.first }.toSet()
        val watch = AckWatch(ids).also { ackWatches += it }
        try {
            return ackWatched(reads, ids, watch)
        } finally {
            ackWatches -= watch
        }
    }

    private suspend fun ackWatched(reads: List<Pair<String, String>>, ids: Set<String>, watch: AckWatch): Result<Unit> {
        val pingsBefore = _pings.value.filter { it.message.channelId in ids }
        val before = _readState.value
        var marked: Map<String, ChannelUnreadState> = emptyMap()
        _pings.update { list -> list.filterNot { it.message.channelId in ids } }
        _readState.update { current ->
            marked = reads.associate { (channelId, messageId) ->
                val newest = current[channelId]?.lastMessageId?.takeIf { compareSnowflakes(it, messageId) > 0 } ?: messageId
                channelId to ChannelUnreadState(newest, 0)
            }
            current + marked
        }
        return rest.ackBulk(reads).onFailure { error ->
            // Discord didn't take it: show them unread again, so trying again sends them again.
            // Channel -> how far another device read meanwhile (null: nowhere); pings up to there stay cleared.
            val rolledBack = HashMap<String, String?>()
            _readState.update { current ->
                val out = current.toMutableMap()
                rolledBack.clear()
                marked.forEach { (channelId, set) ->
                    if (out[channelId] != set) return@forEach // Changed since (read in the chat, a new message).
                    val back = rollbackFor(set, before[channelId], watch.furthest[channelId]) ?: return@forEach
                    if (back.state != null) out[channelId] = back.state else out.remove(channelId)
                    rolledBack[channelId] = back.pingFloor
                }
                out
            }
            // Pings come back only where the unread state did; a channel read elsewhere meanwhile stays clear.
            val restore = pingsBefore.filter { p ->
                val ch = p.message.channelId
                ch in rolledBack && rolledBack[ch].let { floor -> floor == null || compareSnowflakes(p.message.id, floor) > 0 }
            }
            _pings.update { list -> (list + restore).distinctBy { it.message.id } }
            handleApiError(error)
        }
    }

    // ---- Pins, search ----

    suspend fun pins(channelId: String) = rest.getPins(channelId).onFailure { handleApiError(it) }
    suspend fun setPinned(channelId: String, messageId: String, pinned: Boolean) = rest.setPinned(channelId, messageId, pinned)
    suspend fun search(guildId: String?, channelId: String, query: String) = rest.searchMessages(guildId, channelId, query)

    // ---- People ----

    suspend fun refreshRelationships(): Result<List<Relationship>> =
        rest.getRelationships().onSuccess { relationshipStore.set(it) }.onFailure { handleApiError(it) }

    suspend fun acceptFriend(user: DiscordUser): Result<Unit> = rest.acceptFriend(user.id).onSuccess {
        relationshipStore.put(Relationship(user.id, Relationship.FRIEND, user))
    }

    suspend fun removeRelationship(userId: String): Result<Unit> = rest.removeRelationship(userId).onSuccess {
        relationshipStore.remove(userId)
    }

    suspend fun blockUser(user: DiscordUser): Result<Unit> = rest.blockUser(user.id).onSuccess {
        relationshipStore.put(Relationship(user.id, Relationship.BLOCKED, user))
    }

    suspend fun setNickname(guildId: String, nick: String) = rest.setNickname(guildId, nick).onSuccess {
        // The cached member still has the old nickname; the next look fetches the new one.
        currentUser.value?.id?.let { me -> guildMemberCache.remove("$guildId:$me") }
    }

    suspend fun createGroupDm(userIds: List<String>): Result<Channel> = rest.createGroupDm(userIds).onSuccess { ch ->
        channelNameCache[ch.id] = ch.displayName
        channelCache[ch.id] = ch
        _channelNames.value = channelNameCache.toMap()
        _dmChannels.update { list -> if (list.any { it.id == ch.id }) list else listOf(ch) + list }
    }

    suspend fun addToGroup(channelId: String, userId: String) = rest.addToGroup(channelId, userId).onSuccess {
        scope.launch { refreshDmChannels() }
    }

    /** Asks Discord to stream [guildId]'s member list (as seen from [channelId]) into [memberLists]. */
    fun subscribeMemberList(guildId: String, channelId: String) {
        val changed = noteMemberListChannel(guildId, channelId)
        // Already subscribed to this channel (opening its chat does it) and the list is here: asking for the
        // same range again may get an empty answer, so keep what we have; updates keep streaming in.
        if (changed || guildId !in memberListStore.lists.value) gateway.subscribeMemberList(guildId, channelId)
    }

    private val _sessions = MutableStateFlow(0)
    /** Goes up on each READY: a new gateway session, whose subscriptions (op 14) must be asked for again. */
    val sessions: StateFlow<Int> = _sessions.asStateFlow()

    /** Which channel each server's member list subscription is for, this gateway session. */
    private val memberListChannel = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Records [channelId] as [guildId]'s subscribed list; a different channel drops the old list. True if it changed. */
    private fun noteMemberListChannel(guildId: String, channelId: String): Boolean {
        val old = memberListChannel.put(guildId, channelId)
        if (old != channelId) memberListStore.reset(guildId)
        return old != channelId
    }

    // ---- Sending ----

    suspend fun forward(message: DiscordMessage, toChannelId: String) =
        rest.forwardMessage(toChannelId, message.channelId, message.guildId ?: channelGuildCache[message.channelId], message.id)

    suspend fun createPoll(channelId: String, question: String, answers: List<Pair<String, String?>>, hours: Int, multiselect: Boolean) =
        rest.createPoll(channelId, question, answers, hours, multiselect).onSuccess { typingDone(channelId) }

    // ---- Servers ----

    suspend fun invitePreview(code: String) = rest.getInvite(code)

    suspend fun joinInvite(code: String): Result<Unit> = rest.joinInvite(code).onSuccess {
        scope.launch { refreshGuilds() }
    }

    suspend fun leaveGuild(guildId: String): Result<Unit> = rest.leaveGuild(guildId).onSuccess {
        _guilds.update { list -> list.filterNot { it.id == guildId } }
        unread.forgetGuild(guildId, _readState.value)
    }

    suspend fun scheduledEvents(guildId: String) = rest.getScheduledEvents(guildId)
    suspend fun setEventInterest(guildId: String, eventId: String, interested: Boolean) = rest.setEventInterest(guildId, eventId, interested)
    suspend fun requestToSpeak(guildId: String, channelId: String, request: Boolean) = rest.requestToSpeak(guildId, channelId, request)

    // ---- Soundboard ----

    private val soundCache = java.util.concurrent.ConcurrentHashMap<String, List<SoundboardSound>>()

    /** The server's own sounds first, then Discord's defaults. */
    suspend fun soundboard(guildId: String?): List<SoundboardSound> {
        val defaults = soundCache["default"] ?: rest.getDefaultSounds().getOrNull()?.also { soundCache["default"] = it }.orEmpty()
        val own = if (guildId == null) emptyList()
        else soundCache[guildId] ?: rest.getGuildSounds(guildId).getOrNull()?.also { soundCache[guildId] = it }.orEmpty()
        return own + defaults
    }

    suspend fun playSound(voiceChannelId: String, sound: SoundboardSound) = rest.playSound(voiceChannelId, sound)

    // ---- Bots ----

    private val commandCache = java.util.concurrent.ConcurrentHashMap<String, List<ApplicationCommand>>()

    /** Slash commands for [channelId] (cached per server, or per DM). */
    suspend fun commands(guildId: String?, channelId: String): Result<List<ApplicationCommand>> {
        val key = guildId ?: channelId
        commandCache[key]?.let { return Result.success(it) }
        return rest.getCommands(guildId, channelId).onSuccess { commandCache[key] = it.filter { c -> c.usable } }
            .map { commandCache[key].orEmpty() }
    }

    suspend fun runCommand(command: ApplicationCommand, options: Map<String, Any>, guildId: String?, channelId: String): Result<String> {
        val session = gateway.currentSessionId ?: return Result.failure(java.io.IOException("Not connected"))
        return rest.runCommand(command, options, guildId, channelId, session)
    }

    suspend fun pressComponent(message: DiscordMessage, componentType: Int, customId: String, values: List<String>?): Result<String> {
        val session = gateway.currentSessionId ?: return Result.failure(java.io.IOException("Not connected"))
        return rest.pressComponent(message, componentType, customId, values, message.guildId ?: channelGuildCache[message.channelId], session)
    }
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
        // Only the kinds this reaction has are asked for; it failed if every request that was made failed
        // (a skipped request isn't a success, or a dead connection would look like "nobody reacted").
        val normal = if (reaction.count > reaction.burstCount || reaction.burstCount == 0)
            rest.getReactionUsers(channelId, messageId, key) else null
        val burst = if (reaction.burstCount > 0)
            rest.getReactionUsers(channelId, messageId, key, burst = true) else null
        val made = listOfNotNull(normal, burst)
        made.firstOrNull { it.isFailure }?.takeIf { made.all { r -> r.isFailure } }?.let { return it }
        return Result.success(made.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id })
    }

    suspend fun sendFileAttachment(
        channelId: String,
        fileBytes: ByteArray,
        filename: String,
        mimeType: String,
        caption: String = "",
        replyToId: String? = null,
        ping: Boolean = true
    ): Result<DiscordMessage> {
        val result = rest.sendFileAttachment(channelId, fileBytes, filename, mimeType, caption, replyToId, ping)
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
            syncPreview(channelId) { if (it.id == updated.id) updated else it }
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
            syncPreview(channelId, deleted = setOf(messageId))
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

                    is GatewayEvent.VoiceStateUpdate -> {
                        val vs = event.state
                        vs.user?.let { u ->
                            userDisplayNames[u.id] = vs.nick ?: u.displayName
                            _voiceProfiles.update { it + (u.id to VoiceProfile(vs.nick ?: u.displayName, u.avatarUrl(128))) }
                        }
                        _voiceStates.update { current ->
                            if (vs.channelId == null) current - voiceKey(vs) else current + (voiceKey(vs) to vs)
                        }
                        _voiceEvents.tryEmit(event)
                    }

                    is GatewayEvent.VoiceServerUpdate -> _voiceEvents.tryEmit(event)

                    is GatewayEvent.CallUpdate -> {
                        _calls.update { it + (event.channelId to event.ringing) }
                        event.voiceStates?.let { states ->
                            _voiceStates.update { current ->
                                current + states.filter { it.channelId != null }.associateBy(::voiceKey)
                            }
                        }
                    }

                    is GatewayEvent.CallDelete -> {
                        _calls.update { it - event.channelId }
                        _voiceStates.update { current -> current.filterValues { it.guildId != null || it.channelId != event.channelId } }
                    }

                    is GatewayEvent.MembersChunk -> {
                        event.members.forEach { userDisplayNames[it.id] = it.displayName }
                        if (event.nonce?.startsWith("voice") == true) {
                            _voiceProfiles.update { profiles ->
                                profiles + event.members.associate { it.id to VoiceProfile(it.displayName, it.imageUrl) }
                            }
                        }
                        memberChunks.tryEmit(event)
                    }

                    is GatewayEvent.Ready -> {
                        event.guilds.forEach { g ->
                            g.myRoleIds?.let { myRolesByGuild[g.id] = it }
                            g.ownerId?.let { guildOwnerCache[g.id] = it }
                            if (g.roles.isNotEmpty()) guildRolesCache[g.id] = g.roles
                            g.channels.forEach { ch ->
                                channelGuildCache[ch.id] = g.id
                                if (!channelNameCache.containsKey(ch.id)) channelNameCache[ch.id] = ch.name
                            }
                        }
                        _notifySettings.value = event.notifySettings
                        relationshipStore.set(event.relationships)
                        memberListChannel.clear() // A new session has no subscriptions yet.
                        _sessions.update { it + 1 }
                        unread.onReady(event.user.id, event.guilds, event.notifySettings, event.readState.ifEmpty { _readState.value })
                        _currentUser.value = event.user
                        currentUserId = event.user.id
                        _voiceStates.value = event.voiceStates.filter { it.channelId != null }.associateBy(::voiceKey)
                        event.voiceStates.forEach { vs -> vs.user?.let { userDisplayNames[it.id] = it.displayName } }
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

                    is GatewayEvent.MessageAck -> {
                        val acked = ChannelUnreadState(event.messageId, event.mentionCount)
                        ackWatches.forEach { w ->
                            if (event.channelId in w.channels) w.furthest.compute(event.channelId) { _, old -> furtherAck(old, acked) }
                        }
                        _readState.update { current ->
                            val old = current[event.channelId]
                            if (old != null && compareSnowflakes(event.messageId, old.lastMessageId) < 0) current
                            else current + (event.channelId to ChannelUnreadState(event.messageId, event.mentionCount))
                        }
                        if (event.mentionCount == 0) _pings.update { list ->
                            list.filterNot { it.message.channelId == event.channelId && compareSnowflakes(it.message.id, event.messageId) <= 0 }
                        }
                    }

                    is GatewayEvent.MemberListUpdate -> memberListStore.apply(event)
                    is GatewayEvent.RelationshipAdd -> relationshipStore.put(event.relationship)
                    is GatewayEvent.RelationshipRemove -> relationshipStore.remove(event.userId)
                    is GatewayEvent.SoundboardPlay -> _soundboardPlays.tryEmit(event)
                    is GatewayEvent.InteractionResult -> _interactionResults.tryEmit(event)
                    is GatewayEvent.GuildJoined -> {
                        currentUser.value?.id?.let { me ->
                            val wrapped = JSONObject().put("guilds", JSONArray().put(event.raw))
                            ReadyParsing.guilds(wrapped, me).firstOrNull()?.let { g ->
                                g.channels.forEach { ch -> channelGuildCache[ch.id] = g.id }
                                unread.onGuildJoined(g, _readState.value)
                            }
                        }
                        if (_guilds.value.none { it.id == event.guild.id }) {
                            _guilds.update { it + event.guild }
                            scope.launch { refreshGuilds() }
                        }
                    }
                    is GatewayEvent.GuildLeft -> {
                        _guilds.update { list -> list.filterNot { it.id == event.guildId } }
                        unread.forgetGuild(event.guildId, _readState.value)
                    }
                    is GatewayEvent.NotifySettingsUpdate -> {
                        _notifySettings.update { it + (event.guildId to event.settings) }
                        unread.onSettings(event.guildId, event.settings, _readState.value)
                    }

                    is GatewayEvent.MessageCreate -> {
                        val msg = event.message
                        if (msg.author.id == currentUserId) typingDone(msg.channelId)
                        unread.onMessage(msg.channelId, msg.id, _readState.value, msg.guildId)
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
                            _lastMessages.update { it + (msg.channelId to msg) }
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
                        syncPreview(event.channelId) { if (it.id == event.id) mergeUpdate(it, event) else it }
                    }

                    is GatewayEvent.MessageDeleteBulk -> {
                        _messages.update { current ->
                            val list = current[event.channelId]?.filter { it.id !in event.ids }
                                ?: return@update current
                            current + (event.channelId to list)
                        }
                        syncPreview(event.channelId, deleted = event.ids.toSet())
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
                        syncPreview(event.channelId, deleted = setOf(event.id))
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
                        ch.guildId?.let { unread.onChannels(it, listOf(ch), _readState.value) }
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
                            unread.onChannelDelete(ch.guildId, ch.id, _readState.value)
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
            // Updates carry no reactions, and other fields only when they changed: keep the ones we have.
            return full.copy(
                reactions = if (raw.has("reactions")) full.reactions else old.reactions,
                poll = if (raw.has("poll")) full.poll else old.poll,
                embeds = if (raw.has("embeds")) full.embeds else old.embeds,
                components = if (raw.has("components")) full.components else old.components,
                pinned = if (raw.has("pinned")) full.pinned else old.pinned,
                flags = if (raw.has("flags")) full.flags else old.flags,
            )
        }
        // Partial update: link previews arriving after the message was sent, a bot changing its buttons, a pin.
        var merged = old
        raw.optJSONArray("embeds")?.let { arr ->
            merged = merged.copy(embeds = (0 until arr.length()).mapNotNull { i -> runCatching { Embed.fromJson(arr.getJSONObject(i)) }.getOrNull() })
        }
        if (raw.has("components")) {
            runCatching { MessageComponent.listFromJson(raw.optJSONArray("components")) }.getOrNull()?.let { merged = merged.copy(components = it) }
        }
        if (raw.has("pinned")) merged = merged.copy(pinned = raw.optBoolean("pinned"))
        if (raw.has("flags")) merged = merged.copy(flags = raw.optInt("flags"))
        return merged
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
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            _readState.debounce(300).collect { unread.recompute(it) }
        }
    }

    /** Forgets what's kept on disk for offline reading (log-out). */
    fun clearOfflineCache() = messageCache.clear()

    /**
     * Fills the repository with sample data, as if it had connected, for the screenshot tests. The app
     * never calls this.
     */
    internal fun seedForTests(
        user: DiscordUser,
        guilds: List<Guild> = emptyList(),
        dms: List<Channel> = emptyList(),
        channels: Map<String, List<CategoryGroup>> = emptyMap(),
        messages: Map<String, List<DiscordMessage>> = emptyMap(),
        readState: Map<String, ChannelUnreadState> = emptyMap(),
        presences: List<UserPresence> = emptyList(),
        voiceStates: List<VoiceState> = emptyList(),
        folders: List<GuildFolder> = emptyList(),
        relationships: List<Relationship> = emptyList(),
    ) {
        _currentUser.value = user
        currentUserId = user.id
        _guilds.value = guilds
        _dmChannels.value = dms
        _guildFolders.value = folders
        _guildChannelsMap.value = channels
        (dms + channels.values.flatten().flatMap { it.channels }).forEach { ch ->
            channelCache[ch.id] = ch
            channelNameCache[ch.id] = ch.displayName
            ch.guildId?.let { channelGuildCache[ch.id] = it }
        }
        _channelNames.value = channelNameCache.toMap()
        _channelGuilds.value = channelGuildCache.toMap()
        messages.values.flatten().forEach { userDisplayNames[it.author.id] = it.author.displayName }
        _messages.value = messages
        loadedChannels.addAll(messages.keys)
        _lastMessages.value = messages.mapNotNull { (id, list) -> list.lastOrNull()?.let { id to it } }.toMap()
        _readState.value = readState
        _presences.value = presences.associateBy { it.userId }
        _voiceStates.value = voiceStates.filter { it.channelId != null }.associateBy(::voiceKey)
        relationshipStore.set(relationships)
        // Everyone can see every channel in the sample servers.
        val everyone = { gid: String -> GuildRole(gid, "@everyone", 0, 0, Permissions.VIEW_CHANNEL) }
        unread.onReady(
            user.id,
            channels.map { (gid, groups) -> ReadyGuild(gid, null, groups.flatMap { it.channels }, listOf(everyone(gid)), emptyList()) },
            emptyMap(), readState
        )
    }
}

/**
 * Merges the latest page [fetched] (oldest first) into the [held] messages: messages inside the page's range are
 * replaced by the page (so deletions while away disappear), older and newer ones are kept. Returns null if
 * [held] doesn't reach the page (more than a page was missed), in which case the caller should start over.
 */
/** Where a failed mark-read goes back to: [state] (null: no read state), keeping pings up to [pingFloor] cleared. */
internal data class Rollback(val state: ChannelUnreadState?, val pingFloor: String?)

/** Of two reads Discord reported, the one further on; for the same message the newer report (its mention count) wins. */
internal fun furtherAck(old: ChannelUnreadState?, new: ChannelUnreadState): ChannelUnreadState =
    if (old == null || compareSnowflakes(new.lastMessageId, old.lastMessageId) >= 0) new else old

/**
 * Undoing an optimistic mark-read to [set] that Discord refused. [before] is the state before it, [furthest]
 * the furthest read Discord reported (another device) meanwhile. Null when that already covers [set]: it stands.
 * Otherwise back to the further of [before] and that read, with that read's own mention count.
 */
internal fun rollbackFor(set: ChannelUnreadState, before: ChannelUnreadState?, furthest: ChannelUnreadState?): Rollback? {
    if (furthest != null && compareSnowflakes(furthest.lastMessageId, set.lastMessageId) >= 0) return null
    val state = if (furthest != null && (before == null || compareSnowflakes(furthest.lastMessageId, before.lastMessageId) > 0)) furthest else before
    return Rollback(state, furthest?.lastMessageId)
}

/** Message flag for "@silent": shown, but no push or watch notification. */
private const val SUPPRESS_NOTIFICATIONS_FLAG = 1 shl 12

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

/** How someone in voice is shown: name and avatar. */
data class VoiceProfile(val name: String, val avatarUrl: String?)
