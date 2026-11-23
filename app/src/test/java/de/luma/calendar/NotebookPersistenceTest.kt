package de.luma.calendar

import android.content.res.Configuration
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = CalendarApplication::class, qualifiers = "en-rGB")
class NotebookPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<CalendarApplication>()
    private lateinit var db: CalendarDatabase
    @Before fun setup() {
        context.scope.cancel(); context.deviceConnections.close()
        db = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).addCallback(CalendarDatabase.defaultsCallback).allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }
    @Test fun checklistUpdatesPinArchiveAndCalendarRemovalKeepTasksIndependent(): Unit = runBlocking {
        val taskId = db.tasks().insert(CalendarTask(title = "Keep reminder", due = "2030-10-05", reminderTime = "08:30"))
        val repo = NoteRepository(db)
        val id = repo.save(CalendarNote(title = "Trip", kind = NoteKind.CHECKLIST, items = listOf(NoteItem(text = "Tickets"), NoteItem(text = "Bags")), labels = listOf("Travel", "travel"), color = 0xFFFF7070))
        var note = db.notes().find(id)!!
        assertEquals(listOf("Travel"), note.labels)
        repo.toggleItem(id, note.items.first().id); repo.pin(id); repo.archive(id)
        note = db.notes().find(id)!!
        assertTrue(note.items.first().checked); assertFalse(note.items.last().checked); assertTrue(note.pinned); assertTrue(note.archived)
        val target = db.calendars().all().first { it.id != 1L }.id
        CalendarRepository(db).removeAndMove(1, target)
        assertEquals(target, db.notes().find(id)!!.calendarId)
        assertEquals(target, db.tasks().find(taskId)!!.calendarId)
        assertEquals("08:30", db.tasks().find(taskId)!!.reminderTime)
        repo.remove(id)
        assertNotNull(db.tasks().find(taskId)); assertTrue(db.notes().all().isEmpty())
    }
    @Test fun jsonSevenRestoresNotesWithRemappedIdsAndReadsOlderBackups(): Unit = runBlocking {
        val id = NoteRepository(db).save(CalendarNote(title = "Journal", body = "One\nTwo", labels = listOf("Home"), pinned = true))
        val note = db.notes().find(id)!!
        val text = CalendarFiles.backup(emptyList(), db.calendars().all(), notes = listOf(note))
        assertEquals(7, JSONObject(text).getInt("version"))
        val backup = CalendarFiles.readCalendarBackup(text)
        assertEquals(note, backup.notes.single())
        CalendarRepository(db).import(backup)
        assertEquals(2, db.notes().all().size)
        assertNotEquals(id, db.notes().all().first { it.id != id }.id)
        assertTrue(db.notes().all().all { it.body == "One\nTwo" && it.pinned })
        val legacy = JSONObject(text).put("version", 6).apply { remove("notes") }
        assertTrue(CalendarFiles.readCalendarBackup(legacy.toString()).notes.isEmpty())
        val broken = JSONObject(text).apply { getJSONArray("notes").getJSONObject(0).put("calendarId", 900) }
        assertThrows(IllegalArgumentException::class.java) { CalendarFiles.readCalendarBackup(broken.toString()) }
    }
    @Test fun invalidImportRollsBackCalendarsAndNotePayloadsAreBounded(): Unit = runBlocking {
        val before = db.calendars().all()
        val invalid = CalendarBackup(listOf(UserCalendar(70, "Added only if valid")), emptyList(), notes = listOf(CalendarNote(id = 9, title = "Bad", calendarId = 900)))
        try { CalendarRepository(db).import(invalid); fail("Must reject dangling calendar") } catch (_: IllegalStateException) { }
        assertEquals(before, db.calendars().all()); assertTrue(db.notes().all().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { CalendarNote(body = "x".repeat(20001)).checked() }
        assertThrows(IllegalArgumentException::class.java) { CalendarNote(title = "Bad", kind = NoteKind.CHECKLIST, items = listOf(NoteItem("same", "A"), NoteItem("same", "B"))).checked() }
        assertThrows(IllegalArgumentException::class.java) { CalendarNote(title = "Bad", color = 0x00112233).checked() }
        assertThrows(IllegalArgumentException::class.java) { NotePayloadConverter().readItems("[" + List(201) { "{\"id\":\"$it\",\"text\":\"x\",\"checked\":false}" }.joinToString(",") + "]") }
    }
    @Test fun realSchemaSixMigratesWithoutLosingExistingEventsOrTaskReminders(): Unit = runBlocking {
        val name = "notes-schema-six"
        context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("de.luma.calendar.data.CalendarDatabase/6.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices")
            for (j in 0 until (indices?.length() ?: 0)) old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        old.execSQL("INSERT INTO calendars (id,name,color,defaultCategory) VALUES(1,'Keep',4288539626,NULL)")
        old.execSQL("INSERT INTO events (id,title,start,end,zone,allDay,category,location,notes,repeat,interval,until,reminderMinutes,calendarId) VALUES(99,'Keep','2030-10-05T09:00','2030-10-05T10:00','Europe/Vienna',0,'PERSONAL','','','NONE',1,NULL,15,1)")
        old.execSQL("INSERT INTO tasks (id,title,due,completed,repeat,interval,anchor,calendarId,reminderTime) VALUES(42,'Keep task','2030-10-05',0,'NONE',1,NULL,1,'08:30')")
        old.version = 6; old.close()
        val migrated = CalendarDatabase.builder(context, name).build()
        try {
            assertEquals("Keep", migrated.events().find(99)!!.title)
            assertEquals("08:30", migrated.tasks().find(42)!!.reminderTime)
            assertTrue(migrated.notes().all().isEmpty())
            assertTrue(NoteRepository(migrated).save(CalendarNote(title = "After migration")) > 0)
            assertEquals(7, migrated.openHelper.readableDatabase.version)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun everyLanguageHasNativeNavigationAndUnsupportedTextFallsBackToEnglish() {
        assertEquals(33, AppLanguage.entries.count { it != AppLanguage.SYSTEM })
        for (language in AppLanguage.entries.filter { it != AppLanguage.SYSTEM }) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) }
            val localized = context.createConfigurationContext(config)
            assertTrue(localized.text("Kalender").isNotBlank())
            assertTrue(localized.text("Notizblock").isNotBlank())
        }
        val french = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) })
        assertEquals("Calendrier", french.text("Kalender"))
        assertEquals("Bloc-notes", french.text("Notizblock"))
        assertEquals("The note is too large.", french.text("Notiz ist zu groß."))
        assertEquals(AppLanguage.CHINESE_TRADITIONAL, AppLanguage.fromTag("zh-TW"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-Hans-CN"))
        assertEquals(AppLanguage.NORWEGIAN, AppLanguage.fromTag("no-NO"))
    }
    @Test fun customColourAndMonthAppearanceAreStoredWithoutRestrictingPalette() {
        val store = SettingsStore(context)
        store.write(store.read().copy(accent = 0xFF123456, monthStyle = MonthStyle.COMPACT, highlightHolidays = false, highlightWeekends = false, eventCounts = false))
        val value = store.read()
        assertEquals(0xFF123456, value.accent); assertEquals(MonthStyle.COMPACT, value.monthStyle)
        assertFalse(value.highlightHolidays); assertFalse(value.highlightWeekends); assertFalse(value.eventCounts)
    }
}
