package com.dihcord.app.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import com.dihcord.app.BuildConfig
import com.dihcord.app.AppTheme
import com.dihcord.app.NotificationService
import com.dihcord.app.Perf
import com.dihcord.app.Notifier
import com.dihcord.app.SetupPreferences
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
    var showThemes by remember { mutableStateOf(false) }
    var showStatuses by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("online") }
    var savedCustom by remember { mutableStateOf("") }
    var customText by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        repo?.getStatus()?.onSuccess { (s, c) -> status = s; savedCustom = c; customText = c }
    }


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
    val initialPerfMode = remember { SetupPreferences.getPerfMode(context) }
    var perfMode by remember { mutableStateOf(initialPerfMode) }
    val perfModeChanged = perfMode != initialPerfMode
    val hardware = remember { Perf.hardware(context) }
    val weakness = remember { Perf.weaknessReason(hardware) }
    var compactMode by remember {
        mutableStateOf(SetupPreferences.getCompactMode(context))
    }

    var showLogoutConfirm by remember { mutableStateOf(false) }

    // Back closes the confirmation instead of leaving Settings.
    BackHandler(enabled = showLogoutConfirm) { showLogoutConfirm = false }

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
                ScreenTitle("Settings")
            }

            item {
                SectionHeader("Status")
            }

            item {
                Button(
                    onClick = { showStatuses = !showStatuses },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { StatusDot(status) },
                    secondaryLabel = {
                        Text(
                            savedCustom.ifBlank { "No custom status" },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                ) { Text(STATUS_LABELS[status] ?: "Online") }
            }

            if (showStatuses) {
                STATUS_LABELS.forEach { (key, label) ->
                    item {
                        Button(
                            onClick = {
                                status = key
                                showStatuses = false
                                scope.launch {
                                    repo?.setStatus(key, savedCustom)
                                        ?.onFailure { statusError = "Failed: ${it.message}" }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = if (key == status) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                            icon = { StatusDot(key) }
                        ) { Text(label, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CompactTextField(
                            value = customText,
                            onValueChange = { customText = it.take(128) },
                            placeholder = "Custom status",
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledIconButton(
                            onClick = {
                                val text = customText.trim()
                                scope.launch {
                                    repo?.setStatus(status, text)
                                        ?.onSuccess { savedCustom = text; showStatuses = false }
                                        ?.onFailure { statusError = "Failed: ${it.message}" }
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) { Text(if (customText.isBlank()) "\u2715" else "\u2713") }
                    }
                }
            }

            if (statusError.isNotEmpty()) {
                item {
                    Text(
                        statusError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                SectionHeader("Performance")
            }

            item {
                Button(
                    onClick = {
                        perfMode = Perf.Mode.values()[(perfMode.ordinal + 1) % Perf.Mode.values().size]
                        SetupPreferences.setPerfMode(context, perfMode)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = {
                        Text(
                            when {
                                perfModeChanged -> "Restart the app to apply"
                                perfMode == Perf.Mode.AUTO ->
                                    if (weakness != null) "Auto: on (${weakness})" else "Auto: off (${hardware.summary})"
                                perfMode == Perf.Mode.ON -> "Static GIFs, no extras, smaller pages"
                                else -> "Full animations and effects"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                ) {
                    Text(
                        "Lite mode: " + when (perfMode) {
                            Perf.Mode.AUTO -> "Auto"
                            Perf.Mode.ON -> "On"
                            Perf.Mode.OFF -> "Off"
                        }
                    )
                }
            }

            item {
                var screenOffDisconnect by remember { mutableStateOf(SetupPreferences.getDisconnectOnScreenOff(context)) }
                SwitchButton(
                    modifier = Modifier.fillMaxWidth(),
                    checked = screenOffDisconnect,
                    onCheckedChange = {
                        screenOffDisconnect = it
                        SetupPreferences.setDisconnectOnScreenOff(context, it)
                    },
                    label = { Text("Sleep when screen off", style = MaterialTheme.typography.bodySmall) },
                    secondaryLabel = {
                        Text(
                            if (notificationsEnabled) "Paused while notifications are on" else "Saves battery",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }

            item {
                SectionHeader("Notifications")
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
                            "Notifications",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    secondaryLabel = {
                        Text(
                            "DMs & mentions",
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
                SectionHeader("Appearance")
            }

            item {
                Button(
                    onClick = { showThemes = !showThemes },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { ThemeSwatch(AppTheme.preset, size = 20.dp) },
                    secondaryLabel = {
                        Text(
                            if (showThemes) "Tap to close" else "Tap to change",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                ) { Text("Theme: ${AppTheme.preset.label}") }
            }

            if (showThemes) {
                val (colours, pride) = AppTheme.Preset.values().partition { !it.isPride }
                listOf("Colours" to colours, "Pride" to pride).forEach { (title, presets) ->
                    item { SectionHeader(title) }
                    presets.chunked(4).forEach { row ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                            ) {
                                row.forEach { p ->
                                    ThemeSwatch(
                                        preset = p,
                                        size = 34.dp,
                                        selected = p == AppTheme.preset,
                                        onClick = { AppTheme.setPreset(context, p) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                var showServerNames by remember { mutableStateOf(SetupPreferences.getShowServerNames(context)) }
                SwitchButton(
                    modifier = Modifier.fillMaxWidth(),
                    checked = showServerNames,
                    onCheckedChange = {
                        showServerNames = it
                        SetupPreferences.setShowServerNames(context, it)
                    },
                    label = { Text("Server names", style = MaterialTheme.typography.bodySmall) },
                    secondaryLabel = {
                        Text("Show names under server icons", style = MaterialTheme.typography.labelSmall)
                    }
                )
            }

            item {
                SwitchButton(
                    modifier = Modifier.fillMaxWidth(),
                    checked = AppTheme.amoled,
                    onCheckedChange = { AppTheme.setAmoled(context, it) },
                    label = { Text("AMOLED black", style = MaterialTheme.typography.bodySmall) },
                    secondaryLabel = {
                        Text("Pure black backgrounds, saves battery", style = MaterialTheme.typography.labelSmall)
                    }
                )
            }

            item {
                SectionHeader("General")
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
                        .fillMaxWidth(),
                    checked = spoilerRevealOnTap,
                    onCheckedChange = {
                        spoilerRevealOnTap = it
                        SetupPreferences.setSpoilerRevealOnTap(context, it)
                    },
                    label = { Text("Spoiler reveal on tap", style = MaterialTheme.typography.bodySmall) },
                    secondaryLabel = {
                        Text("||spoiler|| text hidden until tapped", style = MaterialTheme.typography.labelSmall)
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
                SectionHeader("Vencord")
            }

            item {
                SwitchButton(
                    modifier = Modifier
                        .fillMaxWidth(),
                    checked = sendAnimatedAsGif,
                    onCheckedChange = {
                        sendAnimatedAsGif = it
                        SetupPreferences.setSendAnimatedAsGif(context, it)
                    },
                    // Short label + secondaryLabel like the other rows: a Column in `label` gets clipped.
                    label = { Text("Nitro emoji as link", style = MaterialTheme.typography.bodySmall) },
                    secondaryLabel = {
                        Text(
                            "Animated & other-server emoji send as a link; off = locked",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 3
                        )
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
                SectionHeader("Account")
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
                SectionHeader("Changelog")
            }

            CHANGELOG.forEach { entry ->
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "v${entry.version} \u201C${entry.name}\u201D",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        entry.notes.forEach { note ->
                            Text(
                                "\u2022 $note",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Dihcord v${BuildConfig.VERSION_NAME} \u201CRainfield\u201D",
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

/** One release in the in-app changelog, newest first in [CHANGELOG]. */
private data class ChangelogEntry(val version: String, val name: String, val notes: List<String>)

private val CHANGELOG = listOf(
    ChangelogEntry("1.0", "Rainfield", listOf("Initial release")),
)

private val STATUS_LABELS = linkedMapOf(
    "online" to "Online",
    "idle" to "Idle",
    "dnd" to "Do Not Disturb",
    "invisible" to "Invisible",
)

/** Discord's status colours. */
@Composable
private fun StatusDot(status: String) {
    val color = when (status) {
        "idle" -> androidx.compose.ui.graphics.Color(0xFFF0B232)
        "dnd" -> androidx.compose.ui.graphics.Color(0xFFF23F43)
        "invisible" -> androidx.compose.ui.graphics.Color(0xFF80848E)
        else -> androidx.compose.ui.graphics.Color(0xFF23A55A)
    }
    Box(
        modifier = Modifier
            .size(14.dp)
            .background(color, androidx.compose.foundation.shape.CircleShape)
    )
}
