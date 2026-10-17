package com.dihcord.app.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.dihcord.app.api.ClientIdentity.discordHeaders
import com.dihcord.app.captcha.CaptchaBroker
import com.dihcord.app.captcha.CaptchaChallenge
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How many captchas in a row the user may be asked to solve for one request. */
private const val MAX_CAPTCHA_TRIES = 3

/** Longest rate-limit wait (ms) worth sitting through before giving up on a request. */
private const val MAX_RATE_LIMIT_WAIT_MS = 5_000L

/** Shared by every REST client in the process, like one browser profile. */
private val cookieJar = MemoryCookieJar()

/** [baseUrl] is only changed by the screenshot tests, which point it at a closed local port. */
class DiscordRestClient(private val token: String, private val baseUrl: String = "https://discord.com/api/v9") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    private val jsonMime = "application/json; charset=utf-8".toMediaType()

    private fun buildRequest(path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", token)
            .discordHeaders()

    private class RawResponse(val code: Int, val body: String, val ok: Boolean, val retryAfter: String? = null)

    private suspend fun send(request: Request): RawResponse = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val response = http.newCall(request).execute().use {
                RawResponse(it.code, it.body?.string().orEmpty(), it.isSuccessful, it.header("Retry-After"))
            }
            // Rate limited: wait as long as Discord asks (if that's short) and try again, rather than failing.
            val waitMs = if (response.code == 429) retryAfterMs(response) else null
            if (waitMs == null || waitMs > MAX_RATE_LIMIT_WAIT_MS || attempt++ >= 2) return@withContext response
            kotlinx.coroutines.delay(waitMs)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun retryAfterMs(response: RawResponse): Long? {
        val seconds = runCatching { JSONObject(response.body).optDouble("retry_after", Double.NaN) }.getOrNull()
            ?.takeIf { !it.isNaN() }
            ?: response.retryAfter?.toDoubleOrNull()
            ?: return null
        return (seconds * 1000).toLong().coerceAtLeast(100)
    }

    /**
     * Runs [request]. If Discord answers with a captcha challenge, asks the user to solve it (see CaptchaBroker)
     * and retries with the solution. Discord may reject an answer and send a fresh challenge, so the user gets
     * a few tries; if they cancel, fails with [CaptchaRequiredException].
     */
    private suspend fun execute(request: Request): String {
        var response = send(request)
        repeat(MAX_CAPTCHA_TRIES) {
            if (response.code != 400) return checked(response)
            val challenge = CaptchaChallenge.parse(response.body) ?: return checked(response)
            val key = CaptchaBroker.solve(challenge) ?: throw CaptchaRequiredException()
            val retry = request.newBuilder()
                .apply { challenge.answerHeaders(key).forEach { (k, v) -> header(k, v) } }
                .build()
            response = send(retry)
        }
        return checked(response)
    }

    private fun checked(response: RawResponse): String {
        if (!response.ok) {
            if (response.code == 400 && CaptchaChallenge.parse(response.body) != null) throw CaptchaRequiredException()
            val msg = runCatching { JSONObject(response.body).optString("message", response.body) }
                .getOrDefault(response.body)
            throw DiscordApiException(response.code, msg)
        }
        return response.body
    }

    private suspend fun get(path: String): String =
        execute(buildRequest(path).get().build())

    private suspend fun post(path: String, body: JSONObject): String =
        execute(
            buildRequest(path)
                .post(body.toString().toRequestBody(jsonMime))
                .build()
        )

    private suspend fun delete(path: String): String =
        execute(buildRequest(path).delete().build())

    private suspend fun put(path: String, body: JSONObject = JSONObject()): String =
        execute(buildRequest(path).put(body.toString().toRequestBody(jsonMime)).build())

    private suspend fun patch(path: String, body: JSONObject): String =
        execute(buildRequest(path).patch(body.toString().toRequestBody(jsonMime)).build())

    /** A POST with an extra header, e.g. the X-Context-Properties Discord's client sends when joining. */
    private suspend fun postWith(path: String, body: JSONObject, header: Pair<String, String>): String =
        execute(buildRequest(path).header(header.first, header.second).post(body.toString().toRequestBody(jsonMime)).build())

    private fun nonce(): String = (((System.currentTimeMillis() - 1_420_070_400_000L) shl 22)).toString()

    // ---- Typing, read state ----

    /** Tells the others in [channelId] we're typing (Discord shows it for ~10 s). */
    suspend fun sendTyping(channelId: String): Result<Unit> = runCatching {
        post("/channels/$channelId/typing", JSONObject()); Unit
    }

    /** Marks many channels read at once; [reads] are (channel id, message id). */
    suspend fun ackBulk(reads: List<Pair<String, String>>): Result<Unit> = runCatching {
        reads.chunked(100).forEach { chunk ->
            val arr = JSONArray()
            chunk.forEach { (c, m) -> arr.put(JSONObject().put("channel_id", c).put("message_id", m).put("read_state_type", 0)) }
            post("/read-states/ack-bulk", JSONObject().put("read_states", arr))
        }
    }

    // ---- Pins, search ----

    suspend fun getPins(channelId: String): Result<List<DiscordMessage>> = runCatching {
        DiscordMessage.listFromJson(JSONArray(get("/channels/$channelId/pins")))
    }

    suspend fun setPinned(channelId: String, messageId: String, pinned: Boolean): Result<Unit> = runCatching {
        if (pinned) put("/channels/$channelId/pins/$messageId") else delete("/channels/$channelId/pins/$messageId")
        Unit
    }

    /** Thrown when Discord is still indexing a channel for search (it answers 202 with a retry hint). */
    class SearchNotReadyException : java.io.IOException("Search isn't ready yet")

    /** Messages matching [query] in [channelId] (a server channel when [guildId] is set, else a DM), newest first. */
    suspend fun searchMessages(guildId: String?, channelId: String, query: String): Result<List<DiscordMessage>> = runCatching {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val path = if (guildId != null) "/guilds/$guildId/messages/search?content=$q&channel_id=$channelId"
        else "/channels/$channelId/messages/search?content=$q"
        val response = send(buildRequest(path).get().build())
        if (response.code == 202) throw SearchNotReadyException()
        val o = JSONObject(checked(response))
        val groups = o.optJSONArray("messages") ?: JSONArray()
        (0 until groups.length()).mapNotNull { i ->
            val group = groups.optJSONArray(i) ?: return@mapNotNull null
            // Each hit comes with context messages around it; the hit itself is flagged.
            val hit = (0 until group.length()).map { group.getJSONObject(it) }.firstOrNull { it.optBoolean("hit") }
                ?: group.optJSONObject(0) ?: return@mapNotNull null
            runCatching { DiscordMessage.fromJson(hit) }.getOrNull()
        }
    }

    // ---- People ----

    suspend fun getRelationships(): Result<List<Relationship>> = runCatching {
        Relationship.listFromJson(JSONArray(get("/users/@me/relationships")))
    }

    /** Accepts a friend request (or sends one back). */
    suspend fun acceptFriend(userId: String): Result<Unit> = runCatching { put("/users/@me/relationships/$userId"); Unit }

    /** Declines/cancels a request, removes a friend, or unblocks. */
    suspend fun removeRelationship(userId: String): Result<Unit> = runCatching { delete("/users/@me/relationships/$userId"); Unit }

    suspend fun blockUser(userId: String): Result<Unit> = runCatching {
        put("/users/@me/relationships/$userId", JSONObject().put("type", Relationship.BLOCKED)); Unit
    }

    /** Sets our nickname in [guildId]; blank clears it. */
    suspend fun setNickname(guildId: String, nick: String): Result<Unit> = runCatching {
        patch("/guilds/$guildId/members/@me", JSONObject().put("nick", nick.ifBlank { JSONObject.NULL }))
        Unit
    }

    /** Creates a group DM with [userIds]. */
    suspend fun createGroupDm(userIds: List<String>): Result<Channel> = runCatching {
        Channel.fromJson(JSONObject(post("/users/@me/channels", JSONObject().put("recipients", JSONArray(userIds)))))
    }

    suspend fun addToGroup(channelId: String, userId: String): Result<Unit> = runCatching {
        put("/channels/$channelId/recipients/$userId"); Unit
    }

    // ---- Sending ----

    /** Forwards [messageId] (from [fromChannelId]) to [toChannelId]. */
    suspend fun forwardMessage(toChannelId: String, fromChannelId: String, fromGuildId: String?, messageId: String): Result<DiscordMessage> = runCatching {
        val ref = JSONObject().put("type", 1).put("message_id", messageId).put("channel_id", fromChannelId)
        if (fromGuildId != null) ref.put("guild_id", fromGuildId)
        DiscordMessage.fromJson(JSONObject(post("/channels/$toChannelId/messages", JSONObject().put("message_reference", ref).put("nonce", nonce()))))
    }

    /** Posts a poll; [answers] are (text, emoji or null). */
    suspend fun createPoll(channelId: String, question: String, answers: List<Pair<String, String?>>, hours: Int, multiselect: Boolean): Result<DiscordMessage> = runCatching {
        val arr = JSONArray()
        answers.forEach { (text, emoji) ->
            val media = JSONObject().put("text", text)
            if (emoji != null) media.put("emoji", JSONObject().put("name", emoji))
            arr.put(JSONObject().put("poll_media", media))
        }
        val poll = JSONObject()
            .put("question", JSONObject().put("text", question))
            .put("answers", arr)
            .put("duration", hours)
            .put("allow_multiselect", multiselect)
            .put("layout_type", 1)
        DiscordMessage.fromJson(JSONObject(post("/channels/$channelId/messages", JSONObject().put("poll", poll).put("nonce", nonce()))))
    }

    // ---- Servers ----

    suspend fun getInvite(code: String): Result<InvitePreview> = runCatching {
        InvitePreview.fromJson(JSONObject(get("/invites/$code?with_counts=true")))
    }

    /** Joins the server behind [code] (a captcha may be asked, like on Discord's own client). */
    suspend fun joinInvite(code: String): Result<Unit> = runCatching {
        val context = java.util.Base64.getEncoder().encodeToString("{\"location\":\"Markdown Link\"}".toByteArray())
        postWith("/invites/$code", JSONObject(), "X-Context-Properties" to context)
        Unit
    }

    suspend fun leaveGuild(guildId: String): Result<Unit> = runCatching {
        execute(buildRequest("/users/@me/guilds/$guildId").delete(JSONObject().put("lurking", false).toString().toRequestBody(jsonMime)).build())
        Unit
    }

    suspend fun getScheduledEvents(guildId: String): Result<List<ScheduledEvent>> = runCatching {
        ScheduledEvent.listFromJson(JSONArray(get("/guilds/$guildId/scheduled-events?with_user_count=true")))
    }

    suspend fun setEventInterest(guildId: String, eventId: String, interested: Boolean): Result<Unit> = runCatching {
        val path = "/guilds/$guildId/scheduled-events/$eventId/users/@me"
        if (interested) put(path) else delete(path)
        Unit
    }

    /** On a stage: ask to speak (or take the request back). */
    suspend fun requestToSpeak(guildId: String, channelId: String, request: Boolean): Result<Unit> = runCatching {
        val at: Any = if (request) java.time.Instant.now().toString() else JSONObject.NULL
        patch("/guilds/$guildId/voice-states/@me", JSONObject().put("channel_id", channelId).put("request_to_speak_timestamp", at))
        Unit
    }

    // ---- Soundboard ----

    suspend fun getDefaultSounds(): Result<List<SoundboardSound>> = runCatching {
        SoundboardSound.listFromJson(JSONArray(get("/soundboard-default-sounds")))
    }

    suspend fun getGuildSounds(guildId: String): Result<List<SoundboardSound>> = runCatching {
        SoundboardSound.listFromJson(JSONObject(get("/guilds/$guildId/soundboard-sounds")).optJSONArray("items"))
    }

    suspend fun playSound(voiceChannelId: String, sound: SoundboardSound): Result<Unit> = runCatching {
        val body = JSONObject().put("sound_id", sound.id)
        if (sound.guildId != null) body.put("source_guild_id", sound.guildId)
        post("/channels/$voiceChannelId/send-soundboard-sound", body)
        Unit
    }

    // ---- Bots: commands and components ----

    /** Slash commands usable in [channelId] (a server's when [guildId] is set). */
    suspend fun getCommands(guildId: String?, channelId: String): Result<List<ApplicationCommand>> = runCatching {
        val path = if (guildId != null) "/guilds/$guildId/application-command-index" else "/channels/$channelId/application-command-index"
        ApplicationCommand.indexFromJson(JSONObject(get(path)))
    }

    /** Runs [command] with [options] (name to value). Returns the nonce the gateway's answer will carry. */
    suspend fun runCommand(
        command: ApplicationCommand,
        options: Map<String, Any>,
        guildId: String?,
        channelId: String,
        sessionId: String,
    ): Result<String> = runCatching {
        val opts = JSONArray()
        command.options.filter { it.name in options }.forEach { o ->
            opts.put(JSONObject().put("type", o.type).put("name", o.name).put("value", options[o.name]))
        }
        val data = JSONObject()
            .put("version", command.version)
            .put("id", command.id)
            .put("name", command.name)
            .put("type", 1)
            .put("options", opts)
            .put("application_command", command.raw)
            .put("attachments", JSONArray())
        val n = nonce()
        post("/interactions", interaction(2, command.applicationId, guildId, channelId, sessionId, n).put("data", data))
        n
    }

    /** Presses a bot button, or picks [values] in its menu. Returns the nonce. */
    suspend fun pressComponent(
        message: DiscordMessage,
        componentType: Int,
        customId: String,
        values: List<String>?,
        guildId: String?,
        sessionId: String,
    ): Result<String> = runCatching {
        val appId = message.applicationId ?: error("This message has no bot behind it")
        val data = JSONObject().put("component_type", componentType).put("custom_id", customId)
        if (values != null) data.put("values", JSONArray(values))
        val n = nonce()
        val body = interaction(3, appId, guildId, message.channelId, sessionId, n)
            .put("message_id", message.id)
            .put("message_flags", message.flags)
            .put("data", data)
        post("/interactions", body)
        n
    }

    private fun interaction(type: Int, appId: String, guildId: String?, channelId: String, sessionId: String, nonce: String) =
        JSONObject()
            .put("type", type)
            .put("application_id", appId)
            .put("guild_id", guildId ?: JSONObject.NULL)
            .put("channel_id", channelId)
            .put("session_id", sessionId)
            .put("nonce", nonce)

    /** The user's online status ("online", "idle", "dnd", "invisible") and custom status text. */
    suspend fun getStatus(): Result<Pair<String, String>> = runCatching {
        val o = JSONObject(get("/users/@me/settings"))
        val text = o.optJSONObject("custom_status")?.optString("text").orEmpty().takeIf { it != "null" }.orEmpty()
        o.optString("status", "online") to text
    }

    /** Saves the online status and custom status (blank text clears it) to the account, for every device. */
    suspend fun setStatus(status: String, customText: String): Result<Unit> = runCatching {
        val body = JSONObject()
            .put("status", status)
            .put("custom_status", if (customText.isBlank()) JSONObject.NULL else JSONObject().put("text", customText))
        execute(buildRequest("/users/@me/settings").patch(body.toString().toRequestBody(jsonMime)).build())
        Unit
    }

    suspend fun getCurrentUser(): Result<DiscordUser> = runCatching {
        DiscordUser.fromJson(JSONObject(get("/users/@me")))
    }

    suspend fun getGuilds(): Result<List<Guild>> = runCatching {
        Guild.listFromJson(JSONArray(get("/users/@me/guilds?limit=200")))
    }

    @Volatile private var myUserId: String? = null

    /**
     * The current user's membership (roles) in [guildId]. `/guilds/{id}/members/@me` is not a readable endpoint,
     * so use the user-scoped one, falling back to the member lookup by the user's own id.
     */
    suspend fun getGuildMember(guildId: String): Result<GuildMember> = runCatching {
        runCatching { GuildMember.fromJson(JSONObject(get("/users/@me/guilds/$guildId/member"))) }
            .getOrElse {
                val me = myUserId ?: getCurrentUser().getOrThrow().id.also { myUserId = it }
                GuildMember.fromJson(JSONObject(get("/guilds/$guildId/members/$me")))
            }
    }

    suspend fun getGuildMember(guildId: String, userId: String): Result<GuildMember> = runCatching {
        GuildMember.fromJson(JSONObject(get("/guilds/$guildId/members/$userId")))
    }

    suspend fun getGuildMemberRoles(guildId: String, userId: String): Result<List<String>> =
        runCatching {
            val member = JSONObject(get("/guilds/$guildId/members/$userId"))
            val arr = member.getJSONArray("roles")
            (0 until arr.length()).map { arr.getString(it) }
        }

    /** The user's server folder layout; used when the gateway READY didn't include it. */
    suspend fun getGuildFolders(): Result<List<GuildFolder>> = runCatching {
        val arr = JSONObject(get("/users/@me/settings")).optJSONArray("guild_folders") ?: JSONArray()
        GuildFolder.listFromJson(arr)
    }

    /**
     * Threads in [channelId] (or posts in a forum), newest activity first. Active ones by default;
     * [archived] lists older, archived ones. Falls back to the guild-wide active list if search isn't allowed.
     */
    suspend fun getChannelThreads(
        channelId: String,
        guildId: String?,
        archived: Boolean = false
    ): Result<List<ThreadSummary>> = runCatching {
        val search = runCatching {
            ThreadSummary.listFromResponse(
                JSONObject(
                    get("/channels/$channelId/threads/search?archived=$archived&sort_by=last_message_time&sort_order=desc&limit=25")
                )
            )
        }
        search.getOrNull() ?: if (archived) {
            ThreadSummary.listFromResponse(JSONObject(get("/channels/$channelId/threads/archived/public?limit=25")))
        } else {
            val gid = guildId ?: throw search.exceptionOrNull() ?: IOException("No guild")
            ThreadSummary.listFromResponse(JSONObject(get("/guilds/$gid/threads/active")))
                .filter { it.parentId == channelId }
        }
    }

    /** Starts a public thread on [messageId] (Discord's "Create Thread" on a message). */
    suspend fun createThreadFromMessage(
        channelId: String,
        messageId: String,
        name: String
    ): Result<ThreadSummary> = runCatching {
        val body = JSONObject().put("name", name.take(100)).put("auto_archive_duration", 1440)
        ThreadSummary.fromJson(JSONObject(post("/channels/$channelId/messages/$messageId/threads", body)))
    }

    suspend fun getGuildOwnerId(guildId: String): Result<String> = runCatching {
        JSONObject(get("/guilds/$guildId")).getString("owner_id")
    }

    suspend fun getGuildRoles(guildId: String): Result<List<GuildRole>> = runCatching {
        val guild = JSONObject(get("/guilds/$guildId"))
        GuildRole.listFromJson(guild.getJSONArray("roles"))
    }

    suspend fun getChannel(channelId: String): Result<Channel> = runCatching {
        Channel.fromJson(JSONObject(get("/channels/$channelId")))
    }

    suspend fun getGuildChannels(
        guildId: String,
        filterInaccessible: Boolean = true
    ): Result<List<CategoryGroup>> = runCatching {
        val raw = Channel.listFromJson(JSONArray(get("/guilds/$guildId/channels")))
        val member = getGuildMember(guildId).getOrNull()
        val guild = runCatching { JSONObject(get("/guilds/$guildId")) }.getOrNull()
        val roles = guild?.optJSONArray("roles")?.let { GuildRole.listFromJson(it) }
        val ownerId = guild?.optString("owner_id")?.takeIf { it.isNotEmpty() }

        fun canView(channel: Channel): Boolean {
            // Role-gated servers deny @everyone and grant access through roles, so never guess "hidden":
            // if we can't work out the user's roles, show the channel and let the server decide on open.
            if (member == null || roles == null) return true
            if (ownerId != null && member.userId == ownerId) return true

            val perms = Permissions.effectiveForMember(
                member = member,
                guildId = guildId,
                everyoneRoleId = guildId,
                roles = roles,
                channel = channel
            )
            return Permissions.has(perms, Permissions.VIEW_CHANNEL)
        }

        val categories = raw.filter { it.isCategory }.sortedBy { it.position }
        // Forum/media channels are listed alongside text channels; opening one shows its posts.
        // Voice channels come after them in each category, like in Discord.
        val textChannels = raw.filter { it.isText || it.isForum || it.isVoice }
        val byParent = textChannels.groupBy { it.parentId }
        val groups = mutableListOf<CategoryGroup>()

        val topLevel = byParent[null].orEmpty()
            .map { it.copy(hasAccess = canView(it)) }
            .filter { !filterInaccessible || it.hasAccess }
            .sortedWith(compareBy({ it.isVoice }, { it.position }))
        if (topLevel.isNotEmpty()) groups.add(CategoryGroup(category = null, channels = topLevel))

        for (cat in categories) {
            val children = byParent[cat.id].orEmpty()
                .map { it.copy(hasAccess = canView(it)) }
                .filter { !filterInaccessible || it.hasAccess }
                .sortedWith(compareBy({ it.isVoice }, { it.position }))
            if (children.isNotEmpty()) groups.add(
                CategoryGroup(
                    category = cat,
                    channels = children
                )
            )
        }

        groups
    }

    suspend fun getDmChannels(): Result<List<Channel>> = runCatching {
        Channel.listFromJson(JSONArray(get("/users/@me/channels"))).filter { it.isDm }
    }

    /** The newest page as raw JSON (newest first), for the offline cache. */
    suspend fun getMessagesRaw(channelId: String, limit: Int): Result<String> = runCatching {
        get("/channels/$channelId/messages?limit=${limit.coerceIn(1, 100)}")
    }

    /** Newest first. With [before], the page of messages older than that message id. */
    suspend fun getMessages(
        channelId: String,
        limit: Int = 50,
        before: String? = null
    ): Result<List<DiscordMessage>> =
        runCatching {
            val safe = limit.coerceIn(1, 100)
            val cursor = if (before != null) "&before=$before" else ""
            DiscordMessage.listFromJson(JSONArray(get("/channels/$channelId/messages?limit=$safe$cursor")))
        }

    /** Opens (or returns the existing) DM channel with [userId]. */
    suspend fun openDm(userId: String): Result<Channel> = runCatching {
        Channel.fromJson(JSONObject(post("/users/@me/channels", JSONObject().put("recipients", JSONArray().put(userId)))))
    }

    /** Rings [recipients] (everyone else when null) in the DM/group call of [channelId]. */
    suspend fun ringCall(channelId: String, recipients: List<String>? = null): Result<Unit> = runCatching {
        post("/channels/$channelId/call/ring", JSONObject().put("recipients", recipients?.let { JSONArray(it) } ?: JSONObject.NULL))
        Unit
    }

    /** Stops ringing [recipients] (e.g. ourselves, to decline). */
    suspend fun stopRinging(channelId: String, recipients: List<String>): Result<Unit> = runCatching {
        post("/channels/$channelId/call/stop-ringing", JSONObject().put("recipients", JSONArray(recipients)))
        Unit
    }

    /** People on the friends list (relationship type 1). */
    suspend fun getFriends(): Result<List<DiscordUser>> = runCatching {
        val arr = JSONArray(get("/users/@me/relationships"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optInt("type") != 1) return@mapNotNull null
            o.optJSONObject("user")?.let { runCatching { DiscordUser.fromJson(it) }.getOrNull() }
        }
    }

    suspend fun sendMessage(channelId: String, content: String): Result<DiscordMessage> =
        runCatching {
            val body = JSONObject().put("content", content)
            DiscordMessage.fromJson(JSONObject(post("/channels/$channelId/messages", body)))
        }


    suspend fun sendReply(
        channelId: String,
        content: String,
        replyToId: String,
        ping: Boolean = true
    ): Result<DiscordMessage> = runCatching {
        val body = JSONObject()
            .put("content", content)
            .put(
                "message_reference", JSONObject()
                    .put("message_id", replyToId)
                    .put("channel_id", channelId)
                    .put("fail_if_not_exists", false)
            )
            .put("allowed_mentions", JSONObject().put("replied_user", ping))
        DiscordMessage.fromJson(JSONObject(post("/channels/$channelId/messages", body)))
    }

    suspend fun deleteMessage(channelId: String, messageId: String): Result<Unit> = runCatching {
        delete("/channels/$channelId/messages/$messageId")
        Unit
    }

    suspend fun editMessage(
        channelId: String,
        messageId: String,
        newContent: String
    ): Result<DiscordMessage> = runCatching {
        val body = JSONObject().put("content", newContent)
        val req = buildRequest("/channels/$channelId/messages/$messageId")
            .method("PATCH", body.toString().toRequestBody(jsonMime))
            .build()
        DiscordMessage.fromJson(JSONObject(execute(req)))
    }


    suspend fun getGuildEmojis(guildId: String): Result<List<GuildEmoji>> = runCatching {
        GuildEmoji.listFromJson(JSONArray(get("/guilds/$guildId/emojis")))
    }

    /** Discord's GIF search (Tenor), as the web client's GIF picker does it. */
    suspend fun searchGifs(query: String): Result<List<Gif>> = runCatching {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        Gifs.parseResults(get("/gifs/search?q=$q&media_format=mp4&provider=tenor&locale=en-US"))
    }

    suspend fun trendingGifs(): Result<List<Gif>> = runCatching {
        Gifs.parseResults(get("/gifs/trending-gifs?media_format=mp4&provider=tenor&locale=en-US"))
    }

    /** The account's favourite GIFs (stored in the frecency settings proto), newest first. */
    suspend fun favoriteGifs(): Result<List<Gif>> = runCatching {
        val encoded = JSONObject(get("/users/@me/settings-proto/2")).optString("settings")
        if (encoded.isEmpty()) emptyList()
        else Gifs.parseFavorites(java.util.Base64.getDecoder().decode(encoded))
    }

    suspend fun getGuildStickers(guildId: String): Result<List<StickerItem>> = runCatching {
        val arr = JSONArray(get("/guilds/$guildId/stickers"))
        (0 until arr.length()).map { StickerItem.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun sendSticker(channelId: String, stickerId: String): Result<DiscordMessage> =
        runCatching {
            val body = JSONObject().put("sticker_ids", org.json.JSONArray().put(stickerId))
            DiscordMessage.fromJson(JSONObject(post("/channels/$channelId/messages", body)))
        }

    suspend fun sendFileAttachment(
        channelId: String,
        fileBytes: ByteArray,
        filename: String,
        mimeType: String,
        caption: String = "",
        replyToId: String? = null,
        ping: Boolean = true
    ): Result<DiscordMessage> = runCatching {
        withContext(Dispatchers.IO) {
            val payloadJson = JSONObject()
                .put("content", caption)
                .put(
                    "attachments", org.json.JSONArray().put(
                        JSONObject().put("id", 0).put("filename", filename)
                    )
                )
                .apply {
                    if (replyToId != null) {
                        put(
                            "message_reference", JSONObject()
                                .put("message_id", replyToId)
                                .put("channel_id", channelId)
                                .put("fail_if_not_exists", false)
                        )
                        put("allowed_mentions", JSONObject().put("replied_user", ping))
                    }
                }
                .toString()

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "payload_json", null,
                    payloadJson.toRequestBody("application/json".toMediaType())
                )
                .addFormDataPart(
                    "files[0]", filename,
                    fileBytes.toRequestBody(mimeType.toMediaType())
                )
                .build()

            val request = buildRequest("/channels/$channelId/messages").post(multipart).build()
            val responseText = execute(request)
            DiscordMessage.fromJson(JSONObject(responseText))
        }
    }

    suspend fun sendVoiceMessage(
        channelId: String,
        audioBytes: ByteArray,
        durationSecs: Double,
        waveform: String = ""
    ): Result<DiscordMessage> = runCatching {
        withContext(Dispatchers.IO) {
            val payloadJson = JSONObject()
                .put("flags", 8192)
                .put(
                    "attachments", org.json.JSONArray().put(
                        JSONObject()
                            .put("id", 0)
                            .put("filename", "voice-message.ogg")
                            .put("duration_secs", durationSecs)
                            .put("waveform", waveform)
                    )
                )
                .toString()

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "payload_json", null,
                    payloadJson.toRequestBody("application/json".toMediaType())
                )
                .addFormDataPart(
                    "files[0]", "voice-message.ogg",
                    audioBytes.toRequestBody("audio/ogg".toMediaType())
                )
                .build()

            val request = buildRequest("/channels/$channelId/messages").post(multipart).build()
            val responseText = execute(request)
            DiscordMessage.fromJson(JSONObject(responseText))
        }
    }

    suspend fun addReaction(
        channelId: String,
        messageId: String,
        emojiKey: String,
        burst: Boolean = false
    ): Result<Unit> = runCatching {
        val encoded = java.net.URLEncoder.encode(emojiKey, "UTF-8")
        val typeParam = if (burst) "?type=1" else ""
        execute(
            buildRequest("/channels/$channelId/messages/$messageId/reactions/$encoded/@me$typeParam")
                .put("".toRequestBody(jsonMime)).build()
        )
        Unit
    }

    suspend fun removeReaction(
        channelId: String,
        messageId: String,
        emojiKey: String,
        burst: Boolean = false
    ): Result<Unit> = runCatching {
        val encoded = java.net.URLEncoder.encode(emojiKey, "UTF-8")
        val typeParam = if (burst) "?type=1" else ""
        execute(
            buildRequest("/channels/$channelId/messages/$messageId/reactions/$encoded/@me$typeParam")
                .delete().build()
        )
        Unit
    }

    /** Sets the user's poll answers ([answerIds] empty removes their vote). */
    suspend fun votePoll(channelId: String, messageId: String, answerIds: List<Int>): Result<Unit> = runCatching {
        val body = JSONObject().put("answer_ids", JSONArray(answerIds.map { it.toString() }))
        execute(
            buildRequest("/channels/$channelId/polls/$messageId/answers/@me")
                .put(body.toString().toRequestBody(jsonMime)).build()
        )
        Unit
    }

    /** Who reacted with [emojiKey] ([burst] = super reactions), up to [limit] people. */
    suspend fun getReactionUsers(
        channelId: String,
        messageId: String,
        emojiKey: String,
        burst: Boolean = false,
        limit: Int = 50
    ): Result<List<DiscordUser>> = runCatching {
        val encoded = java.net.URLEncoder.encode(emojiKey, "UTF-8")
        val arr = JSONArray(get("/channels/$channelId/messages/$messageId/reactions/$encoded?limit=$limit&type=${if (burst) 1 else 0}"))
        (0 until arr.length()).map { DiscordUser.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun ackChannel(channelId: String, messageId: String) {
        runCatching {
            post(
                "/channels/$channelId/messages/$messageId/ack",
                JSONObject().put("token", null as String?)
            )
        }
    }

    suspend fun logout(): Result<Unit> = runCatching {
        post("/auth/logout", JSONObject())
        Unit
    }

    suspend fun getUserProfile(userId: String): Result<DiscordUser> = runCatching {
        val json = JSONObject(get("/users/$userId/profile"))
        val userObj = json.optJSONObject("user") ?: json
        DiscordUser.fromJson(userObj, json)
    }


}

/** Discord wanted a captcha and it wasn't solved (cancelled, timed out, or the app wasn't on screen). */
class CaptchaRequiredException : DiscordApiException(
    400,
    "Discord wants a captcha. Do this once in the Discord app on your phone, then try again."
)

open class DiscordApiException(val httpCode: Int, message: String) :
    IOException("Discord API error $httpCode: $message")
