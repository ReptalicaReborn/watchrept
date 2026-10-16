package com.dihcord.app.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RtpCryptoTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `round trip without extension`() {
        val crypto = RtpCrypto(key)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = crypto.encrypt(RtpCrypto.header(7, 960, 1234), payload)
        assertEquals(12 + payload.size + 16 + 4, packet.size)
        assertEquals(1234L, RtpCrypto.readU32(packet, 8))
        assertArrayEquals(payload, RtpCrypto(key).decrypt(packet))
    }

    @Test
    fun `nonce counts up from one`() {
        val crypto = RtpCrypto(key)
        val a = crypto.encrypt(RtpCrypto.header(1, 0, 1), byteArrayOf(9))
        val b = crypto.encrypt(RtpCrypto.header(2, 960, 1), byteArrayOf(9))
        assertEquals(1L, RtpCrypto.readU32(a, a.size - 4))
        assertEquals(2L, RtpCrypto.readU32(b, b.size - 4))
    }

    @Test
    fun `received packet with header extension drops the extension body`() {
        // Header with X bit set, then the 4-byte extension preamble (profile 0xBEDE, length 1 word) in clear.
        val header = RtpCrypto.header(1, 0, 42)
        header[0] = (header[0].toInt() or 0x10).toByte()
        val aad = header + byteArrayOf(0xBE.toByte(), 0xDE.toByte(), 0, 1)
        val extensionBody = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val opus = byteArrayOf(0x55, 0x66, 0x77)
        val nonce = ByteArray(12).also { it[3] = 9 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(extensionBody + opus)
        val packet = aad + sealed + nonce.copyOfRange(0, 4)

        assertArrayEquals(opus, RtpCrypto(key).decrypt(packet))
    }

    @Test
    fun `tampered packet is rejected`() {
        val packet = RtpCrypto(key).encrypt(RtpCrypto.header(1, 0, 1), byteArrayOf(1, 2, 3))
        packet[13] = (packet[13].toInt() xor 1).toByte()
        assertNull(RtpCrypto(key).decrypt(packet))
    }
}
