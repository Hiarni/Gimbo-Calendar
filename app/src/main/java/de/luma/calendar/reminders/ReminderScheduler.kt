package de.luma.calendar.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import de.luma.calendar.MainActivity
import de.luma.calendar.R
import de.luma.calendar.data.CalendarDatabase
import de.luma.calendar.model.*
import de.luma.calendar.i18n.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

object ReminderScheduler {
    private val mutex = Mutex()
    private const val CHANNEL = "calendar-reminders"
    const val SNOOZE = "de.luma.calendar.SNOOZE"
    private fun preferences(context: Context) = context.getSharedPreferences("event-alarm-state", Context.MODE_PRIVATE)
    fun precise(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun pending(context: Context, id: Long, minutes: Int? = null, start: Instant = Instant.EPOCH, key: String? = null, snoozedAt: Long? = null): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("luma://${if (snoozedAt == null) "reminder" else "snoozed"}/$id" + (minutes?.let { "/$it" } ?: "")))
            .putExtra("id", id).putExtra("start", start.toEpochMilli()).putExtra("occurrenceKey", key).putExtra("minutes", minutes ?: -1).putExtra("snoozedAt", snoozedAt ?: -1),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(context: Context, trigger: Instant, intent: PendingIntent) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (precise(context)) {
            try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), intent) }
            catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), intent) }
        } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), intent)
    }
    private data class Snooze(val id: Long, val minutes: Int, val start: Long, val key: String, val original: Long, val fireAt: Long) {
        val identity get() = "$id/$minutes"
        fun json() = JSONObject().put("id", id).put("minutes", minutes).put("start", start).put("key", key).put("original", original).put("fireAt", fireAt)
    }
    private fun snoozes(context: Context): List<Snooze> = runCatching {
        JSONArray(preferences(context).getString("snoozes", "[]")).let { a -> require(a.length() <= 400); (0 until a.length()).map { i -> val r = a.getJSONObject(i); Snooze(r.getLong("id"), r.getInt("minutes"), r.getLong("start"), r.getString("key"), r.getLong("original"), r.getLong("fireAt")) } }
    }.getOrDefault(emptyList())
    private fun writeSnoozes(context: Context, values: List<Snooze>) { check(preferences(context).edit().putString("snoozes", JSONArray(values.map { it.json() }).toString()).commit()) }
    private fun validSnooze(record: Snooze, events: List<Event>, exceptions: List<EventException>): Occurrence? {
        val event = events.firstOrNull { it.id == record.id } ?: return null
        val date = Instant.ofEpochMilli(record.start).atZone(ZoneId.systemDefault()).toLocalDate()
        return EventEngine.occurrences(listOf(event), date.minusDays(1), date.plusDays(2), exceptions = exceptions).firstOrNull {
            it.key == record.key && it.start.toInstant().toEpochMilli() == record.start && record.minutes in it.event.reminderOffsets && it.event.reminderAt(it, record.minutes).toEpochMilli() == record.original
        }
    }
    suspend fun cancel(context: Context, event: Event) = mutex.withLock {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(pending(context, event.id))
        val identities = preferences(context).getStringSet("scheduled", emptySet()).orEmpty()
        identities.filter { it.startsWith("${event.id}/") }.forEach { alarm.cancel(pending(context, event.id, it.substringAfter('/').toInt())) }
        event.reminderOffsets.forEach { alarm.cancel(pending(context, event.id, it)) }
        snoozes(context).filter { it.id == event.id }.forEach { alarm.cancel(pending(context, it.id, it.minutes, snoozedAt = it.fireAt)) }
        writeSnoozes(context, snoozes(context).filterNot { it.id == event.id })
        preferences(context).edit().putStringSet("scheduled", identities.filterNot { it.startsWith("${event.id}/") }.toSet()).apply()
        NotificationManagerCompat.from(context).cancel(event.id.toString(), 1)
    }
    suspend fun rebuild(context: Context) = mutex.withLock {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val now = Instant.now()
        val database = CalendarDatabase.get(context)
        val (events, exceptions) = database.withTransaction { database.events().all() to database.exceptions().all() }
        preferences(context).getStringSet("scheduled", emptySet()).orEmpty().forEach { key ->
            val parts = key.split('/')
            if (parts.size == 2) alarm.cancel(pending(context, parts[0].toLong(), parts[1].toInt()))
        }
        events.forEach { alarm.cancel(pending(context, it.id)) }
        val planned = events.flatMap { e -> EventEngine.nextReminders(e, now, exceptions = exceptions).map { e.id to it } }.sortedBy { it.second.trigger }.take(400)
        planned.forEach { (id, value) -> schedule(context, value.trigger, pending(context, id, value.minutes, value.occurrence.start.toInstant(), value.occurrence.key)) }
        check(preferences(context).edit().putStringSet("scheduled", planned.map { "${it.first}/${it.second.minutes}" }.toSet()).commit())
        val retained = snoozes(context).filter { r ->
            alarm.cancel(pending(context, r.id, r.minutes, snoozedAt = r.fireAt))
            r.fireAt > now.toEpochMilli() && validSnooze(r, events, exceptions)?.end?.toInstant()?.isAfter(now) == true
        }
        writeSnoozes(context, retained)
        retained.forEach { r -> schedule(context, Instant.ofEpochMilli(r.fireAt), pending(context, r.id, r.minutes, Instant.ofEpochMilli(r.start), r.key, r.fireAt)) }
    }
    suspend fun snooze(context: Context, intent: Intent) = mutex.withLock {
        val id = intent.getLongExtra("id", -1)
        val minutes = intent.getIntExtra("minutes", -1)
        val now = Instant.now()
        val record = Snooze(id, minutes, intent.getLongExtra("start", 0), intent.getStringExtra("occurrenceKey") ?: return@withLock, intent.getLongExtra("original", 0), now.plusSeconds(600).toEpochMilli())
        val db = CalendarDatabase.get(context)
        val (events, exceptions) = db.withTransaction { db.events().all() to db.exceptions().all() }
        if (validSnooze(record, events, exceptions)?.end?.toInstant()?.isAfter(now.plusSeconds(600)) != true) return@withLock
        val values = snoozes(context).filterNot { it.identity == record.identity } + record
        if (values.size > 400) return@withLock
        writeSnoozes(context, values)
        schedule(context, Instant.ofEpochMilli(record.fireAt), pending(context, id, minutes, Instant.ofEpochMilli(record.start), record.key, record.fireAt))
        NotificationManagerCompat.from(context).cancel(id.toString(), 1)
    }
    suspend fun notify(baseContext: Context, id: Long, startMillis: Long, occurrenceKey: String? = null, offset: Int? = null, snoozedAt: Long? = null) = mutex.withLock {
        val context = AppLocale.wrap(baseContext)
        val db = CalendarDatabase.get(context)
        val (parent, exceptions) = db.withTransaction { db.events().find(id) to db.exceptions().forEvent(id) }
        if (parent == null || !notificationsAllowed(context)) return@withLock
        val start = Instant.ofEpochMilli(startMillis)
        val date = start.atZone(ZoneId.systemDefault()).toLocalDate()
        val occurrences = EventEngine.occurrences(listOf(parent), date.minusDays(1), date.plusDays(2), exceptions = exceptions)
        val occurrence = occurrences.firstOrNull { it.start.toInstant() == start && (occurrenceKey == null || it.key == occurrenceKey) } ?: return@withLock
        val event = occurrence.event
        val minutes = offset ?: event.reminderOffsets.firstOrNull() ?: return@withLock
        if (minutes !in event.reminderOffsets) return@withLock
        val expected = event.reminderAt(occurrence, minutes)
        val now = Instant.now()
        if (occurrence.end.toInstant() < now) return@withLock
        if (snoozedAt != null) {
            val record = snoozes(context).firstOrNull { it.id == id && it.minutes == minutes && it.fireAt == snoozedAt && it.key == occurrence.key } ?: return@withLock
            if (record.fireAt > now.plusSeconds(60).toEpochMilli() || record.original != expected.toEpochMilli()) return@withLock
            writeSnoozes(context, snoozes(context).filterNot { it.identity == record.identity })
        } else if (expected > now.plusSeconds(60)) return@withLock
        val due = occurrences.filter { it.end.toInstant() >= now && it.event.reminderOffsets.any { offset -> it.event.reminderAt(it, offset) == expected } }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, context.text("Terminerinnerungen"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setData(Uri.parse("luma://event/$id/${Uri.encode(occurrence.key)}"))
            .putExtra("date", date.toString()).putExtra("eventId", id).putExtra("occurrenceKey", occurrence.key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).setAction(SNOOZE).setData(Uri.parse("luma://snooze/$id/$minutes/${Uri.encode(occurrence.key)}"))
            .putExtra("id", id).putExtra("minutes", minutes).putExtra("start", startMillis).putExtra("occurrenceKey", occurrence.key).putExtra("original", expected.toEpochMilli()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun line(item: Occurrence): String = listOf(if (item.event.allDay) context.text("Ganztägig") else item.start.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), item.event.title, item.event.location).filter { it.isNotBlank() }.joinToString(" · ")
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (due.size > 1) context.text("%1\$s Terminerinnerungen", due.size) else event.title)
            .setContentText(if (due.size > 1) due.joinToString(" · ") { it.event.title } else line(occurrence)).setContentIntent(open).setAutoCancel(true)
        if (occurrence.end.toInstant() > now.plusSeconds(600)) builder.addAction(R.drawable.ic_notification, context.text("In 10 Min. erinnern"), snooze)
        if (due.size > 1) builder.setStyle(NotificationCompat.InboxStyle().also { inbox -> due.forEach { inbox.addLine(line(it)) } })
        try { NotificationManagerCompat.from(context).notify(id.toString(), 1, builder.build()) } catch (_: SecurityException) { }
    }
}
