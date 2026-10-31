package app.voicedrop.platform

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
actual fun rememberPickers(dir: String, onPicked: (List<PickedFile>) -> Unit): Pickers {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun copyAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                File(dir).mkdirs()
                uris.mapNotNull { uri ->
                    runCatching {
                        val cr = ctx.contentResolver
                        var name = "file"
                        var size = 0L
                        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                name = c.getString(0) ?: name
                                size = c.getLong(1)
                            }
                        }
                        val mime = cr.getType(uri) ?: "application/octet-stream"
                        val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..5 }
                            ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                        val out = File(dir, "pick_${System.nanoTime()}.$ext")
                        cr.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                        PickedFile(out.absolutePath, mime, name, if (size > 0) size else out.length())
                    }.getOrNull()
                }
            }
            onPicked(files)
        }
    }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { copyAll(it) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { copyAll(it) }
    val pending = remember { arrayOfNulls<File>(1) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending[0] ?: return@rememberLauncherForActivityResult
        if (ok && f.length() > 0) onPicked(listOf(PickedFile(f.absolutePath, "image/jpeg", f.name, f.length())))
        else f.delete()
    }
    return remember(dir) {
        Pickers(
            media = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            documents = { docs.launch(arrayOf("*/*")) },
            camera = {
                File(dir).mkdirs()
                val f = File(dir, "camera_${System.currentTimeMillis()}.jpg")
                pending[0] = f
                camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
            },
        )
    }
}

@Composable
actual fun rememberMicPermission(): () -> Boolean {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember {
        {
            val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!ok) launcher.launch(Manifest.permission.RECORD_AUDIO)
            ok
        }
    }
}

@Composable
actual fun VideoPlayer(path: String, modifier: Modifier, gif: Boolean) {
    val ctx = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            repeatMode = if (gif) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            volume = if (gif) 0f else 1f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
        modifier = modifier,
    )
}

@Composable
actual fun dynamicScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val ctx = LocalContext.current
    return if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
}

private val zone get() = ZoneId.systemDefault()
private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

actual fun clockTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(timeFmt)

actual fun sameDay(a: Long, b: Long) = date(a) == date(b)

actual fun dayLabel(ms: Long, now: Long): String {
    val d = date(ms)
    val today = date(now)
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today.minusDays(7)) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        d.year == today.year -> d.format(DateTimeFormatter.ofPattern("d MMMM"))
        else -> d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
    }
}

actual fun listTime(ms: Long, now: Long): String {
    val d: LocalDate = date(ms)
    val today = date(now)
    return when {
        d == today -> clockTime(ms)
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today.minusDays(7)) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
    }
}
