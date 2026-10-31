@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package app.voicedrop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voicedrop.data.MessageItem
import app.voicedrop.data.Repository
import app.voicedrop.data.db.MessageEntity
import app.voicedrop.data.db.Status
import app.voicedrop.data.duration
import app.voicedrop.data.isGroup
import app.voicedrop.data.preview
import app.voicedrop.platform.BackHandler
import app.voicedrop.platform.PickedFile
import app.voicedrop.platform.clockTime
import app.voicedrop.platform.dayLabel
import app.voicedrop.platform.rememberMicPermission
import app.voicedrop.platform.rememberPickers
import app.voicedrop.platform.sameDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val PAGE = 60
private val reactionEmoji = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@Composable
fun ChatScreen(repo: Repository, jid: String, onBack: () -> Unit, onOpenMedia: (MessageEntity) -> Unit) {
    val scope = rememberCoroutineScope()
    val chat by remember(jid) { repo.chat(jid) }.collectAsState(null)
    val names by repo.contactNames.collectAsState()
    val title = repo.title(jid, chat?.name, names)
    val limit = remember(jid) { MutableStateFlow(PAGE) }
    val items by remember(jid) { limit.flatMapLatest { repo.messages(jid, it) } }.collectAsState(emptyList())
    val list = rememberLazyListState()
    val colors = LocalChatColors.current

    var selected by remember { mutableStateOf<MessageItem?>(null) }
    var reply by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var text by remember(jid) { mutableStateOf("") }
    var funOpen by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) }
    var olderState by remember(jid) { mutableStateOf(Older.Idle) }
    val initialUnread = remember(jid) { mutableStateOf<Int?>(null) }
    if (initialUnread.value == null && chat != null) initialUnread.value = chat!!.unread

    DisposableEffect(jid) {
        repo.setOpenChat(jid)
        onDispose {
            repo.setOpenChat(null)
            repo.platform.player.stop()
        }
    }
    BackHandler(enabled = funOpen || selected != null) {
        if (selected != null) selected = null else funOpen = false
    }

    // Keep the view pinned to the newest message while the user is at the bottom.
    val newestId = items.firstOrNull()?.msg?.id
    LaunchedEffect(newestId) {
        if (list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0)
    }

    // Paging: show more of what's stored, then ask the phone for older history.
    val nearTop by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 8 } == true } }
    LaunchedEffect(nearTop, items.size) {
        if (!nearTop || items.isEmpty()) return@LaunchedEffect
        if (items.size >= limit.value) {
            limit.value += PAGE
        } else if (olderState == Older.Idle) {
            olderState = Older.Loading
            val n = repo.loadOlder(jid)
            olderState = when {
                n > 0 -> Older.Idle.also { limit.value += PAGE }
                n == 0 -> Older.Done
                else -> Older.Failed
            }
        }
    }

    fun send() {
        val body = text
        val e = editing
        text = ""
        if (e != null) {
            editing = null
            scope.launch { repo.edit(e, body) }
            return
        }
        val q = reply
        reply = null
        scope.launch { repo.sendText(jid, body, q) }
        scope.launch { list.scrollToItem(0) }
    }

    fun jumpTo(id: String) {
        val idx = items.indexOfFirst { it.msg.id == id }
        if (idx >= 0) scope.launch {
            list.animateScrollToItem(idx)
            flash = id
            delay(900)
            flash = null
        }
    }

    Column(Modifier.fillMaxSize().background(colors.wallpaper).imePadding()) {
        ChatTopBar(repo, jid, title, chat, onBack)
        ConnectionBanner(repo)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(top = 8.dp, bottom = 6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items, key = { _, it -> it.msg.id }, contentType = { _, it -> it.msg.kind }) { i, item ->
                    val m = item.msg
                    val older = items.getOrNull(i + 1)?.msg
                    val newer = items.getOrNull(i - 1)?.msg
                    val newDay = older == null || !sameDay(older.ts, m.ts)
                    val firstOfRun = newDay || older!!.fromMe != m.fromMe || older.sender != m.sender
                    val lastOfRun = newer == null || newer.fromMe != m.fromMe || newer.sender != m.sender || !sameDay(newer.ts, m.ts)
                    Column {
                        if (newDay) DayChip(dayLabel(m.ts, repo.platform.now()))
                        val unread = initialUnread.value ?: 0
                        if (unread > 0 && i == unread - 1) UnreadDivider(unread)
                        MessageRow(
                            repo, item,
                            showSender = firstOfRun,
                            tail = firstOfRun,
                            selected = selected?.msg?.id == m.id || flash == m.id,
                            onLongPress = { selected = item },
                            onReply = { reply = m; editing = null },
                            onOpenMedia = onOpenMedia,
                            onQuoteClick = ::jumpTo,
                            onReactionsClick = { selected = item },
                        )
                        if (lastOfRun) Spacer(Modifier.height(4.dp))
                    }
                }
                item(key = "older") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        when (olderState) {
                            Older.Loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Older.Failed -> TextButton({ olderState = Older.Idle }) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Your phone didn't send older messages. Retry")
                            }
                            Older.Done -> if (items.isNotEmpty()) DayChip("Start of what your phone shared")
                            Older.Idle -> Unit
                        }
                    }
                }
            }
            val showJump by remember { derivedStateOf { list.firstVisibleItemIndex > 3 } }
            androidx.compose.animation.AnimatedVisibility(visible = showJump, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { list.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) { Icon(Icons.Default.KeyboardArrowDown, "Newest") }
            }
        }
        Composer(
            repo, jid, text, { text = it; repo.setTyping(jid, if (it.isEmpty()) "paused" else "composing") },
            reply = reply, editing = editing,
            onCancelContext = { reply = null; if (editing != null) { editing = null; text = "" } },
            funOpen = funOpen, onToggleFun = { funOpen = !funOpen },
            onSend = ::send,
            onSentOther = { reply = null; scope.launch { list.scrollToItem(0) } },
        )
        if (funOpen) FunPanel(repo, jid, reply, onSent = { reply = null; scope.launch { list.scrollToItem(0) } }, modifier = Modifier.fillMaxWidth().height(340.dp))
        else Spacer(Modifier.navigationBarsPadding())
    }

    selected?.let { item ->
        MessageActions(
            repo, item,
            onDismiss = { selected = null },
            onReply = { reply = item.msg; editing = null },
            onEdit = { editing = item.msg; reply = null; text = item.msg.text.orEmpty() },
        )
    }
}

