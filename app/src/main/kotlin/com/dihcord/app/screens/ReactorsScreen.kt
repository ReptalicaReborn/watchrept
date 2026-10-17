package com.dihcord.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import coil.ImageLoader
import coil.compose.AsyncImage
import com.dihcord.app.R
import com.dihcord.app.api.DiscordUser
import com.dihcord.app.api.Reaction
import com.dihcord.app.discordApp

/** Who reacted to a message with one emoji (long-press a reaction to open). */
@Composable
fun ReactorsScreen(
    channelId: String,
    messageId: String,
    reaction: Reaction,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }
    val repo = LocalContext.current.discordApp.repository
    var users by remember { mutableStateOf<List<DiscordUser>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(messageId, reaction.emoji.apiKey, attempt) {
        users = null
        error = null
        val result = repo?.reactionUsers(channelId, messageId, reaction) ?: return@LaunchedEffect
        result.onSuccess { users = it }.onFailure { error = it }
    }
    ReactorsContent(reaction, users, error, imageLoader, onRetry = { attempt++ })
}

/** The reactors list from plain data: null [users] means still loading. */
@Composable
internal fun ReactorsContent(
    reaction: Reaction,
    users: List<DiscordUser>?,
    error: Throwable?,
    imageLoader: ImageLoader,
    onRetry: () -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val url = reaction.emoji.imageUrl
                    if (url != null) {
                        AsyncImage(
                            model = url.replace("size=16", "size=64"),
                            imageLoader = imageLoader,
                            contentDescription = reaction.emoji.name,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Text(reaction.emoji.name, fontSize = 28.sp)
                    }
                    Text(
                        if (reaction.count == 1) "1 reaction" else "${reaction.count} reactions",
                        style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                    )
                }
            }
            when {
                error != null -> item { ErrorState("Couldn't load who reacted", error, onRetry) }
                users == null -> item { LoadingIndicator() }
                users.isEmpty() -> item { EmptyState(R.drawable.emoji, "No one to show", "They may have removed it.") }
                else -> items(users.size, key = { users[it].id }) { i ->
                    val user = users[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec)
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        UserAvatar(user.displayName, user.avatarUrl(64), 28.dp)
                        Text(
                            user.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
