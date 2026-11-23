package de.luma.calendar

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.*
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.CalendarDatabase
import de.luma.calendar.model.*
import de.luma.calendar.reminders.ReminderScheduler
import de.luma.calendar.widget.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "de-rDE")
class ReminderWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun clearEvents() = runBlocking {
        val dao = CalendarDatabase.get(context).events()
        dao.all().forEach { dao.delete(it.id) }
        val db = CalendarDatabase.get(context); db.tasks().all().forEach { db.tasks().delete(it.id) }
        de.luma.calendar.reminders.ExtraReminderScheduler.rebuild(context)
    }
    @After fun finishWorkers() { (context.applicationContext as CalendarApplication).scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private suspend fun row(widgets: org.robolectric.shadows.ShadowAppWidgetManager, id: Int, expected: String): android.view.View {
        var found: android.view.View? = null
        kotlinx.coroutines.withTimeout(5000) {
            while (found == null) {
                val candidate = widgets.getViewFor(id)?.findViewById<android.widget.LinearLayout>(R.id.widget_items)?.getChildAt(0)
                if (candidate?.findViewById<android.widget.TextView>(R.id.widget_event_title)?.text.toString() == expected) found = candidate
                else kotlinx.coroutines.delay(10)
            }
        }
        return found!!
    }
    private fun futureEvent() = LocalDate.now().plusDays(2).let { day -> Event(title = "Widget-Termin", start = day.atTime(9, 0).toString(), end = day.atTime(10, 0).toString(), reminderMinutes = 15) }

    @Test fun linkedSourceWidgetUsesItsFilterAndClearsAfterRevocationWithoutSchedulingAlarms() = runBlocking {
        val application = context.applicationContext as CalendarApplication
        Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.READ_CALENDAR)
        val provider = MutableCalendarProvider()
        val start = LocalDate.now().plusDays(1).atTime(11, 0).atZone(ZoneId.systemDefault()).toInstant()
        provider.rows[0] = provider.rows[0].copy(start = start, end = start.plusSeconds(3600))
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(android.provider.CalendarContract.AUTHORITY, provider)
        de.luma.calendar.integrations.DeviceCalendarLinksStore(context).connect(provider.calendars.single())
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(context))
        val widgetId = shadow.createWidget(AgendaWidget::class.java, R.layout.calendar_widget)
        WidgetSettingsStore(context).write(widgetId, WidgetSettings(setOf(provider.calendars.single().displayId), holidays = false))
        CalendarWidgets.update(context)
        val rendered = row(shadow, widgetId, "Connected meeting")
        assertTrue(rendered.findViewById<android.widget.TextView>(R.id.widget_event_time).text.toString().contains("Work online"))
        assertTrue(rendered.performClick())
        val launch = Shadows.shadowOf(application).nextStartedActivity
        assertTrue(launch.getLongExtra("eventId", -1) < -1)
        assertNotNull(launch.getStringExtra("occurrenceKey"))
        ReminderScheduler.rebuild(context)
        assertTrue(Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        Shadows.shadowOf(application).denyPermissions(android.Manifest.permission.READ_CALENDAR)
        CalendarWidgets.update(context)
        row(shadow, widgetId, "Gerätekalender nicht verfügbar")
        assertEquals("Gerätekalender prüfen", shadow.getViewFor(widgetId).findViewById<android.widget.TextView>(R.id.widget_subtitle).text.toString())
        assertEquals(0, provider.writes); assertTrue(CalendarDatabase.get(context).events().all().isEmpty())
    }

    @Test fun schedulerCreatesReplacesAndCancelsAlarm() = runBlocking {
        val dao = CalendarDatabase.get(context).events()
        val event = futureEvent()
        val id = dao.insert(event)
        val alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        ReminderScheduler.rebuild(context)
        assertEquals(1, alarms.scheduledAlarms.size)
        ReminderScheduler.rebuild(context)
        assertEquals(1, alarms.scheduledAlarms.size)
        dao.update(event.copy(id = id, reminderMinutes = -1))
        ReminderScheduler.rebuild(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        dao.update(event.copy(id = id))
        ReminderScheduler.rebuild(context)
        dao.delete(id)
        ReminderScheduler.cancel(context, event.copy(id = id))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun widgetsRenderDatabaseEventsAndUpdateAfterDeletion() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = Shadows.shadowOf(manager)
        val id = widgets.createWidget(AgendaWidget::class.java, R.layout.calendar_widget)
        val monthId = widgets.createWidget(MonthWidget::class.java, R.layout.calendar_widget)
        val dao = CalendarDatabase.get(context).events()
        val eventId = dao.insert(futureEvent())
        CalendarWidgets.update(context)
        val agenda = widgets.getViewFor(id)
        assertNotNull(agenda)
        assertEquals("Widget-Termin", row(widgets, id, "Widget-Termin").findViewById<android.widget.TextView>(R.id.widget_event_title).text.toString())
        assertEquals(android.view.View.VISIBLE, widgets.getViewFor(monthId).findViewById<android.widget.LinearLayout>(R.id.widget_days).visibility)
        dao.delete(eventId)
        assertTrue(dao.all().isEmpty())
        CalendarWidgets.update(context)
        assertEquals("Platz für deine Pläne.", row(widgets, id, "Platz für deine Pläne.").findViewById<android.widget.TextView>(R.id.widget_event_title).text.toString())
    }
    @Test fun widgetsFollowAppLanguageAndIncludeReadOnlyImportedHolidays() = runBlocking {
        val native = context.getSystemService(android.app.LocaleManager::class.java)
        native.applicationLocales = android.os.LocaleList.forLanguageTags("en")
        val day = LocalDate.now().plusDays(1).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val store = de.luma.calendar.data.HolidayStore(context)
        store.importIcs("BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:widget-holiday\nDTSTART;VALUE=DATE:$day\nSUMMARY:Widget holiday\nEND:VEVENT\nEND:VCALENDAR\n")
        val manager = AppWidgetManager.getInstance(context)
        val shadow = Shadows.shadowOf(manager)
        val id = shadow.createWidget(AgendaWidget::class.java, R.layout.calendar_widget)
        CalendarWidgets.update(context)
        val view = shadow.getViewFor(id)
        assertEquals("+ Add event", view.findViewById<android.widget.TextView>(R.id.widget_add).text.toString())
        val rendered = row(shadow, id, "Widget holiday")
        assertEquals("Widget holiday", rendered.findViewById<android.widget.TextView>(R.id.widget_event_title).text.toString())
        assertTrue(rendered.findViewById<android.widget.TextView>(R.id.widget_event_time).text.toString().contains("all day"))
        val settings = de.luma.calendar.data.SettingsStore(context)
        settings.write(settings.read().copy(importedHolidays = false))
        CalendarWidgets.update(context)
        val empty = row(shadow, id, "Space for your plans.")
        assertEquals("Space for your plans.", empty.findViewById<android.widget.TextView>(R.id.widget_event_title).text.toString())
        Unit
    }

    @Test fun widgetFollowsCustomCalendarRenameColorAndMove() = runBlocking {
        val db = CalendarDatabase.get(context)
        val repository = de.luma.calendar.data.CalendarRepository(db)
        val calendar = UserCalendar(name = "Sport", color = 0xFF1976D2)
        val calendarId = repository.save(calendar)
        val eventId = db.events().insert(futureEvent().copy(calendarId = calendarId))
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(context))
        val widgetId = shadow.createWidget(AgendaWidget::class.java, R.layout.calendar_widget)
        CalendarWidgets.update(context)
        var rendered = row(shadow, widgetId, "Widget-Termin")
        assertTrue(rendered.findViewById<android.widget.TextView>(R.id.widget_event_time).text.toString().contains("Sport"))
        assertEquals(calendar.color.toInt(), (rendered.findViewById<android.widget.ImageView>(R.id.widget_calendar_color).background as android.graphics.drawable.ColorDrawable).color)
        val renamed = calendar.copy(id = calendarId, name = "Training", color = 0xFFD32F2F)
        repository.save(renamed)
        CalendarWidgets.update(context)
        rendered = row(shadow, widgetId, "Widget-Termin")
        assertTrue(rendered.findViewById<android.widget.TextView>(R.id.widget_event_time).text.toString().contains("Training"))
        assertEquals(renamed.color.toInt(), (rendered.findViewById<android.widget.ImageView>(R.id.widget_calendar_color).background as android.graphics.drawable.ColorDrawable).color)
        repository.removeAndMove(calendarId, 2)
        CalendarWidgets.update(context)
        rendered = row(shadow, widgetId, "Widget-Termin")
        assertTrue(rendered.findViewById<android.widget.TextView>(R.id.widget_event_time).text.toString().contains("Arbeit"))
        assertEquals(2L, db.events().find(eventId)!!.calendarId)
    }

    @Test fun widgetsAndAlarmsFollowMovedSkippedAndRestoredOccurrences() = runBlocking {
        val db = CalendarDatabase.get(context)
        val repository = de.luma.calendar.data.EventRepository(db)
        val future = futureEvent().copy(repeat = Repeat.DAILY, until = LocalDate.now().plusDays(2).toString(), reminderMinutes = -1)
        val parent = future.copy(id = repository.save(future))
        val day = LocalDate.now().plusDays(3)
        val replacement = parent.copy(title = "Geänderter Einzeltermin", start = day.atTime(12, 0).toString(), end = day.atTime(13, 0).toString(),
            repeat = Repeat.NONE, interval = 1, until = null, calendarId = 2, reminderMinutes = 15)
        repository.exception(parent, parent.start, replacement)
        val widgets = Shadows.shadowOf(AppWidgetManager.getInstance(context))
        val widgetId = widgets.createWidget(AgendaWidget::class.java, R.layout.calendar_widget)
        CalendarWidgets.update(context)
        val rendered = row(widgets, widgetId, replacement.title)
        assertEquals(replacement.title, rendered.findViewById<android.widget.TextView>(R.id.widget_event_title).text.toString())
        assertTrue(rendered.performClick())
        val launched = Shadows.shadowOf(context.applicationContext as android.app.Application).nextStartedActivity
        assertEquals("${parent.id}/${parent.start}", launched.getStringExtra("occurrenceKey"))
        assertEquals(parent.id, launched.getLongExtra("eventId", -1))
        val alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        ReminderScheduler.rebuild(context)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(LocalDateTime.parse(replacement.start).atZone(ZoneId.of(replacement.zone)).toInstant().minusSeconds(900).toEpochMilli(), alarm.triggerAtTime)
        assertEquals("${parent.id}/${parent.start}", Shadows.shadowOf(alarm.operation).savedIntent.getStringExtra("occurrenceKey"))
        repository.exception(parent, parent.start, null)
        ReminderScheduler.rebuild(context)
        CalendarWidgets.update(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals("Platz für deine Pläne.", row(widgets, widgetId, "Platz für deine Pläne.").findViewById<android.widget.TextView>(R.id.widget_event_title).text.toString())
        repository.restore(parent.id, parent.start)
        CalendarWidgets.update(context)
        assertEquals(parent.title, row(widgets, widgetId, parent.title).findViewById<android.widget.TextView>(R.id.widget_event_title).text.toString())
        assertEquals(parent, db.events().find(parent.id))
    }

    @Test fun equalReminderTimesAreCombinedWithoutDroppingAnOccurrence() = runBlocking {
        Shadows.shadowOf(context.applicationContext as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val db = CalendarDatabase.get(context)
        val repository = de.luma.calendar.data.EventRepository(db)
        val start = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusMinutes(4)
        val event = Event(title = "Erste Erinnerung", start = start.toString(), end = start.plusHours(2).toString(), repeat = Repeat.DAILY,
            until = start.toLocalDate().plusDays(1).toString(), reminderMinutes = 5)
        val parent = event.copy(id = repository.save(event))
        val replacement = parent.copy(title = "Zweite Erinnerung", start = start.plusMinutes(55).toString(), end = start.plusHours(2).toString(),
            repeat = Repeat.NONE, interval = 1, until = null, reminderMinutes = 60)
        repository.exception(parent, start.plusDays(1).toString(), replacement)
        val occurrence = EventEngine.occurrences(listOf(parent), start.toLocalDate(), start.toLocalDate().plusDays(1), exceptions = db.exceptions().all()).first()
        ReminderScheduler.notify(context, parent.id, occurrence.start.toInstant().toEpochMilli(), occurrence.key)
        val notifications = Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications
        val notification = notifications.single()
        assertEquals("2 Terminerinnerungen", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        val lines = notification.extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.contains(parent.title) })
        assertTrue(lines.any { it.contains(replacement.title) })
        repository.exception(parent, start.toString(), null)
        repository.exception(parent, start.plusDays(1).toString(), null)
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        ReminderScheduler.notify(context, parent.id, occurrence.start.toInstant().toEpochMilli(), occurrence.key)
        assertTrue(Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun twoWidgetsRenderTheirOwnCalendarSelectionAndReconfigureLink() = runBlocking {
        val db = CalendarDatabase.get(context)
        db.events().insert(futureEvent().copy(title = "Personal only", calendarId = 1))
        db.events().insert(futureEvent().copy(title = "Work only", calendarId = 2))
        val widgets = Shadows.shadowOf(AppWidgetManager.getInstance(context))
        val first = widgets.createWidget(AgendaWidget::class.java, R.layout.calendar_widget)
        val second = widgets.createWidget(AgendaWidget::class.java, R.layout.calendar_widget)
        WidgetSettingsStore(context).write(first, WidgetSettings(setOf(1), false, 50, 1))
        WidgetSettingsStore(context).write(second, WidgetSettings(setOf(2), false, 100, 1))
        CalendarWidgets.update(context)
        assertEquals("Personal only", row(widgets, first, "Personal only").findViewById<android.widget.TextView>(R.id.widget_event_title).text.toString())
        assertEquals("Work only", row(widgets, second, "Work only").findViewById<android.widget.TextView>(R.id.widget_event_title).text.toString())
        widgets.getViewFor(second).findViewById<android.widget.TextView>(R.id.widget_configure).performClick()
        val intent = Shadows.shadowOf(context.applicationContext as android.app.Application).nextStartedActivity
        assertEquals(second, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertEquals(WidgetConfigurationActivity::class.java.name, intent.component!!.className)
        WidgetSettingsStore(context).remove(first); WidgetSettingsStore(context).remove(second)
    }

}
