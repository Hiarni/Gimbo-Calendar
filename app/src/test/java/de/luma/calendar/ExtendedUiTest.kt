package de.luma.calendar

import android.Manifest
import android.content.*
import android.database.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import de.luma.calendar.data.*
import de.luma.calendar.integrations.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=CalendarApplication::class,qualifiers="en-rGB")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtendedUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var scenarioIntent: Intent
    private val model get()=ViewModelProvider(compose.activity)[CalendarViewModel::class.java]
    private val db get()=CalendarDatabase.get(compose.activity)
    private fun await(test:()->Boolean) { compose.waitUntil(15000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); test() } }
    @Before fun setup() {
        scenarioIntent = compose.activity.intent
        await { !model.state.value.loading && model.state.value.holidaysLoaded && !model.state.value.sourcesLoading }
        runBlocking { db.events().all().forEach { db.events().delete(it.id) }; db.tasks().all().forEach { db.tasks().delete(it.id) } }
        compose.activity.getSharedPreferences("device-calendar-links",0).edit().clear().commit(); model.refreshDeviceSources()
        Shadows.shadowOf(compose.activity.application).grantPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR,Manifest.permission.POST_NOTIFICATIONS)
        await { model.state.value.events.isEmpty() && model.state.value.tasks.isEmpty() && !model.state.value.sourcesLoading }
    }
    @After fun cleanup() { if (::scenarioIntent.isInitialized) compose.activity.intent = scenarioIntent; val app=compose.activity.application as CalendarApplication; app.deviceConnections.close(); app.scope.coroutineContext[Job]?.cancel(); runBlocking { db.tasks().all().forEach { db.tasks().delete(it.id) }; db.events().all().forEach { db.events().delete(it.id) }; de.luma.calendar.reminders.ExtraReminderScheduler.rebuild(compose.activity); de.luma.calendar.reminders.ReminderScheduler.rebuild(compose.activity) } }
    private fun send(intent: Intent) { org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(compose.activity, "onNewIntent", org.robolectric.util.ReflectionHelpers.ClassParameter.from(Intent::class.java, intent.setClass(compose.activity, MainActivity::class.java))) }
    private fun click(text:String) { compose.onNodeWithText(text).performScrollTo().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() } }
    @Test fun nativeSourceEditAndConfirmedDeletionChangeProviderAndNeverCreateLocalCopies() {
        val provider=WritableCalendarProvider(); ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        DeviceCalendarLinksStore(compose.activity).connect(provider.calendars.single()); model.refreshDeviceSources(); await { !model.state.value.sourcesLoading && model.state.value.deviceSources.events.size==1 }
        compose.onNodeWithTag("nav-agenda").performClick(); compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connected meeting")); compose.onNodeWithText("Connected meeting").performClick(); click("Edit in device calendar")
        await { model.state.value.providerEditor!=null }; compose.onNode(hasSetTextAction() and hasText("Title")).performTextReplacement("Provider edited in UI"); compose.onNodeWithText("Save").performClick()
        await { !model.state.value.busy && model.state.value.providerEditor==null && model.state.value.deviceSources.events.singleOrNull()?.title=="Provider edited in UI" }
        assertTrue(runBlocking { db.events().all().isEmpty() }); assertEquals(1,provider.writes)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Provider edited in UI")); compose.onNodeWithText("Provider edited in UI").performClick(); click("Remove from device calendar")
        await { model.state.value.providerEditor?.remove==true }; compose.onNodeWithText("Remove event from original calendar?").assertExists(); compose.onNodeWithText("Cancel").performClick(); assertEquals(1,provider.rows.size)
        compose.onNodeWithText("Provider edited in UI").performClick(); click("Remove from device calendar"); await { model.state.value.providerEditor?.remove==true }; compose.onNodeWithText("Delete").performClick()
        await { !model.state.value.busy && model.state.value.providerEditor==null && model.state.value.deviceSources.events.isEmpty() }; assertTrue(provider.rows.isEmpty()); assertTrue(runBlocking { db.events().all().isEmpty() })
    }
    @Test fun sharedDocumentStaysUnsavedUntilUserSavesAndAppearsInEventDetails() {
        val source=File(compose.activity.cacheDir,"shared.txt").apply { writeText("Private file from another app") }
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="text/plain"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,order:String?):Cursor=MatrixCursor(p!!).also { it.addRow(p.map { if(it==OpenableColumns.DISPLAY_NAME) "shared.txt" else source.length() }.toTypedArray<Any?>()) }
            override fun openFile(uri:Uri,mode:String)=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri:Uri,v:ContentValues?):Uri?=error("readonly")
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=error("readonly")
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=error("readonly")
        }
        provider.attachInfo(compose.activity, android.content.pm.ProviderInfo().apply { authority = "share.documents"; exported = true }); ShadowContentResolver.registerProviderInternal("share.documents",provider)
        send(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,Uri.parse("content://share.documents/file")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        await { model.state.value.sharedFile!=null }; compose.onNodeWithText("The shared file is saved only when you save the event.").assertExists(); assertTrue(runBlocking { db.events().all().isEmpty() })
        compose.onNode(hasSetTextAction() and hasText("Title")).performTextInput("Shared document event"); compose.onNodeWithText("Save").performClick()
        await { !model.state.value.busy && model.state.value.events.size==1 && model.state.value.attachments.size==1 }
        compose.onNodeWithTag("nav-agenda").performClick(); compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Shared document event")); compose.onNodeWithText("Shared document event").performClick()
        compose.onNodeWithText("shared.txt").performScrollTo().assertExists(); assertEquals("Private file from another app",source.readText()); val a=model.state.value.attachments.single(); assertEquals(source.readText(),AttachmentStore(compose.activity).file(a.fileId).readText())
    }
    @Test fun taskReminderCanBeEnabledSavedAndCompletedThroughNativeControls() {
        compose.onNodeWithTag("nav-tasks").performClick(); compose.onNodeWithTag("notebook-tasks-tab").performClick(); compose.onNodeWithText("New task").performClick()
        compose.onNode(hasSetTextAction() and hasText("Title")).performTextInput("Reminder task UI")
        compose.onNodeWithTag("task-reminder-enabled").performScrollTo().performClick(); compose.onNode(hasSetTextAction() and hasText("Time (HH:mm)")).performTextReplacement("23:55")
        compose.onNodeWithText("Save").performClick(); await { !model.state.value.busy && model.state.value.tasks.singleOrNull()?.reminderTime=="23:55" }
        val task=model.state.value.tasks.single(); compose.onNodeWithTag("task-${task.id}").onChildren().filter(isToggleable()).onFirst().performClick(); await { model.state.value.tasks.single().completed && !model.state.value.busy }
        assertNull(de.luma.calendar.reminders.ExtraReminderScheduler.taskTime(model.state.value.tasks.single()))
    }
    @Test fun privacyInformationIsReadableInSettings() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Privacy & data sources")); compose.onNodeWithText("Privacy & data sources").performClick()
        compose.onNodeWithText("JSON, ZIP and ICS are unencrypted. Only ZIP includes attachment files. Choose the destination carefully; Android app backup is disabled.").assertExists()
        compose.onNodeWithTag("privacy-information").performScrollToNode(hasText("OpenStreetMap: source and licence")); compose.onNodeWithText("OpenStreetMap: source and licence").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
    }
    @Test fun sharedMeetingTextOpensDraftWithUntranslatedContentAndRecognizedLinkAfterSave() {
        val text="Meeting invitation https://meet.google.com/abc-defg-hij?token=keep"
        send(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"Meeting from share").putExtra(Intent.EXTRA_TEXT,text))
        compose.onNodeWithText("Meeting from share").assertExists(); assertTrue(runBlocking { db.events().all().isEmpty() }); compose.onNodeWithText("Save").performClick()
        await { !model.state.value.busy && model.state.value.events.singleOrNull()?.notes==text }
        compose.onNodeWithTag("nav-agenda").performClick(); compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Meeting from share")); compose.onNodeWithText("Meeting from share").performClick(); compose.onNodeWithText("Open link · meet.google.com").performScrollTo().assertExists()
    }
}
