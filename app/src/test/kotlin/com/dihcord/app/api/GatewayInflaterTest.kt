package com.dihcord.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater

class GatewayInflaterTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // The first two frames Discord's gateway really sends on a "compress=zlib-stream" connection: HELLO, and
    // the acknowledgement of a heartbeat. Both belong to one zlib stream.
    private val hello = bytes("78da34c9410a83301005d0bbfc75a624a5ddcc558cc854830a894a325a4ac8dddb4d770f5e8582b7334683f2c77e809d3598c0154b90acaf203aac9b867c49043fdcfdf9fb41b38c01dca1f39845c35b3e74e48924273a0b0529ea68a4b9a4c5c3548fb48e792f1e6c6fb6f5e85bfb020000ffff")
    private val ack = bytes("aac665bf21d87e90402d000000ffff")

    @Test
    fun `reads the gateway's own frames`() {
        val inflater = GatewayInflater()
        val first = JSONObject(inflater.feed(hello)!!)
        assertEquals(10, first.getInt("op"))
        assertTrue(first.getJSONObject("d").getLong("heartbeat_interval") > 0)
        // The second message only makes sense with what the first left in the stream.
        assertEquals(11, JSONObject(inflater.feed(ack)!!).getInt("op"))
    }

    @Test
    fun `a message split over frames waits for its end`() {
        val inflater = GatewayInflater()
        assertNull(inflater.feed(hello.copyOfRange(0, 40)))
        assertEquals(10, JSONObject(inflater.feed(hello.copyOfRange(40, hello.size))!!).getInt("op"))
        assertEquals(11, JSONObject(inflater.feed(ack)!!).getInt("op"))
    }

    /** What a server does: one deflater for the connection, a sync flush after each message. */
    private class Sender {
        private val deflater = Deflater()
        private val buffer = ByteArray(8 * 1024)
        fun frame(text: String): ByteArray {
            deflater.setInput(text.toByteArray())
            val out = ByteArrayOutputStream()
            while (true) {
                val n = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    @Test
    fun `large and non-ASCII messages survive, each one separate`() {
        val sender = Sender()
        val inflater = GatewayInflater()
        // Larger than the working buffer, like the first message after connecting.
        val big = """{"op":0,"t":"READY","d":"${"Blockhaven · général 🌸 ".repeat(20_000)}"}"""
        assertEquals(big, inflater.feed(sender.frame(big)))
        assertEquals("""{"op":11}""", inflater.feed(sender.frame("""{"op":11}""")))
        assertEquals(big, inflater.feed(sender.frame(big)))
    }

    @Test(expected = DataFormatException::class)
    fun `bytes that aren't the stream are refused`() {
        GatewayInflater().feed(byteArrayOf(1, 2, 3, 4, 5, 6, 0, 0, 0xFF.toByte(), 0xFF.toByte()))
    }

    @Test
    fun `frames after closing are ignored`() {
        val inflater = GatewayInflater()
        inflater.close()
        assertNull(inflater.feed(hello))
        inflater.close()
    }
}
