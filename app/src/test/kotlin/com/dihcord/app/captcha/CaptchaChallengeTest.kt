package com.dihcord.app.captcha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptchaChallengeTest {
    @Test
    fun parsesRejectedAnswerChallenge() {
        val c = CaptchaChallenge.parse(
            """{"captcha_key":["invalid-response"],"captcha_sitekey":"sk","captcha_service":"hcaptcha",""" +
                """"captcha_rqdata":"rd","captcha_rqtoken":"rt","captcha_session_id":"sid"}"""
        )!!
        assertEquals("sk", c.sitekey)
        assertEquals(listOf("invalid-response"), c.errors)
        assertEquals(
            listOf("X-Captcha-Key" to "key", "X-Captcha-Rqtoken" to "rt", "X-Captcha-Session-Id" to "sid"),
            c.answerHeaders("key")
        )
    }

    @Test
    fun optionalHeadersAreSkipped() {
        val c = CaptchaChallenge.parse("""{"captcha_key":["captcha-required"],"captcha_sitekey":"sk"}""")!!
        assertEquals("hcaptcha", c.service)
        assertEquals(listOf("X-Captcha-Key" to "k"), c.answerHeaders("k"))
    }

    @Test
    fun ignoresOtherErrors() {
        assertNull(CaptchaChallenge.parse("""{"message":"Invalid Form Body","code":50035}"""))
    }
}
