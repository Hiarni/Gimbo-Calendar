package de.luma.calendar.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo
import java.time.*
import java.time.temporal.ChronoUnit

enum class Repeat(val label: String) { NONE("Einmalig"), DAILY("Täglich"), WEEKLY("Wöchentlich"), MONTHLY("Monatlich"), YEARLY("Jährlich") }
enum class Category(val label: String, val color: Long, val defaultCalendarId: Long) {
    PERSONAL("Privat", 0xFFB9A7FF, 1), WORK("Arbeit", 0xFF9DCBEA, 2), FAMILY("Familie", 0xFFA5D6BE, 3), BIRTHDAY("Geburtstag", 0xFFF0BD91, 4), HOLIDAY("Feiertag", 0xFFDBA7BC, 0)
}
@Entity(tableName = "events", foreignKeys = [ForeignKey(entity = UserCalendar::class, parentColumns = ["id"],
    childColumns = ["calendarId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = EventCategory::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("calendarId"), Index("categoryId")])
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val start: String,
    val end: String,
    val zone: String = ZoneId.systemDefault().id,
    val allDay: Boolean = false,
    val category: Category = Category.PERSONAL,
    val location: String = "",
    val notes: String = "",
    val repeat: Repeat = Repeat.NONE,
    val interval: Int = 1,
    val until: String? = null,
    val reminderMinutes: Int = -1,
    @ColumnInfo(defaultValue = "1") val calendarId: Long = category.defaultCalendarId,
    @ColumnInfo(defaultValue = "NULL") val categoryId: Long? = null,
    @ColumnInfo(defaultValue = "'[]'") val additionalReminders: List<Int> = emptyList(),
    @ColumnInfo(defaultValue = "'00:00'") val allDayReminderTime: String = "00:00",
) : java.io.Serializable {
    val reminderOffsets: List<Int> get() = (listOf(reminderMinutes).filter { it >= 0 } + additionalReminders).distinct().sorted()
    fun reminderAt(occurrence: Occurrence, minutes: Int): Instant = if (allDay) occurrence.start.toLocalDate().atTime(LocalTime.parse(allDayReminderTime)).minusMinutes(minutes.toLong()).atZone(occurrence.start.zone).toInstant() else occurrence.start.toInstant().minusSeconds(minutes * 60L)
    fun checked(): Event {
        require(id >= 0 && category != Category.HOLIDAY) { "Ungültige Feiertagsdaten." }
        require(calendarId > 0) { "Bitte einen vorhandenen Kalender wählen." }
        require(title.isNotBlank() && title.length <= 200) { "Bitte einen Titel mit höchstens 200 Zeichen eingeben." }
        val from = LocalDateTime.parse(start)
        val to = LocalDateTime.parse(end)
        ZoneId.of(zone)
        require(from.year in 1900..2100 && to.year in 1900..2101) { "Termine müssen zwischen 1900 und 2100 beginnen." }
        require(to > from && Duration.between(from, to) <= Duration.ofDays(366)) { "Das Ende muss nach dem Beginn liegen; maximal 366 Tage." }
        require(allDay || to.atZone(ZoneId.of(zone)).toInstant() > from.atZone(ZoneId.of(zone)).toInstant()) { "Der Zeitraum ist wegen des Sommerzeitwechsels ungültig." }
        require(interval in 1..99) { "Wiederholungsintervall: 1 bis 99." }
        require(reminderMinutes in -1..40320 && additionalReminders.size <= 4 && additionalReminders.all { it in 0..40320 && it != reminderMinutes } && additionalReminders.distinct().size == additionalReminders.size) { "Ungültige Erinnerung." }
        require(categoryId == null || categoryId > 0) { "Diese Kategorie wurde entfernt." }
        require(allDayReminderTime.matches(Regex("[0-2][0-9]:[0-5][0-9]")) && LocalTime.parse(allDayReminderTime).second == 0) { "Ungültige Erinnerungszeit." }
        require(until == null || LocalDate.parse(until) >= from.toLocalDate()) { "Das Serienende liegt vor dem Beginn." }
        require(until == null || LocalDate.parse(until).year <= 2100) { "Serienende: maximal 2100." }
        require(!allDay || (from.toLocalTime() == LocalTime.MIDNIGHT && to.toLocalTime() == LocalTime.MIDNIGHT))
        require(location.length <= 1000 && notes.length <= 10000)
        return copy(title = title.trim(), location = location.trim(), notes = notes.trim())
    }
}
data class Occurrence(val event: Event, val start: ZonedDateTime, val end: ZonedDateTime,
    val originalStart: String? = null, val changed: Boolean = false) : java.io.Serializable {
    val key: String get() = "${event.id}/${originalStart ?: start.toInstant()}"
}

fun Occurrence.asSingleEvent(): Event {
    val zone = if (event.allDay) start.zone else ZoneId.of(event.zone)
    return event.copy(start = start.withZoneSameInstant(zone).toLocalDateTime().toString(), end = end.withZoneSameInstant(zone).toLocalDateTime().toString(),
        repeat = Repeat.NONE, interval = 1, until = null)
}

object EventEngine {
    fun occurrences(events: List<Event>, from: LocalDate, untilExclusive: LocalDate, viewZone: ZoneId = ZoneId.systemDefault(), exceptions: List<EventException> = emptyList(),
        maxOccurrences: Int = 10000, filterEvent: (Event) -> Boolean = { true }): List<Occurrence> {
        require(untilExclusive > from && ChronoUnit.DAYS.between(from, untilExclusive) <= 3660 && maxOccurrences in 1..100000)
        val rangeStart = from.atStartOfDay(viewZone).toInstant()
        val rangeEnd = untilExclusive.atStartOfDay(viewZone).toInstant()
        val byEvent = exceptions.groupBy { it.eventId }
        data class Cursor(val value: Occurrence, val iterator: Iterator<Occurrence>)
        val queue = java.util.PriorityQueue<Cursor>(compareBy<Cursor> { it.value.start.toInstant() }.thenBy { it.value.event.id }.thenBy { it.value.key })
        fun enqueue(iterator: Iterator<Occurrence>) { if (iterator.hasNext()) queue.add(Cursor(iterator.next(), iterator)) }
        events.forEach { event ->
            val changes = byEvent[event.id].orEmpty().associateBy { it.originalStart }
            if (filterEvent(event)) enqueue(sequence {
                val original = LocalDateTime.parse(event.start)
                val duration = Duration.between(original, LocalDateTime.parse(event.end))
                val zone = if (event.allDay) viewZone else ZoneId.of(event.zone)
                val lower = rangeStart.atZone(zone).toLocalDateTime().minus(duration).minusDays(1)
                val upper = rangeEnd.atZone(zone).toLocalDateTime().plusDays(1)
                var index = if (event.repeat == Repeat.NONE) 0L else maxOf(0, distance(original, lower, event.repeat) / event.interval - 1)
                while (true) {
                    val date = candidate(original, event.repeat, index * event.interval)
                    if (date == null) { if (index > distance(original, upper, event.repeat) / event.interval + 2) break; index++; continue }
                    if (date > upper || (event.until != null && date.toLocalDate() > LocalDate.parse(event.until))) break
                    val start = date.atZone(zone)
                    val end = start.toLocalDateTime().plus(duration).atZone(zone)
                    if (date.toString() !in changes && end.toInstant() > rangeStart && start.toInstant() < rangeEnd && end.toInstant() > start.toInstant())
                        yield(Occurrence(event, start, end, if (event.repeat == Repeat.NONE) null else date.toString()))
                    if (event.repeat == Repeat.NONE) break
                    index++
                }
            }.iterator())
            val replacements = changes.values.mapNotNull { change ->
                val replacement = change.replacement
                if (replacement == null || !filterEvent(replacement) || !isOccurrence(event, LocalDateTime.parse(change.originalStart))) null else {
                    val zone = if (replacement.allDay) viewZone else ZoneId.of(replacement.zone)
                    val begin = LocalDateTime.parse(replacement.start).atZone(zone)
                    val length = Duration.between(LocalDateTime.parse(replacement.start), LocalDateTime.parse(replacement.end))
                    val end = begin.toLocalDateTime().plus(length).atZone(zone)
                    if (end.toInstant() > rangeStart && begin.toInstant() < rangeEnd && end.toInstant() > begin.toInstant())
                        Occurrence(replacement.copy(id = event.id), begin, end, change.originalStart, changed = true) else null
                }
            }.sortedWith(compareBy<Occurrence> { it.start.toInstant() }.thenBy { it.key })
            enqueue(replacements.iterator())
        }

        return buildList { while (queue.isNotEmpty() && size < maxOccurrences) { val cursor = queue.remove(); add(cursor.value); enqueue(cursor.iterator) } }
    }

    fun nextReminder(event: Event, now: Instant, viewZone: ZoneId = ZoneId.systemDefault(), exceptions: List<EventException> = emptyList()): Pair<Occurrence, Instant>? {
        return nextReminders(event, now, viewZone, exceptions).minByOrNull { it.trigger }?.let { it.occurrence to it.trigger }
    }

    data class ScheduledReminder(val occurrence: Occurrence, val minutes: Int, val trigger: Instant)
    fun nextReminders(event: Event, now: Instant, viewZone: ZoneId = ZoneId.systemDefault(), exceptions: List<EventException> = emptyList()): List<ScheduledReminder> {
        val offsets = (event.reminderOffsets + exceptions.filter { it.eventId == event.id }.flatMap { it.replacement?.reminderOffsets.orEmpty() }).distinct()
        if (offsets.isEmpty()) return emptyList()
        val today = now.atZone(viewZone).toLocalDate()
        val candidates = occurrences(listOf(event), today.minusDays(2), today.plusYears(5).coerceAtMost(LocalDate.of(2101, 1, 1)), viewZone, exceptions, maxOccurrences = 20000)
        return offsets.mapNotNull { minutes -> candidates.asSequence().filter { minutes in it.event.reminderOffsets }.map { ScheduledReminder(it, minutes, it.event.reminderAt(it, minutes)) }.filter { it.trigger > now }.minByOrNull { it.trigger } }
    }

    fun isOccurrence(event: Event, date: LocalDateTime): Boolean {
        if (event.repeat == Repeat.NONE || event.interval !in 1..99) return false
        val original = LocalDateTime.parse(event.start)
        val step = distance(original, date, event.repeat)
        return step >= 0 && step % event.interval == 0L && candidate(original, event.repeat, step) == date &&
            (event.until == null || date.toLocalDate() <= LocalDate.parse(event.until))
    }

    private fun distance(start: LocalDateTime, end: LocalDateTime, repeat: Repeat): Long = when (repeat) {
        Repeat.NONE -> 0
        Repeat.DAILY -> ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate())
        Repeat.WEEKLY -> ChronoUnit.WEEKS.between(start.toLocalDate(), end.toLocalDate())
        Repeat.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end))
        Repeat.YEARLY -> (end.year - start.year).toLong()
    }
    internal fun candidate(start: LocalDateTime, repeat: Repeat, step: Long): LocalDateTime? = when (repeat) {
        Repeat.NONE -> start
        Repeat.DAILY -> start.plusDays(step)
        Repeat.WEEKLY -> start.plusWeeks(step)
        Repeat.MONTHLY -> YearMonth.from(start).plusMonths(step).let { month ->
            if (start.dayOfMonth > month.lengthOfMonth()) null else month.atDay(start.dayOfMonth).atTime(start.toLocalTime())
        }
        Repeat.YEARLY -> YearMonth.of(start.year + step.toInt(), start.month).let { month ->
            if (start.dayOfMonth > month.lengthOfMonth()) null else month.atDay(start.dayOfMonth).atTime(start.toLocalTime())
        }
    }
}
