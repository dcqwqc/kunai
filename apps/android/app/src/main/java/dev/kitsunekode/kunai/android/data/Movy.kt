package dev.kitsunekode.kunai.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Kotlin/Android port of the existing Movy direct-provider contract:
 * seed -> per-lane encrypted source list -> mvm1 verification -> stream probe.
 * No desktop runtime, Node, relay service or private IP is needed.
 */
class Movy {
    private val api = "https://api.wecollege.net"
    private val referer = "https://www.movy.sx/"
    private val origin = "https://www.movy.sx"
    private val agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val headers = mapOf("User-Agent" to agent, "Referer" to referer, "Origin" to origin)
    private val lanes = listOf("miami", "seattle", "paris", "denver", "atlanta", "phoenix")
    private val videoHeaders = mapOf("referer" to referer, "user-agent" to agent)

    suspend fun resolve(title: Title, season: Int, episode: Int): Stream? =
        withContext(Dispatchers.IO) {
            require(title.kind == Kind.MOVIE || title.kind == Kind.SERIES)
            val id = title.id.toIntOrNull()?.takeIf { it > 0 } ?: return@withContext null
            val year = title.year?.toString().orEmpty()
            val mediaType = if (title.kind == Kind.MOVIE) "movie" else "tv"
            val params = linkedMapOf(
                "title" to encode(title.name), // Site explicitly double-encodes titles
                "mediaType" to mediaType,
                "year" to year,
                "totalSeasons" to "1",
                "seasonId" to season.toString(),
                "episodeId" to episode.toString(),
                "tmdbId" to id.toString(),
                "imdbId" to "",
                "enc" to "2",
            )
            val chosen = withTimeoutOrNull(35_000) {
                for (lane in lanes) {
                    val candidates = runCatching {
                        sources(lane, id, params)
                    }.getOrNull() ?: continue
                    val items = candidates.optJSONArray("sources") ?: JSONArray()
                    val subtitleRows = candidates.optJSONArray("subtitles") ?: JSONArray()
                    val subtitles = buildList {
                        for (i in 0 until subtitleRows.length()) {
                            val sub = subtitleRows.optJSONObject(i) ?: continue
                            val uri = sub.optString("url").ifBlank { sub.optString("file") }
                            if (!publicHttps(uri)) continue
                            val rawLang = sub.optString("lang").ifBlank {
                                sub.optString("language", "en")
                            }
                            add(Subtitle(uri, rawLang.take(15), sub.optString("label", rawLang)))
                        }
                    }
                    for (i in 0 until items.length()) {
                        val stream = items.optJSONObject(i) ?: continue
                        val url = stream.optString("url")
                        if (!publicHttps(url)) continue
                        val type = stream.optString("type").lowercase()
                        val valid = runCatching {
                            probe(url, type)
                        }.getOrDefault(false)
                        if (!valid) continue
                        return@withTimeoutOrNull Stream(url, videoHeaders, subtitles, "Movy $lane")
                    }
                }
                null
            }
            chosen
        }

    private fun sources(lane: String, id: Int, params: Map<String, String>): JSONObject {
        require(lane in lanes)
        repeat(2) {
            val seed = Network.json("$api/seed?mediaId=$id", headers = headers)
                .optString("seed")
            require(seed.isNotBlank() && seed.length <= 240)
            val values = params + mapOf("seed" to seed)
            val query = values.entries.joinToString("&") {
                "${encode(it.key)}=${encode(it.value)}"
            }
            val body = Network.text("$api/$lane/sources?$query",
                headers = headers, limit = 300_000).trim()
            val decoded = runCatching { MovyCrypto.decrypt(body, seed, id) }.getOrNull()
            if (decoded != null) return JSONObject(decoded)
        }
        error("Source verification failed")
    }

    private fun probe(url: String, type: String): Boolean {
        if (!publicHttps(url)) return false
        val lower = url.substringBefore("?").lowercase()
        val manifest = type == "hls" || lower.endsWith(".m3u8") ||
            type == "dash" || lower.endsWith(".mpd")
        if (manifest) {
            val result = Network.text(url, headers = videoHeaders, limit = 230_000)
            return if (type == "dash" || lower.endsWith(".mpd"))
                result.contains("<MPD")
            else result.trimStart().startsWith("#EXTM3U")
        }
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.connectTimeout = 5500
            conn.readTimeout = 7000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-1023")
            videoHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            if (conn.responseCode !in listOf(200, 206)) return false
            val typ = conn.contentType?.lowercase().orEmpty()
            if (typ.contains("text/html") || typ.contains("application/json")) return false
            val first = ByteArray(24)
            val n = conn.inputStream.use { it.read(first) }
            if (n < 4) return false
            // MP4 'ftyp', Matroska magic, or known binary video MIME.
            return (n > 7 && String(first, 4, 4) == "ftyp") ||
                (first[0] == 0x1a.toByte() && first[1] == 0x45.toByte()) ||
                typ.startsWith("video/")
        } finally {
            conn.disconnect()
        }
    }

    private fun publicHttps(value: String): Boolean {
        val uri = android.net.Uri.parse(value)
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" || host.isBlank() || uri.userInfo != null ||
            uri.port !in listOf(-1, 443)) return false
        return !host.equals("localhost") && !host.endsWith(".local") &&
            !host.matches(Regex("(localhost|127\\..*|10\\..*|192\\.168\\..*|169\\.254\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*)"))
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
