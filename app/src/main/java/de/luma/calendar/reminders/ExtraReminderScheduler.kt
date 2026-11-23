package de.luma.calendar.reminders

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import de.luma.calendar.*
import de.luma.calendar.data.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

object ExtraReminderScheduler {
    private val mutex = Mutex()
    fun identity(kind: String, id: Long): String { require(kind in setOf("task", "travel") && id > 0); return "luma://extras/$kind/$id" }
    private fun pending(context: Context, kind: String, id: Long, start: Instant = Instant.EPOCH, key: String? = null) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ExtraReminderReceiver::class.java).setData(Uri.parse(identity(kind, id))).putExtra("kind", kind).putExtra("id", id).putExtra("start", start.toEpochMilli()).putExtra("key", key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun taskTime(task: CalendarTask, zone: ZoneId = ZoneId.systemDefault()): Instant? = if (task.completed || task.due == null || task.reminderTime == null) null else LocalDate.parse(task.due).atTime(LocalTime.parse(task.reminderTime)).atZone(zone).toInstant()
    fun departure(event: Event, plan: TravelPlan, now: Instant, exceptions: List<EventException> = emptyList()): Pair<Occurrence, Instant>? {
        if (event.allDay) return null
        plan.checked(); val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        return EventEngine.occurrences(listOf(event), today.minusDays(2), today.plusYears(5), exceptions = exceptions, maxOccurrences = 20000)
            .asSequence().filter { !it.event.allDay }.map { it to it.start.toInstant().minusSeconds((plan.minutes + plan.buffer) * 60L) }.firstOrNull { it.second > now }
    }
    suspend fun rebuild(context: Context) = mutex.withLock {
        val db = CalendarDatabase.get(context); val snapshot = db.withTransaction { Triple(db.events().all(), db.exceptions().all(), db.tasks().all()) to db.integrations().travel() }
        val (data, travel) = snapshot; val (events, exceptions, tasks) = data
        val prefs = context.getSharedPreferences("extra-reminders", 0); val previous = prefs.getStringSet("scheduled", emptySet()).orEmpty()
        val current = travel.filter { p -> events.any { it.id == p.eventId && !it.allDay } }.map { identity("travel", it.eventId) }.toSet() + tasks.filter { taskTime(it) != null }.map { identity("task", it.id) }
        val alarm = context.getSystemService(AlarmManager::class.java)
        (previous + current).forEach { uri -> val segments = Uri.parse(uri).pathSegments; if (segments.size == 2 && segments[0] in setOf("task", "travel")) segments[1].toLongOrNull()?.takeIf { it > 0 }?.let { id -> alarm.cancel(pending(context, segments[0], id)); if (uri !in current) NotificationManagerCompat.from(context).cancel(uri, 1) } }
        fun schedule(kind: String, id: Long, start: Instant, trigger: Instant, key: String? = null) {
            val intent = pending(context, kind, id, start, key)
            if (ReminderScheduler.precise(context)) try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), intent) } catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), intent) }
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), intent)
        }
        val now = Instant.now()
        travel.forEach { p -> events.firstOrNull { it.id == p.eventId }?.let { event -> departure(event, p, now, exceptions)?.let { (o, time) -> schedule("travel", event.id, o.start.toInstant(), time, o.key) } } }
        tasks.forEach { task -> taskTime(task)?.takeIf { it > now }?.let { schedule("task", task.id, it, it) } }
        check(prefs.edit().putStringSet("scheduled", current.toSet()).commit())
    }
    suspend fun notify(base: Context, kind: String, id: Long, start: Instant, key: String?) {
        if (kind !in setOf("task", "travel") || id <= 0) return
        val context = AppLocale.wrap(base); if (!ReminderScheduler.notificationsAllowed(context)) return
        val db = CalendarDatabase.get(context); val now = Instant.now(); var date: LocalDate; val title: String; val body: String; val open: Intent
        if (kind == "task") {
            val task = db.tasks().find(id) ?: return; val expected = taskTime(task) ?: return
            if (expected != start || expected > now.plusSeconds(60) || now >= LocalDate.parse(task.due).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()) return
            title = task.title; body = context.text("Aufgabe für heute"); date = LocalDate.parse(task.due)
            open = Intent(context, MainActivity::class.java).putExtra("page", "tasks").putExtra("date", date.toString())
        } else {
            val (event, plan, changes) = db.withTransaction { Triple(db.events().find(id), db.integrations().travel().firstOrNull { it.eventId == id }, db.exceptions().forEvent(id)) }
            if (event == null || event.allDay || plan == null) return
            date = start.atZone(ZoneId.systemDefault()).toLocalDate()
            val o = EventEngine.occurrences(listOf(event), date.minusDays(1), date.plusDays(2), exceptions = changes).firstOrNull { it.start.toInstant() == start && (key == null || it.key == key) && !it.event.allDay } ?: return
            val expected = start.minusSeconds((plan.minutes + plan.buffer) * 60L)
            if (expected > now.plusSeconds(60) || o.end.toInstant() < now) return
            title = context.text("Zeit aufzubrechen · %1\$s", o.event.title); body = context.text("Fahrzeit %1\$s Min. + Puffer %2\$s Min.", plan.minutes, plan.buffer)
            open = Intent(context, MainActivity::class.java).putExtra("date", date.toString()).putExtra("eventId", id).putExtra("occurrenceKey", o.key)
        }
        val channel = if (kind == "task") "calendar-tasks" else "calendar-departures"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, context.text(if (kind == "task") "Aufgabenerinnerungen" else "Abfahrtserinnerungen"), NotificationManager.IMPORTANCE_DEFAULT))
        val target = PendingIntent.getActivity(context, 0, open.setData(Uri.parse(identity(kind, id) + "/open/${start.toEpochMilli()}")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { NotificationManagerCompat.from(context).notify(identity(kind, id), 1, NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body).setContentIntent(target).setAutoCancel(true).build()) } catch (_: SecurityException) { }
    }
}
class ExtraReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync(); (context.applicationContext as CalendarApplication).scope.launch {
            try { ExtraReminderScheduler.notify(context, intent.getStringExtra("kind").orEmpty(), intent.getLongExtra("id", -1), Instant.ofEpochMilli(intent.getLongExtra("start", 0)), intent.getStringExtra("key")); ExtraReminderScheduler.rebuild(context) } finally { result.finish() }
        }
    }
}
