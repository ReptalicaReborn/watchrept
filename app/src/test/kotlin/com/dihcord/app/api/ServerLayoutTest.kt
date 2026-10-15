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
}
