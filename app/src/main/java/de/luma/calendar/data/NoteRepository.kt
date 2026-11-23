package de.luma.calendar.data

import androidx.room.withTransaction
import de.luma.calendar.model.*

class NoteRepository(private val db: CalendarDatabase) {
    suspend fun save(note: CalendarNote): Long = db.withTransaction {
        val value = note.copy(updatedAt = maxOf(System.currentTimeMillis(), note.createdAt)).checked()
        require(db.calendars().find(value.calendarId) != null) { "Bitte einen vorhandenen Kalender wählen." }
        if (value.id == 0L) {
            require(db.notes().all().size < CalendarNote.MAX_COUNT) { "Maximal 2.000 Notizen." }
            db.notes().insert(value)
        } else {
            check(db.notes().update(value) == 1) { "Diese Notiz wurde bereits entfernt." }
            value.id
        }
    }
    suspend fun toggleItem(id: Long, itemId: String): Unit = db.withTransaction {
        val note = db.notes().find(id) ?: error("Diese Notiz wurde bereits entfernt.")
        require(note.items.any { it.id == itemId }) { "Ungültige Checkliste." }
        save(note.copy(items = note.items.map { if (it.id == itemId) it.copy(checked = !it.checked) else it }))
        Unit
    }
    suspend fun pin(id: Long): Unit = db.withTransaction { val note = db.notes().find(id) ?: error("Diese Notiz wurde bereits entfernt."); save(note.copy(pinned = !note.pinned)); Unit }
    suspend fun archive(id: Long): Unit = db.withTransaction { val note = db.notes().find(id) ?: error("Diese Notiz wurde bereits entfernt."); save(note.copy(archived = !note.archived)); Unit }
    suspend fun remove(id: Long) { db.notes().delete(id) }
}
