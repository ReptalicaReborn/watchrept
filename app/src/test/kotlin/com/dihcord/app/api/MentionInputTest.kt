package com.dihcord.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionInputTest {
    private val anna = MentionCandidate("1", "Anna", "anna_k")
    private val ann = MentionCandidate("2", "Ann", "annie")
    private val bob = MentionCandidate("3", "Bob Smith", "bobby")

    @Test
    fun `detects a mention being typed at the end`() {
        assertEquals(MentionInput.Active('@', "an"), MentionInput.active("hi @an"))
        assertEquals(MentionInput.Active('@', ""), MentionInput.active("@"))
        assertNull(MentionInput.active("hi @anna "))   // finished
        assertNull(MentionInput.active("mail@example")) // not after a space
    }

    @Test
    fun `detects a channel being typed`() {
        assertEquals(MentionInput.Active('#', "gen"), MentionInput.active("see #gen"))
        assertNull(MentionInput.active("issue#12"))
    }

    @Test
    fun `detects an emoji being typed after two characters`() {
        assertEquals(MentionInput.Active(':', "so"), MentionInput.active("hi :so"))
        assertNull(MentionInput.active("hi :s"))      // too short
        assertNull(MentionInput.active("hi :)"))      // emoticon
        assertNull(MentionInput.active("hi :sob:"))   // finished
        assertNull(MentionInput.active("time 12:30")) // not after a space
    }

    @Test
    fun `emoji complete with their shown form and go out in wire form`() {
        val e = MentionCandidate(
            "1", "blobwave", "Server", MentionCandidate.Kind.EMOJI,
            shownText = ":blobwave:", wireText = "<:blobwave:1>"
        )
        val text = MentionInput.complete("hey :blob", e)
        assertEquals("hey :blobwave: ", text)
        assertEquals("hey <:blobwave:1>", MentionInput.toWire(text.trim(), mapOf(e.shown to e.wire)))
    }

    @Test
    fun `channels complete and go out as channel links`() {
        val general = MentionCandidate("9", "general", "Text", MentionCandidate.Kind.CHANNEL)
        assertEquals("see #general ", MentionInput.complete("see #gen", general))
        assertEquals("see <#9> now", MentionInput.toWire("see #general now", mapOf(general.shown to general.wire)))
    }

    @Test
    fun `completing replaces the partial name`() {
        assertEquals("hi @Bob Smith ", MentionInput.complete("hi @bo", bob))
        assertEquals("@Anna ", MentionInput.complete("@a", anna))
    }

    @Test
    fun `wire format uses ids and longest names first`() {
        val picked = mapOf("@Ann" to "<@2>", "@Anna" to "<@1>", "@Bob Smith" to "<@3>")
        assertEquals("<@1> and <@2> and <@3>!", MentionInput.toWire("@Anna and @Ann and @Bob Smith!", picked))
    }

    @Test
    fun `ranking prefers prefix matches and dedupes`() {
        val ranked = MentionInput.rank(listOf(bob, anna, ann, anna), "ann")
        assertEquals(listOf("1", "2"), ranked.map { it.id })
        assertEquals(listOf("3"), MentionInput.rank(listOf(anna, bob), "smith").map { it.id })
    }
}
