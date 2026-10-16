package com.dihcord.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.dihcord.app.R
import com.dihcord.app.api.Gif
import com.dihcord.app.discordApp
import kotlinx.coroutines.delay

private enum class GifTab(val label: String) { FAVORITES("Favourites"), TRENDING("Trending") }

/** Discord's GIF picker: favourites, trending and search. Picking one hands its URL to [onGifPicked]. */
@Composable
fun GifPickerScreen(onGifPicked: (Gif) -> Unit) {
    val context = LocalContext.current
    val rest = context.discordApp.repository?.rest
    val listState = rememberScalingLazyListState()
    val imageLoader = context.imageLoader

    var tab by remember { mutableStateOf(GifTab.FAVORITES) }
    var query by remember { mutableStateOf("") }
    var gifs by remember { mutableStateOf<List<Gif>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableStateOf(0) }

    val q = query.trim()
    LaunchedEffect(q, tab, attempt) {
        error = null
        if (rest == null) { gifs = emptyList(); return@LaunchedEffect }
        if (q.isNotEmpty()) delay(500) // wait for typing to settle
        gifs = null
        val result = when {
            q.isNotEmpty() -> rest.searchGifs(q)
            tab == GifTab.FAVORITES -> rest.favoriteGifs()
            else -> rest.trendingGifs()
        }
        gifs = result.getOrElse {
            error = it
            emptyList()
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item { ScreenTitle("GIFs") }
            item {
                CompactTextField(
                    value = query,
                    onValueChange = { query = it.take(64) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp, max = 48.dp),
                    placeholder = "Search Tenor",
                    singleLine = true
                )
            }
            if (q.isEmpty()) {
                item {
                    PickerTabs(GifTab.entries.map { it.label }, tab.ordinal) { tab = GifTab.entries[it] }
                }
            }

            val list = gifs
            when {
                list == null -> item { LoadingIndicator() }
                error != null -> item { ErrorState("Couldn't load GIFs", error) { attempt++ } }
                list.isEmpty() -> item {
                    when {
                        q.isNotEmpty() -> EmptyState(R.drawable.image, "No GIFs found", "Try another word.")
                        tab == GifTab.FAVORITES -> EmptyState(R.drawable.image, "No favourites yet", "Star GIFs in the Discord app.")
                        else -> EmptyState(R.drawable.image, "Nothing trending right now")
                    }
                }
                else -> {
                    val rows = list.chunked(2)
                    items(rows.size) { i ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                        ) {
                            rows[i].forEach { gif -> GifCell(gif, imageLoader) { onGifPicked(gif) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GifCell(gif: Gif, imageLoader: ImageLoader, onClick: () -> Unit) {
    val context = LocalContext.current
    var model by remember(gif) { mutableStateOf(gif.preview) }
    Box(
        modifier = Modifier
            .size(width = 76.dp, height = 58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val current = model
        if (current == null) {
            Text("GIF", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp))
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(current).crossfade(true).build(),
                imageLoader = imageLoader,
                contentDescription = "GIF",
                contentScale = ContentScale.Crop,
                onError = { if (model == gif.preview) model = gif.fallback },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
            )
        }
    }
}
