package dev.vory.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vory.android.VoryApp
import dev.vory.android.data.AnswerShape
import dev.vory.android.data.BotMood
import dev.vory.android.data.ChatItem
import dev.vory.android.data.HermesSocketClient
import dev.vory.android.data.PendingCard
import dev.vory.android.data.TextMessage
import dev.vory.android.data.ToolCardItem
import dev.vory.android.ui.components.BotFace
import dev.vory.android.ui.components.ConnPill
import dev.vory.android.ui.components.LocalVoryUiPrefs
import dev.vory.android.ui.components.OneUiCard
import dev.vory.android.ui.theme.OneUi
import dev.vory.android.vm.AppViewModel
import dev.vory.android.vm.ChatViewModel
import org.json.JSONObject

/**
 * Chat thread: streaming bubbles, tool cards, approval/clarify/secret cards,
 * model picker, slash commands, attachments, reply quotes, bot-to-bot notices.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    profile: String,
    draft: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as VoryApp
    val repo = app.repository
    val appVm: AppViewModel = viewModel(factory = AppViewModel.Factory(repo))
    val activeProfile by appVm.activeProfile.collectAsState()
    val chatProfile = profile.ifEmpty { activeProfile }
    val vm: ChatViewModel = viewModel(
        key = "chat-${sessionId ?: "new"}-$chatProfile",
        factory = ChatViewModel.Factory(repo, sessionId?.ifEmpty { null }, chatProfile, draft),
    )

    val items by vm.items.collectAsState()
    val streamText by vm.streamText.collectAsState()
    val sending by vm.sending.collectAsState()
    val notice by vm.notice.collectAsState()
    val title by vm.title.collectAsState()
    val cards by vm.sessionCards.collectAsState()
    val replyTo by vm.replyTo.collectAsState()
    val modelOptions by vm.modelOptions.collectAsState()
    val pendingRefs by vm.pendingRefs.collectAsState()
    val connState by appVm.connState.collectAsState()
    val profiles by appVm.profiles.collectAsState()
    val moods by appVm.botMoods.collectAsState()
    val botColors by appVm.botColors.collectAsState()
    val prefs = LocalVoryUiPrefs.current
    val snackbar = remember { SnackbarHostState() }

    val bot = profiles.firstOrNull { it.name == chatProfile }
    val curSessionId by vm.sessionId.collectAsState()
    val mood = curSessionId?.let { moods[it] }
        ?: if (sending) BotMood.WORKING else if (cards.isNotEmpty()) BotMood.WAITING else BotMood.IDLE

    var composer by remember { mutableStateOf(draft) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var msgActionFor by remember { mutableStateOf<TextMessage?>(null) }
    // Local image previews (Coil) for attachments pending upload/attach.
    var attachedImages by remember { mutableStateOf(listOf<android.net.Uri>()) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attachedImages = attachedImages + uri
            vm.attach(context, uri, context.contentResolver.getType(uri))
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.attach(context, uri, context.contentResolver.getType(uri))
    }

    LaunchedEffect(notice) {
        when {
            notice == "model-picker" -> {
                vm.loadModelOptions()
                showModelSheet = true
                vm.clearNotice()
            }
            notice != null -> {
                snackbar.showSnackbar(notice!!)
                vm.clearNotice()
            }
        }
    }

    val listState = rememberLazyListState()
    // Scroll to bottom on new items and periodically while streaming.
    LaunchedEffect(items.size, streamText.length / 240, cards.size) {
        val total = items.size + (if (streamText.isNotEmpty()) 1 else 0) + cards.size
        if (total > 0) listState.animateScrollToItem((total - 1).coerceAtLeast(0))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Floating header: back, bot face pill, status, menu.
            ChatHeader(
                title = title,
                botName = chatProfile,
                faceSeed = bot?.faceSeed ?: chatProfile.hashCode(),
                faceColor = botColors[chatProfile]?.let { Color(it) } ?: OneUi.SamsungBlue,
                mood = mood,
                faceMotion = prefs.faceMotion,
                connReady = connState == HermesSocketClient.ConnState.READY,
                connConnecting = connState == HermesSocketClient.ConnState.CONNECTING,
                onBack = onBack,
                onMenu = { showMenu = true },
                onModelPicker = {
                    vm.loadModelOptions()
                    showModelSheet = true
                },
                showMenu = showMenu,
                onDismissMenu = { showMenu = false },
                onNewChat = { vm.send("/new") },
            )

            // Transcript.
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is TextMessage -> MessageBubble(
                            msg = item,
                            accent = botColors[chatProfile]?.let { Color(it) } ?: OneUi.SamsungBlue,
                            showStats = prefs.showStats,
                            onLongPress = { msgActionFor = item },
                        )
                        is ToolCardItem -> if (prefs.showToolCards) ToolCard(item = item)
                    }
                }
                if (streamText.isNotEmpty()) {
                    item(key = "stream") {
                        StreamingBubble(text = streamText)
                    }
                }
                items(cards, key = { "card-${it.requestId}" }) { card ->
                    PendingCardView(
                        card = card,
                        shape = vm.cardShape(card),
                        onAnswer = { payload -> vm.answerCard(card, payload) },
                    )
                }
            }

            // Composer.
            replyTo?.let { quoted ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            quoted.text.take(120), maxLines = 2,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.setReplyTo(null) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear quote")
                        }
                    }
                }
            }
            if (pendingRefs.isNotEmpty()) {
                Text(
                    pendingRefs.joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            // Coil thumbnails for attached images.
            if (attachedImages.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachedImages, key = { it.toString() }) { uri ->
                        Box {
                            coil.compose.AsyncImage(
                                model = uri,
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            IconButton(
                                onClick = { attachedImages = attachedImages - uri },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(28.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close, contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            // Slash suggestions.
            val firstWord = composer.trim().split(" ").firstOrNull().orEmpty()
            if (firstWord.startsWith("/") && firstWord.length > 1) {
                val suggestions = vm.slashSuggestions(firstWord)
                if (suggestions.isNotEmpty()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        suggestions.forEach { s ->
                            androidx.compose.material3.AssistChip(
                                onClick = {
                                    composer = s + " "
                                },
                                label = { Text(s, fontSize = 12.sp) },
                            )
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box {
                    IconButton(
                        onClick = { attachMenu = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach")
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Photo") },
                            onClick = { attachMenu = false; pickImage.launch("image/*") },
                        )
                        DropdownMenuItem(
                            text = { Text("File") },
                            onClick = { attachMenu = false; pickFile.launch("*/*") },
                        )
                    }
                }
                OutlinedTextField(
                    value = composer,
                    onValueChange = { composer = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message ${chatProfile.ifEmpty { "bot" }}…") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                )
                Spacer(Modifier.width(8.dp))
                if (sending) {
                    IconButton(
                        onClick = { vm.stop() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(
                        onClick = {
                            vm.setDraft("")
                            vm.send(composer)
                            composer = ""
                            attachedImages = emptyList()
                        },
                        modifier = Modifier.size(48.dp),
                        enabled = composer.isNotBlank() || pendingRefs.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    // Message long-press actions.
    msgActionFor?.let { msg ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { msgActionFor = null },
            title = { Text("Message") },
            text = { Text(msg.text.take(200), maxLines = 4) },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        vm.setReplyTo(msg)
                        msgActionFor = null
                    }) { Text("Reply") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(msg.text))
                        msgActionFor = null
                    }) { Text("Copy") }
                    if (msg.role == "user") {
                        TextButton(onClick = {
                            vm.setDraft(msg.text)
                            msgActionFor = null
                        }) { Text("Edit & resend") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { msgActionFor = null }) { Text("Close") }
            },
        )
    }

    // Model picker sheet (grouped by provider).
    if (showModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                "Model (this chat)",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val grouped = modelOptions.groupBy { it.provider.ifEmpty { "other" } }
                grouped.forEach { (provider, models) ->
                    item(key = "p-$provider") {
                        Text(
                            provider,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(models, key = { "m-${it.provider}-${it.model}" }) { opt ->
                        Text(
                            opt.model,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(onClick = {
                                    vm.selectModel(opt)
                                    showModelSheet = false
                                })
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
                if (modelOptions.isEmpty()) {
                    item { Text("Loading models…", modifier = Modifier.padding(16.dp)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChatHeader(
    title: String,
    botName: String,
    faceSeed: Int,
    faceColor: Color,
    mood: BotMood,
    faceMotion: String,
    connReady: Boolean,
    connConnecting: Boolean,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onModelPicker: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onNewChat: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            BotFace(seed = faceSeed, color = faceColor, mood = mood, size = 40.dp, motion = faceMotion)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    botName.ifEmpty { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            ConnPill(ready = connReady, connecting = connConnecting)
            Box {
                IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(text = { Text("Model…") }, onClick = { onDismissMenu(); onModelPicker() })
                    DropdownMenuItem(text = { Text("New chat (/new)") }, onClick = { onDismissMenu(); onNewChat() })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: TextMessage,
    accent: Color,
    showStats: Boolean,
    onLongPress: () -> Unit,
) {
    when (msg.role) {
        "notice" -> {
            // Bot-to-bot notice: centred pill.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        msg.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return
        }
        "system" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return
        }
    }
    val isUser = msg.role == "user"
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxWidth(0.85f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (msg.replyTo != null) {
                Text(
                    msg.replyTo, maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isUser) accent else MaterialTheme.colorScheme.surface,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                ),
            ) {
                SelectionContainer {
                    Text(
                        msg.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (showStats && msg.stats != null) {
                Text(
                    msg.stats,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Box(Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.85f),
        ) {
            Text(
                text.ifEmpty { "…" },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (text.isEmpty()) 0.5f else 1f),
            )
        }
    }
}

@Composable
private fun ToolCard(item: ToolCardItem) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    OneUiCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.tool,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!item.done) {
                    Text("running…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            if (item.command.isNotEmpty()) {
                Text(
                    item.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                )
            }
            if (item.todos.isNotEmpty()) {
                // `todo` tool → checklist UI.
                item.todos.forEach { todo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = todo.done, onCheckedChange = null, modifier = Modifier.size(40.dp))
                        Text(
                            todo.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else if (item.output.isNotEmpty()) {
                Text(
                    item.output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 40 else 8,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Show more")
                }
            }
        }
    }
}

@Composable
private fun PendingCardView(
    card: PendingCard,
    shape: AnswerShape,
    onAnswer: (JSONObject) -> Unit,
) {
    var textValue by remember(card.requestId) { mutableStateOf("") }
    OneUiCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (shape) {
                    AnswerShape.CHOICE -> "Approval needed"
                    AnswerShape.TEXT -> "Clarification needed"
                    AnswerShape.SECRET -> "Secret needed"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(card.title, style = MaterialTheme.typography.titleMedium)
            if (card.detail.isNotEmpty()) {
                Text(
                    if (card.secret) "••••••••" else card.detail.take(300),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (shape) {
                AnswerShape.CHOICE -> {
                    val choices = if (card.kind.name == "SUDO") listOf("once" to "Approve", "deny" to "Deny")
                    else listOf("once" to "Once", "session" to "Session", "always" to "Always", "deny" to "Deny")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { (value, label) ->
                            androidx.compose.material3.AssistChip(
                                onClick = { onAnswer(JSONObject().put("choice", value)) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                AnswerShape.TEXT -> {
                    OutlinedTextField(
                        value = textValue, onValueChange = { textValue = it },
                        label = { Text("Answer") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { onAnswer(JSONObject().put("text", textValue)) },
                        enabled = textValue.isNotBlank(),
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Send") }
                }
                AnswerShape.SECRET -> {
                    // SecretField equivalent: never logged, masked.
                    OutlinedTextField(
                        value = textValue, onValueChange = { textValue = it },
                        label = { Text("Secret value") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = { onAnswer(JSONObject().put("secret", textValue)) },
                        enabled = textValue.isNotBlank(),
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Submit") }
                }
            }
        }
    }
}
