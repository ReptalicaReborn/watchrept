package com.dihcord.app.api

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Unpacks Discord's "zlib-stream" gateway compression: the whole connection is one zlib stream, and a message is
 * complete when the bytes received so far end in a sync flush (00 00 FF FF). One per connection - a new
 * connection starts a new stream.
 */
internal class GatewayInflater {
    private val inflater = Inflater()
    private val pending = ByteArrayOutputStream()
    private val buffer = ByteArray(16 * 1024)
    private var closed = false

    /**
     * Takes the next binary frame; returns the message it completes, or null when more frames are needed.
     * Throws [DataFormatException] when the bytes aren't the stream we expect.
     */
    @Synchronized
    fun feed(frame: ByteArray): String? {
        if (closed) return null
        pending.write(frame)
        if (!endsWithFlush()) return null
        val input = pending.toByteArray()
        pending.reset()
        inflater.setInput(input)
        val out = ByteArrayOutputStream(input.size * 6)
        while (true) {
            val n = inflater.inflate(buffer)
            if (n > 0) out.write(buffer, 0, n)
            else if (inflater.needsDictionary()) throw DataFormatException("unexpected dictionary")
            else if (inflater.needsInput() || inflater.finished()) break
        }
        return out.toString("UTF-8")
    }

    private fun endsWithFlush(): Boolean {
        if (pending.size() < 4) return false
        val bytes = pending.toByteArray()
        val n = bytes.size
        return bytes[n - 4] == 0.toByte() && bytes[n - 3] == 0.toByte() && bytes[n - 2] == 0xFF.toByte() && bytes[n - 1] == 0xFF.toByte()
    }

    /** Frees the native buffers; frames arriving afterwards are ignored. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        inflater.end()
    }
}
