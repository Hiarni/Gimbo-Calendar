package de.luma.calendar

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "en-rGB")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishCalendarUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun englishEditorShowsValidationAndSavesUntranslatedUserContent() {
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("New event").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New event").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter a title of no more than 200 characters.").assertExists()
        compose.onNodeWithText("Title").performTextInput("Mein englischer Prüftermin")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty() }
        repeat(2) { compose.mainClock.advanceTimeBy(5000); compose.waitForIdle() }
        compose.onNodeWithTag("nav-search").performClick()
        compose.onNodeWithText("Search title, location or notes").performTextInput("englischer Prüftermin")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Mein englischer Prüftermin").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mein englischer Prüftermin").assertExists()
    }
    @Test fun importedHolidayHasReadOnlyEnglishDetailsAndLanguageOptionsExist() {
        val model = ViewModelProvider(compose.activity)[CalendarViewModel::class.java]
        compose.waitUntil(10000) { !model.state.value.loading && model.state.value.holidaysLoaded }
        val day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val text = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:test-holiday\nDTSTART;VALUE=DATE:$day\nSUMMARY:Imported holiday\nEND:VEVENT\nEND:VCALENDAR\n"
        val uri = Uri.parse("content://luma-test/holidays.ics")
        Shadows.shadowOf(compose.activity.contentResolver).registerInputStream(uri, text.byteInputStream())
        compose.runOnUiThread { model.importHolidays(uri) }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.holidays.holidays.any { it.name == "Imported holiday" } }
        compose.onNodeWithTag("nav-agenda").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Imported holiday").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Imported holiday").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("This public holiday is read-only. Change country and region or hide holidays in settings.").assertExists()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Delete event").assertDoesNotExist()
        compose.onNodeWithText("Close").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("System language").performClick()
        compose.onNodeWithText("Deutsch").assertExists()
        compose.onNodeWithText("English").assertExists()
        compose.onAllNodesWithText("System language").onLast().assertExists()
    }
}
