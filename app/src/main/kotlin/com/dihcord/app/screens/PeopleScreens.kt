package com.dihcord.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dihcord.app.R
import com.dihcord.app.api.*
import com.dihcord.app.discordApp
import kotlinx.coroutines.launch

/** A person in a list: who, their status ring, and what they're doing. */
data class PersonRow(val user: DiscordUser, val name: String, val status: OnlineStatus?, val detail: String?)

/** A member list section: its title and people. */
data class PeopleSection(val title: String, val people: List<PersonRow>)

// ---------------------------------------------------------------- Members

/**
 * Who is in a server channel (as Discord's sidebar shows it, grouped by status and hoisted roles) or in
 * a group DM. Tapping someone opens their profile.
 */
@Composable
fun MembersScreen(guildId: String?, channelId: String, title: String, onOpenProfile: (DiscordUser) -> Unit) {
    val repo = LocalContext.current.discordApp.repository ?: return
    val lists by repo.memberLists.collectAsState()
    val presences by repo.presences.collectAsState()
    val dms by repo.dmChannels.collectAsState()
    val me = repo.currentUser.collectAsState().value
    var roleNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // A new gateway session (after a long disconnect) has no subscription: ask again.
    val session by repo.sessions.collectAsState()
    LaunchedEffect(guildId, channelId, session) {
        if (guildId != null) {
            repo.subscribeMemberList(guildId, channelId)
            roleNames = repo.getGuildRoles(guildId).associate { it.id to it.name }
        }
    }
    val sections: List<PeopleSection>? = if (guildId != null) {
        val list = lists[guildId] ?: return MembersContent(title, null, null, onOpenProfile)
        val out = mutableListOf<PeopleSection>()
        var current: Pair<String, MutableList<PersonRow>>? = null
        list.rows.filterNotNull().forEach { row ->
            when (row) {
                is MemberListItem.Group -> {
                    current?.let { (t, p) -> if (p.isNotEmpty()) out += PeopleSection(t, p) }
                    val name = when (row.id) { "online" -> "Online"; "offline" -> "Offline"; else -> roleNames[row.id] ?: "Members" }
                    current = "$name — ${row.count}" to mutableListOf()
                }
                is MemberListItem.Member -> {
                    if (current == null) current = "Members" to mutableListOf()
                    current!!.second += PersonRow(row.user, row.name, row.status, row.activities.firstNotNullOfOrNull { it.label })
                }
            }
        }
        current?.let { (t, p) -> if (p.isNotEmpty()) out += PeopleSection(t, p) }
        out
    } else {
        val dm = dms.firstOrNull { it.id == channelId }
        val people = (listOfNotNull(me) + dm?.recipients.orEmpty()).distinctBy { it.id }.map { u ->
            val p = presences[u.id]
            PersonRow(u, u.displayName, p?.status ?: if (u.id == me?.id) OnlineStatus.ONLINE else OnlineStatus.OFFLINE, p?.activityLabel ?: p?.customStatusText)
        }
        listOf(PeopleSection("Members — ${people.size}", people))
    }
    val count = if (guildId != null) lists[guildId]?.let { "${it.onlineCount} online · ${it.memberCount} members" } else null
    MembersContent(title, count, sections, onOpenProfile)
}

