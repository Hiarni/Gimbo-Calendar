package de.luma.calendar.widget

import android.content.Context
import de.luma.calendar.model.*
import org.json.JSONArray
import org.json.JSONObject

enum class WidgetLayout(val label: String) { DEFAULT("Automatisch"), AGENDA("Agenda"), MONTH("Monat"), MONTH_AGENDA("Monat und Agenda"), WEEK("Woche"), DAY("Tag") }
enum class WidgetPalette(val label: String) { SYSTEM("Wie die App"), LIGHT("Hell"), DARK("Dunkel") }
enum class WidgetInk(val label: String) { AUTO("Automatisch"), LIGHT("Helle Schrift"), DARK("Dunkle Schrift") }
enum class WidgetDensity(val label: String) { COMFORTABLE("Komfortabel"), COMPACT("Kompakt") }

data class WidgetSettings(
    val calendars: Set<Long>? = null, val holidays: Boolean = true, val opacity: Int = 100, val entries: Int = 5,
    val layout: WidgetLayout = WidgetLayout.DEFAULT, val palette: WidgetPalette = WidgetPalette.SYSTEM,
    val ink: WidgetInk = WidgetInk.AUTO, val density: WidgetDensity = WidgetDensity.COMFORTABLE,
    val textScale: Int = 100, val corners: Int = 24, val horizon: Int = 90, val tasks: Boolean = false,
    val calendarNames: Boolean = true, val locations: Boolean = false, val header: Boolean = true, val actions: Boolean = true,
) : java.io.Serializable {
    fun includesEvent(event: Event) = if (event.category == Category.HOLIDAY) holidays else calendars == null || event.calendarId in calendars
    fun includes(occurrence: Occurrence) = includesEvent(occurrence.event)
    fun checked(): WidgetSettings {
        require(opacity in 0..100 && entries in 1..15 && textScale in 80..140 && corners in 0..32 && horizon in 1..90)
        require(calendars == null || calendars.size <= 110 && calendars.all { it != 0L })
        return this
    }
}
class WidgetSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("widget-settings", Context.MODE_PRIVATE)
    fun read(id: Int): WidgetSettings = runCatching {
        val r = JSONObject(preferences.getString(id.toString(), null) ?: return WidgetSettings())
        val array = if (r.isNull("calendars")) null else r.getJSONArray("calendars")
        WidgetSettings(
            calendars = array?.let { (0 until it.length()).map(it::getLong).toSet() },
            holidays = r.optBoolean("holidays", true), opacity = r.optInt("opacity", 100), entries = r.optInt("entries", 5),
            layout = WidgetLayout.valueOf(r.optString("layout", "DEFAULT")), palette = WidgetPalette.valueOf(r.optString("palette", "SYSTEM")),
            ink = WidgetInk.valueOf(r.optString("ink", "AUTO")), density = WidgetDensity.valueOf(r.optString("density", "COMFORTABLE")),
            textScale = r.optInt("textScale", 100), corners = r.optInt("corners", 24), horizon = r.optInt("horizon", 90),
            tasks = r.optBoolean("tasks", false), calendarNames = r.optBoolean("calendarNames", true), locations = r.optBoolean("locations", false),
            header = r.optBoolean("header", true), actions = r.optBoolean("actions", true),
        ).checked()
    }.getOrDefault(WidgetSettings())
    fun write(id: Int, value: WidgetSettings) {
        require(id > 0); value.checked()
        val json = JSONObject().put("calendars", value.calendars?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
            .put("holidays", value.holidays).put("opacity", value.opacity).put("entries", value.entries)
            .put("layout", value.layout.name).put("palette", value.palette.name).put("ink", value.ink.name).put("density", value.density.name)
            .put("textScale", value.textScale).put("corners", value.corners).put("horizon", value.horizon).put("tasks", value.tasks)
            .put("calendarNames", value.calendarNames).put("locations", value.locations).put("header", value.header).put("actions", value.actions)
        check(preferences.edit().putString(id.toString(), json.toString()).commit())
    }
    fun remove(id: Int) { preferences.edit().remove(id.toString()).apply() }
}
