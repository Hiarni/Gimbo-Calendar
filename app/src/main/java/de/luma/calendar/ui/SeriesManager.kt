package de.luma.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SeriesManager(state: CalendarState, model: CalendarViewModel, onBack: () -> Unit, onEdit: (Event) -> Unit, onManage: (Event) -> Unit) {
    val t = texts()
    var deleting by remember { mutableStateOf<Event?>(null) }
    LaunchedEffect(state.editCompletion) { deleting = null }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onBack, enabled = !state.busy) { Icon(Icons.Back, t("Zurück")) }
            Text(t("Deine Serien."), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(t("Auch vollständig ausgelassene oder vergangene Serien bleiben hier erreichbar."), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.testTag("series-list"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val series = state.events.filter { it.repeat != Repeat.NONE }
            if (series.isEmpty()) item { Text(t("Noch keine Serien angelegt.")) }
            items(series, key = { it.id }) { event ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(16.dp)) {
                        Text(event.title, fontWeight = FontWeight.SemiBold)
                        Text(event.calendarDisplayName() + " · " + t(event.repeat.label), style = MaterialTheme.typography.bodySmall)
                        Text(t("%1\$s Einzeländerungen", state.exceptions.count { it.eventId == event.id }), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton({ onEdit(event) }, enabled = !state.busy, modifier = Modifier.testTag("edit-series-${event.id}")) { Text(t("Gesamte Serie bearbeiten")) }
                        TextButton({ onManage(event) }, enabled = !state.busy, modifier = Modifier.testTag("series-exceptions-${event.id}")) { Text(t("Einzeländerungen ansehen")) }
                        TextButton({ deleting = event }, enabled = !state.busy) { Text(t("Gesamte Serie entfernen")) }
                    }
                }
            }
        }
    }
    deleting?.let { event -> AlertDialog(onDismissRequest = { if (!state.busy) deleting = null }, title = { Text(t("Gesamte Serie entfernen?")) },
        text = { Text(t("Auch alle vergangenen und zukünftigen Wiederholungen werden entfernt.")) },
        confirmButton = { TextButton({ model.delete(event) }, enabled = !state.busy) { Text(t("Entfernen")) } },
        dismissButton = { TextButton({ deleting = null }, enabled = !state.busy) { Text(t("Abbrechen")) } }) }
}

@Composable
fun SeriesExceptions(parent: Event, changes: List<EventException>, busy: Boolean, onDismiss: () -> Unit, onRestore: (String) -> Unit) {
    val t = texts()
    var restoring by remember { mutableStateOf<String?>(null) }
    val format = DateTimeFormatter.ofPattern(if (t.locale.language == "de") "d. MMM yyyy, HH:mm" else "d MMM yyyy, HH:mm", t.locale)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(t("Einzeländerungen") + " · " + parent.title) }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp).testTag("exceptions-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (changes.isEmpty()) item { Text(t("Keine Einzeländerungen. Alle Vorkommen folgen der Serie.")) }
            items(changes, key = { it.originalStart }) { change ->
                Column {
                    Text(LocalDateTime.parse(change.originalStart).format(format), fontWeight = FontWeight.Medium)
                    Text(t(if (change.replacement == null) "Ausgelassen" else "Einzeln geändert"), style = MaterialTheme.typography.bodySmall)
                    change.replacement?.let { event -> Text(event.title + " · " + LocalDateTime.parse(event.start).format(format), style = MaterialTheme.typography.bodySmall) }
                    TextButton({ restoring = change.originalStart }, enabled = !busy, modifier = Modifier.testTag("restore-exception-${change.originalStart}")) { Text(t("Original wiederherstellen")) }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss, enabled = !busy) { Text(t("Schließen")) } })
    restoring?.let { start -> AlertDialog(onDismissRequest = { if (!busy) restoring = null }, title = { Text(t("Original wiederherstellen?")) },
        text = { Text(t("Die Einzeländerung wird entfernt. Das Vorkommen folgt wieder der ursprünglichen Serie.")) },
        confirmButton = { TextButton({ onRestore(start); restoring = null }, enabled = !busy) { Text(t("Wiederherstellen")) } },
        dismissButton = { TextButton({ restoring = null }, enabled = !busy) { Text(t("Abbrechen")) } }) }
}
