package de.luma.calendar

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.integrations.*
import de.luma.calendar.model.*
import de.luma.calendar.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.time.*
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceCalendarSourcesTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var provider: MutableCalendarProvider
    private val from = LocalDate.now().minusYears(1)
    private val until = LocalDate.now().plusYears(1)
    private val store get() = DeviceCalendarLinksStore(context)
    @Before fun setup() {
        context.getSharedPreferences("device-calendar-links", 0).edit().clear().commit()
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR)
        provider = MutableCalendarProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
    }
    private fun read() = DeviceCalendarSources.read(context, from, until)
    @Test fun connectingIsIdempotentAndRenameRetainsIdentityWithoutCreatingCopies() {
        val calendar = provider.calendars.single(); store.connect(calendar); store.connect(calendar)
        assertEquals(1, DeviceCalendarLinksStore(context).read().size)
        val first = read(); assertEquals(SourceStatus.READY, first.sources.single().status)
        provider.calendars[0] = calendar.copy(name = "Renamed", color = 0xFFA5D6BE)
        val second = read(); assertEquals(first.events.single().id, second.events.single().id)
        assertEquals("Renamed", second.calendars.single().name); assertEquals(calendar.displayId, second.calendars.single().id)
        assertEquals(0, provider.writes)
    }
    @Test fun changesDeletionsAndExpandedSeriesHaveStableSeparateIdentities() {
        store.connect(provider.calendars.single())
        val row = provider.rows.single(); provider.rows += row.copy(start = row.start.plusSeconds(86400), end = row.end.plusSeconds(86400))
        val first = read(); assertEquals(2, first.events.size); assertEquals(2, first.events.map { it.id }.distinct().size)
        assertEquals(setOf(70L), first.providerIds.values.toSet()); assertTrue(first.events.all { it.id < -1 && it.id % 2 == 0L && it.calendarId < 0 && it.reminderMinutes == -1 })
        provider.rows[0] = row.copy(title = "Changed title")
        val next = read(); assertEquals(first.events[0].id, next.events[0].id); assertEquals("Changed title", next.events[0].title)
        provider.rows.clear(); assertTrue(read().events.isEmpty()); assertEquals(0, provider.writes)
        assertEquals(-1L, Holiday("id", LocalDate.now(), name = "Holiday").event(Locale.ENGLISH).id % 2)
    }
    @Test fun reusedCalendarIdInAnotherAccountNeverReadsTheReplacement() {
        val calendar = provider.calendars.single(); store.connect(calendar)
        provider.calendars[0] = calendar.copy(account = "other@example.test")
        assertEquals(SourceStatus.MISSING, read().sources.single().status); assertEquals(0, provider.instanceQueries)
        provider.calendars[0] = calendar.copy(accountType = "different.provider")
        assertEquals(SourceStatus.MISSING, read().sources.single().status); assertTrue(read().events.isEmpty())
        assertEquals(0, provider.instanceQueries)
    }
    @Test fun deniedOrRevokedPermissionHidesEventsAndDoesNotQueryTheProvider() {
        store.connect(provider.calendars.single()); assertEquals(1, read().events.size)
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.READ_CALENDAR)
        val count = provider.queries; val denied = read()
        assertEquals(SourceStatus.NO_PERMISSION, denied.sources.single().status); assertTrue(denied.events.isEmpty()); assertEquals(count, provider.queries)
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR)
        assertEquals(1, read().events.size)
    }
    @Test fun unavailableSourceDropsOldDisplayAndRecoversWithoutDeletingSelection() {
        val calendar = provider.calendars.single(); store.connect(calendar); assertEquals(1, read().events.size)
        provider.failInstances = true
        assertEquals(SourceStatus.ERROR, read().sources.single().status); assertTrue(read().events.isEmpty()); assertEquals(listOf(calendar), store.read())
        provider.failInstances = false; assertEquals(1, read().events.size)
        store.disconnect(calendar.displayId); assertTrue(read().sources.isEmpty()); assertEquals(1, provider.rows.size); assertEquals(0, provider.writes)
    }
    @Test fun allDayDateStaysFloatingAndWidgetsDistinguishSourcesFromHolidays() {
        val calendar = provider.calendars.single(); store.connect(calendar)
        val date = LocalDate.now(); provider.rows[0] = provider.rows[0].copy(start = date.atStartOfDay(ZoneOffset.UTC).toInstant(), end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), allDay = true, zone = "America/Los_Angeles")
        val event = read().events.single(); assertEquals(date.atStartOfDay().toString(), event.start); assertEquals("Z", event.zone)
        val settings = WidgetSettings(setOf(calendar.displayId), false).checked()
        assertTrue(settings.includesEvent(event)); assertFalse(WidgetSettings(emptySet(), true).includesEvent(event))
        assertFalse(settings.includesEvent(Holiday("holiday", date, name = "Holiday").event(Locale.ENGLISH)))
        WidgetSettingsStore(context).write(90, settings); assertEquals(settings, WidgetSettingsStore(context).read(90))
    }
    @Test fun sourceSelectionIsBoundedAndCalendarQueriesFollowTheRequestedDateRange() {
        (1L..10).forEach { store.connect(DeviceCalendar(it, "Calendar $it", "account", 0xFF9DCBEA, "test")) }
        assertThrows(IllegalArgumentException::class.java) { store.connect(DeviceCalendar(11, "Extra", "account", 0xFF9DCBEA, "test")) }
        assertEquals(10, store.read().size)
        store.read().forEach { store.disconnect(it.displayId) }; store.connect(provider.calendars.single())
        val start = LocalDate.of(2030, 1, 1); val end = start.plusYears(2)
        assertTrue(DeviceCalendarSources.read(context, start, end).events.isEmpty())
        assertEquals(listOf(start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), end.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()), provider.lastBounds)
    }
    @Test fun autumnClockFoldPreservesBothProviderInstantsAndIdentityWhenZoneChanges() {
        store.connect(provider.calendars.single())
        provider.rows[0] = provider.rows[0].copy(start = Instant.parse("2026-10-25T00:45:00Z"), end = Instant.parse("2026-10-25T01:15:00Z"), zone = "Europe/Vienna")
        val event = read().events.single()
        val occurrence = EventEngine.occurrences(listOf(event), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26), ZoneId.of("Europe/Vienna")).single()
        assertEquals(provider.rows[0].start, occurrence.start.toInstant()); assertEquals(provider.rows[0].end, occurrence.end.toInstant())
        assertEquals(Duration.ofMinutes(30), Duration.between(occurrence.start.toInstant(), occurrence.end.toInstant()))
        provider.rows[0] = provider.rows[0].copy(zone = "UTC")
        assertEquals(event.id, read().events.single().id)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun providerNotificationsAreDebouncedAndDisconnectedSourcesStopObserving() = runTest {
        var widgetUpdates = 0
        val connections = DeviceCalendarConnections(context, backgroundScope) { widgetUpdates++ }
        try {
            connections.connect(provider.calendars.single()); runCurrent()
            val revision = connections.revision.value; val updates = widgetUpdates
            repeat(3) { context.contentResolver.notifyChange(CalendarContract.Events.CONTENT_URI, null) }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); runCurrent()
            advanceTimeBy(301); runCurrent()
            assertEquals(revision + 1, connections.revision.value); assertEquals(updates + 1, widgetUpdates)
            connections.disconnect(provider.calendars.single().displayId); runCurrent(); val disconnected = connections.revision.value
            context.contentResolver.notifyChange(CalendarContract.Events.CONTENT_URI, null)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); advanceTimeBy(1000); runCurrent()
            assertEquals(disconnected, connections.revision.value); assertEquals(0, provider.writes)
        } finally { connections.close() }
    }
}