@Composable
internal fun MembersContent(title: String, subtitle: String?, sections: List<PeopleSection>?, onOpen: (DiscordUser) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Members") }
            item { ScreenSubtitle(subtitle ?: title) }
            when {
                sections == null -> item { LoadingIndicator() }
                sections.isEmpty() -> item { EmptyState(R.drawable.people, "No one to show") }
                // Keys by position: two roles can share a name, and the list can briefly hold someone twice.
                else -> sections.forEachIndexed { si, section ->
                    val people = section.people.distinctBy { it.user.id }
                    item(key = "h$si") { SectionHeader(section.title) }
                    items(people.size, key = { "$si:" + people[it].user.id }) { i ->
                        val p = people[i]
                        Button(
                            onClick = { onOpen(p.user) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            icon = { UserAvatar(p.name, p.user.avatarUrl(64), 28.dp, ring = p.status?.ringColor()) },
                            label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = p.detail?.let { d -> { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Friends, requests, blocks

@Composable
fun FriendsScreen(onOpenDm: (DiscordUser) -> Unit) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val scope = rememberCoroutineScope()
    val all by repo.relationships.collectAsState()
    val presences by repo.presences.collectAsState()
    var error by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { repo.refreshRelationships().onFailure { error = it } }

    fun act(userId: String, block: suspend () -> Result<Unit>) {
        if (busy != null) return
        busy = userId; error = null
        scope.launch { block().onFailure { error = it }; busy = null }
    }
    val rel = all.values.sortedBy { it.user.displayName.lowercase() }
    FriendsContent(
        incoming = rel.filter { it.isIncoming }.map { it.user },
        outgoing = rel.filter { it.isOutgoing }.map { it.user },
        friends = rel.filter { it.isFriend }.map { u -> PersonRow(u.user, u.user.displayName, presences[u.user.id]?.status ?: OnlineStatus.OFFLINE, presences[u.user.id]?.activityLabel) },
        blocked = rel.filter { it.isBlocked }.map { it.user },
        busy = busy, error = error,
        onAccept = { u -> act(u.id) { repo.acceptFriend(u) } },
        onRemove = { u -> act(u.id) { repo.removeRelationship(u.id) } },
        onOpenDm = onOpenDm,
    )
}

@Composable
internal fun FriendsContent(
    incoming: List<DiscordUser>,
    outgoing: List<DiscordUser>,
    friends: List<PersonRow>,
    blocked: List<DiscordUser>,
    busy: String?,
    error: Throwable?,
    onAccept: (DiscordUser) -> Unit,
    onRemove: (DiscordUser) -> Unit,
    onOpenDm: (DiscordUser) -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Friends") }
            if (error != null) item {
                Text(friendlyError(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            if (incoming.isNotEmpty()) {
                item { SectionHeader("Requests — ${incoming.size}") }
                items(incoming.size, key = { "in" + incoming[it].id }) { i ->
                    val u = incoming[i]
                    RequestRow(u, "Wants to be friends", busy == u.id, Modifier.transformedHeight(this, spec)) {
                        FilledIconButton(onClick = { onAccept(u) }, enabled = busy == null, modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = DiscordColors.Online)) {
                            Icon(painterResource(R.drawable.check), "Accept", Modifier.size(18.dp))
                        }
                        FilledTonalIconButton(onClick = { onRemove(u) }, enabled = busy == null, modifier = Modifier.size(34.dp)) {
                            Icon(painterResource(R.drawable.close), "Decline", Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (outgoing.isNotEmpty()) {
                item { SectionHeader("Sent — ${outgoing.size}") }
                items(outgoing.size, key = { "out" + outgoing[it].id }) { i ->
                    val u = outgoing[i]
                    RequestRow(u, "Waiting for them", busy == u.id, Modifier.transformedHeight(this, spec)) {
                        FilledTonalIconButton(onClick = { onRemove(u) }, enabled = busy == null, modifier = Modifier.size(34.dp)) {
                            Icon(painterResource(R.drawable.close), "Cancel request", Modifier.size(16.dp))
                        }
                    }
                }
            }
            item { SectionHeader("Friends — ${friends.size}") }
            if (friends.isEmpty()) item { EmptyState(R.drawable.people, "No friends yet") }
            items(friends.size, key = { "f" + friends[it].user.id }) { i ->
                val p = friends[i]
                Button(
                    onClick = { onOpenDm(p.user) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { UserAvatar(p.name, p.user.avatarUrl(64), 28.dp, ring = p.status?.ringColor()) },
                    label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = p.detail?.let { d -> { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                )
            }
            if (blocked.isNotEmpty()) {
                item { SectionHeader("Blocked — ${blocked.size}") }
                items(blocked.size, key = { "b" + blocked[it].id }) { i ->
                    val u = blocked[i]
                    RequestRow(u, "Blocked", busy == u.id, Modifier.transformedHeight(this, spec)) {
                        CompactButton(onClick = { onRemove(u) }, enabled = busy == null, label = { Text("Unblock") }, colors = ButtonDefaults.filledTonalButtonColors())
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestRow(user: DiscordUser, detail: String, busy: Boolean, modifier: Modifier, actions: @Composable RowScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UserAvatar(user.displayName, user.avatarUrl(64), 28.dp)
            Column(Modifier.weight(1f, fill = false)) {
                Text(user.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (busy) "…" else detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}
