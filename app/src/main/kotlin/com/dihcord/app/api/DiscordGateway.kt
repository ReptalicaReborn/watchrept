package com.dihcord.app.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel as EventQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiscordGateway(private val token: String) {
    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GatewayEvent> = _events
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Events are queued here and emitted by one coroutine, so they arrive in the order Discord sent them. */
    private val outbox = EventQueue<GatewayEvent>(EventQueue.UNLIMITED)

    init {
        scope.launch { for (event in outbox) _events.emit(event) }
    }

    /** The live socket; null when there is none. Callbacks from older sockets are ignored. */
    @Volatile private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    private val seq = AtomicInteger(-1)
    @Volatile private var sessionId: String? = null
    @Volatile private var resumeUrl: String? = null
    @Volatile
    private var connected = false

    /** Whether the last heartbeat was acknowledged; if not by the next one, the connection is dead. */
    @Volatile private var acked = true

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Notices a connection that silently died (network switch, phone out of range) between heartbeats.
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private object Op {
        const val DISPATCH = 0
        const val HEARTBEAT = 1
        const val IDENTIFY = 2
        const val RESUME = 6
        const val RECONNECT = 7
        const val INVALID_SESSION = 9
        const val HELLO = 10
        const val HEARTBEAT_ACK = 11
    }

    val isConnected: Boolean get() = connected

    /** False after [pause]: scheduled reconnects (after errors, RECONNECT, INVALID_SESSION) then stay quiet. */
    @Volatile private var wanted = true

    @Synchronized
    fun connect() {
        wanted = true
        if (ws != null) return // already connected or connecting
        // resume_gateway_url comes without the version/encoding query, so add it like the default URL has.
        val url = (resumeUrl?.trimEnd('/') ?: "wss://gateway.discord.gg") + "/?v=10&encoding=json"
        val request = Request.Builder().url(url)
            .header("User-Agent", ClientIdentity.USER_AGENT)
            .header("Origin", "https://discord.com")
            .build()
        ws = http.newWebSocket(request, Listener())
    }

    fun disconnect() {
        wanted = false
        close(1000)
    }

    /** Disconnects for good and stops this gateway's background work; it can't be used afterwards. */
    fun shutdown() {
        disconnect()
        outbox.close()
        scope.cancel()
    }

    /**
     * Drops the socket but keeps the session resumable (Discord invalidates it on close code 1000), so [connect]
     * later sends RESUME and Discord replays what was missed while paused.
     */
    fun pause() {
        wanted = false
        close(4000)
    }

    @Synchronized
    private fun close(code: Int) {
        connected = false
        heartbeatJob?.cancel()
        ws?.close(code, "Goodbye")
        ws = null
    }

    /** Called when [socket] closed or failed on its own: forgets it (if still current) and reconnects if asked. */
    @Synchronized
    private fun lost(socket: WebSocket, reconnectInMs: Long?) {
        if (socket !== ws) return
        connected = false
        heartbeatJob?.cancel()
        ws = null
        if (reconnectInMs != null) reconnectLater(reconnectInMs)
    }

    private fun reconnectLater(ms: Long) {
        scope.launch {
            delay(ms)
            if (wanted) connect()
        }
    }

    private fun send(op: Int, d: Any?) {
        val payload = JSONObject()
            .put("op", op)
            .put("d", d)
        ws?.send(payload.toString())
    }

    /** Sets this session's presence (op 3) so the new status shows right away. */
    fun updatePresence(status: String, customText: String) {
        val activities = JSONArray()
        if (customText.isNotBlank()) {
            activities.put(JSONObject().put("type", 4).put("name", "Custom Status").put("state", customText))
        }
        send(
            3,
            JSONObject()
                .put("status", status)
                .put("since", 0)
                .put("activities", activities)
                .put("afk", false)
        )
    }

    /** Asks for members of [guildId] whose name starts with [query] (answered by GUILD_MEMBERS_CHUNK). */
    fun requestGuildMembers(guildId: String, query: String, limit: Int, nonce: String) {
        send(
            8,
            JSONObject()
                .put("guild_id", JSONArray().put(guildId))
                .put("query", query)
                .put("limit", limit)
                .put("presences", false)
                .put("nonce", nonce)
        )
    }

    /** Joins ([channelId] set) or leaves (null) voice, or changes our mute/deafen flags (op 4). */
    fun updateVoiceState(guildId: String?, channelId: String?, selfMute: Boolean, selfDeaf: Boolean) {
        send(
            4,
            JSONObject()
                .put("guild_id", guildId ?: JSONObject.NULL)
                .put("channel_id", channelId ?: JSONObject.NULL)
                .put("self_mute", selfMute)
                .put("self_deaf", selfDeaf)
                .put("self_video", false)
        )
    }

    fun selectChannel(channelId: String, guildId: String?) {
        if (guildId != null) {
            val sub = JSONObject()
                .put("op", 14)
                .put(
                    "d", JSONObject()
                        .put("guild_id", guildId)
                        .put("typing", true)
                        .put("threads", true)
                        .put("activities", !com.dihcord.app.Perf.lite)
                        .put(
                            "channels",
                            JSONObject().put(channelId, JSONArray().put(JSONArray().put(0).put(99)))
                        )
                )
            ws?.send(sub.toString())
        }
        val d = JSONObject()
            .put("channel_id", channelId)
            .put("guild_id", guildId ?: JSONObject.NULL)
            .put("with_message", true)
        send(13, d)
    }

    private fun sendHeartbeat() {
        val s = seq.get().takeIf { it >= 0 }
        send(Op.HEARTBEAT, s)
    }

    private fun sendIdentify() {
        val d = JSONObject()
            .put("token", token)
            .put("properties", ClientIdentity.properties())
        send(Op.IDENTIFY, d)
    }

    private fun sendResume() {
        val d = JSONObject()
            .put("token", token)
            .put("session_id", sessionId)
            .put("seq", seq.get())
        send(Op.RESUME, d)
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket === ws) connected = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
            val op = payload.optInt("op", -1)
            val d = payload.opt("d")
            val s = payload.optInt("s", -1).takeIf { it >= 0 }
            val t = payload.optString("t").takeIf { it.isNotEmpty() }

            if (s != null) seq.set(s)

            when (op) {
                Op.HELLO -> {
                    val interval = (d as? JSONObject)?.optLong("heartbeat_interval", 41250L) ?: 41250L
                    acked = true
                    startHeartbeat(interval)
                    if (sessionId != null) sendResume() else sendIdentify()
                }

                Op.HEARTBEAT -> sendHeartbeat()

                Op.HEARTBEAT_ACK -> acked = true

                Op.RECONNECT -> {
                    close(4000) // keep the session so the reconnect can RESUME
                    reconnectLater(1_000)
                }

                Op.INVALID_SESSION -> {
                    val resumable = (d as? Boolean) == true
                    if (!resumable) {
                        sessionId = null; resumeUrl = null
                    }
                    close(4000)
                    reconnectLater(2_000)
                }

                Op.DISPATCH -> {
                    if (t != null) {
                        runCatching { handleDispatch(t, d as? JSONObject) }
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response?.code == 401) {
                lost(webSocket, reconnectInMs = null)
                outbox.trySend(GatewayEvent.InvalidToken)
            } else {
                lost(webSocket, reconnectInMs = 5_000)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Finish the close handshake Discord started, so onClosed follows promptly.
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            when (code) {
                4004 -> { // authentication failed
                    lost(webSocket, reconnectInMs = null)
                    outbox.trySend(GatewayEvent.InvalidToken)
                }
                // Bad shard/version/intents: reconnecting would fail the same way.
                in 4010..4014 -> lost(webSocket, reconnectInMs = null)
                else -> {
                    // 4007 (bad seq) and 4009 (session timed out) need a fresh IDENTIFY instead of RESUME.
                    if (code == 4007 || code == 4009) { sessionId = null; resumeUrl = null }
                    lost(webSocket, reconnectInMs = 2_000)
                }
            }
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        val socket = ws
        heartbeatJob = scope.launch {
            delay((intervalMs * Math.random()).toLong())
            while (isActive) {
                if (!acked) {
                    // No ACK since the last heartbeat: the connection is a zombie. Drop it and RESUME on a new one.
                    if (socket != null) {
                        socket.cancel()
                        lost(socket, reconnectInMs = 1_000)
                    }
                    return@launch
                }
                acked = false
                sendHeartbeat()
                delay(intervalMs)
            }
        }
    }

    private fun handleDispatch(eventName: String, d: JSONObject?) {
        if (d == null) return

        val event: GatewayEvent? = when (eventName) {
            "READY" -> {
                sessionId = d.optString("session_id")
                resumeUrl = d.optString("resume_gateway_url")
                val user = runCatching { DiscordUser.fromJson(d.getJSONObject("user")) }.getOrNull()
                val readState = mutableMapOf<String, ChannelUnreadState>()
                val rsArr = d.optJSONArray("read_state")
                    ?: d.optJSONObject("read_state")?.optJSONArray("entries")
                if (rsArr != null) {
                    for (i in 0 until rsArr.length()) {
                        runCatching {
                            val rs = rsArr.getJSONObject(i)
                            val chId =
                                rs.optString("id").takeIf { it.isNotEmpty() } ?: return@runCatching
                            val lastRead = rs.optString("last_message_id")
                                .takeIf { it.isNotEmpty() && it != "null" }
                                ?: return@runCatching
                            val mentionCount = rs.optInt("mention_count", 0)
                            readState[chId] = ChannelUnreadState(lastRead, mentionCount)
                        }
                    }
                }
                val presences = mutableListOf<UserPresence>()
                val presArr = d.optJSONArray("presences")
                if (presArr != null) {
                    for (i in 0 until presArr.length()) {
                        runCatching { presences.add(UserPresence.fromJson(presArr.getJSONObject(i))) }
                    }
                }
                val folders = d.optJSONObject("user_settings")?.optJSONArray("guild_folders")
                    ?.let { GuildFolder.listFromJson(it) }
                    .orEmpty()
                // User-account READY carries every guild's emojis and stickers; keep them for the picker.
                val guildEmojis = mutableMapOf<String, List<GuildEmoji>>()
                val guildStickers = mutableMapOf<String, List<StickerItem>>()
                val voiceStates = mutableListOf<VoiceState>()
                d.optJSONArray("guilds")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val g = arr.optJSONObject(i) ?: continue
                        val gid = g.optString("id").takeIf { it.isNotEmpty() } ?: continue
                        g.optJSONArray("voice_states")?.let { vs ->
                            for (j in 0 until vs.length()) {
                                vs.optJSONObject(j)?.let { VoiceState.fromJson(it, gid) }?.let(voiceStates::add)
                            }
                        }
                        g.optJSONArray("emojis")?.let { guildEmojis[gid] = GuildEmoji.listFromJson(it) }
                        g.optJSONArray("stickers")?.let { st ->
                            guildStickers[gid] = (0 until st.length()).mapNotNull {
                                runCatching { StickerItem.fromJson(st.getJSONObject(it)) }.getOrNull()
                            }.filter { it.isDisplayable }
                        }
                    }
                }
                if (user != null) {
                    GatewayEvent.Ready(user, readState, presences, folders, guildEmojis, guildStickers, voiceStates)
                } else null
            }

            "MESSAGE_CREATE" -> runCatching {
                val msg = DiscordMessage.fromJson(d)
                val memberObj = d.optJSONObject("member")
                val rolesArr = memberObj?.optJSONArray("roles")
                val roleIds = if (rolesArr != null)
                    (0 until rolesArr.length()).map { rolesArr.getString(it) }
                else emptyList()
                val guildId = d.optString("guild_id").takeIf { it.isNotEmpty() && it != "null" }
                GatewayEvent.MessageCreate(msg, roleIds, guildId)
            }.getOrNull()

            "MESSAGE_UPDATE" -> runCatching {
                // Updates can be partial (an embed-only update has no author): keep the raw fields to merge.
                GatewayEvent.MessageUpdate(
                    id = d.getString("id"),
                    channelId = d.getString("channel_id"),
                    message = runCatching { DiscordMessage.fromJson(d) }.getOrNull(),
                    raw = d
                )
            }.getOrNull()

            "MESSAGE_DELETE_BULK" -> runCatching {
                val ids = d.getJSONArray("ids")
                GatewayEvent.MessageDeleteBulk(
                    ids = (0 until ids.length()).map { ids.getString(it) }.toSet(),
                    channelId = d.getString("channel_id")
                )
            }.getOrNull()

            "MESSAGE_REACTION_REMOVE_ALL" -> runCatching {
                GatewayEvent.ReactionRemoveAll(
                    messageId = d.getString("message_id"),
                    channelId = d.getString("channel_id"),
                    emoji = null
                )
            }.getOrNull()

            "MESSAGE_REACTION_REMOVE_EMOJI" -> runCatching {
                GatewayEvent.ReactionRemoveAll(
                    messageId = d.getString("message_id"),
                    channelId = d.getString("channel_id"),
                    emoji = ReactionEmoji.fromJson(d.getJSONObject("emoji"))
                )
            }.getOrNull()

            "MESSAGE_DELETE" -> runCatching {
                GatewayEvent.MessageDelete(
                    id = d.getString("id"),
                    channelId = d.getString("channel_id")
                )
            }.getOrNull()

            "MESSAGE_POLL_VOTE_ADD", "MESSAGE_POLL_VOTE_REMOVE" -> runCatching {
                GatewayEvent.PollVote(
                    channelId = d.getString("channel_id"),
                    messageId = d.getString("message_id"),
                    userId = d.getString("user_id"),
                    answerId = d.getInt("answer_id"),
                    add = eventName == "MESSAGE_POLL_VOTE_ADD"
                )
            }.getOrNull()

            "MESSAGE_REACTION_ADD" -> runCatching {
                GatewayEvent.ReactionAdd(
                    messageId = d.getString("message_id"),
                    channelId = d.getString("channel_id"),
                    userId = d.getString("user_id"),
                    emoji = ReactionEmoji.fromJson(d.getJSONObject("emoji")),
                    burst = d.optBoolean("burst", false) || d.optInt("type", 0) == 1
                )
            }.getOrNull()

            "MESSAGE_REACTION_REMOVE" -> runCatching {
                GatewayEvent.ReactionRemove(
                    messageId = d.getString("message_id"),
                    channelId = d.getString("channel_id"),
                    userId = d.getString("user_id"),
                    emoji = ReactionEmoji.fromJson(d.getJSONObject("emoji")),
                    burst = d.optBoolean("burst", false) || d.optInt("type", 0) == 1
                )
            }.getOrNull()

            "TYPING_START" -> runCatching {
                fun String.realOrNull() = takeIf { it.isNotEmpty() && it != "null" }
                val memberObj = d.optJSONObject("member")
                val userObj = memberObj?.optJSONObject("user") ?: d.optJSONObject("user")
                val nick = memberObj?.optString("nick")?.realOrNull()
                val globalName = userObj?.optString("global_name")?.realOrNull()
                val username = userObj?.optString("username")?.realOrNull()
                val displayName = nick ?: globalName ?: username
                GatewayEvent.TypingStart(
                    channelId = d.getString("channel_id"),
                    userId = d.getString("user_id"),
                    displayName = displayName
                )
            }.getOrNull()

            "GUILD_MEMBERS_CHUNK" -> runCatching {
                val arr = d.optJSONArray("members") ?: JSONArray()
                val members = (0 until arr.length()).mapNotNull { i ->
                    val m = arr.optJSONObject(i) ?: return@mapNotNull null
                    val u = m.optJSONObject("user") ?: return@mapNotNull null
                    val user = DiscordUser.fromJson(u)
                    val nick = if (m.isNull("nick")) null else m.optString("nick").takeIf { it.isNotEmpty() }
                    MentionCandidate(user.id, nick ?: user.displayName, user.username)
                }
                GatewayEvent.MembersChunk(d.optString("nonce").takeIf { it.isNotEmpty() }, members)
            }.getOrNull()

            "VOICE_STATE_UPDATE" -> VoiceState.fromJson(d, null)?.let { GatewayEvent.VoiceStateUpdate(it) }

            "VOICE_SERVER_UPDATE" -> GatewayEvent.VoiceServerUpdate(
                token = d.optString("token"),
                guildId = d.optString("guild_id").takeIf { it.isNotEmpty() && it != "null" },
                channelId = d.optString("channel_id").takeIf { it.isNotEmpty() && it != "null" },
                endpoint = d.optString("endpoint").takeIf { it.isNotEmpty() && it != "null" },
            )

            "PRESENCE_UPDATE" -> runCatching {
                GatewayEvent.PresenceUpdate(UserPresence.fromJson(d))
            }.getOrNull()

            "CHANNEL_CREATE" -> runCatching {
                GatewayEvent.ChannelCreate(Channel.fromJson(d))
            }.getOrNull()

            "CHANNEL_UPDATE" -> runCatching {
                GatewayEvent.ChannelUpdate(Channel.fromJson(d))
            }.getOrNull()

            "CHANNEL_DELETE" -> runCatching {
                val id = d.getString("id")
                val guildId = d.optString("guild_id").takeIf { it.isNotEmpty() && it != "null" }
                val typeCode = d.optInt("type", 0)
                val type = ChannelType.from(typeCode)
                val channel = Channel(
                    id = id,
                    type = type,
                    guildId = guildId,
                    name = d.optString("name"),
                    topic = null,
                    lastMessageId = null,
                    parentId = null,
                    position = 0,
                    permissionOverwrites = emptyList(),
                    recipients = emptyList(),
                    hasAccess = true,
                    slowModeSeconds = 0
                )
                GatewayEvent.ChannelDelete(channel)
            }.getOrNull()

            else -> GatewayEvent.Unknown(eventName)
        }

        if (event != null && event !is GatewayEvent.Unknown) outbox.trySend(event)
    }
}

