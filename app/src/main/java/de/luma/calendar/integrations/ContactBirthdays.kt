package de.luma.calendar.integrations

import android.content.ContentResolver
import android.provider.ContactsContract
import de.luma.calendar.model.*
import java.time.*
import java.time.format.DateTimeFormatter

object ContactBirthdays {
    fun date(value: String): LocalDate = when {
        value.matches(Regex("--[0-9]{2}-[0-9]{2}")) -> LocalDate.of(2024, value.substring(2, 4).toInt(), value.substring(5, 7).toInt())
        value.matches(Regex("--[0-9]{4}")) -> LocalDate.of(2024, value.substring(2, 4).toInt(), value.substring(4, 6).toInt())
        value.matches(Regex("[0-9]{8}")) -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
        else -> LocalDate.parse(value)
    }
    fun read(resolver: ContentResolver): CalendarBackup {
        val fields = arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE)
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
        val events = resolver.query(ContactsContract.Data.CONTENT_URI, fields, selection,
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), ContactsContract.Data.DISPLAY_NAME)?.use { c ->
            val seen = mutableSetOf<String>()
            buildList<Event> { while (c.moveToNext()) {
                val raw = c.getString(2).orEmpty()
                val date = runCatching { date(raw) }.getOrElse { error("Ein Geburtstag hat ein nicht unterstütztes Datumsformat.") }
                if (!seen.add("${c.getLong(0)}/$date")) continue
                require(size < 10000) { "Maximal 10.000 Termine pro Import." }
                add(Event(id = size + 1L, title = c.getString(1).orEmpty().take(200), start = date.atStartOfDay().toString(), end = date.plusDays(1).atStartOfDay().toString(),
                    allDay = true, category = Category.BIRTHDAY, repeat = Repeat.YEARLY, notes = if (raw.startsWith("--")) "" else date.year.toString()).checked())
            } }
        } ?: error("Geburtstage konnten nicht gelesen werden.")
        require(events.isNotEmpty()) { "Keine Geburtstage in den Kontakten gefunden." }
        return CalendarBackup(UserCalendar.defaults().filter { it.id == 4L }, events)
    }
}
