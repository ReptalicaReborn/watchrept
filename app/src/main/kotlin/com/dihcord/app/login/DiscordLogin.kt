package com.dihcord.app.login

import com.dihcord.app.api.ClientIdentity.discordHeaders
import com.dihcord.app.api.MemoryCookieJar
import com.dihcord.app.captcha.CaptchaChallenge
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Signs in with email and password the way discord.com's login page does, including two-factor authentication
 * and captchas. Every call blocks, so call it off the main thread. One instance is one login attempt: it keeps
 * the browser session (cookies, fingerprint), the MFA ticket and the request a captcha interrupted.
 */
class DiscordLogin {
    private val http = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var fingerprint: String? = null
    private var mfa: LoginStep.Mfa? = null

    /** The request a captcha interrupted, and the challenge to answer before repeating it. */
    private var interrupted: Pair<String, JSONObject>? = null
    private var challenge: CaptchaChallenge? = null

    @Synchronized
    fun login(email: String, password: String): LoginStep {
        mfa = null
        return post(
            "/auth/login",
            JSONObject()
                .put("login", email)
                .put("password", password)
                .put("undelete", false)
                .put("login_source", JSONObject.NULL)
                .put("gift_code_sku_id", JSONObject.NULL),
        )
    }

    /** Finishes 2FA with an authenticator ([method] `totp`), SMS (`sms`) or backup (`backup`) code. */
    @Synchronized
    fun submitCode(method: String, code: String): LoginStep {
        val pending = mfa ?: return LoginStep.Failed("The login expired. Start again.")
        val cleaned = if (method == "backup") code.replace(Regex("[\\s-]"), "").lowercase() else code.filter(Char::isDigit)
        if (cleaned.isEmpty()) return LoginStep.Failed("Enter the code first.")
        return post(
            "/auth/mfa/$method",
            JSONObject()
                .put("code", cleaned)
                .put("ticket", pending.ticket)
                .put("login_source", JSONObject.NULL)
                .put("gift_code_sku_id", JSONObject.NULL)
                .apply { pending.loginInstanceId?.let { put("login_instance_id", it) } },
        )
    }

    @Synchronized
    fun sendSms(): LoginStep {
        val pending = mfa ?: return LoginStep.Failed("The login expired. Start again.")
        return post("/auth/mfa/sms/send", JSONObject().put("ticket", pending.ticket))
    }

    /** Repeats the request the captcha interrupted, now carrying the solved [key]. */
    @Synchronized
    fun answerCaptcha(key: String): LoginStep {
        val (path, body) = interrupted ?: return LoginStep.Failed("No captcha is pending. Start again.")
        val c = challenge ?: return LoginStep.Failed("No captcha is pending. Start again.")
        body.put("captcha_key", key)
        c.rqtoken?.let { body.put("captcha_rqtoken", it) }
        return post(path, body, c.answerHeaders(key))
    }

    private fun post(path: String, body: JSONObject, extraHeaders: List<Pair<String, String>> = emptyList()): LoginStep {
        prepareBrowserSession()
        val builder = Request.Builder()
            .url("$API$path")
            .post(body.toString().toRequestBody(JSON))
            .discordHeaders()
            .header("Referer", "https://discord.com/login")
        fingerprint?.let { builder.header("X-Fingerprint", it) }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val step = runCatching {
            http.newCall(builder.build()).execute().use { r -> LoginStep.parse(r.code, r.body?.string().orEmpty()) }
        }.getOrElse { LoginStep.Failed("Couldn't reach Discord: ${it.message ?: it.javaClass.simpleName}") }

        when (step) {
            is LoginStep.Captcha -> {
                // Keep the original body (minus any earlier answer) so the retry repeats exactly this request.
                body.remove("captcha_key")
                body.remove("captcha_rqtoken")
                interrupted = path to body
                challenge = step.challenge
            }
            is LoginStep.Mfa -> { mfa = step; interrupted = null; challenge = null }
            else -> { interrupted = null; challenge = null }
        }
        return step
    }

    /** Fetches the fingerprint and cookies Discord's login page would have; requests without them get more captchas. */
    private fun prepareBrowserSession() {
        if (fingerprint != null) return
        runCatching {
            val request = Request.Builder()
                .url("$API/experiments")
                .discordHeaders()
                .header("Referer", "https://discord.com/login")
                .build()
            http.newCall(request).execute().use { r ->
                fingerprint = runCatching { JSONObject(r.body?.string().orEmpty()).optString("fingerprint") }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
            }
        }
    }

    private companion object {
        const val API = "https://discord.com/api/v9"
        val JSON = "application/json".toMediaType()
    }
}
