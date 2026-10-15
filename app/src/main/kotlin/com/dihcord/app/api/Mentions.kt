package com.dihcord.app.api

/** Someone who can be @mentioned. */
data class MentionCandidate(val id: String, val displayName: String, val username: String)

/**
 * Mention autocomplete helpers. While typing, the input shows readable "@Name"; [toWire] turns those back into
 * Discord's `<@id>` syntax when sending.
 */
object MentionInput {
    // "@partial" at the very end of the text, at the start or after whitespace.
    private val ACTIVE = Regex("""(^|\s)@([^\s@]{0,32})$""")

    /** The partial name after a trailing "@", or null when the user isn't typing a mention. */
    fun activeQuery(text: String): String? = ACTIVE.find(text)?.groupValues?.get(2)

    /** Replaces the trailing "@partial" with "@Name ". */
    fun complete(text: String, candidate: MentionCandidate): String {
        val m = ACTIVE.find(text) ?: return text
        val atIndex = m.range.first + m.groupValues[1].length
        return text.substring(0, atIndex) + "@" + candidate.displayName + " "
    }

    /** Swaps each picked "@Name" for its `<@id>` (longest names first so "@Ann" can't eat "@Anna"). */
    fun toWire(text: String, picked: Map<String, String>): String =
        picked.entries.sortedByDescending { it.key.length }
            .fold(text) { acc, (shown, wire) -> acc.replace(shown, wire) }

    /** Ranks [candidates] for [query]: prefix matches first, then substring; case-insensitive. */
    fun rank(candidates: List<MentionCandidate>, query: String, limit: Int = 5): List<MentionCandidate> {
        val q = query.lowercase()
        fun score(c: MentionCandidate): Int {
            val d = c.displayName.lowercase()
            val u = c.username.lowercase()
            return when {
                q.isEmpty() -> 2
                d.startsWith(q) || u.startsWith(q) -> 0
                d.contains(q) || u.contains(q) -> 1
                else -> -1
            }
        }
        return candidates.distinctBy { it.id }
            .map { it to score(it) }
            .filter { it.second >= 0 }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
    }
}
