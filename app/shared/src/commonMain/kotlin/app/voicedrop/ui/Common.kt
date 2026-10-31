package app.voicedrop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voicedrop.data.Repository
import app.voicedrop.data.db.Status
import app.voicedrop.data.isGroup
import coil3.compose.AsyncImage

fun fileModel(path: String) = "file://$path"

@Composable
fun Avatar(repo: Repository, jid: String, title: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val flow = remember(jid) { repo.avatar(jid) }
    val path by flow.collectAsState(null)
    Box(
        modifier.size(size).clip(CircleShape).background(colorFor(jid).copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (isGroup(jid)) {
            Icon(Icons.Default.Group, null, tint = colorFor(jid), modifier = Modifier.size(size * 0.55f))
        } else {
            val initials = title.split(' ', '-').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() }
            Text(
                initials.ifEmpty { "#" },
                color = colorFor(jid),
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.36f).sp,
            )
        }
        if (path != null) {
            AsyncImage(
                model = fileModel(path!!),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
fun StatusTick(status: Int, tint: Color = LocalChatColors.current.meta, size: Dp = 16.dp) {
    val read = LocalChatColors.current.read
    when (status) {
        Status.FAILED -> Icon(Icons.Default.ErrorOutline, "Not sent", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(size))
        Status.PENDING -> Icon(Icons.Default.Schedule, "Sending", tint = tint, modifier = Modifier.size(size - 2.dp))
        Status.SENT -> Icon(Icons.Default.Done, "Sent", tint = tint, modifier = Modifier.size(size))
        Status.DELIVERED -> Icon(Icons.Default.DoneAll, "Delivered", tint = tint, modifier = Modifier.size(size))
        else -> Icon(Icons.Default.DoneAll, "Read", tint = read, modifier = Modifier.size(size))
    }
}

/** Thin bar under the top bar: connecting / offline / first sync. */
@Composable
fun ConnectionBanner(repo: Repository) {
    val status by repo.status.collectAsState()
    val online by repo.online.collectAsState()
    val sync by repo.sync.collectAsState()
    val text = when {
        status == "connecting" -> "Connecting…"
        status == "disconnected" -> "Waiting for network…"
        !online -> "Connection is slow…"
        sync != null -> "Syncing your chats… ${sync}%"
        else -> null
    }
    AnimatedVisibility(text != null) {
        Column {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (status == "disconnected") Icon(Icons.Default.CloudOff, null, Modifier.size(16.dp))
                else CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(text.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (sync != null) LinearProgressIndicator(progress = { (sync ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> {
        val mb = bytes / (1024.0 * 1024.0)
        "${(mb * 10).toLong() / 10.0} MB"
    }
}
