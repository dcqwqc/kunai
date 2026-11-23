package dev.kitsunekode.kunai.android.data

import java.util.Locale

/** Display identity is separate from the provider ID used for playback and progress. */
object CatalogIdentity {
    fun readable(value: String?): String? = value?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun titleKey(name: String): String = name.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .replace(Regex("\\s+"), " ")

    /** A search supplement can fill gaps in a known relation graph. Do not
     * infer franchises from vague shared words or unrelated partial matches.
     */
    fun sameReleaseFamily(base: String, candidate: String): Boolean {
        val root = titleKey(base)
        val item = titleKey(candidate)
        return root.length >= 12 && (item == root || item.startsWith("$root "))
    }

    /** Some film releases have no AniList relation edge back to their series.
     * Only strip a clear feature-film label; don't guess from every subtitle.
     */
    fun rootFromMovie(name: String): String? {
        val suffix = Regex("""(?i)\s+(?:the\s+)?movie(?:\s*[:\-]|\b)""")
        val match = suffix.find(name) ?: return null
        return name.substring(0,match.range.first).trim().trimEnd(':','-','–','—').trim()
            .takeIf { titleKey(it).length >= 12 }
    }

    /** Collapse only provider-verified anime relation components within one shelf.
     *
     * Search results, MAL entries and user progress stay separate. Relations to
     * titles that are not on this shelf still connect two releases through the
     * same verified parent. The first ranked component position is preserved.
     */
    fun collapseRelated(
        titles: List<Title>,
        relatedIds: Map<String, Set<String>>,
    ): List<Title> {
        val unique = distinct(titles)
        val parent = mutableMapOf<String, String>()
        fun root(id: String): String {
            var current = id
            while (parent.getValue(current) != current) {
                current = parent.getValue(current)
            }
            return current
        }
        fun connect(a: String, b: String) {
            parent.putIfAbsent(a,a)
            parent.putIfAbsent(b,b)
            val left = root(a)
            val right = root(b)
            if (left != right) parent[right] = left
        }
        for (title in unique) {
            if (title.kind == Kind.ANIME) parent.putIfAbsent(title.id,title.id)
        }
        val actualIds = unique.filter { it.kind == Kind.ANIME }.mapTo(HashSet()) { it.id }
        for ((id, neighbors) in relatedIds) {
            if (id !in actualIds) continue
            for (neighbor in neighbors) {
                if (neighbor.matches(Regex("[0-9]{1,12}"))) connect(id,neighbor)
            }
        }
        val components = linkedMapOf<String, MutableList<Title>>()
        for (title in unique) {
            val groupId = if (title.kind == Kind.ANIME) root(title.id)
                else title.kind.key + ":" + title.id
            components.getOrPut(groupId) { mutableListOf() }.add(title)
        }
        return components.values.map { members ->
            members.minWith(compareBy<Title> { it.year ?: Int.MAX_VALUE }
                .thenBy { unique.indexOf(it) })
        }
    }

    /** Collapse the same release listed under different provider/MAL identifiers.
     * Never merge different years or separately titled seasons and movies.
     */
    fun distinct(titles: List<Title>): List<Title> {
        val used = HashSet<String>()
        return titles.filter { title ->
            if (readable(title.name) == null ||
                title.name.equals("Untitled", ignoreCase = true)) return@filter false
            val key = "${title.kind.key}:${titleKey(title.name)}:${title.year ?: "unknown"}"
            used.add(key)
        }
    }
}
