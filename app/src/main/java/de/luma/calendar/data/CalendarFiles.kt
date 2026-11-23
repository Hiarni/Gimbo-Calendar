package de.luma.calendar.data

import de.luma.calendar.model.*
import org.json.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

object CalendarFiles {
    const val MAX_BYTES = 10 * 1024 * 1024
    fun backup(events: List<Event>, calendars: List<UserCalendar> = UserCalendar.defaults(), exceptions: List<EventException> = emptyList(), tasks: List<CalendarTask> = emptyList(), templates: List<EventTemplate> = emptyList(), travel: List<TravelPlan> = emptyList(), categories: List<EventCategory> = emptyList(), notes: List<CalendarNote> = emptyList()): String {
        val eventIds = events.mapIndexed { index, event -> event.id to index + 1L }.toMap()
        return JSONObject().put("format", "luma-calendar").put("version", 7)
            .put("notes", JSONArray().also { a -> notes.forEach { a.put(NoteCodec.json(it)) } })
            .put("categories", JSONArray().also { a -> categories.forEach { c -> a.put(JSONObject().put("id", c.id).put("name", c.name).put("color", c.color)) } })
            .put("calendars", JSONArray().also { array -> calendars.forEach { c ->
                array.put(JSONObject().put("id", c.id).put("name", c.name).put("color", c.color)
                    .put("defaultCategory", c.defaultCategory?.name ?: JSONObject.NULL))
            } })
            .put("templates", JSONArray().also { array -> templates.forEach { template -> array.put(JSONObject().put("name", template.name).put("event", EventCodec.json(template.event))) } })
            .put("tasks", JSONArray().also { array -> tasks.forEach { task -> array.put(JSONObject().put("title", task.title).put("due", task.due ?: JSONObject.NULL)
                .put("reminderTime", task.reminderTime ?: JSONObject.NULL).put("completed", task.completed).put("repeat", task.repeat.name).put("interval", task.interval).put("anchor", task.anchor ?: JSONObject.NULL).put("calendarId", task.calendarId)) } })
            .put("travel", JSONArray().also { a -> travel.forEach { p -> a.put(JSONObject().put("eventId", eventIds[p.eventId] ?: error("Ungültige Abfahrtserinnerung.")).put("minutes", p.minutes).put("buffer", p.buffer)) } })
            .put("events", JSONArray().also { array -> events.forEachIndexed { index, event -> array.put(EventCodec.json(event.copy(id = index + 1L))) } })
            .put("exceptions", JSONArray().also { array -> exceptions.forEach { change ->
                val id = eventIds[change.eventId] ?: error("Ungültige Serienausnahme.")
                array.put(JSONObject().put("eventId", id).put("originalStart", change.originalStart)
                    .put("replacement", change.replacement?.let { EventCodec.json(it.copy(id = id)) } ?: JSONObject.NULL))
            } }).toString(2).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Die Sicherung ist zu groß (maximal 10 MB)." } }
    }

