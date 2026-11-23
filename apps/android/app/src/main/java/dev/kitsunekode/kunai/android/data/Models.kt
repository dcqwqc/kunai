package dev.kitsunekode.kunai.android.data

enum class Kind(val key: String, val label: String) {
    ANIME("anime", "Anime"), SERIES("series", "Series"), MOVIE("movie", "Movies");
    companion object { fun from(key: String) = entries.firstOrNull { it.key == key } ?: ANIME }
}
data class Title(
    val id: String,
    val kind: Kind,
    val name: String,
    val year: Int? = null,
    val poster: String = "",
    val backdrop: String = "",
    val description: String = "",
    val episodeCount: Int? = null,
    val nextAiringAt: Long? = null,
    val sourceSlug: String? = null,
    val seasonCount: Int? = null,
    val trailerId: String? = null,
)
data class Episode(
    val number: Int,
    val name: String,
    val season: Int = 1,
    val still: String = "",
)
data class Subtitle(
    val uri: String, val language: String, val label: String,
    // Explicit format discovered from content. Some providers serve WEBVTT
    // files with misleading .srt names, which Media3 cannot decode as SubRip.
    val mimeType: String? = null,
)
data class Stream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<Subtitle> = emptyList(),
    val source: String = "",
)
data class Upcoming(val title: Title, val episode: Int, val airingAt: Long)
