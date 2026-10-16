package com.dihcord.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class GifsTest {
    // --- tiny protobuf writer for building test settings ---
    private fun ByteArrayOutputStream.varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7F.inv().toLong() == 0L) { write(x.toInt()); return }
            write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
    }
    private fun msg(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()
    private fun ByteArrayOutputStream.bytes(field: Int, b: ByteArray) { varint((field shl 3 or 2).toLong()); varint(b.size.toLong()); write(b) }
    private fun ByteArrayOutputStream.str(field: Int, s: String) = bytes(field, s.toByteArray())
    private fun ByteArrayOutputStream.num(field: Int, v: Long) { varint((field shl 3).toLong()); varint(v) }

    private fun favorite(url: String, src: String, order: Long) = msg {
        str(1, url)
        bytes(2, msg { num(1, 2); str(2, src); num(3, 220); num(4, 124); num(5, order) })
    }

    @Test
    fun `favourites are read from the settings proto, newest first`() {
        val settings = msg {
            bytes(1, msg { num(1, 7) }) // versions
            bytes(2, msg {
                bytes(1, favorite("https://tenor.com/view/old-123", "https://media.tenor.com/abcDEF12345AAAPo/old.mp4", 1))
                bytes(1, favorite("https://tenor.com/view/new-456", "https://media.tenor.com/xyzXYZ98765AAAPo/new.mp4", 5))
                num(2, 1) // hide_tooltip
            })
            bytes(3, msg { }) // favorite stickers
        }
        val gifs = Gifs.parseFavorites(settings)
        assertEquals(listOf("https://tenor.com/view/new-456", "https://tenor.com/view/old-123"), gifs.map { it.url })
        assertEquals("https://media.tenor.com/xyzXYZ98765AAAAM/new.gif", gifs[0].preview)
        assertEquals(220, gifs[0].width)
    }

    @Test
    fun `garbage settings give no favourites`() {
        assertEquals(emptyList<Gif>(), Gifs.parseFavorites(byteArrayOf(0x12, 0x7F, 0x01)))
    }

    @Test
    fun `search results use a small gif preview`() {
        val body = """[{"id":"1","title":"cat","url":"https://tenor.com/view/cat-1","src":"https://media.tenor.com/catID000000AAAPo/cat.mp4","gif_src":"https://media.tenor.com/catID000000AAAAC/cat.gif","width":498,"height":280,"preview":"https://media.tenor.com/catID000000AAAAe/cat.png"}]"""
        val gif = Gifs.parseResults(body).single()
        assertEquals("https://tenor.com/view/cat-1", gif.url)
        assertEquals("https://media.tenor.com/catID000000AAAAM/cat.gif", gif.preview)
        assertEquals("https://media.tenor.com/catID000000AAAAC/cat.gif", gif.fallback)
    }

    @Test
    fun `giphy and unknown sources`() {
        assertEquals(
            "https://media.giphy.com/media/abc123/200w.gif",
            Gifs.smallGif("https://media.giphy.com/media/abc123/giphy.mp4")
        )
        assertNull(Gifs.smallGif("https://example.com/video.mp4"))
    }
}
