package de.luma.calendar.integrations

import androidx.room.withTransaction
import de.luma.calendar.data.CalendarDatabase
import de.luma.calendar.data.CalendarFiles
import de.luma.calendar.model.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ExchangedTask(val uid: String, val task: CalendarTask)
enum class TaskChange { NEW, UPDATE, UNCHANGED, LOCAL_CHANGED, CONFLICT }
data class TaskPreview(val incoming: ExchangedTask, val local: CalendarTask?, val origin: TaskOrigin?, val change: TaskChange)

object TaskExchange {
    private val date = DateTimeFormatter.BASIC_ISO_DATE
    fun fingerprint(task: CalendarTask) = JSONObject().put("title", task.title).put("due", task.due ?: JSONObject.NULL).put("completed", task.completed).put("repeat", task.repeat.name).put("interval", task.interval).put("anchor", task.anchor ?: JSONObject.NULL).put("reminderTime", task.reminderTime ?: JSONObject.NULL).toString()
    fun write(tasks: List<CalendarTask>, origins: List<TaskOrigin>, namespace: String): String {
        UUID.fromString(namespace); require(tasks.size <= 10000)
        val uids = origins.associate { it.taskId to it.uid }
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Gimbo Kalender//Tasks 0.7//EN", "CALSCALE:GREGORIAN")
        tasks.forEach { t -> t.checked(); val uid = uids[t.id] ?: "$namespace-${t.id}@luma.calendar"
            require(uid.isNotBlank() && uid.length <= 512 && uid.none { it.isISOControl() })
            lines += "BEGIN:VTODO"; lines += "UID:$uid"; lines += "DTSTAMP:${DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())}"
            lines += "SUMMARY:${escape(t.title)}"; lines += "STATUS:${if (t.completed) "COMPLETED" else "NEEDS-ACTION"}"
            t.due?.let { lines += "DUE;VALUE=DATE:${LocalDate.parse(it).format(date)}" }
            if (t.repeat != Repeat.NONE) { lines += "DTSTART;VALUE=DATE:${LocalDate.parse(t.anchor).format(date)}"; lines += "RRULE:FREQ=${t.repeat.name};INTERVAL=${t.interval}" }
            t.reminderTime?.let { time ->
                val seconds = LocalTime.parse(time).toSecondOfDay()
                lines += "BEGIN:VALARM"; lines += "ACTION:DISPLAY"; lines += "TRIGGER;RELATED=END:${Duration.ofSeconds(seconds.toLong())}"; lines += "DESCRIPTION:${escape(t.title)}"; lines += "END:VALARM"
            }
            lines += "END:VTODO"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }
    fun read(text: String, calendarId: Long): List<ExchangedTask> {
        val invalid = "Keine unterstützte Aufgaben-ICS-Datei. Nur VTODO mit Tagesdatum und einfachen Wiederholungen ist erlaubt."
        require(text.toByteArray().size <= CalendarFiles.MAX_BYTES)
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "").lines().filter { it.isNotEmpty() }
        require(lines.firstOrNull() == "BEGIN:VCALENDAR" && lines.lastOrNull() == "END:VCALENDAR") { invalid }
        val result = mutableListOf<ExchangedTask>(); var p: MutableMap<String, String>? = null; var alarm: MutableMap<String, String>? = null; var reminder: String? = null
        for (line in lines.drop(1).dropLast(1)) when {
            line == "BEGIN:VTODO" -> { require(p == null) { invalid }; p = mutableMapOf(); reminder = null }
            line == "BEGIN:VALARM" -> { require(p != null && alarm == null && reminder == null) { invalid }; alarm = mutableMapOf() }
            line == "END:VALARM" -> {
                val a = alarm ?: throw IllegalArgumentException(invalid); require(a.keys.all { it in setOf("ACTION", "TRIGGER;RELATED=END", "DESCRIPTION") } && a["ACTION"] == "DISPLAY") { invalid }
                val duration = Duration.parse(a["TRIGGER;RELATED=END"] ?: throw IllegalArgumentException(invalid)); require(duration.nano == 0) { invalid }; val seconds = duration.seconds
                require(seconds in 0..86340 && seconds % 60 == 0L) { invalid }; reminder = LocalTime.ofSecondOfDay(seconds).toString(); alarm = null
            }
            alarm != null -> { val colon = line.indexOf(':'); require(colon > 0 && alarm.put(line.substring(0, colon), line.substring(colon + 1)) == null) { invalid } }
            line == "END:VTODO" -> {
                val row = p ?: throw IllegalArgumentException(invalid)
                require(row.keys.all { it in setOf("UID", "SUMMARY", "STATUS", "DUE;VALUE=DATE", "DTSTART;VALUE=DATE", "RRULE", "DTSTAMP", "CREATED", "LAST-MODIFIED", "SEQUENCE", "PERCENT-COMPLETE", "COMPLETED") }) { invalid }
                val uid = row["UID"] ?: throw IllegalArgumentException(invalid); require(uid.isNotBlank() && uid.length <= 512 && uid.none { it.isISOControl() }) { invalid }
                val due = row["DUE;VALUE=DATE"]?.let { LocalDate.parse(it, date).toString() }
                val anchor = row["DTSTART;VALUE=DATE"]?.let { LocalDate.parse(it, date).toString() } ?: due
                val pairs = row["RRULE"]?.split(';')?.map { part -> val pair = part.split('='); require(pair.size == 2) { invalid }; pair[0] to pair[1] }.orEmpty()
                require(pairs.map { it.first }.distinct().size == pairs.size) { invalid }; val rule = pairs.toMap()
                require(rule.keys.all { it in setOf("FREQ", "INTERVAL") } && (row["RRULE"] == null || rule["FREQ"] != null)) { invalid }
                require(rule["FREQ"] == null || rule["FREQ"] in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) { invalid }
                val repeat = rule["FREQ"]?.let { Repeat.valueOf(it) } ?: Repeat.NONE
                require(row["DTSTART;VALUE=DATE"] == null || repeat != Repeat.NONE || anchor == due) { invalid }
                val status = row["STATUS"] ?: "NEEDS-ACTION"; require(status in setOf("COMPLETED", "NEEDS-ACTION")) { invalid }
                val percent = row["PERCENT-COMPLETE"]?.toInt() ?: if (status == "COMPLETED") 100 else 0
                require(percent in listOf(0, 100) && (percent == 100) == (status == "COMPLETED")) { invalid }
                val task = CalendarTask(title = unescape(row["SUMMARY"] ?: throw IllegalArgumentException(invalid)), due = due, completed = status == "COMPLETED", repeat = repeat, interval = rule["INTERVAL"]?.toInt() ?: 1, anchor = anchor, calendarId = calendarId, reminderTime = reminder).checked()
                result += ExchangedTask(uid, task); require(result.size <= 10000); p = null
            }
            line.startsWith("BEGIN:") || line.startsWith("END:") -> throw IllegalArgumentException(invalid)
            p != null -> { val colon = line.indexOf(':'); require(colon > 0 && p.put(line.substring(0, colon), line.substring(colon + 1)) == null) { invalid } }
            else -> { require(line.substringBefore(':') in setOf("VERSION", "PRODID", "CALSCALE", "METHOD") && (!line.startsWith("METHOD:") || line == "METHOD:PUBLISH")) { invalid } }
        }
        require(p == null && alarm == null && lines.count { it == "VERSION:2.0" } == 1 && result.isNotEmpty() && result.map { it.uid }.distinct().size == result.size) { invalid }
        return result
    }
    suspend fun preview(db: CalendarDatabase, incoming: List<ExchangedTask>): List<TaskPreview> = db.withTransaction {
        incoming.map { entry ->
            val origin = db.integrations().origin(entry.uid); val local = origin?.let { db.tasks().find(it.taskId) }
            val remote = fingerprint(entry.task); val current = local?.let(::fingerprint)
            val change = when {
                local == null -> TaskChange.NEW
                current == remote -> TaskChange.UNCHANGED
                current == origin!!.baseline -> TaskChange.UPDATE
                remote == origin.baseline -> TaskChange.LOCAL_CHANGED
                else -> TaskChange.CONFLICT
            }
            TaskPreview(entry, local, origin, change)
        }
    }
    suspend fun apply(db: CalendarDatabase, plan: List<TaskPreview>, replace: Set<String> = emptySet()): Int = db.withTransaction {
        require(plan.size <= 10000 && plan.map { it.incoming.uid }.distinct().size == plan.size)
        require(db.tasks().all().size + plan.count { it.change == TaskChange.NEW } <= 10000)
        var changed = 0
        plan.forEach { p ->
            require(db.integrations().origin(p.incoming.uid) == p.origin && (p.local == null || db.tasks().find(p.local.id) == p.local)) { "Aufgaben wurden inzwischen geändert. Bitte den Import erneut öffnen." }
            if (p.change in setOf(TaskChange.CONFLICT, TaskChange.LOCAL_CHANGED) && p.incoming.uid !in replace) return@forEach
            val task = p.incoming.task.copy(id = p.local?.id ?: 0, calendarId = p.local?.calendarId ?: p.incoming.task.calendarId).checked()
            require(db.calendars().find(task.calendarId) != null)
            val id = if (p.local == null) db.tasks().insert(task) else { if (p.change != TaskChange.UNCHANGED) { check(db.tasks().update(task) == 1); changed++ }; task.id }
            if (p.local == null) changed++
            db.integrations().origin(TaskOrigin(p.incoming.uid, id, fingerprint(task)))
        }
        changed
    }
    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
    private fun unescape(text: String): String { val out = StringBuilder(); var escaped = false; for (c in text) { if (escaped) { require(c in listOf('n', 'N', '\\', ';', ',')); out.append(if (c == 'n' || c == 'N') '\n' else c); escaped = false } else if (c == '\\') escaped = true else out.append(c) }; require(!escaped); return out.toString() }
    private fun fold(line: String): String { val out = StringBuilder(); var bytes = 0; line.codePoints().forEach { cp -> val part = String(Character.toChars(cp)); val size = part.toByteArray().size; if (bytes + size > 75) { out.append("\r\n "); bytes = 1 }; out.append(part); bytes += size }; return out.toString() }
}
