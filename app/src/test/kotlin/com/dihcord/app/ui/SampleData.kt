package com.dihcord.app.ui

import com.dihcord.app.api.CategoryGroup
import com.dihcord.app.api.Channel
import com.dihcord.app.api.ChannelType
import com.dihcord.app.api.ChannelUnreadState
import com.dihcord.app.api.DiscordMessage
import com.dihcord.app.api.DiscordRepository
import com.dihcord.app.api.DiscordUser
import com.dihcord.app.api.Embed
import com.dihcord.app.api.Guild
import com.dihcord.app.api.OnlineStatus
import com.dihcord.app.api.Reaction
import com.dihcord.app.api.ReactionEmoji
import com.dihcord.app.api.UserPresence
import com.dihcord.app.api.VoiceState

/**
 * A believable account for the screenshot tests: a few friends, two servers with categories, voice
 * and unread channels, and chats with replies, reactions and mentions. Ids are real-looking snowflakes
 * made from "minutes ago", so the times shown ("2m", "1h") come out right.
 */
object SampleData {
    private val now = System.currentTimeMillis()

    /** A snowflake for something created [mins] minutes ago (plus [n] to keep ids apart). */
    fun id(mins: Int, n: Int = 0): String =
        (((now - mins * 60_000L - 1_420_070_400_000L) shl 22) + n).toString()

    private fun ts(mins: Int) = java.time.Instant.ofEpochMilli(now - mins * 60_000L).toString()

    fun user(id: String, name: String, username: String = name.lowercase().replace(' ', '_')) =
        DiscordUser(id, username, "0", name, null)

    val me = user("900", "Willow", "willow.s")
    val zaffox = user("901", "Zaffox")
    val mira = user("902", "Mira Okafor", "mira")
    val kai = user("903", "Kai Tanaka", "kai")
    val ines = user("904", "Inês Duarte", "ines")
    val teodor = user("905", "Teodor", "teo")

    val blockhaven = Guild("700", "Blockhaven", null)
    val nightOwls = Guild("701", "Night Owls Club", null)
    val pixelForge = Guild("702", "Pixel Forge", null)
    val lofi = Guild("703", "lofi corner", null)
    val guilds = listOf(blockhaven, nightOwls, pixelForge, lofi)

    private fun channel(
        id: String, name: String, type: ChannelType = ChannelType.GUILD_TEXT,
        guild: Guild = blockhaven, last: String? = null, access: Boolean = true
    ) = Channel(id, type, guild.id, name, null, last, null, 0, hasAccess = access)

    private fun category(id: String, name: String) = channel(id, name, ChannelType.GUILD_CATEGORY)

    val rules = channel("800", "rules", last = id(60 * 24 * 30))
    val announcements = channel("801", "announcements", ChannelType.GUILD_NEWS, last = id(60 * 5))
    val general = channel("802", "general", last = id(1))
    val builds = channel("803", "build-showcase", last = id(30))
    val memes = channel("804", "memes", last = id(60 * 30))
    val staff = channel("805", "staff-only", last = id(60), access = false)
    val lobby = channel("806", "Lobby", ChannelType.GUILD_VOICE)
    val afk = channel("807", "AFK", ChannelType.GUILD_VOICE)
    val ideas = channel("808", "ideas", ChannelType.GUILD_FORUM, last = id(90))

    val blockhavenChannels = listOf(
        CategoryGroup(null, listOf(rules, announcements)),
        CategoryGroup(category("850", "TEXT CHANNELS"), listOf(general, builds, memes, ideas, staff)),
        CategoryGroup(category("851", "VOICE"), listOf(lobby, afk)),
    )

    val dmZaffox = Channel("600", ChannelType.DM, null, "", null, id(2), null, 0, recipients = listOf(zaffox))
    val dmMira = Channel("601", ChannelType.DM, null, "", null, id(64), null, 0, recipients = listOf(mira))
    val gameNight = Channel("602", ChannelType.GROUP_DM, null, "Game night", null, id(60 * 26), null, 0, recipients = listOf(kai, ines, teodor))
    val dmInes = Channel("603", ChannelType.DM, null, "", null, id(60 * 24 * 9), null, 0, recipients = listOf(ines))
    val dms = listOf(dmZaffox, dmMira, gameNight, dmInes)

    private fun msg(
        channel: Channel, author: DiscordUser, mins: Int, content: String, n: Int = 0,
        reactions: List<Reaction> = emptyList(), mentions: List<DiscordUser> = emptyList(),
        replyTo: DiscordMessage? = null, embeds: List<Embed> = emptyList()
    ) = DiscordMessage(
        id(mins, n), channel.id, author, content, ts(mins), null,
        reactions = reactions, mentionedUsers = mentions, mentionedUserIds = mentions.map { it.id },
        guildId = channel.guildId, referencedMessage = replyTo, type = if (replyTo != null) 19 else 0,
        embeds = embeds
    )

