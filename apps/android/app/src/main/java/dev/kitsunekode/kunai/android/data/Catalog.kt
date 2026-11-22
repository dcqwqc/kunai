package dev.kitsunekode.kunai.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device catalog. No request depends on Mirai, Philipedia, Moonlight or Termux.
 * AniList and TMDB-compatible discovery return metadata, never assumed streams.
 */
class Catalog(context: Context) {
    private val cacheRoot = File(context.applicationContext.cacheDir, "kunai-metadata")
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Title>>>()
    private val anilist = "https://graphql.anilist.co"
    private val tmdb = "https://db.wingsdatabase.com/3"

    suspend fun browse(kind: Kind, page: Int = 1): List<Title> = withContext(Dispatchers.IO) {
        val key = "browse:${kind.key}:$page"
        cached(key) {
            if (kind == Kind.ANIME) animeCatalog(page) else tmdbCatalog(kind, page)
        }
    }

    suspend fun search(query: String, kinds: Set<Kind>): List<Title> {
        val term = query.trim().take(110)
        if (term.length < 2 || kinds.isEmpty()) return emptyList()
        return coroutineScope {
            kinds.map { kind ->
                async(Dispatchers.IO) {
                    runCatching {
                        if (kind == Kind.ANIME) animeCatalog(1, term)
                        else tmdbCatalog(kind, 1, term)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.kind to it.id }
        }
    }

    suspend fun recommendations(kinds: Set<Kind>): List<Title> = coroutineScope {
        kinds.map { kind -> async { runCatching { browse(kind) }.getOrDefault(emptyList()) } }
            .awaitAll().flatMapIndexed { index, rows -> rows.map { index to it } }
            .groupBy({ it.first }, { it.second }).values
            .let { grouped ->
                val merged = mutableListOf<Title>()
                for (i in 0 until (grouped.maxOfOrNull { it.size } ?: 0)) {
                    grouped.forEach { list -> list.getOrNull(i)?.let(merged::add) }
                }
                merged.distinctBy { it.kind to it.id }
            }
    }

    suspend fun details(title: Title): Title = withContext(Dispatchers.IO) {
        if (title.kind == Kind.ANIME) {
            val query = "query(\$id:Int){Media(id:\$id,type:ANIME){id description(asHtml:false) episodes seasonYear bannerImage coverImage{extraLarge} nextAiringEpisode{airingAt} trailer{id site}}}"
            val media = aniListQuery(query, JSONObject().put("id", title.id.toIntOrNull() ?: -1))
                .getJSONObject("data").optJSONObject("Media") ?: return@withContext title
            title.copy(
                description = clean(media.optString("description")),
                episodeCount = media.optInt("episodes").takeIf { it > 0 } ?: title.episodeCount,
                backdrop = media.optString("bannerImage").takeIf { it.startsWith("https://") } ?: title.backdrop,
                trailerId = media.optJSONObject("trailer")?.let {
                    if (it.optString("site") == "youtube") it.optString("id") else null
                },
            )
        } else {
            val endpoint = if (title.kind == Kind.MOVIE) "movie" else "tv"
            val json = Network.json("$tmdb/$endpoint/${title.id}?language=en-US")
            title.copy(
                description = json.optString("overview", title.description),
                episodeCount = json.optInt("number_of_episodes").takeIf { it > 0 },
                seasonCount = json.optInt("number_of_seasons").takeIf { it > 0 },
            )
        }
    }

    /** Verified official YouTube trailer; only reveal on a real metadata match. */
    suspend fun trailer(title: Title): String? = withContext(Dispatchers.IO) {
        val youtubeId = Regex("[A-Za-z0-9_-]{11}")
        if (title.kind == Kind.ANIME) {
            val id = details(title).trailerId
            return@withContext id?.takeIf { youtubeId.matches(it) }
        }
        val type = if (title.kind == Kind.MOVIE) "movie" else "tv"
        val candidates = mutableListOf<JSONObject>()
        for (language in listOf("en-US", "ja-JP")) {
            val result = runCatching {
                Network.json("$tmdb/$type/${title.id}/videos?language=$language")
                    .optJSONArray("results")
            }.getOrNull() ?: continue
            for (i in 0 until result.length()) {
                val video = result.optJSONObject(i) ?: continue
                if (video.optString("site") == "YouTube" &&
                    video.optString("type") == "Trailer" &&
                    youtubeId.matches(video.optString("key")))
                    candidates.add(video)
            }
            if (candidates.any { it.optBoolean("official") }) break
        }
        candidates.firstOrNull { it.optBoolean("official") }?.optString("key")
            ?: candidates.firstOrNull()?.optString("key")
    }

    suspend fun episodes(title: Title, season: Int = 1): List<Episode> = withContext(Dispatchers.IO) {
        if (title.kind == Kind.MOVIE) return@withContext listOf(Episode(1, title.name))
        if (title.kind == Kind.ANIME) return@withContext listOf()
        val json = Network.json("$tmdb/tv/${title.id}/season/$season?language=en-US")
        val items = json.optJSONArray("episodes") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val n = item.optInt("episode_number")
                if (n < 1) continue
                add(Episode(n, item.optString("name", "Episode $n"), season,
                    photo(item.optString("still_path"), "w500")))
            }
        }
    }

