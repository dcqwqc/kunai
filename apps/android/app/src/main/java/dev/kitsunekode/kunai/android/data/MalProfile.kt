package dev.kitsunekode.kunai.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.zip.GZIPInputStream

data class WatchedSeason(val season: Int, val episodes: List<Int>) {
    val count: Int get() = episodes.size
    fun contains(episode: Int): Boolean = episode in episodes
    fun label(): String = (if (season == 0) "Specials" else "S$season") +
        ": " + compactEpisodes(episodes)
}

fun compactEpisodes(episodes: List<Int>): String {
    val ordered = episodes.filter { it > 0 }.distinct().sorted()
    if (ordered.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var start = ordered.first()
    var end = start
    fun flush() {
        parts += if (start == end) "E$start" else "E$start–$end"
    }
    for (episode in ordered.drop(1)) {
        if (episode == end + 1) end = episode
        else {
            flush()
            start = episode
            end = episode
        }
    }
    flush()
    return parts.joinToString(", ")
}

data class MalEntry(
    val malId: Int,
    val title: Title,
    val status: String,
    val watched: Int,
    val episodes: Int,
    val score: Int,
    val updatedAt: Long,
    val subscribed: Boolean,
    val watchlist: Boolean,
    val aniworld: List<WatchedSeason> = emptyList(),
)

data class MalSuggestion(val title: Title, val reason: String, val malId: Int)
data class MalSnapshot(
    val account: String,
    val entries: List<MalEntry>,
    val suggestions: List<MalSuggestion>,
    val syncedAt: String,
) {
    val watching get() = entries.filter { it.status == "Watching" }
        .sortedWith(compareByDescending<MalEntry> { it.updatedAt }.thenBy { it.title.name })
    val planned get() = entries.filter { it.status == "Plan to Watch" }.sortedBy { it.title.name }
    val completed get() = entries.filter { it.status == "Completed" }.sortedBy { it.title.name }
    val subscribed get() = entries.filter { it.subscribed }.sortedBy { it.title.name }
    val watchlist get() = entries.filter { it.watchlist }.sortedBy { it.title.name }
    companion object { val EMPTY = MalSnapshot("", emptyList(), emptyList(), "") }
}

/** Standalone cached MAL library. Reads public MAL data directly over HTTPS.
 * The optional per-user bootstrap provides offline first-run content, not credentials.
 * Local player positions remain in Library and are never replaced.
 */
class MalProfile(context: Context) {
    private val app = context.applicationContext
    private val cache = File(app.filesDir, "mal-profile-cache.json")
    private val accountPattern = Regex("[A-Za-z0-9_-]{2,40}")

    fun cached(): MalSnapshot {
        val bootstrap = runCatching {
            val source = runCatching {
                // Android's packager inflates .gz assets and removes the suffix.
                app.assets.open("kunai-personal.json").bufferedReader().use { it.readText() }
            }.getOrElse {
                app.assets.open("kunai-personal.json.gz").use { input ->
                    GZIPInputStream(input).bufferedReader().use { it.readText() }
                }
            }
            decode(JSONObject(source))
        }.getOrDefault(MalSnapshot.EMPTY)
        val local = runCatching { decode(JSONObject(cache.readText())) }.getOrNull()
            ?: return bootstrap
        if (bootstrap.account != local.account) return local
        // Repair identifiers from pre-0.1.5 caches, without losing newer MAL
        // status/score updates or device-local watch positions.
        val originals = bootstrap.suggestions.associateBy { it.title.id }
        val owned = local.entries.mapTo(HashSet()) { it.malId }
        val episodeHistory = bootstrap.entries.associateBy { it.malId }
        val bootstrapIsNewer = runCatching {
            Instant.parse(bootstrap.syncedAt).isAfter(Instant.parse(local.syncedAt))
        }.getOrDefault(false)
        return local.copy(
            entries = local.entries.map { cached ->
                val fromSource = episodeHistory[cached.malId]
                val exact = fromSource?.aniworld.orEmpty()
                val withHistory = cached.copy(
                    aniworld = if (exact.isNotEmpty()) exact else cached.aniworld)
                // A newer authenticated offline snapshot takes precedence over
                // an older public MAL fetch, even before network connectivity.
                if (bootstrapIsNewer && fromSource != null) withHistory.copy(
                    status = fromSource.status,
                    watched = fromSource.watched,
                    score = fromSource.score,
                ) else withHistory
            },
            suggestions = local.suggestions.mapNotNull { suggestion ->
            val restored = if (suggestion.malId > 0) suggestion
                else originals[suggestion.title.id] ?: suggestion
            restored.takeIf { it.malId !in owned }
        })
    }

    private fun decode(root: JSONObject): MalSnapshot {
        val account = root.optString("account").takeIf { accountPattern.matches(it) }
            ?: return MalSnapshot.EMPTY
        val rows = root.optJSONArray("library") ?: JSONArray()
        val entries = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val malId = row.optInt("malId").takeIf { it > 0 } ?: continue
                val name = row.optString("title").takeIf { it.isNotBlank() } ?: continue
                val episodes = row.optInt("episodes").coerceAtLeast(0)
                add(MalEntry(
                    malId,
                    Title(
                        id = row.optString("id").ifBlank { "mal-$malId" },
                        kind = Kind.ANIME,
                        name = name,
                        year = row.optInt("year").takeIf { it in 1900..2200 },
                        poster = row.optString("poster").takeIf { it.startsWith("https://") }.orEmpty(),
                        episodeCount = episodes.takeIf { it > 0 },
                    ),
                    row.optString("status").takeIf { it in STATUSES } ?: "Plan to Watch",
                    row.optInt("watched").coerceAtLeast(0),
                    episodes,
                    row.optInt("userScore").coerceIn(0, 10),
                    row.optLong("lastWatchedAt").coerceAtLeast(0L),
                    row.optBoolean("isSubscribed"),
                    row.optBoolean("onAniWorldWatchlist"),
                    buildList {
                        val source = row.optJSONArray("aniworld") ?: JSONArray()
                        for (j in 0 until source.length()) {
                            val coordinate = source.optJSONObject(j) ?: continue
                            val season = coordinate.optInt("season", -1)
                            if (season < 0 || season > 100) continue
                            val raw = coordinate.optJSONArray("episodes") ?: continue
                            val numbers = (0 until raw.length()).mapNotNull { n ->
                                raw.optInt(n).takeIf { it in 1..1500 }
                            }.distinct().sorted()
                            if (numbers.isNotEmpty()) add(WatchedSeason(season, numbers))
                        }
                    },
                ))
            }
        }.distinctBy { it.malId }
        val owned = entries.mapTo(HashSet()) { it.malId }
        val suggestions = buildList {
            val recs = root.optJSONArray("recommendations") ?: JSONArray()
            for (i in 0 until recs.length()) {
                val row = recs.optJSONObject(i) ?: continue
                if (row.optInt("malId") in owned) continue
                val id = row.optString("id").takeIf { it.matches(Regex("[0-9]{1,10}")) }
                    ?: continue
                val name = row.optString("title").takeIf { it.isNotBlank() } ?: continue
                add(MalSuggestion(
                    Title(id, Kind.ANIME, name,
                        year = row.optInt("year").takeIf { it in 1900..2200 },
                        poster = row.optString("poster"),
                        episodeCount = row.optInt("episodes").takeIf { it > 0 }),
                    row.optString("reason"),
                    row.optInt("malId"),
                ))
            }
        }
        return MalSnapshot(account, entries, suggestions, root.optString("syncedAt"))
    }

    suspend fun refresh(previous: MalSnapshot): MalSnapshot = withContext(Dispatchers.IO) {
        if (!accountPattern.matches(previous.account) || previous.entries.isEmpty())
            return@withContext previous
        val all = JSONArray()
        var offset = 0
        do {
            val url = "https://myanimelist.net/animelist/" + previous.account +
                "/load.json?offset=" + offset + "&status=7"
            val rows = JSONArray(Network.text(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
                "Accept" to "application/json")))
            require(rows.length() <= 300)
            for (i in 0 until rows.length()) all.put(rows.getJSONObject(i))
            offset += rows.length()
            require(offset <= 15_000)
            if (rows.length() < 300) break
        } while (true)
        require(all.length() >= previous.entries.size / 2) {
            "The MAL list response is incomplete"
        }
        val existing = previous.entries.associateBy { it.malId }
        val seen = HashSet<Int>()
        val output = JSONArray()
        for (i in 0 until all.length()) {
            val row = all.getJSONObject(i)
            val mid = row.optInt("anime_id")
            require(mid > 0 && seen.add(mid))
            val old = existing[mid]
            val status = when (row.optInt("status")) {
                1 -> "Watching"; 2 -> "Completed"; 3 -> "On Hold"
                4 -> "Dropped"; 6 -> "Plan to Watch"
                else -> error("Unexpected MAL status")
            }
            val name = old?.title?.name
                ?: row.optString("anime_title_eng").ifBlank { row.optString("anime_title") }
            require(name.isNotBlank())
            val poster = old?.title?.poster?.takeIf { it.isNotBlank() }
                ?: row.optString("anime_image_path").takeIf {
                    it.startsWith("https://cdn.myanimelist.net/")
                }.orEmpty()
            output.put(JSONObject()
                .put("id", old?.title?.id ?: "mal-$mid")
                .put("malId", mid)
                .put("title", name)
                .put("poster", poster)
                .put("episodes", row.optInt("anime_num_episodes").coerceAtLeast(0))
                .put("watched", row.optInt("num_watched_episodes").coerceAtLeast(0))
                .put("status", status)
                .put("userScore", row.optInt("score").coerceIn(0, 10))
                .put("isSubscribed", old?.subscribed ?: false)
                .put("onAniWorldWatchlist", old?.watchlist ?: false)
                .put("lastWatchedAt", maxOf(old?.updatedAt ?: 0L,
                    row.optLong("updated_at").coerceAtLeast(0L)))
                .put("year", old?.title?.year ?: JSONObject.NULL)
                .put("aniworld", JSONArray().apply {
                    old?.aniworld?.forEach { season ->
                        put(JSONObject()
                            .put("season", season.season)
                            .put("episodes", JSONArray(season.episodes)))
                    }
                }))
        }
        val recommendations = JSONArray()
        for (rec in previous.suggestions) recommendations.put(JSONObject()
            .put("id", rec.title.id).put("malId", rec.malId).put("title", rec.title.name)
            .put("poster", rec.title.poster)
            .put("episodes", rec.title.episodeCount ?: 0)
            .put("year", rec.title.year ?: JSONObject.NULL)
            .put("reason", rec.reason))
        val json = JSONObject()
            .put("account", previous.account)
            .put("syncedAt", Instant.now().toString())
            .put("library", output)
            .put("recommendations", recommendations)
        val result = decode(json)
        require(result.entries.size == seen.size)
        val tmp = File(app.filesDir, "mal-profile-cache.json.tmp")
        tmp.writeText(json.toString())
        check(tmp.renameTo(cache)) { "Could not save offline MAL cache" }
        result
    }

    companion object {
        private val STATUSES = setOf("Watching", "Completed", "Plan to Watch", "On Hold", "Dropped")
    }
}
