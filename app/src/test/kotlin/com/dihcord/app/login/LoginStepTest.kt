package com.dihcord.app.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginStepTest {
    @Test
    fun tokenMeansSuccess() {
        assertEquals(LoginStep.Success("abc"), LoginStep.parse(200, """{"user_id":"1","token":"abc"}"""))
    }

    @Test
    fun mfaTicketAndMethods() {
        val step = LoginStep.parse(
            200,
            """{"token":null,"mfa":true,"sms":false,"totp":true,"backup":true,"webauthn":null,"ticket":"t1","login_instance_id":"li"}"""
        )
        assertEquals(LoginStep.Mfa("t1", totp = true, sms = false, backup = true, webauthn = false, loginInstanceId = "li"), step)
    }

    @Test
    fun captchaChallenge() {
        val step = LoginStep.parse(
            400,
            """{"captcha_key":["captcha-required"],"captcha_sitekey":"sk","captcha_service":"hcaptcha","captcha_rqdata":"rd","captcha_rqtoken":"rt"}"""
        )
        assertTrue(step is LoginStep.Captcha)
        step as LoginStep.Captcha
        assertEquals("sk", step.challenge.sitekey)
        assertEquals("rt", step.challenge.rqtoken)
    }

    @Test
    fun invalidCredentialsMessage() {
        val step = LoginStep.parse(
            400,
            """{"message":"Invalid Form Body","code":50035,"errors":{"login":{"_errors":[{"code":"INVALID_LOGIN","message":"Login or password is invalid."}]},"password":{"_errors":[{"code":"INVALID_LOGIN","message":"Login or password is invalid."}]}}}"""
        )
        assertEquals(LoginStep.Failed("Login or password is invalid."), step)
    }

    @Test
    fun newLocationNeedsEmailApproval() {
        val step = LoginStep.parse(
            400,
            """{"code":50035,"errors":{"login":{"_errors":[{"code":"ACCOUNT_LOGIN_VERIFICATION_EMAIL","message":"New login location detected, please check your e-mail."}]}}}"""
        ) as LoginStep.Failed
        assertTrue(step.message.contains("email"))
    }

    @Test
    fun wrongMfaCode() {
        val step = LoginStep.parse(400, """{"message":"Invalid two-factor code","code":60008}""") as LoginStep.Failed
        assertTrue(step.message.startsWith("Wrong code"))
    }

    @Test
    fun smsSent() {
        assertEquals(LoginStep.SmsSent("+*******1234"), LoginStep.parse(200, """{"phone":"+*******1234"}"""))
    }

    @Test
    fun rateLimited() {
        val step = LoginStep.parse(429, """{"message":"You are being rate limited.","retry_after":2.5,"global":false}""") as LoginStep.Failed
        assertEquals(2.5, step.retryAfterSeconds!!, 0.0)
        assertTrue(step.message.contains("3 s"))
    }

    @Test
    fun nonJsonServerError() {
        assertTrue(LoginStep.parse(502, "<html>bad gateway</html>") is LoginStep.Failed)
    }
}