private enum class Older { Idle, Loading, Done, Failed }

@Composable
private fun DayChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), shadowElevation = 0.5.dp) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun UnreadDivider(n: Int) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Text("$n unread message${if (n > 1) "s" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(4.dp))
    }
}

@Composable
private fun ChatTopBar(repo: Repository, jid: String, title: String, chat: app.voicedrop.data.db.ChatEntity?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val typing by repo.typing.collectAsState()
    val presence by repo.presence.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val now = repo.platform.now()
    val t = typing[jid]
    val p = presence[jid]
    val subtitle = when {
        t != null -> (if (isGroup(jid) && t.sender != null) repo.senderName(t.sender).substringBefore(' ') + " is " else "") + if (t.recording) "recording audio…" else "typing…"
        p?.online == true -> "online"
        p != null && p.lastSeen > 0 -> "last seen ${dayLabel(p.lastSeen, now).lowercase().let { if (it == "today" || it == "yesterday") it else "on $it" }} at ${clockTime(p.lastSeen)}"
        isGroup(jid) && (chat?.size ?: 0) > 0 -> "${chat!!.size} members"
        else -> null
    }
    val muted = chat != null && (chat.mutedUntil == -1L || chat.mutedUntil > now)
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(repo, jid, title, 40.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(
                        subtitle, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        color = if (t != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (muted) "Unmute" else "Mute for 8 hours") },
                        leadingIcon = { Icon(if (muted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff, null) },
                        onClick = { menu = false; scope.launch { repo.setMuted(jid, if (muted) 0 else 8 * 3600_000L) } },
                    )
                    DropdownMenuItem(
                        text = { Text(if (chat?.pinned == true) "Unpin" else "Pin") },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                        onClick = { menu = false; scope.launch { repo.setPinned(jid, chat?.pinned != true) } },
                    )
                    DropdownMenuItem(
                        text = { Text(if (chat?.archived == true) "Unarchive" else "Archive") },
                        leadingIcon = { Icon(Icons.Default.Archive, null) },
                        onClick = { menu = false; scope.launch { repo.setArchived(jid, chat?.archived != true) } },
                    )
                }
            }
        },
    )
}

