package de.luma.calendar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import de.luma.calendar.model.*
import de.luma.calendar.i18n.*
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(initial: LocalDate, onDismiss: () -> Unit, onSelect: (LocalDate) -> Unit) {
    val t = texts()
    val locale = t.locale
    val safeInitial = initial.coerceIn(LocalDate.of(1900, 1, 1), LocalDate.of(2100, 12, 31))
    val state = rememberDatePickerState(initialSelectedDateMillis = safeInitial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1900..2100)
    androidx.compose.material3.DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton(onClick = { state.selectedDateMillis?.let { onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }, enabled = state.selectedDateMillis != null) { Text(t("Übernehmen")) }
    }, dismissButton = { TextButton(onDismiss) { Text(t("Abbrechen")) } }) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditor(event: Event, busy: Boolean, onDismiss: () -> Unit, singleOccurrence: Boolean = false, remoteError: String? = null, futureOccurrence: Boolean = false, templates: List<EventTemplate> = emptyList(), external: Boolean = false, externalCalendarName: String = "", sharedAttachment: Boolean = false, onSave: (Event) -> Unit) {
    val t = texts()
    val locale = t.locale
    var templatePicker by remember { mutableStateOf(false) }
    var title by rememberSaveable(event.id) { mutableStateOf(event.title) }
    var startText by rememberSaveable(event.id) { mutableStateOf(event.start) }
    var endText by rememberSaveable(event.id) { mutableStateOf(event.end) }
    var allDay by rememberSaveable(event.id) { mutableStateOf(event.allDay) }
    val calendars = LocalCalendars.current.filter { it.id > 0 }
    var calendarId by rememberSaveable(event.id) { mutableLongStateOf(event.calendarId) }
    var location by rememberSaveable(event.id) { mutableStateOf(event.location) }
    var notes by rememberSaveable(event.id) { mutableStateOf(event.notes) }
    var repeat by rememberSaveable(event.id) { mutableStateOf(event.repeat.name) }
    var interval by rememberSaveable(event.id) { mutableStateOf(event.interval.toString()) }
    var until by rememberSaveable(event.id) { mutableStateOf(event.until) }
    var categoryId by rememberSaveable(event.id) { mutableStateOf(event.categoryId) }
    var additionalReminders by rememberSaveable(event.id) { mutableStateOf(event.additionalReminders) }
    var reminderClock by rememberSaveable(event.id) { mutableStateOf(event.allDayReminderTime) }
    var customReminder by remember { mutableStateOf(false) }
    var reminder by rememberSaveable(event.id) { mutableIntStateOf(event.reminderMinutes) }
    var zone by rememberSaveable(event.id) { mutableStateOf(event.zone) }
    var dateField by remember { mutableStateOf<String?>(null) }
    var timeField by remember { mutableStateOf<String?>(null) }
    var zonePicker by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val start = LocalDateTime.parse(startText)
    val end = LocalDateTime.parse(endText)
    fun chosenReminders() = (listOf(reminder).filter { it >= 0 } + additionalReminders).distinct().sorted()
    fun chooseReminders(values: List<Int>) { val sorted = values.distinct().sorted(); reminder = sorted.firstOrNull() ?: -1; additionalReminders = sorted.drop(1) }
    val changed = categoryId != event.categoryId || additionalReminders != event.additionalReminders || reminderClock != event.allDayReminderTime || title != event.title || startText != event.start || endText != event.end || allDay != event.allDay || calendarId != event.calendarId ||
        location != event.location || notes != event.notes || repeat != event.repeat.name || interval != event.interval.toString() || until != event.until || reminder != event.reminderMinutes || zone != event.zone
    fun close() { if (!busy) { if (changed) discard = true else onDismiss() } }
    fun changeStart(value: LocalDateTime) {
        val duration = Duration.between(start, end)
        startText = value.toString(); endText = value.plus(duration).toString()
    }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { close() }
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(if (event.id == 0L) t("Ein neuer Moment.") else t("Termin bearbeiten")) },
                    navigationIcon = { IconButton({ close() }, enabled = !busy) { Icon(Icons.Close, t("Abbrechen")) } },
                    actions = { TextButton(onClick = {
                        try {
                            val value = Event(event.id, title, startText, endText, zone, allDay, calendars.firstOrNull { it.id == calendarId }?.defaultCategory ?: Category.PERSONAL, location, notes,
                                Repeat.valueOf(repeat), if (repeat == Repeat.NONE.name) 1 else interval.toIntOrNull() ?: 0, if (repeat == Repeat.NONE.name) null else until, reminder, calendarId, categoryId, additionalReminders, reminderClock).checked()
                            error = null; onSave(value)
                        } catch (e: Exception) { error = t.error(e.message) }
                    }, enabled = !busy) { Text(if (busy) t("Speichert…") else t("Speichern")) } })
            }) { padding ->
                LazyColumn(Modifier.padding(padding).imePadding().testTag("event-editor-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (event.id == 0L && templates.isNotEmpty()) item { OutlinedButton({ templatePicker = true }) { Text(t("Vorlage wählen")) } }
                    if (futureOccurrence) item { Text(t("Ab dem gewählten Serientermin. Frühere Vorkommen bleiben erhalten."), color = MaterialTheme.colorScheme.primary) }
                    if (singleOccurrence) item { Text(t("Nur dieses Vorkommen wird geändert. Die übrige Serie bleibt erhalten."), color = MaterialTheme.colorScheme.primary) }
                    else if (event.repeat != Repeat.NONE && event.id != 0L) item { Text(t("Änderungen gelten für die Serie. Bestehende Einzeländerungen behalten ihre eigenen Werte."), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    if (external) item { Text(t("Gerätekalender: %1\$s. Speichern ändert das Original. Erinnerungen bleiben bei der Kalender-App.", externalCalendarName)) }
                    if (sharedAttachment) item { Text(t("Die geteilte Datei wird erst mit dem Termin gespeichert.")) }
                    item { OutlinedTextField(title, { title = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(t("Titel")) }, placeholder = { Text(t("Was hast du vor?")) }, singleLine = true, shape = RoundedCornerShape(16.dp), isError = error != null && title.isBlank()) }
                    if (error != null || remoteError != null) item { Text(error ?: remoteError!!, color = MaterialTheme.colorScheme.error) }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("Ganztägig"), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Switch(allDay, { value ->
                                allDay = value
                                if (value && event.id == 0L && reminderClock == "00:00") reminderClock = "09:00"
                                if (value) { startText = start.toLocalDate().atStartOfDay().toString(); endText = maxOf(start.toLocalDate().plusDays(1), end.toLocalDate().plusDays(if (end.toLocalTime() == LocalTime.MIDNIGHT) 0 else 1)).atStartOfDay().toString() }
                                else { startText = start.toLocalDate().atTime(9, 0).toString(); endText = start.toLocalDate().atTime(10, 0).toString() }
                            })
                        }
                        DateTimeRow(t("Beginn"), start, allDay, { dateField = "start" }, { timeField = "start" })
                        DateTimeRow(if (allDay) t("Letzter Tag") else t("Ende"), if (allDay) end.minusDays(1) else end, allDay, { dateField = "end" }, { timeField = "end" })
                    }
                    if (!external) item {
                        Text(t("Kalender"), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            calendars.forEach { item -> FilterChip(calendarId == item.id, { calendarId = item.id }, { Text(item.displayName { t(it) }) }, modifier = Modifier.testTag("editor-calendar-${item.id}")) }
                        }
                    }
                    if (!external) item {
                        Text(t("Kategorie"), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(categoryId == null, { categoryId = null }, { Text(t("Keine")) })
                            LocalCategories.current.forEach { category -> FilterChip(categoryId == category.id, { categoryId = category.id }, { Text(category.name) }, modifier = Modifier.testTag("editor-category-${category.id}"), leadingIcon = { Icon(Icons.Tag, null, tint = androidx.compose.ui.graphics.Color(category.color), modifier = Modifier.size(16.dp)) }) }
                        }
                        if (LocalCategories.current.isEmpty()) Text(t("Eigene Kategorien unter Einstellungen → Kategorien anlegen."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { OutlinedTextField(location, { location = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text(t("Ort (optional)")) }, leadingIcon = { Icon(Icons.Location, null) }, singleLine = true, shape = RoundedCornerShape(16.dp)) }
                    item { OutlinedTextField(notes, { notes = it.take(10000) }, Modifier.fillMaxWidth(), label = { Text(t("Notizen (optional)")) }, minLines = 2, maxLines = 6, shape = RoundedCornerShape(16.dp)) }
                    if (!singleOccurrence && !external) item {
                        Text(t("Wiederholung"), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Repeat.entries.forEach { item -> FilterChip(repeat == item.name, { repeat = item.name }, { Text(t(item.label)) }) } }
                        if (repeat != Repeat.NONE.name) {
                            OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(2) }, Modifier.width(140.dp), label = { Text(t("Intervall (1–99)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t("Serie mit Enddatum"), Modifier.weight(1f))
                                Switch(until != null, { until = if (it) start.toLocalDate().plusYears(1).coerceAtMost(LocalDate.of(2100, 12, 31)).toString() else null })
                            }
                            if (until != null) OutlinedButton({ dateField = "until" }) { Text(t("Bis %1\$s", LocalDate.parse(until).format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)))) }
                            if (repeat == Repeat.MONTHLY.name || repeat == Repeat.YEARLY.name) Text(t("Fehlende Kalendertage werden übersprungen (z. B. 31. Februar)."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!external) item {
                        Text(t("Erinnerung"), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(-1 to t("Keine"), 0 to t("Zum Beginn"), 5 to t("5 Min."), 15 to t("15 Min."), 30 to t("30 Min."), 60 to t("1 Std."), 1440 to t("1 Tag")).forEach { (minutes, label) ->
                                FilterChip(if (minutes < 0) chosenReminders().isEmpty() else minutes in chosenReminders(), {
                                    if (minutes < 0) chooseReminders(emptyList()) else chooseReminders(if (minutes in chosenReminders()) chosenReminders() - minutes else chosenReminders() + minutes)
                                }, { Text(t(label)) }, enabled = minutes < 0 || minutes in chosenReminders() || chosenReminders().size < 5)
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chosenReminders().filter { it !in listOf(0, 5, 15, 30, 60, 1440) }.forEach { minutes -> InputChip(true, { chooseReminders(chosenReminders() - minutes) }, { Text(t("%1\$s Min. vorher", minutes)) }, trailingIcon = { Icon(Icons.Close, t("Entfernen"), Modifier.size(16.dp)) }) }
                            AssistChip({ customReminder = true }, { Text(t("Eigene Zeit")) }, leadingIcon = { Icon(Icons.Add, null, Modifier.size(16.dp)) }, enabled = chosenReminders().size < 5)
                        }
                        Text(t("Bis zu fünf Erinnerungen, maximal vier Wochen vorher."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (allDay && chosenReminders().isNotEmpty()) OutlinedButton({ timeField = "reminder" }) { Icon(Icons.Clock, null); Spacer(Modifier.width(8.dp)); Text(t("Ganztags-Erinnerung um %1\$s", reminderClock)) }
                        if (chosenReminders().isNotEmpty()) Text(t("Android-Benachrichtigungen müssen erlaubt sein. Ohne Freigabe genauer Alarme kann Android die Erinnerung verzögern."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!allDay) item {
                        TextButton({ zonePicker = true }) { Text(t("Zeitzone: %1\$s", zone)) }
                        Text(t("Wiederholungen behalten ihre Uhrzeit in dieser Zeitzone. Beim Sommerzeitwechsel werden nicht vorhandene Uhrzeiten nach vorn verschoben."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (customReminder) {
            var amount by remember { mutableStateOf("10") }
            var unit by remember { mutableIntStateOf(1) }
            val minutes = amount.toIntOrNull()?.toLong()?.times(unit)?.takeIf { it in 0..40320 }?.toInt()
            AlertDialog(onDismissRequest = { customReminder = false }, title = { Text(t("Eigene Erinnerung")) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(amount, { amount = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text(t("Vorlauf")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = minutes == null)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1 to "Minuten", 60 to "Stunden", 1440 to "Tage").forEach { (value, label) -> FilterChip(unit == value, { unit = value }, { Text(t(label)) }) } }
                }
            }, confirmButton = { TextButton({ minutes?.let { chooseReminders(chosenReminders() + it) }; customReminder = false }, modifier = Modifier.testTag("custom-reminder-add"), enabled = minutes != null && chosenReminders().size < 5) { Text(t("Hinzufügen")) } }, dismissButton = { TextButton({ customReminder = false }) { Text(t("Abbrechen")) } })
        }
        if (templatePicker) AlertDialog(onDismissRequest = { templatePicker = false }, title = { Text(t("Vorlage wählen")) }, text = {
            LazyColumn { items(templates, key = { it.id }) { template -> TextButton({
                val draft = template.draft(start.toLocalDate())
                title = draft.title; startText = draft.start; endText = draft.end; zone = draft.zone; allDay = draft.allDay; calendarId = draft.calendarId
                location = draft.location; notes = draft.notes; repeat = draft.repeat.name; interval = draft.interval.toString(); until = draft.until; reminder = draft.reminderMinutes; additionalReminders = draft.additionalReminders; reminderClock = draft.allDayReminderTime; categoryId = draft.categoryId
                templatePicker = false
            }, Modifier.fillMaxWidth()) { Text(template.name) } } }
        }, confirmButton = { TextButton({ templatePicker = false }) { Text(t("Abbrechen")) } })
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(t("Änderungen verwerfen?")) }, text = { Text(t("Deine Eingaben wurden noch nicht gespeichert.")) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("Verwerfen")) } }, dismissButton = { TextButton({ discard = false }) { Text(t("Weiter bearbeiten")) } })
        dateField?.let { field ->
            val initial = when (field) { "start" -> start.toLocalDate(); "end" -> if (allDay) end.toLocalDate().minusDays(1) else end.toLocalDate(); else -> LocalDate.parse(until) }
            DatePickerDialog(initial, { dateField = null }) { date ->
                when (field) {
                    "start" -> changeStart(date.atTime(start.toLocalTime()))
                    "end" -> endText = (if (allDay) date.plusDays(1).atStartOfDay() else date.atTime(end.toLocalTime())).toString()
                    else -> until = date.toString()
                }
                dateField = null
            }
        }
        timeField?.let { field ->
            val value = when (field) { "start" -> start; "reminder" -> start.toLocalDate().atTime(LocalTime.parse(reminderClock)); else -> end }
            val time = rememberTimePickerState(value.hour, value.minute, true)
            AlertDialog(onDismissRequest = { timeField = null }, title = { Text(if (field == "start") t("Beginn") else t("Ende")) },
                text = { TimeInput(time) }, confirmButton = { TextButton({
                    val chosen = value.toLocalDate().atTime(time.hour, time.minute)
                    if (field == "start") changeStart(chosen) else if (field == "reminder") reminderClock = chosen.toLocalTime().toString() else endText = chosen.toString()
                    timeField = null
                }) { Text(t("Übernehmen")) } }, dismissButton = { TextButton({ timeField = null }) { Text(t("Abbrechen")) } })
        }
        if (zonePicker) ZonePicker(zone, { zonePicker = false }) { zone = it; zonePicker = false }
    }
}

@Composable
private fun DateTimeRow(label: String, date: LocalDateTime, allDay: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    val t = texts()
    val locale = t.locale
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onDate, Modifier.weight(1f)) { Text(date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))) }
        if (!allDay) { Spacer(Modifier.width(8.dp)); OutlinedButton(onTime) { Text(date.format(DateTimeFormatter.ofPattern("HH:mm"))) } }
    }
}

@Composable
private fun ZonePicker(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val t = texts()
    val locale = t.locale
    var query by remember { mutableStateOf("") }
    val zones = remember(query) { ZoneId.getAvailableZoneIds().sorted().filter { it.contains(query, ignoreCase = true) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("Zeitzone wählen")) }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text(t("Stadt oder Region suchen")) }, singleLine = true)
            LazyColumn(Modifier.height(300.dp)) { items(zones) { zone -> TextButton({ onSelect(zone) }, Modifier.fillMaxWidth()) { Text(if (zone == current) "✓ $zone" else zone) } } }
        }
    }, confirmButton = { TextButton(onDismiss) { Text(t("Schließen")) } })
}
