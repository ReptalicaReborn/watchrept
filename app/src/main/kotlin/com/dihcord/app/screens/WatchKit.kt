package com.dihcord.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import coil.compose.AsyncImage
import coil.imageLoader
import com.dihcord.app.R
import com.dihcord.app.api.DiscordMessage
import com.dihcord.app.api.OnlineStatus

/*
 * Watch-sized building blocks shared by the redesigned screens: Discord's status colours, the mention
 * badge, avatars, short times, one-line message previews, empty states, the one-screen action menu and
 * the time text with a title curved in front of it.
 */

/** Discord's fixed status and alert colours, so every screen uses the same red, green, etc. */
object DiscordColors {
    val Mention = Color(0xFFF23F43)
    val Online = Color(0xFF23A55A)
    val Idle = Color(0xFFF0B232)
    val Dnd = Color(0xFFF23F43)
    val Offline = Color(0xFF80848E)
    val MentionHighlight = Color(0xFFFEE75C)
    val Blurple = Color(0xFF5865F2)
}

/** The status ring colour; null for offline/invisible, which get no ring so the list stays calm. */
fun OnlineStatus.ringColor(): Color? = when (this) {
    OnlineStatus.ONLINE -> DiscordColors.Online
    OnlineStatus.IDLE -> DiscordColors.Idle
    OnlineStatus.DND -> DiscordColors.Dnd
    OnlineStatus.INVISIBLE, OnlineStatus.OFFLINE -> null
}

/** Digits that all have the same width, so counts and timers don't jiggle as they change. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/** The red mention-count pill, outlined in black so it stands out from whatever it sits on. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
            .background(Color.Black, CircleShape)
            .padding(1.5.dp)
            .background(DiscordColors.Mention, CircleShape)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            style = TabularNumbers,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** A small dot for "new messages, none of them for you", outlined in black like [CountBadge]. */
@Composable
fun UnreadDot(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.size(11.dp).background(Color.Black, CircleShape).padding(1.5.dp).background(color, CircleShape))
}

/** A stable colour per name for people and servers without a picture. */
fun avatarColor(name: String): Color {
    val palette = listOf(0xFF5865F2, 0xFF3BA55C, 0xFFFAA61A, 0xFFED4245, 0xFFEB459E, 0xFF747F8D)
    return Color(palette[(name.hashCode() and 0x7FFFFFFF) % palette.size])
}

/**
 * A round avatar: the picture when there is one, otherwise the name's first letter on a stable colour.
 * [ring] draws a status ring with a thin black gap between it and the picture.
 */
@Composable
fun UserAvatar(
    name: String,
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    ring: Color? = null,
    dimmed: Boolean = false,
) {
    val ringWidth = if (size >= 32.dp) 2.dp else 1.5.dp
    val alpha = if (dimmed) 0.4f else 1f
    Box(
        modifier
            .size(size)
            .then(if (ring != null) Modifier.border(ringWidth, ring, CircleShape).padding(ringWidth + 1.5.dp) else Modifier)
            .clip(CircleShape)
            .background(avatarColor(name).copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            color = Color.White.copy(alpha = alpha),
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold
        )
        if (url != null && !dimmed) {
            AsyncImage(
                model = url,
                imageLoader = LocalContext.current.imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** When a Discord id (snowflake) was created, in epoch milliseconds. */
fun snowflakeMillis(id: String?): Long? = id?.toLongOrNull()?.let { (it ushr 22) + 1_420_070_400_000L }

/** "now", "5m", "3h", "Tue", "Mar 4": how long ago, short enough for the corner of a row. */
fun shortAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val mins = (now - millis) / 60_000
    val zoned = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        mins < 24 * 60 -> "${mins / 60}h"
        mins < 6 * 24 * 60 -> zoned.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        else -> zoned.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.getDefault()))
    }
}

private val UserMentionRegex = Regex("<@!?(\\d+)>")
private val ChannelMentionRegex = Regex("<#(\\d+)>")
private val RoleMentionRegex = Regex("<@&(\\d+)>")
private val CustomEmojiRegex = Regex("<a?:(\\w+):\\d+>")
private val TimestampRegex = Regex("<t:(\\d+)(:\\w)?>")
private val LinkRegex = Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)")
private val SpoilerRegex = Regex("\\|\\|.+?\\|\\|", RegexOption.DOT_MATCHES_ALL)
private val MarkdownMarks = Regex("(\\*\\*|__|~~|\\|\\||`+)")
private val LinePrefix = Regex("^(>+ |#{1,3} |-# )")

/**
 * A message as one line of plain text for lists: mentions and emoji as text, no markdown marks, and a
 * word like "Photo" for messages without text. Messages from [myId] start with "You: ".
 */
fun messagePreview(msg: DiscordMessage, myId: String?, channelNames: Map<String, String> = emptyMap()): String {
    val names = msg.mentionedUsers.associate { it.id to it.displayName }
    var text = msg.content
        // Spoilers stay hidden here too, as they are in the chat until tapped.
        .replace(SpoilerRegex, "▒▒▒")
        .replace(UserMentionRegex) { "@" + (names[it.groupValues[1]] ?: "someone") }
        .replace(ChannelMentionRegex) { "#" + (channelNames[it.groupValues[1]] ?: "channel") }
        .replace(RoleMentionRegex, "@role")
        .replace(CustomEmojiRegex) { ":${it.groupValues[1]}:" }
        .replace(TimestampRegex) { m ->
            m.groupValues[1].toLongOrNull()?.let { com.dihcord.app.api.DiscordTimestamp.format(it, m.groupValues[2].removePrefix(":").firstOrNull()) } ?: "a time"
        }
        .replace(LinkRegex) { it.groupValues[1] }
        .replace(MarkdownMarks, "")
        .lines()
        .map { it.trim().replace(LinePrefix, "") }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    if (text.isEmpty()) text = when {
        msg.attachments.any { it.contentType?.startsWith("image/") == true } -> "Photo"
        msg.attachments.any { it.contentType?.startsWith("video/") == true } -> "Video"
        msg.attachments.any { it.contentType?.startsWith("audio/") == true } -> "Voice message"
        msg.attachments.isNotEmpty() -> "File"
        msg.stickers.isNotEmpty() -> "Sticker"
        msg.poll != null -> "Poll"
        msg.embeds.isNotEmpty() -> "Link"
        else -> "Message"
    }
    return if (myId != null && msg.author.id == myId) "You: $text" else text
}

