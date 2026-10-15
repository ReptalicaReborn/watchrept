package com.dihcord.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Tiny HTTP server that lets the user paste their token from another device on the same Wi-Fi.
 *
 * Hardening over a bare socket server: every submission must carry a random PIN that is shown only on
 * the watch, requests are size- and time-limited, the PIN locks after a few wrong guesses, and the
 * server accepts exactly one token. Traffic is still plain HTTP, so only use it on a network you trust.
 */
class TokenWebServer(
    private val port: Int = 8080,
    val pin: String = generatePin(),
    /** Called (on a background thread) when the port can't be bound, so the UI can show it. */
    private val onStartFailed: (Throwable) -> Unit = {},
    private val onTokenReceived: suspend (String) -> Unit,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running = false
    @Volatile private var failedAttempts = 0
    @Volatile private var done = false

    /** The port actually bound (useful when constructed with port 0), or -1 before binding. */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun getLocalAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || !iface.name.startsWith("wlan")) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) {
                        addresses.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting addresses", e)
        }
        return addresses
    }

    fun start() {
        if (running) return
        running = true
        scope.launch {
            try {
                // reuseAddress lets the server restart right away even if old connections linger in TIME_WAIT.
                try {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 5)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Couldn't bind port $port", e)
                    val wasRunning = running
                    running = false
                    if (wasRunning) onStartFailed(e)
                    return@launch
                }
                Log.i(TAG, "Listening on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        var submitted: String? = null
        try {
            socket.use {
                it.soTimeout = READ_TIMEOUT_MS
                val input = it.getInputStream()
                val headBytes = MiniHttp.readHead(input, MAX_HEAD) ?: return MiniHttp.respond(it, 400, "Bad request")
                val lines = headBytes.toString(Charsets.ISO_8859_1).split("\r\n")
                val isPost = lines.firstOrNull()?.startsWith("POST ") == true
                if (!isPost) return MiniHttp.respond(it, 200, buildFormPage())

                val length = lines.firstOrNull { l -> l.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: -1
                if (length !in 1..MAX_BODY) return MiniHttp.respond(it, 413, "Request too large")

                val body = String(MiniHttp.readExactly(input, length) ?: return MiniHttp.respond(it, 400, "Bad request"), Charsets.UTF_8)
                val form = body.split("&").mapNotNull { kv ->
                    val k = kv.substringBefore("=", "")
                    if (k.isEmpty()) null else k to URLDecoder.decode(kv.substringAfter("=", ""), "UTF-8")
                }.toMap()

                when {
                    done || failedAttempts >= MAX_FAILED_ATTEMPTS ->
                        MiniHttp.respond(it, 403, buildErrorPage("Too many wrong PINs. Restart the web server on the watch."))
                    !pinMatches(form["pin"].orEmpty().trim()) -> {
                        failedAttempts++
                        MiniHttp.respond(it, 403, buildErrorPage("Wrong PIN."))
                    }
                    form["token"].isNullOrBlank() -> MiniHttp.respond(it, 400, buildErrorPage("Token is empty."))
                    else -> {
                        done = true
                        submitted = form["token"]!!.trim()
                        MiniHttp.respond(it, 200, buildSuccessPage())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        }
        submitted?.let { onTokenReceived(it) }
    }

    /** Accept another submission, e.g. after the previous token turned out to be invalid. */
    fun rearm() {
        done = false
    }

    private fun pinMatches(candidate: String) =
        MessageDigest.isEqual(candidate.toByteArray(), pin.toByteArray())

    private fun buildFormPage() = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dihcord Token Setup</title>
<style>
  body { font-family: system-ui, sans-serif; background: #1e1f22; color: #dbdee1;
         display: flex; flex-direction: column; align-items: center;
         justify-content: center; min-height: 100vh; margin: 0; padding: 16px; }
  .card { background: #2b2d31; border-radius: 12px; padding: 24px;
          width: 100%; max-width: 420px; box-sizing: border-box; }
  h1 { margin: 0 0 8px; font-size: 1.3rem; color: #fff; }
  p  { margin: 0 0 16px; font-size: .85rem; color: #949ba4; line-height: 1.5; }
  input[type=password], input[type=text] {
    width: 100%; padding: 10px 12px; border-radius: 4px; border: 1px solid #3d3f45;
    background: #1e1f22; color: #dbdee1; font-size: 1rem; box-sizing: border-box;
    margin-bottom: 12px;
  }
  button {
    width: 100%; padding: 10px; border: none; border-radius: 4px;
    background: #5865f2; color: #fff; font-size: 1rem; cursor: pointer;
    font-weight: 600;
  }
  button:hover { background: #4752c4; }
  .warn { background: #f23f42; border-radius: 6px; padding: 8px 12px;
          font-size: .8rem; margin-bottom: 16px; color: #fff; }
</style>
</head>
<body>
<div class="card">
  <h1>Dihcord</h1>
  <p>Enter your Discord token below to log in on your watch. The token is stored only on your device.</p>
  <div class="warn">Never share your token. Only enter it on your own device, on a network you trust (this page is not encrypted).</div>
  <form method="POST" action="/">
    <input type="text" name="pin" placeholder="PIN shown on your watch" inputmode="numeric" autocomplete="off" required>
    <input type="password" name="token" placeholder="Paste your Discord token…" autocomplete="off" required>
    <button type="submit">Save Token</button>
  </form>
</div>
</body>
</html>"""

    private fun buildSuccessPage() = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dihcord</title>
<style>
  body { font-family: system-ui, sans-serif; background: #1e1f22; color: #dbdee1;
         display: flex; align-items: center; justify-content: center;
         min-height: 100vh; margin: 0; }
  .card { background: #2b2d31; border-radius: 12px; padding: 32px;
          text-align: center; max-width: 320px; }
  .icon { font-size: 3rem; }
  h1 { color: #23a55a; margin: 8px 0; }
  p  { color: #949ba4; font-size: .9rem; }
</style>
</head>
<body>
<div class="card">
  <h1>Token Saved!</h1>
  <p>Your Discord token has been sent to the watch. You can now close this page.</p>
</div>
</body>
</html>"""

    private fun buildErrorPage(message: String) = buildFormPage().replace(
        "<form",
        "<div class=\"warn\">$message</div>\n  <form",
    )

    companion object {
        private const val TAG = "TokenWebServer"
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 8 * 1024
        private const val MAX_FAILED_ATTEMPTS = 5

        fun generatePin(): String = "%06d".format(SecureRandom().nextInt(1_000_000))
    }
}
