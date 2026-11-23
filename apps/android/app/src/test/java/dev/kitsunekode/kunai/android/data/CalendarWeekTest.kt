package dev.kitsunekode.kunai.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarWeekTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun mondayToSundayAlwaysSevenDays() {
        val monday = CalendarWeek.monday(LocalDate.of(2026, 11, 22))
        assertEquals(LocalDate.of(2026, 11, 16), monday)
        assertEquals(7, CalendarWeek.days(monday).size)
        assertEquals(LocalDate.of(2026, 11, 22), CalendarWeek.days(monday).last())
    }

    @Test fun daylightSavingWeekUsesRealLocalMidnights() {
        val monday = LocalDate.of(2026, 3, 23)
        val range = CalendarWeek.range(monday, berlin)
        assertEquals(7 * 86400 - 3600L, range.last - range.first + 1)
    }

    @Test fun selectedDayRequestHonorsDstBoundaries() {
        val spring = CalendarWeek.dayRange(LocalDate.of(2026, 3, 29), berlin)
        val autumn = CalendarWeek.dayRange(LocalDate.of(2026, 10, 25), berlin)
        assertEquals(23 * 3600L, spring.last - spring.first + 1)
        assertEquals(25 * 3600L, autumn.last - autumn.first + 1)
    }

    @Test fun forwardBackLimitsMatchRemoteSchedule() {
        val today = LocalDate.of(2026, 11, 22)
        val current = CalendarWeek.monday(today)
        assertTrue(CalendarWeek.weekInRange(current.plusWeeks(17), today))
        assertFalse(CalendarWeek.weekInRange(current.plusWeeks(18), today))
        assertTrue(CalendarWeek.weekInRange(current.minusWeeks(5), today))
        assertFalse(CalendarWeek.weekInRange(current.minusWeeks(6), today))
    }

    @Test fun episodeFilteringUsesLocalTimezone() {
        val date = LocalDate.of(2026, 11, 22)
        val sunday = date.atTime(23, 30).atZone(berlin).toEpochSecond()
        val monday = date.plusDays(1).atStartOfDay(berlin).toEpochSecond()
        val title = Title("125", Kind.ANIME, "Example")
        val rows = listOf(Upcoming(title, 1, sunday), Upcoming(title, 2, monday))
        assertEquals(listOf(1), CalendarWeek.onDay(rows, date, berlin).map { it.episode })
        assertEquals(listOf(2), CalendarWeek.onDay(rows, date.plusDays(1), berlin).map { it.episode })
    }
}
