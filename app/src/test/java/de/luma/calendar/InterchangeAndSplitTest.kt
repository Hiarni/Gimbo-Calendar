package de.luma.calendar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.*
import de.luma.calendar.model.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InterchangeAndSplitTest {
    private lateinit var db: CalendarDatabase
    private lateinit var repo: EventRepository
    private val template = Event(title = "Yoga, Café; \\", start = "2026-10-01T09:00", end = "2026-10-01T10:00", zone = "Europe/Berlin",
        repeat = Repeat.WEEKLY, reminderMinutes = 15, notes = "Line one\nLine two")
    @Before fun setup() { db = CalendarDatabase.memoryBuilder(ApplicationProvider.getApplicationContext()).build(); repo = EventRepository(db) }
    @After fun close() { db.close() }
    private suspend fun parent(e: Event = template) = e.copy(id = repo.save(e))
    @Test fun splitKeepsPastAndTransfersUnchangedFutureExceptions() = runBlocking {
        val p = parent()
        repo.exception(p, "2026-10-01T09:00", null)
        repo.exception(p, "2026-10-15T09:00", p.futureDraft("2026-10-15T09:00").copy(title = "Special", repeat = Repeat.NONE, until = null))
        val id = repo.saveFuture(p, "2026-10-08T09:00", p.futureDraft("2026-10-08T09:00").copy(title = "New title"))
        assertNotEquals(p.id, id)
        assertEquals("2026-10-07", db.events().find(p.id)!!.until)
        assertEquals(p.start, db.events().find(p.id)!!.start)
        assertEquals(p.id, db.exceptions().all().first().eventId)
        val future = db.exceptions().all().last()
        assertEquals(id, future.eventId); assertEquals(id, future.replacement!!.id)
        assertEquals(listOf("New title", "Special", "New title"), EventEngine.occurrences(db.events().all(), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-23"), exceptions = db.exceptions().all()).map { it.event.title })
    }
    @Test fun splitRhythmChangeResetsOnlyFutureAndUsesOriginalDateForMovedException() = runBlocking {
        val p = parent()
        repo.exception(p, "2026-10-01T09:00", p.copy(start = "2026-11-01T14:00", end = "2026-11-01T15:00", repeat = Repeat.NONE))
        repo.exception(p, "2026-10-15T09:00", null)
        val id = repo.saveFuture(p, "2026-10-08T09:00", p.futureDraft("2026-10-08T09:00").copy(start = "2026-10-08T18:00", end = "2026-10-08T19:00"))
        assertEquals(1, db.exceptions().all().size); assertEquals(p.id, db.exceptions().all().single().eventId)
        assertEquals("2026-10-08T18:00", db.events().find(id)!!.start)
    }
    @Test fun splitAtFirstKeepsIdentityAndRejectsStaleOrInvalidRequestsAtomically() = runBlocking {
        val p = parent()
        assertEquals(p.id, repo.saveFuture(p, p.start, p.copy(title = "Changed")))
        assertEquals(1, db.events().all().size)
        try { repo.saveFuture(p, "2026-10-08T09:00", p.futureDraft("2026-10-08T09:00")); fail() } catch (_: IllegalArgumentException) { }
        val current = db.events().find(p.id)!!
        try { repo.saveFuture(current, "2026-10-09T09:00", current.copy(calendarId = 99)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(current, db.events().find(p.id)); assertEquals(1, db.events().all().size)
    }
    @Test fun splitMonthlyAndLeapYearIntervalsRetainAnchors() = runBlocking {
        for ((e, cut, next) in listOf(
            Triple(template.copy(start = "2026-01-31T09:00", end = "2026-01-31T10:00", repeat = Repeat.MONTHLY, interval = 2), "2026-03-31T09:00", "2026-05-31"),
            Triple(template.copy(start = "2024-02-29T09:00", end = "2024-02-29T10:00", repeat = Repeat.YEARLY), "2028-02-29T09:00", "2032-02-29"))) {
            val p = parent(e); val id = repo.saveFuture(p, cut, p.futureDraft(cut)); val future = db.events().find(id)!!
            assertTrue(EventEngine.isOccurrence(future, LocalDate.parse(next).atTime(9, 0)))
        }
    }
    @Test fun fullIcsRoundTripIncludesSeriesColorsEscapesReminderOverridesAndSkips() = runBlocking {
        val p = parent()
        val changed = p.copy(start = "2026-10-19T18:00", end = "2026-10-19T19:00", zone = "America/New_York", calendarId = 2, title = "New place", repeat = Repeat.NONE, reminderMinutes = -1)
        repo.exception(p, "2026-10-08T09:00", changed); repo.exception(p, "2026-10-15T09:00", null)
        val text = IcsFiles.write(db.events().all(), db.calendars().all(), db.exceptions().all())
        assertTrue(text.contains("BEGIN:VTIMEZONE")); assertTrue(text.contains("RRULE:FREQ=WEEKLY")); assertTrue(text.contains("STATUS:CANCELLED"))
        assertTrue(text.split("\r\n").all { it.toByteArray().size <= 75 })
        val imported = IcsFiles.read(text)
        assertEquals(p.copy(id = 1), imported.events.single())
        assertEquals(-1, imported.exceptions.first { it.replacement != null }.replacement!!.reminderMinutes)
        assertEquals("America/New_York", imported.exceptions.first { it.replacement != null }.replacement!!.zone)
        val id = imported.events.single().id
        assertEquals(listOf("2026-10-08T09:00", "2026-10-15T09:00"), imported.exceptions.filter { it.eventId == id }.map { it.originalStart })
        CalendarRepository(db).import(imported) { it }
        assertEquals(2, db.events().all().size)
    }
    @Test fun dstGapNominalTimesAndAllDayLeapSeriesSurviveIcs() {
        val gap = template.copy(id = 1, start = "2026-03-29T02:30", end = "2026-03-29T04:30", repeat = Repeat.YEARLY)
        val exception = EventException(1, gap.start)
        assertEquals(gap, IcsFiles.read(IcsFiles.write(listOf(gap), UserCalendar.defaults(), listOf(exception))).events.single())
        assertEquals(gap.start, IcsFiles.read(IcsFiles.write(listOf(gap), UserCalendar.defaults(), listOf(exception))).exceptions.single().originalStart)
        val all = gap.copy(start = "2024-02-29T00:00", end = "2024-03-01T00:00", allDay = true, reminderMinutes = -1)
        val read = IcsFiles.read(IcsFiles.write(listOf(all), UserCalendar.defaults(), emptyList()), ZoneId.of("Pacific/Honolulu")).events.single()
        assertEquals(all.start, read.start); assertEquals(all.end, read.end); assertTrue(read.allDay)
    }
    private fun file(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$body\r\nEND:VCALENDAR\r\n"
    private fun event(extra: String = "") = "BEGIN:VEVENT\r\nUID:test\r\nDTSTART;TZID=Europe/Berlin:20260131T090000\r\nDTEND;TZID=Europe/Berlin:20260131T100000\r\nSUMMARY:Test\r\n$extra\r\nEND:VEVENT"
    @Test fun countSkipsInvalidMonthsAndUntilRespectsTimeOfDay() {
        val count = IcsFiles.read(file(event("RRULE:FREQ=MONTHLY;COUNT=3"))).events.single()
        assertEquals("2026-05-31", count.until)
        val until = IcsFiles.read(file(event("RRULE:FREQ=DAILY;UNTIL=20260202T070000Z"))).events.single()
        assertEquals("2026-02-01", until.until)
    }
    @Test fun externalExdateAndReplacementHaveOneExceptionWithParentInheritance() {
        val root = event("RRULE:FREQ=MONTHLY\r\nEXDATE;TZID=Europe/Berlin:20260331T090000")
        val override = "BEGIN:VEVENT\r\nUID:test\r\nRECURRENCE-ID;TZID=Europe/Berlin:20260331T090000\r\nDTSTART;TZID=Europe/Berlin:20260401T100000\r\nDTEND;TZID=Europe/Berlin:20260401T110000\r\nEND:VEVENT"
        val read = IcsFiles.read(file("$root\r\n$override"))
        assertEquals(1, read.exceptions.size); assertEquals("Test", read.exceptions.single().replacement!!.title)
    }
    @Test fun unsupportedRulesCustomZonesAndDanglingExceptionsFailBeforeImport() {
        for (value in listOf(file(event("RRULE:FREQ=WEEKLY;BYDAY=MO,WE")), file(event("RDATE:20260201T090000Z")),
            file(event().replace("Europe/Berlin", "Custom/Planet")), file(event().replace("UID:test", "UID:test\r\nRECURRENCE-ID:20260201T090000Z")))) {
            try { IcsFiles.read(value); fail(value) } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }
}