sealed class GatewayEvent {
    data class Ready(
        val user: DiscordUser,
        val readState: Map<String, ChannelUnreadState> = emptyMap(),
        val presences: List<UserPresence> = emptyList(),
        val folders: List<GuildFolder> = emptyList(),
        val guildEmojis: Map<String, List<GuildEmoji>> = emptyMap(),
        val guildStickers: Map<String, List<StickerItem>> = emptyMap(),
        val voiceStates: List<VoiceState> = emptyList()
    ) : GatewayEvent()

    data class VoiceStateUpdate(val state: VoiceState) : GatewayEvent()
    data class VoiceServerUpdate(
        val token: String,
        val guildId: String?,
        val channelId: String?,
        /** Null while Discord is still picking a server; another update follows. */
        val endpoint: String?
    ) : GatewayEvent()

    data class MessageCreate(
        val message: DiscordMessage,
        val memberRoleIds: List<String> = emptyList(),
        val guildId: String? = null
    ) : GatewayEvent()

    /** [message] is null when the update is partial; [raw] says which fields it carries. */
    data class MessageUpdate(
        val id: String,
        val channelId: String,
        val message: DiscordMessage?,
        val raw: JSONObject
    ) : GatewayEvent()
    data class MessageDeleteBulk(val ids: Set<String>, val channelId: String) : GatewayEvent()
    /** A moderator cleared every reaction on a message, or only those with [emoji]. */
    data class ReactionRemoveAll(val messageId: String, val channelId: String, val emoji: ReactionEmoji?) : GatewayEvent()
    data class PollVote(
        val channelId: String,
        val messageId: String,
        val userId: String,
        val answerId: Int,
        val add: Boolean
    ) : GatewayEvent()
    data class MessageDelete(val id: String, val channelId: String) : GatewayEvent()
    data class ReactionAdd(
        val messageId: String,
        val channelId: String,
        val userId: String,
        val emoji: ReactionEmoji,
        val burst: Boolean = false
    ) : GatewayEvent()

