package de.luma.calendar.model

import androidx.room.*
import java.time.*
import java.time.temporal.ChronoUnit

@Entity(tableName = "templates", foreignKeys = [ForeignKey(entity = UserCalendar::class, parentColumns = ["id"], childColumns = ["calendarId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("calendarId")])
data class EventTemplate(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val event: Event, val calendarId: Long = event.calendarId) {
    fun checked(): EventTemplate { require(name.isNotBlank() && name.length <= 60 && event.id == 0L && calendarId == event.calendarId) { "Vorlagenname: 1 bis 60 Zeichen." }; return copy(name = name.trim(), event = event.checked()) }
    fun draft(day: LocalDate): Event {
        val old = LocalDateTime.parse(event.start); val duration = Duration.between(old, LocalDateTime.parse(event.end)); val start = day.atTime(old.toLocalTime())
        return event.copy(id = 0, start = start.toString(), end = start.plus(duration).toString(), until = event.until?.let { day.plusDays(ChronoUnit.DAYS.between(old.toLocalDate(), LocalDate.parse(it))).coerceAtMost(LocalDate.of(2100, 12, 31)).toString() })
    }
}
