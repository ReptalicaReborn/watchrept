package com.dihcord.app.screens

import com.dihcord.app.api.DiscordRepository
import com.dihcord.app.api.EmojiUse
import com.dihcord.app.api.MentionCandidate

/**
 * Emoji suggestions for ":query" in the message box, like Discord's. Unicode emoji match on their keywords, custom
 * emoji on their name; prefix matches come before substring ones and the current server's emoji before others.
 * Emoji the user can't send (no Nitro, link fallback off) are left out; ones only sendable as a link go out as one.
 */
suspend fun emojiCandidates(
    repo: DiscordRepository,
    guildId: String?,
    query: String,
    hasNitro: Boolean,
    linkFallback: Boolean,
    limit: Int = 5
): List<MentionCandidate> {
    val q = query.lowercase()
    fun score(words: List<String>): Int = when {
        words.any { it.startsWith(q) } -> 0
        words.any { it.contains(q) } -> 1
        else -> -1
    }

    val ranked = ArrayList<Pair<MentionCandidate, Int>>()
    COMMON_UNICODE_EMOJI.forEach { (char, keywords) ->
        val words = keywords.split(' ')
        val s = score(words)
        if (s >= 0) {
            ranked += MentionCandidate(
                id = char, displayName = words.first(), username = "", kind = MentionCandidate.Kind.EMOJI,
                shownText = char, wireText = char
            ) to s * 3
        }
    }
    repo.allGuildEmojis(guildId).forEach { set ->
        val here = set.guildId == guildId
        set.items.forEach { e ->
            val s = score(listOf(e.name.lowercase()))
            if (s < 0) return@forEach
            val use = EmojiUse.forEmoji(
                animated = e.animated, fromThisServer = here, hasNitro = hasNitro,
                linkFallback = linkFallback, reaction = false
            )
            if (use == EmojiUse.LOCKED) return@forEach
            ranked += MentionCandidate(
                id = e.id, displayName = e.name, username = set.guildName, kind = MentionCandidate.Kind.EMOJI,
                imageUrl = e.imageUrl, shownText = ":${e.name}:",
                wireText = if (use == EmojiUse.AS_LINK) e.linkUrl else e.insertText
            ) to s * 3 + (if (here) 1 else 2)
        }
    }
    return ranked.sortedBy { it.second }.distinctBy { it.first.shown }.take(limit).map { it.first }
}
