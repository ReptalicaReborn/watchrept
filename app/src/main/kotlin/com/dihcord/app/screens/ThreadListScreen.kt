package com.dihcord.app.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dihcord.app.R
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
    val scope = rememberCoroutineScope()

    var active by remember(channelId) { mutableStateOf<List<ThreadSummary>?>(null) }
    var archived by remember(channelId) { mutableStateOf<List<ThreadSummary>?>(null) }
    var loadingArchived by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(channelId, attempt) {
        active = null
        error = null
        repo.rest.getChannelThreads(channelId, guildId, archived = false)
            .onSuccess { active = it }
            .onFailure { error = it }
    }

    ThreadListContent(
        title = if (isForum) channelName else "Threads",
        subtitle = if (isForum) null else "#$channelName",
        noun = if (isForum) "post" else "thread",
        active = active,
        archived = archived,
        error = error,
        loadingArchived = loadingArchived,
        onRetry = { attempt++ },
        onLoadOlder = {
            val current = active.orEmpty()
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
        onOpenThread = onOpenThread
    )
}

/** The thread/post list from plain data: null [active] means still loading. */
@Composable
internal fun ThreadListContent(
    title: String,
    subtitle: String?,
    noun: String,
    active: List<ThreadSummary>?,
    archived: List<ThreadSummary>?,
    error: Throwable?,
    loadingArchived: Boolean,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
    onOpenThread: (String, String) -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle(title) }
            if (subtitle != null) item { ScreenSubtitle(subtitle) }

            when {
                error != null -> item { ErrorState("Couldn't load ${noun}s", error, onRetry) }
                active == null -> item { LoadingIndicator() }
                active.isEmpty() && archived.isNullOrEmpty() -> item {
                    EmptyState(R.drawable.chat, "No active ${noun}s", if (archived == null) "Older ones may be below." else null)
                }
            }

            active.orEmpty().forEach { t ->
                item(key = "a" + t.id) {
                    ThreadRow(t, Modifier.transformedHeight(this, spec), SurfaceTransformation(spec), onOpenThread)
                }
            }

            archived?.let { old ->
                if (old.isNotEmpty()) item { SectionHeader("Older") }
                old.forEach { t ->
                    item(key = "o" + t.id) {
                        ThreadRow(t, Modifier.transformedHeight(this, spec), SurfaceTransformation(spec), onOpenThread)
                    }
                }
            }

            if (archived == null && active != null && error == null) {
                item {
                    CompactButton(
                        onClick = onLoadOlder,
                        label = { Text(if (loadingArchived) "Loading…" else "Show older ${noun}s") },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(
    thread: ThreadSummary,
    modifier: Modifier,
    transformation: SurfaceTransformation,
    onOpenThread: (String, String) -> Unit
) {
    val details = buildList {
        add(if (thread.messageCount == 1) "1 message" else "${thread.messageCount} messages")
        snowflakeMillis(thread.lastMessageId)?.let { add(shortAgo(it)) }
        if (thread.locked) add("locked") else if (thread.archived) add("archived")
    }.joinToString(" · ")
    Button(
        onClick = { onOpenThread(thread.id, thread.name) },
        modifier = modifier.fillMaxWidth(),
        transformation = transformation,
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = {
            Icon(
                painterResource(if (thread.locked) R.drawable.lock else R.drawable.chat), null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        label = { Text(thread.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    )
}
