package dev.kitsunekode.kunai.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Device-local watch history and library, with no account or relay requirement. */
class Library(context: Context) {
    private val prefs = context.getSharedPreferences("kunai-library-v1", Context.MODE_PRIVATE)
    fun saved(): List<Title> {
        val raw = prefs.getString("list", "[]") ?: "[]"
        val values = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until values.length()) {
                values.optJSONObject(i)?.let(::decode)?.let(::add)
            }
        }
    }
    fun has(title: Title) = saved().any { it.id == title.id && it.kind == title.kind }
    fun toggle(title: Title) {
        val entries = saved().toMutableList()
        val found = entries.indexOfFirst { it.id == title.id && it.kind == title.kind }
        if (found >= 0) entries.removeAt(found) else entries.add(0, title)
        val json = JSONArray()
        entries.take(250).forEach { json.put(encode(it)) }
        prefs.edit().putString("list", json.toString()).apply()
    }
    fun record(title: Title, season: Int, episode: Int, positionMs: Long, durationMs: Long) {
        val rows = recent().filterNot {
            it.title.id == title.id && it.title.kind == title.kind &&
                it.season == season && it.episode == episode
        }.toMutableList()
        rows.add(0, Recent(title, season, episode, positionMs, durationMs))
        val json = JSONArray()
        rows.take(120).forEach { entry ->
            json.put(JSONObject()
                .put("title", encode(entry.title)).put("season", entry.season)
                .put("episode", entry.episode).put("positionMs", entry.positionMs)
                .put("durationMs", entry.durationMs))
        }
        prefs.edit().putString("recent", json.toString()).apply()
    }
    fun recent(): List<Recent> {
        val raw = prefs.getString("recent", "[]") ?: "[]"
        val values = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i) ?: continue
                val title = decode(item.optJSONObject("title") ?: continue) ?: continue
                add(Recent(title, item.optInt("season", 1), item.optInt("episode", 1),
                    item.optLong("positionMs"), item.optLong("durationMs")))
            }
        }
    }
    fun position(title: Title, season: Int, episode: Int) =
        recent().firstOrNull { it.title.id == title.id && it.title.kind == title.kind &&
            it.season == season && it.episode == episode }?.positionMs ?: 0L

    data class Recent(
        val title: Title, val season: Int, val episode: Int,
        val positionMs: Long, val durationMs: Long,
    )

    companion object {
        fun encode(value: Title): JSONObject = JSONObject()
            .put("id", value.id).put("kind", value.kind.key).put("name", value.name)
            .put("year", value.year).put("poster", value.poster)
            .put("backdrop", value.backdrop).put("description", value.description)
            .put("episodeCount", value.episodeCount).put("sourceSlug", value.sourceSlug)
        fun decode(value: JSONObject): Title? {
            val id = value.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = value.optString("name").takeIf { it.isNotBlank() } ?: return null
            return Title(id, Kind.from(value.optString("kind")), name,
                value.optInt("year").takeIf { it > 0 }, value.optString("poster"),
                value.optString("backdrop"), value.optString("description"),
                value.optInt("episodeCount").takeIf { it > 0 },
                sourceSlug = value.optString("sourceSlug").takeIf { it.isNotBlank() })
        }
    }
}
