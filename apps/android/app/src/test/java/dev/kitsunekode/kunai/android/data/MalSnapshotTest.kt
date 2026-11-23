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

    @Test fun exactAniWorldHistoryNeverFillsEpisodeGaps() {
        val season = WatchedSeason(1, listOf(9, 1, 2, 4, 5, 8, 9))
        assertEquals("S1: E1–2, E4–5, E8–9", season.label())
        assertFalse(season.contains(3))
        assertTrue(season.contains(9))
        assertEquals(7, season.count)
    }

    @Test fun seasonsAndSpecialsKeepDistinctEpisodeIdentity() {
        val seasons = listOf(WatchedSeason(1, listOf(1, 2)),
            WatchedSeason(2, listOf(1, 3)), WatchedSeason(0, listOf(1)))
        assertEquals("S1: E1–2", seasons[0].label())
        assertEquals("S2: E1, E3", seasons[1].label())
        assertEquals("Specials: E1", seasons[2].label())
        assertEquals("E1–3, E5", compactEpisodes(listOf(1, 2, 3, 5, 5)))
    }

    @Test fun fullyWatchedWatchingIsCompletedWithoutHidingUnknownTotals() {
        val completedByCount = entry(11, "Watching", watched = 12)
        val incomplete = entry(12, "Watching", watched = 11)
        val unknownTotal = entry(13, "Watching", watched = 12).copy(episodes = 0)
        val explicitlyCompleted = entry(14, "Completed", watched = 8)
        val profile = MalSnapshot("account", listOf(completedByCount,
            incomplete, unknownTotal, explicitlyCompleted), emptyList(), "")
        assertEquals(listOf(12, 13), profile.watching.map { it.malId })
        assertEquals(listOf(11, 14), profile.completed.map { it.malId })
        assertTrue(completedByCount.isComplete)
        assertEquals("Completed", completedByCount.displayStatus)
        assertFalse(unknownTotal.isComplete)
        assertEquals("Watching", unknownTotal.displayStatus)
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
