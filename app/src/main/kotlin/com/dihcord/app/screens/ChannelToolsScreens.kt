package com.dihcord.app.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.dihcord.app.FeaturePreferences
import com.dihcord.app.R
import com.dihcord.app.api.*
import com.dihcord.app.discordApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A message in a list (pins, search, saved): who and when, then a two-line preview, and where. */
@Composable
internal fun TransformingLazyColumnItemScope.MessageListCard(
    author: String,
    avatarUrl: String?,
    timeMillis: Long?,
    preview: String,
    place: String?,
    spec: TransformationSpec,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UserAvatar(author, avatarUrl, 22.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        author,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    timeMillis?.let {
                        Text(" · " + shortAgo(it), style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (place != null) {
                    Text(place, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Pinned messages

@Composable
fun PinnedScreen(channelId: String, channelName: String, onOpen: (messageId: String) -> Unit) {
    val repo = LocalContext.current.discordApp.repository ?: return
    var pins by remember { mutableStateOf<List<DiscordMessage>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val myId = repo.currentUser.collectAsState().value?.id
    LaunchedEffect(channelId, attempt) {
        pins = null; error = null
        repo.pins(channelId).onSuccess { pins = it }.onFailure { error = it }
    }
    PinnedContent(channelName, pins, error, myId, onRetry = { attempt++ }, onOpen = onOpen)
}

@Composable
internal fun PinnedContent(
    channelName: String,
    pins: List<DiscordMessage>?,
    error: Throwable?,
    myId: String?,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Pinned") }
            item { ScreenSubtitle(channelName) }
            when {
                error != null -> item { ErrorState("Couldn't load pins", error, onRetry) }
                pins == null -> item { LoadingIndicator() }
                pins.isEmpty() -> item { EmptyState(R.drawable.pin, "Nothing pinned yet", "Long-press a message to pin it.") }
                else -> items(pins.size, key = { pins[it].id }) { i ->
                    val m = pins[i]
                    MessageListCard(m.author.displayName, m.author.avatarUrl(64), snowflakeMillis(m.id), messagePreview(m, myId), null, spec) { onOpen(m.id) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Search

@Composable
fun SearchScreen(channelId: String, guildId: String?, channelName: String, onOpen: (messageId: String) -> Unit) {
    val repo = LocalContext.current.discordApp.repository ?: return
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DiscordMessage>?>(emptyList()) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val myId = repo.currentUser.collectAsState().value?.id
    LaunchedEffect(query, attempt) {
        error = null
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(600) // wait for typing to settle
        results = null
        repo.search(guildId, channelId, q).onSuccess { results = it }.onFailure { error = it; results = emptyList() }
    }
    SearchContent(channelName, query, { query = it.take(64) }, results, error, myId, onRetry = { attempt++ }, onOpen = onOpen)
}

@Composable
internal fun SearchContent(
    channelName: String,
    query: String,
    onQuery: (String) -> Unit,
    results: List<DiscordMessage>?,
    error: Throwable?,
    myId: String?,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Search") }
            item { ScreenSubtitle(channelName) }
            item {
                CompactTextField(
                    value = query, onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 48.dp),
                    placeholder = "Search messages", singleLine = true
                )
            }
            when {
                error is DiscordRestClient.SearchNotReadyException -> item {
                    EmptyState(R.drawable.search, "Discord is still indexing this chat", "Try again in a minute.")
                }
                error != null -> item { ErrorState("Couldn't search", error, onRetry) }
                results == null -> item { LoadingIndicator() }
                query.trim().length < 2 -> item { EmptyState(R.drawable.search, "Type at least 2 letters") }
                results.isEmpty() -> item { EmptyState(R.drawable.search, "No messages found", "Try another word.") }
                else -> items(results.size, key = { results[it].id }) { i ->
                    val m = results[i]
                    MessageListCard(m.author.displayName, m.author.avatarUrl(64), snowflakeMillis(m.id), messagePreview(m, myId), null, spec) { onOpen(m.id) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Saved messages

@Composable
fun SavedScreen(onOpen: (SavedMessage) -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(FeaturePreferences.saved(context)) }
    SavedContent(saved, onOpen = onOpen, onRemove = { m ->
        FeaturePreferences.toggleSaved(context, m)
        saved = FeaturePreferences.saved(context)
    })
}

@Composable
internal fun SavedContent(saved: List<SavedMessage>, onOpen: (SavedMessage) -> Unit, onRemove: (SavedMessage) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Saved") }
            if (saved.isEmpty()) {
                item { EmptyState(R.drawable.bookmark_border, "No saved messages", "Long-press a message and tap Save.") }
            } else {
                item { ScreenSubtitle("Swipe one away to remove it") }
                items(saved.size, key = { saved[it].messageId }) { i ->
                    val m = saved[i]
                    SwipeAway(onDismiss = { onRemove(m) }) {
                        MessageListCard(m.author, null, snowflakeMillis(m.messageId), m.preview, if (m.guildId != null) "#${m.channelName}" else m.channelName, spec) { onOpen(m) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Forward

/** A place to forward to. */
data class ForwardTarget(val channelId: String, val name: String, val detail: String?, val avatarUrl: String?)

@Composable
fun ForwardScreen(message: DiscordMessage, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val scope = rememberCoroutineScope()
    val dms by repo.dmChannels.collectAsState()
    val channels by repo.guildChannelsMap.collectAsState()
    val guilds by repo.guilds.collectAsState()
    var query by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val targets = remember(dms, channels, guilds, message.guildId) {
        val dmTargets = dms.sortedByDescending { it.lastMessageId?.toLongOrNull() ?: 0L }.take(15).map {
            ForwardTarget(it.id, it.displayName, if (it.recipients.size > 1) "Group" else "Direct message", if (it.recipients.size == 1) it.recipients[0].avatarUrl(64) else null)
        }
        // Channels of the servers whose lists are loaded, this message's server first.
        val serverTargets = channels.entries.sortedBy { if (it.key == message.guildId) 0 else 1 }.flatMap { (gid, groups) ->
            val gName = guilds.firstOrNull { it.id == gid }?.name
            groups.flatMap { it.channels }.filter { it.isText && it.hasAccess }.map { ForwardTarget(it.id, "#${it.name}", gName, null) }
        }
        dmTargets + serverTargets
    }
    ForwardContent(
        preview = messagePreview(message, null),
        query = query, onQuery = { query = it.take(32) },
        targets = targets.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) },
        sending = sending, error = error,
        onPick = { t ->
            if (sending != null) return@ForwardContent
            sending = t.channelId; error = null
            scope.launch {
                repo.forward(message, t.channelId)
                    .onSuccess { Toast.makeText(context, "Forwarded to ${t.name}", Toast.LENGTH_SHORT).show(); onDone() }
                    .onFailure { error = it }
                sending = null
            }
        }
    )
}

@Composable
internal fun ForwardContent(
    preview: String,
    query: String,
    onQuery: (String) -> Unit,
    targets: List<ForwardTarget>,
    sending: String?,
    error: Throwable?,
    onPick: (ForwardTarget) -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("Forward to…") }
            item {
                Text(
                    "“$preview”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.85f)
                )
            }
            item {
                CompactTextField(query, onQuery, Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 48.dp), placeholder = "Search", singleLine = true)
            }
            if (error != null) item {
                Text("Couldn't forward. " + friendlyError(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            if (targets.isEmpty()) item { EmptyState(R.drawable.forward, "Nowhere to forward to", "Open a server once to list its channels.") }
            items(targets.size, key = { targets[it].channelId }) { i ->
                val t = targets[i]
                Button(
                    onClick = { onPick(t) },
                    enabled = sending == null,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { UserAvatar(t.name.removePrefix("#"), t.avatarUrl, 26.dp) },
                    label = { Text(if (sending == t.channelId) "Sending…" else t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = t.detail?.let { d -> { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Create a poll

/** How long a poll runs, in hours, with its label. */
private val PollDurations = listOf(1 to "1 hour", 4 to "4 hours", 8 to "8 hours", 24 to "1 day", 72 to "3 days", 168 to "1 week")

@Composable
fun CreatePollScreen(channelId: String, onPosted: () -> Unit) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val scope = rememberCoroutineScope()
    var posting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    CreatePollContent(posting = posting, error = error) { question, answers, hours, multi ->
        posting = true; error = null
        scope.launch {
            repo.createPoll(channelId, question, answers.map { it to null }, hours, multi)
                .onSuccess { onPosted() }
                .onFailure { error = it }
            posting = false
        }
    }
}

@Composable
internal fun CreatePollContent(
    posting: Boolean,
    error: Throwable?,
    initialQuestion: String = "",
    initialAnswers: List<String> = listOf("", ""),
    onPost: (question: String, answers: List<String>, hours: Int, multiselect: Boolean) -> Unit,
) {
    var question by remember { mutableStateOf(initialQuestion) }
    val answers = remember { mutableStateListOf<String>().apply { addAll(initialAnswers) } }
    var hours by remember { mutableIntStateOf(24) }
    var multi by remember { mutableStateOf(false) }
    val filled = answers.map { it.trim() }.filter { it.isNotEmpty() }
    val ready = question.isNotBlank() && filled.size >= 2 && !posting
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = state,
        edgeButton = {
            EdgeButton(onClick = { if (ready) onPost(question.trim(), filled, hours, multi) }, enabled = ready) {
                Text(if (posting) "Posting…" else "Post")
            }
        }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("New poll") }
            item {
                CompactTextField(question, { question = it.take(300) }, Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 80.dp), placeholder = "Ask a question", maxLines = 3)
            }
            items(answers.size) { i ->
                CompactTextField(
                    answers[i], { answers[i] = it.take(55) },
                    Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 48.dp).padding(top = 2.dp),
                    placeholder = "Answer ${i + 1}", singleLine = true
                )
            }
            if (answers.size < 10) item {
                CompactButton(onClick = { answers.add("") }, label = { Text("Add answer") }, icon = { Icon(painterResource(R.drawable.add), null, Modifier.size(16.dp)) }, colors = ButtonDefaults.childButtonColors())
            }
            item { SectionHeader("Runs for") }
            item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PollDurations.forEach { (h, label) ->
                        val on = h == hours
                        Box(
                            Modifier.clip(RoundedCornerShape(14.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { hours = h }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            item {
                SwitchButton(
                    checked = multi, onCheckedChange = { multi = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    label = { Text("Allow several answers", style = MaterialTheme.typography.bodySmall) }
                )
            }
            if (error != null) item {
                Text("Couldn't post. " + friendlyError(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------- Slash commands

/** Matching commands for "/[query]" above the message box; tapping one opens its form. */
@Composable
internal fun CommandSuggestions(commands: List<ApplicationCommand>, query: String, onPick: (ApplicationCommand) -> Unit) {
    val q = query.removePrefix("/").trim().lowercase()
    val shown = commands.filter { q.isEmpty() || it.name.startsWith(q) || it.name.contains(q) }.take(6)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (shown.isEmpty()) {
            Text("No commands match", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
        shown.forEach { c ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onPick(c) }.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("/" + c.name, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    listOfNotNull(c.appName, c.description.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Fills in a command's options and runs it. Values: text for strings, numbers, true/false. */
@Composable
internal fun CommandFormScreen(command: ApplicationCommand, running: Boolean, error: Throwable?, onRun: (Map<String, Any>) -> Unit) {
    val values = remember(command.id) { mutableStateMapOf<String, Any>() }
    val options = command.options.filter { it.supported }
    val missing = options.any { it.required && values[it.name].let { v -> v == null || (v is String && v.isBlank()) } }
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = state,
        edgeButton = { EdgeButton(onClick = { if (!missing && !running) onRun(values.toMap()) }, enabled = !missing && !running) { Text(if (running) "Sending…" else "Send") } }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle("/" + command.name) }
            if (command.description.isNotBlank()) item { ScreenSubtitle(command.description) }
            if (options.isEmpty()) item { EmptyState(R.drawable.chat, "No options", "Tap Send to run it.") }
            options.forEach { o ->
                item(key = o.name) {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            o.name + if (o.required) " *" else "",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center
                        )
                        if (o.description.isNotBlank()) {
                            Text(o.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        when {
                            o.choices.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                o.choices.forEach { (label, value) ->
                                    val on = values[o.name] == value
                                    Box(
                                        Modifier.clip(RoundedCornerShape(14.dp))
                                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                                            .clickable { if (on) values.remove(o.name) else values[o.name] = value }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) { Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                                }
                            }
                            o.type == 5 -> SwitchButton(
                                checked = values[o.name] == true,
                                onCheckedChange = { values[o.name] = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(if (values[o.name] == true) "Yes" else "No") }
                            )
                            else -> CompactTextField(
                                value = values[o.name]?.toString() ?: "",
                                onValueChange = { text ->
                                    when (o.type) {
                                        4 -> text.toLongOrNull()?.let { values[o.name] = it } ?: if (text.isEmpty()) values.remove(o.name) else Unit
                                        10 -> text.toDoubleOrNull()?.let { values[o.name] = it } ?: if (text.isEmpty()) values.remove(o.name) else Unit
                                        else -> values[o.name] = text
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 60.dp),
                                placeholder = if (o.type == 3) "Text" else "Number",
                                maxLines = 2
                            )
                        }
                    }
                }
            }
            if (options.size < command.options.size) item {
                Text("Some options can't be filled in on the watch.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (error != null) item {
                Text("Couldn't run it. " + friendlyError(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A bot's dropdown as a list: tap to pick (or toggle, when several may be picked), then send. */
@Composable
internal fun SelectMenuScreen(select: MessageComponent.Select, sending: Boolean, onSubmit: (List<String>) -> Unit) {
    val picked = remember(select.customId) { mutableStateListOf<String>().apply { addAll(select.options.filter { it.default }.map { it.value }) } }
    val multi = select.maxValues > 1
    val ok = picked.size >= select.minValues.coerceAtLeast(if (multi) 0 else 1) && picked.size <= select.maxValues
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = state,
        edgeButton = { EdgeButton(onClick = { if (ok && !sending) onSubmit(picked.toList()) }, enabled = ok && !sending) { Text(if (sending) "Sending…" else "Choose") } }
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ScreenTitle(select.placeholder ?: "Choose") }
            if (multi) item { ScreenSubtitle("Pick up to ${select.maxValues}") }
            items(select.options.size) { i ->
                val o = select.options[i]
                val on = o.value in picked
                Button(
                    onClick = {
                        if (multi) { if (on) picked.remove(o.value) else if (picked.size < select.maxValues) picked.add(o.value) }
                        else { picked.clear(); picked.add(o.value) }
                    },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = if (on) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    icon = o.emoji?.takeIf { it.id == null }?.let { e -> { Text(e.name) } },
                    label = { Text(o.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = o.description?.let { d -> { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                )
            }
        }
    }
}
