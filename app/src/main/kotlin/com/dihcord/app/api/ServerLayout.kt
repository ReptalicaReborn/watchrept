package com.dihcord.app.api

/** What the server grid shows: a single server or a folder of servers. */
sealed interface ServerEntry {
    data class Single(val guild: Guild) : ServerEntry
    data class Folder(val folder: GuildFolder, val guilds: List<Guild>) : ServerEntry
}

/** Stable key identifying an entry in the user's custom order: the server id, or "f:" + the folder key. */
val ServerEntry.orderKey: String
    get() = when (this) {
        is ServerEntry.Single -> guild.id
        is ServerEntry.Folder -> "f:" + folder.key
    }

object ServerLayout {
    /**
     * Applies the user's own order on top of [entries]. Pinned servers stay in front (ordered among themselves);
     * anything not in [order] yet (e.g. a newly joined server) goes first, in its original order.
     */
    fun applyCustomOrder(entries: List<ServerEntry>, order: List<String>, pinnedIds: Set<String>): List<ServerEntry> {
        if (order.isEmpty()) return entries
        val index = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { i, key -> index.putIfAbsent(key, i) }
        fun rank(e: ServerEntry) = index[e.orderKey] ?: -1
        val (pinned, rest) = entries.partition { it is ServerEntry.Single && it.guild.id in pinnedIds }
        return pinned.sortedBy(::rank) + rest.sortedBy(::rank)
    }

    /**
     * The stored order after dragging [fromKey] onto [toKey]: it takes that spot and the entries in between shift
     * by one. Unchanged if either isn't on screen or they are the same.
     */
    fun moveTo(displayedKeys: List<String>, fromKey: String, toKey: String, stored: List<String>): List<String> {
        val from = displayedKeys.indexOf(fromKey)
        val to = displayedKeys.indexOf(toKey)
        if (from < 0 || to < 0 || from == to) return stored
        val moved = displayedKeys.toMutableList().apply {
            removeAt(from)
            add(to, fromKey)
        }
        return moved + stored.filter { it !in moved }
    }

    /**
     * The stored order after moving [key] by [delta] places within the currently [displayedKeys].
     * Keys not on screen keep their place behind the displayed ones; unchanged if the move isn't possible.
     */
    fun moveInOrder(displayedKeys: List<String>, key: String, delta: Int, stored: List<String>): List<String> {
        val from = displayedKeys.indexOf(key)
        if (from < 0) return stored
        val to = (from + delta).coerceIn(0, displayedKeys.lastIndex)
        if (to == from) return stored
        val moved = displayedKeys.toMutableList().apply {
            removeAt(from)
            add(to, key)
        }
        return moved + stored.filter { it !in moved }
    }

    /** Whether entry [index] can move [delta] places without crossing between pinned and unpinned servers. */
    fun canMove(entries: List<ServerEntry>, index: Int, delta: Int, pinnedIds: Set<String>): Boolean {
        val neighbour = entries.getOrNull(index + delta) ?: return false
        val me = entries.getOrNull(index) ?: return false
        fun pinned(e: ServerEntry) = e is ServerEntry.Single && e.guild.id in pinnedIds
        return pinned(me) == pinned(neighbour)
    }

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
