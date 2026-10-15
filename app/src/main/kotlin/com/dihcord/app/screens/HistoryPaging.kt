package com.dihcord.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import com.dihcord.app.api.DiscordMessage
import com.dihcord.app.api.DiscordRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Stable
class HistoryState {
    /** An older page is being fetched. */
    var loadingOlder by mutableStateOf(false)
        internal set

    /** The very first message of the conversation is loaded. */
    var noMore by mutableStateOf(false)
        internal set
}

private const val TRIGGER_DISTANCE = 2

/**
 * Infinite scroll upwards: when the list is scrolled near its top, loads the next page of older messages and
 * keeps the message the user was looking at in place.
 *
 * @param headerCount list items before the first message (title, draft banner...)
 * @param ready false until the initial load and initial scroll are done, so opening a chat doesn't page at once
 */
@Composable
fun rememberHistoryPaging(
    repo: DiscordRepository,
    channelId: String,
    listState: ScalingLazyListState,
    headerCount: () -> Int,
    messages: () -> List<DiscordMessage>,
    ready: () -> Boolean,
): HistoryState {
    val state = remember(channelId) { HistoryState().also { it.noMore = !repo.hasMoreHistory(channelId) } }

    // Only start paging after the *user* has scrolled: opening a chat can leave the list near its top (nothing
    // unread, or a thread) and that must not trigger loading the whole history. Scrolls in the first moments after
    // the list is ready are the app's own initial positioning.
    var userScrolled by remember(channelId) { mutableStateOf(false) }
    LaunchedEffect(channelId) {
        snapshotFlow { ready() }.first { it }
        delay(700)
        snapshotFlow { listState.isScrollInProgress }.first { it }
        userScrolled = true
    }

    LaunchedEffect(channelId) {
        fun nearTop(): Boolean {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return false
            return first <= headerCount() + TRIGGER_DISTANCE
        }

        snapshotFlow { ready() && userScrolled && !state.noMore && messages().isNotEmpty() && nearTop() }
            .distinctUntilChanged()
            .collectLatest { shouldLoad ->
                if (!shouldLoad) return@collectLatest
                delay(350) // let the initial scroll to the unread position settle
                while (!state.noMore && nearTop()) {
                    val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index >= headerCount() }
                    val anchorId = anchor?.key as? String
                    val anchorOffset = anchor?.offset ?: 0
                    val before = messages().size

                    state.loadingOlder = true
                    val added = try {
                        // Finish the page even if the user scrolls away meanwhile; it is cached either way.
                        withContext(NonCancellable) { repo.loadOlderMessages(channelId) }
                    } finally {
                        state.loadingOlder = false
                    }
                    if (!repo.hasMoreHistory(channelId)) state.noMore = true
                    if (added <= 0) break // nothing older, or an error: don't hammer the API

                    // Wait for the new items to be in the list, then put the user back where they were.
                    withTimeoutOrNull(1_500) { snapshotFlow { messages().size }.first { it >= before + added } }
                    val idx = messages().indexOfFirst { it.id == anchorId }
                    if (idx >= 0) listState.scrollToItem(headerCount() + idx, anchorOffset)
                    delay(100)
                }
            }
    }
    return state
}
