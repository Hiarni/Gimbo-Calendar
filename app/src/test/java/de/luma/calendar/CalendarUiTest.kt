package de.luma.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "de-rDE")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createFindEditAndDeleteRealLocalEvent() {
        compose.activity
        compose.waitForIdle()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Neuer Termin").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Neuer Termin").performClick()
        compose.onNodeWithText("Titel").performTextInput("Kalender UI Prüftermin")
        compose.onNodeWithText("Speichern").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Speichern").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("nav-agenda").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Kalender UI Prüftermin").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kalender UI Prüftermin").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Bearbeiten").performClick()
        compose.onNodeWithText("Titel").performTextReplacement("Kalender UI aktualisiert")
        compose.onNodeWithText("Speichern").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Speichern").fetchSemanticsNodes().isEmpty() }
        repeat(3) { compose.mainClock.advanceTimeBy(5000); compose.waitForIdle() }
        compose.onNodeWithTag("nav-search").performClick()
        compose.onNodeWithText("Titel, Ort oder Notiz suchen").performTextInput("aktualisiert")

        compose.onNodeWithText("Kalender UI aktualisiert").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Termin entfernen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Termin entfernen").performScrollTo().performClick()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.onNodeWithText("Bearbeiten").assertExists()
        compose.onNodeWithText("Termin entfernen").performScrollTo().performClick()
        compose.onNodeWithText("Entfernen", substring = false).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Platz für deine Pläne.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Platz für deine Pläne.").assertExists()
    }
}