    data class CalendarFeed(val entries: List<Upcoming>, val savedCopy: Boolean)

    /** A week remains available during a temporary AniList outage.
     * Refreshes normally after ten minutes, and displays a saved-copy notice
     * when falling back to a cached result no older than fourteen days.
     */
    suspend fun calendar(
        from: Long,
        until: Long,
        forceRefresh: Boolean = false,
    ): CalendarFeed = withContext(Dispatchers.IO) {
        require(from < until && until - from <= 15 * 86400L)
        val file = File(cacheRoot, "airing-$from-$until.json")
        val now = System.currentTimeMillis()
        val age = now - file.lastModified()
        val saved = runCatching {
            if (!file.isFile || file.length() !in 2..480_000 || age !in 0..1_209_600_000L)
                return@runCatching null
            val json = JSONArray(file.readText(Charsets.UTF_8))
            if (json.length() > 200) return@runCatching null
            buildList<Upcoming> {
                for (i in 0 until json.length()) {
                    val item = json.getJSONObject(i)
                    val title = Library.decode(item.getJSONObject("title")) ?: error("Bad title")
                    val ep = item.getInt("episode")
                    val time = item.getLong("airingAt")
                    check(title.kind == Kind.ANIME && ep in 1..5000 &&
                        time >= from && time < until) { "Invalid saved schedule" }
                    add(Upcoming(title, ep, time))
                }
            }
        }.getOrNull()
        if (!forceRefresh && saved != null && age < 600_000L) {
            return@withContext CalendarFeed(saved, false)
        }
        try {
            val fresh = fetchCalendar(from, until)
            runCatching {
                cacheRoot.mkdirs()
                val json = JSONArray()
                fresh.take(200).forEach { event ->
                    json.put(JSONObject()
                        .put("title", Library.encode(event.title))
                        .put("episode", event.episode)
                        .put("airingAt", event.airingAt))
                }
                val temp = File(cacheRoot, file.name + ".tmp")
                temp.writeText(json.toString(), Charsets.UTF_8)
                if (!temp.renameTo(file)) temp.delete()
            }
            CalendarFeed(fresh, false)
        } catch (e: Exception) {
            if (saved != null) CalendarFeed(saved, true) else throw e
        }
    }

    private fun fetchCalendar(from: Long, until: Long): List<Upcoming> {
        val found = mutableListOf<Upcoming>()
        for (page in 1..4) {
            val query = "query(\$start:Int,\$end:Int){Page(page:$page,perPage:50){pageInfo{hasNextPage} airingSchedules(airingAt_greater: \$start,airingAt_lesser: \$end,sort:TIME){airingAt episode media{id title{english romaji userPreferred} coverImage{large} seasonYear isAdult}}}}"
            val response = aniListQuery(query,
                JSONObject().put("start", from.toInt()).put("end", until.toInt()))
            val pageData = response.optJSONObject("data")?.optJSONObject("Page")
                ?: throw IllegalStateException("Calendar response did not contain a page")
            val rows = pageData.optJSONArray("airingSchedules")
                ?: throw IllegalStateException("Calendar response did not contain a schedule")
            for (i in 0 until rows.length()) {
                val entry = rows.optJSONObject(i) ?: continue
                val m = entry.optJSONObject("media") ?: continue
                if (m.optBoolean("isAdult")) continue
                val ep = entry.optInt("episode")
                val time = entry.optLong("airingAt")
                if (ep > 0 && time >= from && time < until) {
                    found.add(Upcoming(animeTitle(m), ep, time))
                }
            }
            if (pageData.optJSONObject("pageInfo")?.optBoolean("hasNextPage") != true) break
        }
        return found.distinctBy { Triple(it.title.id, it.episode, it.airingAt) }
    }

    private fun aniListQuery(query: String, variables: JSONObject = JSONObject()): JSONObject =
        Network.json(anilist, JSONObject().put("query", query).put("variables", variables))

