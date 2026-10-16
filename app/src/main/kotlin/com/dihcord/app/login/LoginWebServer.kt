package com.dihcord.app.login

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
 * Serves a Discord-style sign-in page at `http://<watch-ip>:<port>/<code>` so the user can type their email,
 * password, 2FA code and solve any captcha on their phone. The watch itself talks to Discord ([DiscordLogin]);
 * the page only relays what the user types. The random [code] in the path keeps other devices on the network
 * out, and the server stops answering after too many wrong paths. Traffic is plain HTTP on the local network.
 */
class LoginWebServer(
    private val port: Int = 8082,
    val code: String = "%06d".format(SecureRandom().nextInt(1_000_000)),
    /** Short progress text for the watch screen. Called on a background thread. */
    private val onStatus: (String) -> Unit = {},
    /** Called once, on a background thread, with the account token. */
    private val onToken: (String) -> Unit,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val login = DiscordLogin()

    @Volatile private var badRequests = 0
    @Volatile private var done = false

    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun start(onStartFailed: (Throwable) -> Unit = {}) {
        scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 5)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't bind port $port", e)
                onStartFailed(e)
                return@launch
            }
            try {
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    launch { handle(client) }
                }
            } catch (e: Exception) {
                Log.i(TAG, "Login server stopped: ${e.message}")
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
                when {
                    badRequests >= MAX_BAD_REQUESTS -> MiniHttp.respond(it, 403, "Locked. Restart sign-in on the watch.", "text/plain")
                    req.path != "/$code" -> {
                        if (req.path != "/favicon.ico") badRequests++
                        MiniHttp.respond(it, 404, "Not found", "text/plain")
                    }
                    req.method == "GET" -> MiniHttp.respond(it, 200, page())
                    req.method == "POST" -> {
                        val (reply, received) = act(MiniHttp.parseForm(req.body))
                        token = received
                        MiniHttp.respond(it, 200, reply.toString(), "application/json; charset=utf-8")
                    }
                    else -> MiniHttp.respond(it, 400, "Bad request", "text/plain")
                }
            }
        }.onFailure { Log.w(TAG, "Client error", it) }
        token?.let(onToken)
    }

    /** Runs one page action against Discord; returns the JSON for the page and the token if login finished. */
    private fun act(form: Map<String, String>): Pair<JSONObject, String?> {
        if (done) return JSONObject().put("state", "done") to null
        val step = when (form["action"]) {
            "login" -> {
                val email = form["email"]?.trim().orEmpty()
                val password = form["password"].orEmpty()
                if (email.isEmpty() || password.isEmpty()) {
                    LoginStep.Failed("Enter your email (or phone number) and password.")
                } else {
                    onStatus("Signing in…")
                    login.login(email, password)
                }
            }
            "code" -> {
                onStatus("Checking code…")
                login.submitCode(form["method"]?.takeIf { it in MFA_METHODS } ?: "totp", form["code"].orEmpty())
            }
            "sms" -> {
                onStatus("Sending SMS…")
                login.sendSms()
            }
            "captcha" -> {
                val key = form["key"]?.trim().orEmpty()
                if (key.isEmpty()) LoginStep.Failed("The captcha wasn't solved.") else {
                    onStatus("Captcha solved, continuing…")
                    login.answerCaptcha(key)
                }
            }
            else -> LoginStep.Failed("Unknown action.")
        }
        val reply = JSONObject()
        var token: String? = null
        when (step) {
            is LoginStep.Success -> {
                done = true
                token = step.token
                onStatus("Signed in!")
                reply.put("state", "done")
            }
            is LoginStep.Mfa -> {
                onStatus("Enter your 2FA code on the phone")
                reply.put("state", "mfa").put("totp", step.totp).put("sms", step.sms).put("backup", step.backup)
                if (!step.totp && !step.sms && !step.backup) {
                    reply.put("message", "This account only allows a security key, which Dihcord can't use. Use a backup code if you have one.")
                    reply.put("backup", true)
                }
            }
            is LoginStep.Captcha -> {
                onStatus("Solve the captcha on the phone")
                reply.put("state", "captcha").put("sitekey", step.challenge.sitekey)
                    .put("rqdata", step.challenge.rqdata ?: JSONObject.NULL)
                    .put("service", step.challenge.service)
                if (step.challenge.errors.isNotEmpty()) reply.put("message", "Discord didn't accept that captcha. Try again.")
            }
            is LoginStep.SmsSent -> {
                onStatus("SMS sent, enter the code on the phone")
                reply.put("state", "sms_sent").put("phone", step.phone ?: JSONObject.NULL)
            }
            is LoginStep.Failed -> {
                onStatus(step.message)
                reply.put("state", "error").put("message", step.message)
            }
        }
        return reply to token
    }

    private fun page() = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dihcord sign-in</title>
