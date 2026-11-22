package dev.kitsunekode.kunai.android.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Monday-first seven-day calendar, shared by TV and phone Compose layouts.
 * Local midnight boundaries are calculated in the device timezone so DST
 * never shifts Tuesday releases into Monday.
 */
object CalendarWeek {
    fun monday(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun days(monday: LocalDate): List<LocalDate> =
        (0L..6L).map { monday.plusDays(it) }

    fun range(monday: LocalDate, zone: ZoneId): LongRange {
        val start = monday.atStartOfDay(zone).toEpochSecond()
        val endExclusive = monday.plusWeeks(1).atStartOfDay(zone).toEpochSecond()
        return start until endExclusive
    }

    fun onDay(events: List<Upcoming>, day: LocalDate, zone: ZoneId): List<Upcoming> =
        events.filter { row ->
            row.airingAt > 0 &&
                Instant.ofEpochSecond(row.airingAt).atZone(zone).toLocalDate() == day
        }.sortedBy { it.airingAt }

    fun weekInRange(
        weekStart: LocalDate,
        today: LocalDate,
        pastWeeks: Long = 5,
        futureWeeks: Long = 17,
    ): Boolean {
        val current = monday(today)
        return !weekStart.isBefore(current.minusWeeks(pastWeeks)) &&
            !weekStart.isAfter(current.plusWeeks(futureWeeks))
    }
}
