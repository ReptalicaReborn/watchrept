package com.dihcord.app.voice

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * The UDP side of a voice connection: IP discovery, keep-alives and the media packets themselves.
 * [onPacket] runs on the receive thread.
 */
class VoiceUdp(
    host: String,
    port: Int,
    /** Wi-Fi/LTE network to send over; watches otherwise default to the phone's Bluetooth link, which drops UDP. */
    network: android.net.Network?,
    private val onPacket: (ByteArray) -> Unit,
) {
    private val remote = InetSocketAddress(host, port)
    private val socket = DatagramSocket().apply {
        network?.let { runCatching { it.bindSocket(this) } }
        connect(remote)
    }
    @Volatile private var closed = false
    private var receiver: Thread? = null
    private var keepAlive: Thread? = null

    /** Our public address/port as Discord sees it (needed for SELECT_PROTOCOL); blocks for up to ~6 s. */
    fun discover(ssrc: Long): Pair<String, Int> {
        val request = ByteArray(74)
        request[1] = 1 // type: request
        request[3] = 70 // length
        RtpCrypto.writeU32(request, 4, ssrc)
        val buf = ByteArray(74)
        socket.soTimeout = 2_000
        repeat(3) {
            socket.send(DatagramPacket(request, request.size))
            try {
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    if (p.length >= 74 && buf[1].toInt() == 2) {
                        val end = (8 until 72).firstOrNull { buf[it].toInt() == 0 } ?: 72
                        val ip = String(buf, 8, end - 8, Charsets.UTF_8)
                        val port = ((buf[72].toInt() and 0xFF) shl 8) or (buf[73].toInt() and 0xFF)
                        return ip to port
                    }
                }
            } catch (_: SocketTimeoutException) {
                // try again
            }
        }
        throw SocketException("No reply to UDP IP discovery (is the watch on Wi-Fi or LTE?)")
    }

    fun start() {
        socket.soTimeout = 0
        receiver = Thread({
            val buf = ByteArray(2048)
            while (!closed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    if (p.length > 12) onPacket(buf.copyOf(p.length))
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "receive failed", e)
                    if (socket.isClosed) break
                }
            }
        }, "voice-udp-rx").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }

        keepAlive = Thread({
            var counter = 0L
            val packet = ByteArray(8)
            while (!closed) {
                RtpCrypto.writeU32(packet, 0, counter++ and 0xFFFFFFFFL)
                send(packet)
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
            }
        }, "voice-udp-keepalive").apply { isDaemon = true; start() }
    }

    fun send(data: ByteArray) {
        if (closed) return
        try {
            socket.send(DatagramPacket(data, data.size))
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "send failed", e)
        }
    }

    fun close() {
        closed = true
        keepAlive?.interrupt()
        runCatching { socket.close() }
    }

    private companion object {
        const val TAG = "VoiceUdp"
    }
}
