package de.luma.calendar.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import de.luma.calendar.model.*
import de.luma.calendar.i18n.*

val LocalCategories = compositionLocalOf<List<EventCategory>> { emptyList() }
@Composable fun Event.presentationColor(): Long = LocalCategories.current.firstOrNull { it.id == categoryId }?.color ?: calendarColor(LocalCalendars.current)
val LocalCalendars = compositionLocalOf<List<UserCalendar>> { UserCalendar.defaults() }
@Composable fun Event.calendarDisplayName(): String {
    val t = texts()
    return calendarLabel(LocalCalendars.current) { t(it) }
}
