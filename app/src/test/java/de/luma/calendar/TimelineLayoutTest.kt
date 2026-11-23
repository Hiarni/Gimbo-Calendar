package de.luma.calendar

import de.luma.calendar.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimelineLayoutTest {
    private val day = LocalDate.parse("2026-10-11")
    private val zone = ZoneId.of("Europe/Berlin")
    private fun occurrence(id: Long, from: Int, to: Int) = Event(id = id, title = "Event $id", start = day.atStartOfDay().plusMinutes(from.toLong()).toString(), end = day.atStartOfDay().plusMinutes(to.toLong()).toString()).let { e -> Occurrence(e, LocalDateTime.parse(e.start).atZone(zone), LocalDateTime.parse(e.end).atZone(zone)) }
    @Test fun overlappingEventsUseLanesAndAdjacentEventsAreNotConflicts() {
        val blocks = TimelineLayout.blocks(listOf(occurrence(1, 540, 600), occurrence(2, 570, 630), occurrence(3, 630, 690)), day, zone)
        assertEquals(listOf(true, true, false), blocks.map { it.conflict }); assertEquals(listOf(0, 1, 0), blocks.map { it.lane }); assertEquals(2, blocks.first().lanes)
    }
    @Test fun shortEventsHaveSeparateTouchableLanesWithoutFalseConflict() {
        val blocks = TimelineLayout.blocks(listOf(occurrence(1, 540, 545), occurrence(2, 550, 555)), day, zone)
        assertEquals(2, blocks.first().lanes); assertFalse(blocks.any { it.conflict })
    }
    @Test fun multidayClippingAllDayExclusionAndSnapping() {
        val o = occurrence(1, -60, 1500)
        val blocks = TimelineLayout.blocks(listOf(o, o.copy(event = o.event.copy(id = 2, allDay = true))), day, zone)
        assertEquals(1, blocks.size); assertEquals(0, blocks.single().startMinute); assertEquals(1440, blocks.single().endMinute)
        assertEquals(0, TimelineLayout.snappedMinute(-5f)); assertEquals(1425, TimelineLayout.snappedMinute(1480f)); assertEquals(555, TimelineLayout.snappedMinute(552f))
    }
    @Test fun fallBackRepeatedClockHourHasPositiveVisibleHeight() {
        val date = LocalDate.parse("2026-10-25")
        val start = ZonedDateTime.ofLocal(date.atTime(2, 45), zone, ZoneOffset.ofHours(2))
        val end = ZonedDateTime.ofLocal(date.atTime(2, 15), zone, ZoneOffset.ofHours(1))
        val o = Occurrence(Event(id = 1, title = "DST", start = "2026-10-25T02:45", end = "2026-10-25T03:15"), start, end)
        val block = TimelineLayout.blocks(listOf(o), date, zone).single()
        assertTrue(block.endMinute > block.startMinute)
    }
}
