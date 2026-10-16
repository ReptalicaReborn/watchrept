package com.dihcord.app.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dihcord.app.R
import com.dihcord.app.SetupPreferences
import com.dihcord.app.api.OnlineStatus
import com.dihcord.app.api.compareSnowflakes
import com.dihcord.app.discordApp
import com.dihcord.app.voice.VoiceCall
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Jump target meaning "the first message after the read marker that mentions me". */
const val JUMP_TO_FIRST_MENTION = "mention"

/** A recent DM in the row of faces at the top of Home. */
data class RecentDm(
    val channelId: String,
    val name: String,
    val avatarUrl: String?,
    val status: OnlineStatus?,
    val mentionCount: Int,
    val unread: Boolean,
)

/** A mention card on Home: who pinged, where, and what they said (when we have the message). */
data class HomeMention(
    val key: String,
    val channelId: String,
    val channelName: String,
    val guildId: String?,
    val guildName: String?,
    val author: String?,
    val avatarUrl: String?,
    val preview: String?,
    val timeMillis: Long?,
    val count: Int,
    /** The message to open at, or [JUMP_TO_FIRST_MENTION]. */
    val jumpTo: String,
) {
    val isDm: Boolean get() = guildId == null
}

@Composable
fun HomeScreen(
    onNavigateToDms: () -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToWelcome: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChat: (channelId: String, channelName: String, guildId: String?, jumpTo: String?) -> Unit,
    onOpenVoice: (call: VoiceCall) -> Unit = {}
) {
    val voiceCall by com.dihcord.app.voice.VoiceManager.call.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!SetupPreferences.isSetupComplete(context)) {
            onNavigateToWelcome()
        }
    }

    val repo = context.discordApp.repository
    val pings by (repo?.pings ?: return).collectAsState()
    val readState by repo.readState.collectAsState()
    val dmChannels by repo.dmChannels.collectAsState()
    val guilds by repo.guilds.collectAsState()
    val presences by repo.presences.collectAsState()
    val lastMessages by repo.lastMessages.collectAsState()
    val heldMessages by repo.messages.collectAsState()
    val channelNames by repo.channelNames.collectAsState()
    val channelGuilds by repo.channelGuilds.collectAsState()
    val myId = repo.currentUser.collectAsState().value?.id
    val hiddenDms = remember { SetupPreferences.getHiddenDms(context) }

    val dmIds = remember(dmChannels) { dmChannels.map { it.id }.toSet() }
    val dmMentionCount = remember(readState, dmIds) {
        readState.entries.filter { it.key in dmIds }.sumOf { it.value.mentionCount }
    }
    val serverMentionCount = remember(readState, dmIds) {
        readState.entries.filter { it.key !in dmIds }.sumOf { it.value.mentionCount }
    }

    val recentDms = remember(dmChannels, readState, presences, hiddenDms) {
        dmChannels
            .filter { it.id !in hiddenDms }
            .sortedByDescending { it.lastMessageId?.toLongOrNull() ?: 0L }
            .take(3)
            .map { dm ->
                val oneToOne = dm.recipients.size == 1
                val recipient = dm.recipients.firstOrNull()
                val rs = readState[dm.id]
                RecentDm(
                    channelId = dm.id,
                    name = dm.displayName,
                    avatarUrl = if (oneToOne) recipient?.avatarUrl(64) else null,
                    status = if (oneToOne) recipient?.let { presences[it.id]?.status } else null,
                    mentionCount = rs?.mentionCount ?: 0,
                    unread = dm.lastMessageId != null && rs != null && compareSnowflakes(dm.lastMessageId, rs.lastMessageId) > 0
                )
            }
    }
    val shownDmMentions = recentDms.sumOf { it.mentionCount }

    // Fetch names for mentioned channels we haven't cached yet, once per distinct set.
    val uncachedMentionIds = remember(readState, channelNames) {
        readState.entries
            .filter { it.value.mentionCount > 0 && it.key !in channelNames }
            .map { it.key }
            .toSet()
    }
    LaunchedEffect(uncachedMentionIds) {
        uncachedMentionIds.forEach { repo.ensureChannelCached(it) }
    }

    val mentions = remember(readState, pings, dmIds, channelNames, channelGuilds, guilds, heldMessages, lastMessages, myId) {
        fun guildName(id: String?) = id?.let { gid -> guilds.firstOrNull { it.id == gid }?.name }
        // The read-state counts (known from startup), with the message itself when we have it: a live ping,
        // a held message that mentions us, or a DM's newest message.
        val fromReadState = readState.entries
            .filter { it.value.mentionCount > 0 }
            .map { (channelId, state) ->
                val isDm = channelId in dmIds
                val guildId = if (isDm) null else channelGuilds[channelId]
                val msg = pings.firstOrNull { it.message.channelId == channelId }?.message
                    ?: heldMessages[channelId]?.lastOrNull { myId != null && it.author.id != myId && it.pingFor(myId, repo.getMyRoles(guildId ?: "")) }
                    ?: lastMessages[channelId]?.takeIf { isDm && it.author.id != myId }
                HomeMention(
                    key = "m$channelId",
                    channelId = channelId,
                    channelName = channelNames[channelId] ?: channelId,
                    guildId = guildId,
                    guildName = guildName(guildId),
                    author = msg?.author?.displayName,
                    avatarUrl = msg?.author?.avatarUrl(64),
                    preview = msg?.let { messagePreview(it, myId, channelNames) },
                    timeMillis = snowflakeMillis(msg?.id),
                    count = state.mentionCount,
                    jumpTo = pings.firstOrNull { it.message.channelId == channelId }?.message?.id ?: JUMP_TO_FIRST_MENTION
                )
            }
        // Live pings that arrived after the last read-state sync.
        val extra = pings
            .filter { p -> fromReadState.none { it.channelId == p.message.channelId } }
            .map { p ->
                HomeMention(
                    key = "p" + p.message.id,
                    channelId = p.message.channelId,
                    channelName = p.channelName,
                    guildId = p.message.guildId,
                    guildName = p.guildName,
                    author = p.message.author.displayName,
                    avatarUrl = p.message.author.avatarUrl(64),
                    preview = messagePreview(p.message, myId, channelNames),
                    timeMillis = snowflakeMillis(p.message.id),
                    count = 1,
                    jumpTo = p.message.id
                )
            }
        (fromReadState + extra).sortedByDescending { it.timeMillis ?: 0L }
    }

    fun dismiss(channelId: String) {
        repo.dismissMentions(channelId)
        com.dihcord.app.Notifier.clear(context, channelId)
    }

    HomeContent(
        voiceCall = voiceCall?.takeIf { com.dihcord.app.voice.VoiceManager.isInCall },
        recentDms = recentDms,
        otherDmMentions = (dmMentionCount - shownDmMentions).coerceAtLeast(0),
        mentions = mentions,
        serverMentionCount = serverMentionCount,
        onOpenVoice = onOpenVoice,
        onOpenDm = { onNavigateToChat(it.channelId, it.name, null, null) },
        onOpenDms = onNavigateToDms,
        onOpenServers = onNavigateToServers,
        onOpenMention = { onNavigateToChat(it.channelId, it.channelName, it.guildId, it.jumpTo) },
        onDismissMention = { dismiss(it.channelId) },
        onOpenSettings = onNavigateToSettings
    )
}

