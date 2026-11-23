package de.luma.calendar

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import de.luma.calendar.data.CalendarDatabase
import de.luma.calendar.model.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "en-rGB")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotebookUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[CalendarViewModel::class.java]
    private fun await(block: () -> Boolean) { compose.waitUntil(10000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); block() } }
    @Before fun ready() { await { !model.state.value.loading && model.state.value.holidaysLoaded && !model.state.value.sourcesLoading } }
    @After fun cleanup() {
        (compose.activity.application as CalendarApplication).scope.cancel()
        runBlocking {
            val db = CalendarDatabase.get(compose.activity)
            db.notes().all().forEach { db.notes().delete(it.id) }
            db.events().all().forEach { db.events().delete(it.id) }
            if (db.calendars().find(1) == null) db.calendars().insert(UserCalendar.defaults().first { it.id == 1L })
        }
    }
    @Test fun createChecklistCheckPinArchiveAndSearchThroughNativeControls() {
        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithTag("notebook-add").performClick()
        compose.onNodeWithText("Checklist").performClick()
        compose.onNodeWithText("Title").performTextInput("Trip plan")
        compose.onNodeWithText("Content").performTextInput("Beach week")
        compose.onNodeWithTag("note-editor").performScrollToNode(hasTestTag("note-new-item"))
        compose.onNodeWithTag("note-new-item").performTextInput("Book flights")
        compose.onNodeWithTag("note-add-item").performClick()
        compose.onNodeWithTag("note-new-item").performTextInput("Pack bags")
        compose.onNodeWithTag("note-editor").performScrollToNode(hasText("Labels (separated by commas)"))
        compose.onNodeWithText("Labels (separated by commas)").performTextInput("travel, family")
        compose.onNodeWithText("Save").performClick()
        await { model.state.value.notes.size == 1 && !model.state.value.busy }
        val note = model.state.value.notes.single()
        assertEquals(listOf("Book flights", "Pack bags"), note.items.map { it.text })
        assertEquals(listOf("travel", "family"), note.labels)
        compose.onNodeWithTag("note-item-${note.id}-${note.items.first().id}").performScrollTo().assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        await { model.state.value.notes.single().items.first().checked && !model.state.value.busy }
        compose.onNodeWithContentDescription("Note actions").performScrollTo().performClick()
        compose.onNodeWithText("Pin").performClick()
        await { model.state.value.notes.single().pinned && !model.state.value.busy }
        compose.onNodeWithContentDescription("Note actions").performScrollTo().performClick()
        compose.onNodeWithText("Archive note").performClick()
        await { model.state.value.notes.single().archived && !model.state.value.busy }
        compose.onNodeWithTag("note-${note.id}").assertDoesNotExist()
        compose.onNodeWithText("Archive").performClick()
        compose.onNodeWithTag("note-${note.id}").assertExists()
        compose.onNodeWithText("Search notes").performTextInput("travel")
        compose.onNodeWithTag("note-${note.id}").assertExists()
        compose.onNodeWithText("Search notes").performTextReplacement("missing label")
        compose.onNodeWithText("No matching notes.").assertExists()
    }
    @Test fun settingsReturnKeepsScrollAndCustomHexColourSurvives() {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-list").performScrollToIndex(2)
        compose.onNodeWithTag("custom-colour").performScrollTo().performClick()
        compose.onNodeWithTag("colour-hex").performTextReplacement("123456")
        compose.onNodeWithText("Apply").performClick()
        await { model.state.value.settings.accent == 0xFF123456 }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Categories"))
        val before = compose.onNodeWithTag("settings-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(before > 0)
        compose.onNodeWithText("Categories").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Categories").assertIsDisplayed()
        val after = compose.onNodeWithTag("settings-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 1f)
        compose.activityRule.scenario.recreate()
        await { !model.state.value.loading }
        compose.onNodeWithText("Categories").assertIsDisplayed()
        assertEquals(0xFF123456, model.state.value.settings.accent)
    }
    @Test fun failedSaveKeepsDraftAndAllowsChoosingAnExistingCalendar() {
        compose.onNodeWithTag("nav-tasks").performClick()
        compose.onNodeWithTag("nav-create").performClick()
        compose.onNodeWithText("Title").performTextInput("Keep draft")
        compose.onNodeWithText("Content").performTextInput("Do not discard this text")
        val db = CalendarDatabase.get(compose.activity)
        runBlocking { db.calendars().delete(1) }
        await { model.state.value.calendars.none { it.id == 1L } }
        compose.onNodeWithText("Save").performClick()
        await { model.state.value.noteError != null && !model.state.value.busy }
        compose.onNodeWithTag("note-editor").assertExists()
        compose.onNodeWithText("Content").assertTextContains("Do not discard this text")
        compose.onNodeWithText(model.state.value.noteError!!).assertIsDisplayed()
        compose.onNodeWithTag("note-editor").performScrollToNode(hasText("Work"))
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("Save").performClick()
        await { model.state.value.notes.any { it.title == "Keep draft" } && !model.state.value.busy }
        assertEquals("Do not discard this text", model.state.value.notes.single().body)
        assertEquals(2, model.state.value.notes.single().calendarId)
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
    }
    @Test fun monthShowsEventTitlesAndCompactModeKeepsAccessibleCounts() {
        val today = java.time.LocalDate.now()
        model.save(Event(title = "Board meeting", start = today.atTime(13, 0).toString(), end = today.atTime(14, 0).toString()))
        await { model.state.value.events.any { it.title == "Board meeting" } && !model.state.value.busy }
        val day = compose.onNodeWithTag("calendar-day-$today")
        day.performScrollTo()
        day.assertTextContains("Board meeting", substring = true)
        day.assertContentDescriptionContains("1 events · 0 public holidays", substring = true)
        val detailed = day.fetchSemanticsNode().boundsInRoot.height
        model.settings(model.state.value.settings.copy(monthStyle = MonthStyle.COMPACT))
        compose.waitForIdle()
        day.performScrollTo()
        day.assertContentDescriptionContains("1 events · 0 public holidays", substring = true)
        assertTrue(day.fetchSemanticsNode().boundsInRoot.height < detailed)
    }
}
