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
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.dihcord.app.R
import com.dihcord.app.discordApp
import com.dihcord.app.voice.VoiceCall
import com.dihcord.app.voice.VoiceManager

private val SpeakingGreen = Color(0xFF23A55A)
private val HangUpRed = Color(0xFFDA373C)

/** One person in a voice channel: a speaking dot, their name, and mute/deafen marks. */
@Composable
fun VoiceMemberLine(
    name: String,
    speaking: Boolean,
    muted: Boolean,
    deafened: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .then(
                    if (speaking) Modifier.background(SpeakingGreen, CircleShape)
                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                )
        )
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = if (speaking) Color.White else Color.White.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (deafened) {
            Icon(painterResource(R.drawable.headset_off), "Deafened", Modifier.size(12.dp), tint = Color.White.copy(alpha = 0.6f))
        } else if (muted) {
            Icon(painterResource(R.drawable.mic_off), "Muted", Modifier.size(12.dp), tint = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun VoiceChannelScreen(guildId: String?, channelId: String, channelName: String) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val listState = rememberScalingLazyListState()
    val call by VoiceManager.call.collectAsState()
    val voiceStates by repo.voiceStates.collectAsState()
    val me = repo.currentUser.collectAsState().value?.id

    val here = call?.takeIf { it.channelId == channelId }
    val active = here != null && here.status != VoiceCall.Status.ENDED
    val elsewhere = call?.takeIf { it.channelId != channelId && VoiceManager.isInCall }
    val members = remember(voiceStates, channelId) {
        voiceStates.values.filter { it.channelId == channelId }.sortedBy { it.userId != me }
    }
    var permissionDenied by remember { mutableStateOf(false) }

    val join = { VoiceManager.join(context, repo, guildId, channelId, channelName) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) join()
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
                    here?.status == VoiceCall.Status.CONNECTED -> "Voice connected"
                    here?.status == VoiceCall.Status.ENDED -> here.error ?: "Disconnected."
                    elsewhere != null -> "You're in ${elsewhere.channelName}. Joining moves you here."
                    members.isEmpty() -> "No one is here."
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

            items(members.size) { i ->
                val vs = members[i]
                val speaking = here != null &&
                        (vs.userId in here.speaking || (vs.userId == me && here.selfSpeaking))
                VoiceMemberLine(
                    name = vs.nick ?: repo.getDisplayName(vs.userId) ?: vs.user?.displayName ?: "Someone",
                    speaking = speaking,
                    muted = vs.muted,
                    deafened = vs.deafened
                )
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
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SpeakingGreen, contentColor = Color.White),
                        icon = { Icon(painterResource(R.drawable.speaker), null, Modifier.size(18.dp)) },
                        label = { Text("Join voice") },
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                            if (granted) join() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
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
