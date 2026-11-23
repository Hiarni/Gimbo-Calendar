package de.luma.calendar.integrations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import de.luma.calendar.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

enum class SourceStatus(val label: String) {
    READY("Verbunden · Kontokalender"), NO_PERMISSION("Kalenderfreigabe fehlt"),
    MISSING("Quelle nicht mehr verfügbar"), ERROR("Quelle konnte nicht gelesen werden")
}
data class DeviceCalendarSource(val calendar: DeviceCalendar, val status: SourceStatus)
data class DeviceSourceSnapshot(
    val sources: List<DeviceCalendarSource> = emptyList(), val events: List<Event> = emptyList(),
    val providerIds: Map<Long, Long> = emptyMap(), val from: LocalDate = LocalDate.now().minusYears(1),
    val until: LocalDate = LocalDate.now().plusYears(1),
) {
    val calendars get() = sources.map { it.calendar.displayCalendar() }
    val unavailable get() = sources.any { it.status != SourceStatus.READY }
}

class DeviceCalendarLinksStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("device-calendar-links", Context.MODE_PRIVATE)
    fun read(): List<DeviceCalendar> = runCatching {
        val array = JSONArray(prefs.getString("sources", "[]"))
        require(array.length() <= MAX_SOURCES)
        (0 until array.length()).map { i -> val c = array.getJSONObject(i)
            DeviceCalendar(c.getLong("id"), c.getString("name"), c.getString("account"), c.getLong("color"), c.getString("accountType")).also {
                require(it.id >= 0 && it.name.isNotBlank() && it.name.length <= 60 && it.account.length <= 512 && it.accountType.length <= 512 && it.color in 0xFF000000..0xFFFFFFFF)
            }
        }.also { require(it.map(DeviceCalendar::displayId).distinct().size == it.size) }
    }.getOrDefault(emptyList())
    fun connect(calendar: DeviceCalendar) = synchronized(lock) {
        require(calendar.id >= 0 && calendar.name.isNotBlank() && calendar.account.length <= 512 && calendar.accountType.length <= 512 && calendar.color in 0xFF000000..0xFFFFFFFF)
        val links = read()
        if (links.any { it.identity == calendar.identity }) return@synchronized
        require(links.size < MAX_SOURCES) { "Maximal 10 verbundene Gerätekalender." }
        require(links.none { it.displayId == calendar.displayId })
        write(links + calendar.copy(name = calendar.name.take(60)))
    }
    fun disconnect(id: Long) = synchronized(lock) { write(read().filterNot { it.displayId == id }) }
    private fun write(calendars: List<DeviceCalendar>) {
        val array = JSONArray()
        calendars.forEach { c -> array.put(JSONObject().put("id", c.id).put("name", c.name).put("account", c.account).put("color", c.color).put("accountType", c.accountType)) }
        check(prefs.edit().putString("sources", array.toString()).commit())
    }
    companion object { const val MAX_SOURCES = 10; private val lock = Any() }
}

object DeviceCalendarSources {
    fun allowed(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun read(context: Context, from: LocalDate, until: LocalDate): DeviceSourceSnapshot {
        require(until > from && until <= from.plusYears(2))
        val links = DeviceCalendarLinksStore(context).read()
        fun unavailable(status: SourceStatus) = DeviceSourceSnapshot(links.map { DeviceCalendarSource(it, status) }, from = from, until = until)
        if (links.isEmpty()) return DeviceSourceSnapshot(from = from, until = until)
        if (!allowed(context)) return unavailable(SourceStatus.NO_PERMISSION)
        val available = try { DeviceCalendars.list(context.contentResolver) }
            catch (_: SecurityException) { return unavailable(SourceStatus.NO_PERMISSION) }
            catch (_: Exception) { return unavailable(SourceStatus.ERROR) }
        val events = mutableListOf<Event>(); val origins = mutableMapOf<Long, Long>()
        val sources = links.map { link ->
            val current = available.firstOrNull { it.identity == link.identity }
            if (current == null) DeviceCalendarSource(link, SourceStatus.MISSING)
            else try {
                val values = DeviceCalendars.connected(context.contentResolver, current, from, until)

                require(events.size + values.size <= 10000) { "Maximal 10.000 verbundene Vorkommen." }
                require(values.none { it.first.id in origins })
                events += values.map { it.first }; values.forEach { origins[it.first.id] = it.second }
                DeviceCalendarSource(current, SourceStatus.READY)
            } catch (_: SecurityException) { DeviceCalendarSource(current, SourceStatus.NO_PERMISSION) }
            catch (_: Exception) { DeviceCalendarSource(current, SourceStatus.ERROR) }
        }

        if (!allowed(context) || sources.any { it.status == SourceStatus.NO_PERMISSION }) return unavailable(SourceStatus.NO_PERMISSION)
        return DeviceSourceSnapshot(sources, events, origins, from, until)
    }
}
