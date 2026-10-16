package com.dihcord.app.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
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

    val status = when {
        permissionDenied -> "Dihcord needs the microphone to join voice."
        here?.status == VoiceCall.Status.CONNECTING -> "Connecting…"
        here?.status == VoiceCall.Status.ENDED -> here.error ?: "Disconnected."
        incoming -> "Incoming call"
        isCall && ringing.any { it != me } ->
            "Ringing ${ringing.filter { it != me }.joinToString { nameOf(it) }}…"
        elsewhere != null -> "You're in ${elsewhere.channelName}. Joining moves you here."
        members.isEmpty() -> if (isCall) "No call right now." else "No one is here."
        else -> null
    }
    val statusTone = when {
        permissionDenied || here?.status == VoiceCall.Status.ENDED -> VoiceTone.ERROR
        else -> VoiceTone.QUIET
    }
    val mode = when {
        active -> VoiceMode.Active(muted = here!!.muted, deafened = here.deafened)
        incoming -> VoiceMode.Incoming
        else -> VoiceMode.Idle(
            when {
                !isCall -> "Join voice"
                members.any { it.userId != me } -> "Join call"
                else -> "Call"
            }
        )
    }
    val tiles = members.map { vs ->
        VoiceTile(
            userId = vs.userId,
            name = nameOf(vs.userId, vs.nick),
            avatarUrl = profiles[vs.userId]?.avatarUrl ?: vs.user?.avatarUrl(128),
            speaking = here != null && (vs.userId in here.speaking || (vs.userId == me && here.selfSpeaking)),
            muted = vs.muted,
            deafened = vs.deafened
        )
    }

    VoiceChannelContent(
        channelName = channelName,
        status = status,
        statusTone = statusTone,
        tiles = tiles,
        mode = mode,
        connectedAt = here?.connectedAt?.takeIf { here.status == VoiceCall.Status.CONNECTED },
        privacyCode = here?.privacyCode?.takeIf { it.isNotBlank() && active },
        onToggleMute = { VoiceManager.toggleMute() },
        onToggleDeafen = { VoiceManager.toggleDeafen() },
        onLeave = { VoiceManager.leave() },
        onDecline = {
            CallRinger.cancel(context, channelId)
            scope.launch { repo.declineCall(channelId) }
        },
        onJoin = { tryJoin() }
    )
}

/** Someone in the channel, ready to draw. */
data class VoiceTile(
    val userId: String,
    val name: String,
    val avatarUrl: String?,
    val speaking: Boolean,
    val muted: Boolean,
    val deafened: Boolean,
)

/** What the buttons at the bottom do: in the call, being rung, or neither. */
sealed interface VoiceMode {
    data class Active(val muted: Boolean, val deafened: Boolean) : VoiceMode
    data object Incoming : VoiceMode
    data class Idle(val joinLabel: String) : VoiceMode
}

enum class VoiceTone { QUIET, ERROR }

/**
 * The voice screen from plain data: the people in a circle that fits the round screen (whoever talks
 * lights up green) and who is talking under them. In a call, the call time is on the curve at the top
 * and the controls stay pinned to the bottom, bent along the edge, so they never scroll out of reach.
 */
