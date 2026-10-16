package com.dihcord.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.dihcord.app.SetupPreferences
import com.dihcord.app.api.Ping
import com.dihcord.app.discordApp
import com.dihcord.app.R
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Jump target meaning "the first message after the read marker that mentions me". */
const val JUMP_TO_FIRST_MENTION = "mention"


@Composable
fun HomeScreen(
    onNavigateToDms: () -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToWelcome: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChat: (channelId: String, channelName: String, guildId: String?, jumpTo: String?) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()

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

    // Mention counts for badges
    val dmIds = remember(dmChannels) { dmChannels.map { it.id }.toSet() }
    val dmMentionCount = remember(readState, dmIds) {
        readState.entries.filter { it.key in dmIds }.sumOf { it.value.mentionCount }
    }
    val serverMentionCount = remember(readState, dmIds) {
        readState.entries.filter { it.key !in dmIds }.sumOf { it.value.mentionCount }
    }

    val channelNames by repo.channelNames.collectAsState()

    fun dismiss(channelId: String) {
        repo.dismissMentions(channelId)
        com.dihcord.app.Notifier.clear(context, channelId)
    }
    val channelGuilds by repo.channelGuilds.collectAsState()

    data class MentionEntry(
        val channelId: String,
        val channelName: String,
        val guildName: String?,
        val count: Int,
        val isDm: Boolean
    )

    // Fetch names for mentioned channels we haven't cached yet, once per distinct set
    // (not from inside remember{}, which re-ran on every readState change).
    val uncachedMentionIds = remember(readState, channelNames) {
        readState.entries
            .filter { it.value.mentionCount > 0 && it.key !in channelNames }
            .map { it.key }
            .toSet()
    }
    LaunchedEffect(uncachedMentionIds) {
        uncachedMentionIds.forEach { repo.ensureChannelCached(it) }
    }

    val mentionEntries = remember(readState, dmIds, channelNames, channelGuilds, guilds) {
        readState.entries
            .filter { it.value.mentionCount > 0 }
            .map { (channelId, state) ->
                val isDm = channelId in dmIds
                val chName = channelNames[channelId] ?: channelId
                val guildId = channelGuilds[channelId]
                val guildName = guildId?.let { id -> guilds.firstOrNull { it.id == id }?.name }
                MentionEntry(channelId, chName, guildName, state.mentionCount, isDm)
            }
            .sortedByDescending { it.count }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {

            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ScreenTitle("Dihcord")
                    PrideBar(Modifier.padding(bottom = 4.dp))
                }
            }

            item {
                Box {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavigateToDms,
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Direct Messages") }
                    if (dmMentionCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp, end = 2.dp)
                                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .background(
                                    androidx.compose.ui.graphics.Color(0xFFF23F43),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (dmMentionCount > 99) "99+" else dmMentionCount.toString(),
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                Box {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onNavigateToServers,
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Servers") }
                    if (serverMentionCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp, end = 2.dp)
                                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .background(
                                    androidx.compose.ui.graphics.Color(0xFFF23F43),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (serverMentionCount > 99) "99+" else serverMentionCount.toString(),
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (mentionEntries.isNotEmpty() || pings.isNotEmpty()) {
                item {
                    Text(
                        text = "@  Mentions",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                // readState-derived cards (visible from startup)
                // Swipe a card sideways to clear it; tap it to open the chat at the mention.
                items(mentionEntries.size, key = { "m" + mentionEntries[it].channelId }) { index ->
                    val entry = mentionEntries[index]
                    SwipeAway(onDismiss = { dismiss(entry.channelId) }) {
                        MentionCard(
                            title = if (entry.isDm) entry.channelName else "#${entry.channelName}",
                            subtitle = if (entry.isDm) "Direct message" else entry.guildName ?: "Server",
                            count = entry.count,
                            onClick = {
                                // The newest live ping there is the exact message; otherwise find the first
                                // message after the read marker that mentions us.
                                val ping = pings.firstOrNull { it.message.channelId == entry.channelId }
                                onNavigateToChat(
                                    entry.channelId,
                                    entry.channelName,
                                    if (entry.isDm) null else channelGuilds[entry.channelId],
                                    ping?.message?.id ?: JUMP_TO_FIRST_MENTION
                                )
                            }
                        )
                    }
                }
                // live gateway pings not yet in readState (e.g. arrived after last ack)
                val extraPings = pings.filter { p -> mentionEntries.none { it.channelId == p.message.channelId } }
                items(extraPings.size, key = { "p" + extraPings[it].message.id }) { index ->
                    val ping = extraPings[index]
                    SwipeAway(onDismiss = { dismiss(ping.message.channelId) }) {
                        PingCard(ping = ping, onClick = {
                            onNavigateToChat(
                                ping.message.channelId,
                                ping.channelName,
                                ping.message.guildId,
                                ping.message.id
                            )
                        })
                    }
                }
            } else {
                item {
                    Text(
                        "No mentions yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            item {
                FilledIconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .height(40.dp)
                        .width(40.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.settings),
                        contentDescription = "Settings"
                    )
                }
            }
        }
    }
}

/** Lets a card be flung sideways off screen; past a third of its width it calls [onDismiss]. */
@Composable
private fun SwipeAway(onDismiss: () -> Unit, content: @Composable () -> Unit) {
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

@Composable
private fun MentionCard(title: String, subtitle: String, count: Int, onClick: () -> Unit) {
    TitleCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (count > 99) "99+" else "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFFF23F43),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PingCard(ping: Ping, onClick: () -> Unit) {
    val location = if (ping.guildName != null) "${ping.guildName} • #${ping.channelName}"
    else "DM • ${ping.channelName}"

    TitleCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ping.message.author.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    ) {
        Text(
            text = ping.message.content.take(80),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
