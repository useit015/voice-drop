package app.voicedrop.sounds

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess

data class Sound(val title: String, val mp3: String, val color: String? = null)

class SoundPage(val items: List<Sound>, val hasMore: Boolean)

enum class SoundSource(val label: String, val path: String) {
    Morocco("Morocco", "/en/index/ma/"),
    Trending("Trending", "/en/trending/"),
    Best("All-time", "/en/best_of_all_time/"),
    New("New", "/en/recent/"),
}

/** myinstants.com scraping, ported from poc/lib/myinstants.mjs. */
class MyInstants(private val http: HttpClient) {
    private val cache = LinkedHashMap<String, Pair<Long, String>>()

    suspend fun browse(source: SoundSource, query: String = "", page: Int = 1, now: Long): SoundPage {
        val q = query.trim()
        val p = page.coerceIn(1, 50)
        val url = if (q.isNotEmpty()) "$BASE/en/search/?name=${q.encodeURLParameter()}&page=$p"
        else "$BASE${source.path}${if (p > 1) "?page=$p" else ""}"
        val html = html(url, now)
        return SoundPage(parseListing(html), html.contains("page=${p + 1}"))
    }

    private suspend fun html(url: String, now: Long): String {
        cache[url]?.let { (at, html) -> if (now - at < TTL) return html }
        val res = http.get(url) { header("User-Agent", UA) }
        if (res.status.value == 404) return ""
        if (!res.status.isSuccess()) error("myinstants answered ${res.status.value}")
        val html = res.bodyAsText()
        cache[url] = now to html
        if (cache.size > 100) cache.remove(cache.keys.first())
        return html
    }

    companion object {
        const val BASE = "https://www.myinstants.com"
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
        private const val TTL = 5 * 60 * 1000L

        private val PLAY = Regex("""play\('([^']+)'""")
        private val LINK = Regex("""<a href="([^"]+)"[^>]*class="instant-link[^"]*"[^>]*>([^<]*)</a>""")
        private val COLOR = Regex("""background-color:\s*(#[0-9a-fA-F]{6})""")

        fun parseListing(html: String): List<Sound> = html.split("<div class=\"instant\">").drop(1).mapNotNull { chunk ->
            val play = PLAY.find(chunk)?.groupValues?.get(1) ?: return@mapNotNull null
            val link = LINK.find(chunk) ?: return@mapNotNull null
            val mp3 = absolute(decode(play))
            if (!isSoundUrl(mp3)) return@mapNotNull null
            Sound(decode(link.groupValues[2]).trim(), mp3, COLOR.find(chunk)?.groupValues?.get(1))
        }

        /** Only ever fetch audio from myinstants itself. */
        fun isSoundUrl(u: String): Boolean =
            Regex("""^https://(www\.)?myinstants\.com/media/sounds/[^?#]+$""").matches(u)

        private fun absolute(path: String) = if (path.startsWith("http")) path.replace("http://", "https://") else BASE + (if (path.startsWith("/")) "" else "/") + path

        private fun decode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    }
}
