package de.luma.calendar.integrations

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import de.luma.calendar.model.*
import java.time.*

data class ProviderEdit(val calendar: DeviceCalendar, val eventId: Long?, val baseline: ContentValues?, val draft: Event, val remove: Boolean = false)

object DeviceCalendarMutations {
    val fields = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.ALL_DAY,
        CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.RRULE, CalendarContract.Events.RDATE,
        CalendarContract.Events.EXDATE, CalendarContract.Events.EXRULE, CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.STATUS,
        CalendarContract.Events.DELETED, CalendarContract.Events.DURATION, CalendarContract.Events.HAS_ATTENDEE_DATA)
    fun allowed(context: Context) = DeviceCalendarSources.allowed(context) && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    private fun calendarValues(context: Context, calendar: DeviceCalendar): ContentValues {
        check(allowed(context)) { "Kalender-Schreibfreigabe fehlt." }
        val current = DeviceCalendars.list(context.contentResolver).firstOrNull { it.identity == calendar.identity } ?: error("Quelle nicht mehr verfügbar")
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, current.id)
        return context.contentResolver.query(uri, arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL), null, null, null)?.use { c ->
            check(c.moveToFirst() && c.getString(0).orEmpty() == calendar.account && c.getString(1).orEmpty() == calendar.accountType) { "Quelle nicht mehr verfügbar" }
            require(c.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_EDITOR) { "Dieser Gerätekalender ist schreibgeschützt." }
            ContentValues().apply { put(CalendarContract.Calendars.ACCOUNT_NAME, calendar.account); put(CalendarContract.Calendars.ACCOUNT_TYPE, calendar.accountType); put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, c.getInt(2)) }
        } ?: error("Quelle nicht mehr verfügbar")
    }
    fun prepare(context: Context, calendar: DeviceCalendar, eventId: Long?, remove: Boolean = false, date: LocalDate = LocalDate.now()): ProviderEdit {
        calendarValues(context, calendar)
        if (eventId == null) return ProviderEdit(calendar, null, null, Event(title = "", start = date.atTime(9, 0).toString(), end = date.atTime(10, 0).toString()), remove)
        require(eventId >= 0)
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val values = context.contentResolver.query(uri, fields, null, null, null)?.use { c ->
            check(c.moveToFirst()) { "Dieser Termin wurde bereits entfernt." }
            ContentValues().apply { fields.forEachIndexed { i, field -> if (c.isNull(i)) putNull(field) else put(field, c.getString(i)) } }
        } ?: error("Dieser Termin wurde bereits entfernt.")
        require(values.getAsLong(CalendarContract.Events.CALENDAR_ID) == calendar.id && values.getAsInteger(CalendarContract.Events.DELETED) == 0) { "Dieser Termin wurde bereits entfernt." }
        require(listOf(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.EXDATE, CalendarContract.Events.EXRULE).all { values.getAsString(it).isNullOrBlank() } && values.getAsLong(CalendarContract.Events.ORIGINAL_ID) == null && (values.getAsInteger(CalendarContract.Events.HAS_ATTENDEE_DATA) ?: 0) == 0) { "Serien und Einladungen bitte in der ursprünglichen Kalender-App bearbeiten." }
        val allDay = values.getAsInteger(CalendarContract.Events.ALL_DAY) != 0
        var zone = if (allDay) ZoneOffset.UTC else runCatching { ZoneId.of(values.getAsString(CalendarContract.Events.EVENT_TIMEZONE)) }.getOrDefault(ZoneId.systemDefault())
        val start = Instant.ofEpochMilli(values.getAsLong(CalendarContract.Events.DTSTART)); val end = Instant.ofEpochMilli(values.getAsLong(CalendarContract.Events.DTEND) ?: error("Serien und Einladungen bitte in der ursprünglichen Kalender-App bearbeiten."))
        if (!allDay && (start.atZone(zone).toLocalDateTime().atZone(zone).toInstant() != start || end.atZone(zone).toLocalDateTime().atZone(zone).toInstant() != end || end.atZone(zone).toLocalDateTime() <= start.atZone(zone).toLocalDateTime())) zone = ZoneOffset.UTC
        val draft = Event(title = values.getAsString(CalendarContract.Events.TITLE).orEmpty().ifBlank { calendar.name }, start = start.atZone(zone).toLocalDateTime().toString(), end = end.atZone(zone).toLocalDateTime().toString(), zone = zone.id, allDay = allDay,
            location = values.getAsString(CalendarContract.Events.EVENT_LOCATION).orEmpty(), notes = values.getAsString(CalendarContract.Events.DESCRIPTION).orEmpty()).checked()
        return ProviderEdit(calendar, eventId, values, draft, remove)
    }
    fun write(context: Context, edit: ProviderEdit, draft: Event = edit.draft): Long? {
        val calendar = calendarValues(context, edit.calendar)
        val operations = arrayListOf(ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, edit.calendar.id)).withValues(calendar).withExpectedCount(1).build())
        val uri = edit.eventId?.let { ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it) }
        if (uri != null) operations += ContentProviderOperation.newAssertQuery(uri).withValues(edit.baseline ?: error("baseline")).withExpectedCount(1).build()
        if (edit.remove) { require(uri != null); operations += ContentProviderOperation.newDelete(uri).withExpectedCount(1).build() }
        else {
            val e = draft.copy(id = 0, calendarId = 1, reminderMinutes = -1).checked(); require(e.repeat == Repeat.NONE)
            val zone = if (e.allDay) ZoneOffset.UTC else ZoneId.of(e.zone)
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, edit.calendar.id); put(CalendarContract.Events.TITLE, e.title)
                put(CalendarContract.Events.DTSTART, LocalDateTime.parse(e.start).atZone(zone).toInstant().toEpochMilli()); put(CalendarContract.Events.DTEND, LocalDateTime.parse(e.end).atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id); put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_LOCATION, e.location); put(CalendarContract.Events.DESCRIPTION, e.notes)
            }
            operations += if (uri == null) ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values).build() else ContentProviderOperation.newUpdate(uri).withValues(values).withExpectedCount(1).build()
        }
        val result = try { context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations) }
            catch (e: OperationApplicationException) { throw IllegalStateException("Der Originaltermin oder Kalender wurde geändert. Bitte erneut öffnen; es wurde nichts überschrieben.", e) }
        return if (edit.remove) null else edit.eventId ?: ContentUris.parseId(result.last().uri ?: error("Speichern nicht bestätigt."))
    }
}
