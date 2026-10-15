package com.dihcord.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLayoutTest {
    private fun g(id: String) = Guild(id = id, name = "Server $id", iconHash = null)
    private val guilds = listOf(g("1"), g("2"), g("3"), g("4"), g("5"))

    private fun ids(entries: List<ServerEntry>) = entries.map {
        when (it) {
            is ServerEntry.Single -> it.guild.id
            is ServerEntry.Folder -> "F[" + it.guilds.joinToString(",") { g -> g.id } + "]"
        }
    }

    @Test
    fun `without folder data the list is flat`() {
        val out = ServerLayout.build(guilds, emptyList(), emptySet(), emptySet(), showHidden = false)
        assertEquals(listOf("1", "2", "3", "4", "5"), ids(out))
    }

    @Test
    fun `follows the folder layout, with servers missing from it first`() {
        val folders = listOf(
            GuildFolder(id = null, name = null, color = null, guildIds = listOf("3")),
            GuildFolder(id = "77", name = "Games", color = 0x00FF00, guildIds = listOf("1", "2")),
        )
        val out = ServerLayout.build(guilds, folders, emptySet(), emptySet(), showHidden = false)
        assertEquals(listOf("4", "5", "3", "F[1,2]"), ids(out))
        assertEquals("Games", (out.last() as ServerEntry.Folder).folder.name)
    }

    @Test
    fun `pinned servers come first and are pulled out of folders`() {
        val folders = listOf(GuildFolder("77", "Games", null, listOf("1", "2")))
        val out = ServerLayout.build(guilds, folders, pinnedIds = setOf("2"), hiddenIds = emptySet(), showHidden = false)
        assertEquals(listOf("2", "3", "4", "5", "F[1]"), ids(out))
    }

    @Test
    fun `hidden servers are dropped unless shown, and empty folders disappear`() {
        val folders = listOf(GuildFolder("77", "Games", null, listOf("1", "2")))
        val hidden = setOf("1", "2")
        val out = ServerLayout.build(guilds, folders, emptySet(), hidden, showHidden = false)
        assertEquals(listOf("3", "4", "5"), ids(out))
        val shown = ServerLayout.build(guilds, folders, emptySet(), hidden, showHidden = true)
        assertTrue("F[1,2]" in ids(shown))
    }

    @Test
    fun `folders referencing servers the user left are ignored`() {
        val folders = listOf(GuildFolder(null, null, null, listOf("999")))
        val out = ServerLayout.build(guilds, folders, emptySet(), emptySet(), showHidden = false)
        assertEquals(5, out.size)
    }

    @Test
    fun `honeycomb rows alternate three and two`() {
        val rows = ServerLayout.honeycombRows((1..8).toList())
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5), listOf(6, 7, 8)), rows)
        assertEquals(listOf(listOf(1)), ServerLayout.honeycombRows(listOf(1)))
        assertEquals(emptyList<List<Int>>(), ServerLayout.honeycombRows(emptyList<Int>()))
    }

    // ---- custom order ----

    private fun keys(entries: List<ServerEntry>) = entries.map { it.orderKey }

    private val flat = guilds.map { ServerEntry.Single(it) }

    @Test
    fun `no custom order leaves the layout alone`() {
        assertEquals(keys(flat), keys(ServerLayout.applyCustomOrder(flat, emptyList(), emptySet())))
    }

    @Test
    fun `custom order is applied and new servers go first`() {
        val ordered = ServerLayout.applyCustomOrder(flat, listOf("3", "1", "2"), emptySet())
        // 4 and 5 aren't in the order yet (just joined): they lead, in their original order.
        assertEquals(listOf("4", "5", "3", "1", "2"), keys(ordered))
    }

    @Test
    fun `pinned servers stay in front and are ordered among themselves`() {
        val ordered = ServerLayout.applyCustomOrder(flat, listOf("1", "2", "3", "4", "5"), pinnedIds = setOf("5", "4"))
        assertEquals(listOf("4", "5", "1", "2", "3"), keys(ordered))
    }

    @Test
    fun `folders sort by their own key`() {
        val folder = ServerEntry.Folder(GuildFolder("77", "Games", null, listOf("1", "2")), listOf(g("1"), g("2")))
        val entries = listOf(ServerEntry.Single(g("3")), folder)
        assertEquals(listOf("f:77", "3"), keys(ServerLayout.applyCustomOrder(entries, listOf("f:77", "3"), emptySet())))
    }

    @Test
    fun `moving swaps places and remembers hidden-context keys`() {
        val displayed = listOf("1", "2", "3")
        assertEquals(listOf("2", "1", "3", "9"), ServerLayout.moveInOrder(displayed, "2", -1, stored = listOf("9")))
        assertEquals(listOf("1", "3", "2"), ServerLayout.moveInOrder(displayed, "2", 1, stored = emptyList()))
    }

    @Test
    fun `moving past the ends or an unknown key changes nothing`() {
        val displayed = listOf("1", "2", "3")
        val stored = listOf("x")
        assertEquals(stored, ServerLayout.moveInOrder(displayed, "1", -1, stored))
        assertEquals(stored, ServerLayout.moveInOrder(displayed, "3", 1, stored))
        assertEquals(stored, ServerLayout.moveInOrder(displayed, "nope", 1, stored))
    }

    @Test
    fun `moves cannot cross the pinned boundary`() {
        // 1 is pinned; 2 and 3 are not.
        val entries = listOf(ServerEntry.Single(g("1")), ServerEntry.Single(g("2")), ServerEntry.Single(g("3")))
        val pinned = setOf("1")
        assertEquals(false, ServerLayout.canMove(entries, 1, -1, pinned)) // 2 can't go above pinned 1
        assertEquals(true, ServerLayout.canMove(entries, 1, 1, pinned))   // 2 can go below... to 3
        assertEquals(false, ServerLayout.canMove(entries, 0, -1, pinned)) // nothing above the first
        assertEquals(false, ServerLayout.canMove(entries, 2, 1, pinned))  // nothing below the last
    }
}
