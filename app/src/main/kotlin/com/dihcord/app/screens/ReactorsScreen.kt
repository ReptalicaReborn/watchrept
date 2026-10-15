package com.dihcord.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil.ImageLoader
import coil.compose.AsyncImage
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
    var error by remember { mutableStateOf("") }
    LaunchedEffect(messageId, reaction.emoji.apiKey) {
        val result = repo?.reactionUsers(channelId, messageId, reaction)
        users = result?.getOrNull() ?: emptyList()
        if (result == null || result.isFailure) error = "Couldn't load reactions"
    }

    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val url = reaction.emoji.imageUrl
                    if (url != null) {
                        AsyncImage(
                            model = url.replace("size=16", "size=48"),
                            imageLoader = imageLoader,
                            contentDescription = reaction.emoji.name,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Text(reaction.emoji.name, fontSize = 20.sp)
                    }
                    Text("${reaction.count}", style = MaterialTheme.typography.titleMedium)
                }
            }
            val list = users
            when {
                list == null -> item { LoadingIndicator() }
                error.isNotEmpty() && list.isEmpty() -> item {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                else -> items(list, key = { it.id }) { user ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    ) {
                        AsyncImage(
                            model = user.avatarUrl(48),
                            imageLoader = imageLoader,
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                        )
                        Text(
                            user.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
