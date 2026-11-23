package de.luma.calendar

import android.Manifest
import android.app.Application
import android.content.*
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.integrations.*
import de.luma.calendar.model.Event
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29,35], application = Application::class)
class ProviderMutationTest {
    private lateinit var context: Context; private lateinit var provider: WritableCalendarProvider
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR); provider = WritableCalendarProvider(); ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider) }
    @Test fun updateCreateAndDeleteWriteToSelectedProviderWithoutLocalCopies() {
        val cal = provider.calendars.single(); val record = DeviceCalendarMutations.prepare(context, cal, 70)
        assertEquals(70L, DeviceCalendarMutations.write(context, record, record.draft.copy(title = "Edited", location = "Linz")))
        assertEquals("Edited", provider.rows.single().title); assertEquals("Linz", provider.rows.single().location)
        val draft = DeviceCalendarMutations.prepare(context, cal, null, date = LocalDate.of(2030,1,2))
        val id = DeviceCalendarMutations.write(context, draft, draft.draft.copy(title = "New source", allDay = true, start = "2030-01-02T00:00", end = "2030-01-03T00:00"))!!
        assertEquals(Instant.parse("2030-01-02T00:00:00Z"), provider.rows.first { it.id == id }.start)
        val deletion = DeviceCalendarMutations.prepare(context, cal, id, true); DeviceCalendarMutations.write(context, deletion)
        assertEquals(1, provider.rows.size); assertEquals(3, provider.writes)
    }
    @Test fun concurrentRemoteEditFailsAtomicAssertionAndKeepsNewestData() {
        val record = DeviceCalendarMutations.prepare(context, provider.calendars.single(), 70)
        provider.rows[0] = provider.rows[0].copy(notes = "Remote change")
        val error = assertThrows(IllegalStateException::class.java) { DeviceCalendarMutations.write(context, record, record.draft.copy(title = "Stale")) }
        assertTrue(error.message!!.contains("nichts überschrieben")); assertEquals("Connected meeting", provider.rows.single().title); assertEquals("Remote change", provider.rows.single().notes); assertEquals(0, provider.writes)
    }
    @Test fun revokedPermissionReadOnlyAccountAndReusedAccountIdentityRejectWriting() {
        val cal = provider.calendars.single(); val record = DeviceCalendarMutations.prepare(context, cal, 70)
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        assertThrows(IllegalStateException::class.java) { DeviceCalendarMutations.write(context, record) }
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.WRITE_CALENDAR); provider.access = CalendarContract.Calendars.CAL_ACCESS_READ
        assertThrows(IllegalArgumentException::class.java) { DeviceCalendarMutations.prepare(context, cal, null) }
        provider.access = CalendarContract.Calendars.CAL_ACCESS_OWNER; provider.calendars[0] = cal.copy(account = "other@example.test")
        assertThrows(IllegalStateException::class.java) { DeviceCalendarMutations.write(context, record) }; assertEquals(0, provider.writes)
    }
    @Test fun recurringEventsAndInvitationsRequireOriginalAppInsteadOfLossyEdits() {
        val cal = provider.calendars.single(); provider.extras[70] = ContentValues().apply { put(CalendarContract.Events.RRULE, "FREQ=DAILY") }
        assertThrows(IllegalArgumentException::class.java) { DeviceCalendarMutations.prepare(context, cal, 70) }
        provider.extras[70] = ContentValues().apply { put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1) }
        assertThrows(IllegalArgumentException::class.java) { DeviceCalendarMutations.prepare(context, cal, 70, true) }; assertEquals(0, provider.writes)
    }
    @Test fun foldInstantsSurviveUnchangedProviderSave() {
        provider.rows[0] = provider.rows[0].copy(start = Instant.parse("2026-10-25T00:45:00Z"), end = Instant.parse("2026-10-25T01:15:00Z"), zone = "Europe/Vienna")
        val original = provider.rows.single(); val edit = DeviceCalendarMutations.prepare(context, provider.calendars.single(), original.id)
        assertEquals("Z", edit.draft.zone); DeviceCalendarMutations.write(context, edit, edit.draft.copy(title = "Fold"))
        assertEquals(original.start, provider.rows.single().start); assertEquals(original.end, provider.rows.single().end)
    }
}
