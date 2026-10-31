package app.voicedrop.sounds

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A GIF as WhatsApp sends it: an MP4 played muted on loop. */
data class Gif(val id: String, val title: String, val preview: String, val mp4: String, val w: Int, val h: Int)

interface GifProvider {
    val configured: Boolean
    suspend fun trending(offset: Int = 0): List<Gif>
    suspend fun search(q: String, offset: Int = 0): List<Gif>
}

/** GIPHY. The key comes from app/local.properties (giphy.key=...). */
class Giphy(private val http: HttpClient, private val key: String) : GifProvider {
    override val configured get() = key.isNotBlank()

    override suspend fun trending(offset: Int) = fetch("trending", "")

    override suspend fun search(q: String, offset: Int) = fetch("search", "&q=${q.encodeURLParameter()}&offset=$offset")

    private suspend fun fetch(endpoint: String, extra: String): List<Gif> {
        if (!configured) return emptyList()
        val res = http.get("https://api.giphy.com/v1/gifs/$endpoint?api_key=$key&limit=36&rating=pg-13&bundle=messaging_non_clips$extra")
        if (!res.status.isSuccess()) error("GIPHY answered ${res.status.value}")
        return json.decodeFromString<Resp>(res.bodyAsText()).data.mapNotNull { g ->
            val send = g.images.downsized_small?.mp4 ?: g.images.original_mp4?.mp4 ?: return@mapNotNull null
            val prev = g.images.fixed_width?.webp ?: g.images.fixed_width?.url ?: return@mapNotNull null
            val size = g.images.original_mp4 ?: g.images.downsized_small
            Gif(g.id, g.title, prev, send, size?.width?.toIntOrNull() ?: 0, size?.height?.toIntOrNull() ?: 0)
        }
    }

    @Serializable private class Resp(val data: List<G> = emptyList())
    @Serializable private class G(val id: String, val title: String = "", val images: Images)
    @Serializable @Suppress("PropertyName") private class Images(
        val fixed_width: R? = null,
        val downsized_small: R? = null,
        val original_mp4: R? = null,
    )
    @Serializable private class R(val url: String? = null, val webp: String? = null, val mp4: String? = null, val width: String? = null, val height: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
