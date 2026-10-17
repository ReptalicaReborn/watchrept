package com.dihcord.app.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dihcord.app.R
import com.dihcord.app.api.DiscordUser
import com.dihcord.app.api.OnlineStatus
import com.dihcord.app.discordApp
import kotlinx.coroutines.launch

/**
 * Pick a friend to message (opens or creates the DM), or several to start a group. With [addToGroup],
 * the picked friends are added to that group instead and [onDone] is called.
 */
@Composable
fun NewDmScreen(
    onOpenDm: (channelId: String, channelName: String) -> Unit,
    addToGroup: String? = null,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val scope = rememberCoroutineScope()
    val presences by repo.presences.collectAsState()
    val dms by repo.dmChannels.collectAsState()

    var friends by remember { mutableStateOf<List<DiscordUser>?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var openError by remember { mutableStateOf<Throwable?>(null) }
    var query by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var groupMode by remember { mutableStateOf(addToGroup != null) }
    val picked = remember { mutableStateListOf<String>() }
    // People already in the group being added to aren't offered.
    val alreadyIn = remember(dms, addToGroup) { dms.firstOrNull { it.id == addToGroup }?.recipients?.map { it.id }?.toSet().orEmpty() }

    LaunchedEffect(attempt) {
        friends = null
        loadError = null
        repo.getFriends()
            .onSuccess { list -> friends = list.filter { it.id !in alreadyIn }.sortedBy { it.displayName.lowercase() } }
            .onFailure { loadError = it }
    }

    fun open(user: DiscordUser) {
        if (opening != null) return
        opening = user.id
        openError = null
        scope.launch {
            repo.openDm(user.id)
                .onSuccess { onOpenDm(it.id, it.displayName.ifBlank { user.displayName }) }
                .onFailure { openError = it }
            opening = null
        }
    }

    fun finishGroup() {
        if (opening != null || picked.isEmpty()) return
        opening = "group"
        openError = null
        scope.launch {
            if (addToGroup != null) {
                var failed: Throwable? = null
                picked.forEach { id -> repo.addToGroup(addToGroup, id).onFailure { failed = it } }
                if (failed == null) onDone() else openError = failed
            } else {
                repo.createGroupDm(picked.toList())
                    .onSuccess { onOpenDm(it.id, it.displayName.ifBlank { "Group" }) }
                    .onFailure { openError = it }
            }
            opening = null
        }
    }

    NewDmContent(
        friends = friends,
        statuses = presences.mapValues { it.value.status },
        query = query,
        onQueryChange = { query = it.take(32) },
        loadError = loadError,
        openError = openError,
        opening = opening,
        onRetry = { attempt++ },
        onOpen = { user ->
            if (groupMode) {
                // Discord groups hold 10 people, us included.
                if (user.id in picked) picked.remove(user.id) else if (picked.size + alreadyIn.size < 9) picked.add(user.id)
            } else open(user)
        },
        groupMode = groupMode,
        picked = picked.toSet(),
        addingToGroup = addToGroup != null,
        onToggleGroup = if (addToGroup == null) ({ groupMode = !groupMode; picked.clear() }) else null,
        onFinishGroup = ::finishGroup,
    )
}

/** The friend picker from plain data: null [friends] means still loading. */
@Composable
internal fun NewDmContent(
    friends: List<DiscordUser>?,
    statuses: Map<String, OnlineStatus>,
    query: String,
    onQueryChange: (String) -> Unit,
    loadError: Throwable?,
    openError: Throwable?,
    opening: String?,
    onRetry: () -> Unit,
    onOpen: (DiscordUser) -> Unit,
    groupMode: Boolean = false,
    picked: Set<String> = emptySet(),
    addingToGroup: Boolean = false,
    onToggleGroup: (() -> Unit)? = null,
    onFinishGroup: () -> Unit = {},
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val q = query.trim().lowercase()
    val shown = friends.orEmpty().filter {
        q.isEmpty() || it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q)
    }
    val body: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle(when { addingToGroup -> "Add people"; groupMode -> "New group"; else -> "New message" }) }
            if (groupMode) item { ScreenSubtitle(if (picked.isEmpty()) "Pick up to 9 friends" else "${picked.size} picked") }
            if (loadError == null) {
                item {
                    CompactTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 48.dp),
                        placeholder = "Search friends",
                        singleLine = true
                    )
                }
            }
            if (onToggleGroup != null && loadError == null) item {
                CompactButton(
                    onClick = onToggleGroup,
                    icon = { Icon(painterResource(if (groupMode) R.drawable.chat else R.drawable.people), null, Modifier.size(16.dp)) },
                    label = { Text(if (groupMode) "Just one person" else "New group") },
                    colors = ButtonDefaults.childButtonColors()
                )
            }
            if (openError != null) item {
                Text(
                    (if (groupMode) "Couldn't make the group. " else "Couldn't open the chat. ") + friendlyError(openError),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
            when {
                loadError != null -> item { ErrorState("Couldn't load friends", loadError, onRetry) }
                friends == null -> item { LoadingIndicator() }
                shown.isEmpty() -> item {
                    if (q.isEmpty()) EmptyState(R.drawable.chat, "No friends yet", "You can also message someone from their profile.")
                    else EmptyState(R.drawable.chat, "No one matches \"$query\"")
                }
                else -> items(shown.size, key = { shown[it].id }) { i ->
                    val user = shown[i]
                    val on = user.id in picked
                    Button(
                        onClick = { onOpen(user) },
                        enabled = opening == null,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = if (on) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                        icon = { UserAvatar(user.displayName, user.avatarUrl(64), 30.dp, ring = statuses[user.id]?.ringColor()) },
                        label = { Text(if (opening == user.id) "Opening…" else user.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(if (on) "Picked" else "@" + user.username, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    }
    if (groupMode) {
        ScreenScaffold(
            scrollState = state,
            edgeButton = {
                EdgeButton(onClick = onFinishGroup, enabled = picked.isNotEmpty() && opening == null) {
                    Text(
                        when {
                            opening != null -> "Working…"
                            addingToGroup -> "Add (${picked.size})"
                            else -> "Create (${picked.size})"
                        }
                    )
                }
            },
            content = { body(it) }
        )
    } else {
        ScreenScaffold(scrollState = state, content = { body(it) })
    }
}
