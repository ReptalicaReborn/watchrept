package com.dihcord.app.captcha

import com.dihcord.app.AppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands captcha challenges from network code to the UI and waits for the user to solve them
 * (on their phone, via [CaptchaWebServer]). One challenge at a time.
 */
object CaptchaBroker {
    class Pending(val challenge: CaptchaChallenge, internal val result: CompletableDeferred<String?>)

    private const val TIMEOUT_MS = 5 * 60 * 1000L

    private val _pending = MutableStateFlow<Pending?>(null)

    /** The challenge currently waiting for the user, shown by the captcha screen. */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val mutex = Mutex()

    /**
     * Asks the user to solve [challenge]; returns the captcha token, or null if they cancelled, it timed out,
     * the service isn't supported, or the app isn't on screen to ask (e.g. a reply sent from a notification).
     */
    suspend fun solve(challenge: CaptchaChallenge): String? {
        if (challenge.service != "hcaptcha" || !AppState.inForeground) return null
        return mutex.withLock {
            val pending = Pending(challenge, CompletableDeferred())
            _pending.value = pending
            try {
                withTimeoutOrNull(TIMEOUT_MS) { pending.result.await() }
            } finally {
                _pending.value = null
            }
        }
    }

    fun complete(token: String?) {
        _pending.value?.result?.complete(token)
    }
}
