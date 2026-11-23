package de.luma.calendar.data

import androidx.room.withTransaction
import de.luma.calendar.model.*
import kotlinx.coroutines.flow.map

data class CalendarSnapshot(val events: List<Event>, val calendars: List<UserCalendar>, val exceptions: List<EventException>, val tasks: List<CalendarTask>, val templates: List<EventTemplate>, val attachments: List<EventAttachment> = emptyList(), val travel: List<TravelPlan> = emptyList(), val categories: List<EventCategory> = emptyList(), val notes: List<CalendarNote> = emptyList())

fun CalendarDatabase.observeSnapshot() = invalidationTracker.createFlow("events", "calendars", "event_exceptions", "tasks", "templates", "attachments", "travel_plans", "event_categories", "notes").map {
    withTransaction { CalendarSnapshot(events().all(), calendars().all(), exceptions().all(), tasks().all(), templates().all(), integrations().attachments(), integrations().travel(), categories().all(), notes().all()) }
}
