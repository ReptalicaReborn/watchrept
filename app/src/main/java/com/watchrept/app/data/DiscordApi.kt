package com.watchrept.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

val DiscordJson = Json { ignoreUnknownKeys = true }

/** Minimal Discord REST client. Authenticates as a bot (user tokens are against Discord's ToS). */
class DiscordApi(private val token: String, private val http: OkHttpClient) {

    private suspend fun <T> call(request: Request.Builder, parse: (String) -> T): T =
        withContext(Dispatchers.IO) {
            val req = request.header("Authorization", "Bot $token").build()
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IOException("Discord ${res.code}: ${body.take(120)}")
                parse(body)
            }
        }

    private fun url(path: String) = "$BASE/$path"

    suspend fun guilds(): List<Guild> =
        call(Request.Builder().url(url("users/@me/guilds"))) {
            DiscordJson.decodeFromString(ListSerializer(Guild.serializer()), it)
        }

    suspend fun textChannels(guildId: String): List<Channel> =
        call(Request.Builder().url(url("guilds/$guildId/channels"))) {
            DiscordJson.decodeFromString(ListSerializer(Channel.serializer()), it)
        }.filter { it.type == CHANNEL_TYPE_TEXT }.sortedBy { it.position }

    /** Oldest first. */
    suspend fun messages(channelId: String, limit: Int = 30): List<Message> =
        call(Request.Builder().url(url("channels/$channelId/messages?limit=$limit"))) {
            DiscordJson.decodeFromString(ListSerializer(Message.serializer()), it)
        }.reversed()

    suspend fun send(channelId: String, content: String) {
        val json = """{"content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), content)}}"""
        call(
            Request.Builder().url(url("channels/$channelId/messages"))
                .post(json.toRequestBody("application/json".toMediaType())),
        ) { }
    }

    companion object {
        const val BASE = "https://discord.com/api/v10"
    }
}
