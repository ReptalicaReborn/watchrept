package com.dihcord.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollTest {
    private val json = JSONObject(
        """
        {"question":{"text":"Pizza?"},
         "answers":[{"answer_id":1,"poll_media":{"text":"Yes"}},
                    {"answer_id":2,"poll_media":{"text":"No","emoji":{"id":null,"name":"👎"}}}],
         "expiry":"2020-01-01T00:00:00+00:00","allow_multiselect":false,
         "results":{"is_finalized":false,"answer_counts":[{"id":1,"count":3,"me_voted":true}]}}
        """
    )

    @Test
    fun `parses answers and results`() {
        val poll = Poll.fromJson(json)
        assertEquals("Pizza?", poll.question)
        assertEquals(listOf("Yes", "No"), poll.answers.map { it.text })
        assertEquals(3, poll.totalVotes)
        assertTrue(poll.iVoted)
        assertEquals("👎", poll.answers[1].emoji?.name)
        assertTrue(poll.isClosed()) // expiry in the past
    }

    @Test
    fun `tapping toggles or replaces the vote`() {
        val poll = Poll.fromJson(json)
        assertEquals(emptyList<Int>(), poll.votesAfterTap(1))   // un-vote
        assertEquals(listOf(2), poll.votesAfterTap(2))          // single choice replaces
        assertEquals(listOf(1, 2), poll.copy(multiselect = true).votesAfterTap(2))
    }

    @Test
    fun `vote events update counts and my vote`() {
        val poll = Poll.fromJson(json).withVote(1, add = false, mine = true).withVote(2, add = true, mine = false)
        assertEquals(2, poll.answers[0].count)
        assertFalse(poll.answers[0].meVoted)
        assertEquals(1, poll.answers[1].count)
        assertFalse(poll.answers[1].meVoted)
    }

    @Test
    fun `percentages and leaders`() {
        val poll = Poll.fromJson(json).withVote(2, add = true, mine = false)
        assertEquals(75, poll.percentOf(poll.answers[0]))
        assertEquals(25, poll.percentOf(poll.answers[1]))
        assertEquals(setOf(1), poll.leaders)
        // A tie has more than one leader; no votes has none.
        assertEquals(setOf(1, 2), poll.withVote(2, true, false).withVote(2, true, false).leaders)
        val empty = poll.copy(answers = poll.answers.map { it.copy(count = 0) })
        assertEquals(emptySet<Int>(), empty.leaders)
        assertEquals(0, empty.percentOf(empty.answers[0]))
    }

    @Test
    fun `my vote shows at once and its echo changes nothing`() {
        val open = Poll.fromJson(json).copy(expiry = null)            // 1: 3 votes (mine), 2: none
        val moved = open.withMyVotes(open.votesAfterTap(2))           // single choice: my vote moves to 2
        assertEquals(listOf(2, 1), moved.answers.map { it.count })
        assertEquals(listOf(false, true), moved.answers.map { it.meVoted })
        // Discord then reports the same change: it must not be counted twice.
        val echoed = moved.withVote(1, add = false, mine = true).withVote(2, add = true, mine = true)
        assertEquals(moved, echoed)
        // Someone else's vote for the same answer still counts.
        assertEquals(2, echoed.withVote(2, add = true, mine = false).answers[1].count)
        // A failed request puts the old picks back exactly.
        assertEquals(open, moved.withMyVotes(listOf(1)))
    }

    private fun system(type: Int, content: String = "", extra: String = "") = DiscordMessage.fromJson(
        JSONObject(
            """{"id":"1","channel_id":"2","type":$type,"content":"$content","timestamp":"2024-01-01T00:00:00+00:00",
                "author":{"id":"9","username":"anna","discriminator":"0","global_name":"Anna"}$extra}"""
        )
    )

    @Test
    fun `system messages read as one line`() {
        assertEquals("Annie joined the server", system(7).systemText("Annie"))
        assertEquals("Anna pinned a message", system(6).systemText("Anna"))
        assertEquals("Anna boosted the server. It reached Level 2!", system(10).systemText("Anna"))
        assertEquals("Anna started a thread: plans", system(18, "plans").systemText("Anna"))
        assertTrue(system(7).isSystem)
        // Ordinary messages and replies keep their bubble.
        assertEquals(null, system(0, "hello").systemText("Anna"))
        assertEquals(null, system(19, "hello").systemText("Anna"))
        assertFalse(system(19).isSystem)
    }

    @Test
    fun `poll result message names the winner`() {
        fun fields(vararg f: Pair<String, String>) = f.joinToString(",") { """{"name":"${it.first}","value":"${it.second}"}""" }
        val won = system(46, extra = ""","embeds":[{"type":"poll_result","fields":[${fields(
            "poll_question_text" to "Pizza?", "victor_answer_text" to "Yes", "victor_answer_votes" to "3", "total_votes" to "4"
        )}]}]""")
        assertEquals("Anna's poll \u201CPizza?\u201D ended \u00B7 Yes won \u00B7 75% of 4 votes", won.systemText("Anna"))
        val tie = system(46, extra = ""","embeds":[{"type":"poll_result","fields":[${fields(
            "poll_question_text" to "Pizza?", "victor_answer_votes" to "2", "total_votes" to "4"
        )}]}]""")
        assertEquals("Anna's poll \u201CPizza?\u201D ended \u00B7 It's a tie \u00B7 4 votes", tie.systemText("Anna"))
        val none = system(46, extra = ""","embeds":[{"type":"poll_result","fields":[${fields("poll_question_text" to "Pizza?", "total_votes" to "0")}]}]""")
        assertEquals("Anna's poll \u201CPizza?\u201D ended \u00B7 No votes", none.systemText("Anna"))
    }
}
