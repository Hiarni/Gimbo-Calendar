package de.luma.calendar.integrations

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import de.luma.calendar.model.*
import java.time.*

data class DeviceCalendar(val id: Long, val name: String, val account: String, val color: Long, val accountType: String = "") {
    val identity: String get() = org.json.JSONArray(listOf(id, accountType, account)).toString()
    val displayId: Long get() = -maxOf(2L, java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))).long and Long.MAX_VALUE)
    fun displayCalendar() = UserCalendar(displayId, name.take(60), color)
}

object DeviceCalendars {
    fun list(resolver: ContentResolver): List<DeviceCalendar> {
        val fields = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.ACCOUNT_TYPE)
        return resolver.query(CalendarContract.Calendars.CONTENT_URI, fields, null, null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)?.use { c ->
            buildList { while (c.moveToNext()) { require(size < 100) { "Maximal 100 Kalender." }; add(DeviceCalendar(c.getLong(0), c.getString(1).orEmpty().ifBlank { "Android" }, c.getString(2).orEmpty(), c.getInt(3).toLong() and 0xFFFFFFFFL or 0xFF000000L, c.getString(4).orEmpty())) } }
        } ?: error("Gerätekalender konnten nicht gelesen werden.")
    }
    fun snapshot(resolver: ContentResolver, calendar: DeviceCalendar, from: LocalDate, until: LocalDate, zone: ZoneId = ZoneId.systemDefault()): CalendarBackup {
        val events = instances(resolver, calendar, from, until, zone, false).map { it.first }
        return CalendarBackup(listOf(UserCalendar(1, calendar.name.take(60), calendar.color).checked()), events)
    }

    fun connected(resolver: ContentResolver, calendar: DeviceCalendar, from: LocalDate, until: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Event, Long>> =
        instances(resolver, calendar, from, until, zone, true).map { (event, providerId) ->
            val begin = LocalDateTime.parse(event.start).atZone(ZoneId.of(event.zone)).toInstant().toEpochMilli()
            val key = "${calendar.identity}/$providerId/$begin"
            val hash = java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))).long
            val id = -maxOf(2L, hash and (Long.MAX_VALUE - 1))
            event.copy(id = id, calendarId = calendar.displayId) to providerId
        }.distinctBy { it.first.id }

    private fun instances(resolver: ContentResolver, calendar: DeviceCalendar, from: LocalDate, until: LocalDate, zone: ZoneId, connected: Boolean): List<Pair<Event, Long>> {
        require(until > from && until <= from.plusYears(2))
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, from.atStartOfDay(zone).toInstant().toEpochMilli())
            ContentUris.appendId(builder, until.atStartOfDay(zone).toInstant().toEpochMilli())
        }.build()
        val fields = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_TIMEZONE) + if (connected) arrayOf(CalendarContract.Instances.EVENT_ID) else emptyArray()
        return resolver.query(uri, fields, "${CalendarContract.Instances.CALENDAR_ID} = ?", arrayOf(calendar.id.toString()), CalendarContract.Instances.BEGIN)?.use { c ->
            buildList<Pair<Event, Long>> { while (c.moveToNext()) {
                require(size < 10000) { "Maximal 10.000 Termine pro Import." }
                val allDay = c.getInt(3) != 0
                var eventZone: ZoneId = if (allDay) ZoneOffset.UTC else runCatching { ZoneId.of(c.getString(6)) }.getOrDefault(zone)
                val begin = Instant.ofEpochMilli(c.getLong(1)); val finish = Instant.ofEpochMilli(c.getLong(2))
                val localBegin = begin.atZone(eventZone).toLocalDateTime(); val localFinish = finish.atZone(eventZone).toLocalDateTime()

                if (connected && !allDay && (localFinish <= localBegin || localBegin.atZone(eventZone).toInstant() != begin || localFinish.atZone(eventZone).toInstant() != finish)) eventZone = ZoneOffset.UTC
                val start = begin.atZone(eventZone).toLocalDateTime()
                val end = finish.atZone(eventZone).toLocalDateTime()
                add(Event(id = size + 1L, title = c.getString(0).orEmpty().ifBlank { calendar.name }.take(200), start = start.toString(), end = end.toString(), zone = eventZone.id,
                    allDay = allDay, location = c.getString(4).orEmpty(), notes = c.getString(5).orEmpty(), calendarId = 1).checked() to if (connected) c.getLong(7) else 0L)
            } }
        } ?: error("Gerätekalender konnten nicht gelesen werden.")
    }
}
