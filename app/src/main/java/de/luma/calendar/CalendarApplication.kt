package de.luma.calendar

import android.app.Application
import de.luma.calendar.data.CalendarDatabase
import de.luma.calendar.data.observeSnapshot
import de.luma.calendar.reminders.ReminderScheduler
import de.luma.calendar.widget.CalendarWidgets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class CalendarApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration get() = androidx.work.Configuration.Builder().build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val deviceConnections by lazy { de.luma.calendar.integrations.DeviceCalendarConnections(this, scope) }
    override fun onCreate() {
        super.onCreate()
        deviceConnections.refresh()
        scope.launch {
            try { de.luma.calendar.integrations.AttachmentStore(this@CalendarApplication).collectGarbage() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.e("GimboCalendar", "Cannot clean unreferenced attachment copies", error) }
            CalendarDatabase.get(this@CalendarApplication).observeSnapshot().map { it.copy(notes = emptyList()) }.distinctUntilChanged()
                .catch { android.util.Log.e("GimboCalendar", "Cannot observe local events", it) }
                .collectLatest {
                    try {
                        ReminderScheduler.rebuild(this@CalendarApplication)
                        de.luma.calendar.reminders.ExtraReminderScheduler.rebuild(this@CalendarApplication)
                        CalendarWidgets.update(this@CalendarApplication)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { android.util.Log.e("GimboCalendar", "Cannot refresh reminders/widgets", error) }
                }
        }
    }
    override fun onTerminate() { deviceConnections.close(); scope.cancel(); super.onTerminate() }
}
