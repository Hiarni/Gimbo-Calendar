package de.luma.calendar.data

import android.content.Context
import de.luma.calendar.model.*
import de.luma.calendar.i18n.*

class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("calendar-settings", Context.MODE_PRIVATE)
    fun storedLanguage() = AppLanguage.entries.firstOrNull { it.name == prefs.getString("language", "SYSTEM") } ?: AppLanguage.SYSTEM
    fun read() = AppSettings(
        theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", "SYSTEM") } ?: ThemeMode.SYSTEM,
        accent = prefs.getLong("accent", 0xFFB9A7FF),
        monthStyle = MonthStyle.entries.firstOrNull { it.name == prefs.getString("month-style", "DETAILED") } ?: MonthStyle.DETAILED,
        highlightHolidays = prefs.getBoolean("highlight-holidays", true),
        highlightWeekends = prefs.getBoolean("highlight-weekends", true),
        eventCounts = prefs.getBoolean("event-counts", true),
        mondayFirst = prefs.getBoolean("monday", true),
        weekNumbers = prefs.getBoolean("weeks", true),
        startView = CalendarView.entries.firstOrNull { it.name == prefs.getString("view", "MONTH") } ?: CalendarView.MONTH,
        language = AppLocale.language(context),
        schoolHolidays = prefs.getBoolean("school", false),
        schoolCountry = prefs.getString("school-country", AppSettings().schoolCountry) ?: "DE",
        schoolRegion = prefs.getString("school-region", "") ?: "",
        schoolGroup = prefs.getString("school-group", "") ?: "",
        holidays = prefs.getBoolean("holidays", false),
        holidayCountry = prefs.getString("holiday-country", AppSettings().holidayCountry) ?: "DE",
        holidayRegion = prefs.getString("holiday-region", "") ?: "",
        importedHolidays = prefs.getBoolean("holiday-import", true),
        defaultCalendarId = prefs.getLong("default-calendar", 1),
    )
    fun write(settings: AppSettings) {
        prefs.edit().putString("month-style", settings.monthStyle.name).putBoolean("highlight-holidays", settings.highlightHolidays).putBoolean("highlight-weekends", settings.highlightWeekends).putBoolean("event-counts", settings.eventCounts).putBoolean("school", settings.schoolHolidays).putString("school-country", settings.schoolCountry).putString("school-region", settings.schoolRegion).putString("school-group", settings.schoolGroup).putString("theme", settings.theme.name).putLong("accent", settings.accent)
            .putBoolean("monday", settings.mondayFirst).putBoolean("weeks", settings.weekNumbers)
            .putString("view", settings.startView.name).putString("language", settings.language.name)
            .putBoolean("holidays", settings.holidays).putString("holiday-country", settings.holidayCountry)
            .putString("holiday-region", settings.holidayRegion).putBoolean("holiday-import", settings.importedHolidays).putLong("default-calendar", settings.defaultCalendarId).apply()
    }
}
