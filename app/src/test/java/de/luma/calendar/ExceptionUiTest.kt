package de.luma.calendar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import de.luma.calendar.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "en-rGB")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExceptionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[CalendarViewModel::class.java]
    private fun await(condition: () -> Boolean) = compose.waitUntil(10000) { compose.waitForIdle(); condition() }
    private fun click(text: String) = compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun series(title: String, days: Long = 0): Event {
        await { !model.state.value.loading && model.state.value.holidaysLoaded }
        val day = LocalDate.now()
        compose.runOnUiThread { model.save(Event(title = title, start = day.atTime(9, 0).toString(), end = day.atTime(10, 0).toString(), repeat = Repeat.DAILY, until = day.plusDays(days).toString())) }
        await { !model.state.value.busy && model.state.value.events.any { it.title == title } }
        return model.state.value.events.single { it.title == title }
    }
    private fun manageSeries() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Series & individual changes"))
        click("Series & individual changes")
    }
    private fun tag(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }

    @Test fun editOneOccurrenceChangeCalendarSkipAllAndRestoreThroughSeriesManager() {
        val parent = series("UI series individual")
        val original = EventEngine.occurrences(listOf(parent), LocalDate.now(), LocalDate.now().plusDays(1)).single()
        compose.onNodeWithTag("nav-agenda").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("occurrence-${original.key}"))
        tag("occurrence-${original.key}")
        compose.onNodeWithText("Edit this occurrence").performScrollTo().performClick()
        compose.onNodeWithText("Only this occurrence will change. The rest of the series is preserved.").assertExists()
        compose.onNodeWithText("Title").performTextReplacement("Changed occurrence")
        compose.onNodeWithTag("event-editor-list").performScrollToNode(hasTestTag("editor-calendar-2"))
        tag("editor-calendar-2")
        click("Save")
        await { !model.state.value.busy && model.state.value.exceptions.any { it.eventId == parent.id && it.replacement?.title == "Changed occurrence" } }
        assertEquals(parent, model.state.value.events.single { it.id == parent.id })
        compose.onNodeWithTag("nav-search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Changed occurrence")
        tag("filter-calendar-2")
        compose.onNodeWithTag("filter-calendar-2").assertIsSelected()
        await { compose.onAllNodesWithTag("occurrence-${original.key}").fetchSemanticsNodes().isNotEmpty() }
        tag("occurrence-${original.key}")
        compose.onNodeWithText("Individual change to a recurring event").assertExists()
        compose.onNodeWithText("Skip this occurrence").performScrollTo().performClick()
        click("Cancel")
        assertNotNull(model.state.value.exceptions.single { it.eventId == parent.id }.replacement)
        compose.onNodeWithText("Skip this occurrence").performScrollTo().performClick()
        click("Skip")
        await { !model.state.value.busy && model.state.value.exceptions.single { it.eventId == parent.id }.replacement == null }
        compose.onNodeWithTag("occurrence-${original.key}").assertDoesNotExist()
        manageSeries()
        compose.onNodeWithTag("series-list").performScrollToNode(hasTestTag("series-exceptions-${parent.id}"))
        tag("series-exceptions-${parent.id}")
        compose.onNodeWithText("Skipped").assertExists()
        click("Restore original")
        click("Cancel")
        compose.onNodeWithText("Skipped").assertExists()
        click("Restore original")
        click("Restore")
        await { !model.state.value.busy && model.state.value.exceptions.none { it.eventId == parent.id } }
        compose.onNodeWithText("No individual changes. All occurrences follow the series.").assertExists()
        click("Close")
        repeat(2) { compose.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick) { it() } }
        click("All")
        compose.onNodeWithTag("nav-agenda").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("occurrence-${original.key}"))
        await { compose.onAllNodesWithTag("occurrence-${original.key}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(parent.title).assertExists()
        assertEquals(parent, model.state.value.events.single { it.id == parent.id })
    }

    @Test fun changingSeriesPatternRequiresExplicitExceptionResetAndCanBeCancelled() {
        val parent = series("UI series reset", days = 2)
        val tomorrow = LocalDate.now().plusDays(1).atTime(9, 0).toString()
        compose.runOnUiThread { model.skipOccurrence(parent, tomorrow) }
        await { !model.state.value.busy && model.state.value.exceptions.any { it.eventId == parent.id } }
        manageSeries()
        compose.onNodeWithTag("series-list").performScrollToNode(hasTestTag("edit-series-${parent.id}"))
        tag("edit-series-${parent.id}")
        compose.onNodeWithTag("event-editor-list").performScrollToNode(hasText("Weekly"))
        click("Weekly")
        click("Save")
        compose.onNodeWithText("Reset individual changes?").assertExists()
        click("Keep editing")
        assertEquals(Repeat.DAILY, model.state.value.events.single { it.id == parent.id }.repeat)
        assertTrue(model.state.value.exceptions.any { it.eventId == parent.id })
        click("Save")
        click("Reset & save")
        await { !model.state.value.busy && model.state.value.events.single { it.id == parent.id }.repeat == Repeat.WEEKLY && model.state.value.exceptions.none { it.eventId == parent.id } }
    }
}
