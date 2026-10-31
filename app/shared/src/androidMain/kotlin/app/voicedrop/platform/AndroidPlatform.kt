package app.voicedrop.platform

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioRecord
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.voicedrop.sounds.MyInstants
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

class AndroidPlatform(private val ctx: Context, override val giphyKey: String) : Platform {
    override val filesDir: String = ctx.filesDir.absolutePath
    override val cacheDir: String = ctx.cacheDir.absolutePath

    override val http = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
        }
    }

    override val player: AudioPlayer by lazy { ExoAudioPlayer(ctx) }

    override fun now() = System.currentTimeMillis()
    override fun exists(path: String) = File(path).exists()
    override fun mkdirs(path: String) {
        File(path).mkdirs()
    }
    override fun delete(path: String) {
        File(path).delete()
    }
    override fun copy(from: String, to: String) {
        File(from).copyTo(File(to), overwrite = true)
    }
    override fun readText(path: String) = File(path).takeIf { it.exists() }?.readText()
    override fun writeText(path: String, text: String) = File(path).writeText(text)

    override suspend fun download(url: String, out: String) {
        val res = http.get(url) { header("User-Agent", MyInstants.UA) }
        if (!res.status.isSuccess()) error("download failed (${res.status.value})")
        val bytes = res.bodyAsBytes()
        withContext(Dispatchers.IO) {
            val tmp = File("$out.part")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(out))
        }
    }

    override suspend fun encodeVoiceNote(src: String, out: String): VoiceNote = withContext(Dispatchers.Default) {
        Opus.encode(Opus.decode(src, 60), out, 60)
    }

    override suspend fun describeMedia(path: String, video: Boolean): MediaInfo = withContext(Dispatchers.IO) {
        val thumbDir = File(cacheDir, "thumbs").apply { mkdirs() }
        val thumb = File(thumbDir, "${File(path).nameWithoutExtension}_${now()}.jpg")
        if (video) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) w = h.also { h = w }
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val frame = r.getFrameAtTime(0)
                val saved = frame?.let { saveThumb(it, thumb) } == true
                MediaInfo(w, h, ((ms + 500) / 1000).toInt(), thumb.absolutePath.takeIf { saved })
            } finally {
                r.release()
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var w = o.outWidth
            var h = o.outHeight
            val rot = runCatching {
                when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }.getOrDefault(0)
            if (rot == 90 || rot == 270) w = h.also { h = w }
            val sample = Integer.highestOneBit(max(1, max(o.outWidth, o.outHeight) / 192))
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            val rotated = if (bmp != null && rot != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else bmp
            val saved = rotated?.let { saveThumb(it, thumb) } == true
            MediaInfo(w, h, 0, thumb.absolutePath.takeIf { saved })
        }
    }

    private fun saveThumb(src: Bitmap, out: File): Boolean {
        val scale = 96f / max(src.width, src.height)
        val b = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        return FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.JPEG, 70, it) }
    }

    override fun newRecorder(): VoiceRecorder = PcmRecorder(File(cacheDir, "rec").apply { mkdirs() }, File(filesDir, "media"))

    override fun openExternally(path: String, mime: String?) {
        val f = File(path)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val type = mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun qrImage(code: String, size: Int): ImageBitmap {
        val m = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { if (m[it % size, it / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    override fun copyText(text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
    }
}

/** One ExoPlayer for every voice note and sound preview. Main thread only. */
private class ExoAudioPlayer(ctx: Context) : AudioPlayer {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state
    private val main = Handler(Looper.getMainLooper())
    private val speeds = floatArrayOf(1f, 1.5f, 2f)

    private val exo: ExoPlayer = ExoPlayer.Builder(ctx).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
                if (isPlaying) main.post(tick)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    seekTo(0)
                    pause()
                    _state.value = _state.value.copy(playing = false, positionMs = 0)
                } else publish()
            }
        })
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            publish()
            if (exo.isPlaying) main.postDelayed(this, 50)
        }
    }

    private fun publish() {
        _state.value = _state.value.copy(
            playing = exo.isPlaying,
            positionMs = exo.currentPosition.coerceAtLeast(0),
            durationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0,
        )
    }

    override fun toggle(key: String, source: String) {
        if (_state.value.key == key && exo.playbackState != Player.STATE_IDLE) {
            if (exo.isPlaying) exo.pause() else exo.play()
            return
        }
        val uri = if (source.startsWith("http")) source else File(source).toURI().toString()
        _state.value = PlayerState(key = key, speed = _state.value.speed)
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.playbackParameters = PlaybackParameters(_state.value.speed)
        exo.prepare()
        exo.play()
    }

    override fun seek(key: String, fraction: Float) {
        if (_state.value.key != key) return
        val d = exo.duration.takeIf { it != C.TIME_UNSET } ?: return
        exo.seekTo((d * fraction.coerceIn(0f, 1f)).toLong())
        publish()
    }

    override fun cycleSpeed() {
        val next = speeds[(speeds.indexOfFirst { it == _state.value.speed } + 1) % speeds.size]
        exo.playbackParameters = PlaybackParameters(next)
        _state.value = _state.value.copy(speed = next)
    }

    override fun stop() {
        exo.stop()
        _state.value = PlayerState(speed = _state.value.speed)
    }
}

