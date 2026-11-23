package de.luma.calendar

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
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
@Config(sdk = [35], application = android.app.Application::class)
class PersistenceTest {
    private lateinit var context: Context
    private lateinit var db: CalendarDatabase
    private val event = Event(title = "Familie, Café; Urlaub", start = "2026-10-10T09:00", end = "2026-10-10T10:00", zone = "Europe/Vienna",
        category = Category.FAMILY, repeat = Repeat.WEEKLY, interval = 2, reminderMinutes = 15, notes = "Zeile 1\nZeile 2 \\ Ende", until = "2027-10-10")
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); db = CalendarDatabase.memoryBuilder(context).build() }
    @After fun close() { db.close() }
    @Test fun databaseCrudAndEnumsRoundTrip() = runBlocking {
        val id = db.events().insert(event.checked())
        assertEquals(event.copy(id = id), db.events().find(id))
        assertEquals(1, db.events().update(event.copy(id = id, title = "Neu")))
        assertEquals("Neu", db.events().find(id)!!.title)
        assertEquals(1, db.events().delete(id))
        assertNull(db.events().find(id))
    }
    @Test fun databaseSurvivesReopening() = runBlocking {
        val name = "reopen-test.db"
        context.deleteDatabase(name)
        CalendarDatabase.builder(context, name).build().let { disk -> disk.events().insert(event); disk.close() }
        CalendarDatabase.builder(context, name).build().let { disk -> assertEquals(event.copy(id = 1), disk.events().all().single()); disk.close() }
        context.deleteDatabase(name)
        Unit
    }
    @Test fun settingsSurviveNewStoreInstance() {
        val settings = AppSettings(ThemeMode.DARK, 0xFFA5D6BE, mondayFirst = false, weekNumbers = false, startView = CalendarView.DAY)
        SettingsStore(context).write(settings)
        assertEquals(settings, SettingsStore(context).read())
    }
    @Test fun backupPreservesSeriesAndEscapingAndClearsIds() {
        val result = CalendarFiles.readBackup(CalendarFiles.backup(listOf(event.copy(id = 99))))
        assertEquals(listOf(event.checked()), result)
    }
    @Test fun invalidBackupDoesNotModifyDatabase() = runBlocking {
        db.events().insert(event)
        val text = CalendarFiles.backup(listOf(event, event.copy(title = "")))
        assertThrows(IllegalArgumentException::class.java) { CalendarFiles.readBackup(text) }
        assertEquals(1, db.events().all().size)
    }
    @Test fun importTransactionRollsBackOnFailure() = runBlocking {
        db.events().insert(event)
        try { db.withTransaction { db.events().insert(event); error("interrupted") } } catch (_: IllegalStateException) { }
        assertEquals(1, db.events().all().size)
    }
    @Test fun icsEscapesAndUsesCrLf() {
        val text = CalendarFiles.ics(listOf(event.copy(id = 1, repeat = Repeat.NONE)), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), ZoneId.of("Europe/Vienna"))
        assertTrue(text.contains("SUMMARY:Familie\\, Café\\; Urlaub\r\n"))
        assertTrue(text.contains("DTSTART:20261010T070000Z\r\n"))
        assertTrue(text.contains("DESCRIPTION:Zeile 1\\nZeile 2 \\\\ Ende"))
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
    }
    @Test fun icsFoldsUtf8WithoutSplittingCharacters() {
        val text = CalendarFiles.ics(listOf(event.copy(title = "ä".repeat(120))), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))
        assertTrue(text.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertTrue(text.replace("\r\n ", "").contains("SUMMARY:${"ä".repeat(120)}"))
    }
    @Test fun icsAllDayUsesExclusiveEnd() {
        val e = event.copy(start = "2026-10-10T00:00", end = "2026-10-12T00:00", allDay = true, repeat = Repeat.NONE)
        val text = CalendarFiles.ics(listOf(e), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261010"))
        assertTrue(text.contains("DTEND;VALUE=DATE:20261012"))
    }
    @Test fun icsExportsOccurrencesWithinExplicitHorizon() {
        val text = CalendarFiles.ics(listOf(event), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 1))
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(text).count())
        assertFalse(text.contains("RRULE"))
    }
}
