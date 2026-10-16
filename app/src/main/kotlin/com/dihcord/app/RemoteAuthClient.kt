package com.dihcord.app

import com.dihcord.app.captcha.CaptchaBroker
import com.dihcord.app.captcha.CaptchaChallenge
import com.dihcord.app.api.ClientIdentity
import com.dihcord.app.api.ClientIdentity.discordHeaders
import com.dihcord.app.api.MemoryCookieJar
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

private const val MAX_CAPTCHA_TRIES = 3

sealed class RemoteAuthState {
    object Connecting : RemoteAuthState()
    data class WaitingForScan(val fingerprint: String) : RemoteAuthState()
    data class UserScanned(val userId: String, val username: String, val avatarHash: String) :
        RemoteAuthState()

    /** Discord wanted a captcha that wasn't solved or wasn't accepted; [detail] says which. */
    data class CaptchaRequired(val detail: String) : RemoteAuthState()
    object Canceled : RemoteAuthState()
    data class Error(val message: String) : RemoteAuthState()
}

data class RemoteAuthStatus(val lines: List<String> = emptyList()) {
    fun plus(line: String) = RemoteAuthStatus(lines + line)
}

class RemoteAuthClient(
    private val onStateChange: (RemoteAuthState) -> Unit,
    private val onStatusUpdate: (RemoteAuthStatus) -> Unit,
    private val onTokenReceived: suspend (String) -> Unit
) {
    private val TAG = "RemoteAuthClient"

    // UI callbacks are posted to the main looper rather than launched in a scope, so the final state and
    // log lines are delivered even after the socket has been torn down. Only disconnect() silences them.
    private val mainHandler = Handler(Looper.getMainLooper())

    // Socket work (heartbeat, expiry timer); cancelled when the socket ends.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The ticket exchange (and any captcha wait) must outlive the socket, which Discord closes right
    // after pending_login. Only disconnect() cancels it.
    private val exchangeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var expiryJob: Job? = null

    /** disconnect() was called: drop every further callback. */
    @Volatile private var disposed = false

    /** pending_login arrived (ticket exchange running or done) or the phone cancelled: socket errors no longer matter. */
    @Volatile private var loginStarted = false
    @Volatile private var finished = false
    private val socketEnded = AtomicBoolean(false)

    private var privateKey: RSAPrivateKey? = null
    private var publicKeySpki: ByteArray? = null  // Java encoded() = SPKI DER

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .cookieJar(MemoryCookieJar())
        .build()

    /** Discord's browser fingerprint for this login, fetched (with its cookies) before the ticket is exchanged. */
    @Volatile private var fingerprint: String? = null

    /**
     * Gets the fingerprint and cookies Discord's login page would have, so the ticket exchange looks like a normal
     * browser login; without them Discord is much more likely to ask for a captcha (and then cancel the ticket).
     */
    private fun prepareBrowserSession() {
        if (fingerprint != null) return
        runCatching {
            val request = Request.Builder()
                .url("https://discord.com/api/v9/experiments")
                .discordHeaders()
                .header("Referer", "https://discord.com/login")
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                fingerprint = runCatching { JSONObject(body).optString("fingerprint") }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }
        }.onFailure { log("Couldn't prepare the login session: ${it.message}") }
    }

    private val oaepSpec = OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
    )

    private var status = RemoteAuthStatus()

    private fun setState(s: RemoteAuthState) {
        if (disposed) return
        mainHandler.post { if (!disposed) onStateChange(s) }
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        if (disposed) return
        val snapshot = synchronized(this) {
            status = status.plus(line)
            status
        }
        mainHandler.post { if (!disposed) onStatusUpdate(snapshot) }
    }

    fun connect() {
        setState(RemoteAuthState.Connecting)
        // Warm up the browser-like session while the user scans, so the ticket exchange doesn't wait for it.
        ioScope.launch { prepareBrowserSession() }
        log("Opening WebSocket…")
        val request = Request.Builder()
            .url("wss://remote-auth-gateway.discord.gg/?v=2")
            .header("Origin", "https://discord.com")
            .header("User-Agent", ClientIdentity.USER_AGENT)
            .build()

        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("Connected (HTTP ${response.code})")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Handled on OkHttp's reader thread so messages are processed in order and the
                // pending_login flag is set before a following close is seen.
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle message", e)
                    log("Error handling message: ${e.message}")
                    if (!loginStarted && !finished) {
                        finished = true
                        setState(RemoteAuthState.Error("Login failed: ${e.message}"))
                    }
                    runCatching { webSocket.close(1000, null) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("Failed (HTTP ${response?.code}): ${t.message}")
                onSocketEnded { RemoteAuthState.Error("Connection failed: ${t.message}") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("Closing: $code ${reason.ifBlank { "(no reason)" }}")
                runCatching { webSocket.close(1000, null) }
                onSocketEnded { closedState(code) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("Closed: $code ${reason.ifBlank { "(no reason)" }}")
                onSocketEnded { closedState(code) }
            }
        })
    }

    private fun closedState(code: Int): RemoteAuthState =
        RemoteAuthState.Error("The QR code expired or the connection was closed ($code). Try again for a new one.")

    /** Runs once per socket: stops socket work and reports [state] unless the login already moved on. */
    private fun onSocketEnded(state: () -> RemoteAuthState) {
        if (!socketEnded.compareAndSet(false, true)) return
        if (!loginStarted && !finished) {
            finished = true
            setState(state())
        }
        cleanupSocket()
    }

    private fun handleMessage(text: String) {
        val json = JSONObject(text)
        when (val op = json.getString("op")) {
            "hello" -> {
                val intervalMs = json.getLong("heartbeat_interval")
                log("← hello (heartbeat ${intervalMs}ms)")
                log("Generating RSA-2048 keypair…")
                generateKeyPair()
                val b64 = Base64.encodeToString(publicKeySpki, Base64.NO_WRAP)
                log("Pubkey: ${b64.take(20)}… (${publicKeySpki!!.size}B SPKI)")
                send(JSONObject().put("op", "init").put("encoded_public_key", b64))
                startHeartbeat(intervalMs)
                val timeoutMs = json.optLong("timeout_ms", 0L)
                if (timeoutMs > 0) startExpiryTimer(timeoutMs)
                log("→ init sent, waiting for nonce_proof…")
            }

            "nonce_proof" -> {
                log("← nonce_proof, decrypting…")
                val encryptedNonce = json.getString("encrypted_nonce")
                val decryptedNonce = decryptBytes(Base64.decode(encryptedNonce, Base64.DEFAULT))
                val proof = Base64.encodeToString(
                    decryptedNonce,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                log("Nonce decrypted (${decryptedNonce.size}B), proof=${proof.take(16)}…")
                send(JSONObject().put("op", "nonce_proof").put("nonce", proof))
                log("→ nonce_proof sent, waiting for pending_remote_init…")
            }

            "pending_remote_init" -> {
                val fingerprint = json.getString("fingerprint")
                log("← pending_remote_init!")
                val spki = publicKeySpki!!
                val digest = MessageDigest.getInstance("SHA-256").digest(spki)
                val computed = Base64.encodeToString(
                    digest,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                log("Fingerprint match: ${fingerprint == computed}")
                if (fingerprint != computed) {
                    log("Expected: $fingerprint / Got: $computed")
                }
                setState(RemoteAuthState.WaitingForScan(fingerprint))
            }

            "pending_ticket" -> {
                log("← pending_ticket, decrypting user…")
                val payload = decryptBytes(
                    Base64.decode(
                        json.getString("encrypted_user_payload"),
                        Base64.DEFAULT
                    )
                )
                    .toString(Charsets.UTF_8)
                val parts = payload.split(":")
                if (parts.size >= 4) {
                    log("User: ${parts[3]}")
                    setState(
                        RemoteAuthState.UserScanned(
                            userId = parts[0],
                            avatarHash = parts[2],
                            username = parts[3]
                        )
                    )
                }
            }

            "pending_login" -> {
                loginStarted = true
                expiryJob?.cancel()
                log("← pending_login, exchanging ticket…")
                val ticket = json.getString("ticket")
                exchangeScope.launch { exchangeTicketForToken(ticket) }
            }

            "cancel" -> {
                finished = true
                log("← cancel"); setState(RemoteAuthState.Canceled)
            }

            "heartbeat_ack" -> log("← heartbeat_ack")
            else -> log("← unknown op: $op")
        }
    }

    private fun generateKeyPair() {
        val kp = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048) }.generateKeyPair()
        privateKey = kp.private as RSAPrivateKey
        publicKeySpki = kp.public.encoded   // Java always encodes RSA public keys as SPKI DER
    }

    private fun decryptBytes(cipherBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec)
        return cipher.doFinal(cipherBytes)
    }

    private suspend fun exchangeTicketForToken(ticket: String) {
        try {
            var challenge: CaptchaChallenge? = null
            var key: String? = null
            var tries = 0
            withContext(Dispatchers.IO) { prepareBrowserSession() }
            while (true) {
                val (code, responseBody) = postTicket(ticket, challenge, key)
                log("Ticket exchange HTTP $code")
                val next = if (code == 400) CaptchaChallenge.parse(responseBody) else null
                if (next == null) {
                    if (code == 404 && responseBody.contains("20042")) {
                        // Discord withdrew the login ticket. After a captcha it does that on purpose (the captcha
                        // flagged the attempt), so a fresh QR code is the only way forward.
                        finished = true
                        log("Discord cancelled the login ticket.")
                        setState(
                            if (key != null) RemoteAuthState.CaptchaRequired(
                                "Discord cancelled this login after the captcha."
                            ) else RemoteAuthState.Error("This login expired before it finished. Scan a new code.")
                        )
                        return
                    }
                    if (code !in 200..299) throw Exception("HTTP $code: $responseBody")
                    val encryptedToken = JSONObject(responseBody).getString("encrypted_token")
                    val token = decryptBytes(Base64.decode(encryptedToken, Base64.DEFAULT))
                        .toString(Charsets.UTF_8)
                    log("Token decrypted! Logging in…")
                    finished = true
                    if (!disposed) withContext(Dispatchers.Main) { onTokenReceived(token) }
                    return
                }
                if (key != null) log("Discord didn't accept the captcha (${next.errors.joinToString().ifEmpty { "no reason" }})")
                if (++tries > MAX_CAPTCHA_TRIES) {
                    finished = true
                    setState(RemoteAuthState.CaptchaRequired(
                        "Discord kept rejecting the captcha" +
                            next.errors.firstOrNull()?.let { " ($it)" }.orEmpty() + "."
                    ))
                    return
                }
                log("Captcha required, waiting for it to be solved on the phone…")
                key = CaptchaBroker.solve(next)
                if (key == null) {
                    log("Captcha was not solved.")
                    finished = true
                    setState(RemoteAuthState.CaptchaRequired("The captcha was cancelled or timed out."))
                    return
                }
                challenge = next
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Token exchange error: ${e.message}")
            finished = true
            setState(RemoteAuthState.Error("Token exchange failed: ${e.message}"))
        }
    }

    /** Posts the ticket, with the solved captcha [key] for [challenge] when retrying. Returns code and body. */
    private suspend fun postTicket(ticket: String, challenge: CaptchaChallenge?, key: String?): Pair<Int, String> {
        val jsonBody = JSONObject().put("ticket", ticket)
        if (challenge != null && key != null) {
            jsonBody.put("captcha_key", key)
            challenge.rqtoken?.let { jsonBody.put("captcha_rqtoken", it) }
        }
        val request = Request.Builder()
            .url("https://discord.com/api/v9/users/@me/remote-auth/login")
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json")
            .discordHeaders()
            .header("Referer", "https://discord.com/login")
            .apply { fingerprint?.let { header("X-Fingerprint", it) } }
            .apply { if (challenge != null && key != null) challenge.answerHeaders(key).forEach { (k, v) -> header(k, v) } }
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = ioScope.launch {
            while (isActive) {
                delay(intervalMs)
                send(JSONObject().put("op", "heartbeat"))
                log("→ heartbeat")
            }
        }
    }

    /** Discord drops the session after hello's timeout_ms; don't rely on its close frame alone. */
    private fun startExpiryTimer(timeoutMs: Long) {
        expiryJob?.cancel()
        expiryJob = ioScope.launch {
            delay(timeoutMs + 5_000)
            if (!loginStarted && !finished) {
                log("QR code timed out")
                onSocketEnded { RemoteAuthState.Error("The QR code expired. Try again for a new one.") }
                runCatching { ws?.close(1000, "timeout") }
            }
        }
    }

    private fun send(json: JSONObject) {
        ws?.send(json.toString())
    }

    fun disconnect() {
        disposed = true
        runCatching { ws?.close(1000, "user cancelled") }
        cleanupSocket()
        exchangeScope.cancel()
    }

    /** Stops socket-bound work. Leaves the ticket exchange and UI delivery alone. */
    private fun cleanupSocket() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        expiryJob = null
        ioScope.cancel()
    }
}
