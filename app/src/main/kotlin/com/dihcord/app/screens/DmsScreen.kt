package com.dihcord.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import com.dihcord.app.Perf
import com.dihcord.app.R
import com.dihcord.app.SetupPreferences
import com.dihcord.app.api.*
import com.dihcord.app.discordApp

/** One row of the DM list, as plain data. */
data class DmRow(
    val channelId: String,
    val name: String,
    val avatarUrl: String?,
    val decorationUrl: String?,
    val nameplateUrl: String?,
    val status: OnlineStatus?,
    /** The newest message as one line ("You: ok see you"), when we have it. */
    val preview: String?,
    /** What to show when there is no preview: the custom status, or the status itself. */
    val fallback: String?,
    val timeMillis: Long?,
    val mentionCount: Int,
    val unread: Boolean,
    val hidden: Boolean,
    val muted: Boolean = false,
    val isGroup: Boolean = false,
)

@Composable
fun DmsScreen(
    onNavigateToChatScreen: (channelId: String, channelName: String) -> Unit,
    onNewDm: () -> Unit = {},
    onFriends: () -> Unit = {},
    onAddPeople: (channelId: String) -> Unit = {},
    onMembers: (channelId: String, name: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val dmChannels by repo.dmChannels.collectAsState()
    val presences by repo.presences.collectAsState()
    val readState by repo.readState.collectAsState()
    val lastMessages by repo.lastMessages.collectAsState()
    val channelNames by repo.channelNames.collectAsState()
    val myId = repo.currentUser.collectAsState().value?.id
    val showBadges = remember { SetupPreferences.getShowMentionBadges(context) }
    var loading by remember { mutableStateOf(dmChannels.isEmpty()) }
    var hiddenIds by remember { mutableStateOf(SetupPreferences.getHiddenDms(context)) }
    var showHidden by remember { mutableStateOf(false) }
    var menuDm by remember { mutableStateOf<DmRow?>(null) }
    var muteMenu by remember { mutableStateOf(false) }
    var mutedUntil by remember { mutableStateOf(com.dihcord.app.FeaturePreferences.mutedUntil(context)) }
    val relationships by repo.relationships.collectAsState()
    val requests = relationships.values.count { it.isIncoming }
    // Held here, above the menu branch, so closing the menu returns to the same spot in the list.
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(Unit) {
        repo.refreshDmChannels()
        loading = false
    }

    val sortedDms = remember(dmChannels, hiddenIds, showHidden) {
        dmChannels
            .filter { showHidden || !hiddenIds.contains(it.id) }
            .sortedByDescending { it.lastMessageId?.toLongOrNull() ?: 0L }
    }
    // Previews for the DMs near the top; the rest fill in as they get messages or are opened.
    LaunchedEffect(sortedDms.take(12).map { it.id to it.lastMessageId }) {
        repo.loadLastMessages(sortedDms.take(12).map { it.id })
    }

    val rows = remember(sortedDms, presences, readState, lastMessages, hiddenIds, myId, mutedUntil) {
        sortedDms.map { dm ->
            val oneToOne = dm.recipients.size == 1
            val recipient = dm.recipients.firstOrNull()
            val presence = if (oneToOne) recipient?.let { presences[it.id] } else null
            val rs = readState[dm.id]
            val status = presence?.status ?: if (oneToOne) OnlineStatus.OFFLINE else null
            DmRow(
                channelId = dm.id,
                name = dm.displayName,
                avatarUrl = if (oneToOne) recipient?.avatarUrl(64) else null,
                decorationUrl = if (oneToOne && !Perf.lite) recipient?.avatarDecorationUrl() else null,
                nameplateUrl = if (oneToOne && !Perf.lite) recipient?.nameplateUrl() else null,
                status = status,
                preview = lastMessages[dm.id]?.takeIf { it.id == dm.lastMessageId }?.let { msg ->
                    val text = messagePreview(msg, myId, channelNames)
                    if (!oneToOne && msg.author.id != myId) msg.author.displayName.substringBefore(' ') + ": " + text else text
                },
                fallback = presence?.activityLabel ?: presence?.customStatusText ?: when {
                    !oneToOne -> "${dm.recipients.size + 1} members"
                    else -> status?.label()
                },
                timeMillis = snowflakeMillis(dm.lastMessageId),
                mentionCount = if (showBadges) rs?.mentionCount ?: 0 else 0,
                unread = showBadges && dm.lastMessageId != null && rs != null &&
                        compareSnowflakes(dm.lastMessageId, rs.lastMessageId) > 0,
                hidden = dm.id in hiddenIds,
                muted = dm.id in mutedUntil || dm.id in SetupPreferences.getMutedChannels(context),
                isGroup = !oneToOne,
            )
        }
    }

    // Back closes the menu instead of leaving the screen.
    BackHandler(enabled = menuDm != null) { if (muteMenu) muteMenu = false else menuDm = null }

    val activeDm = menuDm
    if (activeDm != null && muteMenu) {
        fun mute(ms: Long?) {
            com.dihcord.app.FeaturePreferences.muteChat(context, activeDm.channelId, ms)
            mutedUntil = com.dihcord.app.FeaturePreferences.mutedUntil(context)
            muteMenu = false
            menuDm = null
        }
        ActionMenu(
            title = "Mute ${activeDm.name}",
            actions = listOfNotNull(
                MenuAction(R.drawable.mute, "1 hour") { mute(3_600_000L) },
                MenuAction(R.drawable.mute, "8 hours") { mute(8 * 3_600_000L) },
                MenuAction(R.drawable.mute, "24 hours") { mute(24 * 3_600_000L) },
                MenuAction(R.drawable.mute, "Always") { mute(null) },
                if (activeDm.muted) MenuAction(R.drawable.bell, "Unmute") { mute(0L) } else null,
            ),
            onDismiss = { muteMenu = false }
        )
        return
    }
    if (activeDm != null) {
        ActionMenu(
            title = activeDm.name,
            header = { UserAvatar(activeDm.name, activeDm.avatarUrl, 36.dp) },
            actions = listOfNotNull(
                MenuAction(if (activeDm.muted) R.drawable.bell else R.drawable.mute, if (activeDm.muted) "Muted" else "Mute") { muteMenu = true },
                MenuAction(
                    if (activeDm.hidden) R.drawable.unhide else R.drawable.hide,
                    if (activeDm.hidden) "Unhide" else "Hide"
                ) {
                    SetupPreferences.toggleHiddenDm(context, activeDm.channelId)
                    hiddenIds = SetupPreferences.getHiddenDms(context)
                    menuDm = null
                },
                if (activeDm.isGroup) MenuAction(R.drawable.person_add, "Add") { menuDm = null; onAddPeople(activeDm.channelId) } else null,
                if (activeDm.isGroup) MenuAction(R.drawable.people, "Members") { menuDm = null; onMembers(activeDm.channelId, activeDm.name) } else null,
            ),
            onDismiss = { menuDm = null }
        )
        return
    }

    DmsContent(
        rows = rows,
        loading = loading,
        hiddenCount = hiddenIds.size,
        showingHidden = showHidden,
        onOpen = { if (!it.hidden) onNavigateToChatScreen(it.channelId, it.name) },
        onLongPress = { menuDm = it },
        onToggleHidden = { showHidden = !showHidden },
        onNewDm = onNewDm,
        state = listState,
        friendRequests = requests,
        onFriends = onFriends,
    )
}

/** The DM list, drawn from plain data. */
@Composable
internal fun DmsContent(
    rows: List<DmRow>,
    loading: Boolean,
    hiddenCount: Int,
    showingHidden: Boolean,
    onOpen: (DmRow) -> Unit,
    onLongPress: (DmRow) -> Unit,
    onToggleHidden: () -> Unit,
    onNewDm: () -> Unit,
    state: androidx.wear.compose.foundation.lazy.TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    friendRequests: Int = 0,
    onFriends: () -> Unit = {},
) {
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = state,
        edgeButton = {
            EdgeButton(onClick = onNewDm) {
                Icon(painterResource(R.drawable.add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(2.dp))
                Text("New")
            }
        }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Direct Messages") }
            item {
                // Friends and requests; the badge counts requests waiting for an answer.
                Box(Modifier.padding(bottom = 2.dp)) {
                    CompactButton(
                        onClick = onFriends,
                        icon = { Icon(painterResource(R.drawable.people), null, Modifier.size(16.dp)) },
                        label = { Text("Friends") },
                        colors = ButtonDefaults.filledTonalButtonColors()
                    )
                    CountBadge(friendRequests, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
                }
            }
            when {
                loading && rows.isEmpty() -> items(3) { DmSkeletonRow(Modifier.transformedHeight(this, spec)) }
                rows.isEmpty() -> item { EmptyState(R.drawable.chat, "No DMs yet", "Start one with New.") }
                else -> items(rows.size, key = { rows[it].channelId }) { index ->
                    val row = rows[index]
                    DmRowCard(
                        row = row,
                        modifier = Modifier.transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        onClick = { onOpen(row) },
                        onLongClick = { onLongPress(row) }
                    )
                }
            }
            if (hiddenCount > 0) {
                item {
                    CompactButton(
                        onClick = onToggleHidden,
                        label = { Text(if (showingHidden) "Hide hidden DMs" else "Show hidden ($hiddenCount)") },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Avatar with its status ring, the name and how long ago, and the last message under it. The
 * person's Discord nameplate, if any, is the background, darkened so the text stays readable.
 */
@Composable
private fun DmRowCard(
    row: DmRow,
    modifier: Modifier,
    transformation: SurfaceTransformation,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val padding = PaddingValues(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
    val content: @Composable ColumnScope.() -> Unit = { DmRowContent(row) }
    val nameplate = row.nameplateUrl?.takeIf { !row.hidden }
    if (nameplate != null) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            containerPainter = CardDefaults.containerPainter(
                image = rememberAsyncImagePainter(nameplate, imageLoader = LocalContext.current.imageLoader),
                scrim = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.35f)))
            ),
            colors = CardDefaults.cardWithContainerPainterColors(),
            modifier = modifier.fillMaxWidth(),
            transformation = transformation,
            contentPadding = padding,
            content = content
        )
    } else {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.fillMaxWidth(),
            transformation = transformation,
            contentPadding = padding,
            content = content
        )
    }
}

@Composable
private fun DmRowContent(row: DmRow) {
    val bright = row.unread || row.mentionCount > 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            UserAvatar(row.name, row.avatarUrl, 34.dp, ring = if (row.hidden) null else row.status?.ringColor(), dimmed = row.hidden)
            if (row.decorationUrl != null && !row.hidden) {
                AsyncImage(
                    model = row.decorationUrl,
                    imageLoader = LocalContext.current.imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(40.dp)
                )
            }
            if (!row.hidden && row.mentionCount > 0) CountBadge(row.mentionCount, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (bright) FontWeight.Bold else FontWeight.Medium),
                    color = if (row.hidden) Color.White.copy(alpha = 0.4f) else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (row.muted && !row.hidden) {
                    Icon(
                        painterResource(R.drawable.mute), "Muted",
                        Modifier.padding(start = 3.dp).size(11.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (row.timeMillis != null && !row.hidden) {
                    Text(
                        shortAgo(row.timeMillis),
                        style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                        color = if (bright) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        row.hidden -> "Hidden"
                        else -> row.preview ?: row.fallback ?: ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        row.hidden -> Color.White.copy(alpha = 0.4f)
                        bright -> Color.White
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (row.unread && row.mentionCount == 0 && !row.hidden) UnreadDot(Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** A grey stand-in row with the same shape as a DM, shown while the list loads. */
@Composable
private fun DmSkeletonRow(modifier: Modifier = Modifier) {
    val shade = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(26.dp))
            .padding(start = 11.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(Modifier.size(34.dp).background(shade, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(70.dp).height(10.dp).background(shade, RoundedCornerShape(5.dp)))
            Box(Modifier.width(100.dp).height(8.dp).background(shade, RoundedCornerShape(4.dp)))
        }
    }
}

private fun OnlineStatus.label(): String = when (this) {
    OnlineStatus.ONLINE -> "Online"
    OnlineStatus.IDLE -> "Idle"
    OnlineStatus.DND -> "Do Not Disturb"
    OnlineStatus.INVISIBLE -> "Invisible"
    OnlineStatus.OFFLINE -> "Offline"
}
