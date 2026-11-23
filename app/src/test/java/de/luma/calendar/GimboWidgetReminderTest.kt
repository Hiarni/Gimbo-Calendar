package de.luma.calendar

import android.Manifest
import android.app.*
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.widget.*
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.*
import de.luma.calendar.model.*
import de.luma.calendar.reminders.*
import de.luma.calendar.widget.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = CalendarApplication::class, qualifiers = "en-rGB")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GimboWidgetReminderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db get() = CalendarDatabase.get(context)
    @Before fun clear() = runBlocking {
        (context.applicationContext as CalendarApplication).scope.cancel()
        (context.applicationContext as CalendarApplication).deviceConnections.close()
        db.events().all().forEach { db.events().delete(it.id) }; db.tasks().all().forEach { db.tasks().delete(it.id) }
        ReminderScheduler.rebuild(context); ExtraReminderScheduler.rebuild(context)
        context.getSharedPreferences("widget-settings", 0).edit().clear().commit()
        context.getSharedPreferences("device-calendar-links", 0).edit().clear().commit()
        Shadows.shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun finish() = runBlocking {
        db.events().all().forEach { db.events().delete(it.id) }; db.tasks().all().forEach { db.tasks().delete(it.id) }
        ReminderScheduler.rebuild(context); ExtraReminderScheduler.rebuild(context)
    }
    private fun render(id: Int, events: List<Event> = emptyList(), tasks: List<CalendarTask> = emptyList()) = CalendarWidgets.render(context, id, false, AppSettings(theme = ThemeMode.LIGHT), events, UserCalendar.defaults(), emptyList(), tasks, false).apply(context, FrameLayout(context))
    @Test fun perInstanceStylesPreserveRoundedAlphaAndExplicitInkAndOldSettingsDefaults() {
        assertTrue(context.getDrawable(R.mipmap.ic_launcher) is android.graphics.drawable.AdaptiveIconDrawable)
        if (android.os.Build.VERSION.SDK_INT >= 33) assertNotNull((context.getDrawable(R.mipmap.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable).monochrome)
        val store = WidgetSettingsStore(context)
        store.write(1, WidgetSettings(opacity = 25, ink = WidgetInk.LIGHT, textScale = 140, corners = 32))
        store.write(2, WidgetSettings(opacity = 100, ink = WidgetInk.DARK, corners = 0))
        val first = render(1); val second = render(2)
        val bitmap = (first.findViewById<ImageView>(R.id.widget_surface).drawable as BitmapDrawable).bitmap
        assertEquals(63, android.graphics.Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(0xFFF4F4FA.toInt(), first.findViewById<TextView>(R.id.widget_title).currentTextColor)
        assertEquals(0xFF20202A.toInt(), second.findViewById<TextView>(R.id.widget_title).currentTextColor)
        assertTrue(first.findViewById<TextView>(R.id.widget_title).textSize > second.findViewById<TextView>(R.id.widget_title).textSize)
        context.getSharedPreferences("widget-settings", 0).edit().putString("3", "{\"calendars\":null,\"holidays\":true,\"opacity\":40,\"entries\":7}").commit()
        assertEquals(40, store.read(3).opacity); assertEquals(WidgetLayout.DEFAULT, store.read(3).layout); assertEquals(100, store.read(3).textScale)
        assertTrue(runCatching { store.write(4, WidgetSettings(textScale = 200)) }.isFailure)
    }
    @Test fun realMonthCellsOpenTheirOwnDateAndHiddenControlsCanBeConfiguredAgain() {
        WidgetSettingsStore(context).write(1, WidgetSettings(layout = WidgetLayout.MONTH, header = false, actions = false))
        val view = render(1)
        assertEquals(android.view.View.GONE, view.findViewById<LinearLayout>(R.id.widget_actions).visibility)
        val grid = view.findViewById<LinearLayout>(R.id.widget_days)
        assertTrue(grid.childCount in 5..7)
        val target = LocalDate.now().withDayOfMonth(15)
        var clicked = false
        for (row in 1 until grid.childCount) {
            val week = grid.getChildAt(row) as LinearLayout
            for (column in 0 until week.childCount) {
                val cell = week.getChildAt(column)
                if (cell.findViewById<TextView>(R.id.widget_day_number).text.toString() == "15") { assertTrue(cell.performClick()); clicked = true }
            }
        }
        assertTrue(clicked)
        val launch = Shadows.shadowOf(context.applicationContext as Application).nextStartedActivity
        assertEquals(target.toString(), launch.getStringExtra("date"))
        assertEquals(-1L, launch.getLongExtra("eventId", 0))
    }
    @Test fun taskRowsRespectCalendarCompletionAndHorizonAndOpenTasksWithoutCompletingThem() {
        val today = LocalDate.now()
        WidgetSettingsStore(context).write(1, WidgetSettings(calendars = setOf(2), tasks = true, horizon = 7, entries = 15, header = false, actions = false))
        val tasks = listOf(CalendarTask(id = 1, title = "Overdue task", calendarId = 2, due = today.minusDays(1).toString()), CalendarTask(id = 2, title = "Wrong calendar", calendarId = 1),
            CalendarTask(id = 3, title = "Completed", calendarId = 2, completed = true), CalendarTask(id = 4, title = "Later", calendarId = 2, due = today.plusDays(10).toString()))
        val view = render(1, tasks = tasks)
        val rows = view.findViewById<LinearLayout>(R.id.widget_items)
        assertEquals(1, rows.childCount)
        assertTrue(rows.getChildAt(0).findViewById<TextView>(R.id.widget_event_title).text.contains("Overdue task"))
        assertTrue(rows.getChildAt(0).performClick())
        assertEquals("tasks", Shadows.shadowOf(context.applicationContext as Application).nextStartedActivity.getStringExtra("page"))
        assertFalse(tasks.first().completed)
    }
    @Test fun schedulerCreatesIndependentOffsetsAndCancelsRemovedOffsetsAndDeletedEvents() = runBlocking {
        val start = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0)
        val id = db.events().insert(Event(title = "Offsets", start = start.toString(), end = start.plusHours(1).toString(), reminderMinutes = 15, additionalReminders = listOf(0, 60)))
        ReminderScheduler.rebuild(context)
        val alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        val offsets = alarms.scheduledAlarms.map { Shadows.shadowOf(it.operation).savedIntent.getIntExtra("minutes", -1) }.toSet()
        assertEquals(setOf(0, 15, 60), offsets)
        db.events().update(db.events().find(id)!!.copy(additionalReminders = emptyList()))
        ReminderScheduler.rebuild(context)
        assertEquals(1, alarms.scheduledAlarms.size)
        db.events().delete(id); ReminderScheduler.rebuild(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
    @Test fun snoozeSurvivesRebuildAndEditedOrDeletedEventsCannotSendStaleNotifications() = runBlocking {
        val start = Instant.now().plusSeconds(120).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0)
        val id = db.events().insert(Event(title = "Snooze", start = start.toString(), end = start.plusHours(1).toString(), reminderMinutes = 15))
        val event = db.events().find(id)!!
        val occurrence = EventEngine.occurrences(listOf(event), start.toLocalDate(), start.toLocalDate().plusDays(1)).single()
        ReminderScheduler.notify(context, id, occurrence.start.toInstant().toEpochMilli(), occurrence.key, 15)
        val notifications = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        val notification = notifications.getNotification(id.toString(), 1)
        assertNotNull(notification)
        val intent = Shadows.shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(ReminderScheduler.SNOOZE, intent.action)
        ReminderScheduler.snooze(context, intent)
        val alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        val delivery = Shadows.shadowOf(alarms.scheduledAlarms.single().operation).savedIntent
        val planned = delivery.getLongExtra("snoozedAt", -1)
        ReminderScheduler.rebuild(context)
        assertEquals(planned, alarms.scheduledAlarms.single().triggerAtTime)
        assertTrue(notifications.allNotifications.isEmpty())
        db.events().update(event.copy(start = start.plusHours(2).toString(), end = start.plusHours(3).toString()))
        ReminderScheduler.notify(context, id, occurrence.start.toInstant().toEpochMilli(), occurrence.key, 15, planned)
        assertTrue(notifications.allNotifications.isEmpty())
        db.events().delete(id); ReminderScheduler.rebuild(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
