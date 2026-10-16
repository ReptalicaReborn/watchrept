package com.dihcord.app.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.Base64
import java.util.TimeZone

/**
 * How Dihcord presents itself to Discord: one consistent, ordinary desktop-Chrome identity for logins, API calls and
 * the gateway. An odd or inconsistent client description (unknown device, mismatched user agent, a custom
 * "Dihcord/1.0" agent on a user account) is exactly what makes Discord demand captchas or cancel logins.
 */
object ClientIdentity {
    const val BROWSER_VERSION = "141.0.0.0"
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$BROWSER_VERSION Safari/537.36"
    private const val CLIENT_BUILD_NUMBER = 621195
    private const val LOCALE = "en-US"

    /** The client description sent in the gateway IDENTIFY and (base64) as X-Super-Properties. */
    fun properties(): JSONObject = JSONObject()
        .put("os", "Linux")
        .put("browser", "Chrome")
        .put("device", "")
        .put("system_locale", LOCALE)
        .put("has_client_mods", false)
        .put("browser_user_agent", USER_AGENT)
        .put("browser_version", BROWSER_VERSION)
        .put("os_version", "")
        .put("referrer", "")
        .put("referring_domain", "")
        .put("referrer_current", "")
        .put("referring_domain_current", "")
        .put("release_channel", "stable")
        .put("client_build_number", CLIENT_BUILD_NUMBER)
        .put("client_event_source", JSONObject.NULL)

    val superProperties: String by lazy {
        Base64.getEncoder().encodeToString(properties().toString().toByteArray(Charsets.UTF_8))
    }

    /** Adds the headers Discord's web client sends with every API request. */
    fun Request.Builder.discordHeaders(): Request.Builder = this
        .header("User-Agent", USER_AGENT)
        .header("X-Super-Properties", superProperties)
        .header("X-Discord-Locale", LOCALE)
        .header("X-Discord-Timezone", TimeZone.getDefault().id)
        .header("X-Debug-Options", "bugReporterEnabled")
        .header("Origin", "https://discord.com")
        .header("Referer", "https://discord.com/channels/@me")
}

/**
 * Keeps the cookies Discord hands out (`__dcfduid`, `__sdcfduid`, `__cfruid`, ...) for the life of the process,
 * like a browser would; requests without them look automated.
 */
class MemoryCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies["${it.domain}|${it.path}|${it.name}"] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.values.removeAll { it.expiresAt < now }
        return cookies.values.filter { it.matches(url) }
    }
}
