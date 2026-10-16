package com.dihcord.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import coil.ImageLoader
import coil.imageLoader
import coil.decode.ImageDecoderDecoder
import com.dihcord.app.R
import com.dihcord.app.api.DiscordMessage
import com.dihcord.app.api.DiscordUser
import com.dihcord.app.api.MentionCandidate
import com.dihcord.app.api.MentionInput
import com.dihcord.app.api.Reaction
import com.dihcord.app.api.GuildRole
import com.dihcord.app.discordApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dihcord.app.SetupPreferences
import com.dihcord.app.api.ReactionEmoji
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Full-screen thread viewer.
 * Shows messages in the thread and lets the user reply to the thread.
 *
 * Called from ChatScreen when the user taps "Open Thread" in MessageOptionsDialog
 * or taps the thread count chip on a message.
 *
 * Navigation: push "thread/{threadId}/{threadName}" onto the nav stack.
 */
@Composable
fun ThreadScreen(
    threadId: String,
    threadName: String,
    guildId: String? = null,
    onNavigateToProfile: ((userId: String, user: DiscordUser?) -> Unit)? = null,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val repo = context.discordApp.repository ?: return
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    val imageLoader = context.imageLoader

    // distinctBy: a duplicated id would crash the keyed list ("Key was already used").
    val threadMessages by remember(threadId) {
        repo.messages.map { it[threadId].orEmpty() }.distinctUntilChanged().map { l -> l.distinctBy { it.id } }
    }.collectAsState(initial = repo.messages.value[threadId].orEmpty().distinctBy { it.id })
    val currentUser by repo.currentUser.collectAsState()
    val myId = currentUser?.id ?: ""

    var loading by remember { mutableStateOf(true) }
    var sendError by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    var pickedMentions by remember(threadId) { mutableStateOf(mapOf<String, String>()) }
    val history = rememberHistoryPaging(
        repo = repo,
        channelId = threadId,
        listState = listState,
        headerCount = { 1 },
        messages = { threadMessages },
        ready = { !loading },
    )
    var mentionSuggestions by remember(threadId) { mutableStateOf<List<MentionCandidate>>(emptyList()) }
    // Autocomplete: people for "@partial", channels for "#partial", emoji for ":partial" at the end of the text.
    val mentionQuery = MentionInput.active(inputText)
    LaunchedEffect(mentionQuery) {
        val active = mentionQuery
        mentionSuggestions = when {
            active == null -> emptyList()
            active.trigger == '#' -> if (guildId == null) emptyList() else {
                delay(150)
                repo.channelCandidates(guildId, active.query)
            }
            active.trigger == ':' -> {
                delay(120)
                emojiCandidates(
                    repo, guildId, active.query,
                    hasNitro = repo.currentUser.value?.hasNitro ?: false,
                    linkFallback = SetupPreferences.getSendAnimatedAsGif(context)
                )
            }
            else -> {
                delay(250)
                repo.mentionCandidates(threadId, guildId, active.query)
            }
        }
    }
    var pendingText by remember { mutableStateOf("") }
    var selectedMsg by remember { mutableStateOf<DiscordMessage?>(null) }
    var replyingTo by remember { mutableStateOf<DiscordMessage?>(null) }
    var imageDraft by remember { mutableStateOf<ImageDraft?>(null) }
    var replyPing by remember { mutableStateOf(true) }
    var reactingToMsg by remember { mutableStateOf<DiscordMessage?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    val roleColorCache = remember { mutableStateMapOf<String, GuildRole?>() }
    // Same map instance until a name actually changes: it is a remember key for every bubble's parsed markdown.
    val publishedChannelNames by repo.channelNames.collectAsState()
    var channelNames by remember { mutableStateOf(repo.getChannelNames()) }
    LaunchedEffect(publishedChannelNames, threadMessages.size, loading) {
        val fresh = repo.getChannelNames()
        if (fresh != channelNames) channelNames = fresh
    }

    var uploadError by remember { mutableStateOf("") }
    var showPhotoPicker by remember { mutableStateOf(false) }
    var reactorsFor by remember { mutableStateOf<Pair<String, Reaction>?>(null) }
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
    // Bumped when the mic permission is granted, so recording starts right away (startRecording is declared below).
    var micGrantedTick by remember { mutableStateOf(0) }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) micGrantedTick++ else uploadError = "Microphone permission denied"
    }

    fun startRecording() {
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasAudio) { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        if (recorder != null) return
        val f = File(context.cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        var created: MediaRecorder? = null
        try {
            val mr = if (android.os.Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            created = mr
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mr.setOutputFile(f.absolutePath)
            mr.prepare()
            mr.start()
            voiceFile = f
            recorder = mr
            isRecording = true
            recordingSecs = 0
        } catch (e: Exception) {
            runCatching { created?.release() }
            f.delete()
            voiceFile = null
            uploadError = "Mic error: ${e.message}"
            isRecording = false
        }
    }

    fun stopAndSendRecording() {
        val mr = recorder ?: return
        val f = voiceFile
        val dur = recordingSecs.toDouble()
        recorder = null
        voiceFile = null
        isRecording = false
        // stop() throws when nothing usable was recorded (e.g. a very short tap); the file is then corrupt.
        val stopped = try { mr.stop(); true } catch (_: Exception) { false }
        runCatching { mr.release() }
        if (!stopped || f == null) {
            f?.delete()
            uploadError = "Recording too short"
            return
        }
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { f.readBytes().also { f.delete() } }
                repo.sendVoiceMessage(threadId, bytes, dur)
                    .onFailure { uploadError = "Voice send failed: ${it.message}" }
            }.onFailure { uploadError = "Error: ${it.message}" }
        }
    }

    fun cancelRecording() {
        val mr = recorder ?: return
        recorder = null
        try { mr.stop() } catch (_: Exception) {}
        runCatching { mr.release() }
        isRecording = false
        voiceFile?.delete()
        voiceFile = null
    }

    LaunchedEffect(micGrantedTick) {
        if (micGrantedTick > 0) startRecording()
    }

    // Never keep the mic recording (or leak the temp file) once the screen is backgrounded or left.
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
                if (recordingSecs >= 120) { stopAndSendRecording(); break }
            }
        }
    }

    LaunchedEffect(threadId) {
        com.dihcord.app.Notifier.clear(context, threadId)
        scope.launch {
            repo.loadMessages(threadId)
            loading = false
        }
    }

    LaunchedEffect(threadMessages, guildId, loading) {
        if (!loading && guildId != null) {
            threadMessages.map { it.author.id }.distinct()
                .filterNot { roleColorCache.containsKey(it) }
                .forEach { userId ->
                    roleColorCache[userId] = repo.getTopRoleForUser(guildId, userId)
                }
        }
    }

    BackHandler(enabled = isRecording) {
        cancelRecording()
    }
    BackHandler(enabled = showPhotoPicker) {
        showPhotoPicker = false
    }
    BackHandler(enabled = showPicker) {
        showPicker = false
        reactingToMsg = null
    }
    BackHandler(enabled = reactorsFor != null) {
        reactorsFor = null
    }
    BackHandler(enabled = replyingTo != null) {
        replyingTo = null
    }
    BackHandler(enabled = selectedMsg != null) {
        selectedMsg = null
    }
    BackHandler(
        enabled = !isRecording && !showPhotoPicker && !showPicker && reactorsFor == null &&
            replyingTo == null && selectedMsg == null
    ) { onBack() }

    val hasNitro = currentUser?.hasNitro ?: false
    val sendAnimatedAsGif = SetupPreferences.getSendAnimatedAsGif(context)

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
                            repo.toggleReaction(threadId, target.id, reactionEmoji)
                                .onFailure { sendError = "Couldn't react: ${it.message}" }
                        } catch (e: Exception) {
                            sendError = "Failed: ${e.message}"
                        }
                    }
                    reactingToMsg = null
                } else if (asLink) {
                    // No Nitro for this emoji here: post it as an image link instead.
                    scope.launch {
                        repo.sendMessage(threadId, emoji.linkUrl)
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
                            repo.toggleReaction(threadId, target.id, reactionEmoji)
                                .onFailure { sendError = "Couldn't react: ${it.message}" }
                        }
                        catch (e: Exception) { sendError = "Failed: ${e.message}" }
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
                        if (asLink) repo.sendMessage(threadId, sticker.imageUrl) else repo.sendSticker(threadId, sticker.id)
                    } catch (e: Exception) {
                        sendError = "Failed: ${e.message}"
                    }
                }
            }
        )
        return
    }

    reactorsFor?.let { (messageId, reaction) ->
        ReactorsScreen(threadId, messageId, reaction, imageLoader, onDismiss = { reactorsFor = null })
        return
    }

    if (showPhotoPicker) {
        PhotoPickerScreen(
            imageLoader = imageLoader,
            onImageSelected = { uri, mime ->
                showPhotoPicker = false
                // Wait for Send so the image can go out with a caption and/or as a reply.
                imageDraft = ImageDraft(uri, mime)
            },
            onDismiss = { showPhotoPicker = false }
        )
        return
    }

    // Message options overlay
    val msgForOptions = selectedMsg
    if (msgForOptions != null) {
        val threadListState = rememberScalingLazyListState()
        ScreenScaffold(scrollState = threadListState) {
            ScalingLazyColumn(state = threadListState, modifier = Modifier.fillMaxSize()) {
                item {
                    Text("Message by ${msgForOptions.author.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Button(onClick = { replyingTo = msgForOptions; selectedMsg = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()) {
                        Icon(painter = painterResource(id = R.drawable.reply),
                            contentDescription = null, tint = Color.White,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reply")
                    }
                }
                item {
                    Button(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", msgForOptions.content))
                        selectedMsg = null
                    }, modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()) {
                        Icon(painter = painterResource(id = R.drawable.copy),
                            contentDescription = null, tint = Color.White,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                }
                if (msgForOptions.author.id == myId) {
                    item {
                        Button(onClick = {
                            scope.launch {
                                repo.deleteMessage(threadId, msgForOptions.id)
                                    .onFailure { sendError = "Delete failed" }
                            }
                            selectedMsg = null
                        }, modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error)) {
                            Icon(painter = painterResource(id = R.drawable.delete),
                                contentDescription = null, tint = Color.White,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                    }
                }
                item {
                    Button(onClick = { selectedMsg = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        colors = ButtonDefaults.outlinedButtonColors()) { Text("Cancel") }
                }
            }
        }
        return
    }

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 5
            info.totalItemsCount <= 5 || lastVisible >= info.totalItemsCount - 6
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "thread_title") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(painter = painterResource(id = R.drawable.reply),
                            contentDescription = null, tint = Color(0xFF5865F2),
                            modifier = Modifier.size(14.dp))
                        Text(
                            threadName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                    }
                    if (history.loadingOlder) {
                        Text("Loading older messages…", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (history.noMore && threadMessages.isNotEmpty()) {
                        Text("Start of the thread", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            when {
                loading -> items(4, key = { "skeleton$it" }) { MessageSkeleton(it) }
                threadMessages.isEmpty() -> item {
                    Text("No messages in thread.", style = MaterialTheme.typography.bodySmall)
                }
                else -> items(threadMessages.size, key = { threadMessages[it].id }) { index ->
                    val msg = threadMessages[index]
                    val prevMsg = if (index > 0) threadMessages[index - 1] else null
                    fun String.toEpochMs(): Long = runCatching {
                        java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli()
                    }.getOrElse { 0L }
                    val gapMs = if (prevMsg != null)
                        msg.timestamp.toEpochMs() - prevMsg.timestamp.toEpochMs()
                    else Long.MAX_VALUE
                    val isContinuation = prevMsg != null &&
                        prevMsg.author.id == msg.author.id && gapMs < 10 * 60 * 1000L

                    MessageBubble(
                        msg = msg,
                        isContinuation = isContinuation,
                        imageLoader = imageLoader,
                        channelNames = channelNames,
                        guildId = guildId,
                        roleColorCache = roleColorCache,
                        myId = myId,
                        onReact = { emoji ->
                            scope.launch {
                                repo.toggleReaction(threadId, msg.id, emoji)
                                    .onFailure { sendError = "Couldn't react: ${it.message}" }
                            }
                        },
                        onShowReactors = { reaction -> reactorsFor = msg.id to reaction },
                        onPollVote = { answerId ->
                            scope.launch { repo.votePoll(threadId, msg, answerId) }
                        },
                        onSwipeToReply = { msgToReply ->
                            replyingTo = msgToReply
                        },
                        onLongPress = { selectedMsg = msg },
                        onAvatarClick = { userId ->
                            onNavigateToProfile?.invoke(userId, msg.author.takeIf { it.id == userId })
                        }
                    )
                }
            }

            if (sendError.isNotEmpty()) {
                item(key = "error") {
                    Text(sendError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { sendError = "" })
                }
            }

            // Reply banner
            item(key = "reply_banner") {
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
                        Icon(painter = painterResource(id = R.drawable.reply), contentDescription = null,tint = Color.White, modifier = Modifier.size(10.dp))
                        Text(" ${replyingTo!!.author.displayName}: ${replyingTo!!.content}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "@",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (replyPing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.38f),
                                modifier = Modifier
                                    .clickable { replyPing = !replyPing }
                                    .padding(4.dp)
                            )
                            Text("X", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .clickable { replyingTo = null; replyPing = true }
                                    .padding(4.dp))
                        }
                    }
                }
            }

            // Text input
            item(key = "text_input") {
              Column(modifier = Modifier.fillMaxWidth()) {
                imageDraft?.let { draft ->
                    ImageDraftChip(draft, imageLoader) { imageDraft = null }
                }
                if (mentionQuery != null) {
                    MentionSuggestions(mentionSuggestions) { c ->
                        val newText = MentionInput.complete(inputText, c)
                        inputText = newText
                        pendingText = newText
                        pickedMentions = pickedMentions + (c.shown to c.wire)
                        mentionSuggestions = emptyList()
                    }
                }
                CompactTextField(
                    value = inputText,
                    onValueChange = { inputText = it; pendingText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Reply in thread",
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
              }
            }

            item(key = "action_buttons") {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                        FilledIconButton(
                            onClick = {
                                reactingToMsg = null
                                showPicker = true
                                tab = 0
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(painter = painterResource(id = R.drawable.emoji),
                                contentDescription = "Emoji")
                        }
                        FilledIconButton(
                            colors = altIconButtonColors(),
                            onClick = {
                                reactingToMsg = null
                                showPicker = true
                                tab = 1
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(painter = painterResource(id = R.drawable.sticker),
                                contentDescription = "Stickers")
                        }
                        FilledIconButton(
                            onClick = {
                                val raw = inputText
                                val mentions = pickedMentions
                                val text = MentionInput.toWire(raw.trim(), mentions)
                                val image = imageDraft
                                if (text.isBlank() && image == null) return@FilledIconButton
                                pickedMentions = emptyMap()
                                inputText = ""
                                pendingText = ""
                                imageDraft = null
                                val replyTarget = replyingTo
                                val ping = replyPing
                                replyingTo = null
                                replyPing = true
                                scope.launch {
                                    val result = when {
                                        image != null -> runCatching { image.readBytes(context) }.fold(
                                            onSuccess = { bytes ->
                                                repo.sendFileAttachment(
                                                    threadId, bytes, image.filename, image.mime, text,
                                                    replyTarget?.id, ping
                                                )
                                            },
                                            onFailure = { Result.failure(it) }
                                        )
                                        replyTarget != null -> repo.sendReply(threadId, text, replyTarget.id, ping)
                                        else -> repo.sendMessage(threadId, text)
                                    }
                                    result.onFailure {
                                        if (image != null && imageDraft == null) imageDraft = image
                                        sendError = "Failed: ${it.message}"
                                        // Give the draft back so it can be retried, unless something new was typed.
                                        if (inputText.isEmpty()) {
                                            inputText = raw
                                            pendingText = raw
                                            pickedMentions = mentions
                                        }
                                        if (replyingTo == null && replyTarget != null) {
                                            replyingTo = replyTarget
                                            replyPing = ping
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(40.dp),
                            enabled = inputText.isNotBlank() || imageDraft != null
                        ) {
                            Icon(painter = painterResource(id = R.drawable.send),
                                contentDescription = "Send")
                        }
                    }

                    if (!isRecording) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(space = 16.dp, alignment = Alignment.CenterHorizontally)
                        ) {
                            FilledIconButton(
                                onClick = {
                                    val perm = if (android.os.Build.VERSION.SDK_INT >= 33)
                                        Manifest.permission.READ_MEDIA_IMAGES
                                    else
                                        Manifest.permission.READ_EXTERNAL_STORAGE
                                    val hasPerm = ContextCompat.checkSelfPermission(context, perm) ==
                                            PackageManager.PERMISSION_GRANTED
                                    if (hasPerm) {
                                        showPhotoPicker = true
                                    } else {
                                        imagePermLauncher.launch(perm)
                                    }
                                },
                                modifier = Modifier
                                    .height(40.dp)
                                    .width(40.dp),
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.image),
                                    contentDescription = "Upload Photo"
                                )
                            }
                            FilledIconButton(
                                colors = altIconButtonColors(),
                                onClick = { startRecording() },
                                modifier = Modifier
                                    .height(40.dp)
                                    .width(40.dp),
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

        if (!isAtBottom) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 10.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                FilledIconButton(
                    onClick = {
                        val last = listState.layoutInfo.totalItemsCount - 1
                        if (last >= 0) scope.launch { listState.animateScrollToItem(last) }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.down),
                        contentDescription = "Scroll to bottom"
                    )
                }
            }
        }
    }
}
}