    private fun animeCatalog(page: Int, term: String = ""): List<Title> {
        val query = if (term.isBlank())
            "query(\$page:Int){Page(page:\$page,perPage:20){media(type:ANIME,sort:TRENDING_DESC,isAdult:false){id title{english romaji userPreferred} coverImage{extraLarge large} bannerImage description(asHtml:false) seasonYear episodes nextAiringEpisode{airingAt}}}}"
        else
            "query(\$page:Int,\$search:String){Page(page:\$page,perPage:20){media(type:ANIME,search:\$search,isAdult:false){id title{english romaji userPreferred} coverImage{extraLarge large} bannerImage description(asHtml:false) seasonYear episodes nextAiringEpisode{airingAt}}}}"
        val vars = JSONObject().put("page", page.coerceIn(1, 10))
        if (term.isNotBlank()) vars.put("search", term)
        val arr = aniListQuery(query, vars).optJSONObject("data")?.optJSONObject("Page")
            ?.optJSONArray("media") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(animeTitle(it)) }
            }
        }
    }

    private fun animeTitle(obj: JSONObject): Title {
        val names = obj.optJSONObject("title") ?: JSONObject()
        val name = listOf("english", "userPreferred", "romaji")
            .map { names.optString(it) }.firstOrNull { it.isNotBlank() } ?: "Untitled"
        val cover = obj.optJSONObject("coverImage")
        return Title(
            id = obj.optInt("id").toString(), kind = Kind.ANIME, name = name,
            year = obj.optInt("seasonYear").takeIf { it in 1900..2200 },
            poster = cover?.optString("extraLarge").takeUnless { it.isNullOrBlank() }
                ?: cover?.optString("large").orEmpty(),
            backdrop = obj.optString("bannerImage").takeIf { it.startsWith("https://") }.orEmpty(),
            description = clean(obj.optString("description")),
            episodeCount = obj.optInt("episodes").takeIf { it > 0 },
            nextAiringAt = obj.optJSONObject("nextAiringEpisode")?.optLong("airingAt")
                ?.takeIf { it > 0 },
        )
    }

    private fun tmdbCatalog(kind: Kind, page: Int = 1, term: String = ""): List<Title> {
        val category = if (kind == Kind.MOVIE) "movie" else "tv"
        val path = if (term.isEmpty()) "/trending/$category/week?language=en-US&page=$page"
            else "/search/$category?query=${encoded(term)}&language=en-US&page=$page"
        val json = Network.json(tmdb + path)
        val arr = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                if (row.optBoolean("adult")) continue
                val id = row.optInt("id")
                val name = row.optString(if (kind == Kind.MOVIE) "title" else "name")
                if (id <= 0 || name.isBlank() || isSpamListing(name)) continue
                val date = row.optString(if (kind == Kind.MOVIE) "release_date" else "first_air_date")
                add(Title(
                    id = id.toString(), kind = kind, name = name,
                    year = date.take(4).toIntOrNull(),
                    poster = photo(row.optString("poster_path"), "w500"),
                    backdrop = photo(row.optString("backdrop_path"), "w780"),
                    description = clean(row.optString("overview")),
                ))
            }
        }
    }

    private fun isSpamListing(name: String): Boolean =
        name.length > 170 ||
        Regex("""(?i)(?:\+?\d[\d ()-]{7,}\d|https?://|www\.|call[- ]?(?:now|us)|ticket[- ]?support)""")
            .containsMatchIn(name)

    private fun photo(path: String, size: String): String =
        if (path.matches(Regex("/[A-Za-z0-9_.-]+"))) "https://image.tmdb.org/t/p/$size$path" else ""

    private fun clean(raw: String) =
        raw.replace(Regex("<[^>]{0,1000}>"), "").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'").trim().take(1200)

    private fun encoded(q: String) = URLEncoder.encode(q, Charsets.UTF_8.name())

    private fun cached(key: String, loader: () -> List<Title>): List<Title> {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.first > now) return it.second }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val file = File(cacheRoot, "$digest.json")
        val disk = runCatching {
            if (!file.isFile || file.length() !in 2..480_000) return@runCatching null
            val json = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until minOf(json.length(), 80)) {
                    json.optJSONObject(i)?.let(Library::decode)?.let(::add)
                }
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
        if (disk != null && now - file.lastModified() in 0..(4 * 60 * 60_000L)) {
            cache[key] = now + 8 * 60_000L to disk
            return disk
        }
        val fresh = try { loader() } catch (e: Exception) {
            if (disk != null) return disk
            throw e
        }
        if (fresh.isEmpty() && disk != null) return disk
        if (fresh.isNotEmpty()) {
            runCatching {
                cacheRoot.mkdirs()
                val json = JSONArray()
                fresh.take(80).forEach { json.put(Library.encode(it)) }
                val temp = File(cacheRoot, "$digest.tmp")
                temp.writeText(json.toString(), Charsets.UTF_8)
                if (!temp.renameTo(file)) temp.delete()
            }
        }
        cache[key] = now + 8 * 60_000L to fresh
        return fresh
    }
}
