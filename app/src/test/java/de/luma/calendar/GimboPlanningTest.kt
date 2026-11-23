package de.luma.calendar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.*
import de.luma.calendar.model.*
import de.luma.calendar.integrations.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class GimboPlanningTest {
    private fun event() = Event(title = "Check", start = "2030-10-05T09:00", end = "2030-10-05T10:00", zone = "Europe/Vienna", reminderMinutes = 15, additionalReminders = listOf(60, 1440))
    @Test fun multipleRemindersRespectOffsetsAndGanztagClockAcrossDst() {
        val now = Instant.parse("2030-10-01T12:00:00Z")
        val values = EventEngine.nextReminders(event(), now)
        assertEquals(setOf(15, 60, 1440), values.map { it.minutes }.toSet())
        values.forEach { assertEquals(it.occurrence.start.toInstant().minusSeconds(it.minutes * 60L), it.trigger) }
        val day = Event(title = "Clock", start = "2030-03-31T00:00", end = "2030-04-01T00:00", allDay = true, reminderMinutes = 1440, additionalReminders = listOf(0), allDayReminderTime = "09:00", zone = "Europe/Vienna")
        val reminders = EventEngine.nextReminders(day, Instant.parse("2030-03-28T12:00:00Z"), ZoneId.of("Europe/Vienna"))
        val atStart = reminders.single { it.minutes == 0 }.trigger
        assertEquals(Instant.parse("2030-03-31T07:00:00Z"), atStart)
        assertEquals(Instant.parse("2030-03-30T08:00:00Z"), reminders.single { it.minutes == 1440 }.trigger)
    }
    @Test fun manyDisabledMovedOccurrencesCannotHideTheNextBaseReminder() {
        val today = LocalDate.of(2030, 10, 5)
        val start = today.minusDays(1200).atTime(9, 0)
        val parent = Event(id = 1, title = "Base", start = start.toString(), end = start.plusHours(1).toString(), repeat = Repeat.DAILY, reminderMinutes = 60, zone = "UTC")
        val replacement = parent.copy(start = today.minusDays(1).atStartOfDay().toString(), end = today.plusDays(1).atStartOfDay().toString(), repeat = Repeat.NONE, reminderMinutes = -1)
        val exceptions = (0L until 1000).map { EventException(1, start.plusDays(it).toString(), replacement) }
        val reminder = EventEngine.nextReminder(parent, today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC, exceptions)
        assertNotNull(reminder)
        assertEquals(today.plusDays(1).atTime(8, 0).toInstant(ZoneOffset.UTC), reminder!!.second)
    }
    @Test fun categoryRemovalKeepsEventsAndClearsExceptionAndTemplateReferences() = runBlocking {
        val db = CalendarDatabase.memoryBuilder(ApplicationProvider.getApplicationContext()).build()
        try {
            val category = EventCategory(name = "Health")
            val label = CategoryRepository(db).save(category)
            val parent = event().copy(categoryId = label, repeat = Repeat.DAILY)
            val id = EventRepository(db).save(parent)
            val stored = db.events().find(id)!!
            EventRepository(db).exception(stored, "2030-10-06T09:00", stored.futureDraft("2030-10-06T09:00").copy(repeat = Repeat.NONE, interval = 1, until = null))
            db.templates().insert(EventTemplate(name = "Preset", event = parent.copy(id = 0)))
            CategoryRepository(db).remove(label)
            assertEquals(1, db.events().all().size)
            assertNull(db.events().find(id)!!.categoryId)
            assertNull(db.exceptions().all().single().replacement!!.categoryId)
            assertNull(db.templates().all().single().event.categoryId)
        } finally { db.close() }
    }
    @Test fun backupAndIcsCarryCategoriesMultipleRemindersAndIndependentIdRemapping() = runBlocking {
        val category = EventCategory(20, "Health, work", 0xFFA5D6BE)
        val original = event().copy(id = 9, categoryId = category.id, allDay = true, start = "2030-10-05T00:00", end = "2030-10-06T00:00", allDayReminderTime = "09:00")
        val json = CalendarFiles.backup(listOf(original), categories = listOf(category))
        val backup = CalendarFiles.readCalendarBackup(json)
        assertEquals(original.reminderOffsets, backup.events.single().reminderOffsets)
        assertEquals("09:00", backup.events.single().allDayReminderTime)
        val exchange = IcsFiles.read(IcsFiles.write(listOf(original), UserCalendar.defaults(), emptyList(), listOf(category)))
        assertEquals(category.name, exchange.categories.single().name)
        assertEquals(original.reminderOffsets, exchange.events.single().reminderOffsets)
        assertEquals("09:00", exchange.events.single().allDayReminderTime)
        val db = CalendarDatabase.memoryBuilder(ApplicationProvider.getApplicationContext()).build()
        try {
            db.categories().insert(EventCategory(name = "Health, work", color = 0xFFB9A7FF))
            CalendarRepository(db).import(backup)
            val saved = db.events().all().single()
            assertNotEquals(category.id, saved.categoryId)
            assertEquals(category.color, db.categories().find(saved.categoryId!!)!!.color)
            assertEquals(original.reminderOffsets, saved.reminderOffsets)
        } finally { db.close() }
    }
    @Test fun oldBackupKeepsLegacyMidnightAndRejectsDanglingCategoryOrExcessiveAlarms() {
        val root = JSONObject(CalendarFiles.backup(listOf(event().copy(additionalReminders = emptyList()))))
        root.put("version", 5).remove("categories")
        root.getJSONArray("events").getJSONObject(0).apply { remove("additionalReminders"); remove("categoryId"); remove("allDayReminderTime") }
        val restored = CalendarFiles.readCalendarBackup(root.toString()).events.single()
        assertEquals("00:00", restored.allDayReminderTime)
        assertEquals(listOf(15), restored.reminderOffsets)
        val invalid = JSONObject(CalendarFiles.backup(listOf(event().copy(categoryId = 999))))
        assertTrue(runCatching { CalendarFiles.readCalendarBackup(invalid.toString()) }.isFailure)
        assertTrue(runCatching { event().copy(additionalReminders = listOf(1, 2, 3, 4, 5)).checked() }.isFailure)
    }
    @Test fun rolesUseRealCalendarContractLevelsAndSyncDoesNotPretendUnavailableAccountsAreReady() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context.applicationContext as Application).grantPermissions(android.Manifest.permission.READ_CALENDAR)
        val provider = WritableCalendarProvider()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(android.provider.CalendarContract.AUTHORITY, provider)
        val calendar = provider.calendars.single()
        assertEquals(CalendarAccess.OWNER, SharedCalendars.capabilities(context, calendar).access)
        provider.access = android.provider.CalendarContract.Calendars.CAL_ACCESS_EDITOR
        assertEquals(CalendarAccess.EDITOR, SharedCalendars.capabilities(context, calendar).access)
        provider.access = android.provider.CalendarContract.Calendars.CAL_ACCESS_READ
        assertEquals(CalendarAccess.READ_ONLY, SharedCalendars.capabilities(context, calendar).access)
        assertEquals(SyncRequest.NO_ADAPTER, SharedCalendars.requestSync(context, calendar))
        val reusedId = calendar.copy(account = "Different account")
        assertEquals(CalendarAccess.UNKNOWN, SharedCalendars.capabilities(context, reusedId).access)
        assertEquals(SyncRequest.UNAVAILABLE, SharedCalendars.requestSync(context, reusedId))
        org.robolectric.Shadows.shadowOf(context.applicationContext as Application).denyPermissions(android.Manifest.permission.READ_CALENDAR)
        assertEquals(CalendarAccess.UNKNOWN, SharedCalendars.capabilities(context, calendar).access)
        assertEquals(SyncRequest.UNAVAILABLE, SharedCalendars.requestSync(context, calendar))
    }
    @Test fun actualSchemaFiveMigrationPreservesExistingValuesAndAddsNullableCategoryAndReminderDefaults() = runBlocking {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        val name = "gimbo-schema-five.db"
        context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("de.luma.calendar.data.CalendarDatabase/5.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
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
        old.version = 5; old.close()
        val db = CalendarDatabase.builder(context, name).build()
        try {
            val saved = db.events().find(99)!!
            assertEquals("Keep", saved.title); assertNull(saved.categoryId); assertTrue(saved.additionalReminders.isEmpty()); assertEquals("00:00", saved.allDayReminderTime)
            val categoryId = db.categories().insert(EventCategory(name = "Health"))
            db.events().update(saved.copy(categoryId = categoryId, additionalReminders = listOf(60, 1440)))
            assertEquals(listOf(15, 60, 1440), db.events().find(99)!!.reminderOffsets)
            db.categories().delete(categoryId)
            assertNull(db.events().find(99)!!.categoryId)
            assertTrue(db.events().insert(event()) > 99)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