/** A centred icon, title and hint for a list with nothing in it yet. */
@Composable
fun EmptyState(iconRes: Int, title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.8f)
            )
        }
    }
}

/**
 * A failure in plain words for the screen, never the raw exception: "Failed to connect to /1.2.3.4"
 * means nothing on a wrist.
 */
fun friendlyError(e: Throwable?): String = when {
    e is com.dihcord.app.api.DiscordApiException && e.httpCode == 401 -> "You've been signed out."
    e is com.dihcord.app.api.DiscordApiException && e.httpCode == 403 -> "You don't have access to this."
    e is com.dihcord.app.api.DiscordApiException && e.httpCode == 404 -> "This isn't there any more."
    e is com.dihcord.app.api.DiscordApiException && e.httpCode == 429 -> "Discord asked us to slow down. Try again in a moment."
    e is com.dihcord.app.api.DiscordApiException -> "Discord had a problem. Try again."
    e is java.io.IOException -> "No connection. Check the watch's Wi-Fi or phone link."
    else -> "Something went wrong. Try again."
}

/** [EmptyState] for a failed load, with a "Try again" button when [onRetry] is given. */
@Composable
fun ErrorState(title: String, error: Throwable?, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(R.drawable.close, title, friendlyError(error))
        if (onRetry != null) {
            androidx.wear.compose.material3.CompactButton(
                onClick = onRetry,
                label = { Text("Try again") },
                colors = androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors()
            )
        }
    }
}

/**
 * One action in an [ActionMenu]; [flipIcon] turns the icon upside down (the "down" arrow as "up") and
 * [destructive] paints it red.
 */
data class MenuAction(
    val iconRes: Int,
    val label: String,
    val flipIcon: Boolean = false,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * A long-press menu that fits on one screen: what it is about at the top, the actions as round buttons
 * with a label (three to a row) and a small close button. Back also closes it. [belowTitle] adds
 * something between the title and the actions (a message preview, quick reactions).
 */
@Composable
fun ActionMenu(
    title: String,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    header: @Composable () -> Unit = {},
    belowTitle: (@Composable () -> Unit)? = null,
) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.8f).padding(top = 4.dp)
                    )
                }
            }
            belowTitle?.let { item { it() } }
            actions.chunked(3).forEach { row ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                    ) {
                        row.forEach { action -> MenuActionButton(action) }
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuActionButton(action: MenuAction) {
    Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = action.onClick,
            modifier = Modifier.size(46.dp),
            colors = if (action.destructive) androidx.wear.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) else androidx.wear.compose.material3.IconButtonDefaults.filledTonalIconButtonColors()
        ) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = action.label,
                modifier = Modifier
                    .size(20.dp)
                    .then(if (action.flipIcon) Modifier.graphicsLayer { rotationZ = 180f } else Modifier)
            )
        }
        Text(
            action.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Lets the screenshot tests pin the clock shown by [TitledTimeText]. */
val LocalTimeSource = staticCompositionLocalOf<TimeSource?> { null }

/** The time with a screen's name curved in front of it ("#general · 11:39"), freeing a row at the top. */
@Composable
fun TitledTimeText(title: String, color: Color = Color.Unspecified) {
    // The curve only has room for about a dozen letters before it runs into the clock.
    val short = if (title.length > 12) title.take(11) + "…" else title
    val content: CurvedScope.(String) -> Unit = { time ->
        timeTextCurvedText(short, androidx.wear.compose.foundation.CurvedTextStyle(color = color))
        timeTextSeparator()
        timeTextCurvedText(time)
    }
    val source = LocalTimeSource.current
    if (source != null) TimeText(timeSource = source, content = content) else TimeText(content = content)
}

/**
 * A one-screen "are you sure?": icon, question, what it is about, and two round buttons — cancel on
 * the left, the red action on the right (where the thumb doesn't rest by accident).
 */
@Composable
fun ConfirmScreen(
    iconRes: Int,
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(painterResource(iconRes), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(52.dp)) {
                    Icon(painterResource(R.drawable.close), "Cancel", Modifier.size(22.dp))
                }
                androidx.wear.compose.material3.FilledIconButton(
                    onClick = onConfirm,
                    modifier = Modifier.size(52.dp),
                    colors = androidx.wear.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(painterResource(iconRes), confirmLabel, Modifier.size(22.dp))
                }
            }
        }
    }
}

/**
 * For rows that aren't cards (emoji grids, photo rows) in a TransformingLazyColumn: they shrink and
 * fade at the round edges the same way cards do.
 */
fun Modifier.edgeMorph(
    scope: androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope,
    spec: androidx.wear.compose.material3.lazy.TransformationSpec,
): Modifier = this
    .transformedHeight(scope, spec)
    .then(with(scope) { Modifier.graphicsLayer { with(spec) { applyContainerTransformation(scrollProgress) } } })
