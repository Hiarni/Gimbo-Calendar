package de.luma.calendar.widget

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.room.withTransaction
import de.luma.calendar.*
import de.luma.calendar.data.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import de.luma.calendar.ui.readableAccent
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*
import java.time.format.DateTimeFormatter

open class CalendarWidget : AppWidgetProvider() {
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetSettingsStore(context).remove(it) } }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)
    private fun refresh(context: Context) {
        val result = goAsync()
        (context.applicationContext as CalendarApplication).scope.launch {
            try { CalendarWidgets.update(context) } finally { result?.finish() }
        }
    }
}
class AgendaWidget : CalendarWidget()
class MonthWidget : CalendarWidget()

object CalendarWidgets {
    private val updateMutex = Mutex()
    internal fun open(context: Context, date: LocalDate, eventId: Long = -1, add: Boolean = false, occurrenceKey: String? = null, page: String? = null): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setData(Uri.parse("luma://widget/$date/$eventId/$add/${Uri.encode(occurrenceKey.orEmpty())}/${page.orEmpty()}"))
            .putExtra("date", date.toString()).putExtra("eventId", eventId).putExtra("add", add).putExtra("occurrenceKey", occurrenceKey).putExtra("page", page),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    suspend fun update(context: Context) = updateMutex.withLock {
        val local = AppLocale.wrap(context)
        val manager = AppWidgetManager.getInstance(context)
        val agenda = manager.getAppWidgetIds(ComponentName(context, AgendaWidget::class.java))
        val month = manager.getAppWidgetIds(ComponentName(context, MonthWidget::class.java))
        if (agenda.isEmpty() && month.isEmpty()) return@withLock
        val today = LocalDate.now()
        val settings = SettingsStore(local).read()
        val db = CalendarDatabase.get(context)
        val snapshot = db.withTransaction { CalendarSnapshot(db.events().all(), db.calendars().all(), db.exceptions().all(), db.tasks().all(), emptyList()) }
        val linked = de.luma.calendar.integrations.DeviceCalendarSources.read(context, today.withDayOfMonth(1).minusDays(7), today.plusMonths(3).plusDays(1))
        val calendars = snapshot.calendars + linked.calendars
        val events = snapshot.events + linked.events + HolidayStore(local).read().events(settings, local.resources.configuration.locales[0]) + de.luma.calendar.integrations.SchoolHolidays(local).read(settings).events(settings, local.resources.configuration.locales[0])
        fun unavailable(id: Int): Boolean {
            val options = WidgetSettingsStore(context).read(id)
            return linked.sources.any { it.status != de.luma.calendar.integrations.SourceStatus.READY && (options.calendars == null || it.calendar.displayId in options.calendars) }
        }
        agenda.forEach { id -> manager.updateAppWidget(id, render(local, id, false, settings, events, calendars, snapshot.exceptions, snapshot.tasks, unavailable(id))) }
        month.forEach { id -> manager.updateAppWidget(id, render(local, id, true, settings, events, calendars, snapshot.exceptions, snapshot.tasks, unavailable(id))) }
    }

