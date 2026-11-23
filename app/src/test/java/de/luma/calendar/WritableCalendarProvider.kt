package de.luma.calendar

import android.content.*
import android.database.*
import android.net.Uri
import android.provider.CalendarContract
import java.time.Instant

internal class WritableCalendarProvider : MutableCalendarProvider() {
    var access = CalendarContract.Calendars.CAL_ACCESS_OWNER
    val extras = mutableMapOf<Long, ContentValues>()
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (uri.path!!.startsWith("/instances")) return super.query(uri, projection, selection, selectionArgs, sortOrder)
        queries++; val p = projection!!; val cursor = MatrixCursor(p); val id = uri.lastPathSegment?.toLongOrNull()
        if (uri.path!!.startsWith("/calendars")) calendars.filter { id == null || it.id == id }.forEach { c ->
            val values = mapOf(CalendarContract.Calendars._ID to c.id, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to c.name, CalendarContract.Calendars.ACCOUNT_NAME to c.account, CalendarContract.Calendars.ACCOUNT_TYPE to c.accountType, CalendarContract.Calendars.CALENDAR_COLOR to c.color.toInt(), CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL to access)
            cursor.addRow(p.map { values[it] }.toTypedArray<Any?>())
        } else rows.filter { id == null || it.id == id }.forEach { r ->
            val values = mapOf(CalendarContract.Events._ID to r.id, CalendarContract.Events.CALENDAR_ID to r.calendar, CalendarContract.Events.TITLE to r.title, CalendarContract.Events.DTSTART to r.start.toEpochMilli(), CalendarContract.Events.DTEND to r.end.toEpochMilli(), CalendarContract.Events.ALL_DAY to if (r.allDay) 1 else 0, CalendarContract.Events.EVENT_TIMEZONE to r.zone, CalendarContract.Events.DESCRIPTION to r.notes, CalendarContract.Events.EVENT_LOCATION to r.location, CalendarContract.Events.STATUS to 1, CalendarContract.Events.DELETED to 0, CalendarContract.Events.HAS_ATTENDEE_DATA to 0)
            cursor.addRow(p.map { field -> if (extras[r.id]?.containsKey(field) == true) extras[r.id]?.get(field) else values[field] }.toTypedArray<Any?>())
        }
        return cursor
    }
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val saved = rows.toMutableList(); val savedExtras = extras.mapValues { ContentValues(it.value) }; val count = writes
        try { return super.applyBatch(operations) }
        catch (error: Exception) { rows = saved; extras.clear(); extras.putAll(savedExtras); writes = count; throw error }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        writes++; val v = values!!; val id = (rows.maxOfOrNull { it.id } ?: 70) + 1
        rows += Row(id, v.getAsLong(CalendarContract.Events.CALENDAR_ID), v.getAsString(CalendarContract.Events.TITLE), Instant.ofEpochMilli(v.getAsLong(CalendarContract.Events.DTSTART)), Instant.ofEpochMilli(v.getAsLong(CalendarContract.Events.DTEND)), v.getAsInteger(CalendarContract.Events.ALL_DAY) == 1, v.getAsString(CalendarContract.Events.EVENT_TIMEZONE), v.getAsString(CalendarContract.Events.DESCRIPTION), v.getAsString(CalendarContract.Events.EVENT_LOCATION))
        return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val id = ContentUris.parseId(uri); val index = rows.indexOfFirst { it.id == id }; if (index < 0) return 0
        val v = values!!; val old = rows[index]; rows[index] = old.copy(title = v.getAsString(CalendarContract.Events.TITLE), start = Instant.ofEpochMilli(v.getAsLong(CalendarContract.Events.DTSTART)), end = Instant.ofEpochMilli(v.getAsLong(CalendarContract.Events.DTEND)), zone = v.getAsString(CalendarContract.Events.EVENT_TIMEZONE), allDay = v.getAsInteger(CalendarContract.Events.ALL_DAY) == 1, notes = v.getAsString(CalendarContract.Events.DESCRIPTION), location = v.getAsString(CalendarContract.Events.EVENT_LOCATION)); writes++; return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { val id = ContentUris.parseId(uri); val changed = rows.removeAll { it.id == id }; if (changed) writes++; return if (changed) 1 else 0 }
}
