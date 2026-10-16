package com.dihcord.app.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateColorAsState
import coil.compose.AsyncImage
import coil.imageLoader
import com.dihcord.app.api.ServerLayout
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.dihcord.app.R
import com.dihcord.app.discordApp
import com.dihcord.app.voice.CallRinger
import com.dihcord.app.voice.VoiceCall
import kotlinx.coroutines.launch
import com.dihcord.app.voice.VoiceManager

private val SpeakingGreen = Color(0xFF23A55A)
private val HangUpRed = Color(0xFFDA373C)

/** A round avatar (or initial) with a green ring while [speaking], and a mute/deafen badge. */
@Composable
fun VoiceAvatar(
    name: String,
    avatarUrl: String?,
    size: Dp,
    speaking: Boolean,
    muted: Boolean,
    deafened: Boolean,
) {
    val ring by animateColorAsState(if (speaking) SpeakingGreen else Color.Transparent, label = "speaking")
    val ringWidth = if (size >= 32.dp) 3.dp else 2.dp
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .border(ringWidth, ring, CircleShape)
                .padding(ringWidth + 1.dp)
                .clip(CircleShape)
                .background(avatarColor(name)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Bold
            )
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    imageLoader = LocalContext.current.imageLoader,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (deafened || muted) {
            val badge = size * 0.36f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .background(Color.Black, CircleShape)
                    .padding(1.dp)
                    .background(HangUpRed, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(if (deafened) R.drawable.headset_off else R.drawable.mic_off),
                    if (deafened) "Deafened" else "Muted",
                    Modifier.size(badge * 0.62f),
                    tint = Color.White
                )
            }
        }
    }
}

/** A stable colour per name for avatar-less users. */
private fun avatarColor(name: String): Color {
    val palette = listOf(0xFF5865F2, 0xFF3BA55C, 0xFFFAA61A, 0xFFED4245, 0xFFEB459E, 0xFF747F8D)
    return Color(palette[(name.hashCode() and 0x7FFFFFFF) % palette.size])
}

