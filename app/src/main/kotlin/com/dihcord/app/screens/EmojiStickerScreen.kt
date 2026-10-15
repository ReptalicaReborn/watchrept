package com.dihcord.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.dihcord.app.R
import com.dihcord.app.api.DiscordRepository.GuildSet
import com.dihcord.app.api.EmojiUse
import com.dihcord.app.api.GuildEmoji
import com.dihcord.app.api.StickerItem
import com.dihcord.app.discordApp

/** Common emoji with search keywords. */
private val COMMON_UNICODE_EMOJI = listOf(
    "👍" to "thumbsup like yes", "👎" to "thumbsdown dislike no", "❤️" to "heart love red",
    "😂" to "joy laugh lol", "😮" to "wow open mouth surprised", "😢" to "cry sad tear",
    "😡" to "angry rage mad", "🎉" to "tada party celebrate", "🔥" to "fire lit hot",
    "✅" to "check yes done", "👀" to "eyes look", "🙏" to "pray please thanks",
    "💯" to "100 hundred", "⭐" to "star", "💀" to "skull dead", "🫡" to "salute",
    "😭" to "sob crying", "😍" to "heart eyes love", "🤔" to "thinking hmm", "💪" to "muscle strong flex",
    "🤣" to "rofl laughing", "😎" to "cool sunglasses", "🥹" to "holding back tears",
    "🫶" to "heart hands", "😊" to "blush smile happy", "🙂" to "smile slight", "😅" to "sweat smile",
    "😴" to "sleep sleeping", "🤯" to "mind blown exploding", "👋" to "wave hi hello bye",
    "👏" to "clap applause", "🤝" to "handshake deal", "😳" to "flushed", "🥺" to "pleading puppy",
    "😏" to "smirk", "🙄" to "eyeroll roll eyes", "😤" to "triumph huff", "😬" to "grimace",
    "💔" to "broken heart", "✨" to "sparkles", "🎂" to "cake birthday", "☕" to "coffee",
    "🐐" to "goat", "🤡" to "clown", "😈" to "devil smiling imp", "🙃" to "upside down",
    "❌" to "x cross no", "⚠️" to "warning", "❓" to "question", "🆗" to "ok",
)

private const val MAX_SEARCH_RESULTS = 60

/**
 * Emoji / sticker picker covering every server the user is in (current server first) with name search.
 * Without Nitro, other-server and animated emoji (and other-server stickers) are either posted as an image
 * link (when [sendAnimatedAsGif] is on) or shown locked, matching Discord's rules.
 */
