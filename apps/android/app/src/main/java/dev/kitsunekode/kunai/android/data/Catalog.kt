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
    private val franchiseCache = ConcurrentHashMap<String, Pair<Long, List<FranchiseRelease>>>()
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

    data class FranchiseRelease(
        val title: Title, val format: String, val releaseDate: Int,
        val malId: Int? = null, val status: String = "",
    )

    /** AniList relation graph, not guessed title-prefix matching. Follow the direct
     * prequel/sequel and side-story links within a strict API budget. Movie and
     * season entries keep their own IDs and playback checkpoints.
     */
    suspend fun franchise(title: Title): List<FranchiseRelease> = withContext(Dispatchers.IO) {
        if (title.kind != Kind.ANIME || title.id.toIntOrNull() == null) return@withContext emptyList()
        franchiseCache[title.id]?.let { (loaded, rows) ->
            if (System.currentTimeMillis() - loaded in 0..1_800_000L)
                return@withContext rows
        }
        val discovered = linkedMapOf<String, FranchiseRelease>()
        val visited = hashSetOf<String>()
        val waiting = ArrayDeque<String>()
        waiting.add(title.id)
        val query = "query(\$id:Int){Media(id:\$id,type:ANIME){id idMal status type isAdult format seasonYear startDate{year month day} title{english romaji userPreferred} coverImage{extraLarge large} relations{edges{relationType node{id idMal status type isAdult format seasonYear startDate{year month day} title{english romaji userPreferred} coverImage{extraLarge large}}}}}}"
        fun record(media: JSONObject) {
            val id = media.optInt("id").toString()
            if (media.optString("type") != "ANIME" || media.optBoolean("isAdult") ||
                id == "0") return
            val item = animeTitle(media)
            if (CatalogIdentity.readable(item.name) == null || item.name == "Untitled") return
            val format = CatalogIdentity.readable(media.optString("format")) ?: "TV"
            val date = media.optJSONObject("startDate")
            val y = date?.optInt("year")?.takeIf { it in 1900..2200 }
                ?: item.year ?: 9999
            val m = date?.optInt("month")?.takeIf { it in 1..12 } ?: 12
            val d = date?.optInt("day")?.takeIf { it in 1..31 } ?: 31
            discovered[id] = FranchiseRelease(
                item, format, y * 10_000 + m * 100 + d,
                media.optInt("idMal").takeIf { it > 0 },
                media.optString("status"),
            )
        }
        var calls = 0
        while (waiting.isNotEmpty() && calls < 8 && discovered.size < 28) {
            val id = waiting.removeFirst()
            if (!visited.add(id)) continue
            calls++
            val media = runCatching {
                aniListQuery(query, JSONObject().put("id", id.toInt()))
                    .optJSONObject("data")?.optJSONObject("Media")
            }.getOrNull() ?: continue
            record(media)
            val edges = media.optJSONObject("relations")?.optJSONArray("edges") ?: continue
            for (i in 0 until edges.length()) {
                val edge = edges.optJSONObject(i) ?: continue
                if (edge.optString("relationType") !in setOf(
                        "PREQUEL", "SEQUEL", "SIDE_STORY", "SPIN_OFF", "PARENT", "ALTERNATIVE"))
                    continue
                val node = edge.optJSONObject("node") ?: continue
                if (node.optString("type") != "ANIME" || node.optBoolean("isAdult")) continue
                val nextId = node.optInt("id").toString()
                if (nextId == "0") continue
                record(node)
                if (nextId !in visited && nextId !in waiting && waiting.size < 24)
                    waiting.add(nextId)
            }
        }
        // AniList does not always create a relation edge for a movie.
        // Supplement an already verified franchise with conservative exact
        // title-prefix search. This never changes stream IDs or progress.
        val movieRoot = CatalogIdentity.rootFromMovie(title.name)
        if (discovered.size > 1 || movieRoot != null) {
            val root = movieRoot ?: discovered.values
                .filter { it.format == "TV" }
                .minByOrNull { it.releaseDate }?.title?.name
                ?: discovered.values.minByOrNull { it.releaseDate }?.title?.name
            if (root != null && root.length >= 12) {
                val searchQuery = "query(\$term:String){Page(page:1,perPage:50){media(type:ANIME,search:\$term,isAdult:false){id idMal status type isAdult format seasonYear startDate{year month day} title{english romaji userPreferred} coverImage{extraLarge large}}}}"
                val results = runCatching {
                    aniListQuery(searchQuery, JSONObject().put("term",root))
                        .optJSONObject("data")?.optJSONObject("Page")
                        ?.optJSONArray("media")
                }.getOrNull()
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val names = item.optJSONObject("title") ?: continue
                        val candidates = listOf("english", "userPreferred", "romaji")
                            .mapNotNull { CatalogIdentity.readable(names.optString(it)) }
                        if (candidates.any { CatalogIdentity.sameReleaseFamily(root,it) })
                            record(item)
                    }
                }
            }
        }
        val rows = discovered.values.sortedWith(
            compareBy<FranchiseRelease> { it.releaseDate }.thenBy { it.title.name })
        val result = if (rows.size > 1) rows else emptyList()
        val loadedAt = System.currentTimeMillis()
        // Navigating to a related season should keep the same group and order.
        for (entry in result) franchiseCache[entry.title.id] = loadedAt to result
        franchiseCache[title.id] = loadedAt to result
        result
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
            "query(\$page:Int){Page(page:\$page,perPage:20){media(type:ANIME,sort:TRENDING_DESC,isAdult:false){id title{english romaji userPreferred} coverImage{extraLarge large} bannerImage description(asHtml:false) seasonYear episodes nextAiringEpisode{airingAt} relations{edges{relationType node{id type isAdult}}}}}}"
        else
            "query(\$page:Int,\$search:String){Page(page:\$page,perPage:20){media(type:ANIME,search:\$search,isAdult:false){id title{english romaji userPreferred} coverImage{extraLarge large} bannerImage description(asHtml:false) seasonYear episodes nextAiringEpisode{airingAt}}}}"
        val vars = JSONObject().put("page", page.coerceIn(1, 10))
        if (term.isNotBlank()) vars.put("search", term)
        val arr = aniListQuery(query, vars).optJSONObject("data")?.optJSONObject("Page")
            ?.optJSONArray("media") ?: return emptyList()
        val releases = mutableListOf<Title>()
        val relations = linkedMapOf<String, MutableSet<String>>()
        for (i in 0 until arr.length()) {
            val anime = arr.optJSONObject(i) ?: continue
            val item = animeTitle(anime)
            releases.add(item)
            // Keep search results literal so a request for Season 4 still
            // shows Season 4 instead of redirecting to the original series.
            if (term.isNotBlank()) continue
            val edges = anime.optJSONObject("relations")?.optJSONArray("edges") ?: continue
            val relatives = relations.getOrPut(item.id) { linkedSetOf() }
            for (index in 0 until edges.length()) {
                val edge = edges.optJSONObject(index) ?: continue
                if (edge.optString("relationType") !in setOf(
                        "PREQUEL", "SEQUEL", "SIDE_STORY", "SPIN_OFF", "PARENT"))
                    continue
                val node = edge.optJSONObject("node") ?: continue
                if (node.optString("type") != "ANIME" || node.optBoolean("isAdult")) continue
                val otherId = node.optInt("id")
                if (otherId > 0) relatives.add(otherId.toString())
            }
        }
        return if (term.isBlank()) CatalogIdentity.collapseRelated(releases,relations)
            else CatalogIdentity.distinct(releases)
    }

    private fun animeTitle(obj: JSONObject): Title {
        val names = obj.optJSONObject("title") ?: JSONObject()
        val name = listOf("english", "userPreferred", "romaji")
            .firstNotNullOfOrNull { key ->
                if (names.isNull(key)) null else CatalogIdentity.readable(names.optString(key))
            } ?: "Untitled"
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
