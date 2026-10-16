package com.dihcord.app.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureModelsTest {
    private val utc = java.time.ZoneId.of("UTC")
    private val us = java.util.Locale.US

    // ---- Timestamps ----

    @Test fun timestampStyles() {
        val t = 1_700_000_000L // 2023-11-14 22:13:20 UTC
        assertEquals("10:13 PM", DiscordTimestamp.format(t, 't', zone = utc, locale = us))
        assertEquals("11/14/2023", DiscordTimestamp.format(t, 'd', zone = utc, locale = us))
        assertEquals("November 14, 2023", DiscordTimestamp.format(t, 'D', zone = utc, locale = us))
        assertEquals("November 14, 2023 10:13 PM", DiscordTimestamp.format(t, null, zone = utc, locale = us))
        assertEquals("Tuesday, November 14, 2023 10:13 PM", DiscordTimestamp.format(t, 'F', zone = utc, locale = us))
    }

    @Test fun relativeTimes() {
        val now = 1_000_000_000_000L
        assertEquals("in 2 hours", DiscordTimestamp.relative(now + 2 * 3_600_000, now))
        assertEquals("3 days ago", DiscordTimestamp.relative(now - 3 * 86_400_000L, now))
        assertEquals("a minute ago", DiscordTimestamp.relative(now - 70_000, now))
        assertEquals("an hour ago", DiscordTimestamp.relative(now - 80 * 60_000L, now))
        assertEquals("in a few seconds", DiscordTimestamp.relative(now + 5_000, now))
    }

    @Test fun timestampTagsParse() {
        val parts = ContentParser.parse("starts <t:1700000000:R> ok")
        val ts = parts.filterIsInstance<ContentParser.Part.Timestamp>().single()
        assertEquals(1_700_000_000L, ts.epochSeconds)
        assertEquals('R', ts.style)
        val plain = ContentParser.parse("<t:1700000000>").filterIsInstance<ContentParser.Part.Timestamp>().single()
        assertNull(plain.style)
    }

    // ---- Components ----

    @Test fun componentsParse() {
        val arr = JSONArray(
            """[{"type":1,"components":[
                {"type":2,"style":1,"label":"Red","custom_id":"r"},
                {"type":2,"style":5,"label":"Site","url":"https://example.com"},
                {"type":3,"custom_id":"pick","placeholder":"Pick one","options":[{"label":"A","value":"a"},{"label":"B","value":"b","default":true}]}
            ]},
            {"type":17,"accent_color":255,"components":[{"type":10,"content":"# Hello"},{"type":9,"components":[{"type":10,"content":"side"}],"accessory":{"type":2,"style":2,"label":"Go","custom_id":"g"}}]},
            {"type":12,"items":[]}]"""
        )
        val list = MessageComponent.listFromJson(arr)
        assertEquals(2, list.size) // the media gallery isn't drawn
        val row = list[0] as MessageComponent.Row
        val red = row.children[0] as MessageComponent.Button
        assertEquals("r", red.customId); assertFalse(red.isLink)
        assertTrue((row.children[1] as MessageComponent.Button).isLink)
        val select = row.children[2] as MessageComponent.Select
        assertEquals(listOf("a", "b"), select.options.map { it.value })
        assertTrue(select.options[1].default)
        val box = list[1] as MessageComponent.Container
        assertEquals(255, box.accentColor)
        assertEquals("g", ((box.children[1] as MessageComponent.Section).accessory as MessageComponent.Button).customId)
    }

    @Test fun messageKeepsComponentsAndBotApp() {
        val o = JSONObject(
            """{"id":"1","channel_id":"2","content":"","timestamp":"2024-01-01T00:00:00Z",
               "author":{"id":"99","username":"bot","discriminator":"0","bot":true},
               "components":[{"type":1,"components":[{"type":2,"style":3,"label":"Yes","custom_id":"y"}]}],
               "flags":64,"pinned":true}"""
        )
        val m = DiscordMessage.fromJson(o)
        assertEquals("99", m.applicationId)
        assertEquals(64, m.flags)
        assertTrue(m.pinned)
        assertEquals(1, m.components.size)
    }

    // ---- Member list ----

    @Test fun memberListOps() {
        fun member(id: String) = MemberListItem.Member(DiscordUser(id, id, "0", null, null), null, emptyList(), OnlineStatus.ONLINE, emptyList())
        val group = MemberListItem.Group("online", 2)
        var rows = MemberListOp.apply(emptyList(), listOf(MemberListOp.Sync(0, listOf(group, member("a"), member("b")))))
        assertEquals(3, rows.size)
        rows = MemberListOp.apply(rows, listOf(MemberListOp.Insert(1, member("c"))))
        assertEquals(listOf("c", "a", "b"), rows.drop(1).map { (it as MemberListItem.Member).user.id })
        rows = MemberListOp.apply(rows, listOf(MemberListOp.Delete(2), MemberListOp.Update(1, member("d"))))
        assertEquals(listOf("d", "b"), rows.drop(1).map { (it as MemberListItem.Member).user.id })
        rows = MemberListOp.apply(rows, listOf(MemberListOp.Invalidate(0, 99)))
        assertTrue(rows.all { it == null })
    }

    @Test fun memberListParsesPresence() {
        val ops = MemberListOp.listFromJson(JSONArray(
            """[{"op":"SYNC","range":[0,99],"items":[{"group":{"id":"online","count":1}},
               {"member":{"user":{"id":"5","username":"kai","discriminator":"0"},"nick":"Kai","roles":["r1"],
                 "presence":{"status":"idle","activities":[{"type":0,"name":"Minecraft"}]}}}]}]"""
        ))
        val sync = ops.single() as MemberListOp.Sync
        val m = sync.items[1] as MemberListItem.Member
        assertEquals("Kai", m.name)
        assertEquals(OnlineStatus.IDLE, m.status)
        assertEquals("Playing Minecraft", m.activities.single().label)
    }

    // ---- Links and invites ----

    @Test fun discordLinks() {
        assertEquals(DiscordLink("1", "2", "3"), DiscordLink.parse("https://discord.com/channels/1/2/3"))
        assertEquals(DiscordLink(null, "2", null), DiscordLink.parse("https://ptb.discord.com/channels/@me/2"))
        assertNull(DiscordLink.parse("https://discord.com/invite/abc"))
        assertNull(DiscordLink.parse("https://example.com/channels/1/2"))
    }

    @Test fun inviteCodes() {
        assertEquals("minecraft", InvitePreview.codeFrom("https://discord.gg/minecraft"))
        assertEquals("abc-12", InvitePreview.codeFrom("discord.com/invite/abc-12"))
        assertEquals("XyZ", InvitePreview.codeFrom("  XyZ "))
        assertNull(InvitePreview.codeFrom("not a code!"))
    }

    // ---- Notification settings ----

    @Test fun notifySettingsBothShapes() {
        val entries = """[{"guild_id":"g1","muted":true,"mute_config":{"end_time":null},"message_notifications":1,
            "channel_overrides":[{"channel_id":"c1","muted":true,"mute_config":{"end_time":"2000-01-01T00:00:00Z"},"message_notifications":0}]},
            {"guild_id":null,"muted":false,"message_notifications":3,"channel_overrides":[]}]"""
        val fromList = ReadyParsing.notifySettings(JSONArray(entries))
        val fromObject = ReadyParsing.notifySettings(JSONObject().put("entries", JSONArray(entries)))
        assertEquals(fromList, fromObject)
        val g1 = fromList.getValue("g1")
        assertTrue(g1.mutedNow())
        assertFalse("a mute that ended in 2000 is over", g1.channels.getValue("c1").mutedNow())
        assertTrue("" in fromList)
    }

    // ---- Unread dots ----

    private fun channel(id: String, last: String?, deny: Long = 0, parent: String? = null) =
        Channel(id, ChannelType.GUILD_TEXT, "g", id, null, last, parent, 0,
            permissionOverwrites = if (deny != 0L) listOf(PermissionOverwrite("g", 0, 0, deny)) else emptyList())

    private val everyone = GuildRole("g", "@everyone", 0, 0, Permissions.VIEW_CHANNEL)

    @Test fun unreadTracker() {
        val t = UnreadTracker()
        val read = mapOf(
            "a" to ChannelUnreadState("10"), // newer message 20: unread
            "b" to ChannelUnreadState("30"), // read
            "hidden" to ChannelUnreadState("1"),
        )
        t.onReady(
            "me",
            listOf(ReadyGuild("g", null, listOf(channel("a", "20"), channel("b", "30"), channel("c", "50"), channel("hidden", "9", deny = Permissions.VIEW_CHANNEL)), listOf(everyone), emptyList())),
            emptyMap(), read
        )
        assertEquals(setOf("g"), t.unreadGuilds.value)
        assertEquals(listOf("a" to "20"), t.unreadChannels("g", read))

        // Reading it clears the dot.
        val readAll = read + ("a" to ChannelUnreadState("20"))
        t.recompute(readAll)
        assertTrue(t.unreadGuilds.value.isEmpty())

        // A new message brings it back; muting the server hides it.
        t.onMessage("b", "40", readAll)
        assertEquals(setOf("g"), t.unreadGuilds.value)
        t.onSettings("g", NotifySettings(true, 0, 3, emptyMap()), readAll)
        assertTrue(t.unreadGuilds.value.isEmpty())
    }

    @Test fun hugeTimestampsDoNotCrash() {
        assertEquals("a time", DiscordTimestamp.format(9_999_999_999_999L * 1_000_000, 'f', zone = utc, locale = us))
        assertEquals("a time", DiscordTimestamp.format(Long.MAX_VALUE / 10, 'R'))
        // The tag itself only takes up to 13 digits.
        assertNull(DiscordTimestamp.REGEX.find("<t:92233720368547758070>"))
    }

    @Test fun memberListSyncClearsItsRange() {
        fun member(id: String) = MemberListItem.Member(DiscordUser(id, id, "0", null, null), null, emptyList(), OnlineStatus.ONLINE, emptyList())
        var rows = MemberListOp.apply(emptyList(), listOf(MemberListOp.Sync(0, listOf(member("a"), member("b"), member("c")))))
        rows = MemberListOp.apply(rows, listOf(MemberListOp.Sync(0, listOf(member("d")), end = 99)))
        assertEquals(listOf("d"), rows.filterNotNull().map { (it as MemberListItem.Member).user.id })
    }

    @Test fun memberListsDoNotMix() {
        fun member(id: String) = MemberListItem.Member(DiscordUser(id, id, "0", null, null), null, emptyList(), OnlineStatus.ONLINE, emptyList())
        val store = MemberListStore()
        store.apply(GatewayEvent.MemberListUpdate("g", "everyone", 3, 3, listOf(MemberListOp.Sync(0, listOf(member("a"), member("b"), member("c"))))))
        store.apply(GatewayEvent.MemberListUpdate("g", "staff", 1, 1, listOf(MemberListOp.Update(0, member("s")))))
        assertEquals(listOf("s"), store.lists.value["g"]!!.rows.filterNotNull().map { (it as MemberListItem.Member).user.id })
        store.reset("g")
        assertNull(store.lists.value["g"])
    }

    @Test fun deletedChannelIsNotUnread() {
        val t = UnreadTracker()
        val read = mapOf("a" to ChannelUnreadState("10"))
        t.onReady("me", listOf(ReadyGuild("g", null, listOf(channel("a", "20")), listOf(everyone), emptyList())), emptyMap(), read)
        assertEquals(setOf("g"), t.unreadGuilds.value)
        t.onChannelDelete("g", "a", read)
        assertTrue(t.unreadGuilds.value.isEmpty())
        // A refresh that still lists other channels doesn't bring it back.
        t.onChannels("g", listOf(channel("b", null)), read)
        assertTrue(t.unreadGuilds.value.isEmpty())
    }

    @Test fun voiceChatCountsAsUnread() {
        val t = UnreadTracker()
        val read = mapOf("v" to ChannelUnreadState("10"))
        val voice = channel("v", "20").copy(type = ChannelType.GUILD_VOICE)
        t.onReady("me", listOf(ReadyGuild("g", null, listOf(voice), listOf(everyone), emptyList())), emptyMap(), read)
        assertEquals(setOf("g"), t.unreadGuilds.value)
    }

    @Test fun joinedServerGetsUnreadData() {
        val t = UnreadTracker()
        t.onReady("me", emptyList(), emptyMap(), emptyMap())
        val read = mapOf("a" to ChannelUnreadState("10"))
        t.onGuildJoined(ReadyGuild("g", null, listOf(channel("a", "20", parent = "cat")), listOf(everyone), emptyList()), read)
        assertEquals(setOf("g"), t.unreadGuilds.value)
        assertEquals("cat", t.parentOf("a"))
    }

    @Test fun threadsAreMarkedReadButDoNotLightTheDot() {
        val t = UnreadTracker()
        val read = mapOf("th" to ChannelUnreadState("10"), "live" to ChannelUnreadState("5"))
        val thread = channel("th", "20").copy(type = ChannelType.PUBLIC_THREAD)
        t.onReady("me", listOf(ReadyGuild("g", null, emptyList(), listOf(everyone), emptyList(), threads = listOf(thread))), emptyMap(), read)
        assertTrue(t.unreadGuilds.value.isEmpty())
        assertEquals(listOf("th" to "20"), t.unreadThreads("g", read))
        // A thread READY didn't list shows up once a message arrives in it.
        t.onMessage("live", "9", read, guildId = "g")
        assertEquals(setOf("th", "live"), t.unreadThreads("g", read).map { it.first }.toSet())
    }
}
