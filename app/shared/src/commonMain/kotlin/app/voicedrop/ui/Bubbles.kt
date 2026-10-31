@file:OptIn(ExperimentalFoundationApi::class)

package app.voicedrop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voicedrop.data.MessageItem
import app.voicedrop.data.Repository
import app.voicedrop.data.db.MessageEntity
import app.voicedrop.data.db.Status
import app.voicedrop.data.duration
import app.voicedrop.data.isGroup
import app.voicedrop.data.userOf
import app.voicedrop.engine.WireMeta
import app.voicedrop.platform.VideoPlayer
import app.voicedrop.platform.clockTime
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

/** Kinds whose attachment is fetched as soon as the bubble shows, like WhatsApp's default auto-download. */
private val autoDownload = setOf("image", "sticker", "voice", "gif", "audio")

@Composable
fun MessageRow(
    repo: Repository,
    item: MessageItem,
    showSender: Boolean,
    tail: Boolean,
    selected: Boolean,
    onLongPress: () -> Unit,
    onReply: () -> Unit,
    onOpenMedia: (MessageEntity) -> Unit,
    onQuoteClick: (String) -> Unit,
    onReactionsClick: () -> Unit,
) {
    val m = item.msg
    val colors = LocalChatColors.current
    val density = LocalDensity.current
    var dx by remember { mutableFloatStateOf(0f) }
    val trigger = with(density) { 64.dp.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .pointerInput(m.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dx > trigger) onReply()
                        dx = 0f
                    },
                    onDragCancel = { dx = 0f },
                ) { _, amount -> dx = (dx + amount).coerceIn(0f, trigger * 1.3f) }
            }
            .padding(horizontal = 10.dp, vertical = if (tail) 3.dp else 1.dp),
    ) {
        if (dx > 8f) Icon(
            Icons.AutoMirrored.Filled.Reply, null,
            tint = colors.meta,
            modifier = Modifier.align(Alignment.CenterStart).graphicsLayer { alpha = (dx / trigger).coerceIn(0f, 1f) },
        )
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(dx.roundToInt(), 0) },
            horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start,
        ) {
            Bubble(repo, item, showSender, tail, onLongPress, onOpenMedia, onQuoteClick)
            if (item.reactions.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 1.dp,
                    modifier = Modifier.offset(y = (-4).dp).padding(horizontal = 8.dp).clickable(onClick = onReactionsClick),
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        item.reactions.take(3).forEach { (e, _) -> Text(e, fontSize = 14.sp) }
                        val total = item.reactions.sumOf { it.second }
                        if (total > 1) Text(" $total", style = MetaText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(
    repo: Repository,
    item: MessageItem,
    showSender: Boolean,
    tail: Boolean,
    onLongPress: () -> Unit,
    onOpenMedia: (MessageEntity) -> Unit,
    onQuoteClick: (String) -> Unit,
) {
    val m = item.msg
    val meta = item.meta
    val colors = LocalChatColors.current
    val bg = if (m.fromMe) colors.outgoing else colors.incoming
    val fg = if (m.fromMe) colors.onOutgoing else colors.onIncoming
    val r = 16.dp
    val shape = when {
        !tail -> RoundedCornerShape(r)
        m.fromMe -> RoundedCornerShape(r, 4.dp, r, r)
        else -> RoundedCornerShape(4.dp, r, r, r)
    }
    val bare = m.kind == "sticker"
    val scope = rememberCoroutineScope()

    val media = m.kind in setOf("image", "video", "gif")
    Surface(
        shape = shape,
        color = if (bare) Color.Transparent else bg,
        contentColor = fg,
        shadowElevation = if (bare) 0.dp else 0.5.dp,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .combinedClickable(
                onClick = {
                    if (m.status == Status.FAILED) scope.launch { repo.retry(m) }
                },
                onLongClick = onLongPress,
            ),
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(if (media || bare) 3.dp else 0.dp)) {
            if (showSender && !m.fromMe && isGroup(m.jid)) {
                Text(
                    repo.senderName(m.sender) + (m.push?.let { p -> if (repo.contactNames.value[m.sender] == null) " ~$p" else "" } ?: ""),
                    color = colorFor(m.sender.orEmpty()),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp),
                )
            }
            if (m.forwarded) Text(
                "↪ Forwarded", style = MetaText, fontStyle = FontStyle.Italic, color = colors.meta,
                modifier = Modifier.padding(start = 10.dp, top = 4.dp, end = 10.dp),
            )
            if (m.quoteId != null) Quote(repo, m, onQuoteClick)
            when (m.kind) {
                "text" -> TextBody(m, meta)
                "image", "video", "gif" -> {
                    MediaBody(repo, m, meta, onOpenMedia)
                    if (!m.text.isNullOrBlank()) TextBody(m, meta)
                    else Box(Modifier.fillMaxWidth()) { MetaLine(m, Modifier.align(Alignment.BottomEnd).padding(6.dp), onMedia = true) }
                }
                "sticker" -> StickerBody(repo, m)
                "voice", "audio" -> VoiceBody(repo, m, meta)
                "document" -> DocumentBody(repo, m, meta)
                else -> TextBody(m, meta)
            }
        }
    }
}

@Composable
private fun Quote(repo: Repository, m: MessageEntity, onQuoteClick: (String) -> Unit) {
    val colors = LocalChatColors.current
    val me = repo.me.collectAsState().value
    val mine = m.quoteSender != null && me != null && userOf(m.quoteSender) == userOf(me.jid)
    val who = if (mine) "You" else repo.senderName(m.quoteSender ?: m.jid)
    val accent = if (mine) MaterialTheme.colorScheme.primary else colorFor(m.quoteSender ?: m.jid)
    Row(
        Modifier
            .padding(start = 4.dp, end = 4.dp, top = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (m.fromMe) colors.quoteOut else colors.quoteIn)
            .clickable { onQuoteClick(m.quoteId!!) }
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(who, color = accent, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                app.voicedrop.data.preview(m.quoteKind, m.quoteText).ifBlank { m.quoteText.orEmpty() },
                style = MaterialTheme.typography.bodySmall,
                color = colors.meta,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val urlRegex = Regex("""(https?://[^\s<>"]+|www\.[^\s<>"]+)""")

@Composable
private fun TextBody(m: MessageEntity, meta: WireMeta?) {
    val colors = LocalChatColors.current
    val revoked = m.kind == "revoked"
    val raw = when (m.kind) {
        "text", "image", "video", "gif" -> m.text.orEmpty()
        "revoked" -> if (m.fromMe) "You deleted this message" else "This message was deleted"
        "location" -> "📍 Location\n${meta?.name ?: ""}${if (meta != null) "\n${meta.lat}, ${meta.lng}" else ""}".trim()
        "contact" -> "👤 ${m.text ?: "Contact"}"
        "poll" -> "📊 ${m.text.orEmpty()}" + meta?.options.orEmpty().joinToString("") { "\n  ○ $it" }
        else -> m.text ?: "This message type isn't supported yet. Open WhatsApp on your phone to see it."
    }
    val metaWidth = (if (m.fromMe) 9 else 6) + (if (m.edited) 7 else 0) + (if (m.starred) 2 else 0)
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(raw, metaWidth) {
        buildAnnotatedString {
            var last = 0
            for (match in urlRegex.findAll(raw)) {
                append(raw.substring(last, match.range.first))
                val url = match.value.let { if (it.startsWith("www.")) "https://$it" else it }
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(match.value) }
                last = match.range.last + 1
            }
            append(raw.substring(last))
            // Invisible tail so the time never overlaps the last line.
            withStyle(SpanStyle(color = Color.Transparent, fontSize = 11.sp)) { append(" " + " ".repeat(metaWidth)) }
        }
    }
    Box(Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (revoked) {
                Icon(Icons.Default.Block, null, Modifier.size(16.dp), tint = colors.meta)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge.let { s -> if (isJumbo(raw)) s.copy(fontSize = 40.sp, lineHeight = 46.sp) else s },
                fontStyle = if (revoked) FontStyle.Italic else FontStyle.Normal,
                color = if (revoked) colors.meta else LocalContentColorOr(),
            )
        }
        MetaLine(m, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun LocalContentColorOr(): Color = androidx.compose.material3.LocalContentColor.current

/** 1-3 emoji only, shown big like WhatsApp. */
private fun isJumbo(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty() || t.length > 12) return false
    return t.none { it.isLetterOrDigit() || it.isWhitespace() || it in "!?.,:;'\"()[]{}<>-_/\\@#$%^&*+=~`|" }
}

@Composable
fun MetaLine(m: MessageEntity, modifier: Modifier = Modifier, onMedia: Boolean = false) {
    val colors = LocalChatColors.current
    val tint = if (onMedia) Color.White else colors.meta
    Row(
        modifier.then(if (onMedia) Modifier.background(Color(0x66000000), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (m.starred) Icon(Icons.Default.Star, "Starred", Modifier.size(11.dp), tint = tint)
        if (m.edited) Text("Edited", style = MetaText, color = tint)
        Text(clockTime(m.ts), style = MetaText, color = tint)
        if (m.fromMe) StatusTick(m.status, tint, 15.dp)
    }
}

@Composable
private fun rememberMedia(repo: Repository, m: MessageEntity, auto: Boolean): Triple<String?, Boolean, () -> Unit> {
    val scope = rememberCoroutineScope()
    var path by remember(m.id) { mutableStateOf(m.localPath?.takeIf { repo.platform.exists(it) }) }
    var busy by remember(m.id) { mutableStateOf(false) }
    var failed by remember(m.id) { mutableStateOf(false) }
    val fetch = {
        if (!busy && path == null && m.raw != null) {
            busy = true
            failed = false
            scope.launch {
                path = runCatching { repo.media(m) }.getOrNull()
                failed = path == null
                busy = false
            }
        }
    }
    LaunchedEffect(m.id, m.localPath) {
        if (path == null && m.localPath != null && repo.platform.exists(m.localPath)) path = m.localPath
        if (auto && path == null) fetch()
    }
    return Triple(path, busy, if (failed || path == null) fetch else ({}))
}

@Composable
private fun MediaBody(repo: Repository, m: MessageEntity, meta: WireMeta?, onOpenMedia: (MessageEntity) -> Unit) {
    val (path, busy, fetch) = rememberMedia(repo, m, m.kind in autoDownload)
    val ratio = if (meta != null && meta.w > 0 && meta.h > 0) (meta.w.toFloat() / meta.h).coerceIn(0.6f, 1.8f) else 1f
    val thumb = remember(meta?.thumb) { meta?.thumb?.let { runCatching { Base64.decode(it) }.getOrNull() } }
    Box(
        Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(13.dp)).background(Color(0x22000000))
            .clickable { if (path != null) onOpenMedia(m.copy(localPath = path)) else fetch() },
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        when {
            path != null && m.kind == "gif" -> VideoPlayer(path, Modifier.fillMaxSize(), gif = true)
            path != null && m.kind == "image" -> AsyncImage(fileModel(path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            path != null && m.kind == "video" -> {
                AsyncImage(fileModel(path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                RoundIcon { Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(34.dp)) }
            }
            busy -> RoundIcon { CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp) }
            m.raw != null -> RoundIcon {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, "Download", tint = Color.White)
                    if (meta?.size != null && meta.size > 0) Text(formatSize(meta.size), color = Color.White, style = MetaText, modifier = Modifier.padding(end = 4.dp))
                }
            }
        }
        if (m.kind == "gif") Text(
            "GIF", color = Color.White, fontWeight = FontWeight.Bold, style = MetaText,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        if (m.kind == "video" && (meta?.seconds ?: 0) > 0) Text(
            duration(meta!!.seconds), color = Color.White, style = MetaText,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun RoundIcon(content: @Composable () -> Unit) {
    Box(Modifier.background(Color(0x88000000), CircleShape).padding(10.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun StickerBody(repo: Repository, m: MessageEntity) {
    val (path, _, _) = rememberMedia(repo, m, true)
    Box(Modifier.size(150.dp)) {
        if (path != null) AsyncImage(fileModel(path), "Sticker", Modifier.fillMaxSize())
        MetaLine(m, Modifier.align(Alignment.BottomEnd), onMedia = true)
    }
}

@Composable
private fun VoiceBody(repo: Repository, m: MessageEntity, meta: WireMeta?) {
    val colors = LocalChatColors.current
    val (path, busy, fetch) = rememberMedia(repo, m, true)
    val player = repo.platform.player
    val ps by player.state.collectAsState()
    val key = "${m.jid}/${m.id}"
    val active = ps.key == key
    val playing = active && ps.playing
    val total = (if (active && ps.durationMs > 0) ps.durationMs / 1000 else meta?.seconds?.toLong() ?: 0L).toInt()
    val progress = if (active && ps.durationMs > 0) ps.positionMs.toFloat() / ps.durationMs else 0f
    val bars = remember(meta?.wave) {
        meta?.wave?.let { runCatching { Base64.decode(it) }.getOrNull() }?.map { (it.toInt() and 0xFF).coerceAtMost(100) / 100f }
            ?: List(64) { 0.15f + ((it * 37) % 11) / 20f }
    }
    val accent = if (m.kind == "voice") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

    Column(Modifier.width(270.dp).padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable {
                    if (path == null) fetch()
                    else {
                        player.toggle(key, path)
                        if (!active) repo.markPlayed(m)
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    path == null && m.raw != null -> Icon(Icons.Default.Download, "Download", tint = colors.meta)
                    playing -> Icon(Icons.Default.Pause, "Pause", Modifier.size(34.dp), tint = colors.meta)
                    else -> Icon(Icons.Default.PlayArrow, "Play", Modifier.size(34.dp), tint = colors.meta)
                }
            }
            Waveform(
                bars, progress, accent, colors.meta.copy(alpha = 0.45f),
                Modifier.weight(1f).height(30.dp).pointerInput(key, path) {
                    detectTapGestures { o ->
                        if (path != null) {
                            if (!active) player.toggle(key, path)
                            player.seek(key, o.x / size.width)
                        }
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
            if (active) Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.meta.copy(alpha = 0.25f),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { player.cycleSpeed() },
            ) {
                Text("${ps.speed.toString().removeSuffix(".0")}×", style = MetaText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            } else Icon(Icons.Default.Mic, null, Modifier.size(18.dp), tint = if (m.kind == "voice") accent else Color.Transparent)
        }
        Row(Modifier.fillMaxWidth().padding(start = 46.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (active && ps.positionMs > 0) duration((ps.positionMs / 1000).toInt()) else duration(total),
                style = MetaText,
                color = colors.meta,
                modifier = Modifier.weight(1f),
            )
            MetaLine(m)
        }
    }
}

@Composable
private fun Waveform(bars: List<Float>, progress: Float, played: Color, rest: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = bars.size
        val gap = 2.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        bars.forEachIndexed { i, v ->
            val h = (v.coerceIn(0.08f, 1f)) * size.height
            val x = i * (w + gap)
            drawRoundRect(
                color = if ((i + 0.5f) / n <= progress) played else rest,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

@Composable
private fun DocumentBody(repo: Repository, m: MessageEntity, meta: WireMeta?) {
    val colors = LocalChatColors.current
    val (path, busy, fetch) = rememberMedia(repo, m, false)
    val name = meta?.name ?: m.text ?: "Document"
    Column(Modifier.width(270.dp).padding(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (m.fromMe) colors.quoteOut else colors.quoteIn)
                .clickable { if (path != null) repo.platform.openExternally(path, meta?.mime) else fetch() }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(name.substringAfterLast('.', "").uppercase().takeIf { it.length in 1..5 }, meta?.size?.let { formatSize(it) }?.ifEmpty { null }).joinToString(" · "),
                    style = MetaText, color = colors.meta,
                )
            }
            when {
                busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                path == null -> Icon(Icons.Default.Download, "Download", tint = colors.meta)
            }
        }
        val caption = m.text?.takeIf { it != name }
        if (caption != null) Text(caption, modifier = Modifier.padding(6.dp))
        Box(Modifier.fillMaxWidth()) { MetaLine(m, Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 2.dp)) }
    }
}
