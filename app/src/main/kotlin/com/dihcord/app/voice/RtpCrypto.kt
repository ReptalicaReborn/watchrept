package com.dihcord.app.voice

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Discord's voice transport encryption, mode `aead_aes256_gcm_rtpsize` (the only AEAD mode the JDK can do; the
 * other, XChaCha20, needs libsodium). Layout of a packet:
 *
 * `RTP header (+ 4-byte extension preamble) | AES-GCM(extension body + payload) | 16-byte tag | 4-byte nonce`
 *
 * The clear header is the AAD; the nonce is a 32-bit counter, big-endian in the first 4 of 12 bytes.
 */
class RtpCrypto(secretKey: ByteArray) {
    private val key = SecretKeySpec(secretKey, "AES")
    private var nonceCounter = 0L

    /** Builds a full packet: [header] (12 bytes, sent in clear) + encrypted [payload] + tag + nonce. */
    fun encrypt(header: ByteArray, payload: ByteArray): ByteArray {
        nonceCounter = (nonceCounter + 1) and 0xFFFFFFFFL
        val nonce = ByteArray(12)
        writeU32(nonce, 0, nonceCounter)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        val sealed = cipher.doFinal(payload) // ciphertext + tag
        val out = ByteArray(header.size + sealed.size + 4)
        header.copyInto(out)
        sealed.copyInto(out, header.size)
        nonce.copyInto(out, header.size + sealed.size, 0, 4)
        return out
    }

    /**
     * The media payload (Opus, still DAVE-encrypted) of a received packet, with RTP extension data and padding
     * removed; null when the packet is malformed or doesn't authenticate.
     */
    fun decrypt(packet: ByteArray): ByteArray? {
        if (packet.size < 12 + TAG_BYTES + 4) return null
        val first = packet[0].toInt() and 0xFF
        val hasExtension = (first shr 4) and 1 == 1
        val csrcCount = first and 0x0F
        var headerSize = 12 + 4 * csrcCount
        val extensionOffset = headerSize
        if (hasExtension) headerSize += 4
        if (packet.size < headerSize + TAG_BYTES + 4) return null

        val nonce = ByteArray(12)
        packet.copyInto(nonce, 0, packet.size - 4, packet.size)
        val plain = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(packet, 0, headerSize)
            cipher.doFinal(packet, headerSize, packet.size - headerSize - 4)
        } catch (_: Exception) {
            return null
        }

        var start = 0
        var end = plain.size
        // RFC 3550 padding: the last byte says how many bytes to drop.
        if (first and 0x20 != 0 && end > 0) {
            val pad = plain[end - 1].toInt() and 0xFF
            if (pad < end) end -= pad
        }
        if (hasExtension) {
            val words = ((packet[extensionOffset + 2].toInt() and 0xFF) shl 8) or (packet[extensionOffset + 3].toInt() and 0xFF)
            start = 4 * words
        }
        if (start > end) return null
        return plain.copyOfRange(start, end)
    }

    companion object {
        const val MODE = "aead_aes256_gcm_rtpsize"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16

        /** A 12-byte RTP header for an Opus packet. */
        fun header(sequence: Int, timestamp: Long, ssrc: Long): ByteArray {
            val h = ByteArray(12)
            h[0] = 0x80.toByte()
            h[1] = OPUS_PAYLOAD_TYPE.toByte()
            h[2] = (sequence shr 8).toByte()
            h[3] = sequence.toByte()
            writeU32(h, 4, timestamp)
            writeU32(h, 8, ssrc)
            return h
        }

        const val OPUS_PAYLOAD_TYPE = 0x78

        fun readU32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                    ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

        fun writeU32(b: ByteArray, at: Int, v: Long) {
            b[at] = (v shr 24).toByte()
            b[at + 1] = (v shr 16).toByte()
            b[at + 2] = (v shr 8).toByte()
            b[at + 3] = v.toByte()
        }
    }
}
