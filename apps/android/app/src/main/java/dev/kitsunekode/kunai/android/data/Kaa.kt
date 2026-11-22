package dev.kitsunekode.kunai.android.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Native KickAssAnime adapter. Port of packages/providers/src/kickassanime's
 * existing JSON + Astro contracts. Executes on the Android device itself.
 * Only exact title/year matches are accepted. No shared backend or JS runtime.
 */
class Kaa {
    private val base = "https://kaa.lt"
    private val cached = ConcurrentHashMap<String, String>()
    private val headers = mapOf("Referer" to "$base/", "Origin" to base)
    private val slugPattern = Regex("[a-z0-9][a-z0-9-]{0,110}")
    private data class Found(val slug: String, val year: Int?, val names: Set<String>)
    private data class Page(val numbers: Set<Int>, val rows: List<Entry>, val pageOf: Map<Int, Int>)
    private data class Entry(val index: Int, val id: String, val name: String, val still: String)
    private data class Server(val name: String, val uri: String)

    private fun request(path: String): JSONObject = Network.json(base + path, headers = headers)
    private fun post(path: String, body: JSONObject): JSONObject =
        Network.json(base + path, body, headers)

    private fun key(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase()
        .replace(Regex("['’`]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun names(json: JSONObject): Set<String> = setOf(
        json.optString("title"), json.optString("title_en")
    ).map(::key).filter { it.isNotEmpty() }.toSet()

    suspend fun find(title: Title): String? = withContext(Dispatchers.IO) {
        title.sourceSlug?.takeIf { slugPattern.matches(it) }?.let { return@withContext it }
        val id = title.id
        cached["${title.kind.key}:$id"]?.let { return@withContext it }
        val wanted = key(title.name)
        if (wanted.length < 3) return@withContext null
        val arr = post("/api/fsearch", JSONObject().put("query", title.name).put("page", 1))
            .optJSONArray("result") ?: return@withContext null
        val matches = buildList {
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val slug = row.optString("slug")
                if (!slugPattern.matches(slug) || wanted !in names(row)) continue
                val year = row.optInt("year").takeIf { it > 0 }
                if (year != null && title.year != null && year != title.year) continue
                if (row.optJSONArray("locales")?.length() == 0 &&
                    row.isNull("watch_uri")) continue
                add(Found(slug, year, names(row)))
            }
        }
        // Ambiguous titles are deliberately not assigned a playable stream.
        matches.singleOrNull()?.slug?.also { cached["${title.kind.key}:$id"] = it }
    }

    suspend fun episodes(title: Title): List<Episode> = withContext(Dispatchers.IO) {
        val slug = find(title) ?: return@withContext emptyList()
        val page = page(slug, 1, "ja-JP")
        val list = page.numbers.sorted()
        list.map { number ->
            val row = page.rows.find { it.index == number }
            Episode(number, row?.name?.ifBlank { "Episode $number" } ?: "Episode $number",
                still = row?.still.orEmpty())
        }
    }

    suspend fun resolve(title: Title, episode: Int): Stream? = withContext(Dispatchers.IO) {
        require(episode in 1..1500)
        val slug = find(title) ?: return@withContext null
        var entry: Entry? = null
        for (lang in listOf("ja-JP", "en-US")) {
            val first = page(slug, 1, lang)
            val targetPage = first.pageOf[episode] ?: continue
            val selected = if (targetPage == 1) first else page(slug, targetPage, lang)
            entry = selected.rows.firstOrNull { it.index == episode }
            if (entry != null) break
        }
        if (entry == null) return@withContext null
        val e = entry!!
        if (!slugPattern.matches(e.id)) return@withContext null
        val payload = request("/api/show/$slug/episode/ep-$episode-${e.id}")
        val arr = payload.optJSONArray("servers") ?: return@withContext null
        val servers = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val link = item.optString("src")
                val name = item.optString("name")
                if (name in setOf("VidStreaming", "CatStream") &&
                    safeVideoUrl(link)) add(Server(name, link))
            }
        }
        for (server in servers) {
            val pageHtml = runCatching {
                Network.text(server.uri, headers = mapOf("Referer" to "$base/"), limit = 1_200_000)
            }.getOrNull() ?: continue
            val parsed = parseAstro(pageHtml) ?: continue
            val origin = Uri.parse(server.uri).let { it.scheme + "://" + it.host }
            val streamHeaders = mapOf(
                "referer" to "$origin/",
                "origin" to origin,
                "user-agent" to "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
            )
            // A source must pass a real manifest probe before it is playable.
            val master = runCatching {
                Network.text(parsed.first, headers = streamHeaders, limit = 230_000)
            }.getOrNull()
            if (!master.orEmpty().startsWith("#EXTM3U")) continue
            return@withContext Stream(parsed.first, streamHeaders, parsed.second, server.name)
        }
        null
    }

    private fun page(slug: String, number: Int, locale: String): Page {
        require(slugPattern.matches(slug) && number in 1..100)
        val body = request("/api/show/$slug/episodes?ep=$number&lang=$locale")
        val pages = body.optJSONArray("pages") ?: JSONArray()
        val index = linkedMapOf<Int, Int>()
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: continue
            val p = page.optInt("number")
            if (p !in 1..100) continue
            val eps = page.optJSONArray("eps") ?: continue
            for (j in 0 until eps.length()) {
                val n = eps.optInt(j)
                if (n in 1..1500) index[n] = p
            }
        }
        val arr = body.optJSONArray("result") ?: JSONArray()
        val rows = buildList {
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val n = row.optInt("episode_number")
                val slugId = row.optString("slug")
                if (n !in 1..1500 || !slugPattern.matches(slugId)) continue
                val posterKey = row.optJSONObject("thumbnail")?.optString("hq").orEmpty()
                val image = if (posterKey.matches(Regex("[a-zA-Z0-9/_-]{1,260}")))
                    "$base/image/thumbnail/$posterKey.webp" else ""
                add(Entry(n, slugId, row.optString("title", "Episode $n"), image))
            }
        }
        if (index.isEmpty()) rows.forEach { index[it.index] = number }
        return Page(index.keys, rows, index)
    }

    private fun safeVideoUrl(raw: String): Boolean {
        val u = Uri.parse(raw)
        val host = u.host.orEmpty().lowercase()
        return u.scheme == "https" && host.isNotEmpty() && u.userInfo == null &&
            host != "localhost" && host != "127.0.0.1" && !host.endsWith(".local") &&
            !host.startsWith("192.168.") && !host.startsWith("10.") &&
            !host.matches(Regex("(172\\.(1[6-9]|2[0-9]|3[01]))\\..*"))
    }

    private fun normalizeVideoUrl(raw: String): String {
        val value = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("https:////") -> "https://" + raw.removePrefix("https:////")
            else -> raw
        }
        return value.takeIf(::safeVideoUrl).orEmpty()
    }

    /**
     * Astro props are tagged tuples: [0,value] and [1,[items]].
     * Bound nested recursion and reject malformed/untrusted hosts.
     */
    private fun decode(value: Any?, level: Int = 0): Any? {
        if (level > 15) return null
        if (value is JSONArray && value.length() == 2 &&
            value.optInt(0, -1) in 0..1) {
            val payload = value.opt(1)
            if (value.optInt(0) == 1 && payload is JSONArray) {
                val out = JSONArray()
                for (i in 0 until minOf(payload.length(), 80)) out.put(decode(payload.opt(i), level + 1))
                return out
            }
            return decode(payload, level + 1)
        }
        if (value is JSONObject) {
            val out = JSONObject()
            val names = value.keys()
            var count = 0
            while (names.hasNext() && count++ < 60) {
                val k = names.next()
                out.put(k, decode(value.opt(k), level + 1))
            }
            return out
        }
        return value
    }

    private fun parseAstro(html: String): Pair<String, List<Subtitle>>? {
        var from = 0
        repeat(32) {
            val start = html.indexOf("<astro-island", from, ignoreCase = true)
            if (start < 0) return null
            var end = start
            var quoted = false
            while (end < html.length && end - start < 600_000) {
                val ch = html[end]
                if (ch == '"') quoted = !quoted
                if (ch == '>' && !quoted) break
                end++
            }
            if (end >= html.length) return null
            val tag = html.substring(start, end)
            from = end + 1
            val marker = "props=\""
            val begin = tag.indexOf(marker)
            if (begin < 0) return@repeat
            val startValue = begin + marker.length
            val finish = tag.indexOf('"', startValue)
            if (finish < 0) return@repeat
            val encoded = tag.substring(startValue, finish)
            if (!encoded.contains("manifest")) return@repeat
            val raw = encoded.replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            val props = runCatching { JSONObject(raw) }.getOrNull() ?: return@repeat
            val manifest = normalizeVideoUrl((decode(props.opt("manifest")) as? String).orEmpty())
            if (manifest.isEmpty()) return@repeat
            val subs = decode(props.opt("subtitles")) as? JSONArray ?: JSONArray()
            val parsed = buildList {
                for (i in 0 until subs.length()) {
                    val obj = subs.optJSONObject(i) ?: continue
                    val uri = normalizeVideoUrl(obj.optString("src"))
                    val lang = obj.optString("language")
                    if (uri.isBlank() || lang.isBlank()) continue
                    add(Subtitle(uri, lang, obj.optString("name", lang)))
                }
            }
            return manifest to parsed
        }
        return null
    }
}
