package de.luma.calendar.data

import androidx.room.withTransaction
import de.luma.calendar.model.*

class EventRepository(private val database: CalendarDatabase) {
    suspend fun save(event: Event): Long = database.withTransaction {
        val value = event.checked()
        require(value.categoryId == null || database.categories().find(value.categoryId) != null) { "Diese Kategorie wurde entfernt." }
        require(database.calendars().find(value.calendarId) != null) { "Bitte einen vorhandenen Kalender wählen." }
        if (value.id == 0L) database.events().insert(value) else {
            val old = database.events().find(value.id) ?: error("Dieser Termin wurde bereits entfernt.")
            if (old.recurrenceChanged(value)) database.exceptions().deleteForEvent(value.id)
            check(database.events().update(value) == 1)
            if (value.allDay) database.integrations().removeTravel(value.id)
            value.id
        }
    }
    suspend fun exception(parent: Event, originalStart: String, replacement: Event?) = database.withTransaction {
        val current = database.events().find(parent.id) ?: error("Dieser Termin wurde bereits entfernt.")
        require(!parent.recurrenceChanged(current)) { "Die Serie wurde geändert. Bitte den Termin erneut öffnen." }
        val value = EventException(parent.id, originalStart, replacement).checked(current)
        require(value.calendarId == null || database.calendars().find(value.calendarId) != null) { "Bitte einen vorhandenen Kalender wählen." }
        require(value.replacement?.categoryId == null || database.categories().find(value.replacement.categoryId) != null) { "Diese Kategorie wurde entfernt." }
        val existing = database.exceptions().all()
        require(existing.size < 10000 || existing.any { it.eventId == value.eventId && it.originalStart == originalStart }) { "Maximal 10.000 Serienausnahmen." }
        database.exceptions().save(value)
    }

    suspend fun saveFuture(parent: Event, originalStart: String, event: Event): Long = database.withTransaction {
        val current = database.events().find(parent.id) ?: error("Dieser Termin wurde bereits entfernt.")
        require(current == parent) { "Die Serie wurde geändert. Bitte den Termin erneut öffnen." }
        val cutoff = java.time.LocalDateTime.parse(originalStart)
        require(EventEngine.isOccurrence(current, cutoff)) { "Ungültige Serienausnahme." }
        val value = event.copy(id = 0).checked()
        require(value.categoryId == null || database.categories().find(value.categoryId) != null) { "Diese Kategorie wurde entfernt." }
        require(database.calendars().find(value.calendarId) != null) { "Bitte einen vorhandenen Kalender wählen." }
        val future = current.futureDraft(originalStart)
        val changes = database.exceptions().forEvent(current.id).filter { java.time.LocalDateTime.parse(it.originalStart) >= cutoff }
        val keep = !future.recurrenceChanged(value)
        changes.forEach { database.exceptions().delete(current.id, it.originalStart) }
        val id = if (cutoff == java.time.LocalDateTime.parse(current.start)) {
            check(database.events().update(value.copy(id = current.id)) == 1)
            current.id
        } else {
            check(database.events().update(current.copy(until = cutoff.toLocalDate().minusDays(1).toString()).checked()) == 1)
            database.events().insert(value)
        }
        val travel = database.integrations().travel().firstOrNull { it.eventId == current.id }
        if (value.allDay) { if (id == current.id) database.integrations().removeTravel(id) } else if (travel != null && id != current.id) database.integrations().travel(travel.copy(eventId = id))
        if (keep) changes.forEach { change ->
            database.exceptions().save(change.copy(eventId = id, replacement = change.replacement?.copy(id = id)).checked(value.copy(id = id)))
        }
        id
    }
    suspend fun restore(id: Long, originalStart: String) = database.withTransaction {
        require(database.events().find(id) != null) { "Dieser Termin wurde bereits entfernt." }
        database.exceptions().delete(id, originalStart)
    }
}
