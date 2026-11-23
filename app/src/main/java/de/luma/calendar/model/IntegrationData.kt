package de.luma.calendar.model

import androidx.room.*

@Entity(tableName = "attachments", foreignKeys = [ForeignKey(entity = Event::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)], indices = [Index("eventId")])
data class EventAttachment(@PrimaryKey val fileId: String, val eventId: Long, val name: String, val mime: String, val bytes: Long)
@Entity(tableName = "travel_plans", foreignKeys = [ForeignKey(entity = Event::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)])
data class TravelPlan(@PrimaryKey val eventId: Long, val minutes: Int, val buffer: Int = 0) {
    fun checked() = also { require(eventId > 0 && minutes in 1..1440 && buffer in 0..1440 && minutes + buffer <= 1440) { "Fahrzeit und Puffer: zusammen 1 bis 1440 Minuten." } }
}
@Entity(tableName = "task_origins", foreignKeys = [ForeignKey(entity = CalendarTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["taskId"], unique = true)])
data class TaskOrigin(@PrimaryKey val uid: String, val taskId: Long, val baseline: String)
