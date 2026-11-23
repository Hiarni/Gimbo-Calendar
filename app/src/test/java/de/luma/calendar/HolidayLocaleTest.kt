package de.luma.calendar

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import de.luma.calendar.data.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "de-rAT")
class HolidayLocaleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = """[
        {"date":"2026-01-01","localName":"Neujahr","name":"New Year's Day","countryCode":"DE","global":true,"counties":null,"types":["Public"]},
        {"date":"2026-01-06","localName":"Heilige Drei Könige","name":"Epiphany","countryCode":"DE","global":false,"counties":["DE-BY","DE-BW"],"types":["Public"]},
        {"date":"2026-03-08","localName":"Internationaler Frauentag","name":"International Women's Day","countryCode":"DE","global":false,"counties":["DE-BE"],"types":["Public"]},
        {"date":"2026-12-24","localName":"Heiligabend","name":"Christmas Eve","countryCode":"DE","global":true,"counties":null,"types":["Bank"]}
    ]"""
    private val ics = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:one@example
DTSTART;VALUE=DATE:20261010
DTEND;VALUE=DATE:20261011
SUMMARY:Feiertag\, lokal
END:VEVENT
END:VCALENDAR
"""
    @Test @Config(sdk = [29]) fun android10LanguageChoiceSurvivesStoreReopeningWithoutChangingDeviceCountry() {
        val settings = SettingsStore(context)
        assertEquals("AT", settings.read().holidayCountry)
        settings.write(settings.read().copy(language = AppLanguage.ENGLISH))
        assertEquals(AppLanguage.ENGLISH, SettingsStore(context).read().language)
        assertEquals("Calendar", AppLocale.wrap(context).text("Kalender"))
        assertEquals("AT", SettingsStore(context).read().holidayCountry)
        settings.write(settings.read().copy(language = AppLanguage.GERMAN))
        assertEquals("Kalender", AppLocale.wrap(context).text("Kalender"))
        settings.write(settings.read().copy(language = AppLanguage.SYSTEM))
        assertEquals(AppLanguage.SYSTEM, settings.read().language)
    }
    @Test fun appLanguageKeepsDeviceCountryAndNativeLanguageSettingsInSync() {
        val settings = SettingsStore(context)
        assertEquals("AT", settings.read().holidayCountry)
        val native = context.getSystemService(LocaleManager::class.java)
        native.applicationLocales = LocaleList.forLanguageTags("en")
        assertEquals(AppLanguage.ENGLISH, settings.read().language)
        assertEquals("AT", settings.read().holidayCountry)
        assertEquals("Calendar", AppLocale.wrap(context).text("Kalender"))
        native.applicationLocales = LocaleList.forLanguageTags("de")
        assertEquals("Kalender", AppLocale.wrap(context).text("Kalender"))
        settings.write(settings.read().copy(holidayCountry = "CH", holidayRegion = "CH-ZH", holidays = true))
        native.applicationLocales = LocaleList.forLanguageTags("en")
        assertEquals("CH-ZH", settings.read().holidayRegion)
        assertTrue(settings.read().holidays)
        assertEquals("CH", settings.read().holidayCountry)
    }
    @Test @Config(qualifiers = "is-rIS") fun unsupportedSystemLanguageFallsBackToEnglishWithoutChangingCountry() {
        assertEquals(Locale.ENGLISH, AppLocale.locale(context))
        assertEquals("IS", SettingsStore(context).read().holidayCountry)
        assertEquals("Save", AppLocale.wrap(context).text("Speichern"))
    }
    @Test fun everyTranslationHasACompleteGermanResourceAndFormatsInBothLanguages() {
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.forLanguageTags("de")
        val german = AppLocale.wrap(context)
        manager.applicationLocales = LocaleList.forLanguageTags("en")
        val english = AppLocale.wrap(context)
        LocalizedText.resources.forEach { (source, _) ->
            assertEquals(source, String.format(Locale.GERMAN, source, "eins", "zwei", "drei", "vier"), german.text(source, "eins", "zwei", "drei", "vier"))
            assertTrue(english.text(source, "one", "two", "three", "four").isNotBlank())
        }
        assertEquals("Add 4 events?", english.text("%1\$s Termine hinzufügen?", 4))
        assertEquals("4 Termine hinzufügen?", german.text("%1\$s Termine hinzufügen?", 4))
        assertEquals("Please check your entries.", english.userError("internal database trace"))
    }
    @Test fun regionalHolidaysAreOptInAndCountryScoped() {
        val snapshot = HolidaySnapshot(HolidayStore.parseApi(api, "DE", 2026))
        assertEquals(3, snapshot.holidays.size)
        assertEquals(0, snapshot.visible(AppSettings(holidays = false, holidayCountry = "DE")).size)
        assertEquals(1, snapshot.visible(AppSettings(holidays = true, holidayCountry = "DE")).size)
        val bavaria = snapshot.visible(AppSettings(holidays = true, holidayCountry = "DE", holidayRegion = "DE-BY"))
        assertEquals(listOf("Neujahr", "Heilige Drei Könige"), bavaria.map { it.localName })
        assertEquals(0, snapshot.visible(AppSettings(holidays = true, holidayCountry = "AT", holidayRegion = "DE-BY")).size)
        val event = bavaria.first().event(Locale.ENGLISH)
        assertEquals("New Year's Day", event.title)
        assertTrue(event.id < -1)
        assertTrue(event.allDay)
        assertEquals(-1, event.reminderMinutes)
        assertNull(EventEngine.nextReminder(event, java.time.Instant.now()))
        assertThrows(IllegalArgumentException::class.java) { event.checked() }
        assertEquals("Neujahr", bavaria.first().event(Locale.GERMAN).title)
    }
    @Test fun apiRejectsMismatchedCountryYearAndOversizedData() {
        assertThrows(IllegalArgumentException::class.java) { HolidayStore.parseApi(api, "AT", 2026) }
        assertThrows(IllegalArgumentException::class.java) { HolidayStore.parseApi(api, "DE", 2027) }
        assertThrows(IllegalArgumentException::class.java) { HolidayStore.parseApi("x".repeat(HolidayStore.MAX_BYTES + 1), "DE", 2026) }
    }
    @Test fun successfulFetchIsOfflinePersistentIdempotentAndFailurePreservesCache() {
        val urls = mutableListOf<String>()
        val store = HolidayStore(context) { url -> urls += url; JSONArray(api).let { values ->
            for (i in 0 until values.length()) { val row = values.getJSONObject(i); row.put("holidayTypes", row.getJSONArray("types")).put("nationalHoliday", row.getBoolean("global")).put("subdivisionCodes", row.opt("counties")); row.remove("localName") }; values.toString()
        } }
        store.fetch("DE", 2026); store.fetch("DE", 2026)
        assertEquals(listOf("https://nagerholidays.com/api/v4/Holidays/DE/2026", "https://nagerholidays.com/api/v4/Holidays/DE/2026"), urls)
        val offline = HolidayStore(context) { error("network unavailable") }
        assertEquals(3, offline.read().holidays.size)
        assertThrows(IllegalStateException::class.java) { offline.fetch("DE", 2026) }
        assertEquals(3, offline.read().holidays.size)
        assertEquals(listOf(2026), offline.read().years("DE"))
    }
    @Test fun icsImportIsSeparateReplaceableAndCanBeHiddenOrRemoved() {
        val store = HolidayStore(context)
        store.importIcs(ics); store.importIcs(ics)
        assertEquals(1, store.read().holidays.size)
        assertEquals("Feiertag, lokal", store.read().holidays.single().name)
        assertEquals(LocalDate.of(2026, 10, 11), store.read().holidays.single().end)
        assertEquals(0, store.read().visible(AppSettings(importedHolidays = false)).size)
        assertEquals(1, store.read().visible(AppSettings(holidayCountry = "US", holidays = false)).size)
        store.removeImport()
        assertTrue(store.read().holidays.isEmpty())
    }
    @Test fun badImportNeverReplacesExistingHolidayData() {
        val store = HolidayStore(context)
        store.importIcs(ics)
        assertThrows(IllegalArgumentException::class.java) { store.importIcs(ics.replace("SUMMARY:", "RRULE:FREQ=YEARLY\nSUMMARY:")) }
        assertThrows(IllegalStateException::class.java) { store.importIcs(ics.replace("DTSTART;VALUE=DATE:20261010", "DTSTART:20261010T100000Z")) }
        assertEquals(1, store.read().holidays.size)
    }
    @Test fun foldedUnicodeEscapesAndMultiDayDatesImportWithoutShiftingTimeZones() {
        val text = ics.replace("20261011", "20261013").replace("Feiertag\\, lokal", "Örtlicher Feiertag\\nZei\n le\\; eins")
        val holiday = HolidayStore.parseIcs(text).single()
        assertEquals("Örtlicher Feiertag\nZeile; eins", holiday.name)
        val occurrence = EventEngine.occurrences(listOf(holiday.event(Locale.ENGLISH)), holiday.date, holiday.end, java.time.ZoneId.of("America/Los_Angeles")).single()
        assertEquals(LocalDate.of(2026, 10, 10), occurrence.start.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 13), occurrence.end.toLocalDate())
    }
    @Test fun holidaysDoNotEnterPersonalBackupsOrMutateRoomEvents() = kotlinx.coroutines.runBlocking {
        val db = CalendarDatabase.memoryBuilder(context).build()
        try {
            val event = Event(title = "Personal", start = "2026-10-10T09:00", end = "2026-10-10T10:00")
            db.events().insert(event)
            HolidayStore(context).importIcs(ics)
            assertEquals(1, db.events().all().size)
            assertEquals("Personal", CalendarFiles.readBackup(CalendarFiles.backup(db.events().all())).single().title)
            HolidayStore(context).removeImport()
            assertEquals(1, db.events().all().size)
        } finally { db.close() }
        Unit
    }
}