    private val ranked = msg(general, mira, 9, "anyone up for **ranked**? need 1 more",
        reactions = listOf(
            Reaction(ReactionEmoji(null, "🔥", false), 3, me = false),
            Reaction(ReactionEmoji(null, "👍", false), 2, me = true)
        ))
    val generalMessages = listOf(
        msg(general, teodor, 14, "server reset is tonight at 10, back up your builds"),
        ranked,
        msg(general, kai, 8, "i'm in after dinner", replyTo = ranked),
        msg(general, zaffox, 1, "<@900> you coming?", mentions = listOf(me)),
    )
    val announcementMessages = listOf(
        msg(announcements, teodor, 60 * 6, "**Season 4 starts Friday!** New biomes, a bigger spawn and a fresh map.",
            embeds = listOf(Embed("rich", "Season 4 patch notes", "Everything that changes on reset night, from mob caps to the new trading hall.",
                "https://example.com", 0x57F287, null, null, null, "Blockhaven", null, "blockhaven.net", null))),
        DiscordMessage(
            id(60 * 5), announcements.id, teodor, "", ts(60 * 5), null, guildId = blockhaven.id,
            poll = com.dihcord.app.api.Poll(
                "Which biome should spawn in?",
                listOf(
                    com.dihcord.app.api.PollAnswer(1, "Cherry grove", ReactionEmoji(null, "🌸", false), 14, meVoted = true),
                    com.dihcord.app.api.PollAnswer(2, "Mushroom island", ReactionEmoji(null, "🍄", false), 9),
                    com.dihcord.app.api.PollAnswer(3, "Badlands", ReactionEmoji(null, "🏜️", false), 4),
                ),
                expiry = java.time.Instant.ofEpochMilli(now + 26 * 3_600_000L).toString(), multiselect = false, finalized = false
            )
        ),
    )
    val zaffoxMessages = listOf(
        msg(dmZaffox, me, 40, "did you finish the castle?"),
        msg(dmZaffox, zaffox, 38, "almost! the towers took forever"),
        msg(dmZaffox, zaffox, 37, "the roof is _still_ a mess though", n = 1),
        msg(dmZaffox, me, 20, "send pics when you can"),
        msg(dmZaffox, zaffox, 2, "are you coming tonight? we start at 8"),
    )
    val miraMessages = listOf(msg(dmMira, me, 64, "ok see you there"))
    val gameNightMessages = listOf(msg(gameNight, kai, 60 * 26, "lol that was close"))
    val inesMessages = listOf(
        DiscordMessage(id(60 * 24 * 9), dmInes.id, ines, "", ts(60 * 24 * 9), null,
            attachments = listOf(com.dihcord.app.api.Attachment("1", "beach.jpg", "", "", "image/jpeg", 800, 600)))
    )

    fun repository(): DiscordRepository = DiscordRepository("test-token", context = null, apiBase = "http://127.0.0.1:9").apply {
        seedForTests(
            user = me,
            guilds = SampleData.guilds,
            dms = SampleData.dms,
            channels = mapOf(blockhaven.id to blockhavenChannels),
            messages = mapOf(
                general.id to generalMessages,
                announcements.id to announcementMessages,
                dmZaffox.id to zaffoxMessages,
                dmMira.id to miraMessages,
                gameNight.id to gameNightMessages,
                dmInes.id to inesMessages,
            ),
            readState = mapOf(
                dmZaffox.id to ChannelUnreadState(id(20), mentionCount = 1),
                dmMira.id to ChannelUnreadState(id(64)),
                gameNight.id to ChannelUnreadState(id(60 * 30)),
                dmInes.id to ChannelUnreadState(id(60 * 24 * 9)),
                general.id to ChannelUnreadState(id(10), mentionCount = 1),
                builds.id to ChannelUnreadState(id(60)),
                memes.id to ChannelUnreadState(id(60 * 30)),
                announcements.id to ChannelUnreadState(id(60 * 6), mentionCount = 2),
                rules.id to ChannelUnreadState(id(60 * 24 * 30)),
                ideas.id to ChannelUnreadState(id(90)),
            ),
            presences = listOf(
                UserPresence(zaffox.id, OnlineStatus.ONLINE),
                UserPresence(mira.id, OnlineStatus.IDLE, customStatusText = "studying"),
                UserPresence(ines.id, OnlineStatus.DND),
            ),
            voiceStates = listOf(
                VoiceState(kai.id, blockhaven.id, lobby.id, "s1", false, false, false, false, kai),
                VoiceState(teodor.id, blockhaven.id, lobby.id, "s2", true, false, false, false, teodor),
                VoiceState(mira.id, blockhaven.id, lobby.id, "s3", false, false, false, false, mira),
            ),
        )
    }
}
