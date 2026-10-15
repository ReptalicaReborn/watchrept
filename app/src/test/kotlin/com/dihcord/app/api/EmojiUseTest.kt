package com.dihcord.app.api

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiUseTest {
    private fun emoji(animated: Boolean, here: Boolean, nitro: Boolean, link: Boolean = true, reaction: Boolean = false) =
        EmojiUse.forEmoji(animated, here, nitro, link, reaction)

    @Test
    fun `static emoji from this server is native without nitro`() {
        assertEquals(EmojiUse.NATIVE, emoji(animated = false, here = true, nitro = false))
    }

    @Test
    fun `other-server or animated emoji fall back to a link without nitro`() {
        assertEquals(EmojiUse.AS_LINK, emoji(animated = false, here = false, nitro = false))
        assertEquals(EmojiUse.AS_LINK, emoji(animated = true, here = true, nitro = false))
    }

    @Test
    fun `locked when the link fallback is off, and always for reactions`() {
        assertEquals(EmojiUse.LOCKED, emoji(animated = true, here = false, nitro = false, link = false))
        assertEquals(EmojiUse.LOCKED, emoji(animated = false, here = false, nitro = false, reaction = true))
    }

    @Test
    fun `nitro can use anything natively`() {
        assertEquals(EmojiUse.NATIVE, emoji(animated = true, here = false, nitro = true))
        assertEquals(EmojiUse.NATIVE, emoji(animated = true, here = false, nitro = true, reaction = true))
    }

    @Test
    fun `stickers from other servers need nitro, lottie can't be linked`() {
        val png = StickerItem("1", "a", 1)
        val lottie = StickerItem("2", "b", 3)
        assertEquals(EmojiUse.NATIVE, EmojiUse.forSticker(png, fromThisServer = true, hasNitro = false, linkFallback = true))
        assertEquals(EmojiUse.AS_LINK, EmojiUse.forSticker(png, fromThisServer = false, hasNitro = false, linkFallback = true))
        assertEquals(EmojiUse.LOCKED, EmojiUse.forSticker(lottie, fromThisServer = false, hasNitro = false, linkFallback = true))
        assertEquals(EmojiUse.NATIVE, EmojiUse.forSticker(lottie, fromThisServer = false, hasNitro = true, linkFallback = false))
    }
}
