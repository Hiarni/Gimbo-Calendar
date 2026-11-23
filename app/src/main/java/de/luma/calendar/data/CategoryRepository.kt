package de.luma.calendar.data

import androidx.room.withTransaction
import de.luma.calendar.model.EventCategory

class CategoryRepository(private val database: CalendarDatabase) {
    suspend fun save(category: EventCategory): Long = database.withTransaction {
        val value = category.checked()
        val existing = database.categories().all()
        require(existing.none { it.id != value.id && it.name.equals(value.name, true) }) { "Diese Kategorie gibt es bereits." }
        if (value.id == 0L) {
            require(existing.size < 100) { "Maximal 100 Kategorien." }
            database.categories().insert(value)
        } else {
            check(database.categories().update(value) == 1) { "Diese Kategorie wurde entfernt." }
            value.id
        }
    }
    suspend fun remove(id: Long) = database.withTransaction {
        database.exceptions().all().filter { it.replacement?.categoryId == id }.forEach { database.exceptions().save(it.copy(replacement = it.replacement!!.copy(categoryId = null))) }
        database.templates().all().filter { it.event.categoryId == id }.forEach { database.templates().update(it.copy(event = it.event.copy(categoryId = null))) }
        database.categories().delete(id)
    }
}
