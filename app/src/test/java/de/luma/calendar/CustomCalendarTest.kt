package de.luma.calendar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.*
import de.luma.calendar.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CustomCalendarTest {
    private lateinit var context: Context
    private lateinit var db: CalendarDatabase
    private lateinit var repository: CalendarRepository
    private val series = Event(title = "Sport, draußen", start = "2026-10-10T18:00", end = "2026-10-10T19:00",
        zone = "Europe/Berlin", notes = "Mitbringen: Schuhe", location = "Park", repeat = Repeat.WEEKLY,
        interval = 2, until = "2027-10-10", reminderMinutes = 30)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = CalendarDatabase.memoryBuilder(context).build()
        repository = CalendarRepository(db)
    }
    @After fun close() { db.close() }

    @Test fun upgradePreservesEveryCategorySeriesAndRetiredEventIds() = runBlocking { upgrade() }
    @Test @Config(sdk = [29]) fun upgradeAlsoWorksOnAndroid10() = runBlocking { upgrade() }
    @Test fun upgradePreservesRetiredIdsEvenWhenAllOldEventsWereDeleted() = runBlocking { upgrade(empty = true) }
    private suspend fun upgrade(empty: Boolean = false) {
        val name = "calendar-upgrade.db"
        context.deleteDatabase(name)
        val originals = UserCalendar.defaults().mapIndexed { index, c ->
            series.copy(id = 41L + index, category = c.defaultCategory!!, calendarId = c.id)
        }
        val schema = javaClass.classLoader!!.getResourceAsStream("de.luma.calendar.data.CalendarDatabase/1.json")!!.use {
            JSONObject(it.bufferedReader().readText()).getJSONObject("database")
        }
        val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        old.execSQL(schema.getJSONArray("entities").getJSONObject(0).getString("createSql").replace("\${TABLE_NAME}", "events"))
        originals.forEach { e ->
            old.insertOrThrow("events", null, ContentValues().apply {
                put("id", e.id); put("title", e.title); put("start", e.start); put("end", e.end); put("zone", e.zone)
                put("allDay", if (e.allDay) 1 else 0); put("category", e.category.name); put("location", e.location)
                put("notes", e.notes); put("repeat", e.repeat.name); put("interval", e.interval)
                put("until", e.until); put("reminderMinutes", e.reminderMinutes)
            })
        }
        old.execSQL("UPDATE sqlite_sequence SET seq = 999 WHERE name = 'events'")
        if (empty) old.delete("events", null, null)
        old.version = 1
        old.close()
        val migrated = CalendarDatabase.builder(context, name).build()
        try {
            assertEquals(if (empty) emptyList<Event>() else originals, migrated.events().all())
            assertEquals(UserCalendar.defaults(), migrated.calendars().all())
            assertTrue(migrated.events().insert(series) > 999)
            val orphan = runCatching { migrated.events().insert(series.copy(calendarId = 9999)) }.exceptionOrNull()
            assertTrue(orphan is SQLiteConstraintException)
            val deletion = runCatching { migrated.calendars().delete(1) }.exceptionOrNull()
            assertTrue(deletion is SQLiteConstraintException)
        } finally { migrated.close() }
        CalendarDatabase.builder(context, name).build().let { reopened ->
            try { assertEquals(if (empty) emptyList<Event>() else originals, reopened.events().all().filter { it.id < 999 }) }
            finally { reopened.close() }
        }
        context.deleteDatabase(name)
    }

    @Test fun renameColorAndDefaultPreferenceSurviveReopening() = runBlocking {
        val name = "calendar-reopen.db"
        context.deleteDatabase(name)
        var id = 0L
        CalendarDatabase.builder(context, name).build().let { disk ->
            try {
                val repo = CalendarRepository(disk)
                id = repo.save(UserCalendar(name = " Sport ", color = 0xFF1976D2))
                repo.save(UserCalendar(id, "Training", 0xFFD32F2F))
            } finally { disk.close() }
        }
        val settings = SettingsStore(context)
        settings.write(settings.read().copy(defaultCalendarId = id))
        CalendarDatabase.builder(context, name).build().let { disk ->
            try { assertEquals(UserCalendar(id, "Training", 0xFFD32F2F), disk.calendars().find(id)) }
            finally { disk.close() }
        }
        assertEquals(id, SettingsStore(context).read().defaultCalendarId)
        context.deleteDatabase(name)
        Unit
    }

    @Test fun removingCalendarMovesSeriesWithoutChangingIdsOrReminders() = runBlocking {
        val source = repository.save(UserCalendar(name = "Sport"))
        val id = db.events().insert(series.copy(calendarId = source))
        assertEquals(1, repository.removeAndMove(source, Category.WORK.defaultCalendarId))
        assertNull(db.calendars().find(source))
        assertEquals(series.copy(id = id, calendarId = 2), db.events().find(id))
        assertTrue(runCatching { repository.removeAndMove(2, 2) }.isFailure)
        assertTrue(runCatching { repository.removeAndMove(2, 999) }.isFailure)
        assertEquals(series.copy(id = id, calendarId = 2), db.events().find(id))
        repository.removeAndMove(1, 2); repository.removeAndMove(3, 2); repository.removeAndMove(4, 2)
        assertTrue(runCatching { repository.removeAndMove(2, 2) }.isFailure)
        assertEquals(1, db.calendars().all().size)
    }

    @Test fun backupRestoresCalendarMetadataAndRemapsConflictingIdsAndNames() = runBlocking {
        val original = UserCalendar(1, "Sport", 0xFF1976D2)
        repository.save(UserCalendar(name = "Sport", color = 0xFFD32F2F))
        val backup = CalendarFiles.readCalendarBackup(CalendarFiles.backup(listOf(series.copy(id = 98)), listOf(original)))
        assertEquals(1, repository.import(backup))
        val restored = db.calendars().all().single { it.color == original.color }
        assertNotEquals(1L, restored.id)
        assertEquals("Sport (Import 2)", restored.name)
        val restoredEvent = db.events().all().single()
        assertEquals(series.copy(id = restoredEvent.id, calendarId = restored.id), restoredEvent)
        assertEquals(1, repository.import(backup))

        assertEquals(2, db.events().all().size)
        assertEquals(6, db.calendars().all().size)
        assertTrue(db.events().all().all { it.calendarId == restored.id })
    }

    @Test fun oldBackupsRestoreCategoriesAndNewBackupsAllowEmptyCalendars() = runBlocking {
        val root = JSONObject(CalendarFiles.backup(listOf(series.copy(category = Category.FAMILY, calendarId = 3))))
        root.put("version", 1); root.remove("calendars"); root.getJSONArray("events").getJSONObject(0).remove("calendarId")
        assertEquals(1, repository.import(CalendarFiles.readCalendarBackup(root.toString())))
        assertEquals(3L, db.events().all().single().calendarId)
        val empty = CalendarFiles.readCalendarBackup(CalendarFiles.backup(emptyList(), listOf(UserCalendar(88, "Reisen", 0xFFA5D6BE))))
        assertEquals(0, repository.import(empty))
        assertTrue(db.calendars().all().any { it.name == "Reisen" })
    }

    @Test fun malformedBackupsAndLateImportFailureLeaveDatabaseUntouched() = runBlocking {
        val original = UserCalendar(77, "Neu")
        val missing = CalendarFiles.backup(listOf(series.copy(calendarId = 99)), listOf(original))
        assertThrows(IllegalArgumentException::class.java) { CalendarFiles.readCalendarBackup(missing) }
        val duplicate = CalendarFiles.backup(emptyList(), listOf(original, original.copy(name = "Doppelt")))
        assertThrows(IllegalArgumentException::class.java) { CalendarFiles.readCalendarBackup(duplicate) }
        val invalid = CalendarBackup(listOf(original), listOf(series.copy(calendarId = 77), series.copy(title = "", calendarId = 77)))
        assertTrue(runCatching { repository.import(invalid) }.isFailure)
        assertEquals(UserCalendar.defaults(), db.calendars().all())
        assertTrue(db.events().all().isEmpty())
    }

    @Test fun localizedDuplicateNamesAndCalendarLimitsAreEnforced() = runBlocking {
        val translate: (String) -> String = { if (it == "Arbeit") "Work" else it }
        assertTrue(runCatching { repository.save(UserCalendar(name = " work "), translate) }.isFailure)
        assertEquals("work", UserCalendar(name = "work").displayName { "übersetzt" })
        assertThrows(IllegalArgumentException::class.java) { UserCalendar(name = " ").checked() }
        assertThrows(IllegalArgumentException::class.java) { UserCalendar(name = "Rot", color = 0x80FF0000).checked() }
        repeat(UserCalendar.MAX_COUNT - 4) { repository.save(UserCalendar(name = "Kalender $it")) }
        val before = db.calendars().all()
        assertTrue(runCatching { repository.save(UserCalendar(name = "Über Limit")) }.isFailure)
        assertTrue(runCatching { repository.import(CalendarBackup(listOf(UserCalendar(999, "Import")), emptyList())) }.isFailure)
        assertEquals(before, db.calendars().all())
    }
}
