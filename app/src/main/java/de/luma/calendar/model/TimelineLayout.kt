package de.luma.calendar.model

import java.time.*

data class TimeBlock(val occurrence: Occurrence, val startMinute: Int, val endMinute: Int, val lane: Int, val lanes: Int, val conflict: Boolean)
object TimelineLayout {
    fun blocks(occurrences: List<Occurrence>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<TimeBlock> {
        val from = day.atStartOfDay(zone).toInstant(); val until = day.plusDays(1).atStartOfDay(zone).toInstant()
        data class Row(val o: Occurrence, val top: Int, val bottom: Int, var lane: Int = 0)
        val rows = occurrences.filter { !it.event.allDay && it.start.toInstant() < until && it.end.toInstant() > from }.map { o ->
            val top = if (o.start.toInstant() <= from) 0 else o.start.withZoneSameInstant(zone).toLocalTime().toSecondOfDay() / 60
            val bottom = if (o.end.toInstant() >= until) 1440 else o.end.withZoneSameInstant(zone).toLocalTime().toSecondOfDay() / 60
            Row(o, top, maxOf(top + 1, bottom).coerceAtMost(1440))
        }.sortedWith(compareBy<Row> { it.top }.thenBy { it.o.key })
        val result = mutableListOf<TimeBlock>(); var start = 0
        while (start < rows.size) {
            var end = start + 1; var bound = maxOf(rows[start].bottom, rows[start].top + 40)
            while (end < rows.size && rows[end].top < bound) { bound = maxOf(bound, rows[end].bottom, rows[end].top + 40); end++ }
            val group = rows.subList(start, end); val laneEnds = mutableListOf<Int>()
            group.forEach { row ->
                val lane = laneEnds.indexOfFirst { it <= row.top }
                row.lane = if (lane < 0) laneEnds.size.also { laneEnds += maxOf(row.bottom, row.top + 40) } else lane.also { laneEnds[it] = maxOf(row.bottom, row.top + 40) }
            }
            group.forEach { row -> result += TimeBlock(row.o, row.top, row.bottom, row.lane, laneEnds.size,
                rows.any { it.o.key != row.o.key && it.o.start.toInstant() < row.o.end.toInstant() && it.o.end.toInstant() > row.o.start.toInstant() }) }
            start = end
        }
        return result
    }
    fun snappedMinute(minute: Float) = (kotlin.math.round(minute / 15) * 15).toInt().coerceIn(0, 1425)
}
