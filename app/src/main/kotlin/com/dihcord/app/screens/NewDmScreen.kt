package com.dihcord.app.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
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

/** Pick a friend to message; opens (or creates) the DM and hands it to [onOpenDm]. */
@Composable
fun NewDmScreen(onOpenDm: (channelId: String, channelName: String) -> Unit) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val scope = rememberCoroutineScope()
    val presences by repo.presences.collectAsState()

    var friends by remember { mutableStateOf<List<DiscordUser>?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var openError by remember { mutableStateOf<Throwable?>(null) }
    var query by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        friends = null
        loadError = null
        repo.getFriends()
            .onSuccess { list -> friends = list.sortedBy { it.displayName.lowercase() } }
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

    NewDmContent(
        friends = friends,
        statuses = presences.mapValues { it.value.status },
        query = query,
        onQueryChange = { query = it.take(32) },
        loadError = loadError,
        openError = openError,
        opening = opening,
        onRetry = { attempt++ },
        onOpen = ::open
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
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val q = query.trim().lowercase()
    val shown = friends.orEmpty().filter {
        q.isEmpty() || it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q)
    }
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("New message") }
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
            if (openError != null) item {
                Text(
                    "Couldn't open the chat. " + friendlyError(openError),
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
                    Button(
                        onClick = { onOpen(user) },
                        enabled = opening == null,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { UserAvatar(user.displayName, user.avatarUrl(64), 30.dp, ring = statuses[user.id]?.ringColor()) },
                        label = { Text(if (opening == user.id) "Opening…" else user.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text("@" + user.username, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    }
}
