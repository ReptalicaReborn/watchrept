package com.dihcord.app.captcha

import android.util.Log
import com.dihcord.app.MiniHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Serves an hCaptcha page at `http://<watch-ip>:<port>/<code>` so the user can solve the captcha on their
 * phone (Wear OS usually has no WebView). The solved token is POSTed back and handed to [onToken].
 * The random [code] in the path keeps other devices on the network from stumbling onto the page.
 */
class CaptchaWebServer(
    private val challenge: CaptchaChallenge,
    private val port: Int = 8081,
    val code: String = "%06d".format(SecureRandom().nextInt(1_000_000)),
    private val onToken: (String) -> Unit,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun start() {
        scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 5)
                }
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    launch { handle(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Captcha server stopped", e)
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        scope.cancel()
    }

    private fun handle(socket: Socket) {
        var token: String? = null
        runCatching {
            socket.use {
                it.soTimeout = 10_000
                val req = MiniHttp.readRequest(it.getInputStream(), MAX_HEAD, MAX_BODY) {
                    MiniHttp.respond(it, 413, "Too large", "text/plain")
                } ?: return@use
                val expected = "/$code"
                when {
                    req.path != expected -> MiniHttp.respond(it, 404, "Not found", "text/plain")
                    req.method == "GET" -> MiniHttp.respond(it, 200, page())
                    req.method == "POST" -> {
                        val t = MiniHttp.parseForm(req.body)["token"]?.trim()
                        if (t.isNullOrEmpty()) {
                            MiniHttp.respond(it, 400, "Missing token", "text/plain")
                        } else {
                            token = t
                            MiniHttp.respond(it, 200, "OK", "text/plain")
                        }
                    }
                    else -> MiniHttp.respond(it, 400, "Bad request", "text/plain")
                }
            }
        }.onFailure { Log.w(TAG, "Client error", it) }
        token?.let(onToken)
    }

    /** Inline JS string literal, safe inside a <script> block. */
    private fun js(value: String?): String =
        if (value == null) "null" else JSONObject.quote(value).replace("</", "<\\/")

    private fun page() = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dihcord captcha</title>
<style>
  body { font-family: system-ui, sans-serif; background: #1e1f22; color: #dbdee1; display: flex;
         align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 16px; }
  .card { background: #2b2d31; border-radius: 12px; padding: 24px; max-width: 420px; width: 100%;
          box-sizing: border-box; text-align: center; }
  h1 { margin: 0 0 8px; font-size: 1.3rem; color: #fff; }
  p { font-size: .9rem; color: #949ba4; line-height: 1.5; }
  #c { display: inline-block; margin: 12px 0; min-height: 80px; }
  #s { color: #dbdee1; }
</style>
<script src="https://js.hcaptcha.com/1/api.js?render=explicit&onload=dihcordReady" async defer></script>
</head>
<body>
<div class="card">
  <h1>Dihcord</h1>
  <p>Discord wants a captcha before continuing. Solve it here and your watch will carry on automatically.</p>
  <div id="c"></div>
  <p id="s">Loading captcha…</p>
</div>
<script>
  var SITEKEY = ${js(challenge.sitekey)};
  var RQDATA = ${js(challenge.rqdata)};
  var statusEl = document.getElementById('s');
  function fail(m) {
    statusEl.textContent = m + ' If this keeps happening, do this action once in the Discord app on your phone, then try again on the watch.';
  }
  function send(token) {
    statusEl.textContent = 'Sending to your watch…';
    fetch(location.pathname, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'token=' + encodeURIComponent(token)
    }).then(function (r) {
      statusEl.textContent = r.ok ? 'Done! You can close this page.' : 'The watch did not accept the answer.';
    }).catch(function () { fail('Could not reach the watch.'); });
  }
  function dihcordReady() {
    try {
      var id = hcaptcha.render('c', {
        sitekey: SITEKEY, theme: 'dark',
        callback: send,
        'error-callback': function (e) { fail('hCaptcha error (' + e + ').'); }
      });
      if (RQDATA) hcaptcha.setData(id, { rqdata: RQDATA });
      statusEl.textContent = 'Solve the captcha above.';
    } catch (e) { fail('hCaptcha could not start (' + e + ').'); }
  }
  setTimeout(function () { if (!window.hcaptcha) fail('hCaptcha did not load.'); }, 10000);
</script>
</body>
</html>"""

    private companion object {
        const val TAG = "CaptchaWebServer"
        const val MAX_HEAD = 8 * 1024
        const val MAX_BODY = 64 * 1024 // hCaptcha tokens can be several KB
    }
}
