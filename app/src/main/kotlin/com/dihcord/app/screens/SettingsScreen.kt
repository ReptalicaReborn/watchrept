package com.dihcord.app.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.dihcord.app.ApkInstaller
import com.dihcord.app.NotificationService
import com.dihcord.app.Notifier
import com.dihcord.app.SetupPreferences
import com.dihcord.app.UpdateChecker
import com.dihcord.app.discordApp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onLogOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val repo = context.discordApp.repository
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf("") }


    var hideInaccessible by remember {
        mutableStateOf(SetupPreferences.getHideInaccessibleChannels(context))
    }
    var sendAnimatedAsGif by remember {
        mutableStateOf(SetupPreferences.getSendAnimatedAsGif(context))
    }
    var spoilerRevealOnTap by remember {
        mutableStateOf(SetupPreferences.getSpoilerRevealOnTap(context))
    }
    var showMentionBadges by remember {
        mutableStateOf(SetupPreferences.getShowMentionBadges(context))
    }
    var notificationsEnabled by remember {
        mutableStateOf(
            SetupPreferences.getNotificationsEnabled(context) && Notifier.canPostNotifications(context)
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsEnabled = granted
        SetupPreferences.setNotificationsEnabled(context, granted)
        if (granted) NotificationService.start(context)
    }
    var quietEnabled by remember { mutableStateOf(SetupPreferences.getQuietHoursEnabled(context)) }
    var quietStart by remember { mutableStateOf(SetupPreferences.getQuietStartHour(context)) }
    var quietEnd by remember { mutableStateOf(SetupPreferences.getQuietEndHour(context)) }
    var mutedCount by remember {
        mutableStateOf(
            SetupPreferences.getMutedChannels(context).size + SetupPreferences.getMutedServers(context).size
        )
    }
    var compactMode by remember {
        mutableStateOf(SetupPreferences.getCompactMode(context))
    }

    var showLogoutConfirm by remember { mutableStateOf(false) }
    val updateState by UpdateChecker.state.collectAsState()

    if (showLogoutConfirm) {
        ScreenScaffold(scrollState = rememberScalingLazyListState()) {
            ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        "Log out?",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text(
                        "Your token will be cleared from this device.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    )
                }
                item {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { repo?.rest?.logout() }
                                repo?.disconnect()
                                SetupPreferences.clearAll(context)
                                context.discordApp.clearRepository()
                                onLogOut()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Log Out") }
                }
                item {
                    Button(
                        onClick = { showLogoutConfirm = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text("Cancel") }
                }
            }
        }
        return
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {

            item {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Text(
                    "NOTIFICATIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier.fillMaxWidth(),
                    checked = notificationsEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            notificationsEnabled = false
                            SetupPreferences.setNotificationsEnabled(context, false)
                            NotificationService.stop(context)
                        } else if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPostNotifications(context)) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationsEnabled = true
                            SetupPreferences.setNotificationsEnabled(context, true)
                            NotificationService.start(context)
                        }
                    },
                    label = {
                        Text(
                            "Background notifications",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    secondaryLabel = {
                        Text(
                            "DMs & mentions. Uses more battery.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier.fillMaxWidth(),
                    checked = quietEnabled,
                    onCheckedChange = {
                        quietEnabled = it
                        SetupPreferences.setQuietHoursEnabled(context, it)
                    },
                    label = { Text("Quiet hours", style = MaterialTheme.typography.bodySmall) },
                    secondaryLabel = {
                        Text(
                            "No notifications %02d:00–%02d:00".format(quietStart, quietEnd),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }

            if (quietEnabled) {
                item {
                    Button(
                        onClick = {
                            quietStart = (quietStart + 1) % 24
                            SetupPreferences.setQuietHours(context, quietStart, quietEnd)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        secondaryLabel = { Text("Tap to move 1 hour later", style = MaterialTheme.typography.labelSmall) }
                    ) { Text("From %02d:00".format(quietStart)) }
                }
                item {
                    Button(
                        onClick = {
                            quietEnd = (quietEnd + 1) % 24
                            SetupPreferences.setQuietHours(context, quietStart, quietEnd)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        secondaryLabel = { Text("Tap to move 1 hour later", style = MaterialTheme.typography.labelSmall) }
                    ) { Text("Until %02d:00".format(quietEnd)) }
                }
            }

            if (mutedCount > 0) {
                item {
                    Button(
                        onClick = {
                            SetupPreferences.clearMutedChannels(context)
                            SetupPreferences.clearMutedServers(context)
                            mutedCount = 0
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        secondaryLabel = { Text("Chats and servers", style = MaterialTheme.typography.labelSmall) }
                    ) { Text("Unmute all ($mutedCount)") }
                }
            }

            item {
                Text(
                    "GENERAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    checked = showMentionBadges,
                    onCheckedChange = {
                        showMentionBadges = it
                        SetupPreferences.setShowMentionBadges(context, it)
                    },
                    label = {
                        Text(
                            "Show mention badges",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    checked = spoilerRevealOnTap,
                    onCheckedChange = {
                        spoilerRevealOnTap = it
                        SetupPreferences.setSpoilerRevealOnTap(context, it)
                    },
                    label = {
                        Column {
                            Text(
                                "Spoiler reveal on tap",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "||spoiler|| text hidden until tapped",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    checked = compactMode,
                    onCheckedChange = {
                        compactMode = it
                        SetupPreferences.setCompactMode(context, it)
                    },
                    label = { Text("Compact messages", style = MaterialTheme.typography.bodySmall) }
                )
            }

            item {
                Text(
                    "VENCORD",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    checked = sendAnimatedAsGif,
                    onCheckedChange = {
                        sendAnimatedAsGif = it
                        SetupPreferences.setSendAnimatedAsGif(context, it)
                    },
                    label = {
                        Column {
                            Text(
                                "Send animated emoji as GIF",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Posts a GIF link instead of <a:emoji:id>",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    checked = hideInaccessible,
                    onCheckedChange = {
                        hideInaccessible = it
                        SetupPreferences.setHideInaccessibleChannels(context, it)
                    },
                    label = {
                        Text(
                            "Hide locked channels",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )
            }

            item {
                Text(
                    "ACCOUNT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                Button(
                    onClick = { showLogoutConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Log Out") }
            }

            item {
                Text(
                    "UPDATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                )
            }

            item {
                val statusText = when (val s = updateState) {
                    is UpdateChecker.UpdateState.Idle -> "v${UpdateChecker.CURRENT_VERSION}"
                    is UpdateChecker.UpdateState.Checking -> "Checking…"
                    is UpdateChecker.UpdateState.UpToDate -> "v${UpdateChecker.CURRENT_VERSION}: up to date"
                    is UpdateChecker.UpdateState.UpdateAvailable -> "v${s.release.tagName} available!"
                    is UpdateChecker.UpdateState.Error -> "Check failed: ${s.message}"
                }
                val statusColor = when (updateState) {
                    is UpdateChecker.UpdateState.UpdateAvailable -> MaterialTheme.colorScheme.primary
                    is UpdateChecker.UpdateState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = { UpdateChecker.checkNow(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    enabled = updateState !is UpdateChecker.UpdateState.Checking,
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Text(
                        if (updateState is UpdateChecker.UpdateState.Checking) "Checking…" else "Check for updates",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (updateState is UpdateChecker.UpdateState.UpdateAvailable) {
                val release = (updateState as UpdateChecker.UpdateState.UpdateAvailable).release

                if (release.apkUrl != null) {
                    item {
                        Button(
                            onClick = {
                                if (!downloading) {
                                    downloading = true
                                    downloadProgress = 0f
                                    downloadError = ""
                                    scope.launch {
                                        ApkInstaller.downloadAndInstall(
                                            context = context,
                                            url = release.apkUrl,
                                            onProgress = { p ->
                                                downloadProgress = p
                                            }
                                        ).onFailure {
                                            downloadError = it.message ?: "Download failed"
                                        }
                                        downloading = false
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                            enabled = !downloading,
                            colors = ButtonDefaults.buttonColors()
                        ) {
                            Text(
                                if (downloading) "Downloading…" else "Download & Install APK",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (downloading) {
                        item {
                            Column(modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)) {
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                )
                                Text(
                                    text = "${(downloadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    if (downloadError.isNotEmpty()) {
                        item {
                            Text(
                                downloadError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = { ApkInstaller.openInPhoneBrowser(context, release.htmlUrl) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Text("Open release on phone", style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    Text(
                        "Open on phone to download, then sideload via ADB:\nadb install Dihcord.apk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    )
                }
            }

            item {
                Text(
                    "Dihcord v${UpdateChecker.CURRENT_VERSION}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
        }
    }
}
