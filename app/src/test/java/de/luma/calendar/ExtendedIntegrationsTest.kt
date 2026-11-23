package de.luma.calendar

import android.app.Application
import android.content.*
import android.database.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.*
import de.luma.calendar.integrations.*
import de.luma.calendar.model.*
import de.luma.calendar.reminders.ExtraReminderScheduler
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.*
import java.time.*
import java.util.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29,35], application = Application::class)
class ExtendedIntegrationsTest {
    private lateinit var context: Context; private lateinit var db: CalendarDatabase
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); db = CalendarDatabase.memoryBuilder(context).build() }
    @After fun close() { db.close() }
    private fun event(title: String = "Planning") = Event(title = title, start = "2030-10-01T10:00", end = "2030-10-01T11:00", zone = "Europe/Vienna")
    @Test fun meetingLinksKeepTokensAndRejectCredentialsUnsafeSchemesAndMalformedHosts() {
        val links = MeetingLinks.read("Join https://meet.google.com/abc?token=secret.", "https://teams.microsoft.com/l/meetup-join/ABC https://meet.google.com/abc?token=secret", "javascript:alert(1) http://bad.test https://name@bad.test/x https://good.test:444/x https://bad\\test/path")
        assertEquals(listOf("https://meet.google.com/abc?token=secret", "https://teams.microsoft.com/l/meetup-join/ABC"), links)
        assertEquals("meet.google.com", MeetingLinks.label(links.first()))
        assertEquals(listOf("https://example.test/path(abc)"), MeetingLinks.read("(https://example.test/path(abc))"))
    }
    @Test fun schoolDatesIncludeLastDayKeepLocalesAndCacheEmptySelectionsWithoutFabricatingCoverage() {
        val school = """[{"id":"00000000-0000-0000-0000-000000000001","type":"School","startDate":"2026-12-24","endDate":"2027-01-06","name":[{"language":"DE","text":"Weihnachtsferien"},{"language":"EN","text":"Christmas break"}]}]"""
        val store = SchoolHolidays(context) { url -> if (url.contains("SchoolHolidays")) school else "[]" }
        store.fetch("DE", "DE-BY", "", 2026)
        val settings = AppSettings(schoolHolidays = true, schoolCountry = "DE", schoolRegion = "DE-BY")
        val snapshot = store.read(settings); assertEquals("2027-01-07T00:00", snapshot.events(settings, Locale.ENGLISH).single().end)
        assertEquals("Weihnachtsferien", snapshot.events(settings, Locale.GERMAN).single().title); assertEquals("Christmas break", snapshot.events(settings, Locale.ENGLISH).single().title)
        assertTrue(snapshot.events(settings.copy(schoolRegion = "DE-BE"), Locale.ENGLISH).isEmpty())
        SchoolHolidays(context) { "[]" }.fetch("AT", "AT-9", "", 2026)
        val empty = SchoolHolidays(context).read(settings.copy(schoolCountry = "AT", schoolRegion = "AT-9"))
        assertEquals(listOf(2026), empty.loadedYears); assertTrue(empty.events(settings.copy(schoolCountry = "AT", schoolRegion = "AT-9"), Locale.ENGLISH).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { SchoolHolidays.parse(school.replace("2027-01-06", "2026-01-01"), "DE", "DE-BY", "", 2026) }
        assertThrows(IllegalArgumentException::class.java) { SchoolHolidays.validate("DE", "../private", "") }
    }
    @Test fun providerHierarchiesAndSchoolSettingsSurviveReopening() {
        val metadata = """[{"code":"DE-BY","name":[{"language":"DE","text":"Bayern"},{"language":"EN","text":"Bavaria"}],"children":[{"code":"DE-BY-X","name":[{"language":"EN","text":"Area"}],"children":[]}]}]"""
        assertEquals(listOf("DE-BY", "DE-BY-X"), SchoolHolidays.choices(metadata).map { it.code })
        val s = AppSettings(schoolHolidays = true, schoolCountry = "DE", schoolRegion = "DE-BY", schoolGroup = "DE-BY-X")
        SettingsStore(context).write(s); assertEquals("DE-BY-X", SettingsStore(context).read().schoolGroup); assertTrue(SettingsStore(context).read().schoolHolidays)
    }
    @Test fun weatherAggregatesAvailableHoursAndKeepsOfflineCacheAfterNetworkFailure() {
        val fixture = """{"properties":{"timeseries":[
          {"time":"2026-10-11T06:00:00Z","data":{"instant":{"details":{"air_temperature":12.5}},"next_1_hours":{"details":{"precipitation_amount":1.2}}}},
          {"time":"2026-10-11T07:00:00Z","data":{"instant":{"details":{"air_temperature":18.0}},"next_1_hours":{"details":{"precipitation_amount":0.3}}}},
          {"time":"2026-10-12T07:00:00Z","data":{"instant":{"details":{"air_temperature":20.0}}}}
        ]}}"""
        val p = WeatherPoint(48.2082,16.3738); val date = LocalDate.parse("2026-10-11")
        val forecast = WeatherStore(context) { fixture }.fetch(p, date, "Europe/Vienna")
        assertEquals(12.5, forecast.minimum, 0.001); assertEquals(18.0, forecast.maximum, 0.001); assertEquals(1.5, forecast.rain!!, 0.001)
        assertNull(WeatherStore.parse(fixture.replace("precipitation_amount", "unknown"), p, date, "Europe/Vienna", Instant.now()).rain)
        val fail = WeatherStore(context) { throw IOException("offline") }
        assertThrows(IOException::class.java) { fail.fetch(p, date, "Europe/Vienna") }
        assertEquals(forecast, fail.read(p, date, "Europe/Vienna"))
        assertThrows(IllegalArgumentException::class.java) { WeatherStore.parse(fixture, p, LocalDate.parse("2030-01-01"), "UTC", Instant.now()) }
        assertThrows(IllegalArgumentException::class.java) { WeatherPoint(Double.NaN, 0.0).checked() }
        fail.clear(); assertNull(fail.read(p, date, "Europe/Vienna"))
    }
    @Test fun placeSearchIsExplicitEncodedCachedAndRejectsInvalidCoordinates() = runBlocking {
        var requests = 0; val fixture = """[{"display_name":"Wien, Österreich","lat":"48.2082","lon":"16.3738"}]"""
        val search = PlaceSearch(context) { url -> requests++; assertTrue(url.contains("q=Wien%20%26%20Umgebung")); assertTrue(url.contains("accept-language=de")); fixture }
        val result = search.search("Wien & Umgebung", "de"); assertEquals(1, requests); assertEquals("Wien, Österreich", result.single().name)
        assertEquals(result, PlaceSearch(context) { error("must use cache") }.search("Wien & Umgebung", "de"))
        assertThrows(IllegalArgumentException::class.java) { PlaceSearch.parse(fixture.replace("48.2082", "200.0")) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { search.search("x", "de") } }; Unit
    }
    @Test fun vtodosRoundTripUnicodeRecurrenceAndAlarmsAndRejectUnsupportedData() {
        val tasks = listOf(CalendarTask(5, "Überprüfung " + "ü".repeat(120), "2028-02-29", repeat = Repeat.YEARLY, reminderTime = "09:30"), CalendarTask(6, "Done", completed = true), CalendarTask(7, "No date"))
        val text = TaskExchange.write(tasks, emptyList(), UUID.randomUUID().toString())
        assertTrue(text.lines().all { it.toByteArray().size <= 75 }); assertTrue(text.contains("BEGIN:VALARM"))
        val read = TaskExchange.read(text, 1); assertEquals(tasks.map { it.copy(id = 0) }, read.map { it.task })
        assertThrows(IllegalArgumentException::class.java) { TaskExchange.read(text.replace("FREQ=YEARLY", "FREQ=YEARLY;BYDAY=MO"), 1) }
        assertThrows(IllegalArgumentException::class.java) { TaskExchange.read(text.replace("ACTION:DISPLAY", "ACTION:EMAIL"), 1) }
        assertThrows(IllegalArgumentException::class.java) { TaskExchange.read(text.replace("FREQ=YEARLY", "FREQ=YEARLY;FREQ=DAILY"), 1) }
        assertThrows(IllegalArgumentException::class.java) { TaskExchange.read(text.replace("STATUS:NEEDS-ACTION", "STATUS:IN-PROCESS"), 1) }
        assertThrows(IllegalArgumentException::class.java) { TaskExchange.read(text.replace("PT9H30M", "PT9H30M0.5S"), 1) }
        assertThrows(IllegalArgumentException::class.java) { TaskExchange.read(text.replace("BEGIN:VTODO", "BEGIN:VEVENT"), 1) }
    }
    @Test fun repeatedTaskImportUpdatesUnchangedDataSkipsConflictsAndRejectsStalePreviews() = runBlocking {
        val initial = ExchangedTask("unique@example.test", CalendarTask(title = "Remote", due = "2030-10-01"))
        TaskExchange.apply(db, TaskExchange.preview(db, listOf(initial))); val id = db.tasks().all().single().id
        assertEquals(TaskChange.UNCHANGED, TaskExchange.preview(db, listOf(initial)).single().change)
        val updated = initial.copy(task = initial.task.copy(title = "Remote 2")); val plan = TaskExchange.preview(db, listOf(updated))
        assertEquals(TaskChange.UPDATE, plan.single().change); TaskExchange.apply(db, plan); assertEquals(id, db.tasks().all().single().id)
        db.tasks().update(db.tasks().find(id)!!.copy(title = "Local 3"))
        val conflict = TaskExchange.preview(db, listOf(initial.copy(task = initial.task.copy(title = "Remote 3"))))
        assertEquals(TaskChange.CONFLICT, conflict.single().change); TaskExchange.apply(db, conflict); assertEquals("Local 3", db.tasks().find(id)!!.title)
        TaskExchange.apply(db, conflict, setOf(initial.uid)); assertEquals("Remote 3", db.tasks().find(id)!!.title)
        val stale = TaskExchange.preview(db, listOf(initial)); db.tasks().update(db.tasks().find(id)!!.copy(title = "Another edit"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { TaskExchange.apply(db, stale) } }; assertEquals("Another edit", db.tasks().find(id)!!.title)
        db.tasks().delete(id); assertNull(db.integrations().origin(initial.uid))
    }
    @Test fun privateAttachmentsAndZipRestoreRemapIdsPreserveBytesAndCascadeMetadata() = runBlocking {
        val source = File(context.cacheDir,"source.txt").apply { writeText("Private notes äöü") }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/plain"
            override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, order: String?): Cursor = MatrixCursor(p!!).also { it.addRow(p.map { if (it == OpenableColumns.DISPLAY_NAME) "notes.txt" else source.length() }.toTypedArray<Any?>()) }
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri,v: ContentValues?): Uri? = error("readonly")
            override fun update(uri: Uri,v: ContentValues?,s: String?,a: Array<out String>?) = error("readonly")
            override fun delete(uri: Uri,s: String?,a: Array<out String>?) = error("readonly")
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "test.documents"; exported = true })
        ShadowContentResolver.registerProviderInternal("test.documents", provider)
        val id = db.events().insert(event()); val store = AttachmentStore(context,db); val a = store.add(id, Uri.parse("content://test.documents/notes"))
        assertEquals(source.readBytes().toList(), store.file(a.fileId).readBytes().toList())
        db.integrations().travel(TravelPlan(id,30,10)); db.tasks().insert(CalendarTask(title="Alarm task",due="2030-10-01",reminderTime="09:00"))
        val out = ByteArrayOutputStream(); AttachmentArchive.write(context,out,db); val read = AttachmentArchive.read(ByteArrayInputStream(out.toByteArray()))
        val corrupt = ByteArrayOutputStream()
        ZipOutputStream(corrupt).use { z -> ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { input -> while (true) {
            val entry = input.nextEntry ?: break; val bytes = input.readBytes(); z.putNextEntry(ZipEntry(entry.name))
            z.write(if (entry.name.startsWith("attachments/")) "tampered".toByteArray() else bytes); z.closeEntry()
        } } }
        assertThrows(IllegalArgumentException::class.java) { AttachmentArchive.read(ByteArrayInputStream(corrupt.toByteArray())) }; assertEquals(1,db.events().all().size)
        store.restore(read); assertEquals(2,db.events().all().size); assertEquals(2,db.integrations().attachments().size); assertEquals(2,db.integrations().travel().size)
        val imported = db.integrations().attachments().first { it.fileId != a.fileId }; assertTrue(imported.eventId != id); assertEquals(source.readText(),store.file(imported.fileId).readText())
        db.events().delete(id); assertFalse(db.integrations().attachments().any { it.eventId == id }); assertFalse(db.integrations().travel().any { it.eventId == id }); store.collectGarbage(); assertFalse(store.file(a.fileId).exists()); assertTrue(source.exists())
    }
    @Test fun archivesRejectTraversalDuplicatesAndExcessiveEntriesBeforeImport() {
        fun zip(names: List<String>): ByteArray { val out=ByteArrayOutputStream(); ZipOutputStream(out).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write("{}".toByteArray()); z.closeEntry() } }; return out.toByteArray() }
        assertThrows(IllegalArgumentException::class.java) { AttachmentArchive.read(ByteArrayInputStream(zip(listOf("../outside")))) }
        assertThrows(IllegalArgumentException::class.java) { AttachmentArchive.read(ByteArrayInputStream(zip(listOf("attachments/not-a-uuid")))) }
        assertThrows(IllegalArgumentException::class.java) { AttachmentStore.bounded(ByteArrayInputStream(ByteArray(1025)),1024) }
    }
    @Test fun jsonFivePreservesTaskAlarmsAndTravelAndStillReadsVersionFour() = runBlocking {
        val task = CalendarTask(title="Review",due="2030-10-01",reminderTime="09:30")
        val e = event().copy(id=55); val json = CalendarFiles.backup(listOf(e),tasks=listOf(task),travel=listOf(TravelPlan(55,30,10)))
        val read=CalendarFiles.readCalendarBackup(json); assertEquals("09:30",read.tasks.single().reminderTime); assertEquals(1L,read.travel.single().eventId)
        CalendarRepository(db).import(read); assertEquals(db.events().all().single().id,db.integrations().travel().single().eventId)
        val old=JSONObject(json).put("version",4); old.remove("travel"); old.getJSONArray("tasks").getJSONObject(0).remove("reminderTime")
        val legacy=CalendarFiles.readCalendarBackup(old.toString()); assertNull(legacy.tasks.single().reminderTime); assertTrue(legacy.travel.isEmpty())
    }
    @Test fun departureRespectsDstMovedOccurrencesAndUsesSeparateTaskIdentities() {
        val e = Event(55,"Trip","2026-10-25T03:15","2026-10-25T04:15",zone="Europe/Vienna")
        val plan=TravelPlan(55,30,15); val (o,time)=ExtraReminderScheduler.departure(e,plan,Instant.parse("2026-10-24T12:00:00Z"))!!
        assertEquals(Instant.parse("2026-10-25T02:15:00Z"),o.start.toInstant()); assertEquals(Instant.parse("2026-10-25T01:30:00Z"),time)
        assertNull(ExtraReminderScheduler.departure(e.copy(allDay=true),plan,Instant.now()))
        assertNotEquals(ExtraReminderScheduler.identity("travel",55),ExtraReminderScheduler.identity("task",55))
        assertNull(ExtraReminderScheduler.taskTime(CalendarTask(55,"Done","2026-10-25",completed=true,reminderTime="09:00")))
    }
}
