package de.luma.calendar.integrations

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import de.luma.calendar.widget.CalendarWidgets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DeviceCalendarConnections(private val context: Context, private val scope: CoroutineScope,
    private val updateWidgets: suspend () -> Unit = { CalendarWidgets.update(context) }) {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private var registered = false
    private var debounce: Job? = null
    private var foreground: Job? = null
    @Synchronized fun foreground(active: Boolean) {
        foreground?.cancel(); foreground = null
        if (active) foreground = scope.launch {
            while (isActive) { delay(30000); if (DeviceCalendarLinksStore(context).read().isNotEmpty()) refresh() }
        }
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { schedule() }
    }
    fun connect(calendar: DeviceCalendar) {
        check(DeviceCalendarSources.allowed(context)) { "Kalenderfreigabe fehlt" }
        val current = DeviceCalendars.list(context.contentResolver).firstOrNull { it.identity == calendar.identity }
            ?: error("Quelle nicht mehr verfügbar")
        DeviceCalendarLinksStore(context).connect(current)
        refresh()
        SharedCalendars.configureBackground(context)
    }
    fun disconnect(id: Long) { DeviceCalendarLinksStore(context).disconnect(id); refresh(); SharedCalendars.configureBackground(context) }
    fun refresh() {
        reconcileObserver()
        changes.update { it + 1 }
        scope.launch { refreshWidgets() }
    }
    @Synchronized private fun reconcileObserver() {
        val needed = DeviceCalendarSources.allowed(context) && DeviceCalendarLinksStore(context).read().isNotEmpty()
        if (needed && !registered) {
            try { context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer); registered = true }
            catch (_: SecurityException) { registered = false }
        } else if (!needed && registered) { context.contentResolver.unregisterContentObserver(observer); registered = false }
    }
    @Synchronized private fun schedule() {
        if (!registered) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(300)
            reconcileObserver()
            changes.update { it + 1 }
            refreshWidgets()
        }
    }
    private suspend fun refreshWidgets() {
        try { updateWidgets() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { android.util.Log.e("GimboCalendar", "Cannot refresh connected calendar widgets", error) }
    }
    @Synchronized fun close() {
        debounce?.cancel()
        foreground?.cancel()
        if (registered) context.contentResolver.unregisterContentObserver(observer)
        registered = false
    }
}