    data class ReactionRemove(
        val messageId: String,
        val channelId: String,
        val userId: String,
        val emoji: ReactionEmoji,
        val burst: Boolean = false
    ) : GatewayEvent()

    data class TypingStart(
        val channelId: String,
        val userId: String,
        val displayName: String? = null
    ) : GatewayEvent()

    data class PresenceUpdate(val presence: UserPresence) : GatewayEvent()
    data class ChannelCreate(val channel: Channel) : GatewayEvent()
    data class ChannelUpdate(val channel: Channel) : GatewayEvent()
    data class ChannelDelete(val channel: Channel) : GatewayEvent()
    /** Reply to a member search ([DiscordGateway.requestGuildMembers]). */
    data class MembersChunk(val nonce: String?, val members: List<MentionCandidate>) : GatewayEvent()
    object InvalidToken : GatewayEvent()
    data class Unknown(val name: String) : GatewayEvent()
}

/** Someone's voice connection: which channel they're in and their mute/deafen flags. */
data class VoiceState(
    val userId: String,
    val guildId: String?,
    /** Null when they left voice. */
    val channelId: String?,
    val sessionId: String,
    val selfMute: Boolean,
    val selfDeaf: Boolean,
    val serverMute: Boolean,
    val serverDeaf: Boolean,
    /** From the member object, when the event carries one. */
    val user: DiscordUser? = null,
    val nick: String? = null
) {
    val muted: Boolean get() = selfMute || serverMute
    val deafened: Boolean get() = selfDeaf || serverDeaf

    companion object {
        fun fromJson(o: JSONObject, guildId: String?): VoiceState? {
            val userId = o.optString("user_id").takeIf { it.isNotEmpty() } ?: return null
            val member = o.optJSONObject("member")
            return VoiceState(
                userId = userId,
                guildId = guildId ?: o.optString("guild_id").takeIf { it.isNotEmpty() && it != "null" },
                channelId = o.optString("channel_id").takeIf { it.isNotEmpty() && it != "null" },
                sessionId = o.optString("session_id"),
                selfMute = o.optBoolean("self_mute"),
                selfDeaf = o.optBoolean("self_deaf"),
                serverMute = o.optBoolean("mute"),
                serverDeaf = o.optBoolean("deaf"),
                user = member?.optJSONObject("user")?.let { runCatching { DiscordUser.fromJson(it) }.getOrNull() },
                nick = member?.optString("nick")?.takeIf { it.isNotEmpty() && it != "null" }
            )
        }
    }
}
