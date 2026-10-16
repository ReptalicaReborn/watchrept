package com.dihcord.app.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dihcord.app.FeaturePreferences
import com.dihcord.app.R
import com.dihcord.app.api.*
import com.dihcord.app.discordApp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Invites

/** Join a server: from a tapped invite ([initialCode]), or by typing a link or code. */
@Composable
fun InviteScreen(initialCode: String?, onOpenServer: (guildId: String, name: String) -> Unit) {
    val repo = LocalContext.current.discordApp.repository ?: return
    val scope = rememberCoroutineScope()
    val guilds by repo.guilds.collectAsState()
    var input by remember { mutableStateOf(initialCode.orEmpty()) }
    val code = InvitePreview.codeFrom(input)
    var preview by remember { mutableStateOf<InvitePreview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var joining by remember { mutableStateOf(false) }
    LaunchedEffect(code) {
        preview = null; error = null
        if (code == null) return@LaunchedEffect
        loading = true
        repo.invitePreview(code).onSuccess { preview = it }.onFailure { error = it }
        loading = false
    }
    val member = preview?.guild?.let { g -> guilds.any { it.id == g.id } } == true
    InviteContent(
        input = input, onInput = { input = it.take(100) }, showInput = initialCode == null,
        preview = preview, loading = loading, error = error, joining = joining, alreadyMember = member,
        onJoin = {
            val p = preview ?: return@InviteContent
            val g = p.guild
            if (member && g != null) { onOpenServer(g.id, g.name); return@InviteContent }
            joining = true; error = null
            scope.launch {
                repo.joinInvite(p.code).onSuccess { if (g != null) onOpenServer(g.id, g.name) }.onFailure { error = it }
                joining = false
            }
        }
    )
}

@Composable
internal fun InviteContent(
    input: String,
    onInput: (String) -> Unit,
    showInput: Boolean,
    preview: InvitePreview?,
    loading: Boolean,
    error: Throwable?,
    joining: Boolean,
    alreadyMember: Boolean,
    onJoin: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = state,
        edgeButton = {
            if (preview != null) {
                EdgeButton(
                    onClick = onJoin, enabled = !joining,
                    colors = ButtonDefaults.buttonColors(containerColor = DiscordColors.Online, contentColor = androidx.compose.ui.graphics.Color.White)
                ) { Text(when { joining -> "Joining…"; alreadyMember -> "Open"; else -> "Join" }) }
            }
        }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle(if (showInput) "Join a server" else "Invite") }
            if (showInput) item {
                CompactTextField(input, onInput, Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 48.dp), placeholder = "Invite link or code", singleLine = true)
            }
            when {
                error != null -> item { ErrorState(if (error is DiscordApiException && error.httpCode == 404) "This invite doesn't work" else "Couldn't open the invite", error) }
                loading -> item { LoadingIndicator() }
                preview != null -> item {
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val g = preview.guild
                        UserAvatar(g?.name ?: "?", g?.iconUrl(128), 48.dp)
                        Text(g?.name ?: "A server", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                        val counts = listOfNotNull(
                            preview.memberCount?.let { "%,d members".format(it) },
                            preview.onlineCount?.let { "%,d online".format(it) },
                        ).joinToString(" · ")
                        if (counts.isNotEmpty()) Text(counts, style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        preview.channelName?.let { Text("#$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (alreadyMember) Text("You're already in this server", style = MaterialTheme.typography.labelSmall, color = DiscordColors.Online, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                showInput -> item { EmptyState(R.drawable.servers, "Paste or type an invite", "Like discord.gg/minecraft") }
            }
        }
    }
}

// ---------------------------------------------------------------- Nickname

@Composable
fun NicknameScreen(guildId: String, guildName: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val scope = rememberCoroutineScope()
    val me = repo.currentUser.collectAsState().value
    var nick by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(guildId, me?.id) {
        val id = me?.id ?: return@LaunchedEffect
        nick = repo.getGuildMember(guildId, id)?.nick.orEmpty()
    }
    NicknameContent(
        guildName, nick, placeholder = me?.displayName ?: "", saving = saving, error = error,
        onChange = { nick = it.take(32) },
        onSave = {
            saving = true; error = null
            scope.launch {
                repo.setNickname(guildId, nick.orEmpty().trim())
                    .onSuccess { Toast.makeText(context, "Nickname saved", Toast.LENGTH_SHORT).show(); onDone() }
                    .onFailure { error = it }
                saving = false
            }
        }
    )
}

@Composable
internal fun NicknameContent(
    guildName: String,
    nick: String?,
    placeholder: String,
    saving: Boolean,
    error: Throwable?,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = state,
        edgeButton = { EdgeButton(onClick = onSave, enabled = nick != null && !saving) { Text(if (saving) "Saving…" else "Save") } }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Your name") }
            item { ScreenSubtitle("in $guildName") }
            if (nick == null) item { LoadingIndicator() } else {
                item { CompactTextField(nick, onChange, Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 48.dp), placeholder = placeholder, singleLine = true) }
                item { Text("Leave it empty to use your account name.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            }
            if (error != null) item {
                Text(
                    if (error is DiscordApiException && error.httpCode == 403) "This server doesn't let you change your name." else "Couldn't save. " + friendlyError(error),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Alert level

@Composable
fun AlertLevelScreen(guildId: String, guildName: String) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(FeaturePreferences.alertLevel(context, guildId)) }
    AlertLevelContent(guildName, level) {
        FeaturePreferences.setAlertLevel(context, guildId, it)
        level = it
    }
}

@Composable
internal fun AlertLevelContent(guildName: String, level: FeaturePreferences.AlertLevel, onPick: (FeaturePreferences.AlertLevel) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Alerts") }
            item { ScreenSubtitle(guildName) }
            items(FeaturePreferences.AlertLevel.entries.size) { i ->
                val l = FeaturePreferences.AlertLevel.entries[i]
                RadioButton(
                    selected = l == level,
                    onSelect = { onPick(l) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(l.label) },
                    secondaryLabel = when (l) {
                        FeaturePreferences.AlertLevel.DISCORD -> ({ Text("What you chose in Discord") })
                        FeaturePreferences.AlertLevel.NOTHING -> ({ Text("Badges still show") })
                        else -> null
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Events

/** Upcoming and live events of a server, shown at the top of its channel list. */
@Composable
internal fun EventCard(event: ScheduledEvent, interested: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(R.drawable.event), null, Modifier.size(12.dp), tint = if (event.isLive) DiscordColors.Online else MaterialTheme.colorScheme.primary)
            Text(
                if (event.isLive) "LIVE NOW" else event.startMillis?.let { com.dihcord.app.api.DiscordTimestamp.format(it / 1000, 'f') } ?: "Soon",
                style = MaterialTheme.typography.labelSmall,
                color = if (event.isLive) DiscordColors.Online else MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(event.name, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(event.location, if (event.userCount > 0) "${event.userCount} interested" else null).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CompactButton(
            onClick = onToggle,
            label = { Text(if (interested) "Interested ✓" else "Interested") },
            colors = if (interested) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
        )
    }
}

// ---------------------------------------------------------------- Soundboard

@Composable
fun SoundboardScreen(guildId: String?, voiceChannelId: String) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val scope = rememberCoroutineScope()
    var sounds by remember { mutableStateOf<List<SoundboardSound>?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(guildId) { sounds = repo.soundboard(guildId) }
    SoundboardContent(sounds, playing) { sound ->
        playing = sound.id
        scope.launch {
            repo.playSound(voiceChannelId, sound).onFailure {
                Toast.makeText(
                    context,
                    if (it is DiscordApiException && it.httpCode == 403) "This server doesn't allow the soundboard" else "Couldn't play it. " + friendlyError(it),
                    Toast.LENGTH_SHORT
                ).show()
            }
            playing = null
        }
    }
}

@Composable
internal fun SoundboardContent(sounds: List<SoundboardSound>?, playing: String?, onPlay: (SoundboardSound) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Soundboard") }
            when {
                sounds == null -> item { LoadingIndicator() }
                sounds.isEmpty() -> item { EmptyState(R.drawable.sound, "No sounds here") }
                else -> sounds.chunked(3).forEachIndexed { r, row ->
                    item(key = "r$r") {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                            row.forEach { s ->
                                Column(Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier.size(42.dp).clip(CircleShape)
                                            .background(if (playing == s.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                                            .clickable { onPlay(s) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (s.emoji != null) Text(s.emoji, fontSize = 18.sp)
                                        else Icon(painterResource(R.drawable.sound), s.name, Modifier.size(18.dp))
                                    }
                                    Text(s.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
