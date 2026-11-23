package de.luma.calendar.model

import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

data class Holiday(
    val uid: String,
    val date: LocalDate,
    val end: LocalDate = date.plusDays(1),
    val name: String,
    val localName: String = name,
    val country: String = "",
    val regions: Set<String> = emptySet(),
    val national: Boolean = true,
    val imported: Boolean = false,
) {
    fun event(locale: Locale): Event {
        val bytes = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())

        val number = (java.nio.ByteBuffer.wrap(bytes).long and Long.MAX_VALUE) or 1L
        return Event(id = -maxOf(3L, number), title = if (locale.language == "de") localName else name,
            start = date.atStartOfDay().toString(), end = end.atStartOfDay().toString(), allDay = true,
            notes = if (imported) "Quelle: importierte ICS-Datei" else "Quelle: Nager.Date",
            category = Category.HOLIDAY, reminderMinutes = -1)
    }
}
data class HolidaySnapshot(val holidays: List<Holiday> = emptyList()) {
    fun visible(settings: AppSettings) = holidays.filter {
        if (it.imported) settings.importedHolidays else settings.holidays && it.country == settings.holidayCountry &&
            (it.national || (settings.holidayRegion.isNotBlank() && settings.holidayRegion in it.regions))
    }
    fun events(settings: AppSettings, locale: Locale) = visible(settings).map { it.event(locale) }
    fun regions(country: String) = holidays.filter { !it.imported && it.country == country }.flatMap { it.regions }.distinct().sorted()
    fun years(country: String) = holidays.filter { !it.imported && it.country == country }.map { it.date.year }.distinct().sorted()
}
