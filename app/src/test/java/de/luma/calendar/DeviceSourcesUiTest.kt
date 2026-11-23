package de.luma.calendar

import android.Manifest
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import de.luma.calendar.data.CalendarDatabase
import de.luma.calendar.integrations.*
import de.luma.calendar.model.Event
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "en-rGB")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceSourcesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[CalendarViewModel::class.java]
    private lateinit var provider: MutableCalendarProvider
    private val store get() = DeviceCalendarLinksStore(compose.activity)
    private val db get() = CalendarDatabase.get(compose.activity)
    private fun await(block: () -> Boolean) { compose.waitUntil(10000) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle(); block()
    } }
    @Before fun setup() {
        assertSame(compose.activity.application, model.getApplication<android.app.Application>())
        await { !model.state.value.loading && model.state.value.holidaysLoaded && !model.state.value.sourcesLoading }
        runBlocking { db.events().all().forEach { db.events().delete(it.id) } }
        compose.activity.getSharedPreferences("device-calendar-links", 0).edit().clear().commit()
        Shadows.shadowOf(compose.activity.application).grantPermissions(Manifest.permission.READ_CALENDAR)
        provider = MutableCalendarProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        model.refreshDeviceSources()
        await { !model.state.value.sourcesLoading && model.state.value.events.isEmpty() && model.state.value.deviceSources.sources.isEmpty() }
    }
    @After fun cleanup() { val app = compose.activity.application as CalendarApplication; app.deviceConnections.close(); app.scope.coroutineContext[Job]?.cancel() }
    private fun manage() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connected device calendars"))
        compose.onNodeWithText("Connected device calendars").performSemanticsAction(SemanticsActions.OnClick) { it() }
        await { compose.onAllNodesWithTag("device-source-manager").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun connectThroughPicker() {
        manage(); compose.onNodeWithText("Connect device calendar").performScrollTo().performClick()
        await { model.state.value.deviceCalendars != null && !model.state.value.busy }
        compose.onNodeWithText("Work online").performClick()
        await { model.state.value.deviceSources.events.size == 1 && !model.state.value.sourcesLoading && !model.state.value.busy }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connected · account calendar"))
        compose.onNodeWithText("Connected · account calendar").assertExists()
    }
    @Test fun connectingThroughSettingsIsReadOnlyAndCopyOpensAnEditorBeforeSaving() {
        provider.rows[0] = provider.rows[0].copy(notes = "Quelle: Nager.Date")
        connectThroughPicker(); assertTrue(runBlocking { db.events().all().isEmpty() })
        compose.onNodeWithTag("nav-agenda").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connected meeting"))
        compose.onNodeWithText("Connected meeting").performClick()
        compose.onNodeWithText("This event comes from a connected device calendar. The Android provider handles reminders and account sync.").assertExists()
        compose.onNodeWithText("Edit").assertDoesNotExist(); compose.onNodeWithText("Remove event").assertDoesNotExist()
        compose.onNodeWithText("Quelle: Nager.Date").assertExists()
        compose.onNodeWithText("Open in calendar app").assertExists()
        compose.onNodeWithText("Copy as a local event").performScrollTo().performClick()
        compose.onNodeWithText("Title").assertExists()
        assertTrue(runBlocking { db.events().all().isEmpty() })
        compose.onNodeWithText("Save").performClick()
        await { !model.state.value.busy && model.state.value.events.size == 1 }
        val local = runBlocking { db.events().all().single() }
        assertTrue(local.id > 0 && local.calendarId > 0); assertEquals("Quelle: Nager.Date", local.notes)
        assertEquals(1, provider.rows.size); assertEquals(0, provider.writes); assertEquals(1, store.read().size)
    }
    @Test fun providerEditsAndDeletionsRefreshAutomaticallyAndDisconnectPreservesLocalData() {
        connectThroughPicker()
        val today = LocalDate.now()
        runBlocking { db.events().insert(Event(title = "Local appointment", start = today.atTime(12, 0).toString(), end = today.atTime(13, 0).toString())) }
        compose.onNodeWithTag("nav-agenda").performClick()
        provider.rows[0] = provider.rows[0].copy(title = "Updated provider appointment")
        compose.activity.contentResolver.notifyChange(CalendarContract.Events.CONTENT_URI, null)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        await { model.state.value.deviceSources.events.singleOrNull()?.title == "Updated provider appointment" }
        compose.onNodeWithText("Connected meeting").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Updated provider appointment"))
        compose.onNodeWithText("Updated provider appointment").assertExists()
        provider.rows.clear(); compose.activity.contentResolver.notifyChange(CalendarContract.Events.CONTENT_URI, null)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        await { model.state.value.deviceSources.events.isEmpty() }
        assertEquals(1, store.read().size)
        manage(); compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Disconnect")); compose.onNodeWithText("Disconnect").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        await { compose.onAllNodesWithText("Disconnect calendar?").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Cancel").performClick(); assertEquals(1, store.read().size)
        await { compose.onAllNodesWithText("Disconnect calendar?").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Disconnect").performScrollTo().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        await { compose.onAllNodesWithText("Disconnect calendar?").fetchSemanticsNodes().size == 1 }
        compose.onNode(hasText("Disconnect") and hasAnyAncestor(isDialog())).performClick()
        await { !model.state.value.busy && model.state.value.deviceSources.sources.isEmpty() }
        assertEquals("Local appointment", runBlocking { db.events().all().single().title }); assertEquals(1, provider.calendars.size); assertEquals(0, provider.writes)
    }
    @Test fun revocationHidesConnectedEventsAndOffersPermissionRecovery() {
        connectThroughPicker()
        Shadows.shadowOf(compose.activity.application).denyPermissions(Manifest.permission.READ_CALENDAR)
        model.refreshDeviceSources()
        await { !model.state.value.sourcesLoading && model.state.value.deviceSources.events.isEmpty() }
        compose.onNodeWithText("Calendar permission required").assertExists()
        compose.onNodeWithText("Allow calendar access").performScrollTo().assertExists()
        assertEquals(1, store.read().size); assertTrue(runBlocking { db.events().all().isEmpty() }); assertEquals(0, provider.writes)
    }
}
