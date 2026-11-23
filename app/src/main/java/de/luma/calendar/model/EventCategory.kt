package de.luma.calendar.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "event_categories")
data class EventCategory(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val color: Long = 0xFF9DCBEA) : java.io.Serializable {
    fun checked(): EventCategory {
        require(id >= 0 && name.trim().length in 1..40) { "Kategoriename: 1 bis 40 Zeichen." }
        require(color in 0xFF000000..0xFFFFFFFF) { "Ungültige Kategorienfarbe." }
        return copy(name = name.trim())
    }
}