<style>
  body { font-family: system-ui, sans-serif; background: #1e1f22; color: #dbdee1; display: flex;
         align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 16px; box-sizing: border-box; }
  .card { background: #2b2d31; border-radius: 12px; padding: 24px; max-width: 420px; width: 100%; box-sizing: border-box; }
  h1 { margin: 0 0 4px; font-size: 1.3rem; color: #fff; text-align: center; }
  p { font-size: .88rem; color: #949ba4; line-height: 1.5; margin: 0 0 14px; }
  label { display: block; font-size: .72rem; font-weight: 700; text-transform: uppercase; color: #b5bac1; margin-bottom: 6px; }
  input { width: 100%; padding: 10px 12px; border-radius: 4px; border: 1px solid #1e1f22; background: #1e1f22;
          color: #dbdee1; font-size: 1rem; box-sizing: border-box; margin-bottom: 14px; }
  button { width: 100%; padding: 11px; border: none; border-radius: 4px; background: #5865f2; color: #fff;
           font-size: 1rem; font-weight: 600; cursor: pointer; margin-bottom: 8px; }
  button.alt { background: #4e5058; }
  button:disabled { opacity: .6; }
  .link { background: none; color: #00a8fc; padding: 4px; font-size: .88rem; font-weight: 500; }
  #msg { min-height: 1.2em; font-size: .88rem; margin: 0 0 12px; color: #fa777c; text-align: center; }
  #msg.ok { color: #23a55a; }
  .warn { font-size: .75rem; color: #949ba4; text-align: center; margin-top: 12px; }
  .hidden { display: none; }
  #cap { display: flex; justify-content: center; min-height: 80px; margin-bottom: 12px; }
</style>
</head>
<body>
<div class="card">
  <h1>Sign in to Dihcord</h1>
  <p style="text-align:center">Your watch signs in to Discord with what you enter here.</p>
  <div id="msg"></div>

  <form id="creds">
    <label for="email">Email or phone number</label>
    <input id="email" type="text" autocomplete="username" autocapitalize="off" spellcheck="false" required>
    <label for="password">Password</label>
    <input id="password" type="password" autocomplete="current-password" required>
    <button type="submit">Log In</button>
  </form>

  <form id="mfa" class="hidden">
    <p id="mfaText">Enter the 6-digit code from your authenticator app.</p>
    <label for="code" id="codeLabel">Code</label>
    <input id="code" type="text" inputmode="numeric" autocomplete="one-time-code" required>
    <button type="submit">Confirm</button>
    <button type="button" class="link hidden" id="useTotp">Use your authenticator app</button>
    <button type="button" class="link hidden" id="useSms">Send a code by SMS instead</button>
    <button type="button" class="link hidden" id="useBackup">Use a backup code</button>
    <button type="button" class="link" id="restart1">Start over</button>
  </form>

  <div id="captcha" class="hidden">
    <p>Discord wants a captcha. Solve it and the sign-in continues.</p>
    <div id="cap"></div>
    <button type="button" class="link" id="restart2">Start over</button>
  </div>

  <div id="done" class="hidden">
    <p style="text-align:center;font-size:1rem;color:#23a55a">Signed in! Your watch is loading Discord. You can close this page.</p>
  </div>

  <div class="warn">This page is not encrypted. Only use it on your own Wi-Fi.</div>
</div>
<script>
  var $ = function (id) { return document.getElementById(id); };
  var methods = { totp: false, sms: false, backup: false };
  var method = 'totp';
  var lastView = 'creds';
  var busy = false;

  function show(view) {
    ['creds', 'mfa', 'captcha', 'done'].forEach(function (v) { $(v).classList.toggle('hidden', v !== view); });
    if (view !== 'captcha') lastView = view;
  }
  function msg(text, ok) { $('msg').textContent = text || ''; $('msg').className = ok ? 'ok' : ''; }
  function setBusy(b) {
    busy = b;
    Array.prototype.forEach.call(document.querySelectorAll('button'), function (x) { x.disabled = b; });
  }

  function send(params) {
    if (busy) return;
    setBusy(true);
    var body = Object.keys(params).map(function (k) {
      return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]);
    }).join('&');
    fetch(location.pathname, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body
    }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (res) { setBusy(false); handle(res); })
      .catch(function (e) { setBusy(false); msg('Could not reach the watch (' + e.message + '). Is it still on this screen?'); });
  }

  function handle(res) {
    switch (res.state) {
      case 'done': msg(''); show('done'); break;
      case 'mfa':
        methods = { totp: !!res.totp, sms: !!res.sms, backup: !!res.backup };
        msg(res.message || '');
        pickMethod(methods.totp ? 'totp' : methods.sms ? 'sms-pending' : 'backup');
        show('mfa');
        break;
      case 'sms_sent':
        msg('Code sent' + (res.phone ? ' to ' + res.phone : '') + '.', true);
        pickMethod('sms');
        show('mfa');
        break;
      case 'captcha': msg(res.message || ''); captcha(res.sitekey, res.rqdata); break;
      default:
        msg(res.message || 'Something went wrong.');
        show(lastView);
    }
  }

  function pickMethod(m) {
    if (m === 'sms-pending') { send({ action: 'sms' }); m = 'sms'; }
    method = m;
    $('code').value = '';
    if (m === 'totp') {
      $('mfaText').textContent = 'Enter the 6-digit code from your authenticator app.';
      $('code').setAttribute('inputmode', 'numeric');
    } else if (m === 'sms') {
      $('mfaText').textContent = 'Enter the code Discord sent to your phone by SMS.';
      $('code').setAttribute('inputmode', 'numeric');
    } else {
      $('mfaText').textContent = 'Enter one of your 8-character backup codes.';
      $('code').setAttribute('inputmode', 'text');
    }
    $('useTotp').classList.toggle('hidden', !methods.totp || m === 'totp');
    $('useSms').classList.toggle('hidden', !methods.sms || m === 'sms');
    $('useBackup').classList.toggle('hidden', !methods.backup || m === 'backup');
  }

  var hcLoading = null;
  function loadHcaptcha() {
    if (window.hcaptcha) return Promise.resolve();
    if (!hcLoading) hcLoading = new Promise(function (resolve, reject) {
      window.dihcordHcReady = resolve;
      var s = document.createElement('script');
      s.src = 'https://js.hcaptcha.com/1/api.js?render=explicit&onload=dihcordHcReady';
      s.async = true;
      s.onerror = function () { hcLoading = null; reject(new Error('hCaptcha did not load')); };
      document.head.appendChild(s);
    });
    return hcLoading;
  }

  function captcha(sitekey, rqdata) {
    show('captcha');
    var box = $('cap');
    box.innerHTML = '';
    var el = document.createElement('div');
    box.appendChild(el);
    loadHcaptcha().then(function () {
      var id = hcaptcha.render(el, {
        sitekey: sitekey, theme: 'dark',
        callback: function (key) { msg('Checking…', true); send({ action: 'captcha', key: key }); },
        'error-callback': function (e) { msg('hCaptcha error (' + e + '). Start over and try again.'); }
      });
      if (rqdata) hcaptcha.setData(id, { rqdata: rqdata });
    }).catch(function (e) { msg(e.message + '. Check your connection and start over.'); });
  }

  function restart() { msg(''); $('password').value = ''; show('creds'); }

  $('creds').addEventListener('submit', function (e) {
    e.preventDefault();
    msg('');
    send({ action: 'login', email: $('email').value, password: $('password').value });
  });
  $('mfa').addEventListener('submit', function (e) {
    e.preventDefault();
    msg('');
    send({ action: 'code', method: method, code: $('code').value });
  });
  $('useTotp').onclick = function () { msg(''); pickMethod('totp'); };
  $('useSms').onclick = function () { msg(''); pickMethod('sms-pending'); };
  $('useBackup').onclick = function () { msg(''); pickMethod('backup'); };
  $('restart1').onclick = restart;
  $('restart2').onclick = restart;
</script>
</body>
</html>"""

    private companion object {
        const val TAG = "LoginWebServer"
        const val MAX_HEAD = 8 * 1024
        const val MAX_BODY = 64 * 1024 // hCaptcha tokens can be several KB
        const val MAX_BAD_REQUESTS = 40
        val MFA_METHODS = setOf("totp", "sms", "backup")
    }
}
