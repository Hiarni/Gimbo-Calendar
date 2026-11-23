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
class PlanningPersistenceTest {
    private lateinit var db: CalendarDatabase
    @Before fun setup() { db = CalendarDatabase.memoryBuilder(ApplicationProvider.getApplicationContext()).build() }
    @After fun close() { db.close() }
    private val event = Event(title = "Meeting", start = "2026-10-01T09:00", end = "2026-10-01T10:30", calendarId = 2, repeat = Repeat.WEEKLY, until = "2026-10-15")
    @Test fun recurringTasksSkipMissingDatesKeepAnchorAndSimpleTasksCanBeReopened() {
        val task = CalendarTask(title = "Monthly", due = "2026-01-31", repeat = Repeat.MONTHLY).checked()
        val next = task.finish(); assertEquals("2026-03-31", next.due); assertEquals("2026-01-31", next.anchor)
        assertEquals("2026-05-31", next.finish().due)
        assertEquals("2028-02-29", CalendarTask(title = "Leap", due = "2024-02-29", repeat = Repeat.YEARLY).checked().finish().due)
        assertFalse(CalendarTask(title = "One-off").finish().finish().completed)
        assertThrows(IllegalArgumentException::class.java) { CalendarTask(title = "Bad", repeat = Repeat.WEEKLY).checked() }
    }
    @Test fun calendarRemovalMovesTasksAndTemplatePayloadTogether() = runBlocking {
        val task = CalendarTask(title = "Work task", due = "2026-10-01", calendarId = 2)
        val tid = db.tasks().insert(task.checked()); val templateId = db.templates().insert(EventTemplate(name = "Work meeting", event = event).checked())
        CalendarRepository(db).removeAndMove(2, 1)
        assertEquals(1L, db.tasks().find(tid)!!.calendarId)
        val template = db.templates().all().single(); assertEquals(templateId, template.id); assertEquals(1L, template.calendarId); assertEquals(1L, template.event.calendarId)
    }
    @Test fun fullBackupMapsTaskAndTemplateCalendarsAndPreservesRelativeTemplateEnd() = runBlocking {
        val calendar = UserCalendar(88, "Project", 0xFF9DCBEA)
        val template = EventTemplate(name = "Planning", event = event.copy(calendarId = 88)).checked()
        val task = CalendarTask(title = "Monthly", due = "2026-01-31", repeat = Repeat.MONTHLY, calendarId = 88).checked()
        val text = CalendarFiles.backup(emptyList(), listOf(calendar), tasks = listOf(task), templates = listOf(template))
        val backup = CalendarFiles.readCalendarBackup(text)
        CalendarRepository(db).import(backup)
        val id = db.calendars().all().first { it.name == "Project" }.id
        assertEquals(task.copy(id = db.tasks().all().single().id, calendarId = id), db.tasks().all().single())
        val imported = db.templates().all().single(); assertEquals(id, imported.event.calendarId)
        val draft = imported.draft(LocalDate.parse("2027-01-04")).checked()
        assertEquals("2027-01-04T09:00", draft.start); assertEquals("2027-01-04T10:30", draft.end); assertEquals("2027-01-18", draft.until)
    }
    @Test fun lateInvalidTaskRollsBackNewCalendarsAndEvents() = runBlocking {
        val backup = CalendarBackup(listOf(UserCalendar(88, "Project")), listOf(event.copy(calendarId = 88)), tasks = listOf(CalendarTask(title = "", calendarId = 88)))
        try { CalendarRepository(db).import(backup); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(4, db.calendars().all().size); assertTrue(db.events().all().isEmpty()); assertTrue(db.tasks().all().isEmpty())
    }
    @Test fun oldVersion3BackupNeedsNoTasksOrTemplates() {
        val root = org.json.JSONObject(CalendarFiles.backup(listOf(event)))
        root.put("version", 3); root.remove("tasks"); root.remove("templates")
        val backup = CalendarFiles.readCalendarBackup(root.toString())
        assertTrue(backup.tasks.isEmpty()); assertTrue(backup.templates.isEmpty()); assertEquals(Repeat.WEEKLY, backup.events.single().repeat)
    }
    @Test fun actualSchema3UpgradePreservesAnExistingMovedExceptionAndAddsTasksAndTemplates() = runBlocking {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        val name = "planning-upgrade.db"; context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("de.luma.calendar.data.CalendarDatabase/3.json")!!.use {
            org.json.JSONObject(it.bufferedReader().readText()).getJSONObject("database")
        }
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices")
            for (j in 0 until (indices?.length() ?: 0)) old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        UserCalendar.defaults().forEach { c -> old.execSQL("INSERT INTO calendars (id,name,color,defaultCategory) VALUES (?,?,?,?)", arrayOf<Any>(c.id, c.name, c.color, c.defaultCategory!!.name)) }
        val parent = event.copy(id = 99)
        old.execSQL("INSERT INTO events (id,title,start,end,zone,allDay,category,location,notes,repeat,interval,until,reminderMinutes,calendarId) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(parent.id, parent.title, parent.start, parent.end, parent.zone, 0, parent.category.name, parent.location, parent.notes, parent.repeat.name, parent.interval, parent.until, parent.reminderMinutes, parent.calendarId))
        val replacement = parent.copy(start = "2026-10-09T11:00", end = "2026-10-09T12:30", repeat = Repeat.NONE, until = null, calendarId = 3)
        old.execSQL("INSERT INTO event_exceptions (eventId,originalStart,replacement,calendarId) VALUES (?,?,?,?)", arrayOf<Any>(99L, "2026-10-08T09:00", EventCodec.json(replacement).toString(), 3L))
        old.version = 3; old.close()
        val disk = CalendarDatabase.builder(context, name).build()
        try {
            assertEquals(parent, disk.events().find(99)); assertEquals(replacement, disk.exceptions().all().single().replacement)
            disk.tasks().insert(CalendarTask(title = "After upgrade", calendarId = 3))
            disk.templates().insert(EventTemplate(name = "After upgrade", event = replacement.copy(id = 0)))
            assertEquals(1, disk.tasks().all().size); assertEquals(1, disk.templates().all().size)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

}
