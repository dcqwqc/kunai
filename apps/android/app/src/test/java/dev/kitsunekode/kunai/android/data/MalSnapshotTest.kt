package dev.kitsunekode.kunai.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MalSnapshotTest {
    private fun entry(id: Int, state: String, watched: Int = 0, subscribed: Boolean = false,
                      watchlist: Boolean = false, updated: Long = 0) =
        MalEntry(id, Title(id.toString(), Kind.ANIME, "Title $id"),
            state, watched, 12, 0, updated, subscribed, watchlist)

    @Test fun planToWatchDoesNotAppearInContinue() {
        val profile = MalSnapshot("account", listOf(
            entry(1, "Plan to Watch"), entry(2, "Watching", 3),
            entry(3, "Completed", 12)), emptyList(), "")
        assertEquals(listOf(2), profile.watching.map { it.malId })
        assertEquals(listOf(1), profile.planned.map { it.malId })
        assertEquals(listOf(3), profile.completed.map { it.malId })
        assertEquals(3, profile.entries.size)
    }

    @Test fun sourceTagsAreIndependentOfMalStatus() {
        val profile = MalSnapshot("account", listOf(
            entry(1, "Completed", 12, subscribed = true),
            entry(2, "Watching", subscribed = true, watchlist = true),
            entry(3, "Plan to Watch", watchlist = true)), emptyList(), "")
        assertEquals(listOf(1, 2), profile.subscribed.map { it.malId })
        assertEquals(listOf(2, 3), profile.watchlist.map { it.malId })
        assertEquals(1, profile.completed.size)
    }

    @Test fun continuePrefersMoreRecentlyUpdatedEntries() {
        val profile = MalSnapshot("account", listOf(
            entry(1, "Watching", updated = 1), entry(2, "Watching", updated = 50),
            entry(3, "Plan to Watch", updated = 900)), emptyList(), "")
        assertEquals(listOf(2, 1), profile.watching.map { it.malId })
        assertFalse(profile.watching.any { it.status == "Plan to Watch" })
        assertTrue(profile.planned.any { it.malId == 3 })
    }
}
