package de.luma.calendar

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import de.luma.calendar.data.CalendarDatabase
import de.luma.calendar.model.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "de-rDE-w411dp-h891dp-night-420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshotsGerman : StoreScreenshots(false)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarApplication::class, qualifiers = "en-rGB-w411dp-h891dp-night-420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshotsEnglish : StoreScreenshots(true)

abstract class StoreScreenshots(private val english: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[CalendarViewModel::class.java]
    private val output = File(System.getProperty("screenshots.dir") ?: "build/screenshots", if (english) "en" else "de")
    private fun s(german: String, english: String) = if (this.english) english else german
    private val today = LocalDate.now()
    private val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun await(block: () -> Boolean) { compose.waitUntil(15000) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); block() } }
    private fun settle() { repeat(4) { compose.mainClock.advanceTimeBy(1000); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle() } }
    private fun capture(name: String) {
        settle()
        output.mkdirs()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun at(day: LocalDate, time: String) = day.atTime(LocalTime.parse(time)).toString()
    private fun event(title: String, day: LocalDate, from: String, to: String, category: Category, repeat: Repeat = Repeat.NONE, location: String = "", categoryId: Long? = null) =
        Event(title = title, start = at(day, from), end = at(day, to), category = category, repeat = repeat, location = location, categoryId = categoryId)
    private fun allDay(title: String, day: LocalDate, category: Category, repeat: Repeat = Repeat.NONE) =
        Event(title = title, start = day.atStartOfDay().toString(), end = day.plusDays(1).atStartOfDay().toString(), allDay = true, category = category, repeat = repeat)

    @Before fun seed() {
        Shadows.shadowOf(compose.activity.application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        await { !model.state.value.loading }
        runBlocking {
            val db = CalendarDatabase.get(compose.activity)
            val sport = db.categories().insert(EventCategory(name = s("Sport", "Sport"), color = 0xFFA5D6BE))
            val project = db.categories().insert(EventCategory(name = s("Projekt", "Project"), color = 0xFF9DCBEA))
            val home = db.categories().insert(EventCategory(name = s("Haushalt", "Home"), color = 0xFFF0BD91))
            listOf(
                event(s("Team-Besprechung", "Team meeting"), monday, "09:00", "10:00", Category.WORK, Repeat.WEEKLY, s("Raum 2", "Room 2"), project),
                event(s("Laufen im Park", "Run in the park"), monday.plusDays(1), "18:00", "19:00", Category.PERSONAL, Repeat.WEEKLY, categoryId = sport),
                event(s("Projektplanung", "Project planning"), monday.plusDays(2), "10:00", "11:30", Category.WORK, location = s("Büro", "Office"), categoryId = project),
                event(s("Kundentermin", "Client call"), monday.plusDays(2), "11:00", "12:00", Category.WORK, location = s("Videokonferenz", "Video call"), categoryId = project),
                event(s("Yoga", "Yoga"), monday.plusDays(3), "19:00", "20:00", Category.PERSONAL, Repeat.WEEKLY, categoryId = sport),
                event(s("Zahnarzt", "Dentist"), monday.plusDays(4), "08:30", "09:15", Category.PERSONAL),
                event(s("Wocheneinkauf", "Weekly shopping"), monday.plusDays(5), "10:00", "11:00", Category.FAMILY, Repeat.WEEKLY, categoryId = home),
                event(s("Familienessen", "Family lunch"), monday.plusDays(6), "12:30", "14:00", Category.FAMILY),
                event(s("Mittagspause", "Lunch break"), today, "12:00", "13:00", Category.PERSONAL),
                event(s("Konzert", "Concert"), monday.plusDays(11), "20:00", "22:30", Category.PERSONAL, location = s("Stadthalle", "City hall")),
                event(s("Elternabend", "Parents' evening"), monday.plusDays(16), "19:00", "20:30", Category.FAMILY),
                event(s("Quartalsbericht", "Quarterly report"), monday.plusDays(-5), "14:00", "15:30", Category.WORK, categoryId = project),
                allDay(s("Ausflug ins Grüne", "Day trip"), monday.plusDays(13), Category.FAMILY),
                allDay(s("Geburtstag Alex", "Alex's birthday"), monday.plusDays(9), Category.BIRTHDAY, Repeat.YEARLY),
                allDay(s("Wohnung putzen", "Clean the flat"), monday.plusDays(-2), Category.FAMILY),
            ).forEach { db.events().insert(it) }
            listOf(
                CalendarTask(title = s("Rechnungen prüfen", "Check bills"), due = today.toString()),
                CalendarTask(title = s("Geschenk besorgen", "Buy a present"), due = monday.plusDays(8).toString()),
                CalendarTask(title = s("Pflanzen gießen", "Water the plants"), due = today.plusDays(1).toString(), repeat = Repeat.WEEKLY),
                CalendarTask(title = s("Fahrrad zur Inspektion", "Bike service"), due = monday.plusDays(12).toString()),
                CalendarTask(title = s("Altpapier rausstellen", "Put out recycling"), due = today.minusDays(2).toString(), completed = true),
            ).forEach { db.tasks().insert(it) }
            val now = System.currentTimeMillis()
            listOf(
                CalendarNote(title = s("Einkauf", "Shopping"), kind = NoteKind.CHECKLIST, items = listOf(NoteItem(text = s("Brot", "Bread")), NoteItem(text = s("Äpfel", "Apples")), NoteItem(text = s("Milch", "Milk"), checked = true), NoteItem(text = s("Kaffee", "Coffee"))), labels = listOf(s("Haushalt", "Home")), color = 0xFFA5D6BE, pinned = true, createdAt = now - 4000, updatedAt = now - 4000),
                CalendarNote(title = s("Urlaubsideen", "Holiday ideas"), body = s("Wanderung am See\nStädtetrip im Frühling\nFerienhaus am Meer", "Lakeside hike\nCity trip in spring\nCottage by the sea"), labels = listOf(s("Reisen", "Travel")), color = 0xFF9DCBEA, createdAt = now - 3000, updatedAt = now - 3000),
                CalendarNote(title = s("Buchtipps", "Book tips"), body = s("Drei Romane für den Winter notieren und in der Bibliothek reservieren.", "Note three novels for winter and reserve them at the library."), color = 0xFFB9A7FF, createdAt = now - 2000, updatedAt = now - 2000),
                CalendarNote(title = s("Projekt-Ideen", "Project ideas"), kind = NoteKind.CHECKLIST, items = listOf(NoteItem(text = s("Entwurf skizzieren", "Sketch a draft"), checked = true), NoteItem(text = s("Feedback einholen", "Collect feedback")), NoteItem(text = s("Zeitplan festlegen", "Set a schedule"))), labels = listOf(s("Arbeit", "Work")), color = 0xFFF0BD91, createdAt = now - 1000, updatedAt = now - 1000),
            ).forEach { db.notes().insert(it) }
        }
        await { model.state.value.events.size >= 15 && model.state.value.notes.size >= 4 && model.state.value.tasks.size >= 5 }
    }

    @After fun finish() { (compose.activity.application as CalendarApplication).scope.cancel() }

    @Test fun capture() {
        capture("01-month")
        compose.onAllNodesWithText(s("Woche", "Week")).onFirst().performClick()
        capture("02-week")
        compose.onNodeWithTag("nav-agenda").performClick()
        capture("03-agenda")
        compose.onNodeWithTag("nav-overview").performClick()
        capture("04-overview")
        compose.onNodeWithTag("nav-tasks").performClick()
        capture("05-notes")
        compose.onNodeWithTag("notebook-tasks-tab").performClick()
        capture("06-tasks")
    }
}
