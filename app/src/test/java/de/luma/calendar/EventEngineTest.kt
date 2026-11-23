package de.luma.calendar

import de.luma.calendar.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class EventEngineTest {
    private val vienna = ZoneId.of("Europe/Vienna")
    private fun event(start: String, end: String, repeat: Repeat = Repeat.NONE) =
        Event(id = 1, title = "Test", start = start, end = end, zone = vienna.id, repeat = repeat).checked()
    private fun range(e: Event, from: String, to: String, zone: ZoneId = vienna) = EventEngine.occurrences(listOf(e), LocalDate.parse(from), LocalDate.parse(to), zone)

    @Test fun monthly31SkipsMissingDaysWithoutDrifting() {
        val e = event("2026-01-31T09:00", "2026-01-31T10:00", Repeat.MONTHLY)
        assertEquals(listOf(1, 3, 5, 7), range(e, "2026-01-01", "2026-08-01").map { it.start.monthValue })
        assertTrue(range(e, "2026-02-01", "2026-03-01").isEmpty())
    }
    @Test fun leapBirthdayReturnsOnlyLeapYears() {
        val e = event("2024-02-29T09:00", "2024-02-29T10:00", Repeat.YEARLY)
        assertEquals(listOf(2024, 2028, 2032), range(e, "2024-01-01", "2033-01-01").map { it.start.year })
    }
    @Test fun weeklyKeepsLocalTimeAcrossSpringDst() {
        val e = event("2026-03-22T09:00", "2026-03-22T10:00", Repeat.WEEKLY)
        val items = range(e, "2026-03-20", "2026-04-01")
        assertEquals(listOf(9, 9), items.map { it.start.hour })
        assertEquals(167, Duration.between(items[0].start.toInstant(), items[1].start.toInstant()).toHours())
    }
    @Test fun springGapShiftsStartAndEndTogether() {
        val e = event("2026-03-22T02:30", "2026-03-22T03:30", Repeat.WEEKLY)
        val o = range(e, "2026-03-29", "2026-03-30").single()
        assertEquals(3, o.start.hour)
        assertEquals(4, o.end.hour)
    }
    @Test fun untilDateIsInclusive() {
        val e = event("2026-10-01T09:00", "2026-10-01T10:00", Repeat.DAILY).copy(until = "2026-10-03")
        assertEquals(listOf(1, 2, 3), range(e, "2026-10-01", "2026-11-01").map { it.start.dayOfMonth })
    }
    @Test fun intervalAnchorsEverySecondWeek() {
        val e = event("2026-10-01T09:00", "2026-10-01T10:00", Repeat.WEEKLY).copy(interval = 2)
        assertEquals(listOf(1, 15, 29), range(e, "2026-10-01", "2026-11-01").map { it.start.dayOfMonth })
    }
    @Test fun oldSeriesFindsCurrentOccurrencesEfficiently() {
        val e = event("1900-01-01T09:00", "1900-01-01T10:00", Repeat.DAILY)
        assertEquals(31, range(e, "2026-10-01", "2026-11-01").size)
    }
    @Test fun multiDayIncludesMiddleButExcludesMidnightEnd() {
        val e = event("2026-10-01T00:00", "2026-10-04T00:00").copy(allDay = true)
        assertEquals(1, range(e, "2026-10-03", "2026-10-04").size)
        assertTrue(range(e, "2026-10-04", "2026-10-05").isEmpty())
    }
    @Test fun allDayIsFloatingWhenTravelling() {
        val e = event("2026-10-01T00:00", "2026-10-02T00:00").copy(allDay = true)
        val o = range(e, "2026-10-01", "2026-10-02", ZoneId.of("America/Los_Angeles")).single()
        assertEquals(LocalDate.of(2026, 10, 1), o.start.toLocalDate())
        assertEquals(0, o.start.hour)
        assertEquals(ZoneId.of("America/Los_Angeles"), o.start.zone)
    }
    @Test fun timedEventAppearsOnDateInViewersZone() {
        val e = event("2026-10-01T00:30", "2026-10-01T01:30")
        assertEquals(1, range(e, "2026-09-30", "2026-10-01", ZoneId.of("America/Los_Angeles")).size)
    }
    @Test fun nextReminderSkipsMissedOccurrence() {
        val e = event("2026-10-01T09:00", "2026-10-01T10:00", Repeat.DAILY).copy(reminderMinutes = 15)
        val next = EventEngine.nextReminder(e, ZonedDateTime.of(2026, 10, 1, 8, 50, 0, 0, vienna).toInstant(), vienna)!!
        assertEquals(2, next.first.start.dayOfMonth)
        assertEquals(LocalTime.of(8, 45), next.second.atZone(vienna).toLocalTime())
    }
    @Test fun oneDayPriorReminderSchedulesFromTodayForTomorrow() {
        val e = event("2026-10-02T09:00", "2026-10-02T10:00").copy(reminderMinutes = 1440)
        val next = EventEngine.nextReminder(e, ZonedDateTime.of(2026, 10, 1, 8, 0, 0, 0, vienna).toInstant(), vienna)!!
        assertEquals(LocalDate.of(2026, 10, 1), next.second.atZone(vienna).toLocalDate())
    }
    @Test fun noReminderReturnsNull() { assertNull(EventEngine.nextReminder(event("2026-10-01T09:00", "2026-10-01T10:00"), Instant.now())) }
    @Test fun longRecurringEventOverlapsRange() {
        val e = event("2026-01-01T09:00", "2026-01-20T10:00", Repeat.MONTHLY)
        assertEquals(1, range(e, "2026-02-15", "2026-02-16").size)
    }
    @Test(expected = IllegalArgumentException::class) fun blankTitleRejected() { event("2026-10-01T09:00", "2026-10-01T10:00").copy(title = " ").checked() }
    @Test(expected = IllegalArgumentException::class) fun negativeDurationRejected() { event("2026-10-01T10:00", "2026-10-01T09:00") }
    @Test(expected = IllegalArgumentException::class) fun zeroIntervalRejected() { event("2026-10-01T09:00", "2026-10-01T10:00").copy(interval = 0).checked() }
    @Test(expected = IllegalArgumentException::class) fun untilBeforeStartRejected() { event("2026-10-01T09:00", "2026-10-01T10:00").copy(until = "2026-09-30").checked() }
}
