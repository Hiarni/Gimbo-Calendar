package de.luma.calendar.data

import de.luma.calendar.model.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

object IcsFiles {
    private val basic = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val utc = basic.withZone(ZoneOffset.UTC)
    private data class Property(val name: String, val params: Map<String, String>, val value: String)
    private data class Component(val props: MutableList<Property> = mutableListOf(), val alarms: MutableList<List<Property>> = mutableListOf()) {
        fun one(name: String): Property? = props.filter { it.name == name }.also { require(it.size <= 1) { "Ungültige ICS-Datei." } }.firstOrNull()
        fun text(name: String, fallback: String = "") = one(name)?.value?.let(::unescape) ?: fallback
    }
    fun read(text: String, floatingZone: ZoneId = ZoneId.systemDefault()): CalendarBackup {
        require(text.toByteArray(Charsets.UTF_8).size <= CalendarFiles.MAX_BYTES) { "Die Sicherung ist zu groß (maximal 10 MB)." }
        val lines = mutableListOf<String>()
        text.removePrefix("\uFEFF").replace("\r\n", "\n").split('\n').forEach { line ->
            require(line.length <= 20000) { "Ungültige ICS-Datei." }
            if (line.startsWith(' ') || line.startsWith('\t')) {
                require(lines.isNotEmpty()) { "Ungültige ICS-Datei." }
                lines[lines.lastIndex] += line.drop(1)
                require(lines.last().length <= 20000) { "Ungültige ICS-Datei." }
            } else if (line.isNotEmpty()) lines += line
        }
        require(lines.firstOrNull() == "BEGIN:VCALENDAR" && lines.lastOrNull() == "END:VCALENDAR") { "Ungültige ICS-Datei." }
        val stack = mutableListOf<String>()
        val entries = mutableListOf<Component>()
        var entry: Component? = null
        var alarm: MutableList<Property>? = null
        var version = false
        lines.forEach { line ->
            val p = property(line)
            when (p.name) {
                "BEGIN" -> {
                    val type = p.value.uppercase()
                    require(type in setOf("VCALENDAR", "VEVENT", "VALARM", "VTIMEZONE", "STANDARD", "DAYLIGHT")) { "Diese ICS-Funktion wird noch nicht unterstützt." }
                    require(when (type) { "VCALENDAR" -> stack.isEmpty(); "VEVENT", "VTIMEZONE" -> stack.lastOrNull() == "VCALENDAR"; "VALARM" -> stack.lastOrNull() == "VEVENT"; else -> stack.lastOrNull() == "VTIMEZONE" }) { "Ungültige ICS-Datei." }
                    stack += type
                    if (type == "VEVENT") entry = Component()
                    if (type == "VALARM") alarm = mutableListOf()
                }
                "END" -> {
                    require(stack.lastOrNull() == p.value.uppercase()) { "Ungültige ICS-Datei." }
                    if (p.value.uppercase() == "VALARM") { entry!!.alarms += alarm!!; alarm = null }
                    if (p.value.uppercase() == "VEVENT") { entries += entry!!; entry = null; require(entries.size <= 20000) { "Maximal 10.000 Termine pro Import." } }
                    stack.removeAt(stack.lastIndex)
                }
                else -> when (stack.lastOrNull()) {
                    "VCALENDAR" -> if (p.name == "VERSION") { require(!version && p.value == "2.0") { "Ungültige ICS-Datei." }; version = true }
                    "VEVENT" -> entry!!.props += p
                    "VALARM" -> alarm!! += p
                }
            }
        }
        require(stack.isEmpty() && version) { "Ungültige ICS-Datei." }
        val calendars = linkedMapOf<String, UserCalendar>()
        fun calendar(c: Component): Long {
            val key = c.one("X-LUMA-CALENDAR-ID")?.value ?: "import"
            val name = c.text("X-LUMA-CALENDAR-NAME", "ICS").take(60)
            val color = c.one("X-LUMA-CALENDAR-COLOR")?.value?.toLongOrNull() ?: Category.PERSONAL.color
            val value = calendars.getOrPut(key) { UserCalendar(calendars.size + 1L, name, color).checked() }
            require(calendars.size <= UserCalendar.MAX_COUNT) { "Maximal 100 Kalender." }
            return value.id
        }
        val categories = linkedMapOf<String, EventCategory>()
        fun category(c: Component, fallback: Long? = null): Long? {
            val property = c.one("CATEGORIES") ?: return fallback
            require(!Regex("(?<!\\\\),").containsMatchIn(property.value)) { "Mehrere ICS-Kategorien werden noch nicht unterstützt." }
            val name = unescape(property.value)
            val value = categories.getOrPut(name.lowercase(java.util.Locale.ROOT)) {
                EventCategory(categories.size + 1L, name, c.one("X-GIMBO-CATEGORY-COLOR")?.value?.toLongOrNull() ?: 0xFF9DCBEA).checked()
            }
            require(categories.size <= 100) { "Maximal 100 Kategorien." }
            return value.id
        }
        val events = mutableListOf<Event>()
        val changes = mutableListOf<EventException>()
        val grouped = entries.groupBy { it.one("UID")?.value?.takeIf(String::isNotBlank) ?: error("Ungültige ICS-Datei.") }
        grouped.values.forEach { group ->
            val roots = group.filter { it.one("RECURRENCE-ID") == null }
            require(roots.size == 1) { "ICS enthält doppelte oder fehlende Serienursprünge." }
            val root = roots.single()
            require(root.one("STATUS")?.value != "CANCELLED") { "Diese ICS-Funktion wird noch nicht unterstützt." }
            val event = readEvent(root, events.size + 1L, calendar(root), floatingZone).copy(categoryId = category(root))
            events += event
            root.props.filter { it.name == "EXDATE" }.forEach { p ->
                p.value.split(',').forEach { date ->
                    val original = originalDate(p.copy(value = date), event)
                    changes += EventException(event.id, original).checked(event)
                }
            }
            val overrides = group.filter { it !== root }
            require(overrides.map { originalDate(it.one("RECURRENCE-ID")!!, event) }.distinct().size == overrides.size) { "Ungültige Serienausnahme." }
            overrides.forEach { c ->
                val rec = c.one("RECURRENCE-ID")!!
                require("RANGE" !in rec.params) { "Diese ICS-Funktion wird noch nicht unterstützt." }
                val original = originalDate(rec, event)
                val replacement = if (c.one("STATUS")?.value == "CANCELLED") null else readEvent(c, event.id,
                    if (c.one("X-LUMA-CALENDAR-ID") == null) event.calendarId else calendar(c), ZoneId.of(event.zone), event).copy(categoryId = category(c, event.categoryId))

                val prior = changes.indexOfFirst { it.eventId == event.id && it.originalStart == original }
                if (prior >= 0) { require(changes[prior].replacement == null) { "Ungültige Serienausnahme." }; changes.removeAt(prior) }
                changes += EventException(event.id, original, replacement).checked(event)
            }
        }
        require(events.size <= 10000) { "Maximal 10.000 Termine pro Import." }
        require(changes.size <= 10000 && changes.map { it.eventId to it.originalStart }.distinct().size == changes.size) { "Maximal 10.000 Serienausnahmen." }
        return CalendarBackup(calendars.values.toList(), events, changes, categories = categories.values.toList())
    }
    private fun readEvent(c: Component, id: Long, calendarId: Long, defaultZone: ZoneId, parent: Event? = null): Event {
        require(c.props.none { it.name in setOf("RDATE", "EXRULE", "DURATION", "ATTENDEE", "ORGANIZER") }) { "Diese ICS-Funktion wird noch nicht unterstützt." }
        val startProperty = c.one("DTSTART") ?: error("Ungültige ICS-Datei.")
        val day = startProperty.params["VALUE"] == "DATE"
        val start = date(startProperty, defaultZone)
        val zone = start.zone
        val nominal = local(startProperty, zone)
        val endProperty = c.one("DTEND")
        require(endProperty == null || (endProperty.params["VALUE"] == "DATE") == day) { "Ungültige ICS-Datei." }
        val end = endProperty?.let { local(it, zone) }
            ?: if (day) nominal.plusDays(1) else error("ICS-Termine mit Uhrzeit benötigen ein Ende.")
        val reminders = reminders(c, day, parent)
        var event = Event(id = id, title = c.text("SUMMARY", parent?.title ?: ""), start = nominal.toString(), end = end.toString(), zone = zone.id,
            allDay = day, calendarId = calendarId, location = c.text("LOCATION", parent?.location ?: ""), notes = c.text("DESCRIPTION", parent?.notes ?: ""),
            reminderMinutes = reminders.second.firstOrNull() ?: -1, additionalReminders = reminders.second.drop(1), allDayReminderTime = reminders.first)
        c.one("RRULE")?.let { rule ->
            require(parent == null) { "Diese ICS-Funktion wird noch nicht unterstützt." }
            val fields = rule.value.uppercase().split(';').map { part -> part.split('=', limit = 2).also { require(it.size == 2) { "Ungültige ICS-Datei." } } }
            val r = fields.associate { it[0] to it[1] }
            require(r.size == fields.size && r.keys.all { it in setOf("FREQ", "INTERVAL", "UNTIL", "COUNT", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST") }) { "Diese ICS-Wiederholung wird noch nicht unterstützt." }
            val repeat = Repeat.entries.firstOrNull { it != Repeat.NONE && it.name == r["FREQ"] } ?: error("Diese ICS-Wiederholung wird noch nicht unterstützt.")
            require(r["BYDAY"] == null || (repeat == Repeat.WEEKLY && r["BYDAY"] == listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[start.dayOfWeek.value - 1])) { "Diese ICS-Wiederholung wird noch nicht unterstützt." }
            require(r["BYMONTHDAY"] == null || (repeat in listOf(Repeat.MONTHLY, Repeat.YEARLY) && r["BYMONTHDAY"] == start.dayOfMonth.toString())) { "Diese ICS-Wiederholung wird noch nicht unterstützt." }
            require(r["BYMONTH"] == null || (repeat == Repeat.YEARLY && r["BYMONTH"] == start.monthValue.toString())) { "Diese ICS-Wiederholung wird noch nicht unterstützt." }
            val interval = r["INTERVAL"]?.toIntOrNull() ?: if ("INTERVAL" in r) 0 else 1
            require(interval in 1..99 && !("UNTIL" in r && "COUNT" in r)) { "Diese ICS-Wiederholung wird noch nicht unterstützt." }
            var until: LocalDate? = r["UNTIL"]?.let { value ->
                if (value.length == 8) LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE) else {
                    val finish = date(Property("UNTIL", emptyMap(), value), zone).withZoneSameInstant(zone).toLocalDateTime()
                    finish.toLocalDate().minusDays(if (finish.toLocalTime() < nominal.toLocalTime()) 1 else 0)
                }
            }
            r["COUNT"]?.let { value ->
                val count = value.toIntOrNull() ?: 0
                require(count in 1..10000) { "Diese ICS-Wiederholung wird noch nicht unterstützt." }
                var found = 0; var index = 0L
                while (found < count) {
                    require(index * interval <= 240000) { "Serienende: maximal 2100." }
                    val candidate = EventEngine.candidate(nominal, repeat, index++ * interval) ?: continue
                    require(candidate.year <= 2100) { "Serienende: maximal 2100." }
                    found++; until = candidate.toLocalDate()
                }
            }
            event = event.copy(repeat = repeat, interval = interval, until = until?.toString())
        }
        return event.checked()
    }
    private fun reminders(c: Component, allDay: Boolean, parent: Event?): Pair<String, List<Int>> {
        require(c.alarms.size <= 5) { "Maximal fünf Erinnerungen pro Termin." }
        val triggers = c.alarms.map { properties ->
            val alarm = Component(properties.toMutableList())
            require(alarm.one("ACTION")?.value == "DISPLAY" && alarm.one("REPEAT") == null) { "Diese ICS-Funktion wird noch nicht unterstützt." }
            val trigger = alarm.one("TRIGGER") ?: error("Ungültige ICS-Datei.")
            require(trigger.params.keys.all { it == "RELATED" } && trigger.params["RELATED"] in listOf(null, "START")) { "Diese ICS-Funktion wird noch nicht unterstützt." }
            val seconds = Duration.parse(trigger.value).seconds
            require(seconds % 60 == 0L && seconds in -40320L * 60..1439L * 60 && (allDay || seconds <= 0)) { "Diese ICS-Erinnerung wird noch nicht unterstützt." }
            seconds / 60
        }
        val clock = c.one("X-GIMBO-ALLDAY-REMINDER-TIME")?.value ?: if (triggers.any { it > 0 }) LocalTime.MIDNIGHT.plusMinutes(triggers.max()).toString() else parent?.allDayReminderTime ?: "00:00"
        val base = if (allDay) LocalTime.parse(clock).toSecondOfDay() / 60 else 0
        val values = if (triggers.isNotEmpty()) triggers.map { (base - it).toInt() }.distinct() else c.one("X-LUMA-REMINDER")?.value?.toInt()?.let { if (it < 0) emptyList() else listOf(it) } ?: parent?.reminderOffsets.orEmpty()
        require(values.all { it in 0..40320 }) { "Diese ICS-Erinnerung wird noch nicht unterstützt." }
        return clock to values
    }
    private fun originalDate(p: Property, parent: Event): String {
        require((p.params["VALUE"] == "DATE") == parent.allDay) { "Ungültige Serienausnahme." }
        return local(p, ZoneId.of(parent.zone)).toString()
    }
    private fun local(p: Property, zone: ZoneId): LocalDateTime = when {
        p.params["VALUE"] == "DATE" -> LocalDate.parse(p.value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay()
        !p.value.endsWith('Z') && p.params["TZID"] in listOf(null, zone.id) -> LocalDateTime.parse(p.value, basic)
        else -> date(p, zone).withZoneSameInstant(zone).toLocalDateTime()
    }
    private fun date(p: Property, defaultZone: ZoneId): ZonedDateTime {
        require(p.params["VALUE"] in listOf(null, "DATE", "DATE-TIME")) { "Diese ICS-Funktion wird noch nicht unterstützt." }
        val zone = p.params["TZID"]?.let { name -> runCatching { ZoneId.of(name) }.getOrElse { error("Diese ICS-Zeitzone wird noch nicht unterstützt.") } } ?: defaultZone
        return if (p.params["VALUE"] == "DATE") LocalDate.parse(p.value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone)
        else if (p.value.endsWith('Z')) LocalDateTime.parse(p.value.dropLast(1), basic).atZone(ZoneOffset.UTC)
        else LocalDateTime.parse(p.value, basic).atZone(zone)
    }
    private fun property(line: String): Property {
        var quoted = false
        val colon = line.indexOfFirst { char -> if (char == '"') quoted = !quoted; char == ':' && !quoted }
        require(colon > 0) { "Ungültige ICS-Datei." }
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).associate { val pair = it.split('=', limit = 2); require(pair.size == 2) { "Ungültige ICS-Datei." }; pair[0].uppercase() to pair[1].trim('"') }
        require(params.size == head.size - 1) { "Ungültige ICS-Datei." }
        return Property(head.first().uppercase(), params, line.substring(colon + 1))
    }
    private fun unescape(s: String): String {
        val out = StringBuilder(); var i = 0
        while (i < s.length) { val c = s[i++]; if (c == '\\') { require(i < s.length) { "Ungültige ICS-Datei." }; val next = s[i++]; require(next in "nN\\,;") { "Ungültige ICS-Datei." }; out.append(if (next in "nN") '\n' else next) } else out.append(c) }
        return out.toString()
    }
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
    private fun fold(line: String): String {
        val out = StringBuilder(); var bytes = 0
        line.codePoints().forEach { cp -> val s = String(Character.toChars(cp)); val length = s.toByteArray(Charsets.UTF_8).size
            if (bytes + length > 75) { out.append("\r\n "); bytes = 1 }; out.append(s); bytes += length }
        return out.toString()
    }
    fun write(events: List<Event>, calendars: List<UserCalendar>, exceptions: List<EventException>, categories: List<EventCategory> = emptyList()): String {
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Gimbo//Gimbo Kalender//EN", "CALSCALE:GREGORIAN")
        (events.map { it.zone } + exceptions.mapNotNull { it.replacement?.zone }).distinct().forEach { zone -> lines += timezone(ZoneId.of(zone)) }
        fun dates(event: Event, recurrence: String? = null): List<String> {
            fun dateLine(name: String, value: String) = if (event.allDay) "$name;VALUE=DATE:${LocalDateTime.parse(value).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)}"
                else "$name;TZID=${event.zone}:${LocalDateTime.parse(value).format(basic)}"
            return listOfNotNull(recurrence?.let { dateLine("RECURRENCE-ID", it) }, dateLine("DTSTART", event.start), dateLine("DTEND", event.end))
        }
        fun fields(e: Event) = listOf("SUMMARY:${escape(e.title)}", "LOCATION:${escape(e.location)}", "DESCRIPTION:${escape(e.notes)}",
            "X-LUMA-REMINDER:${e.reminderMinutes}", "X-LUMA-CALENDAR-ID:${e.calendarId}", "X-LUMA-CALENDAR-NAME:${escape(calendars.firstOrNull { it.id == e.calendarId }?.name ?: "ICS")}", "X-LUMA-CALENDAR-COLOR:${e.calendarColor(calendars)}", "X-GIMBO-ALLDAY-REMINDER-TIME:${e.allDayReminderTime}") + (categories.firstOrNull { it.id == e.categoryId }?.let { listOf("CATEGORIES:${escape(it.name)}", "X-GIMBO-CATEGORY-COLOR:${it.color}") } ?: emptyList())
        fun alarm(e: Event) = e.reminderOffsets.flatMap { minutes ->
            val offset = (if (e.allDay) LocalTime.parse(e.allDayReminderTime).toSecondOfDay() / 60 else 0) - minutes
            listOf("BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:${if (offset < 0) "-" else ""}PT${kotlin.math.abs(offset)}M", "DESCRIPTION:${escape(e.title)}", "END:VALARM")
        }
        val stamp = "DTSTAMP:${utc.format(Instant.now())}Z"
        events.forEach { e ->
            val uid = "UID:${UUID.randomUUID()}@luma.calendar"
            lines += listOf("BEGIN:VEVENT", uid, stamp) + dates(e) + fields(e)
            if (e.repeat != Repeat.NONE) {
                val until = e.until?.let { day -> if (e.allDay) LocalDate.parse(day).format(DateTimeFormatter.BASIC_ISO_DATE)
                    else utc.format(LocalDate.parse(day).atTime(LocalTime.MAX).atZone(ZoneId.of(e.zone)).toInstant()) + "Z" }
                lines += "RRULE:FREQ=${e.repeat.name};INTERVAL=${e.interval}" + (until?.let { ";UNTIL=$it" } ?: "")
            }
            lines += alarm(e); lines += "END:VEVENT"
            exceptions.filter { it.eventId == e.id }.forEach { change ->
                val replacement = change.replacement ?: e.futureDraft(change.originalStart).copy(repeat = Repeat.NONE, interval = 1, until = null, reminderMinutes = -1)
                lines += listOf("BEGIN:VEVENT", uid, stamp) + dates(replacement, change.originalStart.let { original ->

                    original
                }).drop(1)
                lines += if (e.allDay) "RECURRENCE-ID;VALUE=DATE:${LocalDateTime.parse(change.originalStart).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)}"
                    else "RECURRENCE-ID;TZID=${e.zone}:${LocalDateTime.parse(change.originalStart).format(basic)}"
                if (change.replacement == null) lines += "STATUS:CANCELLED" else { lines += fields(replacement); lines += alarm(replacement) }
                lines += "END:VEVENT"
            }
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n", postfix = "\r\n", transform = ::fold)
    }
    private fun timezone(zone: ZoneId): List<String> {
        val rules = zone.rules
        val out = mutableListOf("BEGIN:VTIMEZONE", "TZID:${zone.id}")
        if (rules.isFixedOffset) {
            val offset = offset(rules.getOffset(Instant.EPOCH))
            out += listOf("BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:$offset", "TZOFFSETTO:$offset", "END:STANDARD")
        } else {
            val transitions = mutableListOf<java.time.zone.ZoneOffsetTransition>()
            var cursor = LocalDate.of(1899, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant()
            val end = LocalDate.of(2102, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
            while (true) { val transition = rules.nextTransition(cursor) ?: break; if (transition.instant >= end) break; transitions += transition; cursor = transition.instant.plusSeconds(1) }
            transitions.groupBy { Triple(it.offsetBefore, it.offsetAfter, rules.isDaylightSavings(it.instant)) }.forEach { (key, group) ->
                val type = if (key.third) "DAYLIGHT" else "STANDARD"
                out += listOf("BEGIN:$type", "DTSTART:${group.first().dateTimeBefore.format(basic)}", "TZOFFSETFROM:${offset(key.first)}", "TZOFFSETTO:${offset(key.second)}")
                if (group.size > 1) out += "RDATE:" + group.drop(1).joinToString(",") { it.dateTimeBefore.format(basic) }
                out += "END:$type"
            }
        }
        out += "END:VTIMEZONE"; return out
    }
    private fun offset(value: ZoneOffset): String = (if (value.totalSeconds < 0) "-" else "+") + kotlin.math.abs(value.totalSeconds).let { seconds -> "%02d%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds % 3600 / 60) + if (seconds % 60 != 0) "%02d".format(java.util.Locale.ROOT, seconds % 60) else "" }
}
