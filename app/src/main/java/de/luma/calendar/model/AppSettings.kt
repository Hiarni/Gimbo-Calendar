package de.luma.calendar.model

import de.luma.calendar.i18n.AppLanguage
import de.luma.calendar.i18n.AppLocale

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Hell"), DARK("Dunkel") }
enum class CalendarView(val label: String) { MONTH("Monat"), WEEK("Woche"), DAY("Tag"), AGENDA("Agenda") }
enum class MonthStyle(val label: String) { DETAILED("Titel und Farben"), COMPACT("Markierungen") }
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Long = 0xFFB9A7FF,
    val monthStyle: MonthStyle = MonthStyle.DETAILED,
    val highlightHolidays: Boolean = true,
    val highlightWeekends: Boolean = true,
    val eventCounts: Boolean = true,
    val mondayFirst: Boolean = true,
    val weekNumbers: Boolean = true,
    val startView: CalendarView = CalendarView.MONTH,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val schoolHolidays: Boolean = false,
    val schoolCountry: String = AppLocale.systemLocale().country.takeIf { it.length == 2 } ?: "DE",
    val schoolRegion: String = "",
    val schoolGroup: String = "",
    val holidays: Boolean = false,
    val holidayCountry: String = AppLocale.systemLocale().country.takeIf { it.length == 2 } ?: "DE",
    val holidayRegion: String = "",
    val importedHolidays: Boolean = true,
    val defaultCalendarId: Long = 1,
)
