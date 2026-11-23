package de.luma.calendar

import android.app.Application
import android.content.*
import android.database.*
import android.net.Uri
import android.provider.*
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.integrations.*
import de.luma.calendar.widget.*
import de.luma.calendar.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private class Provider : ContentProvider() {
        var uri: Uri? = null; var args: Array<out String>? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            this.uri = uri; this.args = selectionArgs
            val p = projection!!
            val cursor = MatrixCursor(p)
            val values: Map<String, Any> = if (uri.path!!.startsWith("/calendars")) mapOf(CalendarContract.Calendars._ID to 7L, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to "Remote work", CalendarContract.Calendars.ACCOUNT_NAME to "account@example.test", CalendarContract.Calendars.CALENDAR_COLOR to 0xFF9DCBEA.toInt())
                else if (uri.authority == ContactsContract.AUTHORITY) mapOf(ContactsContract.Data.CONTACT_ID to 12L, ContactsContract.Data.DISPLAY_NAME to "Leap birthday", ContactsContract.CommonDataKinds.Event.START_DATE to "--02-29")
                else mapOf(CalendarContract.Instances.TITLE to "All-day", CalendarContract.Instances.BEGIN to Instant.parse("2026-10-12T00:00:00Z").toEpochMilli(), CalendarContract.Instances.END to Instant.parse("2026-10-13T00:00:00Z").toEpochMilli(), CalendarContract.Instances.ALL_DAY to 1, CalendarContract.Instances.EVENT_LOCATION to "Vienna", CalendarContract.Instances.DESCRIPTION to "Notes", CalendarContract.Instances.EVENT_TIMEZONE to "America/Los_Angeles")
            cursor.addRow(p.map { values[it] }.toTypedArray()); return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only")
    }
    @Test fun selectedDeviceCalendarUsesBoundedInstanceQueryAndKeepsFloatingAllDayDate() {
        val provider = Provider(); ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val calendar = DeviceCalendars.list(context.contentResolver).single(); assertEquals(7L, calendar.id)
        val backup = DeviceCalendars.snapshot(context.contentResolver, calendar, LocalDate.parse("2026-10-11"), LocalDate.parse("2027-10-11"), ZoneId.of("Pacific/Honolulu"))
        assertArrayEquals(arrayOf("7"), provider.args); assertTrue(provider.uri!!.path!!.contains("/instances/when/"))
        assertEquals("2026-10-12T00:00", backup.events.single().start); assertEquals(Repeat.NONE, backup.events.single().repeat); assertEquals(-1, backup.events.single().reminderMinutes)
    }
    @Test fun birthdayImportDeduplicatesAndParsesKnownAndUnknownYearWithoutChangingContacts() {
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, Provider())
        val event = ContactBirthdays.read(context.contentResolver).events.single()
        assertEquals("2024-02-29T00:00", event.start); assertEquals(Repeat.YEARLY, event.repeat); assertEquals(4L, event.calendarId)
        assertEquals(LocalDate.parse("1980-05-23"), ContactBirthdays.date("19800523")); assertEquals(LocalDate.parse("2024-05-23"), ContactBirthdays.date("--0523"))
        assertThrows(Exception::class.java) { ContactBirthdays.date("next Friday") }
    }
    @Test fun widgetSettingsAreIndependentAndFilterChangedOccurrenceCalendar() {
        val store = WidgetSettingsStore(context); store.write(101, WidgetSettings(setOf(2), false, 50, 3)); store.write(102, WidgetSettings(setOf(1), true, 100, 5))
        val event = Event(id = 9, title = "Moved to work", start = "2026-10-12T09:00", end = "2026-10-12T10:00", calendarId = 2)
        val o = Occurrence(event, LocalDateTime.parse(event.start).atZone(ZoneOffset.UTC), LocalDateTime.parse(event.end).atZone(ZoneOffset.UTC), "2026-10-11T09:00", true)
        assertTrue(store.read(101).includes(o)); assertFalse(store.read(102).includes(o)); assertEquals(50, store.read(101).opacity)
        store.remove(101); assertEquals(WidgetSettings(), store.read(101)); assertEquals(setOf(1L), store.read(102).calendars)
    }
}
