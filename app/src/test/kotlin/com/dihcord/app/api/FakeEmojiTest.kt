package com.dihcord.app.api

import com.dihcord.app.api.ContentParser.Part
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeEmojiTest {
    @Test
    fun `vencord fake nitro link becomes an inline emoji`() {
        val parts = ContentParser.parse(
            "lol [Omegalul](https://cdn.discordapp.com/emojis/518767109346558002.webp?size=48&name=Omegalul&lossless=true)"
        )
        assertEquals(
            listOf(
                Part.PlainText("lol "),
                Part.CustomEmoji("Omegalul", "https://cdn.discordapp.com/emojis/518767109346558002.webp?size=64", false)
            ),
            parts
        )
    }

    @Test
    fun `animated fake emoji keeps animation`() {
        val part = ContentParser.parse(
            "[pepe](<https://cdn.discordapp.com/emojis/123.gif?size=48&animated=true&name=pepe>)"
        ).single() as Part.CustomEmoji
        assertEquals("pepe", part.name)
        assertEquals(true, part.animated)
    }

    @Test
    fun `bare emoji url becomes an emoji`() {
        val part = ContentParser.parse("https://cdn.discordapp.com/emojis/42.webp?size=48&name=kek").single()
        assertEquals(Part.CustomEmoji("kek", "https://cdn.discordapp.com/emojis/42.webp?size=64", false), part)
    }

    @Test
    fun `ordinary links stay links`() {
        assertEquals(listOf(Part.Link("https://example.com/a")), ContentParser.parse("https://example.com/a"))
    }
}