    fun readBackup(text: String): List<Event> = readCalendarBackup(text).events.map { it.copy(id = 0) }
    fun readCalendarBackup(text: String): CalendarBackup {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Die Sicherung ist zu groß (maximal 10 MB)." }
        val root = JSONObject(text)
        val version = root.getInt("version")
        require(root.getString("format") == "luma-calendar" && version in 1..7) { "Keine unterstützte Gimbo-Kalender-Sicherung." }
        val categories = if (version < 6) emptyList() else root.getJSONArray("categories").let { a ->
            require(a.length() <= 100) { "Maximal 100 Kategorien." }
            (0 until a.length()).map { i -> val c = a.getJSONObject(i); EventCategory(c.getLong("id"), c.getString("name"), c.getLong("color")).checked() }
        }
        val categoryIds = categories.map { it.id }.toSet()
        require(categories.all { it.id > 0 } && categoryIds.size == categories.size && categories.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == categories.size) { "Ungültige Kategoriezuordnung." }
        val array = root.getJSONArray("events")
        require(array.length() <= 10000) { "Maximal 10.000 Termine pro Import." }
        val events = (0 until array.length()).map { index ->
            val e = array.getJSONObject(index)
            EventCodec.read(e, legacy = version == 1, idOverride = if (version < 3) index + 1L else e.getLong("id"))
        }
        require(events.all { it.id > 0 } && events.map { it.id }.distinct().size == events.size) { "Ungültige Serienausnahme." }
        val calendars = if (version == 1) UserCalendar.defaults().filter { c -> events.any { it.calendarId == c.id } } else {
            val values = root.getJSONArray("calendars")
            require(values.length() <= UserCalendar.MAX_COUNT) { "Maximal 100 Kalender." }
            (0 until values.length()).map { index -> val c = values.getJSONObject(index)
                UserCalendar(c.getLong("id"), c.getString("name"), c.getLong("color"),
                    if (c.isNull("defaultCategory")) null else Category.valueOf(c.getString("defaultCategory"))).checked()
            }
        }
        val calendarIds = calendars.map { it.id }.toSet()
        require(calendars.all { it.id > 0 } && calendarIds.size == calendars.size && events.all { it.calendarId in calendarIds }) { "Ungültige Kalenderzuordnung in der Sicherung." }
        val parents = events.associateBy { it.id }
        val changes = if (version < 3) emptyList() else {
            val values = root.getJSONArray("exceptions")
            require(values.length() <= 10000) { "Maximal 10.000 Serienausnahmen." }
            (0 until values.length()).map { index ->
                val change = values.getJSONObject(index)
                val id = change.getLong("eventId")
                val replacement = if (change.isNull("replacement")) null else EventCodec.read(change.getJSONObject("replacement"), idOverride = id)
                require(replacement == null || replacement.calendarId in calendarIds) { "Ungültige Kalenderzuordnung in der Sicherung." }
                EventException(id, change.getString("originalStart"), replacement).checked(parents[id] ?: error("Ungültige Serienausnahme."))
            }
        }
        require(changes.map { it.eventId to it.originalStart }.distinct().size == changes.size) { "Ungültige Serienausnahme." }
        val tasks = if (version < 4) emptyList() else root.getJSONArray("tasks").let { values ->
            require(values.length() <= 10000) { "Maximal 10.000 Aufgaben." }
            (0 until values.length()).map { i -> val task = values.getJSONObject(i)
                CalendarTask(title = task.getString("title"), due = if (task.isNull("due")) null else task.getString("due"), completed = task.getBoolean("completed"), reminderTime = if (version < 5 || task.isNull("reminderTime")) null else task.getString("reminderTime"),
                    repeat = Repeat.valueOf(task.getString("repeat")), interval = task.getInt("interval"), anchor = if (task.isNull("anchor")) null else task.getString("anchor"), calendarId = task.getLong("calendarId")).checked().also {
                    require(it.calendarId in calendarIds) { "Ungültige Kalenderzuordnung in der Sicherung." }
                }
            }
        }
        val templates = if (version < 4) emptyList() else root.getJSONArray("templates").let { values ->
            require(values.length() <= 1000) { "Maximal 1.000 Vorlagen." }
            (0 until values.length()).map { i -> val item = values.getJSONObject(i); val event = EventCodec.read(item.getJSONObject("event"), idOverride = 0)
                require(event.calendarId in calendarIds) { "Ungültige Kalenderzuordnung in der Sicherung." }; EventTemplate(name = item.getString("name"), event = event).checked() }
        }
        val travel = if (version < 5) emptyList() else root.getJSONArray("travel").let { a -> require(a.length() <= 10000); (0 until a.length()).map { i -> val p = a.getJSONObject(i); TravelPlan(p.getLong("eventId"), p.getInt("minutes"), p.getInt("buffer")).checked().also { require(it.eventId in parents && !parents[it.eventId]!!.allDay) } } }
        require(travel.map { it.eventId }.distinct().size == travel.size)
        require((events + changes.mapNotNull { it.replacement } + templates.map { it.event }).all { it.categoryId == null || it.categoryId in categoryIds }) { "Ungültige Kategoriezuordnung." }
        val notes = if (version < 7) emptyList() else root.getJSONArray("notes").let { a ->
            require(a.length() <= CalendarNote.MAX_COUNT) { "Maximal 2.000 Notizen." }
            (0 until a.length()).map { NoteCodec.read(a.getJSONObject(it)).also { note -> require(note.calendarId in calendarIds) { "Ungültige Kalenderzuordnung in der Sicherung." } } }
        }
        require(notes.all { it.id > 0 } && notes.map { it.id }.distinct().size == notes.size) { "Bitte Eingaben prüfen." }
        return CalendarBackup(calendars, events, changes, tasks, templates, travel, categories, notes)
    }

    fun ics(events: List<Event>, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault(), exceptions: List<EventException> = emptyList()): String {
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Gimbo//Gimbo Kalender//EN", "CALSCALE:GREGORIAN")
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        EventEngine.occurrences(events, from, to, zone, exceptions).forEach { o ->
            lines += "BEGIN:VEVENT"
            lines += "UID:${UUID.nameUUIDFromBytes(o.key.toByteArray())}@luma.calendar"
            lines += "DTSTAMP:${stamp.format(Instant.now())}"
            if (o.event.allDay) {
                lines += "DTSTART;VALUE=DATE:${o.start.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)}"
                lines += "DTEND;VALUE=DATE:${o.end.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)}"
            } else {
                lines += "DTSTART:${stamp.format(o.start.toInstant())}"
                lines += "DTEND:${stamp.format(o.end.toInstant())}"
            }
            lines += "SUMMARY:${escape(o.event.title)}"
            lines += "LOCATION:${escape(o.event.location)}"
            lines += "DESCRIPTION:${escape(o.event.notes)}"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
        .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
    private fun fold(line: String): String {
        val result = StringBuilder()
        var bytes = 0
        line.codePoints().forEach { cp ->
            val part = String(Character.toChars(cp))
            val size = part.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 75) { result.append("\r\n "); bytes = 1 }
            result.append(part); bytes += size
        }
        return result.toString()
    }
}
