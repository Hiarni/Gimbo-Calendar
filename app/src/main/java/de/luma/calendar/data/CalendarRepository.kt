package de.luma.calendar.data

import androidx.room.withTransaction
import de.luma.calendar.model.*

class CalendarRepository(private val database: CalendarDatabase) {
    suspend fun save(calendar: UserCalendar, translate: (String) -> String = { it }): Long = database.withTransaction {
        val checked = calendar.checked()
        val existing = database.calendars().all()
        require(existing.none { it.id != checked.id && it.displayName(translate).equals(checked.displayName(translate), true) }) { "Dieser Kalendername wird bereits verwendet." }
        if (checked.id == 0L) {
            require(existing.size < UserCalendar.MAX_COUNT) { "Maximal 100 Kalender." }
            database.calendars().insert(checked)
        } else {
            check(database.calendars().update(checked) == 1) { "Dieser Kalender wurde bereits entfernt." }
            checked.id
        }
    }
    suspend fun removeAndMove(source: Long, target: Long): Int = database.withTransaction {
        require(source != target && database.calendars().find(source) != null && database.calendars().find(target) != null) { "Bitte einen vorhandenen Kalender wählen." }
        require(database.calendars().all().size > 1) { "Mindestens ein Kalender muss erhalten bleiben." }
        database.templates().all().filter { it.calendarId == source }.forEach { template -> database.templates().update(template.copy(calendarId = target, event = template.event.copy(calendarId = target))) }
        database.tasks().moveCalendar(source, target)
        database.notes().moveCalendar(source, target)
        val moved = database.events().moveCalendar(source, target)
        database.exceptions().forCalendar(source).forEach { change ->
            database.exceptions().save(change.copy(calendarId = target, replacement = change.replacement!!.copy(calendarId = target)))
        }
        check(database.calendars().delete(source) == 1) { "Dieser Kalender wurde bereits entfernt." }
        moved
    }
    suspend fun import(backup: CalendarBackup, onEvents: suspend (Map<Long, Long>) -> Unit = {}, translate: (String) -> String = { it }): Int = database.withTransaction {
        val existing = database.calendars().all().toMutableList()
        val ids = mutableMapOf<Long, Long>()
        backup.calendars.forEach { original ->
            val source = original.checked()
            val match = existing.firstOrNull {
                it.color == source.color && it.defaultCategory == source.defaultCategory &&
                    (source.defaultCategory != null || it.name.equals(source.name, true))
            }
            if (match != null) ids[source.id] = match.id else {
                var candidate = source.copy(id = 0)
                var suffix = 2
                var reused: UserCalendar? = null
                while (true) {
                    val conflict = existing.firstOrNull { it.displayName(translate).equals(candidate.displayName(translate), true) } ?: break
                    if (conflict.color == candidate.color && conflict.defaultCategory == candidate.defaultCategory) {
                        reused = conflict; break
                    }
                    candidate = source.copy(id = 0, name = source.displayName(translate).take(45) + " (Import ${suffix++})", defaultCategory = null)
                }
                if (reused != null) ids[source.id] = reused.id else {
                    require(existing.size < UserCalendar.MAX_COUNT) { "Maximal 100 Kalender." }
                    val id = database.calendars().insert(candidate.checked())
                    existing += candidate.copy(id = id); ids[source.id] = id
                }
            }
        }
        val categoryIds = mutableMapOf<Long, Long>()
        val categories = database.categories().all().toMutableList()
        backup.categories.forEach { source ->
            var name = source.name
            var suffix = 2
            while (categories.any { it.name.equals(name, true) && it.color != source.color }) name = source.name.take(26) + " (Import ${suffix++})"
            val match = categories.firstOrNull { it.name.equals(name, true) && it.color == source.color }
            val id = if (match != null) match.id else {
                require(categories.size < 100) { "Maximal 100 Kategorien." }
                val value = source.copy(id = 0, name = name).checked()
                database.categories().insert(value).also { categories += value.copy(id = it) }
            }
            categoryIds[source.id] = id
        }
        fun category(event: Event) = event.copy(categoryId = event.categoryId?.let { categoryIds[it] ?: error("Ungültige Kategoriezuordnung.") })
        val eventIds = mutableMapOf<Long, Long>()
        backup.events.forEach { event ->
            val target = ids[event.calendarId] ?: error("Bitte einen vorhandenen Kalender wählen.")
            val id = database.events().insert(category(event).copy(id = 0, calendarId = target).checked())
            eventIds[event.id] = id
        }
        require(database.exceptions().all().size + backup.exceptions.size <= 10000) { "Maximal 10.000 Serienausnahmen." }
        backup.exceptions.forEach { change ->
            val id = eventIds[change.eventId] ?: error("Ungültige Serienausnahme.")
            val replacement = change.replacement?.let { category(it).copy(id = id, calendarId = ids[it.calendarId] ?: error("Ungültige Serienausnahme.")) }
            val parent = database.events().find(id) ?: error("Ungültige Serienausnahme.")
            database.exceptions().save(EventException(id, change.originalStart, replacement).checked(parent))
        }
        require(database.tasks().all().size + backup.tasks.size <= 10000) { "Maximal 10.000 Aufgaben." }
        backup.tasks.forEach { task -> database.tasks().insert(task.copy(id = 0, calendarId = ids[task.calendarId] ?: error("Bitte einen vorhandenen Kalender wählen.")).checked()) }
        require(database.templates().all().size + backup.templates.size <= 1000) { "Maximal 1.000 Vorlagen." }
        backup.templates.forEach { template -> val id = ids[template.calendarId] ?: error("Bitte einen vorhandenen Kalender wählen.")
            database.templates().insert(template.copy(id = 0, calendarId = id, event = category(template.event).copy(id = 0, calendarId = id)).checked()) }
        backup.travel.forEach { plan -> database.integrations().travel(plan.copy(eventId = eventIds[plan.eventId] ?: error("Ungültige Abfahrtserinnerung.")).checked()) }
        require(database.notes().all().size + backup.notes.size <= CalendarNote.MAX_COUNT) { "Maximal 2.000 Notizen." }
        backup.notes.forEach { note -> database.notes().insert(note.copy(id = 0, calendarId = ids[note.calendarId] ?: error("Bitte einen vorhandenen Kalender wählen.")).checked()) }
        onEvents(eventIds)
        backup.events.size
    }
}
