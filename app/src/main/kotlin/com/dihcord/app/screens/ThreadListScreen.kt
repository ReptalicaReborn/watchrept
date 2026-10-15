package com.dihcord.app.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.dihcord.app.api.ThreadSummary
import com.dihcord.app.discordApp
import kotlinx.coroutines.launch

/**
 * Threads in a text channel, or posts in a forum channel. Shows active ones first; "Older" loads archived ones.
 */
@Composable
fun ThreadListScreen(
    channelId: String,
    channelName: String,
    guildId: String?,
    isForum: Boolean,
    onOpenThread: (threadId: String, threadName: String) -> Unit
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    var active by remember(channelId) { mutableStateOf<List<ThreadSummary>?>(null) }
    var archived by remember(channelId) { mutableStateOf<List<ThreadSummary>?>(null) }
    var loadingArchived by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(channelId) {
        repo.rest.getChannelThreads(channelId, guildId, archived = false)
            .onSuccess { active = it }
            .onFailure { active = emptyList(); error = it.message ?: "Couldn't load" }
    }

    val noun = if (isForum) "post" else "thread"

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item {
                ScreenTitle(if (isForum) channelName else "Threads")
            }
            if (!isForum) {
                item {
                    ScreenSubtitle("#$channelName")
                }
            }

            val current = active
            when {
                current == null -> item { LoadingIndicator() }
                current.isEmpty() && archived.isNullOrEmpty() -> item {
                    Text(
                        error.ifEmpty { "No active ${noun}s." },
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                }
            }

            current.orEmpty().forEach { t -> item(key = "a" + t.id) { ThreadRow(t, onOpenThread) } }

            archived?.let { old ->
                if (old.isNotEmpty()) {
                    item {
                        Text(
                            "OLDER",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                }
                old.forEach { t -> item(key = "o" + t.id) { ThreadRow(t, onOpenThread) } }
            }

            if (archived == null && current != null) {
                item {
                    Button(
                        onClick = {
                            if (!loadingArchived) {
                                loadingArchived = true
                                scope.launch {
                                    archived = repo.rest.getChannelThreads(channelId, guildId, archived = true)
                                        .getOrDefault(emptyList())
                                        .filter { a -> current.none { it.id == a.id } }
                                    loadingArchived = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) { Text(if (loadingArchived) "Loading…" else "Show older ${noun}s") }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadSummary, onOpenThread: (String, String) -> Unit) {
    val details = buildList {
        add(if (thread.messageCount == 1) "1 message" else "${thread.messageCount} messages")
        if (thread.locked) add("locked") else if (thread.archived) add("archived")
    }.joinToString(" · ")
    Button(
        onClick = { onOpenThread(thread.id, thread.name) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        secondaryLabel = { Text(details, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    ) {
        Text(thread.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
