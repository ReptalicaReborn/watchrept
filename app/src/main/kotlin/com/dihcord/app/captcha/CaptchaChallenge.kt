package com.dihcord.app.captcha

import org.json.JSONObject

/** A captcha Discord asked for (the 400 body containing `captcha_sitekey`). */
data class CaptchaChallenge(
    val sitekey: String,
    val service: String,
    val rqdata: String?,
    val rqtoken: String?,
    val sessionId: String?,
    /** Why Discord asked again, e.g. `invalid-response` after a rejected answer (the body's `captcha_key`). */
    val errors: List<String> = emptyList()
) {
    /** Headers Discord expects on the retry that carries the solved [key]. */
    fun answerHeaders(key: String): List<Pair<String, String>> = buildList {
        add("X-Captcha-Key" to key)
        rqtoken?.let { add("X-Captcha-Rqtoken" to it) }
        sessionId?.let { add("X-Captcha-Session-Id" to it) }
    }

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
                errors = o.optJSONArray("captcha_key")
                    ?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotEmpty) } }
                    .orEmpty(),
            )
        }.getOrNull()
    }
}
