package com.dihcord.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMergeTest {
    private val author = DiscordUser(id = "1", username = "u", discriminator = "0", globalName = null, avatarHash = null)
    private fun msg(id: String, content: String = "") =
        DiscordMessage(id = id, channelId = "c", author = author, content = content, timestamp = "", editedTimestamp = null)
    private fun ids(list: List<DiscordMessage>?) = list?.map { it.id }

    @Test
    fun snowflakesCompareNumerically() {
        assertTrue(compareSnowflakes("999999999999999999", "1000000000000000000") < 0)
        assertTrue(compareSnowflakes("1000000000000000001", "1000000000000000000") > 0)
        assertEquals(0, compareSnowflakes("5", "5"))
    }

    @Test
    fun keepsOlderHistoryWhenRefreshing() {
        val held = listOf(msg("10"), msg("20"), msg("30"), msg("40"))
        val page = listOf(msg("30"), msg("40", "edited"), msg("50"))
        val merged = mergeLatestPage(held, page)
        assertEquals(listOf("10", "20", "30", "40", "50"), ids(merged))
        assertEquals("edited", merged!!.first { it.id == "40" }.content)
    }

    @Test
    fun dropsMessagesDeletedInsideThePage() {
        val held = listOf(msg("10"), msg("30"), msg("35"), msg("40"))
        val page = listOf(msg("30"), msg("40"))
        assertEquals(listOf("10", "30", "40"), ids(mergeLatestPage(held, page)))
    }

    @Test
    fun startsOverWhenAPageWasMissed() {
        val held = listOf(msg("10"), msg("20"))
        assertNull(mergeLatestPage(held, listOf(msg("90"), msg("100"))))
    }

    @Test
    fun keepsMessagesNewerThanThePage() {
        val held = listOf(msg("30"), msg("40"), msg("1000"))
        assertEquals(listOf("30", "40", "1000"), ids(mergeLatestPage(held, listOf(msg("30"), msg("40")))))
    }
}
