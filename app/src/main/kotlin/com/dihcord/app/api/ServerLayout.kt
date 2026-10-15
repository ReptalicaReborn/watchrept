package com.dihcord.app.api

/** What the server grid shows: a single server or a folder of servers. */
sealed interface ServerEntry {
    data class Single(val guild: Guild) : ServerEntry
    data class Folder(val folder: GuildFolder, val guilds: List<Guild>) : ServerEntry
}

object ServerLayout {
    /**
     * Orders servers the way the Discord sidebar does, from the user's folder layout:
     * pinned servers first, then servers missing from the layout (e.g. just joined), then the layout
     * itself. Hidden servers are dropped unless [showHidden]. Without folder data it is a flat list.
     */
    fun build(
        guilds: List<Guild>,
        folders: List<GuildFolder>,
        pinnedIds: Set<String>,
        hiddenIds: Set<String>,
        showHidden: Boolean
    ): List<ServerEntry> {
        fun visible(g: Guild) = showHidden || g.id !in hiddenIds
        val byId = guilds.associateBy { it.id }

        val pinned = guilds.filter { it.id in pinnedIds && visible(it) }
        val placed = pinned.mapTo(mutableSetOf()) { it.id }

        val fromLayout = folders.mapNotNull { folder ->
            val members = folder.guildIds.mapNotNull { byId[it] }.filter { it.id !in placed && visible(it) }
            members.forEach { placed += it.id }
            when {
                members.isEmpty() -> null
                folder.isFolder -> ServerEntry.Folder(folder, members)
                else -> ServerEntry.Single(members.first())
            }
        }
        val unplaced = guilds.filter { it.id !in placed && visible(it) }

        return pinned.map { ServerEntry.Single(it) } +
            unplaced.map { ServerEntry.Single(it) } +
            fromLayout
    }

    /** Splits tiles into alternating rows of 3 and 2 for a honeycomb look on a round screen. */
    fun <T> honeycombRows(items: List<T>): List<List<T>> {
        val rows = mutableListOf<List<T>>()
        var i = 0
        var wide = true
        while (i < items.size) {
            val n = if (wide) 3 else 2
            rows += items.subList(i, minOf(i + n, items.size))
            i += n
            wide = !wide
        }
        return rows
    }
}
