package com.dihcord.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSizeTest {
    @Test
    fun `a video's thumbnail is a still picture, never the video`() {
        val video = "https://media.discordapp.net/attachments/1/2/clip.mp4?ex=aa"
        assertEquals("$video&format=webp&width=280&height=158", MediaSize.poster(video, 1920, 1080, 280))
        // Smaller than the box: not enlarged.
        assertEquals("$video&format=webp&width=160&height=90", MediaSize.poster(video, 160, 90, 280))
        assertEquals("$video&format=webp", MediaSize.poster(video, null, null, 280))
        assertEquals("https://media.discordapp.net/attachments/1/2/clip.mp4?format=webp", MediaSize.poster("https://media.discordapp.net/attachments/1/2/clip.mp4", 0, 0, 280))
    }

    @Test
    fun `only Discord's own proxy is asked for one`() {
        assertNull(MediaSize.poster("https://cdn.discordapp.com/attachments/1/2/clip.mp4", 1920, 1080, 280))
        assertNull(MediaSize.poster("https://example.com/media.discordapp.net/clip.mp4", 1920, 1080, 280))
    }
}
