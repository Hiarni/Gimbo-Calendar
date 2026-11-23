package de.luma.calendar.model

import androidx.room.*
import java.time.*

@Entity(tableName = "tasks", foreignKeys = [ForeignKey(entity = UserCalendar::class, parentColumns = ["id"], childColumns = ["calendarId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("calendarId")])
data class CalendarTask(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val due: String? = null,
    val completed: Boolean = false, val repeat: Repeat = Repeat.NONE, val interval: Int = 1, val anchor: String? = due, val calendarId: Long = 1, val reminderTime: String? = null) : java.io.Serializable {
    fun checked(): CalendarTask {
        require(id >= 0 && title.isNotBlank() && title.length <= 200 && calendarId > 0) { "Bitte einen Titel mit höchstens 200 Zeichen eingeben." }
        require(reminderTime == null || due != null && reminderTime.matches(Regex("[0-2][0-9]:[0-5][0-9]")) && runCatching { LocalTime.parse(reminderTime) }.isSuccess) { "Für eine Aufgabenerinnerung Datum und gültige Uhrzeit wählen." }
        require(interval in 1..99 && (repeat == Repeat.NONE || due != null && anchor != null && !completed)) { "Wiederholende Aufgaben benötigen ein Datum." }
        for (value in listOfNotNull(due, anchor)) require(LocalDate.parse(value).year in 1900..2100) { "Termine müssen zwischen 1900 und 2100 beginnen." }
        if (repeat != Repeat.NONE) require(EventEngine.isOccurrence(Event(title = title, start = LocalDate.parse(anchor).atStartOfDay().toString(),
            end = LocalDate.parse(anchor).plusDays(1).atStartOfDay().toString(), allDay = true, repeat = repeat, interval = interval), LocalDate.parse(due).atStartOfDay())) { "Wiederholende Aufgaben benötigen ein Datum." }
        return copy(title = title.trim())
    }
    fun finish(): CalendarTask {
        if (repeat == Repeat.NONE) return copy(completed = !completed)
        val original = LocalDate.parse(anchor).atStartOfDay(); val current = LocalDate.parse(due)
        var step = 0L
        while (step <= 240000) {
            val next = EventEngine.candidate(original, repeat, step++ * interval) ?: continue
            require(next.year <= 2100) { "Serienende: maximal 2100." }
            if (next.toLocalDate() > current) return copy(due = next.toLocalDate().toString()).checked()
        }
        error("Serienende: maximal 2100.")
    }
}
