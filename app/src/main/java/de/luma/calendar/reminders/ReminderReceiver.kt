package de.luma.calendar.reminders

import android.content.*
import de.luma.calendar.CalendarApplication
import de.luma.calendar.widget.CalendarWidgets
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        (context.applicationContext as CalendarApplication).scope.launch {
            try {
                if (intent.action == ReminderScheduler.SNOOZE) ReminderScheduler.snooze(context, intent)
                else ReminderScheduler.notify(context, intent.getLongExtra("id", -1), intent.getLongExtra("start", 0), intent.getStringExtra("occurrenceKey"), intent.getIntExtra("minutes", -1).takeIf { it >= 0 }, intent.getLongExtra("snoozedAt", -1).takeIf { it >= 0 })
                ReminderScheduler.rebuild(context)
                        de.luma.calendar.reminders.ExtraReminderScheduler.rebuild(context)
                CalendarWidgets.update(context)
            } finally { result.finish() }
        }
    }
}
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val result = goAsync()
        (context.applicationContext as CalendarApplication).scope.launch {
            try { ReminderScheduler.rebuild(context)
                        de.luma.calendar.reminders.ExtraReminderScheduler.rebuild(context); CalendarWidgets.update(context) }
            finally { result.finish() }
        }
    }
}
