package de.luma.calendar.ui

import android.content.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.integrations.*
import de.luma.calendar.model.*

@Composable fun AttachmentSection(event: Event, state: CalendarState, model: CalendarViewModel) {
    val context = LocalContext.current; val t = texts(); var error by remember { mutableStateOf(false) }; var remove by remember { mutableStateOf<EventAttachment?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.attach(event.id, uri) } }
    val attachments = state.attachments.filter { it.eventId == event.id }
    if (event.id > 0) {
        HorizontalDivider(); Text(t("Anhänge"), style = MaterialTheme.typography.titleMedium)
        if (event.repeat != Repeat.NONE) Text(t("Anhänge gehören zur gesamten Serie."), style = MaterialTheme.typography.bodySmall)
        attachments.forEach { a ->
            Text(a.name, style = MaterialTheme.typography.bodyMedium)
            Row {
                fun open(share: Boolean) { try {
                    val uri = AttachmentStore(context).uri(a.fileId)
                    val intent = if (share) Intent(Intent.ACTION_SEND).setType(a.mime).putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); intent.clipData = ClipData.newRawUri(a.name, uri)
                    context.startActivity(Intent.createChooser(intent, t(if (share) "Anhang teilen" else "Anhang öffnen")))
                } catch (_: Exception) { error = true } }
                TextButton({ open(false) }) { Text(t("Öffnen")) }; TextButton({ open(true) }) { Text(t("Teilen")) }
                TextButton({ remove = a }, enabled = !state.busy) { Text(t("Entfernen")) }
            }
        }
        OutlinedButton({ picker.launch(AttachmentStore.TYPES.toTypedArray()) }, enabled = !state.busy && attachments.size < 20) { Text(t("Bild oder Dokument hinzufügen")) }
        Text(t("Private Kopien. Originale bleiben erhalten. Für Anhänge die ZIP-Sicherung verwenden."), style = MaterialTheme.typography.bodySmall)
        if (error) Text(t("Anhang nicht verfügbar oder keine passende App gefunden."), color = MaterialTheme.colorScheme.error)
    }
    remove?.let { a -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(t("Anhang entfernen?")) }, text = { Text(a.name) }, confirmButton = { TextButton({ model.removeAttachment(a.fileId); remove = null }, enabled = !state.busy) { Text(t("Entfernen")) } }, dismissButton = { TextButton({ remove = null }) { Text(t("Abbrechen")) } }) }
}

@Composable fun TaskImportDialog(state: CalendarState, model: CalendarViewModel) {
    val t = texts(); val plan = state.pendingTasks ?: return
    var replace by remember(plan) { mutableStateOf(emptySet<String>()) }
    AlertDialog(onDismissRequest = model::dismissTasks, title = { Text(t("Aufgaben abgleichen")) }, text = {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(t("Neue Aufgaben werden hinzugefügt. Unveränderte Aufgaben werden aktualisiert. Lokale Änderungen bleiben erhalten, solange du ihre Übernahme nicht auswählst.")) }
            items(plan.size) { i -> val p = plan[i]
                Column {
                    Text(p.incoming.task.title)
                    Text(t(when (p.change) { TaskChange.NEW -> "Neu"; TaskChange.UPDATE -> "Aktualisieren"; TaskChange.UNCHANGED -> "Unverändert"; TaskChange.LOCAL_CHANGED -> "Lokal geändert"; TaskChange.CONFLICT -> "Beide Seiten geändert" }), style = MaterialTheme.typography.bodySmall)
                    if (p.change in setOf(TaskChange.LOCAL_CHANGED, TaskChange.CONFLICT)) {
                        fun summary(task: CalendarTask) = "${task.title} · ${task.due ?: t("Ohne Datum")} · ${task.reminderTime ?: t("Aus")} · ${t(task.repeat.label)} × ${task.interval} · ${task.anchor ?: "—"} · ${t(if (task.completed) "Erledigt" else "Nicht erledigt")}"
                        Text(t("Lokal: %1\$s · Datei: %2\$s", p.local?.let(::summary).orEmpty(), summary(p.incoming.task)), style = MaterialTheme.typography.bodySmall)
                        Row { Checkbox(p.incoming.uid in replace, { yes -> replace = if (yes) replace + p.incoming.uid else replace - p.incoming.uid }); Text(t("Datei übernehmen und lokale Aufgabe ersetzen")) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ model.confirmTasks(replace) }, enabled = !state.busy) { Text(t("Übernehmen")) } }, dismissButton = { TextButton(model::dismissTasks, enabled = !state.busy) { Text(t("Abbrechen")) } })
}
