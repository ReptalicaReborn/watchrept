package com.dihcord.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dihcord.app.R
import com.dihcord.app.SetupPreferences
import com.dihcord.app.api.Channel
import com.dihcord.app.api.ChannelType
import com.dihcord.app.api.compareSnowflakes
import com.dihcord.app.discordApp
import com.dihcord.app.voice.VoiceManager

/** What kind of channel a row is, which picks its icon and what tapping it opens. */
enum class ChannelKind { TEXT, NEWS, RULES, FORUM, VOICE }

/** One channel in the list, as plain data. */
data class ChannelRow(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val locked: Boolean,
    val mentionCount: Int,
    val unread: Boolean,
    /** People in a voice channel. */
    val voice: List<VoiceTile> = emptyList(),
)

/** A category and its channels; [id]/[name] are null for channels outside any category. */
data class ChannelSection(val id: String?, val name: String?, val rows: List<ChannelRow>)

@Composable
fun ServerChannels(
    guildId: String,
    guildName: String,
    onNavigateToChatScreen: (channelId: String, channelName: String) -> Unit,
    onOpenForum: (channelId: String, channelName: String) -> Unit = { _, _ -> },
    onOpenVoice: (channelId: String, channelName: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    var hideInaccessible by remember { mutableStateOf(SetupPreferences.getHideInaccessibleChannels(context)) }
    val showMentionBadges = remember { SetupPreferences.getShowMentionBadges(context) }
    val readState by repo.readState.collectAsState()
    val guildChannelsMap by repo.guildChannelsMap.collectAsState()
    val voiceStates by repo.voiceStates.collectAsState()
    val voiceCall by VoiceManager.call.collectAsState()
    val voiceProfiles by repo.voiceProfiles.collectAsState()
    val myId = repo.currentUser.collectAsState().value?.id
    // Who is in each voice channel of this server.
    val voiceMembers = remember(voiceStates, guildId) {
        voiceStates.values.filter { it.guildId == guildId && it.channelId != null }.groupBy { it.channelId!! }
    }
    LaunchedEffect(voiceMembers.keys, voiceMembers.values.sumOf { it.size }) {
        repo.loadVoiceProfiles(guildId, voiceMembers.values.flatten().map { it.userId })
    }

    val groups = guildChannelsMap[guildId]
        ?: remember { repo.getCachedChannels(guildId, filterInaccessible = hideInaccessible) }
        ?: emptyList()
    var loading by remember { mutableStateOf(groups.isEmpty()) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(guildId) {
        hideInaccessible = SetupPreferences.getHideInaccessibleChannels(context)
        if (groups.isEmpty()) {
            val cached = repo.getCachedChannels(guildId, filterInaccessible = hideInaccessible)
            if (!cached.isNullOrEmpty()) repo.cacheChannelNames(cached)
        }
        repo.refreshGuildChannels(guildId, filterInaccessible = hideInaccessible)
            .onFailure { if (groups.isEmpty()) error = it.message ?: "Couldn't load the channels." }
        loading = false
    }

    val sections = remember(groups, readState, voiceMembers, voiceCall, voiceProfiles, hideInaccessible, myId) {
        val all = groups.flatMap { it.channels }
        val rulesId = all.firstOrNull { it.name.contains("rule", ignoreCase = true) }?.id
        groups.map { group ->
            ChannelSection(
                id = group.category?.id,
                name = group.category?.name,
                rows = group.channels
                    .filter { it.hasAccess || !hideInaccessible }
                    .map { ch ->
                        val rs = if (showMentionBadges) readState[ch.id] else null
                        val inCall = voiceCall?.takeIf { it.channelId == ch.id }
                        ChannelRow(
                            id = ch.id,
                            name = ch.name,
                            kind = ch.kind(rulesId),
                            locked = !ch.hasAccess,
                            mentionCount = rs?.mentionCount ?: 0,
                            unread = rs != null && ch.lastMessageId != null &&
                                    compareSnowflakes(ch.lastMessageId, rs.lastMessageId) > 0,
                            voice = if (!ch.isVoice) emptyList() else voiceMembers[ch.id].orEmpty().map { vs ->
                                VoiceTile(
                                    userId = vs.userId,
                                    name = vs.nick ?: voiceProfiles[vs.userId]?.name ?: repo.getDisplayName(vs.userId)
                                    ?: vs.user?.displayName ?: "Someone",
                                    avatarUrl = voiceProfiles[vs.userId]?.avatarUrl ?: vs.user?.avatarUrl(64),
                                    speaking = inCall != null && (vs.userId in inCall.speaking || (vs.userId == myId && inCall.selfSpeaking)),
                                    muted = vs.muted,
                                    deafened = vs.deafened
                                )
                            }
                        )
                    }
            )
        }.filter { it.rows.isNotEmpty() }
    }

    ServerChannelsContent(
        guildName = guildName,
        sections = sections,
        loading = loading,
        error = error.takeIf { it.isNotEmpty() },
        onOpen = { row ->
            when (row.kind) {
                ChannelKind.VOICE -> onOpenVoice(row.id, row.name)
                ChannelKind.FORUM -> onOpenForum(row.id, row.name)
                else -> onNavigateToChatScreen(row.id, row.name)
            }
        }
    )
}

private fun Channel.kind(rulesId: String?): ChannelKind = when {
    isVoice -> ChannelKind.VOICE
    isForum -> ChannelKind.FORUM
    type == ChannelType.GUILD_NEWS -> ChannelKind.NEWS
    id == rulesId -> ChannelKind.RULES
    else -> ChannelKind.TEXT
}

/**
 * A server's channels, drawn from plain data. Unread
 * channels are bright and bold, read ones grey (as in Discord); a category folds away when tapped.
 */
@Composable
internal fun ServerChannelsContent(
    guildName: String,
    sections: List<ChannelSection>,
    loading: Boolean,
    error: String?,
    onOpen: (ChannelRow) -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    var folded by rememberSaveable { mutableStateOf(setOf<String>()) }
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle(guildName) }
            when {
                loading && sections.isEmpty() -> items(4) { ChannelSkeletonRow(Modifier.transformedHeight(this, spec)) }
                error != null -> item { EmptyState(R.drawable.lock, "Couldn't load channels", error) }
                sections.isEmpty() -> item { EmptyState(R.drawable.chat, "No channels here") }
                else -> sections.forEach { section ->
                    val key = section.id
                    if (section.name != null && key != null) {
                        item(key = "c$key") {
                            val isFolded = key in folded
                            val unseen = isFolded && section.rows.any { it.unread || it.mentionCount > 0 }
                            CategoryHeader(section.name, isFolded, unseen) {
                                folded = if (isFolded) folded - key else folded + key
                            }
                        }
                    }
                    if (key == null || key !in folded) {
                        items(section.rows.size, key = { section.rows[it].id }) { i ->
                            val row = section.rows[i]
                            ChannelButton(
                                row = row,
                                modifier = Modifier.transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                                onClick = { onOpen(row) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(name: String, folded: Boolean, unseen: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
    ) {
        Icon(
            painterResource(R.drawable.chevron),
            contentDescription = if (folded) "Show" else "Hide",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp).rotate(if (folded) -90f else 0f)
        )
        Text(
            name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (unseen) UnreadDot()
    }
}

@Composable
private fun ChannelButton(
    row: ChannelRow,
    modifier: Modifier,
    transformation: SurfaceTransformation,
    onClick: () -> Unit
) {
    val bright = row.unread || row.mentionCount > 0
    val textColor = when {
        row.locked -> Color.White.copy(alpha = 0.4f)
        bright -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        transformation = transformation,
        colors = ButtonDefaults.filledTonalButtonColors(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        icon = {
            val icon = when {
                row.locked -> R.drawable.lock
                row.kind == ChannelKind.VOICE -> R.drawable.speaker
                row.kind == ChannelKind.NEWS -> R.drawable.announce
                row.kind == ChannelKind.RULES -> R.drawable.rules
                row.kind == ChannelKind.FORUM -> R.drawable.chat
                else -> null
            }
            val description = when {
                row.locked -> "Locked"
                row.kind == ChannelKind.VOICE -> "Voice"
                row.kind == ChannelKind.NEWS -> "Announcement"
                row.kind == ChannelKind.RULES -> "Rules"
                row.kind == ChannelKind.FORUM -> "Forum"
                else -> null
            }
            if (icon != null) Icon(painterResource(icon), description, Modifier.size(18.dp), tint = textColor)
            else Text("#", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = textColor, modifier = Modifier.width(18.dp))
        },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    color = textColor,
                    fontWeight = if (bright) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (row.mentionCount > 0) CountBadge(row.mentionCount, Modifier.padding(start = 4.dp))
                else if (row.unread) UnreadDot(Modifier.padding(start = 4.dp), color = Color.White)
            }
        },
        secondaryLabel = if (row.voice.isEmpty()) null else {
            { VoiceFaces(row.voice) }
        }
    )
}

/** Up to four overlapping faces (talking ones ringed green) and how many are in the channel. */
@Composable
private fun VoiceFaces(people: List<VoiceTile>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        people.take(4).forEachIndexed { i, p ->
            Box(
                Modifier
                    .offset(x = (-6 * i).dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                    .padding(1.5.dp)
            ) {
                UserAvatar(p.name, p.avatarUrl, 20.dp, ring = if (p.speaking) DiscordColors.Online else null)
            }
        }
        val extraOffset = (-6 * (people.take(4).size - 1)).dp
        Text(
            if (people.size == 1) people[0].name else "${people.size} in voice",
            style = MaterialTheme.typography.labelSmall,
            color = if (people.any { it.speaking }) DiscordColors.Online else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(x = extraOffset).padding(start = 4.dp)
        )
    }
}

/** A grey stand-in channel row while the list loads. */
@Composable
private fun ChannelSkeletonRow(modifier: Modifier = Modifier) {
    val shade = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(18.dp).background(shade, CircleShape))
        Box(Modifier.width(90.dp).height(10.dp).background(shade, RoundedCornerShape(5.dp)))
    }
}