@Composable
fun EmojiStickerScreen(
    tab: Int,
    guildId: String?,
    hasNitro: Boolean = false,
    sendAnimatedAsGif: Boolean = true,
    reactMode: Boolean = false,
    onEmojiPicked: (emoji: GuildEmoji, asLink: Boolean) -> Unit,
    onUnicodeEmojiPicked: (String) -> Unit,
    onStickerPicked: (sticker: StickerItem, asLink: Boolean) -> Unit
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val imageLoader = context.imageLoader
    val showStickers = tab == 1 && !reactMode

    var emojiSets by remember { mutableStateOf<List<GuildSet<GuildEmoji>>?>(null) }
    var stickerSets by remember { mutableStateOf<List<GuildSet<StickerItem>>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(guildId, showStickers) {
        if (repo == null) {
            emojiSets = emptyList(); stickerSets = emptyList(); return@LaunchedEffect
        }
        if (showStickers) stickerSets = repo.allGuildStickers(guildId)
        else emojiSets = repo.allGuildEmojis(guildId)
    }

    fun emojiUse(e: GuildEmoji, fromGuild: String) = EmojiUse.forEmoji(
        animated = e.animated, fromThisServer = fromGuild == guildId, hasNitro = hasNitro,
        linkFallback = sendAnimatedAsGif, reaction = reactMode
    )

    fun stickerUse(s: StickerItem, fromGuild: String) = EmojiUse.forSticker(
        s, fromThisServer = fromGuild == guildId, hasNitro = hasNitro, linkFallback = sendAnimatedAsGif
    )

    val q = query.trim().lowercase()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item {
                Text(
                    if (reactMode) "React" else if (showStickers) "Stickers" else "Emojis",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(32) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp, max = 48.dp),
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true,
                    placeholder = { Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            if (showStickers) {
                val sets = stickerSets
                when {
                    sets == null -> item { CircularProgressIndicator() }
                    q.isNotEmpty() -> {
                        val hits = sets.flatMap { set -> set.items.map { it to set.guildId } }
                            .filter { (s, _) -> s.name.lowercase().contains(q) }
                            .take(MAX_SEARCH_RESULTS)
                        if (hits.isEmpty()) item { EmptyNote("No stickers match.") }
                        gridRows(hits, perRow = 3) { (s, gid) ->
                            StickerCell(s, stickerUse(s, gid), imageLoader, onStickerPicked)
                        }
                    }
                    sets.isEmpty() -> item { EmptyNote("No stickers.") }
                    else -> sets.forEach { set ->
                        item(key = "sh" + set.guildId) { SectionHeader(set.guildName) }
                        gridRows(set.items, perRow = 3) { s ->
                            StickerCell(s, stickerUse(s, set.guildId), imageLoader, onStickerPicked)
                        }
                    }
                }
            } else {
                val unicode = COMMON_UNICODE_EMOJI.filter { (_, words) -> q.isEmpty() || words.contains(q) }
                if (unicode.isNotEmpty()) {
                    gridRows(unicode, perRow = 6) { (emoji, _) ->
                        Text(
                            text = emoji,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .clickable { onUnicodeEmojiPicked(emoji) }
                                .padding(4.dp)
                        )
                    }
                }

                val sets = emojiSets
                when {
                    sets == null -> item { CircularProgressIndicator() }
                    q.isNotEmpty() -> {
                        val hits = sets.flatMap { set -> set.items.map { it to set.guildId } }
                            .filter { (e, _) -> e.name.lowercase().contains(q) }
                            .take(MAX_SEARCH_RESULTS)
                        if (hits.isEmpty() && unicode.isEmpty()) item { EmptyNote("No emojis match.") }
                        gridRows(hits, perRow = 5) { (e, gid) ->
                            EmojiCell(e, emojiUse(e, gid), imageLoader, onEmojiPicked)
                        }
                    }
                    sets.isEmpty() -> item { EmptyNote("No custom emojis.") }
                    else -> sets.forEach { set ->
                        item(key = "eh" + set.guildId) { SectionHeader(set.guildName) }
                        gridRows(set.items, perRow = 5) { e ->
                            EmojiCell(e, emojiUse(e, set.guildId), imageLoader, onEmojiPicked)
                        }
                    }
                }
            }
        }
    }
}

/** Lays [items] out in centred rows of [perRow]. */
private fun <T> ScalingLazyListScope.gridRows(items: List<T>, perRow: Int, cell: @Composable (T) -> Unit) {
    val rows = items.chunked(perRow)
    items(rows.size) { rowIdx ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            rows[rowIdx].forEach { cell(it) }
        }
    }
}

@Composable
private fun SectionHeader(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun EmojiCell(
    emoji: GuildEmoji,
    use: EmojiUse,
    imageLoader: coil.ImageLoader,
    onPicked: (GuildEmoji, Boolean) -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(enabled = use != EmojiUse.LOCKED) { onPicked(emoji, use == EmojiUse.AS_LINK) },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(emoji.imageUrl).build(),
            imageLoader = imageLoader,
            contentDescription = emoji.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(30.dp)
                .alpha(if (use == EmojiUse.LOCKED) 0.35f else 1f)
        )
        if (use == EmojiUse.LOCKED) {
            Icon(
                painter = painterResource(id = R.drawable.lock),
                contentDescription = "Needs Nitro",
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun StickerCell(
    sticker: StickerItem,
    use: EmojiUse,
    imageLoader: coil.ImageLoader,
    onPicked: (StickerItem, Boolean) -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(52.dp)
            .clickable(enabled = use != EmojiUse.LOCKED) { onPicked(sticker, use == EmojiUse.AS_LINK) },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(sticker.imageUrl).build(),
            imageLoader = imageLoader,
            contentDescription = sticker.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(48.dp)
                .alpha(if (use == EmojiUse.LOCKED) 0.35f else 1f)
        )
        if (use == EmojiUse.LOCKED) {
            Icon(
                painter = painterResource(id = R.drawable.lock),
                contentDescription = "Needs Nitro",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
