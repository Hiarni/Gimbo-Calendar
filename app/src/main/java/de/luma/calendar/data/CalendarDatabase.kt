package de.luma.calendar.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.luma.calendar.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY start, id") fun observe(): Flow<List<Event>>
    @Query("SELECT * FROM events ORDER BY start, id") suspend fun all(): List<Event>
    @Query("SELECT * FROM events WHERE id = :id") suspend fun find(id: Long): Event?
    @Insert suspend fun insert(event: Event): Long
    @Update suspend fun update(event: Event): Int
    @Query("DELETE FROM events WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("UPDATE events SET calendarId = :target WHERE calendarId = :source") suspend fun moveCalendar(source: Long, target: Long): Int
}
@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendars ORDER BY id") fun observe(): Flow<List<UserCalendar>>
    @Query("SELECT * FROM calendars ORDER BY id") suspend fun all(): List<UserCalendar>
    @Query("SELECT * FROM calendars WHERE id = :id") suspend fun find(id: Long): UserCalendar?
    @Insert suspend fun insert(calendar: UserCalendar): Long
    @Update suspend fun update(calendar: UserCalendar): Int
    @Query("DELETE FROM calendars WHERE id = :id") suspend fun delete(id: Long): Int
}
@Dao
interface ExceptionDao {
    @Query("SELECT * FROM event_exceptions ORDER BY eventId, originalStart") fun observe(): Flow<List<EventException>>
    @Query("SELECT * FROM event_exceptions ORDER BY eventId, originalStart") suspend fun all(): List<EventException>
    @Query("SELECT * FROM event_exceptions WHERE eventId = :id ORDER BY originalStart") suspend fun forEvent(id: Long): List<EventException>
    @Query("SELECT * FROM event_exceptions WHERE calendarId = :id") suspend fun forCalendar(id: Long): List<EventException>
    @Upsert suspend fun save(exception: EventException)
    @Query("DELETE FROM event_exceptions WHERE eventId = :id AND originalStart = :start") suspend fun delete(id: Long, start: String): Int
    @Query("DELETE FROM event_exceptions WHERE eventId = :id") suspend fun deleteForEvent(id: Long): Int
}
@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY completed, due IS NULL, due, id") suspend fun all(): List<CalendarTask>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun find(id: Long): CalendarTask?
    @Insert suspend fun insert(task: CalendarTask): Long
    @Update suspend fun update(task: CalendarTask): Int
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("UPDATE tasks SET calendarId = :target WHERE calendarId = :source") suspend fun moveCalendar(source: Long, target: Long): Int
}
@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY name, id") suspend fun all(): List<EventTemplate>
    @Insert suspend fun insert(template: EventTemplate): Long
    @Update suspend fun update(template: EventTemplate): Int
    @Query("DELETE FROM templates WHERE id = :id") suspend fun delete(id: Long): Int
}
@Dao
interface IntegrationDao {
    @Query("SELECT * FROM attachments ORDER BY eventId, name, fileId") suspend fun attachments(): List<EventAttachment>
    @Insert suspend fun attach(value: EventAttachment)
    @Query("DELETE FROM attachments WHERE fileId = :id") suspend fun removeAttachment(id: String)
    @Query("SELECT * FROM travel_plans ORDER BY eventId") suspend fun travel(): List<TravelPlan>
    @Upsert suspend fun travel(value: TravelPlan)
    @Query("DELETE FROM travel_plans WHERE eventId = :id") suspend fun removeTravel(id: Long)
    @Query("SELECT * FROM task_origins WHERE uid = :uid") suspend fun origin(uid: String): TaskOrigin?
    @Query("SELECT * FROM task_origins ORDER BY uid") suspend fun origins(): List<TaskOrigin>
    @Upsert suspend fun origin(value: TaskOrigin)
}
@Dao
interface CategoryDao {
    @Query("SELECT * FROM event_categories ORDER BY name, id") suspend fun all(): List<EventCategory>
    @Query("SELECT * FROM event_categories WHERE id = :id") suspend fun find(id: Long): EventCategory?
    @Insert suspend fun insert(value: EventCategory): Long
    @Update suspend fun update(value: EventCategory): Int
    @Query("DELETE FROM event_categories WHERE id = :id") suspend fun delete(id: Long): Int
}
@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC, id DESC") suspend fun all(): List<CalendarNote>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun find(id: Long): CalendarNote?
    @Insert suspend fun insert(value: CalendarNote): Long
    @Update suspend fun update(value: CalendarNote): Int
    @Query("DELETE FROM notes WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("UPDATE notes SET calendarId = :target WHERE calendarId = :source") suspend fun moveCalendar(source: Long, target: Long): Int
}
@Database(entities = [CalendarNote::class, EventCategory::class, Event::class, UserCalendar::class, EventException::class, CalendarTask::class, EventTemplate::class, EventAttachment::class, TravelPlan::class, TaskOrigin::class], version = 7, exportSchema = true)
@TypeConverters(EventPayloadConverter::class)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun categories(): CategoryDao
    abstract fun integrations(): IntegrationDao
    abstract fun templates(): TemplateDao
    abstract fun tasks(): TaskDao
    abstract fun events(): EventDao
    abstract fun calendars(): CalendarDao
    abstract fun exceptions(): ExceptionDao
    companion object {
        @Volatile private var instance: CalendarDatabase? = null
        private fun seed(database: SupportSQLiteDatabase) {
            UserCalendar.defaults().forEach { calendar ->
                database.execSQL("INSERT INTO calendars (id,name,color,defaultCategory) VALUES (?,?,?,?)",
                    arrayOf<Any>(calendar.id, calendar.name, calendar.color, calendar.defaultCategory!!.name))
            }
        }
        val defaultsCallback = object : Callback() { override fun onCreate(db: SupportSQLiteDatabase) { seed(db) } }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS calendars (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, color INTEGER NOT NULL, defaultCategory TEXT)")
                seed(db)
                db.execSQL("""CREATE TABLE events_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL,
                    start TEXT NOT NULL, end TEXT NOT NULL, zone TEXT NOT NULL, allDay INTEGER NOT NULL, category TEXT NOT NULL,
                    location TEXT NOT NULL, notes TEXT NOT NULL, repeat TEXT NOT NULL, interval INTEGER NOT NULL,
                    until TEXT, reminderMinutes INTEGER NOT NULL, calendarId INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY(calendarId) REFERENCES calendars(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("""INSERT INTO events_new (id,title,start,end,zone,allDay,category,location,notes,repeat,interval,until,reminderMinutes,calendarId)
                    SELECT id,title,start,end,zone,allDay,category,location,notes,repeat,interval,until,reminderMinutes,
                    CASE category WHEN 'WORK' THEN 2 WHEN 'FAMILY' THEN 3 WHEN 'BIRTHDAY' THEN 4 ELSE 1 END FROM events""")

                db.execSQL("INSERT INTO sqlite_sequence (name,seq) SELECT 'events_new',seq FROM sqlite_sequence WHERE name = 'events' AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'events_new')")
                db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'events'), 0)) WHERE name = 'events_new'")
                db.execSQL("DROP TABLE events")
                db.execSQL("ALTER TABLE events_new RENAME TO events")
                db.execSQL("CREATE INDEX index_events_calendarId ON events(calendarId)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS event_exceptions (eventId INTEGER NOT NULL, originalStart TEXT NOT NULL, replacement TEXT, calendarId INTEGER, PRIMARY KEY(eventId, originalStart), FOREIGN KEY(eventId) REFERENCES events(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(calendarId) REFERENCES calendars(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX index_event_exceptions_calendarId ON event_exceptions(calendarId)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, due TEXT, completed INTEGER NOT NULL, repeat TEXT NOT NULL, interval INTEGER NOT NULL, anchor TEXT, calendarId INTEGER NOT NULL, FOREIGN KEY(calendarId) REFERENCES calendars(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX index_tasks_calendarId ON tasks(calendarId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS templates (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, event TEXT NOT NULL, calendarId INTEGER NOT NULL, FOREIGN KEY(calendarId) REFERENCES calendars(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX index_templates_calendarId ON templates(calendarId)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN reminderTime TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS attachments (fileId TEXT NOT NULL PRIMARY KEY, eventId INTEGER NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, bytes INTEGER NOT NULL, FOREIGN KEY(eventId) REFERENCES events(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_attachments_eventId ON attachments(eventId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS travel_plans (eventId INTEGER NOT NULL PRIMARY KEY, minutes INTEGER NOT NULL, buffer INTEGER NOT NULL, FOREIGN KEY(eventId) REFERENCES events(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS task_origins (uid TEXT NOT NULL PRIMARY KEY, taskId INTEGER NOT NULL, baseline TEXT NOT NULL, FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX index_task_origins_taskId ON task_origins(taskId)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS event_categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, color INTEGER NOT NULL)")
                db.execSQL("ALTER TABLE events ADD COLUMN categoryId INTEGER DEFAULT NULL REFERENCES event_categories(id) ON UPDATE NO ACTION ON DELETE SET NULL")
                db.execSQL("CREATE INDEX index_events_categoryId ON events(categoryId)")
                db.execSQL("ALTER TABLE events ADD COLUMN additionalReminders TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE events ADD COLUMN allDayReminderTime TEXT NOT NULL DEFAULT '00:00'")
            }
        }
        fun builder(context: Context, name: String) = Room.databaseBuilder(context.applicationContext, CalendarDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).addCallback(defaultsCallback)
        fun memoryBuilder(context: Context) = Room.inMemoryDatabaseBuilder(context.applicationContext, CalendarDatabase::class.java)
            .addCallback(defaultsCallback)
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, kind TEXT NOT NULL, items TEXT NOT NULL, labels TEXT NOT NULL, color INTEGER NOT NULL, pinned INTEGER NOT NULL, archived INTEGER NOT NULL, calendarId INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(calendarId) REFERENCES calendars(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX index_notes_calendarId ON notes(calendarId)")
            }
        }
        fun get(context: Context): CalendarDatabase = instance ?: synchronized(this) {
            instance ?: builder(context, "luma-calendar.db").build().also { instance = it }
        }
    }
}
