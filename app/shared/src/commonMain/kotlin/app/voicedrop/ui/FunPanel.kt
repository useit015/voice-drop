@file:OptIn(ExperimentalFoundationApi::class)

package app.voicedrop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.voicedrop.data.Repository
import app.voicedrop.data.db.MessageEntity
import app.voicedrop.sounds.Gif
import app.voicedrop.sounds.Sound
import app.voicedrop.sounds.SoundSource
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The custom part: myinstants sounds sent as voice notes, and GIFs. Sits where the keyboard would. */
@Composable
fun FunPanel(repo: Repository, jid: String, quote: MessageEntity?, onSent: () -> Unit, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Sounds") })
                Tab(tab == 1, { tab = 1 }, text = { Text("GIFs") })
            }
            if (tab == 0) SoundsTab(repo, jid, quote, onSent) else GifsTab(repo, jid, quote, onSent)
        }
    }
}

private sealed interface SoundTab {
    data object Favorites : SoundTab
    data object Recent : SoundTab
    data class Web(val source: SoundSource) : SoundTab
}

@Composable
private fun SoundsTab(repo: Repository, jid: String, quote: MessageEntity?, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf<SoundTab>(SoundTab.Web(SoundSource.Morocco)) }
    var query by rememberSaveable { mutableStateOf("") }
    val items = remember { mutableStateListOf<Sound>() }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sending = remember { mutableStateListOf<String>() }
    val favorites by repo.favoriteSounds.collectAsState(emptyList())
    val recent by repo.recentSounds.collectAsState(emptyList())
    val favSet = favorites.map { it.mp3 }.toSet()
    val ps by repo.platform.player.state.collectAsState()
    val grid = rememberLazyGridState()

    suspend fun load(reset: Boolean) {
        val t = tab
        if (t !is SoundTab.Web && query.isBlank()) return
        loading = true
        error = null
        try {
            val p = if (reset) 1 else page + 1
            val res = repo.myInstants.browse((t as? SoundTab.Web)?.source ?: SoundSource.Trending, query, p, repo.platform.now())
            if (reset) items.clear()
            items.addAll(res.items.filter { s -> items.none { it.mp3 == s.mp3 } })
            page = p
            hasMore = res.hasMore
        } catch (t: Throwable) {
            error = "Couldn't reach myinstants"
        }
        loading = false
    }

    LaunchedEffect(tab, query) {
        if (query.isNotBlank()) delay(350)
        load(reset = true)
        grid.scrollToItem(0)
    }
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 6 } == true } }
    LaunchedEffect(nearEnd) {
        if (nearEnd && hasMore && !loading && (tab is SoundTab.Web || query.isNotBlank())) load(reset = false)
    }

    val shown: List<Sound> = when {
        query.isNotBlank() -> items
        tab == SoundTab.Favorites -> favorites.map { Sound(it.title, it.mp3, it.color) }
        tab == SoundTab.Recent -> recent.map { Sound(it.title, it.mp3, it.color) }
        else -> items
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it }, "Search myinstants")
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val tabs = listOf(SoundTab.Favorites, SoundTab.Recent) + SoundSource.entries.map { SoundTab.Web(it) }
            items(tabs) { t ->
                val label = when (t) {
                    SoundTab.Favorites -> "★ Favorites"
                    SoundTab.Recent -> "Recent"
                    is SoundTab.Web -> t.source.label
                }
                FilterChip(selected = tab == t && query.isBlank(), onClick = { tab = t; query = "" }, label = { Text(label) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                state = grid,
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.mp3 }) { s ->
                    SoundButton(
                        s,
                        favorite = s.mp3 in favSet,
                        previewing = ps.key == "preview:${s.mp3}" && ps.playing,
                        sending = s.mp3 in sending,
                        onSend = {
                            if (s.mp3 !in sending) {
                                sending += s.mp3
                                scope.launch {
                                    repo.sendSound(jid, s, quote)
                                    sending -= s.mp3
                                    onSent()
                                }
                            }
                        },
                        onPreview = { repo.platform.player.toggle("preview:${s.mp3}", s.mp3) },
                        onFavorite = { scope.launch { repo.toggleFavorite(s, s.mp3 !in favSet) } },
                    )
                }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                }
            }
            val empty = when {
                error != null -> error
                loading -> null
                shown.isNotEmpty() -> null
                tab == SoundTab.Favorites && query.isBlank() -> "Tap the star on a sound to keep it here."
                tab == SoundTab.Recent && query.isBlank() -> "Sounds you send show up here."
                else -> "No sounds found."
            }
            if (empty != null) Text(
                empty, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).clickable(enabled = error != null) { scope.launch { load(true) } },
            )
        }
    }
}

@Composable
private fun SoundButton(
    s: Sound, favorite: Boolean, previewing: Boolean, sending: Boolean,
    onSend: () -> Unit, onPreview: () -> Unit, onFavorite: () -> Unit,
) {
    val base = s.color?.let { parseHex(it) } ?: colorFor(s.mp3)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp)) {
            Box(
                Modifier
                    .size(58.dp)
                    .align(Alignment.Center)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(lighten(base), base, darken(base))))
                    .border(if (previewing) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .combinedClickable(onClick = onSend, onLongClick = onPreview),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.5.dp)
                else Box(Modifier.size(18.dp).background(Color.White.copy(alpha = 0.35f), CircleShape))
            }
            Icon(
                if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                if (favorite) "Remove favorite" else "Add favorite",
                tint = if (favorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp).align(Alignment.TopEnd).clip(CircleShape).clickable(onClick = onFavorite).padding(2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            s.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
private fun GifsTab(repo: Repository, jid: String, quote: MessageEntity?, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    if (!repo.gifs.configured) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "GIF search needs a GIPHY API key.\nAdd giphy.key=… to app/local.properties and rebuild.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    val gifs = remember { mutableStateListOf<Gif>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyStaggeredGridState()

    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(350)
        loading = true
        error = null
        try {
            val res = if (query.isBlank()) repo.gifs.trending() else repo.gifs.search(query)
            gifs.clear()
            gifs.addAll(res)
            grid.scrollToItem(0)
        } catch (t: Throwable) {
            error = t.message
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it }, "Search GIPHY")
        Box(Modifier.fillMaxSize()) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                state = grid,
                contentPadding = PaddingValues(8.dp),
                verticalItemSpacing = 6.dp,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(gifs, key = { it.id }) { g ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(if (g.w > 0 && g.h > 0) g.w.toFloat() / g.h else 1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(enabled = sending == null) {
                                sending = g.id
                                scope.launch {
                                    repo.sendGif(jid, g, quote)
                                    sending = null
                                    onSent()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(g.preview, g.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (sending == g.id) CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
            if (loading && gifs.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
            error?.let { Text(it, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error) }
            Text(
                "Powered by GIPHY", style = MetaText, fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Icon(Icons.Default.Close, "Clear", Modifier.size(20.dp).clickable { onChange("") })
    }
}

private fun parseHex(s: String): Color? = s.removePrefix("#").toLongOrNull(16)?.let { Color(0xFF000000 or it) }

private fun lighten(c: Color) = Color(c.red + (1 - c.red) * 0.35f, c.green + (1 - c.green) * 0.35f, c.blue + (1 - c.blue) * 0.35f)

private fun darken(c: Color) = Color(c.red * 0.7f, c.green * 0.7f, c.blue * 0.7f)
