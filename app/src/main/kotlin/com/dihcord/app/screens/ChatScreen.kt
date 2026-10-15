package com.dihcord.app.screens

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.imageLoader
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.dihcord.app.Perf
import com.dihcord.app.api.*
import com.dihcord.app.discordApp
import com.dihcord.app.SetupPreferences
import com.dihcord.app.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshots.SnapshotStateMap
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    channelId: String,
    channelName: String,
    guildId: String? = null,
    currentUserId: String = "",
    onNavigateToProfile: ((userId: String, user: DiscordUser?) -> Unit)? = null,
    onNavigateToThread: ((threadId: String, threadName: String) -> Unit)? = null,
    onOpenThreads: (() -> Unit)? = null,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val imageLoader = context.imageLoader

    if (repo == null) return
    // Observe only this channel: the shared maps change on every message/typing event account-wide.
    val messages by remember(channelId) {
        repo.messages.map { it[channelId].orEmpty() }.distinctUntilChanged()
    }.collectAsState(initial = repo.messages.value[channelId].orEmpty())
    val typingUsers by remember(channelId) {
        repo.typing.map { it[channelId].orEmpty() }.distinctUntilChanged()
    }.collectAsState(initial = repo.typing.value[channelId].orEmpty())
    var loading by remember { mutableStateOf(true) }
    var sendError by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) } // 0 = emoji, 1 = stickers
    var inputText by remember { mutableStateOf("") }
    var pendingText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<DiscordMessage?>(null) }
    var replyPing by remember { mutableStateOf(true) }
    var selectedMsg by remember { mutableStateOf<DiscordMessage?>(null) }
    var threadSourceMsg by remember { mutableStateOf<DiscordMessage?>(null) }
    var threadNameDraft by remember { mutableStateOf("") }
    var creatingThread by remember { mutableStateOf(false) }
    var threadError by remember { mutableStateOf("") }
    var highlightedId by remember(channelId) { mutableStateOf<String?>(null) }
    var pickedMentions by remember(channelId) { mutableStateOf(mapOf<String, String>()) }
    var mentionSuggestions by remember(channelId) { mutableStateOf<List<MentionCandidate>>(emptyList()) }
    var reactingToMsg by remember { mutableStateOf<DiscordMessage?>(null) }
    val roleColorCache = remember { mutableStateMapOf<String, GuildRole?>() }
    val roleIconCache = remember { mutableStateMapOf<String, GuildRole?>() }
    val guildMemberCache = remember { mutableStateMapOf<String, GuildMember?>() }
    val roleNames = remember(guildId, loading) { mutableStateMapOf<String, String>() }
    val channelNames = remember(messages.size, loading) { repo.getChannelNames() }

    LaunchedEffect(guildId, loading) {
        if (!loading && guildId != null && repo != null) {
            val roles = repo.getGuildRoles(guildId)
            roles.forEach { roleNames[it.id] = it.name }
        }
    }

    LaunchedEffect(messages, guildId, loading) {
        if (!loading && guildId != null) {
            messages
                .map { it.author.id }
                .distinct()
                .filterNot { roleColorCache.containsKey(it) }
                .forEach { userId ->
                    roleColorCache[userId] = repo?.getTopRoleForUser(guildId, userId)
                    roleIconCache[userId] = repo?.getRoleWithIconForUser(guildId, userId)
                }
            messages
                .map { it.author.id }
                .distinct()
                .filterNot { guildMemberCache.containsKey("$guildId:$it") }
                .forEach { userId ->
                    guildMemberCache["$guildId:$userId"] = repo?.getGuildMember(guildId, userId)
                }
        }
    }
    val currentUser by repo.currentUser.collectAsState()
    val myId = currentUser?.id ?: currentUserId
    val hasNitro = currentUser?.hasNitro ?: false
    val sendAnimatedAsGif = SetupPreferences.getSendAnimatedAsGif(context)
    val spoilerRevealOnTap = remember { SetupPreferences.getSpoilerRevealOnTap(context) }
    val compactMode = remember { SetupPreferences.getCompactMode(context) }
    val slowModeSecs = remember(channelId) { repo.getSlowModeSeconds(channelId) }
    var slowRemaining by remember { mutableStateOf(0) }

    LaunchedEffect(slowModeSecs) {
        if (slowModeSecs > 0) {
            while (true) {
                slowRemaining = repo.slowModeRemainingSeconds(channelId)
                if (slowRemaining <= 0) break
                delay(1_000)
            }
        }
    }

    // Optimistic until the permission check (which may need a couple of requests) finishes.
    var abilities by remember(channelId) { mutableStateOf(ChannelAbilities.ALL) }
    LaunchedEffect(channelId) { abilities = repo.channelAbilities(channelId) }
    val canSend = abilities.canSend


    LaunchedEffect(channelId) {
        repo?.selectChannel(channelId, guildId)
        scope.launch {
            repo.loadMessages(channelId)
            loading = false
        }
    }

    val initialLastRead = remember(channelId) { repo.readState.value[channelId]?.lastMessageId }
    val scrolledToUnread = remember { mutableStateOf(false) }

    LaunchedEffect(channelId, loading, messages.size, pendingText) {
        if (!loading && !scrolledToUnread.value && messages.isNotEmpty()) {
            scrolledToUnread.value = true
            val headerOffset = 1 + (if (pendingText.isNotBlank()) 1 else 0)
            if (initialLastRead != null) {
                val lastReadIdx = messages.indexOfLast { it.id <= initialLastRead }
                when {
                    lastReadIdx >= 0 && lastReadIdx + 1 < messages.size -> {
                        listState.scrollToItem(headerOffset + lastReadIdx + 1)
                    }

                    lastReadIdx >= 0 -> {
                        listState.scrollToItem(headerOffset + lastReadIdx)
                    }

                    else -> {
                        listState.scrollToItem(headerOffset)
                    }
                }
            } else {
                listState.scrollToItem(headerOffset)
            }
        }
    }

    LaunchedEffect(scrolledToUnread.value) {
        if (scrolledToUnread.value && !loading) {
            delay(1_500)
            messages.lastOrNull()?.id?.let { repo?.markChannelRead(channelId, it) }
        }
    }

    var editingMsg by remember { mutableStateOf<DiscordMessage?>(null) }
    var editText by remember { mutableStateOf("") }

    var uploadError by remember { mutableStateOf("") }
    var showPhotoPicker by remember { mutableStateOf(false) }
    val imagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showPhotoPicker = true
        else uploadError = "Photo permission denied"
    }

    var isRecording by remember { mutableStateOf(false) }
    var recordingSecs by remember { mutableStateOf(0) }
    var voiceFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) uploadError = "Microphone permission denied"
    }

    fun startRecording() {
        val hasAudio =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        if (!hasAudio) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO); return
        }
        try {
            val f = File(context.cacheDir, "voice_${System.currentTimeMillis()}.ogg")
            voiceFile = f
            val mr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mr.setOutputFile(f.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            isRecording = true
            recordingSecs = 0
        } catch (e: Exception) {
            uploadError = "Mic error: ${e.message}"
            isRecording = false
        }
    }

    fun stopAndSendRecording() {
        val mr = recorder ?: return
        val f = voiceFile ?: return
        val dur = recordingSecs.toDouble()
        try {
            mr.stop()
            mr.release()
        } catch (_: Exception) {
        }
        recorder = null
        isRecording = false
        scope.launch {
            runCatching {
                val bytes = f.readBytes()
                f.delete()
                repo.sendVoiceMessage(channelId, bytes, dur)
                    .onFailure { uploadError = "Voice send failed: ${it.message}" }
            }.onFailure { uploadError = "Error: ${it.message}" }
        }
    }

    fun cancelRecording() {
        val mr = recorder ?: return
        recorder = null
        try {
            mr.stop(); mr.release()
        } catch (_: Exception) {
        }
        isRecording = false
        voiceFile?.delete()
        voiceFile = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                cancelRecording()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cancelRecording()
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                delay(1_000)
                recordingSecs++
                if (recordingSecs >= 120) {
                    stopAndSendRecording(); break
                }
            }
        }
    }

    fun openEdit(msg: DiscordMessage) {
        editingMsg = msg
        editText = msg.content
    }

    BackHandler(enabled = showPicker) {
        showPicker = false
        reactingToMsg = null
    }
    BackHandler(enabled = editingMsg != null) {
        editingMsg = null
        editText = ""
    }
    BackHandler(enabled = isRecording) {
        cancelRecording()
    }
    BackHandler(enabled = showPhotoPicker) {
        showPhotoPicker = false
    }
    BackHandler(enabled = replyingTo != null) {
        replyingTo = null
    }
    BackHandler(enabled = selectedMsg != null) {
        selectedMsg = null
    }
    BackHandler(enabled = !showPicker && editingMsg == null && !isRecording && !showPhotoPicker && replyingTo == null && selectedMsg == null) {
        onBack()
    }

    if (showPhotoPicker) {
        PhotoPickerScreen(
            imageLoader = imageLoader,
            onImageSelected = { uri, mime ->
                showPhotoPicker = false
                scope.launch {
                    runCatching {
                        val cr = context.contentResolver
                        val ext = when {
                            mime.contains("png") -> "png"
                            mime.contains("gif") -> "gif"
                            mime.contains("webp") -> "webp"
                            else -> "jpg"
                        }
                        val bytes = cr.openInputStream(uri)?.readBytes()
                            ?: throw Exception("Cannot read image")
                        repo.sendFileAttachment(channelId, bytes, "image.$ext", mime)
                            .onFailure { uploadError = "Upload failed: ${it.message}" }
                    }.onFailure { uploadError = "Error: ${it.message}" }
                }
            },
            onDismiss = { showPhotoPicker = false }
        )
        return
    }

    val msgBeingEdited = editingMsg
    if (msgBeingEdited != null) {
        val editListState = rememberScalingLazyListState()
        ScreenScaffold(scrollState = editListState) {
            ScalingLazyColumn(state = editListState, modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        "Edit message",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                item {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                "Edit",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        shape = RoundedCornerShape(18.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 14.sp
                        ),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                val text = editText.trim()
                                if (text.isNotBlank()) {
                                    scope.launch {
                                        repo.editMessage(channelId, msgBeingEdited.id, text)
                                            .onFailure { sendError = "Failed: ${it.message}" }
                                        editingMsg = null
                                        editText = ""
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            enabled = editText.isNotBlank()
                        ) { Text("Save") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { editingMsg = null; editText = "" },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            colors = ButtonDefaults.outlinedButtonColors()
                        ) { Text("Cancel") }
                    }
                }
            }
        }
        return
    }

    if (showPicker) {
        val isReactMode = reactingToMsg != null
        EmojiStickerScreen(
            tab = if (isReactMode) 0 else tab,
            guildId = guildId,
            hasNitro = hasNitro,
            sendAnimatedAsGif = sendAnimatedAsGif,
            reactMode = isReactMode,
            onEmojiPicked = { emoji, asLink ->
                showPicker = false
                val target = reactingToMsg
                if (target != null) {
                    val reactionEmoji = ReactionEmoji(id = emoji.id, name = emoji.name, animated = emoji.animated)
                    scope.launch {
                        try {
                            repo.toggleReaction(channelId, target.id, reactionEmoji)
                        } catch (e: Exception) {
                            sendError = "Failed: ${e.message}"
                        }
                    }
                    reactingToMsg = null
                } else if (asLink) {
                    // No Nitro for this emoji here: post it as an image link instead.
                    scope.launch {
                        repo.sendMessage(channelId, emoji.linkUrl)
                            .onFailure { sendError = "Failed: ${it.message}" }
                    }
                } else {
                    val newText = if (inputText.isBlank()) emoji.insertText else "$inputText${emoji.insertText}"
                    inputText = newText
                    pendingText = newText
                }
            },
            onUnicodeEmojiPicked = { unicode ->
                showPicker = false
                val target = reactingToMsg
                if (target != null) {
                    val reactionEmoji = ReactionEmoji(id = null, name = unicode, animated = false)
                    scope.launch {
                        try {
                            repo.toggleReaction(channelId, target.id, reactionEmoji)
                        } catch (e: Exception) {
                            sendError = "Failed: ${e.message}"
                        }
                    }
                    reactingToMsg = null
                } else {
                    val newText = if (inputText.isBlank()) unicode else "$inputText$unicode"
                    inputText = newText
                    pendingText = newText
                }
            },
            onStickerPicked = { sticker, asLink ->
                showPicker = false
                reactingToMsg = null
                scope.launch {
                    try {
                        if (asLink) repo.sendMessage(channelId, sticker.imageUrl) else repo.sendSticker(channelId, sticker.id)
                    } catch (e: Exception) {
                        sendError = "Failed: ${e.message}"
                    }
                }
            }
        )
        return
    }

    val msgForOptions = selectedMsg
    if (msgForOptions != null) {
        MessageOptionsDialog(
            msg = msgForOptions,
            isOwn = msgForOptions.author.id == myId,
            onReply = {
                replyingTo = msgForOptions
                selectedMsg = null
            },
            onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("message", msgForOptions.content)
                )
                selectedMsg = null
            },
            onEdit = {
                selectedMsg = null
                openEdit(msgForOptions)
            },
            onDelete = {
                scope.launch {
                    repo.deleteMessage(channelId, msgForOptions.id)
                        .onFailure { sendError = "Failed: ${it.message}" }
                }
                selectedMsg = null
            },
            onReact = {
                reactingToMsg = msgForOptions
                selectedMsg = null
                showPicker = true
            },

            onOpenThread = if (msgForOptions.threadId != null) {
                {
                    val tid = msgForOptions.threadId
                    val tname = msgForOptions.content.take(30).ifBlank { "Thread" }
                    selectedMsg = null
                    onNavigateToThread?.invoke(tid, tname)
                }
            } else null,
            onCreateThread = if (msgForOptions.threadId == null && guildId != null && canSend) {
                {
                    selectedMsg = null
                    threadNameDraft = msgForOptions.content.lineSequence().firstOrNull().orEmpty()
                        .take(40).ifBlank { "Thread" }
                    threadSourceMsg = msgForOptions
                }
            } else null,
            onDismiss = { selectedMsg = null }
        )
        return
    }

    val sourceForThread = threadSourceMsg
    if (sourceForThread != null) {
        CreateThreadDialog(
            name = threadNameDraft,
            onNameChange = { threadNameDraft = it },
            creating = creatingThread,
            error = threadError,
            onCreate = {
                val name = threadNameDraft.trim().ifBlank { "Thread" }
                creatingThread = true
                threadError = ""
                scope.launch {
                    repo.rest.createThreadFromMessage(channelId, sourceForThread.id, name)
                        .onSuccess { thread ->
                            threadSourceMsg = null
                            onNavigateToThread?.invoke(thread.id, thread.name)
                        }
                        .onFailure { threadError = it.message ?: "Couldn't create thread" }
                    creatingThread = false
                }
            },
            onDismiss = { threadSourceMsg = null; threadError = "" }
        )
        return
    }

    // Index of the newest message in the list: title + optional draft banner come first.
    val lastMsgId = messages.lastOrNull()?.id
    val lastMessageIndex = 1 + (if (pendingText.isNotBlank()) 1 else 0) + messages.size - 1
    val isAtBottom by remember(lastMsgId, lastMessageIndex) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            lastMsgId == null || visible.any { it.key == lastMsgId } ||
                (visible.lastOrNull()?.index ?: 0) > lastMessageIndex
        }
    }

    // Newest message the user has actually seen; anything after it counts as "new".
    var seenUpToId by remember(channelId) { mutableStateOf<String?>(null) }
    LaunchedEffect(isAtBottom, lastMsgId) { if (isAtBottom) seenUpToId = lastMsgId }
    val unseenCount = remember(messages, seenUpToId) {
        val seen = seenUpToId?.toLongOrNull() ?: return@remember 0
        messages.count { (it.id.toLongOrNull() ?: 0L) > seen }
    }

    // Follow the conversation: if the user was caught up when a message arrived, keep them at the bottom.
    var previousLastId by remember(channelId) { mutableStateOf<String?>(null) }
    LaunchedEffect(lastMsgId) {
        val prev = previousLastId
        previousLastId = lastMsgId
        val wasCaughtUp = seenUpToId == prev || seenUpToId == lastMsgId
        if (prev != null && lastMsgId != null && prev != lastMsgId && wasCaughtUp && scrolledToUnread.value) {
            listState.animateScrollToItem(lastMessageIndex)
        }
    }

    fun jumpToLatest() {
        scope.launch {
            // Snap close first so long jumps don't animate through the whole history.
            listState.scrollToItem((lastMessageIndex - 2).coerceAtLeast(0))
            listState.animateScrollToItem(lastMessageIndex)
        }
    }

    // Mention autocomplete: look people up while the text ends in "@partial".
    val mentionQuery = MentionInput.activeQuery(inputText)
    LaunchedEffect(mentionQuery) {
        if (mentionQuery == null) {
            mentionSuggestions = emptyList()
        } else {
            delay(250)
            mentionSuggestions = repo.mentionCandidates(channelId, guildId, mentionQuery)
        }
    }

    /** Scrolls to [messageId] (e.g. the message a reply points at) and briefly highlights it. */
    fun jumpToMessage(messageId: String) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx < 0) {
            android.widget.Toast.makeText(context, "Original message isn't loaded", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val target = 1 + (if (pendingText.isNotBlank()) 1 else 0) + idx
        scope.launch {
            highlightedId = messageId
            listState.animateScrollToItem(target)
            delay(1_600)
            if (highlightedId == messageId) highlightedId = null
        }
    }

    fun sendTyped() {
        val text = MentionInput.toWire(inputText.trim(), pickedMentions)
        pickedMentions = emptyMap()
        if (text.isBlank() || slowRemaining > 0) return
        inputText = ""
        pendingText = ""
        scope.launch {
            slowRemaining = slowModeSecs
            val replyTarget = replyingTo
            if (replyTarget != null) {
                repo.sendReply(
                    channelId,
                    text,
                    replyTarget.id,
                    replyPing
                )
                    .onFailure {
                        sendError = "Failed: ${it.message}"
                    }
                replyingTo = null
                replyPing = true
            } else {
                repo.sendMessage(channelId, text)
                    .onFailure {
                        sendError = "Failed: ${it.message}"
                    }
            }
            while (true) {
                delay(1_000)
                slowRemaining =
                    repo.slowModeRemainingSeconds(channelId)
                if (slowRemaining <= 0) break
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenScaffold(scrollState = listState) {
            ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "channel_title") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "#$channelName",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (onOpenThreads != null && guildId != null) {
                            Text(
                                "Threads ›",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenThreads() }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                if (pendingText.isNotBlank()) {
                    item(key = "pending_text") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                pendingText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 2
                            )
                            Row {
                                Icon(
                                    painter = painterResource(id = R.drawable.play),
                                    contentDescription = null,
                                    tint = if (slowRemaining > 0)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            if (slowRemaining > 0) return@clickable
                                            val text = pendingText
                                            pendingText = ""
                                            inputText = ""
                                            scope.launch {
                                                slowRemaining = slowModeSecs
                                                val replyTarget = replyingTo
                                                if (replyTarget != null) {
                                                    repo.sendReply(
                                                        channelId,
                                                        text,
                                                        replyTarget.id,
                                                        replyPing
                                                    )
                                                        .onFailure {
                                                            sendError = "Failed: ${it.message}"
                                                        }
                                                    replyingTo = null
                                                    replyPing = true
                                                } else {
                                                    repo.sendMessage(channelId, text)
                                                        .onFailure {
                                                            sendError = "Failed: ${it.message}"
                                                        }
                                                }
                                                while (true) {
                                                    delay(1_000)
                                                    slowRemaining =
                                                        repo.slowModeRemainingSeconds(channelId)
                                                    if (slowRemaining <= 0) break
                                                }
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                        .size(16.dp),
                                )
                                Text(
                                    "X",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable {
                                            pendingText = ""
                                            inputText = ""
                                        }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }

                when {
                    loading -> item(key = "loading") { CircularProgressIndicator() }
                    messages.isEmpty() -> item(key = "empty") {
                        Text("No messages yet.", style = MaterialTheme.typography.bodySmall)
                    }

                    else -> items(messages.size, key = { messages[it].id }) { index ->
                        val msg = messages[index]
                        val prevMsg = if (index > 0) messages[index - 1] else null
                        fun String.toEpochMillis(): Long = runCatching {
                            java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli()
                        }.getOrElse { 0L }

                        val gapMs = if (prevMsg != null)
                            msg.timestamp.toEpochMillis() - prevMsg.timestamp.toEpochMillis()
                        else Long.MAX_VALUE

                        val isContinuation = prevMsg != null &&
                                prevMsg.author.id == msg.author.id &&
                                msg.type !in listOf(19, 23) &&
                                prevMsg.type !in listOf(19, 23) &&
                                gapMs < 10 * 60 * 1000L

                        MessageBubble(
                            msg = msg,
                            isContinuation = isContinuation,
                            imageLoader = imageLoader,
                            channelNames = channelNames,
                            roleNames = roleNames,
                            compactMode = compactMode,
                            spoilerRevealOnTap = spoilerRevealOnTap,
                            guildId = guildId,
                            roleColorCache = roleColorCache,
                            roleIconCache = roleIconCache,
                            guildMemberCache = guildMemberCache,
                            myId = myId,
                            onReact = { emoji ->
                                scope.launch { repo.toggleReaction(channelId, msg.id, emoji) }
                            },
                            onSwipeToReply = { msgToReply ->
                                replyingTo = msgToReply
                            },
                            onLongPress = {
                                selectedMsg = msg
                            },
                            onAvatarClick = { userId ->
                                onNavigateToProfile?.invoke(
                                    userId,
                                    msg.author.takeIf { it.id == userId })
                            },
                            onOpenThread = if (msg.threadId != null) { threadId ->
                                val tname = msg.content.take(30).ifBlank { "Thread" }
                                onNavigateToThread?.invoke(threadId, tname)
                            } else null,
                            onJumpToReply = { refId -> jumpToMessage(refId) },
                            highlighted = msg.id == highlightedId
                        )
                    }
                }

                if (sendError.isNotEmpty()) {
                    item(key = "send_error") {
                        Text(
                            sendError, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                val currentTypingUsers = typingUsers.filter { it != myId }
                if (currentTypingUsers.isNotEmpty()) {
                    item(key = "typing_indicator") {
                        val names = currentTypingUsers.mapNotNull { uid ->
                            repo.getDisplayName(uid) ?: "Someone"
                        }
                        val label = when {
                            names.size == 1 -> "${names[0]} is typing…"
                            names.size == 2 -> "${names[0]} and ${names[1]} are typing…"
                            else -> "Several people are typing…"
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                item(key = "text_input") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (replyingTo != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.reply),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    " ${replyingTo!!.author.displayName}: ${replyingTo!!.content}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "@",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (replyPing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                            alpha = 0.38f
                                        ),
                                        modifier = Modifier
                                            .clickable { replyPing = !replyPing }
                                            .padding(4.dp)
                                    )
                                    Text(
                                        "X",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier
                                            .clickable {
                                                replyingTo = null; replyPing = true
                                            }
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }

                        if (canSend) {
                            if (mentionQuery != null) {
                                MentionSuggestions(mentionSuggestions) { c ->
                                    val newText = MentionInput.complete(inputText, c)
                                    inputText = newText
                                    pendingText = newText
                                    pickedMentions = pickedMentions + ("@${c.displayName}" to "<@${c.id}>")
                                    mentionSuggestions = emptyList()
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedTextField(
                                    value = inputText,
                                    onValueChange = { newValue ->
                                        inputText = newValue
                                        pendingText = newValue
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 36.dp, max = 90.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    placeholder = {
                                        Text(
                                            "Message #$channelName",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 14.sp
                                    ),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        cursorColor = MaterialTheme.colorScheme.primary
                                    ),
                                    minLines = 1,
                                    maxLines = 4,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                                )
                                FilledIconButton(
                                    onClick = { sendTyped() },
                                    modifier = Modifier.size(36.dp),
                                    enabled = inputText.isNotBlank() && slowRemaining <= 0
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.send),
                                        contentDescription = "Send",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "You cannot send messages here",//add lock icon from /res/drawable
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                item(key = "action_buttons") {
                    if (canSend) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (!isRecording) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(
                                        space = 8.dp,
                                        alignment = Alignment.CenterHorizontally
                                    )
                                ) {
                                    FilledIconButton(
                                        onClick = {
                                            reactingToMsg = null
                                            showPicker = true
                                            tab = 0
                                        },
                                        modifier = Modifier
                                            .size(36.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.emoji),
                                            contentDescription = "Emoji"
                                        )
                                    }
                                    FilledIconButton(
                                        onClick = {
                                            reactingToMsg = null
                                            showPicker = true
                                            tab = 1
                                        },
                                        modifier = Modifier
                                            .size(36.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.sticker),
                                            contentDescription = "Stickers"
                                        )
                                    }
                                    if (abilities.canAttach) FilledIconButton(
                                        onClick = {
                                            val perm = if (android.os.Build.VERSION.SDK_INT >= 33)
                                                Manifest.permission.READ_MEDIA_IMAGES
                                            else
                                                Manifest.permission.READ_EXTERNAL_STORAGE
                                            val hasPerm =
                                                ContextCompat.checkSelfPermission(context, perm) ==
                                                        PackageManager.PERMISSION_GRANTED
                                            if (hasPerm) {
                                                showPhotoPicker = true
                                            } else {
                                                imagePermLauncher.launch(perm)
                                            }
                                        },
                                        modifier = Modifier
                                            .size(36.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.image),
                                            contentDescription = "Upload Photo"
                                        )
                                    }
                                    if (abilities.canSendVoice) FilledIconButton(
                                        onClick = { startRecording() },
                                        modifier = Modifier
                                            .size(36.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.mic),
                                            contentDescription = "Voice Message"
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.errorContainer,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val mins = recordingSecs / 60
                                    val secs = recordingSecs % 60
                                    Text(
                                        text = "%02d:%02d".format(mins, secs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        Button(
                                            onClick = { cancelRecording() },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(34.dp),
                                            colors = ButtonDefaults.outlinedButtonColors()
                                        ) { Text("Cancel", fontSize = 12.sp) }
                                        Spacer(Modifier.width(6.dp))
                                        Button(
                                            onClick = { stopAndSendRecording() },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(34.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors()
                                        ) { Text("Send", fontSize = 11.sp) }
                                    }
                                }
                            }
                            if (uploadError.isNotEmpty()) {
                                Text(
                                    uploadError,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.clickable { uploadError = "" }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isAtBottom && !loading && lastMsgId != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 18.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { jumpToLatest() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = when {
                            unseenCount > 99 -> "99+ new"
                            unseenCount > 0 -> "$unseenCount new"
                            else -> "Latest"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

private val BareUrlRegex = Regex("^https?://\\S+$")

@Composable
private fun CreateThreadDialog(
    name: String,
    onNameChange: (String) -> Unit,
    creating: Boolean,
    error: String,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { Text("New thread", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { onNameChange(it.take(100)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp, max = 72.dp),
                    shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("Thread name", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = true
                )
            }
            if (error.isNotEmpty()) {
                item {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Button(
                    onClick = { if (!creating) onCreate() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) { Text(if (creating) "Creating…" else "Create") }
            }
            item {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun SpoilerText(text: String, revealOnTap: Boolean) {
    var revealed by remember { mutableStateOf(false) }
    val modifier = if (revealOnTap) Modifier.clickable { revealed = true } else Modifier
    Box(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (revealed) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0f)
        )
        if (!revealed) {
            Text(
                text = text.map { '█' }.joinToString(""), //epstineify LOL
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun parseInsertTextToReactionEmoji(insertText: String): ReactionEmoji? {
    val animatedMatch = Regex("""<a:(\w+):(\d+)>""").find(insertText)
    if (animatedMatch != null) {
        return ReactionEmoji(
            id = animatedMatch.groupValues[2],
            name = animatedMatch.groupValues[1],
            animated = true
        )
    }
    val customMatch = Regex("""<:(\w+):(\d+)>""").find(insertText)
    if (customMatch != null) {
        return ReactionEmoji(
            id = customMatch.groupValues[2],
            name = customMatch.groupValues[1],
            animated = false
        )
    }
    if (insertText.isNotBlank()) {
        return ReactionEmoji(id = null, name = insertText, animated = false)
    }
    return null
}

@Composable
private fun MessageOptionsDialog(
    msg: DiscordMessage,
    isOwn: Boolean,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: () -> Unit,
    onOpenThread: (() -> Unit)? = null,
    onCreateThread: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    "Message by ${msg.author.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Button(
                    onClick = onReply,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.reply),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Reply")
                }
            }
            if (onOpenThread != null) {
                item {
                    Button(
                        onClick = onOpenThread,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.reply),
                            contentDescription = null,
                            tint = Color(0xFF5865F2),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Open Thread")
                    }
                }
            }
            if (onCreateThread != null) {
                item {
                    Button(
                        onClick = onCreateThread,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.reply),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Create Thread")
                    }
                }
            }
            item {
                Button(
                    onClick = onReact,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.emoji),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("React")
                }
            }
            item {
                Button(
                    onClick = onCopy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.copy),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Text")
                }
            }
            if (isOwn) {
                item {
                    Button(
                        onClick = onEdit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.edit),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Edit")
                    }
                }
                item {
                    Button(
                        onClick = onDelete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.delete),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
            item {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.outlinedButtonColors()
                ) { Text("Cancel") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageBubble(
    msg: DiscordMessage,
    isContinuation: Boolean,
    imageLoader: ImageLoader,
    channelNames: Map<String, String> = emptyMap(),
    roleNames: Map<String, String> = emptyMap(),
    compactMode: Boolean = false,
    spoilerRevealOnTap: Boolean = true,
    guildId: String? = null,
    roleColorCache: SnapshotStateMap<String, GuildRole?> = mutableStateMapOf(),
    roleIconCache: SnapshotStateMap<String, GuildRole?> = mutableStateMapOf(),
    guildMemberCache: SnapshotStateMap<String, GuildMember?> = mutableStateMapOf(),
    myId: String = "",
    onReact: (ReactionEmoji) -> Unit,
    onSwipeToReply: ((DiscordMessage) -> Unit)? = null,
    onLongPress: () -> Unit,
    onAvatarClick: (userId: String) -> Unit = {},
    onOpenThread: ((threadId: String) -> Unit)? = null,
    onJumpToReply: ((messageId: String) -> Unit)? = null,
    highlighted: Boolean = false
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository

    val userNames = remember(msg.mentionedUsers) {
        msg.mentionedUsers.associate { it.id to it.displayName }
    }

    val memberRoles = if (guildId != null && repo != null) repo.getMyRoles(guildId) else emptyList()
    val isMentioned = remember(msg, myId, memberRoles) {
        myId.isNotBlank() && msg.pingFor(myId, memberRoles)
    }

    // Role data is preloaded in ChatScreen for all visible authors
    val topRole = if (guildId != null) roleColorCache[msg.author.id] else null
    val topRoleIcon = if (guildId != null) roleIconCache[msg.author.id] ?: topRole else null
    val rawRoleColor = topRole?.color
    val authorNameColor = if (rawRoleColor != null && rawRoleColor != 0)
        Color(0xFF000000.toInt() or rawRoleColor)
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    val density = LocalDensity.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val thresholdPx = remember(density) { with(density) { -48.dp.toPx() } }
    val maxDragPx = remember(density) { with(density) { -80.dp.toPx() } }

    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var hasVibrated by remember { mutableStateOf(false) }
    val animOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val currentOffset = if (isDragging) dragOffset else animOffset.value

    val swipeModifier = if (onSwipeToReply != null) {
        Modifier.pointerInput(msg.id) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragOffsetPx = 0f
                    var isSwipeLeft = false
                    val pointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent()
                        val dragChange = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!dragChange.pressed) break

                        val dx = dragChange.position.x - dragChange.previousPosition.x
                        val totalDx = dragChange.position.x - down.position.x

                        if (!isSwipeLeft) {
                            if (totalDx < -viewConfiguration.touchSlop) {
                                isSwipeLeft = true
                                isDragging = true
                                hasVibrated = false
                            } else if (totalDx > viewConfiguration.touchSlop) {
                                break
                            }
                        }

                        if (isSwipeLeft) {
                            dragChange.consume()
                            val newOffset = (dragOffsetPx + dx).coerceIn(maxDragPx, 0f)
                            if (newOffset != dragOffsetPx) {
                                if (dragOffsetPx > thresholdPx && newOffset <= thresholdPx && !hasVibrated) {
                                    if (!view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    hasVibrated = true
                                }
                                dragOffsetPx = newOffset
                                dragOffset = dragOffsetPx
                            }
                        }
                    }

                    if (isSwipeLeft) {
                        if (dragOffsetPx <= thresholdPx) {
                            onSwipeToReply(msg)
                        }
                        val startVal = dragOffsetPx
                        isDragging = false
                        scope.launch {
                            animOffset.snapTo(startVal)
                            animOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                }
            }
        }
    } else Modifier

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .then(swipeModifier)
    ) {
        if (onSwipeToReply != null && currentOffset < -2f) {
            val progress = (abs(currentOffset) / abs(thresholdPx)).coerceIn(0f, 1f)
            val isPastThreshold = currentOffset <= thresholdPx
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .graphicsLayer {
                            alpha = progress
                            scaleX = 0.6f + (progress * 0.4f)
                            scaleY = 0.6f + (progress * 0.4f)
                        }
                        .background(
                            if (isPastThreshold) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.reply),
                        contentDescription = "Reply",
                        tint = if (isPastThreshold) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(currentOffset.roundToInt(), 0) }
                .padding(top = if (isContinuation) 0.dp else 4.dp, bottom = 2.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onLongPress() }
                ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!compactMode && !isContinuation) {
                DiscordAvatarWithDecoration(
                    user = msg.author,
                    imageLoader = imageLoader,
                    size = 22.dp,
                    guildId = guildId,
                    guildMemberCache = guildMemberCache,
                    onClick = { onAvatarClick(msg.author.id) }
                )
                Spacer(Modifier.width(4.dp))
            } else if (!compactMode) {
                Spacer(Modifier.width(26.dp))
            }

            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier
                    .widthIn(max = 155.dp)
                    .then(
                        if (isMentioned) {
                            Modifier
                                .background(
                                    Color(0xFFFEE75C).copy(alpha = 0.25f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (!isContinuation) {
                    val timeLabel = remember(msg.timestamp) {
                        runCatching {
                            val instant = java.time.OffsetDateTime.parse(msg.timestamp).toInstant()
                            val zoned = instant.atZone(java.time.ZoneId.systemDefault())
                            val now = java.time.ZonedDateTime.now()
                            val use24 = android.text.format.DateFormat.is24HourFormat(context)
                            val timeFmt = if (use24)
                                java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                            else
                                java.time.format.DateTimeFormatter.ofPattern("h:mma")
                            val timeStr = zoned.format(timeFmt).lowercase()

                            val todayDate = now.toLocalDate()
                            val yesterdayDate = todayDate.minusDays(1)
                            when (zoned.toLocalDate()) {
                                todayDate -> timeStr
                                yesterdayDate -> "Yesterday $timeStr"
                                else -> "${zoned.monthValue}/${zoned.dayOfMonth} $timeStr"
                            }
                        }.getOrElse { "" }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg.author.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = authorNameColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        val clan = msg.author.clan
                        if (clan != null) {
                            Row(
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainer,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 3.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                val badgeUrl = clan.badgeUrl(16)
                                if (badgeUrl != null) {
                                    SubcomposeAsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(badgeUrl)
                                            .crossfade(true)
                                            .build(),
                                        imageLoader = imageLoader,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                                Text(
                                    text = clan.tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // Role icon: show only once loaded, unicode emoji takes priority over custom image
                        if (topRoleIcon != null) {
                            when {
                                !topRoleIcon.unicodeEmoji.isNullOrEmpty() -> {
                                    Text(
                                        text = topRoleIcon.unicodeEmoji,
                                        fontSize = 10.sp
                                    )
                                }

                                !topRoleIcon.iconHash.isNullOrEmpty() -> {
                                    SubcomposeAsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(topRoleIcon.iconUrl())
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        imageLoader = imageLoader,
                                        modifier = Modifier.size(12.dp),
                                        loading = { /* Hide while loading */ },
                                        error = { /* Hide on error */ }
                                    )
                                }
                            }
                        }
                        if (timeLabel.isNotEmpty()) {
                            Text(
                                text = timeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 9.sp
                            )
                        }
                    }
                }

                val ref = msg.referencedMessage
                if (msg.type == 19 && ref != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .then(if (onJumpToReply != null) Modifier.clickable { onJumpToReply(ref.id) } else Modifier)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.reply),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            " ${ref.author.displayName}: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            ref.content.take(40).ifBlank { "Attachment" },//icon?
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }

                if (msg.forwardedContent != null || msg.forwardedAuthor != null || msg.forwardedAttachments.isNotEmpty() || msg.forwardedEmbeds.isNotEmpty() || msg.forwardedStickers.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFF2B2D31),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(6.dp)
                    ) {
                        Text(
                            "Forwarded",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        val fwdAuthorName = msg.forwardedAuthor?.displayName
                            ?: msg.referencedMessage?.author?.displayName ?: "Unknown"
                        Text(
                            fwdAuthorName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                        val fwdText = msg.forwardedContent?.takeIf { it.isNotBlank() }
                            ?: msg.referencedMessage?.content?.takeIf { it.isNotBlank() }
                        if (!fwdText.isNullOrBlank()) {
                            MessageContent(
                                content = fwdText,
                                imageLoader = imageLoader,
                                context = context,
                                userNames = userNames,
                                channelNames = channelNames,
                                roleNames = roleNames,
                                spoilerRevealOnTap = spoilerRevealOnTap
                            )
                        }
                        val fwdAtts = msg.forwardedAttachments.ifEmpty {
                            msg.referencedMessage?.attachments ?: emptyList()
                        }
                        fwdAtts.filter { it.isImage }.forEach { att ->
                            MediaImage(att.proxyUrl, att.filename, imageLoader)
                        }
                        val fwdEmbeds = msg.forwardedEmbeds.ifEmpty {
                            msg.referencedMessage?.embeds ?: emptyList()
                        }
                        fwdEmbeds.forEach { embed ->
                            EmbedCard(embed, imageLoader)
                        }
                        val fwdStickers = msg.forwardedStickers.ifEmpty {
                            msg.referencedMessage?.stickers ?: emptyList()
                        }
                        fwdStickers.filter { it.isDisplayable }.forEach { s ->
                            MediaImage(s.imageUrl, s.name, imageLoader, size = 120.dp)
                        }
                        if (fwdText.isNullOrBlank() && fwdAtts.isEmpty() && fwdEmbeds.isEmpty() && fwdStickers.isEmpty()) {
                            Text(
                                "(no preview available)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val trimmedContent = msg.content.trim()
                    val isJustLink = msg.embeds.isNotEmpty() && (
                            android.util.Patterns.WEB_URL.matcher(trimmedContent).matches() ||
                                    BareUrlRegex.matches(trimmedContent)
                            )

                    if (msg.content.isNotBlank() && !isJustLink) {
                        MessageContent(
                            content = msg.content,
                            imageLoader = imageLoader,
                            context = context,
                            userNames = userNames,
                            channelNames = channelNames,
                            roleNames = roleNames,
                            spoilerRevealOnTap = spoilerRevealOnTap
                        )
                    }
                }

                msg.attachments.filter { it.isImage }.forEach { att ->
                    MediaImage(att.proxyUrl, att.filename, imageLoader)
                }
                msg.attachments.filter { it.isVideo }.forEach { att ->
                    VideoAttachment(att, imageLoader)
                }
                msg.attachments.filter { it.isAudio }.forEach { att ->
                    AudioAttachment(att)
                }
                msg.stickers.filter { it.isDisplayable }.forEach { s ->
                    MediaImage(s.imageUrl, s.name, imageLoader, size = 80.dp)
                }
                msg.embeds
                    .filter { embed ->
                        !(embed.type == "link" && //lets clean this up a litle more
                                embed.url?.contains("cdn.discordapp.com/emojis/") == true)
                    }
                    .forEach { embed ->
                        EmbedCard(embed, imageLoader)
                    }

                if (msg.reactions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        msg.reactions.forEach { reaction ->
                            ReactionChip(reaction = reaction, imageLoader = imageLoader, onClick = {
                                onReact(reaction.emoji)
                            })
                        }
                    }
                }

                // Thread count chip
                if (msg.threadId != null) {
                    val count = msg.threadMessageCount
                    Row(
                        modifier = Modifier
                            .background(
                                Color(0xFF5865F2).copy(alpha = 0.15f),
                                androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                            )
                            .clickable { onOpenThread?.invoke(msg.threadId) }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.reply),
                            contentDescription = "Thread",
                            tint = Color(0xFF5865F2),
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = if (count > 0) " $count replies" else "Thread",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF5865F2),
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}

private fun isOnlyEmojis(content: String): Boolean {
    if (content.isBlank()) return false
    var cleaned = content.replace(Regex("<a?:\\w+:\\d+>"), "")
    cleaned = cleaned.replace(
        Regex("[\\x{1F300}-\\x{1F5FF}\\x{1F600}-\\x{1F64F}\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}\\x{1F780}-\\x{1F7FF}\\x{1F800}-\\x{1F8FF}\\x{1F900}-\\x{1F9FF}\\x{1FA70}-\\x{1FAFF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{FE00}-\\x{FE0F}\\x{1F1E6}-\\x{1F1FF}\\x{200D}\\x{FE0F}]"),
        ""
    )
    cleaned = cleaned.replace(Regex("\\s+"), "")
    val hasEmoji = content.contains(Regex("<a?:\\w+:\\d+>")) ||
            content.contains(Regex("[\\x{1F300}-\\x{1F5FF}\\x{1F600}-\\x{1F64F}\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}\\x{1F780}-\\x{1F7FF}\\x{1F800}-\\x{1F8FF}\\x{1F900}-\\x{1F9FF}\\x{1FA70}-\\x{1FAFF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}]"))
    return cleaned.isEmpty() && hasEmoji
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageContent(
    content: String,
    imageLoader: ImageLoader,
    context: Context,
    userNames: Map<String, String> = emptyMap(),
    roleNames: Map<String, String> = emptyMap(),
    channelNames: Map<String, String> = emptyMap(),
    spoilerRevealOnTap: Boolean = true
) {
    val lines = remember(content) { content.split("\n") }
    val isOnlyEmoji = remember(content) { isOnlyEmojis(content) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { line ->
            val trimmed = line.trimStart()
            val headingLevel = when {
                trimmed.startsWith("### ") -> 3
                trimmed.startsWith("## ") -> 2
                trimmed.startsWith("# ") -> 1
                else -> 0
            }
            val isSubtext = headingLevel == 0 && trimmed.startsWith("-#")
            val cleanLine = when {
                headingLevel > 0 -> trimmed.removePrefix("### ").removePrefix("## ")
                    .removePrefix("# ").trimStart()

                isSubtext -> trimmed.removePrefix("-#").trimStart()
                else -> line
            }
            val parts = remember(cleanLine, userNames, roleNames, channelNames) {
                ContentParser.parse(cleanLine, userNames, roleNames, channelNames)
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                parts.forEach { part ->
                    when (part) {
                        is ContentParser.Part.PlainText -> {
                            val spans = ContentParser.parseMarkdown(
                                part.text,
                                subtext = isSubtext,
                                headingLevel = headingLevel
                            )
                            spans.forEach { span ->
                                if (span.spoiler) {
                                    SpoilerText(span.text, spoilerRevealOnTap)
                                } else {
                                    val fontSize = when (span.headingLevel) {
                                        1 -> 18.sp
                                        2 -> 15.sp
                                        3 -> 13.sp
                                        else -> if (span.subtext) 10.sp else androidx.compose.ui.unit.TextUnit.Unspecified
                                    }
                                    val fontWeight =
                                        if (span.headingLevel > 0 || span.bold) androidx.compose.ui.text.font.FontWeight.Bold else null
                                    val fontStyle =
                                        if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic else null
                                    val textColor = when {
                                        span.headingLevel > 0 -> MaterialTheme.colorScheme.onSurface
                                        span.subtext -> MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.7f
                                        )

                                        else -> androidx.compose.ui.graphics.Color.Unspecified
                                    }

                                    val annotated = buildAnnotatedString {
                                        val style = SpanStyle(
                                            fontWeight = fontWeight,
                                            fontStyle = fontStyle,
                                            textDecoration = markdownDecoration(span),
                                            background = if (span.code)
                                                MaterialTheme.colorScheme.surfaceContainer
                                            else androidx.compose.ui.graphics.Color.Unspecified,
                                            fontFamily = if (span.code)
                                                androidx.compose.ui.text.font.FontFamily.Monospace
                                            else null,
                                            fontSize = fontSize,
                                            color = textColor
                                        )
                                        withStyle(style) { append(span.text) }
                                    }
                                    val textStyle = when {
                                        span.headingLevel == 1 -> MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 18.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )

                                        span.headingLevel == 2 -> MaterialTheme.typography.titleSmall.copy(
                                            fontSize = 15.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )

                                        span.headingLevel == 3 -> MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 13.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )

                                        span.subtext -> MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.7f
                                            )
                                        )

                                        isOnlyEmoji -> MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 28.sp,
                                            lineHeight = 32.sp
                                        )

                                        else -> MaterialTheme.typography.bodySmall
                                    }
                                    Text(text = annotated, style = textStyle)
                                }
                            }
                        }

                        is ContentParser.Part.CustomEmoji -> {
                            val emojiSize =
                                if (isOnlyEmoji) 36.dp else if (isSubtext) 14.dp else 18.dp
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(part.url).crossfade(true).build(),
                                imageLoader = imageLoader,
                                contentDescription = part.name,
                                modifier = Modifier.size(emojiSize)
                            )
                        }

                        is ContentParser.Part.UserMention -> {
                            val annotated = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        background = MaterialTheme.colorScheme.primaryContainer,
                                        fontSize = if (isSubtext) 10.sp else androidx.compose.ui.unit.TextUnit.Unspecified
                                    )
                                ) { append("@${part.displayName}") }
                            }
                            Text(text = annotated, style = MaterialTheme.typography.bodySmall)
                        }

                        is ContentParser.Part.RoleMention -> {
                            val annotated = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontSize = if (isSubtext) 10.sp else androidx.compose.ui.unit.TextUnit.Unspecified
                                    )
                                ) {
                                    append("@${part.roleName}")
                                }
                            }
                            Text(text = annotated, style = MaterialTheme.typography.bodySmall)
                        }

                        is ContentParser.Part.ChannelMention -> {
                            val annotated = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontSize = if (isSubtext) 10.sp else androidx.compose.ui.unit.TextUnit.Unspecified
                                    )
                                ) {
                                    append("#${part.channelName}")
                                }
                            }
                            Text(text = annotated, style = MaterialTheme.typography.bodySmall)
                        }

                        is ContentParser.Part.Link -> {
                            val annotated = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                        fontSize = if (isSubtext) 10.sp else androidx.compose.ui.unit.TextUnit.Unspecified
                                    )
                                ) { append(part.url) }
                            }
                            Text(
                                text = annotated,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable {
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, part.url.toUri())
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionChip(reaction: Reaction, imageLoader: ImageLoader, onClick: () -> Unit) {
    val bgColor = when {
        reaction.me || reaction.meBurst -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }

    Box(
        modifier = Modifier
            .height(22.dp)
            .background(color = bgColor, shape = RoundedCornerShape(11.dp))
            .then(
                if (reaction.meBurst) Modifier.border(
                    1.dp,
                    Color(0xFFFEE75C),
                    RoundedCornerShape(11.dp)
                ) else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val imgUrl = reaction.emoji.imageUrl
            if (imgUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imgUrl).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = reaction.emoji.name,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = reaction.emoji.name,
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
                    lineHeight = 14.sp,
                    modifier = Modifier.wrapContentSize()
                )
            }
            Text(
                text = reaction.count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun MediaImage(
    url: String,
    contentDesc: String,
    imageLoader: ImageLoader,
    size: Dp = 120.dp
) {
    var viewing by remember { mutableStateOf(false) }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url).crossfade(true).build(),
        imageLoader = imageLoader,
        contentDescription = contentDesc,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .padding(top = 2.dp)
            .size(size)
            .clickable { viewing = true }
    )
    if (viewing) ImageViewer(url, contentDesc, imageLoader) { viewing = false }
}

/** Strikethrough and/or underline for a markdown span (null when neither). */
internal fun markdownDecoration(span: ContentParser.MarkdownSpan): TextDecoration? {
    val parts = listOfNotNull(
        TextDecoration.LineThrough.takeIf { span.strikethrough },
        TextDecoration.Underline.takeIf { span.underline },
    )
    return if (parts.isEmpty()) null else TextDecoration.combine(parts)
}

/** Inline markdown (bold, italic, underline, strikethrough, code) for one-line labels such as embed titles and field names. */
private fun inlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    ContentParser.parseMarkdown(text).forEach { span ->
        withStyle(
            SpanStyle(
                fontWeight = if (span.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                fontStyle = if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                textDecoration = markdownDecoration(span),
                fontFamily = if (span.code) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            )
        ) { append(span.text) }
    }
}

@Composable
private fun EmbedCard(embed: Embed, imageLoader: ImageLoader) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp)
    )
    {
        if (!embed.authorName.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                if (!embed.authorIconUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(embed.authorIconUrl).crossfade(true).build(),
                        imageLoader = imageLoader,
                        contentDescription = "author icon",
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    embed.authorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        embed.title?.let { titleText ->
            if (!embed.url.isNullOrBlank()) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Text(
                    inlineMarkdown(titleText),
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.clickable { runCatching { uriHandler.openUri(embed.url) } }
                )
            } else {
                Text(
                    inlineMarkdown(titleText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        embed.description?.let { descText ->
            Spacer(Modifier.height(2.dp))
            // Embed descriptions support the same markdown, emoji and mentions as messages.
            MessageContent(content = descText, imageLoader = imageLoader, context = context)
        }

        if (embed.fields.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            embed.fields.forEach { field ->
                Column(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        inlineMarkdown(field.name),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MessageContent(content = field.value, imageLoader = imageLoader, context = context)
                }
            }
        }

        val imgUrl = embed.displayImageUrl
        var viewingEmbedImage by remember { mutableStateOf(false) }
        if (imgUrl != null) {
            Spacer(Modifier.height(4.dp))
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imgUrl).crossfade(true).build(),
                imageLoader = imageLoader,
                contentDescription = embed.title ?: "embed image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { viewingEmbedImage = true }
            )
            if (viewingEmbedImage) {
                ImageViewer(imgUrl, embed.title ?: "embed image", imageLoader) { viewingEmbedImage = false }
            }
        }

        if (!embed.footerText.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!embed.footerIconUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(embed.footerIconUrl).crossfade(true).build(),
                        imageLoader = imageLoader,
                        contentDescription = "footer icon",
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    embed.footerText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VideoAttachment(att: Attachment, imageLoader: ImageLoader) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .widthIn(max = 140.dp)
            .heightIn(max = 100.dp)
            .clickable {
                runCatching {
                    val urlUri = att.url.toUri()
                    val intent = Intent(Intent.ACTION_VIEW, urlUri)
                        .setDataAndType(urlUri, att.contentType ?: "video/*")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(att.proxyUrl)
                .crossfade(true)
                .build(),
            imageLoader = imageLoader,
            contentDescription = att.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 140.dp)
                .heightIn(max = 100.dp)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.play),
                contentDescription = "play",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
    Text(
        text = att.filename,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

@Composable
private fun AudioAttachment(att: Attachment) {
    val scope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(att.url) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                val dur = mp.duration
                if (dur > 0) progress = mp.currentPosition.toFloat() / dur.toFloat()
            }
            delay(300)
        }
    }

    Column(modifier = Modifier.padding(top = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(id = if (isPlaying) R.drawable.pause else R.drawable.play),
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(24.dp)
                    .clickable {
                        scope.launch {
                            if (isPlaying) {
                                mediaPlayer?.pause()
                                isPlaying = false
                            } else {
                                val mp = mediaPlayer ?: MediaPlayer().also {
                                    it.setDataSource(att.url)
                                    it.prepare()
                                    it.setOnCompletionListener { _ ->
                                        isPlaying = false
                                        progress = 0f
                                    }
                                    mediaPlayer = it
                                }
                                mp.start()
                                isPlaying = true
                            }
                        }
                    }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = att.filename,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }
        }
    }
}


@Composable
private fun DiscordAvatarWithDecoration(
    user: com.dihcord.app.api.DiscordUser,
    imageLoader: ImageLoader,
    size: Dp,
    guildId: String? = null,
    guildMemberCache: Map<String, GuildMember?> = emptyMap(),
    onClick: () -> Unit = {}
) {
    val memberKey = if (guildId != null) "$guildId:${user.id}" else ""
    val member = if (guildId != null) guildMemberCache[memberKey] else null
    val avatarUrl = if (guildId != null && member?.avatarHash != null) member.avatarUrl(
        guildId,
        32
    ) else user.avatarUrl(32)
    val decorUrl = if (Perf.lite) null else member?.avatarDecorationUrl() ?: user.avatarDecorationUrl()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .pointerInput(onClick) {
                detectTapGestures(
                    onTap = { onClick() }
                )
            }
    ) {
        DiscordAvatar(
            url = avatarUrl,
            displayName = user.displayName,
            imageLoader = imageLoader,
            size = size
        )
        if (decorUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(decorUrl).crossfade(false).build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 1.4f)
            )
        }
    }
}

@Composable
private fun DiscordAvatar(url: String?, displayName: String, imageLoader: ImageLoader, size: Dp) {
    val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val blurple = androidx.compose.ui.graphics.Color(0xFF5865F2)

    if (url == null) {
        Box(
            modifier = Modifier
                .size(size)
                .background(color = blurple, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = (size.value * 0.45f).sp
            )
        }
    } else {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(true)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            error = {
                Box(
                    modifier = Modifier
                        .size(size)
                        .background(color = blurple, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = (size.value * 0.45f).sp
                    )
                }
            }
        )
    }
}
