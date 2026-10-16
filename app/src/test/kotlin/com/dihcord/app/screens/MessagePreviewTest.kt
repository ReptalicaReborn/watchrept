package com.dihcord.app.screens

import com.dihcord.app.api.Attachment
import com.dihcord.app.api.DiscordMessage
import com.dihcord.app.api.DiscordUser
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePreviewTest {
    private val me = DiscordUser("1", "willow", "0", "Willow", null)
    private val kai = DiscordUser("2", "kai", "0", "Kai", null)

    private fun msg(content: String, author: DiscordUser = kai, attachments: List<Attachment> = emptyList()) =
        DiscordMessage("10", "c", author, content, "2026-01-01T00:00:00Z", null, attachments = attachments, mentionedUsers = listOf(me))

    @Test fun spoilersStayHidden() = assertEquals("the ending is ▒▒▒ wow", messagePreview(msg("the ending is ||he was the ghost|| wow"), null))
    @Test fun multiLineSpoiler() = assertEquals("▒▒▒", messagePreview(msg("||line one\nline two||"), null))
    @Test fun mentionsAndMarkdown() = assertEquals("@Willow you coming? ranked", messagePreview(msg("<@1> you coming? **ranked**"), null))
    @Test fun customEmoji() = assertEquals("gg :pog:", messagePreview(msg("gg <:pog:123456>"), null))
    @Test fun ownMessages() = assertEquals("You: ok", messagePreview(msg("ok", author = me), "1"))
    @Test fun photoOnly() = assertEquals(
        "Photo",
        messagePreview(msg("", attachments = listOf(Attachment("a", "x.jpg", "", "", "image/jpeg", 1, 1))), null)
    )
    @Test fun quotesAndHeadingsFlatten() = assertEquals("quoted big", messagePreview(msg("> quoted\n# big"), null))
}
