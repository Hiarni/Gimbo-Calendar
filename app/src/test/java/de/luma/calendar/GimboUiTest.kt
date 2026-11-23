package de.luma.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import de.luma.calendar.data.CalendarDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "en-rGB")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GimboUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[CalendarViewModel::class.java]
    private fun await(block: () -> Boolean) { compose.waitUntil(10000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); block() } }
    @Before fun permissions() { Shadows.shadowOf(compose.activity.application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS) }
    @After fun finish() {
        (compose.activity.application as CalendarApplication).scope.cancel()
        runBlocking {
            val db = CalendarDatabase.get(compose.activity)
            db.events().all().forEach { db.events().delete(it.id) }
            db.categories().all().forEach { db.categories().delete(it.id) }
            de.luma.calendar.reminders.ReminderScheduler.rebuild(compose.activity)
        }
    }
    @Test fun createCategoryChooseMultipleRemindersAndUseOverviewAndSharedNavigation() {
        await { !model.state.value.loading && model.state.value.holidaysLoaded }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Categories"))
        compose.onNodeWithText("Categories").performClick()
        compose.onNodeWithContentDescription("Add category").performClick()
        compose.onNodeWithText("Name").performTextInput("Health planning")
        compose.onNodeWithText("Save").performClick()
        await { model.state.value.categories.any { it.name == "Health planning" } && !model.state.value.busy }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("New event").performClick()
        compose.onNodeWithText("Title").performTextInput("Planning check")
        compose.onNodeWithTag("event-editor-list").performScrollToNode(hasText("Health planning"))
        compose.onNodeWithTag("editor-category-${model.state.value.categories.single { it.name == "Health planning" }.id}").performClick()
        compose.onNodeWithTag("event-editor-list").performScrollToNode(hasText("15 min"))
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("1 hour").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("1 hour").assertIsSelected()
        compose.onNodeWithText("Custom time").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Lead time").performTextReplacement("120")
        compose.onNodeWithTag("custom-reminder-add").performClick()
        compose.onNodeWithText("Save").performClick()
        await { model.state.value.events.any { it.title == "Planning check" } && !model.state.value.busy }
        val saved = model.state.value.events.single { it.title == "Planning check" }
        assertEquals(setOf(15, 60, 120), saved.reminderOffsets.toSet())
        assertEquals(model.state.value.categories.single { it.name == "Health planning" }.id, saved.categoryId)
        val otherCalendar = model.state.value.calendars.first { it.id != saved.calendarId }
        compose.onNodeWithTag("filter-calendar-${otherCalendar.id}").performScrollTo().performClick()
        compose.onNodeWithTag("nav-overview").performClick()
        await { !model.state.value.sourcesLoading }
        compose.onNodeWithTag("planning-overview").assertExists()
        compose.onNodeWithText("Your week at a glance").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("1 this week"))
        compose.onNodeWithText("1 this week").assertIsDisplayed()
        compose.onNodeWithTag("nav-shared").performClick()
        compose.onNodeWithTag("shared-calendars").assertExists()
        compose.onNodeWithText("Plan together").assertIsDisplayed()
        compose.onNodeWithText("Connect calendar").assertIsDisplayed()
    }
}
