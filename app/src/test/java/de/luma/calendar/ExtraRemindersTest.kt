package de.luma.calendar

import android.Manifest
import android.app.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.CalendarDatabase
import de.luma.calendar.model.*
import de.luma.calendar.reminders.ExtraReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35], application=Application::class)
class ExtraRemindersTest {
    private lateinit var context: Context
    private lateinit var db: CalendarDatabase
    @Before fun setup() { context=ApplicationProvider.getApplicationContext(); db=CalendarDatabase.get(context); Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS); clear() }
    private fun clear() = runBlocking { db.tasks().all().forEach { db.tasks().delete(it.id) }; db.events().all().forEach { db.events().delete(it.id) }; ExtraReminderScheduler.rebuild(context); de.luma.calendar.reminders.ReminderScheduler.rebuild(context) }
    @After fun cleanup() { clear() }
    @Test fun taskAndDepartureAlarmsCoexistAndCompletionAndDeletionCancelThem() = runBlocking {
        val next=ZonedDateTime.now().plusHours(2).truncatedTo(ChronoUnit.MINUTES)
        val task=CalendarTask(title="Task alarm",due=next.toLocalDate().toString(),reminderTime=next.toLocalTime().toString())
        val taskId=db.tasks().insert(task)
        val e=Event(title="Trip alarm",start=next.toLocalDateTime().toString(),end=next.plusHours(1).toLocalDateTime().toString(),zone=next.zone.id)
        val eventId=db.events().insert(e); db.integrations().travel(TravelPlan(eventId,30,10))
        ExtraReminderScheduler.rebuild(context)
        val alarms=Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        @Suppress("DEPRECATION") val extras=alarms.filter { Shadows.shadowOf(it.operation).savedIntent.data?.host=="extras" }
        assertEquals(2,extras.size)
        @Suppress("DEPRECATION") val times=extras.associate { Shadows.shadowOf(it.operation).savedIntent.getStringExtra("kind") to it.triggerAtTime }
        assertEquals(next.toInstant().toEpochMilli(),times["task"]); assertEquals(next.toInstant().minusSeconds(2400).toEpochMilli(),times["travel"])
        db.tasks().update(task.copy(id=taskId,completed=true)); db.events().delete(eventId); ExtraReminderScheduler.rebuild(context)
        @Suppress("DEPRECATION") val remaining=Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter { Shadows.shadowOf(it.operation).savedIntent.data?.host=="extras" }
        assertTrue(remaining.isEmpty()); assertTrue(context.getSharedPreferences("extra-reminders",0).getStringSet("scheduled",emptySet()).isNullOrEmpty())
    }
    @Test fun staleOrCompletedTaskReminderCannotNotify() = runBlocking {
        val now=ZonedDateTime.now().truncatedTo(ChronoUnit.MINUTES)
        val task=CalendarTask(title="Completed task",due=now.toLocalDate().toString(),reminderTime=now.toLocalTime().toString(),completed=true)
        val id=db.tasks().insert(task); ExtraReminderScheduler.notify(context,"task",id,now.toInstant(),null)
        val notifications=Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        assertTrue(notifications.allNotifications.isEmpty())
        db.tasks().update(task.copy(id=id,completed=false,reminderTime=now.plusHours(1).toLocalTime().toString()))
        ExtraReminderScheduler.notify(context,"task",id,now.toInstant(),null); assertTrue(notifications.allNotifications.isEmpty())
    }
}