/** Home, drawn from plain data (so the screenshot tests can show it without a Discord connection). */
@Composable
internal fun HomeContent(
    voiceCall: VoiceCall?,
    recentDms: List<RecentDm>,
    otherDmMentions: Int,
    mentions: List<HomeMention>,
    serverMentionCount: Int,
    onOpenVoice: (VoiceCall) -> Unit,
    onOpenDm: (RecentDm) -> Unit,
    onOpenDms: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenMention: (HomeMention) -> Unit,
    onDismissMention: (HomeMention) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val pride = com.dihcord.app.AppTheme.preset.isPride
    ScreenScaffold(
        scrollState = state,
        edgeButton = {
            EdgeButton(onClick = onOpenServers) {
                Icon(painterResource(R.drawable.servers), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Servers")
                if (serverMentionCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    CountBadge(serverMentionCount)
                }
            }
        }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            if (pride) item { PrideBar(Modifier.padding(bottom = 2.dp)) }

            item { RecentDmRow(recentDms, otherDmMentions, onOpenDm, onOpenDms) }

            voiceCall?.let { call ->
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        onClick = { onOpenVoice(call) },
                        colors = ButtonDefaults.buttonColors(containerColor = DiscordColors.Online, contentColor = Color.White),
                        icon = { Icon(painterResource(R.drawable.speaker), null, Modifier.size(18.dp)) },
                        label = { Text(call.channelName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text(
                                when {
                                    call.status == VoiceCall.Status.CONNECTING -> "Connecting…"
                                    call.deafened -> "Deafened"
                                    call.muted -> "Muted"
                                    else -> "Voice connected"
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }

            item {
                Text(
                    "Mentions",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)
                )
            }
            if (mentions.isEmpty()) {
                item { EmptyState(R.drawable.chat, "You're all caught up", "Pings for you show up here.") }
            } else {
                items(mentions.size, key = { mentions[it].key }) { index ->
                    val mention = mentions[index]
                    SwipeAway(onDismiss = { onDismissMention(mention) }) {
                        MentionPreviewCard(
                            mention = mention,
                            modifier = Modifier.transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                            onClick = { onOpenMention(mention) }
                        )
                    }
                }
            }

            item {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    FilledTonalIconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                        Icon(painterResource(R.drawable.settings), contentDescription = "Settings", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Up to three recent DMs as faces with their first name under them, then a button for the full list. */
@Composable
private fun RecentDmRow(dms: List<RecentDm>, otherMentions: Int, onOpen: (RecentDm) -> Unit, onOpenAll: () -> Unit) {
    Row(
        // Pushed down a little: the screen is too narrow near the top for four faces.
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        dms.forEach { dm ->
            FaceButton(label = dm.name.substringBefore(' '), onClick = { onOpen(dm) }) {
                // The ring only ever means status; new messages are the badge or the dot.
                UserAvatar(dm.name, dm.avatarUrl, 36.dp, ring = dm.status?.ringColor())
                if (dm.mentionCount > 0) CountBadge(dm.mentionCount, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp))
                else if (dm.unread) UnreadDot(Modifier.align(Alignment.TopEnd))
            }
        }
        FaceButton(label = "All", onClick = onOpenAll, description = "All direct messages") {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.chat), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            if (otherMentions > 0) CountBadge(otherMentions, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp))
        }
    }
}

@Composable
private fun FaceButton(
    label: String,
    onClick: () -> Unit,
    description: String = label,
    face: @Composable BoxScope.() -> Unit
) {
    Column(
        Modifier
            .width(42.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center, content = face)
        Text(
            label,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** A mention: who and when on top, what they said under it, and where (for servers) at the bottom. */
@Composable
private fun MentionPreviewCard(
    mention: HomeMention,
    modifier: Modifier,
    transformation: SurfaceTransformation,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        transformation = transformation,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UserAvatar(mention.author ?: mention.channelName, mention.avatarUrl, 24.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mention.author ?: if (mention.isDm) mention.channelName else "#${mention.channelName}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    mention.timeMillis?.let {
                        Text(
                            " · " + shortAgo(it),
                            style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    mention.preview ?: if (mention.count == 1) "1 mention" else "${mention.count} mentions",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mention.preview != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!mention.isDm) {
                    Text(
                        "#${mention.channelName}" + (mention.guildName?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (mention.count > 1) CountBadge(mention.count)
        }
    }
}

/** Lets a card be flung sideways off screen; past a third of its width it calls [onDismiss]. */
@Composable
internal fun SwipeAway(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val dismissNow by rememberUpdatedState(onDismiss)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            // Report horizontal scrollability so Wear's swipe-right-to-exit doesn't take the gesture.
            .semantics {
                horizontalScrollAxisRange = ScrollAxisRange(value = { 1f }, maxValue = { 2f })
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> scope.launch { offset.snapTo(offset.value + delta) } },
                onDragStopped = { velocity ->
                    val flung = kotlin.math.abs(velocity) > 1_500f
                    if (kotlin.math.abs(offset.value) > width / 3f || flung) {
                        val dir = if (offset.value != 0f) kotlin.math.sign(offset.value) else kotlin.math.sign(velocity)
                        offset.animateTo(dir * width * 1.2f, tween(160))
                        dismissNow()
                        offset.snapTo(0f)
                    } else {
                        offset.animateTo(0f, tween(160))
                    }
                }
            )
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .graphicsLayer { alpha = 1f - (kotlin.math.abs(offset.value) / width).coerceIn(0f, 0.8f) }
    ) { content() }
}
