package de.luma.calendar

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.*
import de.luma.calendar.model.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExceptionPersistenceTest {
    private lateinit var context: Context
    private lateinit var db: CalendarDatabase
    private lateinit var repository: EventRepository
    private val template = Event(title = "Training, Café", start = "2026-10-01T09:00", end = "2026-10-01T10:00",
        zone = "Europe/Berlin", repeat = Repeat.WEEKLY, reminderMinutes = 15, notes = "Eine\nNotiz; \\ Ende", until = "2027-10-01")
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); db = CalendarDatabase.memoryBuilder(context).build(); repository = EventRepository(db) }
    @After fun close() { db.close() }
    private suspend fun parent() = template.copy(id = repository.save(template))
    private fun replacement(parent: Event, calendarId: Long = 1) = parent.copy(title = "Einzeln geändert", start = "2026-10-09T18:00", end = "2026-10-09T19:00",
        repeat = Repeat.NONE, interval = 1, until = null, calendarId = calendarId)

    @Test fun upgradeFromSchema2KeepsCustomCalendarsAndEventsAndAllowsPersistentExceptions() = runBlocking {
        val name = "series-upgrade.db"
        context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("de.luma.calendar.data.CalendarDatabase/2.json")!!.use {
            JSONObject(it.bufferedReader().readText()).getJSONObject("database")
        }
        val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
        }
        old.execSQL("INSERT INTO calendars (id,name,color,defaultCategory) VALUES (7,'Sport',4279858898,NULL)")
        val original = template.copy(id = 99, calendarId = 7)
        old.execSQL("INSERT INTO events (id,title,start,end,zone,allDay,category,location,notes,repeat,interval,until,reminderMinutes,calendarId) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(original.id, original.title, original.start, original.end, original.zone, 0, original.category.name, original.location, original.notes,
                original.repeat.name, original.interval, original.until, original.reminderMinutes, original.calendarId))
        old.version = 2; old.close()
        val disk = CalendarDatabase.builder(context, name).build()
        try {
            assertEquals(original, disk.events().find(99))
            assertEquals(UserCalendar(7, "Sport", 0xFF1976D2), disk.calendars().find(7))
            assertTrue(disk.exceptions().all().isEmpty())
            EventRepository(disk).exception(original, "2026-10-08T09:00", replacement(original, 7))
        } finally { disk.close() }
        val reopened = CalendarDatabase.builder(context, name).build()
        try {
            assertEquals(EventException(99, "2026-10-08T09:00", replacement(original, 7)), reopened.exceptions().all().single())
            assertEquals(original, reopened.events().find(99))
        } finally { reopened.close() }
        context.deleteDatabase(name)
        Unit
    }
    @Test fun exceptionUpdatesRestoreAndSeriesDeletionAreAtomic() = runBlocking {
        val parent = parent()
        repository.exception(parent, "2026-10-08T09:00", replacement(parent))
        assertEquals(parent, db.events().find(parent.id))
        assertEquals(replacement(parent), db.exceptions().all().single().replacement)
        repository.exception(parent, "2026-10-08T09:00", null)
        assertEquals(1, db.exceptions().all().size)
        assertNull(db.exceptions().all().single().replacement)
        repository.restore(parent.id, "2026-10-08T09:00")
        assertTrue(db.exceptions().all().isEmpty())
        repository.exception(parent, "2026-10-08T09:00", replacement(parent))
        db.events().delete(parent.id)
        assertTrue(db.exceptions().all().isEmpty())
    }
    @Test fun metadataEditsPreserveExceptionsAndPatternEditsResetThem() = runBlocking {
        val parent = parent()
        repository.exception(parent, "2026-10-08T09:00", replacement(parent))
        repository.save(parent.copy(title = "Neue Serie", end = "2026-10-01T11:00"))
        assertEquals(replacement(parent), db.exceptions().all().single().replacement)
        repository.save(parent.copy(start = "2026-10-01T10:00", end = "2026-10-01T11:00"))
        assertTrue(db.exceptions().all().isEmpty())
        assertTrue(runCatching { repository.exception(parent, "2026-10-08T09:00", replacement(parent)) }.isFailure)
        assertTrue(db.exceptions().all().isEmpty())
    }
    @Test fun invalidAndOrphanedExceptionsDoNotChangeAnyData() = runBlocking {
        val parent = parent()
        assertTrue(runCatching { repository.exception(parent, "2026-10-09T09:00", replacement(parent)) }.isFailure)
        assertTrue(runCatching { repository.exception(parent, "2026-10-08T09:00", replacement(parent, 999)) }.isFailure)
        assertTrue(runCatching { repository.exception(parent.copy(id = 999), "2026-10-08T09:00", null) }.isFailure)
        assertTrue(db.exceptions().all().isEmpty())
        assertEquals(listOf(parent), db.events().all())
    }
    @Test fun calendarRemovalMovesOverridePayloadAndForeignKeyTogether() = runBlocking {
        val parent = parent()
        val calendars = CalendarRepository(db)
        val id = calendars.save(UserCalendar(name = "Sport"))
        repository.exception(parent, "2026-10-08T09:00", replacement(parent, id))
        assertTrue(runCatching { db.calendars().delete(id) }.isFailure)
        calendars.removeAndMove(id, 2)
        val change = db.exceptions().all().single()
        assertEquals(2L, change.calendarId)
        assertEquals(replacement(parent, 2), change.replacement)
        assertEquals(parent, db.events().find(parent.id))
        assertNull(db.calendars().find(id))
    }
    @Test fun backup3RemapsSeriesAndExceptionCalendarIdsAndPreservesAllFields() = runBlocking {
        val parent = parent()
        repository.exception(parent, "2026-10-08T09:00", replacement(parent, 2))
        repository.exception(parent, "2026-10-15T09:00", null)
        val text = CalendarFiles.backup(db.events().all(), db.calendars().all(), db.exceptions().all())
        val backup = CalendarFiles.readCalendarBackup(text)
        assertEquals(2, backup.exceptions.size)
        val calendars = CalendarRepository(db)
        assertEquals(1, calendars.import(backup))
        val imported = db.events().all().single { it.id != parent.id }
        assertEquals(parent.copy(id = imported.id), imported)
        assertEquals(db.exceptions().forEvent(parent.id).map { it.copy(eventId = imported.id, replacement = it.replacement?.copy(id = imported.id)) }, db.exceptions().forEvent(imported.id))
        val old = JSONObject(text).put("version", 2)
        old.remove("exceptions"); old.getJSONArray("events").getJSONObject(0).remove("id")
        assertTrue(CalendarFiles.readCalendarBackup(old.toString()).exceptions.isEmpty())
    }
    @Test fun invalidBackupExceptionsAreRejectedBeforeDatabaseMutation() = runBlocking {
        val parent = parent()
        repository.exception(parent, "2026-10-08T09:00", replacement(parent))
        val text = CalendarFiles.backup(db.events().all(), db.calendars().all(), db.exceptions().all())
        val wrongParent = JSONObject(text).apply { getJSONArray("exceptions").getJSONObject(0).put("eventId", 999) }
        assertTrue(runCatching { CalendarFiles.readCalendarBackup(wrongParent.toString()) }.isFailure)
        val wrongDate = JSONObject(text).apply { getJSONArray("exceptions").getJSONObject(0).put("originalStart", "2026-10-09T09:00") }
        assertTrue(runCatching { CalendarFiles.readCalendarBackup(wrongDate.toString()) }.isFailure)
        val duplicate = JSONObject(text).apply { val changes = getJSONArray("exceptions"); changes.put(changes.getJSONObject(0)) }
        assertTrue(runCatching { CalendarFiles.readCalendarBackup(duplicate.toString()) }.isFailure)
        val invalid = CalendarFiles.readCalendarBackup(text).let { it.copy(exceptions = it.exceptions + EventException(it.events.single().id, "2026-10-10T09:00")) }
        assertTrue(runCatching { CalendarRepository(db).import(invalid) }.isFailure)
        assertEquals(listOf(parent), db.events().all())
        assertEquals(1, db.exceptions().all().size)
    }
    @Test fun icsSnapshotIncludesMovedOccurrenceAndOmitsSkippedAndOriginalDates() = runBlocking {
        val parent = parent()
        repository.exception(parent, "2026-10-08T09:00", replacement(parent))
        repository.exception(parent, "2026-10-15T09:00", null)
        val text = CalendarFiles.ics(db.events().all(), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-23"), ZoneId.of("Europe/Berlin"), db.exceptions().all())
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(text).count())
        assertTrue(text.contains("SUMMARY:Einzeln geändert"))
        assertTrue(text.contains("DTSTART:20261009T160000Z"))
        assertFalse(text.contains("DTSTART:20261008"))
        assertFalse(text.contains("DTSTART:20261015"))
    }
}