@Composable
internal fun VoiceChannelContent(
    channelName: String,
    status: String?,
    statusTone: VoiceTone,
    tiles: List<VoiceTile>,
    mode: VoiceMode,
    connectedAt: Long?,
    privacyCode: String?,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onLeave: () -> Unit,
    onDecline: () -> Unit,
    onJoin: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val elapsed = connectedAt?.let { callTimer(it) }
    var showCode by remember { mutableStateOf(false) }
    val timeText: @Composable () -> Unit = {
        if (elapsed != null) TitledTimeText(elapsed, color = SpeakingGreen) else TitledTimeText(channelName)
    }
    // Room under the list for the pinned controls.
    val controlsHeight = if (mode is VoiceMode.Idle) 0.dp else 60.dp
    val body: @Composable BoxScope.(PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(
            state = state,
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = padding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = padding.calculateTopPadding() + if (mode is VoiceMode.Idle) 6.dp else 0.dp,
                bottom = padding.calculateBottomPadding() + controlsHeight
            )
        ) {
            if (tiles.isNotEmpty()) item { VoiceStage(tiles) }

            item {
                val talking = tiles.filter { it.speaking }.map { it.name }
                Column(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // In an encrypted call a small lock sits next to this line; tapping it swaps the line
                    // for the privacy code (to compare with the others) and back.
                    Row(
                        Modifier
                            .fillMaxWidth(0.85f)
                            .then(if (privacyCode != null) Modifier.clickable { showCode = !showCode } else Modifier),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (privacyCode != null) {
                            Icon(
                                painterResource(R.drawable.lock), "Encryption code",
                                Modifier.padding(end = 4.dp).size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            when {
                                showCode && privacyCode != null -> privacyCode.chunked(5).joinToString(" ")
                                talking.size == 1 -> "${talking[0]} is talking"
                                talking.size > 1 -> "${talking.size} people talking"
                                // The name is already on the curve unless the call timer took its place.
                                elapsed != null -> channelName
                                tiles.isNotEmpty() -> "${tiles.size} here"
                                else -> ""
                            },
                            style = if (showCode) MaterialTheme.typography.labelSmall.merge(TabularNumbers) else MaterialTheme.typography.labelMedium,
                            color = when {
                                showCode -> MaterialTheme.colorScheme.onSurfaceVariant
                                talking.isNotEmpty() -> SpeakingGreen
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            // The code is 30 digits: let it wrap, never cut it off.
                            maxLines = if (showCode) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (status != null) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = if (statusTone == VoiceTone.ERROR) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(0.85f)
                        )
                    }
                }
            }

        }

        when (mode) {
            is VoiceMode.Active -> CallControls(Modifier.align(Alignment.BottomCenter)) {
                FilledIconButton(
                    onClick = onToggleMute,
                    colors = if (mode.muted) altIconButtonColors() else IconButtonDefaults.filledTonalIconButtonColors(),
                    modifier = Modifier.padding(bottom = 10.dp).size(44.dp)
                ) {
                    Icon(
                        painterResource(if (mode.muted) R.drawable.mic_off else R.drawable.mic),
                        if (mode.muted) "Unmute" else "Mute"
                    )
                }
                FilledIconButton(
                    onClick = onLeave,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = HangUpRed, contentColor = Color.White),
                    modifier = Modifier.size(width = 56.dp, height = 44.dp)
                ) {
                    Icon(painterResource(R.drawable.call_end), "Leave")
                }
                FilledIconButton(
                    onClick = onToggleDeafen,
                    colors = if (mode.deafened) altIconButtonColors() else IconButtonDefaults.filledTonalIconButtonColors(),
                    modifier = Modifier.padding(bottom = 10.dp).size(44.dp)
                ) {
                    Icon(
                        painterResource(if (mode.deafened) R.drawable.headset_off else R.drawable.headset),
                        if (mode.deafened) "Undeafen" else "Deafen"
                    )
                }
            }
            VoiceMode.Incoming -> CallControls(Modifier.align(Alignment.BottomCenter), gap = 18.dp) {
                FilledIconButton(
                    onClick = onDecline,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = HangUpRed, contentColor = Color.White),
                    modifier = Modifier.size(50.dp)
                ) { Icon(painterResource(R.drawable.call_end), "Decline") }
                FilledIconButton(
                    onClick = onJoin,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = SpeakingGreen, contentColor = Color.White),
                    modifier = Modifier.size(50.dp)
                ) { Icon(painterResource(R.drawable.speaker), "Accept") }
            }
            is VoiceMode.Idle -> {}
        }
    }

    if (mode is VoiceMode.Idle) {
        ScreenScaffold(
            scrollState = state,
            timeText = timeText,
            edgeButton = {
                EdgeButton(
                    onClick = onJoin,
                    colors = ButtonDefaults.buttonColors(containerColor = SpeakingGreen, contentColor = Color.White)
                ) {
                    Icon(painterResource(R.drawable.speaker), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(mode.joinLabel)
                }
            },
            content = { body(it) }
        )
    } else {
        ScreenScaffold(scrollState = state, timeText = timeText, content = { body(it) })
    }
}

/** The call buttons, bottom-aligned in a row so the raised outer ones follow the round edge. */
@Composable
private fun CallControls(modifier: Modifier, gap: Dp = 6.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black))
            .padding(top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
        content = content
    )
}

/** "04:12", counting up once a second from [since]. */
@Composable
private fun callTimer(since: Long): String {
    val now by produceState(System.currentTimeMillis(), since) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val secs = ((now - since) / 1000).coerceAtLeast(0)
    return if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
    else "%02d:%02d".format(secs / 60, secs % 60)
}

/**
 * Up to seven people placed evenly around a circle (one alone sits in the middle, two side by side),
 * sized so the ring fits the round screen. More than that falls back to rows of tiles with names.
 */
@Composable
private fun VoiceStage(tiles: List<VoiceTile>) {
    if (tiles.size > 7) {
        val rows = ServerLayout.honeycombRows(tiles)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    row.forEach { VoiceMemberTile(it.name, it.avatarUrl, it.speaking, it.muted, it.deafened) }
                }
            }
        }
        return
    }
    val n = tiles.size
    val (avatar, radius) = when (n) {
        1 -> 64.dp to 0.dp
        2 -> 50.dp to 32.dp
        in 3..5 -> 40.dp to 32.dp
        else -> 32.dp to 36.dp
    }
    // From three people on, the top of the circle is a person and the bottom falls between two, so
    // the ring needs (1 + cos(180°/n)) radii of height rather than two.
    val above = if (n >= 3) radius else 0.dp
    val below = if (n >= 3) radius * kotlin.math.cos(Math.PI / n).toFloat() else 0.dp
    val height = above + below + avatar + 4.dp
    androidx.compose.ui.layout.Layout(
        modifier = Modifier.fillMaxWidth().height(height),
        content = {
            tiles.forEach { VoiceAvatar(it.name, it.avatarUrl, avatar, it.speaking, it.muted, it.deafened) }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints()) }
        val w = constraints.maxWidth
        val r = radius.toPx()
        val cx = w / 2f
        val cy = above.toPx() + avatar.toPx() / 2 + 2.dp.toPx()
        layout(w, height.roundToPx()) {
            placeables.forEachIndexed { i, p ->
                val (dx, dy) = when (n) {
                    1 -> 0f to 0f
                    2 -> (if (i == 0) -r else r) to 0f
                    else -> {
                        val angle = -Math.PI / 2 + 2 * Math.PI * i / n
                        (r * kotlin.math.cos(angle)).toFloat() to (r * kotlin.math.sin(angle)).toFloat()
                    }
                }
                p.place((cx + dx - p.width / 2f).toInt(), (cy + dy - p.height / 2f).toInt())
            }
        }
    }
}
