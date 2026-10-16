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

class DiscordRestClient(private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    private val baseUrl = "https://discord.com/api/v9"
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
        val textChannels = raw.filter { it.isText || it.isForum }
        val byParent = textChannels.groupBy { it.parentId }
        val groups = mutableListOf<CategoryGroup>()

        val topLevel = byParent[null].orEmpty()
            .map { it.copy(hasAccess = canView(it)) }
            .filter { !filterInaccessible || it.hasAccess }
            .sortedBy { it.position }
        if (topLevel.isNotEmpty()) groups.add(CategoryGroup(category = null, channels = topLevel))

        for (cat in categories) {
            val children = byParent[cat.id].orEmpty()
                .map { it.copy(hasAccess = canView(it)) }
                .filter { !filterInaccessible || it.hasAccess }
                .sortedBy { it.position }
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
