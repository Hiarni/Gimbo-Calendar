package de.luma.calendar

import de.luma.calendar.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ExceptionEngineTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val weekly = Event(id = 7, title = "Training", start = "2026-10-01T09:00", end = "2026-10-01T10:00", zone = zone.id, repeat = Repeat.WEEKLY)
    private fun range(changes: List<EventException>, from: String = "2026-10-01", to: String = "2026-10-23", event: Event = weekly, view: ZoneId = zone) =
        EventEngine.occurrences(listOf(event), LocalDate.parse(from), LocalDate.parse(to), view, changes)
    private fun changed(original: String, start: String, end: String = LocalDateTime.parse(start).plusHours(1).toString(), parent: Event = weekly): EventException =
        EventException(parent.id, original, parent.copy(start = start, end = end, title = "Einzeln", repeat = Repeat.NONE, interval = 1, until = null)).checked(parent)

    @Test fun skippingOneWeekPreservesAllOtherOccurrences() {
        val results = range(listOf(EventException(weekly.id, "2026-10-08T09:00").checked(weekly)))
        assertEquals(listOf(1, 15, 22), results.map { it.start.dayOfMonth })
        assertTrue(results.all { it.event == weekly })
    }
    @Test fun movedOccurrenceAppearsOutsideItsOriginalRangeAndKeepsItsIdentity() {
        val exception = changed("2026-10-08T09:00", "2027-01-10T18:00")
        assertEquals(listOf(1, 15, 22), range(listOf(exception)).map { it.start.dayOfMonth })
        val moved = range(listOf(exception), "2027-01-10", "2027-01-11").single()
        val original = range(emptyList(), "2026-10-08", "2026-10-09").single()
        assertEquals(original.key, moved.key)
        assertTrue(moved.changed)
        assertEquals("Einzeln", moved.event.title)
    }
    @Test fun replacementMayMoveBeyondTheSeriesEnd() {
        val parent = weekly.copy(until = "2026-10-08")
        val exception = changed("2026-10-08T09:00", "2027-01-10T18:00", parent = parent)
        assertEquals(1, range(listOf(exception), "2027-01-10", "2027-01-11", parent).size)
        assertTrue(range(listOf(exception), "2026-10-08", "2026-10-09", parent).isEmpty())
    }
    @Test fun coincidentOccurrencesHaveDistinctKeys() {
        val results = range(listOf(changed("2026-10-01T09:00", "2026-10-08T09:00")), "2026-10-08", "2026-10-09")
        assertEquals(2, results.size)
        assertEquals(1, results.map { it.start }.distinct().size)
        assertEquals(2, results.map { it.key }.distinct().size)
    }
    @Test fun dstGapUsesNominalTimeForCancelAndRestore() {
        val parent = weekly.copy(start = "2026-03-22T02:30", end = "2026-03-22T03:30")
        val original = range(emptyList(), "2026-03-29", "2026-03-30", parent).single()
        assertEquals(3, original.start.hour)
        assertEquals("2026-03-29T02:30", original.originalStart)
        assertTrue(range(listOf(EventException(parent.id, original.originalStart!!).checked(parent)), "2026-03-29", "2026-03-30", parent).isEmpty())
        val replacement = changed(original.originalStart!!, "2026-03-29T12:00", parent = parent)
        assertEquals(original.key, range(listOf(replacement), "2026-03-29", "2026-03-30", parent).single().key)
    }
    @Test fun allDayExceptionsFloatWithTheViewerTimeZone() {
        val parent = weekly.copy(start = "2026-10-01T00:00", end = "2026-10-02T00:00", allDay = true)
        val exception = changed("2026-10-08T00:00", "2026-10-09T00:00", "2026-10-11T00:00", parent)
        val berlin = range(listOf(exception), "2026-10-09", "2026-10-11", parent).single()
        val tokyo = range(listOf(exception), "2026-10-09", "2026-10-11", parent, ZoneId.of("Asia/Tokyo")).single()
        assertEquals(berlin.start.toLocalDate(), tokyo.start.toLocalDate())
        assertEquals(berlin.key, tokyo.key)
        assertNotEquals(berlin.start.toInstant(), tokyo.start.toInstant())
    }
    @Test fun invalidMonthlyAndIntervalOccurrencesAreRejected() {
        val monthly = weekly.copy(start = "2026-01-31T09:00", end = "2026-01-31T10:00", repeat = Repeat.MONTHLY)
        assertFalse(EventEngine.isOccurrence(monthly, LocalDateTime.parse("2026-04-30T09:00")))
        assertThrows(IllegalArgumentException::class.java) { EventException(monthly.id, "2026-04-30T09:00").checked(monthly) }
        val biweekly = weekly.copy(interval = 2, until = "2026-10-15")
        assertFalse(EventEngine.isOccurrence(biweekly, LocalDateTime.parse("2026-10-08T09:00")))
        assertFalse(EventEngine.isOccurrence(biweekly, LocalDateTime.parse("2026-10-29T09:00")))
        assertFalse(EventEngine.isOccurrence(biweekly, LocalDateTime.parse("2026-10-15T10:00")))
        assertTrue(EventEngine.isOccurrence(biweekly, LocalDateTime.parse("2026-10-15T09:00")))
    }
    @Test fun exceptionCanAddAReminderToAnOtherwiseSilentSeries() {
        val now = Instant.parse("2026-10-07T00:00:00Z")
        val exception = changed("2026-10-08T09:00", "2026-10-08T12:00")
        val changes = listOf(exception.copy(replacement = exception.replacement!!.copy(reminderMinutes = 15)))
        val next = EventEngine.nextReminder(weekly, now, zone, changes)!!
        assertEquals(Instant.parse("2026-10-08T09:45:00Z"), next.second)
        assertEquals(exception.originalStart, next.first.originalStart)
        assertNull(EventEngine.nextReminder(weekly, now, zone, listOf(EventException(weekly.id, exception.originalStart))))
    }
    @Test fun nextReminderUsesEarliestTriggerAndSkipsCancelledOccurrences() {
        val parent = weekly.copy(reminderMinutes = 0)
        val exception = changed("2026-10-15T09:00", "2026-10-09T08:00", parent = parent)
        val early = exception.copy(replacement = exception.replacement!!.copy(reminderMinutes = 1440))

        val next = EventEngine.nextReminder(parent, Instant.parse("2026-10-08T00:00:00Z"), zone, listOf(early))!!
        assertEquals("2026-10-15T09:00", next.first.originalStart)
        val skipped = EventException(parent.id, "2026-10-08T09:00").checked(parent)
        assertEquals("2026-10-15T09:00", EventEngine.nextReminder(parent, Instant.parse("2026-10-07T00:00:00Z"), zone, listOf(skipped))!!.first.originalStart)
    }
    @Test fun multidayReplacementOverlapsRangeWithExclusiveEnd() {
        val exception = changed("2026-10-08T09:00", "2026-11-01T09:00", "2026-11-03T00:00")
        assertEquals(1, range(listOf(exception), "2026-11-02", "2026-11-03").size)
        assertTrue(range(listOf(exception), "2026-11-03", "2026-11-04").isEmpty())
    }
}
