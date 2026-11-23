package dev.kitsunekode.kunai.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private, device-local library. Import always MERGES; it never clears existing
 * user data. The optional file transport works without Philipedia or an account.
 */
class Library(context: Context) {
    private val prefs = context.getSharedPreferences("kunai-library-v1", Context.MODE_PRIVATE)

    fun saved(): List<Title> = parseTitles(prefs.getString("list", "[]"))
    fun has(title: Title) = saved().any { it.id == title.id && it.kind == title.kind }

    fun toggle(title: Title) {
        val items = saved().toMutableList()
        val existing = items.indexOfFirst { it.id == title.id && it.kind == title.kind }
        if (existing >= 0) items.removeAt(existing) else items.add(0, title)
        val arr = JSONArray()
        items.take(250).forEach { arr.put(encode(it)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun record(title: Title, season: Int, episode: Int, positionMs: Long, durationMs: Long,
               durable: Boolean = false) {
        val rows = recent().filterNot {
            it.title.id == title.id && it.title.kind == title.kind &&
                it.season == season && it.episode == episode
        }.toMutableList()
        rows.add(0, Recent(title, season.coerceAtLeast(1), episode.coerceAtLeast(1),
            positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), System.currentTimeMillis()))
        val edit = prefs.edit().putString("recent", historyJson(rows.take(120)).toString())
        if (durable) check(edit.commit()) { "Playback checkpoint could not be saved" }
        else edit.apply()
    }

    fun recent(): List<Recent> = parseHistory(prefs.getString("recent", "[]"))
    fun position(title: Title, season: Int, episode: Int) =
        recent().firstOrNull { it.title.id == title.id && it.title.kind == title.kind &&
            it.season == season && it.episode == episode }?.positionMs ?: 0L

    /** Portable, versioned format, limited to small title/position records. */
    fun exportJson(): String = JSONObject()
        .put("format", FORMAT).put("version", 1)
        .put("exportedAtMs", System.currentTimeMillis())
        .put("saved", JSONArray().apply { saved().forEach { put(encode(it)) } })
        .put("history", historyJson(recent()))
        .toString(2)

    /**
     * Atomic additive merge, with the newest watch record winning conflicts.
     * Existing entries always win timestamp ties (including old pre-v2 data).
     */
    fun mergeJson(raw: String): MergeResult {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
            "Library file is too large"
        }
        val snapshot = JSONObject(raw)
        require(snapshot.optString("format") == FORMAT && snapshot.optInt("version") == 1) {
            "Not a Kunai library backup"
        }
        val savedInput = snapshot.optJSONArray("saved")
            ?: throw IllegalArgumentException("Missing saved titles")
        val historyInput = snapshot.optJSONArray("history")
            ?: throw IllegalArgumentException("Missing watch history")
        require(savedInput.length() <= 250 && historyInput.length() <= 120) {
            "Library backup exceeds supported limits"
        }
        val importedSaved = parseTitles(savedInput)
        val importedHistory = parseHistory(historyInput)
        require(importedSaved.size == savedInput.length() &&
                importedHistory.size == historyInput.length()) {
            "Library backup contains invalid records"
        }
        val currentSaved = saved()
        val currentHistory = recent()
        val together = (currentSaved + importedSaved)
            .distinctBy { it.kind to it.id }.take(250)
        val histories = (currentHistory + importedHistory)
            .sortedWith(compareByDescending<Recent> { it.updatedAtMs })
            .distinctBy { listOf(it.title.kind.key, it.title.id,
                it.season.toString(), it.episode.toString()) }
            .take(120)
        val savedJson = JSONArray().apply { together.forEach { put(encode(it)) } }
        check(prefs.edit().putString("list", savedJson.toString())
            .putString("recent", historyJson(histories).toString()).commit()) {
            "Could not save imported library"
        }
        return MergeResult(
            savedAdded = (together.map { it.kind to it.id } -
                currentSaved.map { it.kind to it.id }.toSet()).size,
            historyAdded = (histories.map { listOf(it.title.kind.key,it.title.id,
                it.season.toString(),it.episode.toString()) } -
                currentHistory.map { listOf(it.title.kind.key,it.title.id,
                    it.season.toString(),it.episode.toString()) }.toSet()).size,
        )
    }

    data class MergeResult(val savedAdded: Int, val historyAdded: Int)
    data class Recent(
        val title: Title, val season: Int, val episode: Int,
        val positionMs: Long, val durationMs: Long,
        val updatedAtMs: Long = 0L,
    )

    companion object {
        const val MAX_BACKUP_BYTES = 500_000
        private const val FORMAT = "dev.kitsunekode.kunai.library"

        fun encode(value: Title): JSONObject = JSONObject()
            .put("id", value.id).put("kind", value.kind.key).put("name", value.name)
            .put("year", value.year).put("poster", value.poster)
            .put("backdrop", value.backdrop).put("description", value.description)
            .put("episodeCount", value.episodeCount).put("sourceSlug", value.sourceSlug)

        fun decode(value: JSONObject): Title? {
            val id = value.optString("id").takeIf { it.matches(Regex("[0-9]{1,12}")) }
                ?: return null
            val kind = Kind.entries.firstOrNull { it.key == value.optString("kind") }
                ?: return null
            val name = CatalogIdentity.readable(value.optString("name"))
                ?.takeIf { it.length <= 200 } ?: return null
            fun photo(key: String): String = value.optString(key).takeIf {
                it.length <= 600 && (it.isEmpty() ||
                    it.startsWith("https://s4.anilist.co/") ||
                    it.startsWith("https://image.tmdb.org/") ||
                    it.startsWith("https://kaa.lt/"))
            }.orEmpty()
            return Title(id, kind, name,
                value.optInt("year").takeIf { it in 1900..2200 },
                photo("poster"), photo("backdrop"),
                value.optString("description").take(2200),
                value.optInt("episodeCount").takeIf { it in 1..5000 },
                sourceSlug = value.optString("sourceSlug")
                    .takeIf { it.matches(Regex("[a-z0-9][a-z0-9-]{0,110}")) })
        }

        private fun parseTitles(text: String?): List<Title> =
            runCatching { parseTitles(JSONArray(text ?: "[]")) }.getOrDefault(emptyList())

        private fun parseTitles(values: JSONArray): List<Title> = buildList {
            for (i in 0 until minOf(values.length(), 250)) {
                values.optJSONObject(i)?.let(::decode)?.let(::add)
            }
        }

        private fun parseHistory(text: String?): List<Recent> =
            runCatching { parseHistory(JSONArray(text ?: "[]")) }.getOrDefault(emptyList())

        private fun parseHistory(values: JSONArray): List<Recent> = buildList {
            for (i in 0 until minOf(values.length(), 120)) {
                val item = values.optJSONObject(i) ?: continue
                val title = item.optJSONObject("title")?.let(::decode) ?: continue
                val season = item.optInt("season", 1)
                val episode = item.optInt("episode", 1)
                val duration = item.optLong("durationMs")
                val position = item.optLong("positionMs")
                val updated = item.optLong("updatedAtMs", 0L)
                if (season !in 1..100 || episode !in 1..5000 ||
                    duration < 0 || position < 0 || updated < 0) continue
                add(Recent(title, season, episode, position, duration, updated))
            }
        }

        private fun historyJson(entries: List<Recent>): JSONArray = JSONArray().apply {
            entries.take(120).forEach { row ->
                put(JSONObject()
                    .put("title", encode(row.title))
                    .put("season", row.season).put("episode", row.episode)
                    .put("positionMs", row.positionMs)
                    .put("durationMs", row.durationMs)
                    .put("updatedAtMs", row.updatedAtMs))
            }
        }
    }
}
