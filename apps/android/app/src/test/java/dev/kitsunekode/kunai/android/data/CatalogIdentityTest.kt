package dev.kitsunekode.kunai.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogIdentityTest {
    @Test fun jsonNullIsNotARealTitle() {
        assertNull(CatalogIdentity.readable("null"))
        assertNull(CatalogIdentity.readable(" NULL "))
        assertNull(CatalogIdentity.readable(""))
        assertEquals("Slime", CatalogIdentity.readable(" Slime "))
    }

    @Test fun standaloneFilmCanFindMainSeriesWithoutAnAniListRelation() {
        assertEquals("That Time I Got Reincarnated as a Slime",
            CatalogIdentity.rootFromMovie(
                "That Time I Got Reincarnated as a Slime the Movie: Scarlet Bond"))
        assertEquals("Demon Slayer: Kimetsu no Yaiba",
            CatalogIdentity.rootFromMovie(
                "Demon Slayer: Kimetsu no Yaiba - The Movie: Mugen Train"))
        assertNull(CatalogIdentity.rootFromMovie("The Slime Diaries"))
        assertNull(CatalogIdentity.rootFromMovie("Dune 2"))
    }

    @Test fun homeGroupsOnlyVerifiedRelations() {
        val releases = listOf(
            Title("101", Kind.ANIME,"Slime Season 4",2026),
            Title("909", Kind.ANIME,"Unrelated 2025 anime",2025),
            Title("102", Kind.ANIME,"That Time I Got Reincarnated as a Slime",2018),
            Title("103", Kind.ANIME,"Slime the Movie",2022),
            Title("808", Kind.ANIME,"Unrelated OVA",2017)
        )
        val graph = mapOf("101" to setOf("102"), "102" to setOf("103"),
            "909" to setOf("7999"))
        assertEquals(listOf("102","909","808"),
            CatalogIdentity.collapseRelated(releases,graph).map { it.id })
    }

    @Test fun searchRemainsExactAndNeverUsesTitleGuesswork() {
        val releases = listOf(Title("21",Kind.ANIME,"Different title",2026),
            Title("22",Kind.ANIME,"Different title Season 2",2027))
        assertEquals(2,CatalogIdentity.collapseRelated(releases,emptyMap()).size)
        assertEquals(2,CatalogIdentity.distinct(releases).size)
    }

    @Test fun franchiseSearchIsConservative() {
        val name = "That Time I Got Reincarnated as a Slime"
        org.junit.Assert.assertTrue(CatalogIdentity.sameReleaseFamily(
            name, "$name the Movie: Scarlet Bond"))
        org.junit.Assert.assertTrue(CatalogIdentity.sameReleaseFamily(
            name, "$name Season 4"))
        org.junit.Assert.assertFalse(CatalogIdentity.sameReleaseFamily(
            name, "I've Been Killing Slimes for 300 Years"))
        org.junit.Assert.assertFalse(CatalogIdentity.sameReleaseFamily(
            "Slime", "Slime Diaries"))
    }

    @Test fun duplicatesShareAReleaseButNotSeasonsOrMovies() {
        val rows = listOf(
            Title("1", Kind.ANIME, "That Time I Got Reincarnated as a Slime", 2018),
            Title("2", Kind.ANIME, "that time i got reincarnated as a slime!", 2018),
            Title("3", Kind.ANIME, "That Time I Got Reincarnated as a Slime Season 2", 2021),
            Title("4", Kind.ANIME, "That Time I Got Reincarnated as a Slime", 2026),
            Title("5", Kind.MOVIE, "That Time I Got Reincarnated as a Slime", 2018),
            Title("6", Kind.ANIME, "null", 2022),
        )
        assertEquals(listOf("1","3","4","5"), CatalogIdentity.distinct(rows).map { it.id })
    }
}