/** One person under a voice channel in the channel list: small avatar, name. */
@Composable
fun VoiceMemberLine(
    name: String,
    avatarUrl: String?,
    speaking: Boolean,
    muted: Boolean,
    deafened: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        VoiceAvatar(name, avatarUrl, 22.dp, speaking, muted, deafened)
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = if (speaking) Color.White else Color.White.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** A member tile on the call screen: avatar with name below, like the server icons. */
@Composable
private fun VoiceMemberTile(name: String, avatarUrl: String?, speaking: Boolean, muted: Boolean, deafened: Boolean) {
    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        VoiceAvatar(name, avatarUrl, 48.dp, speaking, muted, deafened)
        Text(
            name,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (speaking) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun VoiceChannelScreen(
    guildId: String?,
    channelId: String,
    channelName: String,
    /** Answer the incoming call straight away (from the notification's Accept button). */
    answer: Boolean = false,
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val listState = rememberScalingLazyListState()
    val call by VoiceManager.call.collectAsState()
    val voiceStates by repo.voiceStates.collectAsState()
    val me = repo.currentUser.collectAsState().value?.id
    val calls by repo.calls.collectAsState()
    val profiles by repo.voiceProfiles.collectAsState()
    val dmChannels by repo.dmChannels.collectAsState()
    val isCall = guildId == null
    val recipients = remember(dmChannels, channelId) { dmChannels.firstOrNull { it.id == channelId }?.recipients.orEmpty() }
    val ringing = calls[channelId].orEmpty()
    val incoming = isCall && me != null && me in ringing
    fun nameOf(userId: String, nick: String? = null): String =
        nick ?: profiles[userId]?.name ?: repo.getDisplayName(userId) ?: recipients.firstOrNull { it.id == userId }?.displayName
        ?: repo.currentUser.value?.takeIf { it.id == userId }?.displayName ?: "Someone"

    val here = call?.takeIf { it.channelId == channelId }
    val active = here != null && here.status != VoiceCall.Status.ENDED
    val elsewhere = call?.takeIf { it.channelId != channelId && VoiceManager.isInCall }
    val members = remember(voiceStates, channelId) {
        voiceStates.values.filter { it.channelId == channelId }.sortedBy { it.userId != me }
    }
    LaunchedEffect(members.map { it.userId }) { repo.loadVoiceProfiles(guildId, members.map { it.userId }) }
    var permissionDenied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val join = {
        // Starting a DM/group call (no one else in it yet) rings the others.
        val ring = isCall && members.none { it.userId != me }
        VoiceManager.join(context, repo, guildId, channelId, channelName, ring = ring)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) join()
    }

    val tryJoin = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) join() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) {
        if (answer && !active) tryJoin()
        if (incoming || answer) CallRinger.cancel(context, channelId)
    }

    // A call that ended with an error is shown here once; forget it when leaving the screen.
    DisposableEffect(Unit) { onDispose { VoiceManager.dismiss() } }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item { ScreenTitle(channelName) }

            item {
                val status = when {
                    permissionDenied -> "Dihcord needs the microphone to join voice."
                    here?.status == VoiceCall.Status.CONNECTING -> "Connecting…"
                    here?.status == VoiceCall.Status.ENDED -> here.error ?: "Disconnected."
                    incoming -> "Incoming call"
                    isCall && ringing.any { it != me } ->
                        "Ringing ${ringing.filter { it != me }.joinToString { nameOf(it) }}…"
                    here?.status == VoiceCall.Status.CONNECTED -> "Voice connected"
                    elsewhere != null -> "You're in ${elsewhere.channelName}. Joining moves you here."
                    members.isEmpty() -> if (isCall) "No call right now." else "No one is here."
                    else -> null
                }
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = when {
                            permissionDenied || here?.status == VoiceCall.Status.ENDED -> MaterialTheme.colorScheme.error
                            here?.status == VoiceCall.Status.CONNECTED -> SpeakingGreen
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            val rows = ServerLayout.honeycombRows(members)
            items(rows.size) { r ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    rows[r].forEach { vs ->
                        val speaking = here != null &&
                                (vs.userId in here.speaking || (vs.userId == me && here.selfSpeaking))
                        VoiceMemberTile(
                            name = nameOf(vs.userId, vs.nick),
                            avatarUrl = profiles[vs.userId]?.avatarUrl ?: vs.user?.avatarUrl(128),
                            speaking = speaking,
                            muted = vs.muted,
                            deafened = vs.deafened
                        )
                    }
                }
            }

            item {
                if (active) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                    ) {
                        FilledIconButton(
                            onClick = { VoiceManager.toggleMute() },
                            colors = if (here!!.muted) altIconButtonColors() else IconButtonDefaults.filledIconButtonColors(),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                painterResource(if (here.muted) R.drawable.mic_off else R.drawable.mic),
                                if (here.muted) "Unmute" else "Mute"
                            )
                        }
                        FilledIconButton(
                            onClick = { VoiceManager.toggleDeafen() },
                            colors = if (here!!.deafened) altIconButtonColors() else IconButtonDefaults.filledIconButtonColors(),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                painterResource(if (here.deafened) R.drawable.headset_off else R.drawable.headset),
                                if (here.deafened) "Undeafen" else "Deafen"
                            )
                        }
                        FilledIconButton(
                            onClick = { VoiceManager.leave() },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = HangUpRed, contentColor = Color.White),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(painterResource(R.drawable.call_end), "Leave")
                        }
                    }
                } else if (incoming) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                    ) {
                        FilledIconButton(
                            onClick = {
                                CallRinger.cancel(context, channelId)
                                scope.launch { repo.declineCall(channelId) }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = HangUpRed, contentColor = Color.White),
                            modifier = Modifier.size(48.dp)
                        ) { Icon(painterResource(R.drawable.call_end), "Decline") }
                        FilledIconButton(
                            onClick = { tryJoin() },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = SpeakingGreen, contentColor = Color.White),
                            modifier = Modifier.size(48.dp)
                        ) { Icon(painterResource(R.drawable.speaker), "Accept") }
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SpeakingGreen, contentColor = Color.White),
                        icon = { Icon(painterResource(R.drawable.speaker), null, Modifier.size(18.dp)) },
                        label = {
                            Text(
                                when {
                                    !isCall -> "Join voice"
                                    members.any { it.userId != me } -> "Join call"
                                    else -> "Call"
                                }
                            )
                        },
                        onClick = { tryJoin() }
                    )
                }
            }

            here?.privacyCode?.takeIf { it.isNotBlank() && active }?.let { code ->
                item {
                    Text(
                        "End-to-end encrypted\n${code.chunked(5).joinToString(" ")}",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
