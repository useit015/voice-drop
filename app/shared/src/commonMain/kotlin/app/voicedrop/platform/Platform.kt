package app.voicedrop.platform

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.StateFlow

/** Everything the shared code needs from the OS. Android implementation lives in androidMain. */
interface Platform {
    val filesDir: String
    val cacheDir: String
    val http: HttpClient
    val player: AudioPlayer
    val giphyKey: String

    fun now(): Long
    fun exists(path: String): Boolean
    fun mkdirs(path: String)
    fun delete(path: String)
    fun copy(from: String, to: String)
    fun readText(path: String): String?
    fun writeText(path: String, text: String)

    /** Downloads `url` to `out` (atomically), with a browser user agent. */
    suspend fun download(url: String, out: String)

    /** Any audio file -> WhatsApp voice note: mono Opus in OGG, max 60 s, with a 64-bar waveform. */
    suspend fun encodeVoiceNote(src: String, out: String): VoiceNote

    /** Size, duration and a small JPEG thumbnail for an image or video about to be sent. */
    suspend fun describeMedia(path: String, video: Boolean): MediaInfo

    fun newRecorder(): VoiceRecorder

    fun openExternally(path: String, mime: String?)

    fun qrImage(code: String, size: Int): ImageBitmap

    fun copyText(text: String)
}

class VoiceNote(val path: String, val seconds: Int, val wave: ByteArray)

class MediaInfo(val w: Int, val h: Int, val seconds: Int, val thumbPath: String?)

/** A file the user picked, already copied into app storage. */
class PickedFile(val path: String, val mime: String, val name: String, val size: Long) {
    val isImage get() = mime.startsWith("image/")
    val isVideo get() = mime.startsWith("video/")
}

data class PlayerState(
    val key: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

/** One shared player for voice notes and sound previews, like WhatsApp. */
interface AudioPlayer {
    val state: StateFlow<PlayerState>

    /** Plays `source` (file path or URL) under `key`, or pauses/resumes if `key` is already loaded. */
    fun toggle(key: String, source: String)
    fun seek(key: String, fraction: Float)
    fun cycleSpeed()
    fun stop()
}

interface VoiceRecorder {
    /** 0..1 input level for the live meter. */
    val level: StateFlow<Float>
    val elapsedMs: StateFlow<Long>
    fun start(): Boolean
    suspend fun finish(): VoiceNote?
    fun cancel()
}
