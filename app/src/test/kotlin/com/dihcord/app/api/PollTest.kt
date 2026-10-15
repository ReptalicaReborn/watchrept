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
}
