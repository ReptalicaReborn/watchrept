package com.watchrept.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Minimal Discord Gateway connection that surfaces MESSAGE_CREATE events. No resume support: reconnects fresh. */
class Gateway(
    private val token: String,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    val messages: SharedFlow<Message> = _messages

    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var sequence: Long? = null
    private var stopped = false

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        heartbeat?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    private fun connect() {
        val req = Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnect()
        })
    }

    private fun reconnect() {
        heartbeat?.cancel()
        if (stopped) return
        scope.launch {
            delay(5_000)
            if (!stopped) connect()
        }
    }

    private fun handle(ws: WebSocket, text: String) {
        val payload = runCatching { DiscordJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        payload["s"]?.jsonPrimitive?.longOrNull?.let { sequence = it }
        when (payload["op"]?.jsonPrimitive?.int) {
            OP_HELLO -> {
                val interval = payload["d"]!!.jsonObject["heartbeat_interval"]!!.jsonPrimitive.content.toLong()
                startHeartbeat(ws, interval)
                ws.send(identify())
            }
            OP_HEARTBEAT -> ws.send(heartbeatPayload())
            OP_RECONNECT, OP_INVALID_SESSION -> ws.close(4000, "reconnect")
            OP_DISPATCH -> if (payload["t"]?.jsonPrimitive?.content == "MESSAGE_CREATE") {
                val data = payload["d"] as? JsonObject ?: return
                runCatching { DiscordJson.decodeFromJsonElement(Message.serializer(), data) }
                    .getOrNull()?.let { _messages.tryEmit(it) }
            }
        }
    }

    private fun startHeartbeat(ws: WebSocket, intervalMs: Long) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(intervalMs)
                ws.send(heartbeatPayload())
            }
        }
    }

    private fun heartbeatPayload() = """{"op":1,"d":${sequence ?: "null"}}"""

    private fun identify() = """{"op":2,"d":{"token":"$token","intents":$INTENTS,""" +
        """"properties":{"os":"wearos","browser":"watchrept","device":"watchrept"}}}"""

    private companion object {
        const val OP_DISPATCH = 0
        const val OP_HEARTBEAT = 1
        const val OP_RECONNECT = 7
        const val OP_INVALID_SESSION = 9
        const val OP_HELLO = 10

        // GUILDS (1<<0) | GUILD_MESSAGES (1<<9) | MESSAGE_CONTENT (1<<15, privileged: enable in the dev portal)
        const val INTENTS = (1 shl 0) or (1 shl 9) or (1 shl 15)
    }
}