/** Records 16-bit mono PCM to a temp file, then encodes it to an OGG/Opus voice note. */
private class PcmRecorder(private val tmpDir: File, private val outDir: File) : VoiceRecorder {
    override val level = MutableStateFlow(0f)
    override val elapsedMs = MutableStateFlow(0L)
    private var rec: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var rate = OPUS_RATE
    private val pcmFile = File(tmpDir, "voice.pcm")

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (running) return true
        val r: AudioRecord = listOf(48_000, 44_100, 16_000).firstNotNullOfOrNull { hz: Int ->
            val min = AudioRecord.getMinBufferSize(hz, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return@firstNotNullOfOrNull null
            runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, hz, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, max(min, hz / 5))
            }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }?.also { rate = hz }
        } ?: return false
        rec = r
        running = true
        level.value = 0f
        elapsedMs.value = 0
        r.startRecording()
        worker = thread(name = "voice-rec") {
            val buf = ShortArray(rate / 20) // 50 ms
            val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var samples = 0L
            BufferedOutputStream(FileOutputStream(pcmFile)).use { out ->
                while (running && samples < rate.toLong() * MAX_SECONDS) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    bytes.clear()
                    var sum = 0.0
                    for (i in 0 until n) {
                        bytes.putShort(buf[i])
                        val s = buf[i] / 32768.0
                        sum += s * s
                    }
                    out.write(bytes.array(), 0, n * 2)
                    samples += n
                    level.value = (sqrt(sum / n) * 4).toFloat().coerceIn(0f, 1f)
                    elapsedMs.value = samples * 1000 / rate
                }
            }
        }
        return true
    }

    private fun halt() {
        running = false
        worker?.join(2_000)
        worker = null
        rec?.runCatching { stop() }
        rec?.release()
        rec = null
    }

    override suspend fun finish(): VoiceNote? = withContext(Dispatchers.Default) {
        halt()
        if (elapsedMs.value < 700) {
            pcmFile.delete()
            return@withContext null
        }
        outDir.mkdirs()
        val out = File(outDir, "voice_${System.currentTimeMillis()}.ogg").absolutePath
        try {
            DataInputStream(pcmFile.inputStream().buffered()).use { input ->
                val bytes = ByteArray(16_384)
                val src = object : PcmSource {
                    override val rate = this@PcmRecorder.rate
                    override fun read(dst: ShortArray, max: Int): Int {
                        val want = minOf(max, dst.size, bytes.size / 2) * 2
                        var got = 0
                        while (got < want) {
                            val r = input.read(bytes, got, want - got)
                            if (r < 0) break
                            got += r
                        }
                        if (got < 2) return -1
                        val bb = ByteBuffer.wrap(bytes, 0, got).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val n = got / 2
                        bb.get(dst, 0, n)
                        return n
                    }
                }
                Opus.encode(src, out, MAX_SECONDS)
            }
        } finally {
            pcmFile.delete()
        }
    }

    override fun cancel() {
        halt()
        pcmFile.delete()
        level.value = 0f
        elapsedMs.value = 0
    }

    companion object {
        const val MAX_SECONDS = 15 * 60
    }
}
