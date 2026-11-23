package de.luma.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TaskList(state: CalendarState, model: CalendarViewModel, onNotifications: () -> Unit = {}) {
    val t = texts()
    var help by remember { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf<CalendarTask?>(null) }
    var remove by rememberSaveable { mutableStateOf<CalendarTask?>(null) }
    var completed by rememberSaveable { mutableStateOf(false) }
    var calendar by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(t("Aufgaben"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall); IconButton({ help = true }, Modifier.semantics { contentDescription = t("Aufgabenhilfe") }) { Text("?") }; TextButton({ edit = CalendarTask(title = "", calendarId = state.calendars.firstOrNull { it.id == state.settings.defaultCalendarId }?.id ?: state.calendars.firstOrNull()?.id ?: 1) }) { Text(t("Neue Aufgabe")) } }
        Row { Text(t("Erledigte anzeigen"), Modifier.weight(1f)); Switch(completed, { completed = it }) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(calendar == null, { calendar = null }, { Text(t("Alle")) })
            state.calendars.forEach { c -> FilterChip(calendar == c.id, { calendar = if (calendar == c.id) null else c.id }, { Text(c.displayName { t(it) }) }) }
        }
        val tasks = state.tasks.filter { (completed || !it.completed) && (calendar == null || it.calendarId == calendar) }
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            if (tasks.isEmpty()) item { Text(t("Noch keine Aufgaben."), Modifier.padding(vertical = 24.dp)) }
            items(tasks, key = { it.id }) { task ->
                Row(Modifier.fillMaxWidth().testTag("task-${task.id}"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(task.completed, { model.finishTask(task) }, enabled = !state.busy)
                    TextButton({ edit = task }, Modifier.weight(1f)) { Column(Modifier.fillMaxWidth()) { Text(task.title); Text((task.due ?: t("Ohne Datum")) + if (task.repeat == Repeat.NONE) "" else " · ${t(task.repeat.label)}", style = MaterialTheme.typography.bodySmall, color = if (task.due != null && !task.completed && LocalDate.parse(task.due) < LocalDate.now()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } }
                    IconButton({ remove = task }, enabled = !state.busy) { Icon(Icons.Delete, t("Entfernen")) }
                }
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(t("Aufgabenhilfe")) }, text = { Text(t("Aufgaben können am Fälligkeitstag zur gewählten Uhrzeit erinnern. Bei wiederholenden Aufgaben setzt Erledigen das nächste Datum; fehlende Monatstage werden übersprungen. Android kann die Zustellung verzögern.")) }, confirmButton = { TextButton({ help = false }) { Text(t("Verstanden")) } })
    edit?.let { task -> TaskEditor(task, state.calendars, state.busy, { edit = null }) { model.saveTask(it); if (it.reminderTime != null) onNotifications(); edit = null } }
    remove?.let { task -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(t("Aufgabe entfernen?")) }, text = { Text(task.title) }, confirmButton = { TextButton({ model.deleteTask(task); remove = null }, enabled = !state.busy) { Text(t("Entfernen")) } }, dismissButton = { TextButton({ remove = null }) { Text(t("Abbrechen")) } }) }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TaskEditor(task: CalendarTask, calendars: List<UserCalendar>, busy: Boolean, onDismiss: () -> Unit, onSave: (CalendarTask) -> Unit) {
    val t = texts()
    var title by rememberSaveable { mutableStateOf(task.title) }
    var due by rememberSaveable { mutableStateOf(task.due) }
    var repeat by rememberSaveable { mutableStateOf(task.repeat.name) }
    var interval by rememberSaveable { mutableStateOf(task.interval.toString()) }
    var calendar by rememberSaveable { mutableLongStateOf(task.calendarId) }
    var reminder by rememberSaveable { mutableStateOf(task.reminderTime != null) }
    var reminderTime by rememberSaveable { mutableStateOf(task.reminderTime ?: "09:00") }
    var datePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t(if (task.id == 0L) "Neue Aufgabe" else "Aufgaben")) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(title, { title = it.take(200) }, label = { Text(t("Titel")) }) }
            if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            item { OutlinedButton({ datePicker = true }) { Text(due ?: t("Datum wählen")) }; if (due != null && repeat == Repeat.NONE.name) TextButton({ due = null }) { Text(t("Ohne Datum")) } }
            item { Row { Checkbox(reminder, { reminder = it; if (it && due == null) due = LocalDate.now().toString() }, modifier = Modifier.testTag("task-reminder-enabled")); Text(t("Aufgabenerinnerung")) }; if (reminder) OutlinedTextField(reminderTime, { reminderTime = it.take(5) }, label = { Text(t("Uhrzeit (HH:mm)")) }) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Repeat.entries.forEach { r -> FilterChip(repeat == r.name, { repeat = r.name; if (r != Repeat.NONE && due == null) due = LocalDate.now().toString() }, { Text(t(r.label)) }) } } }
            if (repeat != Repeat.NONE.name) item { OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(2) }, label = { Text(t("Intervall (1–99)")) }) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { calendars.forEach { c -> FilterChip(calendar == c.id, { calendar = c.id }, { Text(c.displayName { t(it) }) }) } } }
        }
    }, confirmButton = { TextButton({ try {
        val r = Repeat.valueOf(repeat); val i = if (r == Repeat.NONE) 1 else interval.toIntOrNull() ?: 0
        val changed = due != task.due || r != task.repeat || i != task.interval
        onSave(task.copy(title = title, due = due, repeat = r, interval = i, anchor = if (changed) due else task.anchor, completed = if (r != Repeat.NONE) false else task.completed, calendarId = calendar, reminderTime = if (reminder && due != null) reminderTime else null).checked())
    } catch (e: Exception) { error = t.error(e.message) } }, enabled = !busy) { Text(t("Speichern")) } }, dismissButton = { TextButton(onDismiss) { Text(t("Abbrechen")) } })
    if (datePicker) DatePickerDialog(due?.let(LocalDate::parse) ?: LocalDate.now(), { datePicker = false }) { due = it.toString(); datePicker = false }
}
