package de.luma.calendar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.*
import de.luma.calendar.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HolidayV4Test {
    private val fixture = """[{"date":"2026-01-01","name":"New Year's Day","countryCode":"DE","nationalHoliday":true,"subdivisionCodes":null,"holidayTypes":["Public"]},{"date":"2026-10-31","name":"Reformation Day","countryCode":"DE","nationalHoliday":false,"subdivisionCodes":["DE-BB"],"holidayTypes":["Public"]}]"""
    @Test fun newV4ScopeAndTypeFieldsRetainCountryRegionAndGermanNames() {
        val values = HolidayStore.parseApiV4(fixture, "DE", 2026)
        assertEquals("Neujahr", values.first().localName); assertTrue(values.first().national)
        assertEquals(setOf("DE-BB"), values.last().regions); assertFalse(values.last().national)
        assertThrows(IllegalArgumentException::class.java) { HolidayStore.parseApiV4(fixture, "AT", 2026) }
    }
    @Test fun annualIcsHolidaySeriesStayReadOnlyAndTimedImportCannotReplaceCache() {
        val context: Application = ApplicationProvider.getApplicationContext()
        val store = HolidayStore(context)
        val event = Event(id = 1, title = "Local holiday", start = "2026-01-01T00:00", end = "2026-01-02T00:00", allDay = true, repeat = Repeat.YEARLY)
        val ics = IcsFiles.write(listOf(event), UserCalendar.defaults(), emptyList())
        store.importCalendarIcs(ics, LocalDate.parse("2026-01-01"))
        val imported = store.read().holidays.filter { it.imported }
        assertEquals(5, imported.size); assertTrue(imported.all { it.event(java.util.Locale.GERMAN).id < 0 })
        val timed = event.copy(start = "2026-01-01T09:00", end = "2026-01-01T10:00", allDay = false)
        assertThrows(IllegalArgumentException::class.java) { store.importCalendarIcs(IcsFiles.write(listOf(timed), UserCalendar.defaults(), emptyList())) }
        assertEquals(imported, store.read().holidays.filter { it.imported })
    }
}
