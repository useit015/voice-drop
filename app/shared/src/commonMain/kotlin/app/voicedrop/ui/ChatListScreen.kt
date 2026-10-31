@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.voicedrop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.voicedrop.data.ChatItem
import app.voicedrop.data.Repository
import app.voicedrop.data.Typing
import app.voicedrop.data.db.MessageEntity
import app.voicedrop.data.parseMeta
import app.voicedrop.data.preview
import app.voicedrop.platform.BackHandler
import app.voicedrop.platform.listTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Filter(val label: String) { All("All"), Unread("Unread"), Groups("Groups"), Archived("Archived") }

@Composable
fun ChatListScreen(repo: Repository, onOpen: (String) -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit) {
    val chats by repo.chats.collectAsState(emptyList())
    val typing by repo.typing.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<ChatItem?>(null) }
    var found by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    val now = repo.platform.now()
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    LaunchedEffect(query) {
        delay(250)
        found = repo.searchMessages(query.trim())
    }

    val q = query.trim().lowercase()
    val shown = chats.filter { c ->
        when {
            q.isNotEmpty() -> c.title.lowercase().contains(q) || c.jid.contains(q.filter { it.isDigit() }.ifEmpty { "\u0000" })
            filter == Filter.Archived -> c.chat.archived
            c.chat.archived -> false
            filter == Filter.Unread -> c.chat.unread > 0 || c.chat.markedUnread
            filter == Filter.Groups -> c.isGroup
            else -> true
        }
    }
    val archivedCount = chats.count { it.chat.archived }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (searching) {
                TopAppBar(
                    navigationIcon = {
                        IconButton({ searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search chats and messages") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Voice Drop", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton({ searching = true }) { Icon(Icons.Default.Search, "Search") }
                        IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    },
                    scrollBehavior = scroll,
                )
            }
        },
        floatingActionButton = {
            if (!searching) FloatingActionButton(onNewChat, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.AutoMirrored.Filled.Chat, "New chat")
            }
        },
    ) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize()) {
            ConnectionBanner(repo)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (!searching) item(key = "filters") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Filter.entries.filter { it != Filter.Archived || archivedCount > 0 }) { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = if (filter == f && f != Filter.All) Filter.All else f },
                                label = { Text(if (f == Filter.Archived) "${f.label} ($archivedCount)" else f.label) },
                            )
                        }
                    }
                }
                items(shown, key = { it.jid }) { c ->
                    ChatRow(repo, c, typing[c.jid], now, onClick = { onOpen(c.jid) }, onLongClick = { menuFor = c }, modifier = Modifier.animateItem())
                }
                if (shown.isEmpty() && q.isEmpty()) item {
                    Text(
                        if (chats.isEmpty()) "Your chats will appear here as they sync." else "Nothing here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
                if (q.isNotEmpty() && found.isNotEmpty()) {
                    item { SectionLabel("Messages") }
                    items(found, key = { "m:${it.jid}/${it.id}" }) { m ->
                        val title = repo.title(m.jid, chats.firstOrNull { it.jid == m.jid }?.chat?.name)
                        ListItem(
                            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(m.text.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { Text(listTime(m.ts, now), style = MaterialTheme.typography.labelSmall) },
                            leadingContent = { Avatar(repo, m.jid, title, 44.dp) },
                            modifier = Modifier.combinedClickable(onClick = { onOpen(m.jid) }),
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { c -> ChatActions(repo, c) { menuFor = null } }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChatRow(repo: Repository, c: ChatItem, typing: Typing?, now: Long, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val chat = c.chat
    val unread = chat.unread > 0 || chat.markedUnread
    val muted = chat.mutedUntil == -1L || chat.mutedUntil > now
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(repo, c.jid, c.title, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.lastTs > 0) Text(
                    listTime(chat.lastTs, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread && !muted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (typing != null) {
                        Text(
                            (if (c.isGroup) repo.senderName(typing.sender).substringBefore(' ') + " is " else "") + if (typing.recording) "recording audio…" else "typing…",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    } else {
                        if (chat.lastFromMe && chat.lastKind != "revoked") {
                            StatusTick(chat.lastStatus, MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(3.dp))
                        }
                        val who = if (c.lastSenderName != null) c.lastSenderName.substringBefore(' ') + ": " else ""
                        Text(
                            who + preview(chat.lastKind, chat.lastText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (muted) Icon(Icons.AutoMirrored.Filled.VolumeOff, "Muted", Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (chat.pinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (unread) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.defaultMinSize(20.dp, 20.dp).background(if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (chat.unread > 0) (if (chat.unread > 999) "999+" else "${chat.unread}") else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatActions(repo: Repository, c: ChatItem, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chat = c.chat
    val now = repo.platform.now()
    val muted = chat.mutedUntil == -1L || chat.mutedUntil > now
    var muteMenu by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Unit) {
        scope.launch { block() }
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(repo, c.jid, c.title, 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (muteMenu) {
            Action(Icons.Default.NotificationsOff, "8 hours") { act { repo.setMuted(c.jid, 8 * 3600_000L) } }
            Action(Icons.Default.NotificationsOff, "1 week") { act { repo.setMuted(c.jid, 7 * 24 * 3600_000L) } }
            Action(Icons.Default.NotificationsOff, "Always") { act { repo.setMuted(c.jid, -1) } }
        } else {
            Action(Icons.Default.PushPin, if (chat.pinned) "Unpin" else "Pin") { act { repo.setPinned(c.jid, !chat.pinned) } }
            Action(if (chat.archived) Icons.Default.Unarchive else Icons.Default.Archive, if (chat.archived) "Unarchive" else "Archive") {
                act { repo.setArchived(c.jid, !chat.archived) }
            }
            if (muted) Action(Icons.Default.NotificationsActive, "Unmute") { act { repo.setMuted(c.jid, 0) } }
            else Action(Icons.Default.NotificationsOff, "Mute…") { muteMenu = true }
            if (chat.unread > 0 || chat.markedUnread) Action(Icons.Default.MarkChatRead, "Mark as read") { act { repo.markRead(c.jid) } }
            else Action(Icons.Default.MarkChatUnread, "Mark as unread") { act { repo.markUnread(c.jid) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Action(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null, tint = tint) },
        headlineContent = { Text(label, color = tint) },
        modifier = Modifier.combinedClickable(onClick = onClick),
    )
}
