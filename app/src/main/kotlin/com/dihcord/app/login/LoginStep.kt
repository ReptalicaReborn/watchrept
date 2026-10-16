package com.dihcord.app.login

import com.dihcord.app.captcha.CaptchaChallenge
import org.json.JSONArray
import org.json.JSONObject

/** Where an email/password login stands after one of Discord's `/auth/...` responses. */
sealed class LoginStep {
    data class Success(val token: String) : LoginStep()

    /** Two-factor authentication is on; finish with one of the allowed methods using [ticket]. */
    data class Mfa(
        val ticket: String,
        val totp: Boolean,
        val sms: Boolean,
        val backup: Boolean,
        val webauthn: Boolean,
        val loginInstanceId: String?,
    ) : LoginStep()

    data class Captcha(val challenge: CaptchaChallenge) : LoginStep()

    /** Discord sent a verification code by SMS to [phone] (masked). */
    data class SmsSent(val phone: String?) : LoginStep()

    /** The request was refused; [message] is meant for the user. */
    data class Failed(val message: String, val retryAfterSeconds: Double? = null) : LoginStep()

    companion object {
        /** Interprets an `/auth/login`, `/auth/mfa/...` or `/auth/mfa/sms/send` response. */
        fun parse(status: Int, body: String): LoginStep {
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return Failed(if (status in 500..599) "Discord is having trouble (HTTP $status). Try again." else "Unexpected reply from Discord (HTTP $status).")

            CaptchaChallenge.parse(body)?.let { return Captcha(it) }

            if (status in 200..299) {
                json.str("token")?.let { return Success(it) }
                if (json.optBoolean("mfa")) {
                    val ticket = json.str("ticket") ?: return Failed("Discord asked for 2FA without a ticket.")
                    return Mfa(
                        ticket = ticket,
                        totp = json.optBoolean("totp"),
                        sms = json.optBoolean("sms"),
                        backup = json.optBoolean("backup"),
                        webauthn = json.str("webauthn") != null,
                        loginInstanceId = json.str("login_instance_id"),
                    )
                }
                if (json.has("phone")) return SmsSent(json.str("phone"))
                return Failed("Discord didn't send a token. Try again.")
            }

            if (status == 429) {
                val wait = json.optDouble("retry_after", Double.NaN).takeIf { !it.isNaN() }
                val text = wait?.let { "Too many attempts. Wait ${Math.ceil(it).toLong()} s and try again." }
                    ?: "Too many attempts. Wait a bit and try again."
                return Failed(text, wait)
            }

            return Failed(errorMessage(json) ?: "Discord refused the login (HTTP $status).")
        }

        /** The most specific human-readable message in a Discord error body. */
        internal fun errorMessage(json: JSONObject): String? {
            json.optJSONObject("errors")?.let { firstNestedError(it) }?.let { return friendly(it.first, it.second) }
            return json.str("message")?.let { friendly(json.opt("code")?.toString(), it) }
        }

        /** Finds the first `{code, message}` under any `_errors` array, depth-first. */
        private fun firstNestedError(obj: JSONObject): Pair<String?, String>? {
            obj.optJSONArray("_errors")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val msg = e.str("message") ?: continue
                    return e.str("code") to msg
                }
            }
            for (key in obj.keys()) {
                when (val child = obj.opt(key)) {
                    is JSONObject -> firstNestedError(child)?.let { return it }
                    is JSONArray -> for (i in 0 until child.length()) {
                        child.optJSONObject(i)?.let { firstNestedError(it) }?.let { return it }
                    }
                }
            }
            return null
        }

        private fun friendly(code: String?, message: String): String = when (code) {
            "ACCOUNT_LOGIN_VERIFICATION_EMAIL" ->
                "Discord sent you an email to confirm this new login location. Approve it, then sign in again."
            "ACCOUNT_COMPROMISED_RESET_PASSWORD" ->
                "Discord wants you to reset your password first. Do that in the Discord app, then try again."
            "60008" -> "Wrong code. Check it and try again."
            else -> message
        }

        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