@Composable
private fun Composer(
    repo: Repository,
    jid: String,
    text: String,
    onText: (String) -> Unit,
    reply: MessageEntity?,
    editing: MessageEntity?,
    onCancelContext: () -> Unit,
    funOpen: Boolean,
    onToggleFun: () -> Unit,
    onSend: () -> Unit,
    onSentOther: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val recorder = remember { repo.platform.newRecorder() }
    val elapsed by recorder.elapsedMs.collectAsState()
    val level by recorder.level.collectAsState()
    var rec by remember { mutableStateOf(Rec.Idle) }
    var dragX by remember { mutableStateOf(0f) }
    var attach by remember { mutableStateOf(false) }
    val micOk = rememberMicPermission()
    val density = LocalDensity.current
    val cancelPx = with(density) { 120.dp.toPx() }
    val lockPx = with(density) { 90.dp.toPx() }

    val pickers = rememberPickers("${repo.platform.filesDir}/media") { files ->
        scope.launch {
            files.forEach { f -> sendPicked(repo, jid, f, reply) }
            onSentOther()
        }
    }

    fun finishRecording(send: Boolean) {
        val wasQuote = reply
        rec = Rec.Idle
        repo.setTyping(jid, "paused")
        if (!send) {
            recorder.cancel()
            return
        }
        scope.launch {
            val vn = recorder.finish() ?: return@launch
            repo.sendVoice(jid, vn.path, vn.seconds, vn.wave, wasQuote)
            onSentOther()
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
                modifier = Modifier.weight(1f),
            ) {
                Column {
                    val ctx = editing ?: reply
                    if (ctx != null) ContextStrip(repo, ctx, editing != null, onCancelContext)
                    if (rec != Rec.Idle) {
                        Row(Modifier.height(48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val pulse by animateFloatAsState(0.4f + level * 0.6f, label = "pulse")
                            Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse }.background(Color(0xFFE53935), CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(duration((elapsed / 1000).toInt()), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            if (rec == Rec.Holding) Text(
                                "‹  Slide to cancel",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.offset { IntOffset((dragX / 2).roundToInt(), 0) }.graphicsLayer { alpha = 1f + dragX / cancelPx },
                            ) else TextButton({ finishRecording(false) }) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
                        }
                    } else Row(verticalAlignment = Alignment.Bottom) {
                        IconButton({
                            if (!funOpen) keyboard?.hide() else keyboard?.show()
                            onToggleFun()
                        }) {
                            Icon(
                                if (funOpen) Icons.Default.Keyboard else Icons.Default.GraphicEq,
                                "Sounds and GIFs",
                                tint = if (funOpen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 13.dp)) {
                            if (text.isEmpty()) Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            BasicTextField(
                                value = text,
                                onValueChange = onText,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 6,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (editing == null) {
                            IconButton({ attach = true }) { Icon(Icons.Default.AttachFile, "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (text.isEmpty()) IconButton(pickers.camera) { Icon(Icons.Default.CameraAlt, "Camera", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            val canSend = text.isNotBlank() || rec == Rec.Locked
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .then(
                        if (canSend) Modifier.clickable { if (rec == Rec.Locked) finishRecording(true) else onSend() }
                        else Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (!micOk()) return@awaitEachGesture
                                if (!recorder.start()) return@awaitEachGesture
                                rec = Rec.Holding
                                dragX = 0f
                                repo.setTyping(jid, "recording")
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    val dx = c.position.x - down.position.x
                                    val dy = c.position.y - down.position.y
                                    dragX = dx.coerceAtMost(0f)
                                    if (dx < -cancelPx) {
                                        finishRecording(false)
                                        break
                                    }
                                    if (dy < -lockPx) {
                                        rec = Rec.Locked
                                        break
                                    }
                                    if (!c.pressed) {
                                        finishRecording(true)
                                        break
                                    }
                                    c.consume()
                                }
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        editing != null -> Icons.Default.Check
                        canSend -> Icons.AutoMirrored.Filled.Send
                        else -> Icons.Default.Mic
                    },
                    "Send",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        if (rec == Rec.Holding) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Row(Modifier.padding(end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" Slide up to lock", style = MetaText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (attach) ModalBottomSheet(onDismissRequest = { attach = false }) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            AttachOption(Icons.Default.Photo, "Gallery", Color(0xFF7C4DFF)) { attach = false; pickers.media() }
            AttachOption(Icons.Default.CameraAlt, "Camera", Color(0xFFE53935)) { attach = false; pickers.camera() }
            AttachOption(Icons.Default.Description, "Document", Color(0xFF3F51B5)) { attach = false; pickers.documents() }
            AttachOption(Icons.Default.GraphicEq, "Sounds", Color(0xFFE2553A)) { attach = false; if (!funOpen) onToggleFun() }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private enum class Rec { Idle, Holding, Locked }

private suspend fun sendPicked(repo: Repository, jid: String, f: PickedFile, quote: MessageEntity?) {
    val kind = when {
        f.isImage && !f.mime.contains("gif") -> "image"
        f.isVideo -> "video"
        f.mime.startsWith("audio/") -> "audio"
        else -> "document"
    }
    repo.sendMedia(jid, kind, f.path, f.mime, name = f.name.takeIf { kind == "document" || kind == "audio" }, quote = quote)
}

@Composable
private fun AttachOption(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Box(Modifier.size(56.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ContextStrip(repo: Repository, m: MessageEntity, editing: Boolean, onCancel: () -> Unit) {
    val accent = if (m.fromMe) MaterialTheme.colorScheme.primary else colorFor(m.sender ?: m.jid)
    Row(
        Modifier.padding(start = 6.dp, end = 2.dp, top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                if (editing) "Edit message" else if (m.fromMe) "You" else repo.senderName(m.sender ?: m.jid),
                color = accent, style = MaterialTheme.typography.labelLarge,
            )
            Text(preview(m.kind, m.text), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onCancel) { Icon(Icons.Default.Close, "Cancel", Modifier.size(18.dp)) }
    }
}

@Composable
private fun MessageActions(repo: Repository, item: MessageItem, onDismiss: () -> Unit, onReply: () -> Unit, onEdit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val m = item.msg
    var confirmDelete by remember { mutableStateOf(false) }
    val now = repo.platform.now()
    val canEdit = m.fromMe && m.kind == "text" && now - m.ts < 15 * 60_000 && m.status != Status.FAILED
    val canRevoke = m.fromMe && m.kind != "revoked" && now - m.ts < 2 * 24 * 3600_000L && m.status != Status.FAILED
    fun act(block: suspend () -> Unit) {
        scope.launch { block() }
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (m.kind != "revoked") Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            reactionEmoji.forEach { e ->
                val mine = item.myReaction == e
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (mine) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { act { repo.react(m, if (mine) "" else e) } },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 26.sp) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (m.status == Status.FAILED) Action(Icons.Default.Refresh, "Retry sending") { act { repo.retry(m) } }
        if (m.kind != "revoked") Action(Icons.AutoMirrored.Filled.Reply, "Reply") { onReply(); onDismiss() }
        if (!m.text.isNullOrEmpty() && m.kind != "revoked") Action(Icons.Default.ContentCopy, "Copy") { repo.platform.copyText(m.text); onDismiss() }
        if (canEdit) Action(Icons.Default.Edit, "Edit") { onEdit(); onDismiss() }
        Action(if (m.starred) Icons.Default.StarBorder else Icons.Default.Star, if (m.starred) "Unstar" else "Star") { act { repo.setStarred(m, !m.starred) } }
        Action(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) { confirmDelete = true }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete message?") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canRevoke) TextButton({ act { repo.deleteForEveryone(m) } }) { Text("Delete for everyone") }
                TextButton({ act { repo.deleteForMe(m) } }) { Text("Delete for me") }
                TextButton({ confirmDelete = false }) { Text("Cancel") }
            }
        },
    )
}
