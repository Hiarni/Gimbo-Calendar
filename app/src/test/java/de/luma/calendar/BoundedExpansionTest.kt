package de.luma.calendar

import de.luma.calendar.model.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class BoundedExpansionTest {
    private val day = LocalDate.parse("2026-01-01")
    private val template = Event(title = "Daily", start = "2026-01-01T09:00", end = "2026-01-01T10:00", repeat = Repeat.DAILY)
    @Test fun thousandsOfSeriesMergeWithoutMaterializingMillionsOfOccurrences() {
        val roots = (1L..1000).map { template.copy(id = it) }
        val values = EventEngine.occurrences(roots, day, day.plusYears(10), ZoneOffset.UTC)
        assertEquals(10000, values.size)
        assertEquals(day.plusDays(9), values.last().start.toLocalDate())
        assertEquals(1000, values.take(1000).map { it.event.id }.distinct().size)
    }
    @Test fun boundUsesEarliestActualDatesIncludingOverridesOutsideOriginalRange() {
        val p = template.copy(id = 1); val replacement = p.copy(start = "2026-01-01T06:00", end = "2026-01-01T07:00", repeat = Repeat.NONE)
        val changes = listOf(EventException(1, "2026-12-01T09:00", replacement))
        val values = EventEngine.occurrences(listOf(p), day, day.plusDays(3), ZoneOffset.UTC, changes, maxOccurrences = 1)
        assertEquals(1, values.size); assertTrue(values.single().changed); assertEquals(6, values.single().start.hour)
    }
    @Test fun filtersAreAppliedBeforeTheBoundAndCanSelectOnlyAnOverride() {
        val roots = (1L..30).map { template.copy(id = it) }
        val changed = roots.first().copy(start = "2026-01-03T15:00", end = "2026-01-03T16:00", repeat = Repeat.NONE, calendarId = 2)
        val values = EventEngine.occurrences(roots, day, day.plusDays(7), ZoneOffset.UTC, listOf(EventException(1, template.start, changed)), maxOccurrences = 1, filterEvent = { it.calendarId == 2L })
        assertEquals(1, values.size); assertEquals(15, values.single().start.hour); assertTrue(values.single().changed)
    }
}
