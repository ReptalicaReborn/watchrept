package com.dihcord.app.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {
    private val guildId = "100"
    private val me = GuildMember(userId = "42", roleIds = listOf("7"))

    private fun role(id: String, perms: Long) =
        GuildRole(id = id, name = id, color = 0, position = 1, permissions = perms)

    private val everyoneCanSend = role(guildId, Permissions.VIEW_CHANNEL or Permissions.SEND_MESSAGES)
    private val memberRole = role("7", 0L)

    private fun channel(vararg overwrites: PermissionOverwrite, type: ChannelType = ChannelType.GUILD_TEXT) = Channel(
        id = "200",
        type = type,
        guildId = guildId,
        name = "general",
        topic = null,
        lastMessageId = null,
        parentId = null,
        position = 0,
        permissionOverwrites = overwrites.toList(),
    )

    private fun canSend(
        channel: Channel,
        roles: List<GuildRole> = listOf(everyoneCanSend, memberRole),
        ownerId: String? = null,
    ) = Permissions.canSendMessages(me, guildId, ownerId, roles, channel)

    @Test
    fun `open channel allows sending`() {
        assertTrue(canSend(channel()))
    }

    @Test
    fun `everyone overwrite denying send blocks a plain member`() {
        val ch = channel(PermissionOverwrite(guildId, 0, allow = 0, deny = Permissions.SEND_MESSAGES))
        assertFalse(canSend(ch))
    }

    @Test
    fun `role overwrite re-allows sending when everyone is denied`() {
        // The case that used to hide the input box: roles weren't known yet, so only the deny was seen.
        val ch = channel(
            PermissionOverwrite(guildId, 0, allow = 0, deny = Permissions.SEND_MESSAGES),
            PermissionOverwrite("7", 0, allow = Permissions.SEND_MESSAGES, deny = 0),
        )
        assertTrue(canSend(ch))
    }

    @Test
    fun `role overwrite for a role the member lacks does not help`() {
        val ch = channel(
            PermissionOverwrite(guildId, 0, allow = 0, deny = Permissions.SEND_MESSAGES),
            PermissionOverwrite("999", 0, allow = Permissions.SEND_MESSAGES, deny = 0),
        )
        assertFalse(canSend(ch))
    }

    @Test
    fun `member overwrite beats role and everyone overwrites`() {
        val allowed = channel(
            PermissionOverwrite(guildId, 0, allow = 0, deny = Permissions.SEND_MESSAGES),
            PermissionOverwrite("42", 1, allow = Permissions.SEND_MESSAGES, deny = 0),
        )
        assertTrue(canSend(allowed))

        val denied = channel(
            PermissionOverwrite("7", 0, allow = Permissions.SEND_MESSAGES, deny = 0),
            PermissionOverwrite("42", 1, allow = 0, deny = Permissions.SEND_MESSAGES),
        )
        assertFalse(canSend(denied))
    }

    @Test
    fun `administrator role bypasses overwrites`() {
        val admin = role("7", Permissions.ADMINISTRATOR)
        val ch = channel(PermissionOverwrite(guildId, 0, allow = 0, deny = Permissions.SEND_MESSAGES))
        assertTrue(canSend(ch, roles = listOf(everyoneCanSend, admin)))
    }

    @Test
    fun `guild owner bypasses overwrites`() {
        val ch = channel(PermissionOverwrite(guildId, 0, allow = 0, deny = Permissions.SEND_MESSAGES))
        assertTrue(canSend(ch, ownerId = "42"))
    }

    @Test
    fun `base permissions without send block sending even with no overwrites`() {
        val readOnly = role(guildId, Permissions.VIEW_CHANNEL)
        assertFalse(canSend(channel(), roles = listOf(readOnly, memberRole)))
    }

    @Test
    fun `announcement channels follow permissions instead of being blocked`() {
        assertTrue(canSend(channel(type = ChannelType.GUILD_NEWS)))
    }
}
