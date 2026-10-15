package com.dihcord.app.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.imageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.dihcord.app.R
import com.dihcord.app.SetupPreferences
import com.dihcord.app.api.Guild
import com.dihcord.app.api.Ping
import com.dihcord.app.api.ServerEntry
import com.dihcord.app.api.ServerLayout
import com.dihcord.app.api.orderKey
import com.dihcord.app.discordApp
import kotlinx.coroutines.launch

private val DiscordBlurple = Color(0xFF5865F2)
private val MentionRed = Color(0xFFF23F43)
private val TileSize = 48.dp
private val TileSizeNoLabel = 54.dp

/**
 * Servers as a launcher-style honeycomb of round icons, mirroring the user's Discord folders.
 * With [folderKey] set, shows just the servers inside that folder.
 */
@Composable
fun ServerScreen(
    folderKey: String? = null,
    onNavigateToChannels: (guildId: String, guildName: String) -> Unit,
    onOpenFolder: (folderKey: String) -> Unit = {}
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val menuState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val imageLoader = context.imageLoader

    val guilds by (repo?.guilds ?: return).collectAsState()
    val folders by repo.guildFolders.collectAsState()
    val pings by repo.pings.collectAsState()
    val readState by repo.readState.collectAsState()
    val serverPings = remember(pings) { pings.filter { it.guildName != null } }

    // guildId -> total mention count, attributed through the channel -> guild cache
    val channelGuilds = remember(readState) { repo.getChannelGuilds() }
    val guildMentionCounts = remember(readState, channelGuilds) {
        val counts = mutableMapOf<String, Int>()
        readState.forEach { (channelId, state) ->
            if (state.mentionCount > 0) {
                val guildId = channelGuilds[channelId] ?: return@forEach
                counts[guildId] = (counts[guildId] ?: 0) + state.mentionCount
            }
        }
        counts
    }
    var loading by remember { mutableStateOf(guilds.isEmpty()) }

    var pinnedIds by remember { mutableStateOf(SetupPreferences.getPinnedServers(context)) }
    var hiddenIds by remember { mutableStateOf(SetupPreferences.getHiddenServers(context)) }
    var mutedIds by remember { mutableStateOf(SetupPreferences.getMutedServers(context)) }
    var showHidden by remember { mutableStateOf(false) }
    var menuGuild by remember { mutableStateOf<Guild?>(null) }
    var menuFolder by remember { mutableStateOf<ServerEntry.Folder?>(null) }
    var customOrder by remember { mutableStateOf(SetupPreferences.getServerOrder(context)) }
    val reorder = remember { ReorderState() }
    var rootPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var rootSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val showNames = remember { SetupPreferences.getShowServerNames(context) }

    LaunchedEffect(Unit) {
        scope.launch {
            repo.refreshGuilds()
            loading = false
        }
        scope.launch { repo.refreshGuildFolders() }
    }

    val allEntries = remember(guilds, folders, pinnedIds, hiddenIds, showHidden, customOrder) {
        ServerLayout.applyCustomOrder(
            ServerLayout.build(guilds, folders, pinnedIds, hiddenIds, showHidden), customOrder, pinnedIds
        )
    }
    val openFolder = remember(allEntries, folderKey) {
        folderKey?.let { key ->
            allEntries.filterIsInstance<ServerEntry.Folder>().firstOrNull { it.folder.key == key }
        }
    }
    val entries: List<ServerEntry> = when {
        folderKey == null -> allEntries
        openFolder != null -> ServerLayout.applyCustomOrder(
            openFolder.guilds.map { ServerEntry.Single(it) }, customOrder, pinnedIds
        )
        else -> emptyList()
    }
    val title = when {
        folderKey == null -> "Servers"
        openFolder != null -> folderName(openFolder)
        else -> "Folder"
    }

    // Moving keeps the menu open so a server can be moved several places in a row.
    fun move(entry: ServerEntry, delta: Int) {
        val updated = ServerLayout.moveInOrder(entries.map { it.orderKey }, entry.orderKey, delta, customOrder)
        if (updated != customOrder) {
            SetupPreferences.setServerOrder(context, updated)
            customOrder = updated
        }
    }

    fun isPinnedEntry(e: ServerEntry) = e is ServerEntry.Single && e.guild.id in pinnedIds

    /** Drag-and-drop: the dragged entry takes the target's place (not across the pinned/unpinned line). */
    fun dropEntry(fromKey: String, toKey: String) {
        val from = entries.firstOrNull { it.orderKey == fromKey } ?: return
        val to = entries.firstOrNull { it.orderKey == toKey } ?: return
        if (isPinnedEntry(from) != isPinnedEntry(to)) return
        val updated = ServerLayout.moveTo(entries.map { it.orderKey }, fromKey, toKey, customOrder)
        if (updated != customOrder) {
            SetupPreferences.setServerOrder(context, updated)
            customOrder = updated
        }
    }

    // Scroll by itself while an icon is dragged near the top or bottom edge.
    LaunchedEffect(reorder.dragKey) {
        if (reorder.dragKey == null) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        while (reorder.dragKey != null) {
            val y = reorder.dragPos.y
            when {
                y < rootPos.y + edge -> listState.scrollBy(-14f)
                y > rootPos.y + rootSize.height - edge -> listState.scrollBy(14f)
            }
            kotlinx.coroutines.delay(16)
        }
    }

    val activeMenu = menuGuild
    if (activeMenu != null) {
        val idx = entries.indexOfFirst { it.orderKey == activeMenu.id }
        ServerMenu(
            title = activeMenu.name,
            isPinned = activeMenu.id in pinnedIds,
            isHidden = activeMenu.id in hiddenIds,
            isMuted = activeMenu.id in mutedIds,
            listState = menuState,
            onTogglePin = {
                SetupPreferences.togglePinnedServer(context, activeMenu.id)
                pinnedIds = SetupPreferences.getPinnedServers(context)
                menuGuild = null
            },
            onToggleHidden = {
                SetupPreferences.toggleHiddenServer(context, activeMenu.id)
                hiddenIds = SetupPreferences.getHiddenServers(context)
                menuGuild = null
            },
            onToggleMuted = {
                SetupPreferences.toggleMutedServer(context, activeMenu.id)
                mutedIds = SetupPreferences.getMutedServers(context)
                menuGuild = null
            },
            onMoveEarlier = if (idx >= 0 && ServerLayout.canMove(entries, idx, -1, pinnedIds)) {
                { move(entries[idx], -1) }
            } else null,
            onMoveLater = if (idx >= 0 && ServerLayout.canMove(entries, idx, 1, pinnedIds)) {
                { move(entries[idx], 1) }
            } else null,
            onDismiss = { menuGuild = null }
        )
        return
    }

    val activeFolder = menuFolder
    if (activeFolder != null) {
        val idx = entries.indexOfFirst { it.orderKey == activeFolder.orderKey }
        ServerMenu(
            title = folderName(activeFolder),
            listState = menuState,
            onMoveEarlier = if (idx >= 0 && ServerLayout.canMove(entries, idx, -1, pinnedIds)) {
                { move(entries[idx], -1) }
            } else null,
            onMoveLater = if (idx >= 0 && ServerLayout.canMove(entries, idx, 1, pinnedIds)) {
                { move(entries[idx], 1) }
            } else null,
            onDismiss = { menuFolder = null }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootPos = it.positionInWindow()
                rootSize = it.size
            }
    ) {
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState) {
            item {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (folderKey == null && serverPings.isNotEmpty()) {
                item {
                    Text(
                        text = "@  Mentions",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                items(serverPings.size) { index ->
                    val ping = serverPings[index]
                    PingCard(ping = ping, onClick = {
                        onNavigateToChannels(
                            ping.message.guildId ?: return@PingCard,
                            ping.guildName ?: return@PingCard
                        )
                    })
                }
            }

            when {
                loading && guilds.isEmpty() -> item { CircularProgressIndicator() }
                entries.isEmpty() -> item {
                    Text(
                        if (folderKey == null) "No servers found." else "This folder is empty.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> {
                    val rows = ServerLayout.honeycombRows(entries)
                    items(rows.size) { rowIndex ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                        ) {
                            rows[rowIndex].forEach { entry ->
                                when (entry) {
                                    is ServerEntry.Single -> {
                                        val guild = entry.guild
                                        val isHidden = guild.id in hiddenIds
                                        ServerTile(
                                            guild = guild,
                                            imageLoader = imageLoader,
                                            isPinned = guild.id in pinnedIds,
                                            isHidden = isHidden,
                                            isMuted = guild.id in mutedIds,
                                            mentionCount = guildMentionCounts[guild.id] ?: 0,
                                            showName = showNames,
                                            reorder = reorder,
                                            onDrop = { from, to -> dropEntry(from, to) },
                                            onClick = { if (!isHidden) onNavigateToChannels(guild.id, guild.name) },
                                            onLongClick = { menuGuild = guild }
                                        )
                                    }

                                    is ServerEntry.Folder -> FolderTile(
                                        entry = entry,
                                        imageLoader = imageLoader,
                                        mentionCount = entry.guilds.sumOf { guildMentionCounts[it.id] ?: 0 },
                                        showName = showNames,
                                        reorder = reorder,
                                        onDrop = { from, to -> dropEntry(from, to) },
                                        onLongClick = { menuFolder = entry },
                                        onClick = { onOpenFolder(entry.folder.key) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (folderKey == null && hiddenIds.isNotEmpty()) {
                item {
                    Button(
                        onClick = { showHidden = !showHidden },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Text(
                            if (showHidden) "Hide hidden servers" else "Show hidden (${hiddenIds.size})",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    // The icon "lifted" under the finger while dragging.
    val draggedKey = reorder.dragKey
    if (draggedKey != null) {
        val dragged = entries.firstOrNull { it.orderKey == draggedKey }
        val half = with(density) { 24.dp.toPx() }
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (reorder.dragPos.x - rootPos.x - half).toInt(),
                        (reorder.dragPos.y - rootPos.y - half * 1.8f).toInt()
                    )
                }
                .size(48.dp)
                .alpha(0.92f)
        ) {
            when (dragged) {
                is ServerEntry.Single -> GuildIcon(dragged.guild, imageLoader, size = 48.dp, shape = CircleShape)
                is ServerEntry.Folder -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DiscordBlurple.copy(alpha = 0.6f))
                )
                null -> {}
            }
        }
    }
    }
}

private fun folderName(entry: ServerEntry.Folder): String =
    entry.folder.name ?: entry.guilds.take(3).joinToString(", ") { it.name }

/** Icon + label column used for both servers and folders. */
@Composable
private fun TileFrame(
    label: String,
    mentionCount: Int,
    showName: Boolean = true,
    dimmed: Boolean = false,
    reorder: ReorderState,
    itemKey: String,
    onDrop: (fromKey: String, toKey: String) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    icon: @Composable BoxScope.() -> Unit
) {
    val beingDragged = reorder.dragKey == itemKey
    val isTarget = reorder.targetKey == itemKey
    Column(
        modifier = Modifier
            .width(56.dp)
            .alpha(if (beingDragged) 0.3f else if (dimmed) 0.4f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(if (showName) TileSize else TileSizeNoLabel)
                .then(
                    if (isTarget) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier
                )
                .reorderable(itemKey, reorder, onClick, onLongClick, onDrop)
        ) {
            icon()
            overlay()
            if (mentionCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .background(MentionRed, CircleShape)
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (mentionCount > 99) "99+" else mentionCount.toString(),
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        if (showName) {
            Text(
                text = label,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ServerTile(
    guild: Guild,
    imageLoader: ImageLoader,
    isPinned: Boolean,
    isHidden: Boolean,
    isMuted: Boolean,
    mentionCount: Int,
    showName: Boolean,
    reorder: ReorderState,
    onDrop: (String, String) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    TileFrame(
        label = guild.name,
        mentionCount = if (isHidden) 0 else mentionCount,
        showName = showName,
        dimmed = isHidden,
        reorder = reorder,
        itemKey = guild.id,
        onDrop = onDrop,
        onClick = onClick,
        onLongClick = onLongClick,
        overlay = {
            if (isMuted || isPinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = if (isMuted) R.drawable.mute else R.drawable.pin),
                        contentDescription = if (isMuted) "Muted" else "Pinned",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }
    ) {
        GuildIcon(guild, imageLoader, size = if (showName) TileSize else TileSizeNoLabel, shape = CircleShape)
    }
}

@Composable
private fun FolderTile(
    entry: ServerEntry.Folder,
    imageLoader: ImageLoader,
    mentionCount: Int,
    showName: Boolean,
    reorder: ReorderState,
    onDrop: (String, String) -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val accent = entry.folder.color?.let { Color(0xFF000000 or it.toLong()) } ?: DiscordBlurple
    TileFrame(
        label = folderName(entry),
        mentionCount = mentionCount,
        showName = showName,
        reorder = reorder,
        itemKey = entry.orderKey,
        onDrop = onDrop,
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(accent.copy(alpha = 0.3f))
                .border(1.5.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                .padding(5.dp)
        ) {
            // 2x2 preview of the first servers in the folder
            val preview = entry.guilds.take(4)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                preview.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        pair.forEach { GuildIcon(it, imageLoader, size = 17.dp, shape = CircleShape) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuildIcon(guild: Guild, imageLoader: ImageLoader, size: Dp, shape: Shape) {
    val iconUrl = guild.iconUrl(128)
    if (iconUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(iconUrl).crossfade(true).build(),
            imageLoader = imageLoader,
            contentDescription = guild.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(DiscordBlurple, shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials(guild.name),
                color = Color.White,
                fontSize = (size.value * 0.33f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/** "My Cool Server" -> "MCS" (max 3 letters), like Discord's icon-less servers. */
private fun initials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(3).joinToString("") { it.first().toString() }
        .ifEmpty { "?" }

/** Long-press menu for a server (all options) or a folder (move only: the pin/mute/hide callbacks are null). */
@Composable
private fun ServerMenu(
    title: String,
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
    onDismiss: () -> Unit,
    onMoveEarlier: (() -> Unit)? = null,
    onMoveLater: (() -> Unit)? = null,
    isPinned: Boolean = false,
    isHidden: Boolean = false,
    isMuted: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onToggleHidden: (() -> Unit)? = null,
    onToggleMuted: (() -> Unit)? = null,
) {
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            onMoveEarlier?.let { move -> item { MoveButton("Move earlier", rotated = true, move) } }
            onMoveLater?.let { move -> item { MoveButton("Move later", rotated = false, move) } }
            onTogglePin?.let { item { MenuButton(if (isPinned) R.drawable.unpin else R.drawable.pin, if (isPinned) "Unpin" else "Pin to top", it) } }
            onToggleMuted?.let { item { MenuButton(R.drawable.mute, if (isMuted) "Unmute" else "Mute notifications", it) } }
            onToggleHidden?.let { item { MenuButton(if (isHidden) R.drawable.unhide else R.drawable.hide, if (isHidden) "Unhide" else "Hide", it) } }
            item {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Done") }
            }
        }
    }
}

@Composable
private fun MoveButton(label: String, rotated: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        colors = ButtonDefaults.filledTonalButtonColors()
    ) {
        Icon(
            painter = painterResource(id = R.drawable.down),
            tint = Color.White,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .rotate(if (rotated) 180f else 0f)
        )
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun MenuButton(iconRes: Int, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        colors = ButtonDefaults.filledTonalButtonColors()
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            tint = Color.White,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun PingCard(ping: Ping, onClick: () -> Unit) {
    TitleCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        title = {
            Text(
                text = "${ping.guildName} • #${ping.channelName}",
                style = MaterialTheme.typography.titleSmall
            )
        },
        time = {
            Text(
                text = ping.message.author.displayName,
                style = MaterialTheme.typography.bodyExtraSmall
            )
        }
    ) {
        Text(
            text = ping.message.content.take(80),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
