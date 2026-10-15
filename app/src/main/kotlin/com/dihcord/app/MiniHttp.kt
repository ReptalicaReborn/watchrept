package com.dihcord.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URLDecoder

/** Minimal, size-bounded HTTP/1.1 helpers for the watch's tiny LAN pages (token entry, captcha). */
object MiniHttp {
    class Request(val method: String, val path: String, val body: String)

    /**
     * Reads one request. Returns null when it is malformed or larger than [maxHead]/[maxBody];
     * [onTooLarge] is invoked for a body over [maxBody] so callers can answer 413.
     */
    fun readRequest(input: InputStream, maxHead: Int, maxBody: Int, onTooLarge: () -> Unit = {}): Request? {
        val head = readHead(input, maxHead) ?: return null
        val lines = head.toString(Charsets.ISO_8859_1).split("\r\n")
        val parts = lines.firstOrNull()?.split(" ") ?: return null
        if (parts.size < 2) return null
        val method = parts[0]
        val path = parts[1].substringBefore("?")
        if (method != "POST") return Request(method, path, "")
        val length = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toIntOrNull() ?: -1
        if (length !in 1..maxBody) {
            onTooLarge()
            return null
        }
        val body = readExactly(input, length) ?: return null
        return Request(method, path, String(body, Charsets.UTF_8))
    }

    /** Parses an application/x-www-form-urlencoded body. */
    fun parseForm(body: String): Map<String, String> = body.split("&").mapNotNull { kv ->
        val k = kv.substringBefore("=", "")
        if (k.isEmpty()) null else k to URLDecoder.decode(kv.substringAfter("=", ""), "UTF-8")
    }.toMap()

    /** Reads up to and including the blank line ending the headers; null if oversized or truncated. */
    fun readHead(input: InputStream, maxHead: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (out.size() < maxHead) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == end.size) return out.toByteArray()
        }
        return null
    }

    fun readExactly(input: InputStream, length: Int): ByteArray? {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) return null
            read += n
        }
        return buf
    }

    fun respond(socket: Socket, status: Int, body: String, contentType: String = "text/html; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val text = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"
            else -> "Error"
        }
        val out = socket.getOutputStream()
        out.write(
            ("HTTP/1.1 $status $text\r\nContent-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                .toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }
}
