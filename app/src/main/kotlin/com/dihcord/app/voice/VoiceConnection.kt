package com.dihcord.app.voice

import android.util.Log
import com.dihcord.app.api.ClientIdentity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.fixedRateTimer
import kotlin.random.Random

/**
 * One connection to a Discord voice server (voice gateway v8): identify, UDP discovery, transport encryption and
 * DAVE end-to-end encryption, then Opus frames in both directions. Modelled on discord.js's voice Networking.
 *
 * Callbacks run on network threads.
 */
class VoiceConnection(
    private val endpoint: String,
    private val serverId: String,
    private val channelId: String,
    private val userId: String,
    private val sessionId: String,
    private val token: String,
    private val network: android.net.Network?,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        /** The connection ended for good; [code] is the voice gateway close code (or -1 for a local failure). */
        fun onClosed(code: Int, reason: String)
        /** A decrypted Opus frame from [userId]. */
        fun onAudio(userId: Long, opus: ByteArray)
        /** Someone's speaking flag changed (from the server's SPEAKING events). */
        fun onSpeaking(userId: Long, speaking: Boolean) {}
        fun onPrivacyCode(code: String) {}
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .apply { network?.let { socketFactory(it.socketFactory) } }
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var udp: VoiceUdp? = null
    @Volatile private var crypto: RtpCrypto? = null
    @Volatile private var dave: DaveSession? = null
    @Volatile private var ssrc = 0L
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var lastSeq = -1
    private var heartbeat: Timer? = null
    private var resumeAttempts = 0

    /** ssrc -> user id, learned from SPEAKING events. */
    private val users = ConcurrentHashMap<Long, Long>()
    private val connectedClients: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // Outgoing RTP state; only the capture thread touches these.
    private var sequence = Random.nextInt(0, 0x10000)
    private var timestamp = Random.nextLong(0, 0x100000000L)
    @Volatile private var speaking = false

    fun connect() {
        open(resume = false)
    }

    private fun open(resume: Boolean) {
        val request = Request.Builder()
            .url("wss://${endpoint.removePrefix("wss://").trimEnd('/')}/?v=8")
            .header("User-Agent", ClientIdentity.USER_AGENT)
            .build()
        ws = http.newWebSocket(request, SocketListener(resume))
    }

    fun close() {
        if (closed) return
        closed = true
        ready = false
        heartbeat?.cancel()
        ws?.close(1000, "Bye")
        ws = null
        udp?.close()
        udp = null
        dave?.destroy()
        dave = null
    }

    val isReady: Boolean get() = ready

    /** Sends one 20 ms Opus frame. */
    fun sendOpus(frame: ByteArray) {
        val c = crypto ?: return
        val u = udp ?: return
        if (!ready) return
        setSpeaking(true)
        val payload = dave?.encrypt(frame) ?: frame
        val header = RtpCrypto.header(sequence, timestamp, ssrc)
        sequence = (sequence + 1) and 0xFFFF
        timestamp = (timestamp + FRAME_SAMPLES) and 0xFFFFFFFFL
        u.send(c.encrypt(header, payload))
    }

    /** Tells the server we started/stopped transmitting (others see the speaking ring). */
    fun setSpeaking(value: Boolean) {
        if (speaking == value || !ready) return
        speaking = value
        sendJson(OP_SPEAKING, JSONObject().put("speaking", if (value) 1 else 0).put("delay", 0).put("ssrc", ssrc))
    }

    // -------------------------------------------------------------------------------------------------------------

    private inner class SocketListener(private val resume: Boolean) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) return
            if (resume) {
                sendJson(
                    OP_RESUME, JSONObject()
                        .put("server_id", serverId)
                        .put("session_id", sessionId)
                        .put("token", token)
                        .put("seq_ack", lastSeq)
                )
            } else {
                sendJson(
                    OP_IDENTIFY, JSONObject()
                        .put("server_id", serverId)
                        .put("user_id", userId)
                        .put("session_id", sessionId)
                        .put("token", token)
                        .put("video", false)
                        .put("max_dave_protocol_version", NativeVoice.daveMaxProtocolVersion())
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            runCatching { handleJson(JSONObject(text)) }.onFailure { Log.w(TAG, "bad voice message", it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket !== ws) return
            runCatching { handleBinary(bytes.toByteArray()) }.onFailure { Log.w(TAG, "bad binary message", it) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === ws) socketEnded(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket === ws) socketEnded(-1, t.message ?: "connection failed")
        }
    }

    private fun socketEnded(code: Int, reason: String) {
        heartbeat?.cancel()
        heartbeat = null
        ws = null
        if (closed) return
        // Same rule as discord.js: normal drops and 4015 (server crashed) can resume; other 4xxx codes are final.
        val canResume = (code == 4015 || code < 4000) && ready && resumeAttempts < 5
        if (canResume) {
            resumeAttempts++
            Log.i(TAG, "voice socket closed ($code $reason), resuming")
            Thread.sleep(500L * resumeAttempts)
            if (!closed) open(resume = true)
        } else {
            close()
            listener.onClosed(code, reason)
        }
    }

    private fun sendJson(op: Int, d: Any) {
        ws?.send(JSONObject().put("op", op).put("d", d).toString())
    }

    private fun sendBinary(op: Int, payload: ByteArray) {
        val message = ByteArray(payload.size + 1)
        message[0] = op.toByte()
        payload.copyInto(message, 1)
        ws?.send(message.toByteString())
    }

    private fun handleJson(packet: JSONObject) {
        if (packet.has("seq")) lastSeq = packet.optInt("seq", lastSeq)
        val op = packet.optInt("op", -1)
        val d = packet.optJSONObject("d")
        when (op) {
            OP_HELLO -> startHeartbeat(d?.optDouble("heartbeat_interval", 13_750.0)?.toLong() ?: 13_750L)
            OP_READY -> d?.let { onVoiceReady(it) }
            OP_SESSION_DESCRIPTION -> d?.let { onSessionDescription(it) }
            OP_RESUMED -> {
                resumeAttempts = 0
                speaking = false
            }
            OP_SPEAKING -> d?.let {
                val uid = it.optString("user_id").toLongOrNull() ?: return@let
                val ssrc = it.optLong("ssrc", -1)
                if (ssrc >= 0) users[ssrc] = uid
                listener.onSpeaking(uid, it.optInt("speaking", 0) != 0)
            }
            OP_CLIENTS_CONNECT -> d?.optJSONArray("user_ids")?.let { ids ->
                for (i in 0 until ids.length()) ids.optString(i).toLongOrNull()?.let(connectedClients::add)
            }
            OP_CLIENT_DISCONNECT -> d?.optString("user_id")?.toLongOrNull()?.let { uid ->
                connectedClients.remove(uid)
                users.entries.removeAll { it.value == uid }
            }
            OP_DAVE_PREPARE_TRANSITION -> d?.let {
                val id = it.optInt("transition_id")
                val sendReady = dave?.prepareTransition(id, it.optInt("protocol_version")) ?: false
                if (sendReady) sendJson(OP_DAVE_TRANSITION_READY, JSONObject().put("transition_id", id))
                if (id == 0) publishPrivacyCode()
            }
            OP_DAVE_EXECUTE_TRANSITION -> d?.let {
                if (dave?.executeTransition(it.optInt("transition_id")) == true) publishPrivacyCode()
            }
            OP_DAVE_PREPARE_EPOCH -> d?.let { dave?.prepareEpoch(it.optLong("epoch"), it.optInt("protocol_version")) }
        }
    }

    private fun handleBinary(message: ByteArray) {
        if (message.size < 3) return
        lastSeq = ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
        val op = message[2].toInt() and 0xFF
        val payload = message.copyOfRange(3, message.size)
        val session = dave ?: return
        when (op) {
            OP_MLS_EXTERNAL_SENDER -> session.setExternalSender(payload)
            OP_MLS_PROPOSALS -> session.processProposals(payload, connectedClients)?.let { sendBinary(OP_MLS_COMMIT_WELCOME, it) }
            OP_MLS_ANNOUNCE_COMMIT -> session.processCommit(payload)?.let { transitionReady(it) }
            OP_MLS_WELCOME -> session.processWelcome(payload)?.let { transitionReady(it) }
        }
    }

    private fun transitionReady(transitionId: Int) {
        if (transitionId == 0) publishPrivacyCode()
        else sendJson(OP_DAVE_TRANSITION_READY, JSONObject().put("transition_id", transitionId))
    }

    private fun publishPrivacyCode() {
        dave?.privacyCode()?.takeIf { it.isNotEmpty() }?.let(listener::onPrivacyCode)
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeat?.cancel()
        heartbeat = fixedRateTimer("voice-heartbeat", daemon = true, initialDelay = intervalMs, period = intervalMs) {
            sendJson(OP_HEARTBEAT, JSONObject().put("t", System.currentTimeMillis()).put("seq_ack", lastSeq))
        }
    }

    private fun onVoiceReady(d: JSONObject) {
        ssrc = d.optLong("ssrc")
        val modes = d.optJSONArray("modes") ?: JSONArray()
        val supported = (0 until modes.length()).any { modes.optString(it) == RtpCrypto.MODE }
        if (!supported) {
            fail("The voice server didn't offer an encryption mode this app supports.")
            return
        }
        val ip = d.optString("ip")
        val port = d.optInt("port")
        Thread({
            try {
                val socket = VoiceUdp(ip, port, network) { packet -> onUdpPacket(packet) }
                udp = socket
                val (myIp, myPort) = socket.discover(ssrc)
                sendJson(
                    OP_SELECT_PROTOCOL, JSONObject()
                        .put("protocol", "udp")
                        .put("data", JSONObject().put("address", myIp).put("port", myPort).put("mode", RtpCrypto.MODE))
                )
            } catch (e: Exception) {
                fail(e.message ?: "UDP connection failed")
            }
        }, "voice-udp-setup").start()
    }

    private fun onSessionDescription(d: JSONObject) {
        val keyArr = d.optJSONArray("secret_key") ?: return fail("No encryption key from the voice server.")
        val key = ByteArray(keyArr.length()) { keyArr.optInt(it).toByte() }
        crypto = RtpCrypto(key)
        val daveVersion = d.optInt("dave_protocol_version", 0)
        dave?.destroy()
        dave = DaveSession(
            protocolVersion = daveVersion,
            userId = userId.toLong(),
            channelId = channelId.toLong(),
            sendKeyPackage = { sendBinary(OP_MLS_KEY_PACKAGE, it) },
            sendInvalidCommitWelcome = { id -> sendJson(OP_MLS_INVALID_COMMIT_WELCOME, JSONObject().put("transition_id", id)) },
        ).also { it.reinit() }
        udp?.start()
        ready = true
        resumeAttempts = 0
        listener.onReady()
    }

    private fun onUdpPacket(packet: ByteArray) {
        if ((packet[1].toInt() and 0x7F) != RtpCrypto.OPUS_PAYLOAD_TYPE) return
        if ((packet[0].toInt() and 0xFF) shr 6 != 2) return
        val uid = users[RtpCrypto.readU32(packet, 8)] ?: return
        val media = crypto?.decrypt(packet) ?: return
        val opus = dave?.decrypt(media, uid) ?: return
        listener.onAudio(uid, opus)
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        close()
        listener.onClosed(-1, message)
    }

    companion object {
        private const val TAG = "VoiceConnection"
        const val FRAME_SAMPLES = 960 // 20 ms at 48 kHz

        private const val OP_IDENTIFY = 0
        private const val OP_SELECT_PROTOCOL = 1
        private const val OP_READY = 2
        private const val OP_HEARTBEAT = 3
        private const val OP_SESSION_DESCRIPTION = 4
        private const val OP_SPEAKING = 5
        private const val OP_RESUME = 7
        private const val OP_HELLO = 8
        private const val OP_RESUMED = 9
        private const val OP_CLIENTS_CONNECT = 11
        private const val OP_CLIENT_DISCONNECT = 13
        private const val OP_DAVE_PREPARE_TRANSITION = 21
        private const val OP_DAVE_EXECUTE_TRANSITION = 22
        private const val OP_DAVE_TRANSITION_READY = 23
        private const val OP_DAVE_PREPARE_EPOCH = 24
        private const val OP_MLS_EXTERNAL_SENDER = 25
        private const val OP_MLS_KEY_PACKAGE = 26
        private const val OP_MLS_PROPOSALS = 27
        private const val OP_MLS_COMMIT_WELCOME = 28
        private const val OP_MLS_ANNOUNCE_COMMIT = 29
        private const val OP_MLS_WELCOME = 30
        private const val OP_MLS_INVALID_COMMIT_WELCOME = 31

        /** Human-readable reasons for the voice gateway's close codes. */
        fun describeClose(code: Int, reason: String): String = when (code) {
            4004 -> "Voice login was rejected."
            4006 -> "The voice session expired."
            4009 -> "The voice session timed out."
            4011 -> "The voice server couldn't be found."
            4014 -> "Disconnected (moved, kicked or the channel was deleted)."
            4016 -> "Unsupported encryption."
            4017 -> "This voice server requires end-to-end encryption."
            4021, 4022 -> "Disconnected by Discord."
            -1 -> reason
            else -> "Voice disconnected ($code${if (reason.isNotBlank()) ": $reason" else ""})."
        }
    }
}
