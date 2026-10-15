package com.watchrept.app

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TokenWebServerTest {
    private val port = 18_089
    private val received = CompletableFuture<String>()
    private lateinit var server: TokenWebServer

    @Before
    fun setUp() {
        server = TokenWebServer(port = port, pin = "123456") { received.complete(it) }
        server.start()
        // Wait for the accept loop to bind, without spending any PIN attempts.
        repeat(100) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(50)
        }
    }

    @After
    fun tearDown() = server.stop()

    private fun post(body: String): Int {
        val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        (if (code < 400) conn.inputStream else conn.errorStream)?.readBytes()
        return code
    }

    @Test
    fun `generated pin is six digits`() {
        assertEquals(6, TokenWebServer.generatePin().length)
        assertEquals(true, TokenWebServer.generatePin().all { it.isDigit() })
    }

    @Test
    fun `wrong pin is rejected and token is not delivered`() {
        assertEquals(403, post("pin=000000&token=abc"))
        assertNull(received.getNow(null))
    }

    @Test
    fun `correct pin delivers the token once`() {
        assertEquals(200, post("pin=123456&token=my.secret.token"))
        assertEquals("my.secret.token", received.get(2, TimeUnit.SECONDS))
        // A second submission is refused, even with the right pin.
        assertEquals(403, post("pin=123456&token=other"))
    }

    @Test
    fun `locks after repeated wrong pins`() {
        repeat(5) { assertEquals(403, post("pin=111111&token=t")) }
        assertEquals(403, post("pin=123456&token=t"))
        assertNull(received.getNow(null))
    }

    @Test
    fun `rearm allows another token after the first was rejected downstream`() = runBlocking {
        assertEquals(200, post("pin=123456&token=first"))
        server.rearm()
        assertEquals(200, post("pin=123456&token=second"))
    }
}
