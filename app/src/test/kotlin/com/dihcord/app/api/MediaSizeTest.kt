package com.dihcord.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSizeTest {
    private val photo = "https://media.discordapp.net/attachments/1/2/IMG_0412.jpg?ex=aa&is=bb&hm=cc"

    @Test
    fun `a big photo is asked for at the size shown`() {
        assertEquals("$photo&format=webp&width=240&height=180", MediaSize.fit(photo, 4000, 3000, 240))
        // Portrait: the longer side is the height.
        assertEquals("$photo&format=webp&width=180&height=240", MediaSize.fit(photo, 3000, 4000, 240))
    }

    @Test
    fun `small pictures and unknown sizes are left alone`() {
        assertEquals(photo, MediaSize.fit(photo, 200, 150, 240))
        assertEquals(photo, MediaSize.fit(photo, null, null, 240))
        assertEquals(photo, MediaSize.fit(photo, 0, 3000, 240))
    }

    @Test
    fun `animated pictures keep their format`() {
        val gif = "https://media.discordapp.net/attachments/1/2/party.GIF?ex=aa"
        assertEquals("$gif&width=240&height=120", MediaSize.fit(gif, 800, 400, 240))
        val webp = "https://media.discordapp.net/attachments/1/2/sticker.webp"
        assertEquals("$webp?width=240&height=240", MediaSize.fit(webp, 512, 512, 240))
    }

    @Test
    fun `only Discord's resizing hosts are asked`() {
        val cdn = "https://cdn.discordapp.com/attachments/1/2/IMG_0412.jpg?ex=aa"
        assertEquals(cdn, MediaSize.fit(cdn, 4000, 3000, 240))
        val elsewhere = "https://example.com/media.discordapp.net/a.jpg"
        assertEquals(elsewhere, MediaSize.fit(elsewhere, 4000, 3000, 240))
        val ext = "https://images-ext-1.discordapp.net/external/sig/https/example.com/a.png"
        assertEquals("$ext?format=webp&width=320&height=180", MediaSize.fit(ext, 1280, 720, 320))
    }

    @Test
    fun `a video's thumbnail is a still picture, never the video`() {
        val video = "https://media.discordapp.net/attachments/1/2/clip.mp4?ex=aa"
        assertEquals("$video&format=webp&width=280&height=158", MediaSize.poster(video, 1920, 1080, 280))
        // Smaller than the box: not enlarged.
        assertEquals("$video&format=webp&width=160&height=90", MediaSize.poster(video, 160, 90, 280))
        assertEquals("$video&format=webp", MediaSize.poster(video, null, null, 280))
        assertNull(MediaSize.poster("https://cdn.discordapp.com/attachments/1/2/clip.mp4", 1920, 1080, 280))
    }

    @Test
    fun `an embed's picture comes with Discord's copy and its size`() {
        val embed = Embed.fromJson(
            JSONObject(
                """{"type":"article","title":"News","thumbnail":{"url":"https://example.com/big.jpg",
                    "proxy_url":"https://images-ext-1.discordapp.net/external/sig/https/example.com/big.jpg","width":2400,"height":1200}}"""
            )
        )
        assertEquals("https://example.com/big.jpg", embed.displayImageUrl)
        assertEquals(
            "https://images-ext-1.discordapp.net/external/sig/https/example.com/big.jpg?format=webp&width=320&height=160",
            MediaSize.fit(embed.displayImageProxyUrl!!, embed.displayImageWidth, embed.displayImageHeight, 320)
        )
        // No proxy copy: the caller falls back to the picture's own address.
        assertNull(Embed.fromJson(JSONObject("""{"type":"rich","image":{"url":"https://example.com/a.png"}}""")).displayImageProxyUrl)
    }
}
