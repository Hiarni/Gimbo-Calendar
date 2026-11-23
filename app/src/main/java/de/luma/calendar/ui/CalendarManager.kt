package de.luma.calendar.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*

@Composable
fun CalendarManager(state: CalendarState, model: CalendarViewModel, onBack: () -> Unit) {
    val t = texts()
    var editing by rememberSaveable { mutableStateOf<UserCalendar?>(null) }
    var removing by rememberSaveable { mutableStateOf<Long?>(null) }
    var completion by rememberSaveable { mutableLongStateOf(state.calendarCompletion) }
    val defaultId = state.calendars.firstOrNull { it.id == state.settings.defaultCalendarId }?.id ?: state.calendars.firstOrNull()?.id
    LaunchedEffect(state.calendarCompletion) {
        if (completion != state.calendarCompletion) { editing = null; removing = null; completion = state.calendarCompletion }
    }
    fun open(calendar: UserCalendar) { model.clearCalendarError(); editing = calendar }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, enabled = !state.busy) { Icon(Icons.Back, t("Zurück")) }
            Text(t("Deine Kalender."), Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(t("Namen und Farben frei wählen. Der Standardkalender gilt für neue Termine; ein aktiver Filter hat Vorrang."), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ open(UserCalendar(name = "")) }, Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            enabled = !state.busy && !state.loading && state.error == null && state.calendars.size < UserCalendar.MAX_COUNT) {
            Icon(Icons.Add, null); Spacer(Modifier.width(8.dp)); Text(t("Kalender hinzufügen"))
        }
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.calendars, key = { it.id }) { calendar ->
                val name = calendar.displayName { t(it) }
                Surface(Modifier.fillMaxWidth().testTag("manage-calendar-${calendar.id}").clickable(enabled = !state.busy, onClickLabel = t("Kalender bearbeiten")) { open(calendar) },
                    shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(16.dp).background(Color(calendar.color), CircleShape))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(name, fontWeight = FontWeight.Medium)
                            Text(t("%1\$s Einträge", state.events.count { it.calendarId == calendar.id } + state.tasks.count { it.calendarId == calendar.id } + state.notes.count { it.calendarId == calendar.id } + state.templates.count { it.calendarId == calendar.id } + state.exceptions.count { it.calendarId == calendar.id }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (defaultId == calendar.id) Text(t("Standard für neue Termine"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        RadioButton(defaultId == calendar.id, { model.defaultCalendar(calendar.id) }, enabled = !state.busy,
                            modifier = Modifier.testTag("default-calendar-${calendar.id}").semantics { contentDescription = t("Standardkalender: %1\$s", name) })
                    }
                }
            }
            item { Text(t("Feiertage werden separat unter Feiertage verwaltet und bleiben schreibgeschützt."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.calendars.size >= UserCalendar.MAX_COUNT) item { Text(t("Maximal 100 Kalender."), style = MaterialTheme.typography.bodySmall) }
        }
    }
    editing?.let { calendar -> CalendarEditor(calendar, state.busy, state.calendarError, state.calendars.size > 1,
        onDismiss = { editing = null; model.clearCalendarError() }, onSave = model::saveCalendar,
        onDelete = { model.clearCalendarError(); removing = calendar.id }) }
    removing?.let { id ->
        val source = state.calendars.firstOrNull { it.id == id }
        if (source != null) CalendarRemoval(source, state, { removing = null; model.clearCalendarError() }) { target -> model.removeCalendar(id, target) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarEditor(calendar: UserCalendar, busy: Boolean, remoteError: String?, canDelete: Boolean,
    onDismiss: () -> Unit, onSave: (UserCalendar) -> Unit, onDelete: () -> Unit) {
    val t = texts()
    val originalName = calendar.displayName { t(it) }
    var name by rememberSaveable(calendar.id) { mutableStateOf(originalName) }
    var hex by rememberSaveable(calendar.id) { mutableStateOf("%06X".format(calendar.color and 0xFFFFFF)) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val chosen = hex.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }?.toLongOrNull(16)?.or(0xFF000000)
    val changed = name != originalName || chosen != calendar.color
    fun close() { if (!busy) { if (changed) discard = true else onDismiss() } }
    AlertDialog(onDismissRequest = { close() }, title = { Text(t(if (calendar.id == 0L) "Kalender hinzufügen" else "Kalender bearbeiten")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(60); error = null }, Modifier.fillMaxWidth(), label = { Text(t("Kalendername")) }, singleLine = true, isError = error != null || remoteError != null)
                Text(t("Kalenderfarbe"), style = MaterialTheme.typography.titleSmall)
                ColorPicker(chosen ?: calendar.color, !busy) { hex = "%06X".format(java.util.Locale.ROOT, it and 0xFFFFFF); error = null }
                OutlinedTextField(hex, { hex = it.removePrefix("#").take(6).uppercase(); error = null }, Modifier.fillMaxWidth(), label = { Text(t("Eigene Farbe (RRGGBB)")) },
                    prefix = { Text("#") }, singleLine = true)
                if (error != null || remoteError != null) Text(error ?: remoteError!!, color = MaterialTheme.colorScheme.error)
                if (calendar.id != 0L) {
                    TextButton(onDelete, enabled = canDelete && !busy) { Text(t("Kalender entfernen")) }
                    if (!canDelete) Text(t("Mindestens ein Kalender muss erhalten bleiben."), style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton({
            try {
                val value = calendar.copy(name = name, color = chosen ?: 0,
                    defaultCategory = if (name.trim() == originalName) calendar.defaultCategory else null).checked()
                error = null; onSave(value)
            } catch (failure: Exception) { error = t.error(failure.message) }
        }, enabled = !busy) { Text(t(if (busy) "Speichert…" else "Speichern")) } },
        dismissButton = { TextButton({ close() }, enabled = !busy) { Text(t("Abbrechen")) } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(t("Änderungen verwerfen?")) }, text = { Text(t("Deine Eingaben wurden noch nicht gespeichert.")) },
        confirmButton = { TextButton(onDismiss) { Text(t("Verwerfen")) } }, dismissButton = { TextButton({ discard = false }) { Text(t("Weiter bearbeiten")) } })
}

@Composable
private fun CalendarRemoval(calendar: UserCalendar, state: CalendarState, onDismiss: () -> Unit, onRemove: (Long) -> Unit) {
    val t = texts()
    val targets = state.calendars.filter { it.id != calendar.id }
    var target by rememberSaveable(calendar.id) { mutableLongStateOf(targets.firstOrNull()?.id ?: -1) }
    AlertDialog(onDismissRequest = { if (!state.busy) onDismiss() }, title = { Text(t("Kalender entfernen?")) }, text = {
        Column {
            Text(t("„%1\$s“ wird entfernt. Termine, Serien, Aufgaben und Vorlagen werden in den gewählten Kalender verschoben. Erinnerungen bleiben erhalten.", calendar.displayName { t(it) }))
            Text(t("Termine verschieben nach"), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.heightIn(max = 240.dp)) { items(targets, key = { it.id }) { destination ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { target = destination.id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(target == destination.id, { target = destination.id }, enabled = !state.busy)
                    Text(destination.displayName { t(it) })
                }
            } }
            state.calendarError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ onRemove(target) }, enabled = !state.busy && targets.any { it.id == target }) { Text(t("Verschieben & entfernen")) } },
        dismissButton = { TextButton(onDismiss, enabled = !state.busy) { Text(t("Abbrechen")) } })
}
