package de.luma.calendar

import android.content.*
import android.database.*
import android.net.Uri
import android.provider.CalendarContract
import de.luma.calendar.integrations.DeviceCalendar
import java.time.*

internal open class MutableCalendarProvider : ContentProvider() {
    var calendars = mutableListOf(DeviceCalendar(7, "Work online", "work@example.test", 0xFF9DCBEA, "calendar.test"))
    data class Row(val id: Long, val calendar: Long, val title: String, val start: Instant, val end: Instant, val allDay: Boolean = false, val zone: String = "Europe/Vienna", val notes: String = "Personal provider note", val location: String = "Vienna")
    var rows = mutableListOf(Row(70, 7, "Connected meeting", LocalDate.now().atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant(), LocalDate.now().atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant()))
    var instanceQueries = 0
    var queries = 0
    var writes = 0
    var failInstances = false
    var lastBounds: List<Long>? = null
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queries++
        val p = projection!!; val cursor = MatrixCursor(p)
        if (uri.path!!.startsWith("/calendars")) calendars.forEach { c ->
            val values = mapOf(CalendarContract.Calendars._ID to c.id, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to c.name, CalendarContract.Calendars.ACCOUNT_NAME to c.account,
                CalendarContract.Calendars.CALENDAR_COLOR to c.color.toInt(), CalendarContract.Calendars.ACCOUNT_TYPE to c.accountType)
            cursor.addRow(p.map { values[it] }.toTypedArray<Any?>())
        } else {
            instanceQueries++
            if (failInstances) throw IllegalStateException("Provider temporarily unavailable")
            val bounds = uri.pathSegments.takeLast(2).map(String::toLong); lastBounds = bounds
            val calendar = selectionArgs!!.single().toLong()
            rows.filter { it.calendar == calendar && it.start.toEpochMilli() < bounds[1] && it.end.toEpochMilli() > bounds[0] }.sortedBy { it.start }.forEach { r ->
                val values = mapOf(CalendarContract.Instances.EVENT_ID to r.id, CalendarContract.Instances.TITLE to r.title,
                    CalendarContract.Instances.BEGIN to r.start.toEpochMilli(), CalendarContract.Instances.END to r.end.toEpochMilli(), CalendarContract.Instances.ALL_DAY to if (r.allDay) 1 else 0,
                    CalendarContract.Instances.EVENT_TIMEZONE to r.zone, CalendarContract.Instances.DESCRIPTION to r.notes, CalendarContract.Instances.EVENT_LOCATION to r.location)
                cursor.addRow(p.map { values[it] }.toTypedArray<Any?>())
            }
        }
        return cursor
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? { writes++; error("Read-only fixture") }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { writes++; error("Read-only fixture") }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { writes++; error("Read-only fixture") }
}
