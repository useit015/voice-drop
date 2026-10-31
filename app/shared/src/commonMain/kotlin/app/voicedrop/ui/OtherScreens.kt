@file:OptIn(ExperimentalMaterial3Api::class)

package app.voicedrop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.voicedrop.data.Repository
import app.voicedrop.data.db.MessageEntity
import app.voicedrop.data.parseMeta
import app.voicedrop.data.phoneOf
import app.voicedrop.platform.VideoPlayer
import app.voicedrop.platform.clockTime
import app.voicedrop.platform.dayLabel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun MediaViewer(repo: Repository, msg: MessageEntity, onBack: () -> Unit) {
    val path = msg.localPath ?: return onBack()
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    var chrome by remember { mutableStateOf(true) }
    val zoom = rememberTransformableState { z, pan, _ ->
        scale = (scale * z).coerceIn(1f, 6f)
        if (scale > 1f) {
            ox += pan.x
            oy += pan.y
        } else {
            ox = 0f
            oy = 0f
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (msg.kind == "image" || msg.kind == "sticker") {
            AsyncImage(
                fileModel(path), null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .transformable(zoom)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { chrome = !chrome },
                            onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                ox = 0f
                                oy = 0f
                            },
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = ox
                        translationY = oy
                    },
            )
        } else {
            VideoPlayer(path, Modifier.fillMaxSize().safeDrawingPadding(), gif = msg.kind == "gif")
        }
        if (chrome) Row(
            Modifier.fillMaxWidth().background(Color(0x88000000)).statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(if (msg.fromMe) "You" else repo.senderName(msg.sender ?: msg.jid), color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("${dayLabel(msg.ts, repo.platform.now())}, ${clockTime(msg.ts)}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            }
            IconButton({ repo.platform.openExternally(path, parseMeta(msg.meta)?.mime) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with", tint = Color.White)
            }
        }
        if (chrome && !msg.text.isNullOrBlank()) Text(
            msg.text, color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000)).safeDrawingPadding().padding(16.dp),
        )
    }
}

@Composable
fun NewChatScreen(repo: Repository, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val contacts by repo.contacts.collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val q = query.trim().lowercase()
    val digits = query.filter { it.isDigit() }
    val shown = remember(contacts, q) {
        contacts.filter { (it.name ?: it.notify) != null && it.jid.endsWith("@s.whatsapp.net") }
            .filter { q.isEmpty() || (it.name ?: it.notify)!!.lowercase().contains(q) || (digits.length >= 3 && it.jid.contains(digits)) }
            .sortedBy { (it.name ?: it.notify)!!.lowercase() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("New chat") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it; error = null },
                placeholder = { Text("Name or number with country code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                supportingText = error?.let { e -> { Text(e, color = MaterialTheme.colorScheme.error) } },
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (digits.length >= 8) item {
                    ListItem(
                        leadingContent = {
                            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                                if (resolving) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                else Icon(Icons.Default.Phone, null, tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        },
                        headlineContent = { Text("Message +$digits") },
                        modifier = Modifier.clickable(enabled = !resolving) {
                            resolving = true
                            scope.launch {
                                try {
                                    onOpen(repo.resolvePhone(digits))
                                } catch (t: Throwable) {
                                    error = t.message
                                }
                                resolving = false
                            }
                        },
                    )
                }
                items(shown, key = { it.jid }) { c ->
                    val name = c.name ?: c.notify!!
                    ListItem(
                        leadingContent = { Avatar(repo, c.jid, name, 44.dp) },
                        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(phoneOf(c.jid)) },
                        modifier = Modifier.clickable { onOpen(c.jid) },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(repo: Repository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val me by repo.me.collectAsState()
    val status by repo.status.collectAsState()
    val total by repo.messageTotal.collectAsState(0)
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                val m = me
                if (m != null) Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(repo, m.jid.substringBefore(':').let { if ('@' in it) it else "$it@s.whatsapp.net" }, m.name ?: "", 64.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(m.name ?: "You", style = MaterialTheme.typography.titleLarge)
                        Text(phoneOf(m.jid.substringBefore(':') + "@s.whatsapp.net"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Link, null) },
                    headlineContent = { Text("Linked device") },
                    supportingContent = { Text("Status: $status. Your phone stays the main device and keeps the notifications.") },
                )
                ListItem(
                    leadingContent = { Icon(Icons.Default.Storage, null) },
                    headlineContent = { Text("Messages on this device") },
                    supportingContent = { Text("$total") },
                )
                ListItem(
                    leadingContent = { Icon(Icons.Default.Info, null) },
                    headlineContent = { Text("About Voice Drop") },
                    supportingContent = {
                        Text("An unofficial WhatsApp client with a myinstants sound board and GIFs. Calls aren't supported. Sending lots of messages quickly can get a number banned, so go easy.")
                    },
                )
                HorizontalDivider()
                ListItem(
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text("Log out", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Unlinks this device and deletes its copy of your chats") },
                    modifier = Modifier.clickable { confirm = true },
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Log out?") },
        text = { Text("Voice Drop will be removed from your linked devices. You can link it again anytime.") },
        confirmButton = {
            TextButton({
                confirm = false
                scope.launch { repo.logout() }
            }) { Text("Log out", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}
