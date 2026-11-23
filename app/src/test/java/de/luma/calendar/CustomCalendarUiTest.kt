package de.luma.calendar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "en-rGB")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomCalendarUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createCalendarChooseDefaultRenameAndSafelyRemoveWithItsEvent() {
        val model = ViewModelProvider(compose.activity)[CalendarViewModel::class.java]
        fun await(condition: () -> Boolean) = compose.waitUntil(10000) { compose.waitForIdle(); condition() }
        await { !model.state.value.loading && model.state.value.holidaysLoaded }
        fun click(text: String) = compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) { it() }
        fun manage() {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Manage calendars"))
            compose.onNodeWithText("Manage calendars").performClick()
        }
        fun back() = compose.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        manage()
        click("Add calendar")
        compose.onNodeWithText("Calendar name").performTextInput("Sport")
        compose.onNodeWithText("Custom colour (RRGGBB)").performScrollTo().performTextReplacement("1976D2")
        click("Save")
        await { !model.state.value.busy && model.state.value.calendars.any { it.name == "Sport" } }
        val calendar = model.state.value.calendars.single { it.name == "Sport" }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("default-calendar-${calendar.id}"))
        compose.onNodeWithTag("default-calendar-${calendar.id}").performSemanticsAction(SemanticsActions.OnClick) { it() }
        await { !model.state.value.busy && model.state.value.settings.defaultCalendarId == calendar.id }
        back(); back()
        compose.onNodeWithContentDescription("New event").performClick()
        compose.onNodeWithText("Title").performTextInput("Training")
        compose.onNodeWithTag("event-editor-list").performScrollToNode(hasTestTag("editor-calendar-${calendar.id}"))
        compose.onNodeWithTag("editor-calendar-${calendar.id}").assertIsSelected()
        click("Save")
        await { !model.state.value.busy && model.state.value.events.any { it.title == "Training" } }
        val event = model.state.value.events.single { it.title == "Training" }
        org.junit.Assert.assertEquals(calendar.id, event.calendarId)
        compose.onNodeWithTag("nav-search").performClick()
        compose.onNodeWithText("Search title, location or notes").performTextInput("Sport")
        await { compose.onAllNodesWithText("Training").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Training").assertExists()
        manage()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("manage-calendar-${calendar.id}"))
        compose.onNodeWithTag("manage-calendar-${calendar.id}").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Calendar name").performTextReplacement("Training & Sport")
        click("Save")
        await { !model.state.value.busy && model.state.value.calendars.any { it.name == "Training & Sport" } }
        compose.onNodeWithTag("manage-calendar-${calendar.id}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Delete calendar").performScrollTo().performClick()
        compose.onAllNodesWithText("Cancel").onLast().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Edit calendar").assertExists()
        org.junit.Assert.assertTrue(model.state.value.calendars.any { it.id == calendar.id })
        compose.onNodeWithText("Delete calendar").performScrollTo().performClick()
        compose.onNodeWithText("Events, series, tasks and templates will move", substring = true).assertExists()
        click("Move & delete")
        await { !model.state.value.busy && model.state.value.calendars.none { it.id == calendar.id } }
        org.junit.Assert.assertEquals(event.copy(calendarId = 1), model.state.value.events.single { it.id == event.id })
        org.junit.Assert.assertEquals(1L, model.state.value.settings.defaultCalendarId)
        back(); back()
        compose.onNodeWithTag("filter-calendar-${calendar.id}").assertDoesNotExist()
        compose.onNodeWithTag("nav-search").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Training")
        val result = hasText("Training") and !hasSetTextAction()
        await { compose.onAllNodes(result).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(result).assertExists()
    }
}
