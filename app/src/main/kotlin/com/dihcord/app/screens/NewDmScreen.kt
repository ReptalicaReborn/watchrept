package com.dihcord.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import com.dihcord.app.api.DiscordUser
import com.dihcord.app.discordApp
import kotlinx.coroutines.launch

/** Pick a friend to message; opens (or creates) the DM and hands it to [onOpenDm]. */
@Composable
fun NewDmScreen(onOpenDm: (channelId: String, channelName: String) -> Unit) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val imageLoader = context.imageLoader

    var friends by remember { mutableStateOf<List<DiscordUser>?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        friends = repo.getFriends().getOrElse {
            error = "Couldn't load friends: ${it.message}"
            emptyList()
        }.sortedBy { it.displayName.lowercase() }
    }

    fun open(user: DiscordUser) {
        if (opening != null) return
        opening = user.id
        error = ""
        scope.launch {
            repo.openDm(user.id)
                .onSuccess { onOpenDm(it.id, it.displayName.ifBlank { user.displayName }) }
                .onFailure { error = "Couldn't open the DM: ${it.message}" }
            opening = null
        }
    }

    val q = query.trim().lowercase()
    val shown = friends.orEmpty().filter {
        q.isEmpty() || it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q)
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item { ScreenTitle("New message") }
            item {
                CompactTextField(
                    value = query,
                    onValueChange = { query = it.take(32) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp, max = 48.dp),
                    placeholder = "Search friends",
                    singleLine = true
                )
            }
            if (error.isNotEmpty()) item {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            when {
                friends == null -> item { LoadingIndicator() }
                shown.isEmpty() -> item {
                    Text(
                        if (q.isEmpty()) "No friends yet. You can also message someone from their profile."
                        else "No friends match.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> items(shown.size, key = { shown[it].id }) { i ->
                    val user = shown[i]
                    Button(
                        onClick = { open(user) },
                        enabled = opening == null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AsyncImage(
                                model = user.avatarUrl(64),
                                imageLoader = imageLoader,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                            )
                            Text(
                                if (opening == user.id) "Opening…" else user.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
