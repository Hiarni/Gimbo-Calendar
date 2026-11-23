package de.luma.calendar.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calendars")
data class UserCalendar(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFFB9A7FF,
    val defaultCategory: Category? = null,
) : java.io.Serializable {
    fun displayName(translate: (String) -> String): String = defaultCategory?.let { translate(it.label) } ?: name
    fun checked(): UserCalendar {
        require(id >= 0 && name.trim().isNotEmpty() && name.trim().length <= 60) { "Kalendername: 1 bis 60 Zeichen." }
        require(color in 0xFF000000..0xFFFFFFFF && defaultCategory != Category.HOLIDAY) { "Ungültige Kalenderfarbe." }
        return copy(name = name.trim())
    }
    companion object {
        const val MAX_COUNT = 100
        fun defaults() = Category.entries.filter { it != Category.HOLIDAY }.map {
            UserCalendar(it.defaultCalendarId, it.label, it.color, it)
        }
    }
}
fun Event.calendarColor(calendars: List<UserCalendar>): Long = if (category == Category.HOLIDAY) category.color
    else calendars.firstOrNull { it.id == calendarId }?.color ?: category.color
fun Event.calendarLabel(calendars: List<UserCalendar>, translate: (String) -> String): String = if (category == Category.HOLIDAY) translate(category.label)
    else calendars.firstOrNull { it.id == calendarId }?.displayName(translate) ?: translate(category.label)

data class CalendarBackup(val calendars: List<UserCalendar>, val events: List<Event>, val exceptions: List<EventException> = emptyList(), val tasks: List<CalendarTask> = emptyList(), val templates: List<EventTemplate> = emptyList(), val travel: List<TravelPlan> = emptyList(), val categories: List<EventCategory> = emptyList(), val notes: List<CalendarNote> = emptyList())
