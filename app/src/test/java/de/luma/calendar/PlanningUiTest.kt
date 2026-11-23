package de.luma.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import de.luma.calendar.data.*
import de.luma.calendar.model.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "de-rDE")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanningUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun clear() {
        await { !model.state.value.loading && model.state.value.holidaysLoaded }
        val db = CalendarDatabase.get(compose.activity)
        runBlocking { db.tasks().all().forEach { db.tasks().delete(it.id) }; db.templates().all().forEach { db.templates().delete(it.id) }; db.events().all().forEach { db.events().delete(it.id) } }
        await { model.state.value.events.isEmpty() && model.state.value.tasks.isEmpty() && model.state.value.templates.isEmpty() }
    }
    @After fun cleanup() { (compose.activity.application as CalendarApplication).scope.coroutineContext[Job]?.cancel() }
    private val model get() = androidx.lifecycle.ViewModelProvider(compose.activity)[CalendarViewModel::class.java]
    private fun await(block: () -> Boolean) { compose.waitUntil(10000) { compose.waitForIdle(); block() } }
    @Test fun createCompleteAndReopenTaskThroughTheNativeScreen() {
        compose.waitForIdle(); compose.onNodeWithTag("nav-tasks").performClick(); compose.onNodeWithTag("notebook-tasks-tab").performClick()
        compose.onNodeWithText("Neue Aufgabe").performClick(); compose.onNodeWithText("Titel").performTextInput("Aufgabe UI")
        compose.onNodeWithText("Speichern").performClick()
        await { !model.state.value.busy && compose.onAllNodesWithText("Aufgabe UI").fetchSemanticsNodes().isNotEmpty() }
        val db = CalendarDatabase.get(compose.activity)
        val task = runBlocking { db.tasks().all().single() }
        compose.onNodeWithTag("task-${task.id}").onChildren().filter(isToggleable()).onFirst().assertIsEnabled().performClick()
        await { compose.onAllNodesWithText("Aufgabe UI").fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { db.tasks().find(task.id)!!.completed })
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        await { !model.state.value.busy && compose.onAllNodesWithText("Aufgabe UI").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task-${task.id}").onChildren().filter(isToggleable()).onFirst().assertIsEnabled().performClick()
        await { !runBlocking { db.tasks().find(task.id)!!.completed } }
    }
    @Test fun weekShowsConflictsAndDraggingOpensAnEditorBeforePersistence() {
        val db = CalendarDatabase.get(compose.activity)
        val today = LocalDate.now()
        val event = Event(title = "Zeitplan UI", start = today.atTime(9, 0).toString(), end = today.atTime(10, 0).toString())
        val id = runBlocking { db.events().insert(event) }
        runBlocking { db.events().insert(event.copy(title = "Konflikt UI", start = today.atTime(9, 30).toString(), end = today.atTime(10, 30).toString())) }
        await { model.state.value.events.size == 2 }
        compose.onNodeWithText("Woche").performClick()
        val occurrence = EventEngine.occurrences(listOf(event.copy(id = id)), today, today.plusDays(1)).single()
        val tag = "week-event-${occurrence.key}"
        await { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        val horizontal = (today.dayOfWeek.value - 1) * 156f * compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("week-timeline").performSemanticsAction(SemanticsActions.ScrollBy) { it(horizontal, 0f) }
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Überschneidung").onFirst().assertExists()
        val distance = 72f * compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag(tag).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag(tag).performTouchInput { moveBy(Offset(0f, distance), 200); up() }
        await { compose.onAllNodesWithText("Termin bearbeiten").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(event.start, runBlocking { db.events().find(id)!!.start })
        compose.onNodeWithText("Speichern").performClick()
        await { runBlocking { db.events().find(id)!!.start } != event.start }
        assertEquals(today.atTime(10, 0).toString(), runBlocking { db.events().find(id)!!.start })
    }
    @Test fun futureSeriesEditShowsItsScopeAndPreservesTheEarlierSection() {
        val db = CalendarDatabase.get(compose.activity); val today = LocalDate.now()
        val original = Event(title = "Serie UI", start = today.minusWeeks(1).atTime(9, 0).toString(), end = today.minusWeeks(1).atTime(10, 0).toString(), repeat = Repeat.WEEKLY, until = today.plusWeeks(2).toString())
        val id = runBlocking { db.events().insert(original) }
        compose.waitUntil(10000) { model.state.value.events.any { it.id == id } }
        compose.onNodeWithTag("nav-agenda").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Serie UI"))
        compose.onAllNodesWithText("Serie UI").onFirst().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Ab diesem Termin bearbeiten").performScrollTo().performClick()
        compose.onNodeWithText("Titel").performTextReplacement("Serie ab heute UI")
        compose.onNodeWithText("Speichern").performClick(); compose.onNodeWithText("Serie ab hier ändern?").assertExists()
        assertEquals(1, runBlocking { db.events().all().size })
        compose.onNodeWithText("Weiter bearbeiten").performClick(); compose.onNodeWithText("Speichern").performClick()
        compose.onNodeWithText("Ab hier speichern").performClick()
        await { runBlocking { db.events().all().size } == 2 }
        assertEquals(today.minusDays(1).toString(), runBlocking { db.events().find(id)!!.until })
        assertEquals("Serie UI", runBlocking { db.events().find(id)!!.title })
        assertEquals("Serie ab heute UI", runBlocking { db.events().all().first { it.id != id }.title })
    }
    @Test fun saveAndReuseTemplateThroughEventDetailsAndEditor() {
        val db = CalendarDatabase.get(compose.activity); val today = LocalDate.now()
        val original = Event(title = "Vorlage UI", start = today.atTime(9, 0).toString(), end = today.atTime(10, 30).toString(), location = "Vienna")
        runBlocking { db.events().insert(original) }
        await { model.state.value.events.any { it.title == original.title } }
        compose.onNodeWithTag("nav-agenda").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Vorlage UI"))
        compose.onNodeWithText("Vorlage UI").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Als Vorlage speichern").performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextReplacement("Planungsvorlage UI")
        compose.onNodeWithText("Speichern").performClick()
        await { !model.state.value.busy && model.state.value.templates.size == 1 }
        compose.onNodeWithContentDescription("Neuer Termin").performClick()
        compose.onNodeWithText("Vorlage wählen").performClick(); compose.onNodeWithText("Planungsvorlage UI").performClick()
        compose.onNodeWithText("Titel").assertTextContains("Vorlage UI")
        compose.onNodeWithText("Speichern").performClick()
        await { runBlocking { db.events().all().size } == 2 }
        assertTrue(runBlocking { db.events().all().all { it.location == "Vienna" && it.end.endsWith("10:30") } })
    }

}
