package com.dihcord.app.api

/**
 * Someone to @mention, a channel to #link or an emoji for ":name". [username] is the secondary line (a channel's
 * category, an emoji's server). Emoji carry their own [shownText]/[wireText] and, for custom ones, an [imageUrl].
 */
data class MentionCandidate(
    val id: String,
    val displayName: String,
    val username: String,
    val kind: Kind = Kind.USER,
    val imageUrl: String? = null,
    val shownText: String? = null,
    val wireText: String? = null,
) {
    enum class Kind { USER, CHANNEL, EMOJI }

    /** What the message box shows, e.g. "@Anna", "#general" or ":blobwave:". */
    val shown: String
        get() = shownText ?: ((if (kind == Kind.CHANNEL) "#" else "@") + displayName)

    /** What Discord receives: `<@id>`, `<#id>` or `<:name:id>`. */
    val wire: String
        get() = wireText ?: if (kind == Kind.CHANNEL) "<#$id>" else "<@$id>"
}

/**
 * Mention autocomplete helpers. While typing, the input shows readable "@Name" / "#channel"; [toWire] turns those
 * back into Discord's `<@id>` / `<#id>` syntax when sending.
 */
object MentionInput {
    /** A mention being typed: [trigger] is '@' (people), '#' (channels) or ':' (emoji). */
    data class Active(val trigger: Char, val query: String)

    // "@partial", "#partial" or ":partial" at the very end of the text, at the start or after whitespace.
    private val ACTIVE = Regex("""(^|\s)([@#:])([^\s@#:]{0,32})$""")

    /** The mention being typed at the end of [text], or null. Emoji need two characters, like Discord (so ":)" isn't one). */
    fun active(text: String): Active? =
        ACTIVE.find(text)
            ?.let { Active(it.groupValues[2].single(), it.groupValues[3]) }
            ?.takeIf { it.trigger != ':' || it.query.length >= 2 }

    /** Replaces the trailing "@partial"/"#partial" with the candidate's shown form plus a space. */
    fun complete(text: String, candidate: MentionCandidate): String {
        val m = ACTIVE.find(text) ?: return text
        val atIndex = m.range.first + m.groupValues[1].length
        return text.substring(0, atIndex) + candidate.shown + " "
    }

    /** Swaps each picked "@Name"/"#channel" for its wire form (longest first so "@Ann" can't eat "@Anna"). */
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
