package de.luma.calendar.model

import androidx.room.*
import org.json.JSONObject
import java.time.LocalDateTime

@Entity(tableName = "event_exceptions", primaryKeys = ["eventId", "originalStart"],
    foreignKeys = [ForeignKey(entity = Event::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = UserCalendar::class, parentColumns = ["id"], childColumns = ["calendarId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("calendarId")])
data class EventException(
    val eventId: Long,
    val originalStart: String,
    val replacement: Event? = null,
    val calendarId: Long? = replacement?.calendarId,
) : java.io.Serializable {
    fun checked(parent: Event): EventException {
        val original = LocalDateTime.parse(originalStart)
        require(eventId == parent.id && eventId > 0 && original.toString() == originalStart && EventEngine.isOccurrence(parent, original)) { "Ungültige Serienausnahme." }
        require(calendarId == replacement?.calendarId && (replacement == null ||
            (replacement.id in listOf(0L, eventId) && replacement.repeat == Repeat.NONE && replacement.until == null && replacement.interval == 1))) { "Ungültige Serienausnahme." }
        return copy(replacement = replacement?.copy(id = eventId)?.checked())
    }
}

object EventCodec {
    fun json(e: Event): JSONObject = JSONObject().put("id", e.id).put("title", e.title).put("start", e.start).put("end", e.end)
        .put("zone", e.zone).put("allDay", e.allDay).put("category", e.category.name).put("calendarId", e.calendarId)
        .put("location", e.location).put("notes", e.notes).put("repeat", e.repeat.name).put("interval", e.interval)
        .put("until", e.until ?: JSONObject.NULL).put("reminderMinutes", e.reminderMinutes)
        .put("categoryId", e.categoryId ?: JSONObject.NULL).put("additionalReminders", org.json.JSONArray(e.additionalReminders)).put("allDayReminderTime", e.allDayReminderTime)
    fun read(e: JSONObject, legacy: Boolean = false, idOverride: Long? = null): Event {
        val category = Category.valueOf(e.getString("category"))
        return Event(id = idOverride ?: e.optLong("id", 0), title = e.getString("title"), start = e.getString("start"), end = e.getString("end"),
            zone = e.getString("zone"), allDay = e.getBoolean("allDay"), category = category, location = e.getString("location"),
            notes = e.getString("notes"), repeat = Repeat.valueOf(e.getString("repeat")), interval = e.getInt("interval"),
            until = if (e.isNull("until")) null else e.getString("until"), reminderMinutes = e.getInt("reminderMinutes"),
            calendarId = if (legacy) category.defaultCalendarId else e.getLong("calendarId"),
            categoryId = if (e.isNull("categoryId")) null else e.getLong("categoryId"),
            additionalReminders = e.optJSONArray("additionalReminders")?.let { a -> require(a.length() <= 4); (0 until a.length()).map(a::getInt) }.orEmpty(),
            allDayReminderTime = e.optString("allDayReminderTime", "00:00")).checked()
    }
}
class EventPayloadConverter {
    @TypeConverter fun encodeReminders(value: List<Int>): String = org.json.JSONArray(value).toString()
    @TypeConverter fun decodeReminders(value: String): List<Int> = org.json.JSONArray(value).let { a -> require(a.length() <= 4); (0 until a.length()).map(a::getInt) }
    @TypeConverter fun encode(event: Event?): String? = event?.let { EventCodec.json(it).toString() }
    @TypeConverter fun decode(text: String?): Event? = text?.let { EventCodec.read(JSONObject(it)) }
}

fun Event.futureDraft(originalStart: String): Event {
    val startTime = LocalDateTime.parse(originalStart)
    require(EventEngine.isOccurrence(this, startTime)) { "Ungültige Serienausnahme." }
    val duration = java.time.Duration.between(LocalDateTime.parse(start), LocalDateTime.parse(end))
    return copy(start = startTime.toString(), end = startTime.plus(duration).toString())
}

fun Event.recurrenceChanged(other: Event): Boolean = start != other.start || repeat != other.repeat || interval != other.interval ||
    until != other.until || zone != other.zone || allDay != other.allDay
