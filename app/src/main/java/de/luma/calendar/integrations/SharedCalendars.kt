package de.luma.calendar.integrations

import android.accounts.Account
import android.content.*
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.net.Uri
import androidx.work.*
import de.luma.calendar.CalendarApplication
import de.luma.calendar.widget.CalendarWidgets
import java.util.concurrent.TimeUnit

const val CONNECTION_WORK = "gimbo-calendar-connections"
enum class CalendarPurpose(val label: String) { PERSONAL("Persönlich"), PARTNER("Partner"), FAMILY("Familie"), TEAM("Team / Betrieb") }
enum class CalendarAccess(val label: String) { OWNER("Verwalten und bearbeiten"), EDITOR("Bearbeiten"), READ_ONLY("Nur lesen"), UNKNOWN("Rechte beim Anbieter prüfen") }
enum class SyncRequest { REQUESTED, NO_ADAPTER, UNAVAILABLE }
data class CalendarCapabilities(val access: CalendarAccess = CalendarAccess.UNKNOWN, val syncEnabled: Boolean = false)
class SharedCalendarStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shared-calendar-settings", Context.MODE_PRIVATE)
    fun purpose(calendar: DeviceCalendar) = runCatching { CalendarPurpose.valueOf(prefs.getString("purpose/${calendar.identity}", "PERSONAL")!!) }.getOrDefault(CalendarPurpose.PERSONAL)
    fun purpose(calendar: DeviceCalendar, value: CalendarPurpose) { prefs.edit().putString("purpose/${calendar.identity}", value.name).apply() }
    var background: Boolean
        get() = prefs.getBoolean("background", false)
        set(value) { check(prefs.edit().putBoolean("background", value).commit()) }
}
object SharedCalendars {
    fun access(level: Int): CalendarAccess = when {
        level >= CalendarContract.Calendars.CAL_ACCESS_OWNER -> CalendarAccess.OWNER
        level >= CalendarContract.Calendars.CAL_ACCESS_EDITOR -> CalendarAccess.EDITOR
        level >= CalendarContract.Calendars.CAL_ACCESS_READ -> CalendarAccess.READ_ONLY
        else -> CalendarAccess.UNKNOWN
    }
    fun capabilities(context: Context, calendar: DeviceCalendar): CalendarCapabilities {
        if (!DeviceCalendarSources.allowed(context)) return CalendarCapabilities()
        return runCatching {
            require(DeviceCalendars.list(context.contentResolver).any { it.identity == calendar.identity })
            val fields = arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.SYNC_EVENTS)
            context.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendar.id), fields, null, null, null)?.use { c ->
                if (!c.moveToFirst()) CalendarCapabilities() else CalendarCapabilities(access(c.getInt(c.getColumnIndexOrThrow(fields[0]))), c.getInt(c.getColumnIndexOrThrow(fields[1])) != 0)
            } ?: CalendarCapabilities()
        }.getOrDefault(CalendarCapabilities())
    }
    fun requestSync(context: Context, calendar: DeviceCalendar): SyncRequest {
        if (!DeviceCalendarSources.allowed(context)) return SyncRequest.UNAVAILABLE
        return try {
            if (DeviceCalendars.list(context.contentResolver).none { it.identity == calendar.identity }) return SyncRequest.UNAVAILABLE
            if (calendar.account.isBlank() || calendar.accountType.isBlank() || calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) return SyncRequest.NO_ADAPTER
            if (ContentResolver.getSyncAdapterTypes().orEmpty().none { it.authority == CalendarContract.AUTHORITY && it.accountType == calendar.accountType }) return SyncRequest.NO_ADAPTER
            val extras = Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true); putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true) }
            ContentResolver.requestSync(Account(calendar.account, calendar.accountType), CalendarContract.AUTHORITY, extras)
            SyncRequest.REQUESTED
        } catch (_: SecurityException) { SyncRequest.UNAVAILABLE }
        catch (_: IllegalArgumentException) { SyncRequest.UNAVAILABLE }
    }
    fun accountSettings(calendar: DeviceCalendar? = null) = Intent(Settings.ACTION_SYNC_SETTINGS).apply {
        putExtra(Settings.EXTRA_AUTHORITIES, arrayOf(CalendarContract.AUTHORITY))
        if (calendar != null && calendar.account.isNotBlank() && calendar.accountType.isNotBlank()) putExtra("account", Account(calendar.account, calendar.accountType))
    }
    fun sharing(calendar: DeviceCalendar): Intent = when {
        calendar.accountType.equals("com.google", true) -> Intent(Intent.ACTION_VIEW, Uri.parse("https://calendar.google.com/calendar/u/0/r/settings"))
        calendar.accountType.contains("outlook", true) || calendar.accountType.contains("microsoft", true) || calendar.accountType.contains("exchange", true) -> Intent(Intent.ACTION_VIEW, Uri.parse("https://outlook.office.com/calendar/"))
        else -> accountSettings(calendar)
    }
    fun configureBackground(context: Context) {
        val enabled = SharedCalendarStore(context).background && DeviceCalendarLinksStore(context).read().isNotEmpty()
        if (!enabled) {
            try { WorkManager.getInstance(context).cancelUniqueWork(CONNECTION_WORK) } catch (_: IllegalStateException) { }
            return
        }
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(CONNECTION_WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CalendarConnectionWorker>(15, TimeUnit.MINUTES).build())
    }
}
class CalendarConnectionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!SharedCalendarStore(context).background) return Result.success()
        if (!DeviceCalendarSources.allowed(context)) return Result.success()
        DeviceCalendarLinksStore(context).read().distinctBy { it.account to it.accountType }.forEach { SharedCalendars.requestSync(context, it) }
        return try {
            (context as CalendarApplication).deviceConnections.refresh()
            CalendarWidgets.update(context)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