    internal fun render(context: Context, id: Int, monthProvider: Boolean, settings: AppSettings, events: List<Event>, calendars: List<UserCalendar>, exceptions: List<EventException>, tasks: List<CalendarTask>, unavailable: Boolean): RemoteViews {
        val options = WidgetSettingsStore(context).read(id)
        val manager = AppWidgetManager.getInstance(context)
        val dimensions = manager.getAppWidgetOptions(id)
        val width = dimensions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320).coerceIn(120, 700)
        val height = dimensions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (monthProvider) 350 else 220).coerceIn(100, 900)
        val appDark = settings.theme == ThemeMode.DARK || settings.theme == ThemeMode.SYSTEM && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = options.palette == WidgetPalette.DARK || options.palette == WidgetPalette.SYSTEM && appDark
        val lightInk = options.ink == WidgetInk.LIGHT || options.ink == WidgetInk.AUTO && dark
        val foreground = if (lightInk) Color.rgb(244, 244, 250) else Color.rgb(32, 32, 42)
        val background = if (dark) Color.rgb(29, 29, 36) else Color.rgb(248, 247, 250)
        val accent = if (options.opacity < 70 || options.ink != WidgetInk.AUTO) foreground else readableAccent(settings.accent.toInt(), background)
        val layout = if (options.layout == WidgetLayout.DEFAULT) if (monthProvider) WidgetLayout.MONTH_AGENDA else WidgetLayout.AGENDA else options.layout
        val showMonth = layout == WidgetLayout.MONTH || layout == WidgetLayout.MONTH_AGENDA
        val showWeek = layout == WidgetLayout.WEEK
        val showEntries = layout != WidgetLayout.MONTH
        val today = LocalDate.now()
        val first = today.withDayOfMonth(1)
        val locale = context.resources.configuration.locales[0]
        val end = if (layout == WidgetLayout.DAY) today.plusDays(1) else today.plusDays(if (showWeek) 7 else options.horizon.toLong())
        val upcoming = EventEngine.occurrences(events, today, end, exceptions = exceptions, filterEvent = options::includesEvent).filter { it.end.toInstant() > Instant.now() }
        val monthEvents = if (showMonth || showWeek) EventEngine.occurrences(events, first.minusDays(7), first.plusMonths(1).plusDays(7), exceptions = exceptions, filterEvent = options::includesEvent) else emptyList()
        val views = RemoteViews(context.packageName, R.layout.calendar_widget)
        val surface = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(surface).drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), options.corners.toFloat(), options.corners.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background; alpha = options.opacity * 255 / 100 })
        views.setImageViewBitmap(R.id.widget_surface, surface)
        val pad = if (height < 240 || width < 200) 8 else if (options.density == WidgetDensity.COMPACT) 10 else 16
        val scale = options.textScale / 100f * context.resources.configuration.fontScale
        val rowHeight = (maxOf(48f, (if (options.density == WidgetDensity.COMPACT) 48 else 54) * options.textScale / 100f) * context.resources.configuration.fontScale).toInt()
        val fullHeader = height >= 240
        val headerHeight = ((if (fullHeader) 55 else 28) * scale).toInt()
        val showHeader = options.header && (if (showMonth) height >= 280 else height - pad * 2 >= headerHeight + rowHeight)
        val showActions = options.actions && width >= 220 && height >= (if (showMonth) 350 else 190) && (showMonth || height - pad * 2 - (if (showHeader) headerHeight else 0) >= rowHeight + 48)
        val monthTooSmall = showMonth && height < 220
        val density = context.resources.displayMetrics.density
        views.setViewPadding(R.id.widget_content, (pad * density).toInt(), (pad * density).toInt(), (pad * density).toInt(), (pad * density).toInt())
        views.setViewVisibility(R.id.widget_header, if (showHeader) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_actions, if (showActions) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_subtitle, if (fullHeader || unavailable) View.VISIBLE else View.GONE)
        views.setTextColor(R.id.widget_title, accent)
        views.setTextColor(R.id.widget_subtitle, foreground)
        views.setTextColor(R.id.widget_add, accent)
        views.setTextColor(R.id.widget_configure, accent)
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 19 * options.textScale / 100f)
        views.setTextViewText(R.id.widget_title, if (showMonth) today.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)) else context.text(layout.label))
        views.setTextViewText(R.id.widget_subtitle, if (unavailable) context.text("Gerätekalender prüfen") else today.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "EEEE, d. MMMM" else "EEEE, d MMMM", locale)))
        views.setTextViewText(R.id.widget_add, context.getString(R.string.widget_add))
        views.setTextViewText(R.id.widget_configure, context.text("Widget anpassen"))
        views.setOnClickPendingIntent(R.id.widget_configure, PendingIntent.getActivity(context, id, Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        views.setOnClickPendingIntent(R.id.widget_root, open(context, today))
        views.setOnClickPendingIntent(R.id.widget_add, open(context, today, add = true))
        var gridHeight = 0
        if ((showMonth || showWeek) && !monthTooSmall) {
            val start = if (showWeek) today.minusDays((if (settings.mondayFirst) today.dayOfWeek.value - 1 else today.dayOfWeek.value % 7).toLong()) else first.minusDays((if (settings.mondayFirst) first.dayOfWeek.value - 1 else first.dayOfWeek.value % 7).toLong())
            val rows = if (showWeek) 1 else ((today.lengthOfMonth() + java.time.temporal.ChronoUnit.DAYS.between(start, first) + 6) / 7).toInt()
            val available = height - pad * 2 - (if (showHeader) headerHeight else 0) - (if (showActions) 48 else 0)
            val cellHeight = if (showWeek) 42 else ((available - 24) / rows).coerceIn(24, 48)
            gridHeight = rows * cellHeight + 24
            views.setViewVisibility(R.id.widget_days, View.VISIBLE)
            views.removeAllViews(R.id.widget_days)
            fun row(dates: List<LocalDate>, names: Boolean): RemoteViews {
                val rv = RemoteViews(context.packageName, R.layout.widget_week_row)
                dates.forEach { date ->
                    val day = RemoteViews(context.packageName, R.layout.widget_day)
                    val values = monthEvents.filter { it.start.toInstant() < date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant() && it.end.toInstant() > date.atStartOfDay(ZoneId.systemDefault()).toInstant() }
                    day.setTextViewText(R.id.widget_day_number, if (names) date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale) else date.dayOfMonth.toString())
                    day.setTextColor(R.id.widget_day_number, if (!names && date == today) accent else if (!names && date.month != today.month && showMonth) Color.argb(150, Color.red(foreground), Color.green(foreground), Color.blue(foreground)) else foreground)
                    day.setTextViewTextSize(R.id.widget_day_number, TypedValue.COMPLEX_UNIT_SP, minOf((if (names) 11 else 14) * options.textScale / 100f, (if (names) 18f else minOf((cellHeight - 12) * .8f, (width - pad * 2) / 7f * .7f)) / context.resources.configuration.fontScale))
                    day.setInt(R.id.widget_day_number, "setHeight", ((if (names) 24 else cellHeight - 12) * density).toInt())
                    day.setViewVisibility(R.id.widget_day_dots, if (names) View.GONE else View.VISIBLE)
                    day.setTextViewText(R.id.widget_day_dots, if (values.isEmpty()) "" else "●".repeat(minOf(3, values.size)))
                    day.setTextColor(R.id.widget_day_dots, if (options.opacity < 70) foreground else values.firstOrNull()?.event?.calendarColor(calendars)?.toInt() ?: accent)
                    if (!names) {
                        day.setContentDescription(R.id.widget_day_root, date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale)) + ", " + context.text("%1\$s Termine", values.size))
                        day.setOnClickPendingIntent(R.id.widget_day_root, open(context, date))
                    }
                    rv.addView(R.id.widget_week_row, day)
                }
                return rv
            }
            views.addView(R.id.widget_days, row((0L..6L).map { start.plusDays(it) }, true))
            repeat(rows) { number -> views.addView(R.id.widget_days, row((0L..6L).map { start.plusDays(number * 7L + it) }, false)) }
        }
        val space = height - pad * 2 - (if (showHeader) headerHeight else 0) - (if (showActions) 48 else 0) - gridHeight
        val limit = if (showEntries) minOf(options.entries, (space / rowHeight).coerceIn(0, 15)) else 0
        data class Entry(val date: LocalDate, val title: String, val detail: String, val color: Long, val intent: PendingIntent)
        val rows = upcoming.take(limit).map { o ->
            val date = o.start.withZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
            val time = if (o.event.allDay) context.text("ganztägig") else o.start.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            val detail = listOf(date.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "dd.MM." else "d MMM", locale)), time,
                if (options.calendarNames) o.event.calendarLabel(calendars) { context.text(it) } else "", if (options.locations) o.event.location else "").filter { it.isNotBlank() }.joinToString(" · ")
            Entry(date, o.event.title, detail, o.event.calendarColor(calendars), open(context, date, o.event.id, occurrenceKey = o.key))
        } + if (options.tasks) tasks.filter { !it.completed && (options.calendars == null || it.calendarId in options.calendars) && (it.due == null || LocalDate.parse(it.due) < end) }.sortedBy { it.due?.let(LocalDate::parse) ?: today }.take(limit).map { task ->
            val date = task.due?.let(LocalDate::parse) ?: today
            Entry(date, "○  " + task.title, context.text(if (date < today) "Überfällig" else "Aufgabe") + if (task.due == null) "" else " · " + date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT).withLocale(locale)), calendars.firstOrNull { it.id == task.calendarId }?.color ?: settings.accent, open(context, date, page = "tasks"))
        } else emptyList()
        views.removeAllViews(R.id.widget_items)
        rows.sortedBy { it.date }.take(limit).forEach { entry ->
            val item = RemoteViews(context.packageName, R.layout.widget_event)
            item.setInt(R.id.widget_event_root, "setMinimumHeight", (rowHeight * density).toInt())
            item.setTextColor(R.id.widget_event_time, accent)
            item.setTextColor(R.id.widget_event_title, foreground)
            item.setTextViewTextSize(R.id.widget_event_title, TypedValue.COMPLEX_UNIT_SP, 14 * options.textScale / 100f)
            item.setTextViewTextSize(R.id.widget_event_time, TypedValue.COMPLEX_UNIT_SP, 11 * options.textScale / 100f)
            item.setInt(R.id.widget_calendar_color, "setBackgroundColor", entry.color.toInt())
            item.setTextViewText(R.id.widget_event_time, entry.detail)
            item.setTextViewText(R.id.widget_event_title, entry.title)
            item.setOnClickPendingIntent(R.id.widget_event_root, entry.intent)
            views.addView(R.id.widget_items, item)
        }
        if (monthTooSmall || rows.isEmpty() && limit > 0) {
            val item = RemoteViews(context.packageName, R.layout.widget_event)
            item.setTextViewText(R.id.widget_event_title, context.text(if (monthTooSmall) "Für die Monatsansicht das Widget höher ziehen." else if (unavailable) "Gerätekalender nicht verfügbar" else "Platz für deine Pläne."))
            item.setTextColor(R.id.widget_event_title, foreground)
            item.setTextViewText(R.id.widget_event_time, context.text(if (unavailable) "Verbindungen in Gimbo Kalender prüfen" else "Keine Einträge im gewählten Zeitraum"))
            item.setInt(R.id.widget_event_root, "setMinimumHeight", (rowHeight * density).toInt())
            item.setTextColor(R.id.widget_event_time, accent)
            views.addView(R.id.widget_items, item)
        }
        return views
    }
}
