package de.luma.calendar

import android.app.Application
import android.content.Context
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
@Config(sdk = [29,35], application = Application::class)
class IntegrationUpgradeTest {
    @Test fun actualSchemaFourUpgradePreservesIdsAndAddsAlarmAttachmentAndOriginConstraints() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext(); val name="integration-upgrade.db"; context.deleteDatabase(name)
        val schema=javaClass.classLoader!!.getResourceAsStream("de.luma.calendar.data.CalendarDatabase/4.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null); val entities=schema.getJSONArray("entities")
        for(i in 0 until entities.length()) { val entity=entities.getJSONObject(i); val table=entity.getString("tableName"); old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table)); val indices=entity.optJSONArray("indices"); for(j in 0 until(indices?.length() ?: 0)) old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table)) }
        UserCalendar.defaults().forEach { c -> old.execSQL("INSERT INTO calendars(id,name,color,defaultCategory) VALUES(?,?,?,?)",arrayOf<Any>(c.id,c.name,c.color,c.defaultCategory!!.name)) }
        old.execSQL("INSERT INTO events(id,title,start,end,zone,allDay,category,location,notes,repeat,interval,until,reminderMinutes,calendarId) VALUES(99,'Original','2030-10-01T09:00','2030-10-01T10:00','Europe/Vienna',0,'PERSONAL','','','NONE',1,NULL,-1,1)")
        old.execSQL("INSERT INTO tasks(id,title,due,completed,repeat,interval,anchor,calendarId) VALUES(75,'Original task','2030-10-01',0,'NONE',1,'2030-10-01',1)")
        old.version=4; old.close(); val db=CalendarDatabase.builder(context,name).build()
        try {
            assertEquals("Original",db.events().find(99)!!.title); assertEquals("Original task",db.tasks().find(75)!!.title); assertNull(db.tasks().find(75)!!.reminderTime)
            val file="00000000-0000-0000-0000-000000000001"; db.integrations().attach(EventAttachment(file,99,"test.txt","text/plain",5)); db.integrations().travel(TravelPlan(99,30,10)); db.integrations().origin(TaskOrigin("uid@example.test",75,"baseline"))
            db.tasks().update(db.tasks().find(75)!!.copy(reminderTime="09:00")); assertEquals("09:00",db.tasks().find(75)!!.reminderTime)
            db.events().delete(99); assertTrue(db.integrations().attachments().isEmpty()); assertTrue(db.integrations().travel().isEmpty())
            db.tasks().delete(75); assertNull(db.integrations().origin("uid@example.test"))
            assertTrue(db.events().insert(Event(title="New",start="2030-10-02T09:00",end="2030-10-02T10:00"))>99)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
