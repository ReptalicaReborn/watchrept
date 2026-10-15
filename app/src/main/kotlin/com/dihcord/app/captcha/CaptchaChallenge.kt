package com.dihcord.app.captcha

import org.json.JSONObject

/** A captcha Discord asked for (the 400 body containing `captcha_sitekey`). */
data class CaptchaChallenge(
    val sitekey: String,
    val service: String,
    val rqdata: String?,
    val rqtoken: String?,
    val sessionId: String?
) {
    companion object {
        fun parse(body: String): CaptchaChallenge? = runCatching {
            val o = JSONObject(body)
            fun str(key: String) = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }
            val sitekey = str("captcha_sitekey") ?: return null
            CaptchaChallenge(
                sitekey = sitekey,
                service = str("captcha_service") ?: "hcaptcha",
                rqdata = str("captcha_rqdata"),
                rqtoken = str("captcha_rqtoken"),
                sessionId = str("captcha_session_id"),
            )
        }.getOrNull()
    }
}
