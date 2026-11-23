package de.luma.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.luma.calendar.model.*
import de.luma.calendar.i18n.*
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.ui.platform.LocalContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetails(occurrence: Occurrence, busy: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
    onEditOccurrence: () -> Unit, onSkip: () -> Unit, onRestore: () -> Unit, onManage: () -> Unit, onEditFuture: () -> Unit = {}, onTemplate: (String) -> Unit = {}, sourceEventId: Long? = null, onEditSource: () -> Unit = {}, onDeleteSource: () -> Unit = {}, extras: @Composable () -> Unit = {}) {
    val t = texts()
    val locale = t.locale
    val context = LocalContext.current
    var integrationError by remember { mutableStateOf(false) }
    fun open(intent: Intent) { try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { integrationError = true } }
    var templateDialog by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf(occurrence.event.title.take(60)) }
    var action by remember { mutableStateOf<String?>(null) }
    val event = occurrence.event
    val linked = event.calendarId < 0
    val recurring = occurrence.originalStart != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(event.calendarDisplayName().uppercase(locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(event.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text((if (event.allDay) occurrence.start else occurrence.start.withZoneSameInstant(ZoneId.systemDefault())).format(DateTimeFormatter.ofPattern(if (locale.language == "de") "EEEE, d. MMMM yyyy" else "EEEE, d MMMM yyyy", locale)))
            Text(occurrence.timeLabel(t), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.allDay && occurrence.end.toLocalDate().minusDays(1) > occurrence.start.toLocalDate()) Text(t("Bis %1\$s", occurrence.end.toLocalDate().minusDays(1).format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))))
            if (!event.allDay) Text(t("Zeitzone: %1\$s", event.zone), style = MaterialTheme.typography.bodySmall)
            if (event.location.isNotBlank()) Text(t("Ort: %1\$s", event.location))
            de.luma.calendar.integrations.MeetingLinks.read(event.location, event.notes).forEach { link ->
                OutlinedButton({ open(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }, Modifier.fillMaxWidth()) { Text(t("Link öffnen · %1\$s", de.luma.calendar.integrations.MeetingLinks.label(link))) }
            }
            if (event.notes.isNotBlank()) Text(if (event.category == Category.HOLIDAY) t(event.notes) else event.notes)
            if (event.repeat != Repeat.NONE) {
                Text(t("%1\$s · Intervall %2\$s", t(event.repeat.label), event.interval) + (event.until?.let { " · " + t("Bis %1\$s", it) } ?: ""), color = MaterialTheme.colorScheme.primary)
            }
            if (occurrence.changed) Text(t("Einzeländerung einer Serie"), color = MaterialTheme.colorScheme.primary)
            LocalCategories.current.firstOrNull { it.id == event.categoryId }?.let { Text(t("Kategorie: %1\$s", it.name), style = MaterialTheme.typography.labelLarge) }
            event.reminderOffsets.forEach { minutes -> Text(if (minutes == 0) t("Erinnerung zum Beginn") else t("Erinnerung %1\$s Minuten vorher", minutes), style = MaterialTheme.typography.bodySmall) }
            if (event.allDay && event.reminderOffsets.isNotEmpty()) Text(t("Ganztags-Erinnerung um %1\$s", event.allDayReminderTime), style = MaterialTheme.typography.bodySmall)
            if (event.category == Category.HOLIDAY) {
                Text(t(if (event.notes.endsWith("OpenHolidays · ODbL 1.0")) "Diese Schulferien sind schreibgeschützt. Land, Region und Schulgruppe in den Erweiterungen ändern." else "Dieser Feiertag ist schreibgeschützt. Land und Region ändern oder Feiertage in den Einstellungen ausblenden."), style = MaterialTheme.typography.bodySmall)
                Text(t("Feiertagskalender"), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onDismiss, Modifier.fillMaxWidth()) { Text(t("Schließen")) }
            } else {
                if (linked) {
                    Text(t("Dieser Termin stammt aus einem verbundenen Gerätekalender. Erinnerungen und Kontosynchronisierung übernimmt der Android-Anbieter."), style = MaterialTheme.typography.bodySmall)
                    if (sourceEventId != null) OutlinedButton({ open(Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, sourceEventId))) }, Modifier.fillMaxWidth()) { Text(t("In Kalender-App öffnen")) }
                    OutlinedButton(onDuplicate, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Als lokalen Termin kopieren")) }
                    if (sourceEventId != null) {
                        Text(t("Mit zusätzlicher Schreibfreigabe lassen sich einfache Einzeltermine direkt im Gerätekalender ändern. Serien und Einladungen bleiben in der ursprünglichen App."), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onEditSource, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Im Gerätekalender bearbeiten")) }
                        TextButton(onDeleteSource, enabled = !busy) { Text(t("Im Gerätekalender entfernen")) }
                    }
                } else if (recurring) {
                    Button(onEditOccurrence, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Dieses Vorkommen bearbeiten")) }
                    OutlinedButton(onEditFuture, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Ab diesem Termin bearbeiten")) }
                    OutlinedButton(onEdit, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Gesamte Serie bearbeiten")) }
                    TextButton({ action = "skip" }, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Dieses Vorkommen auslassen")) }
                    if (occurrence.changed) TextButton({ action = "restore" }, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Original wiederherstellen")) }
                    TextButton(onManage, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Einzeländerungen ansehen")) }
                } else Button(onEdit, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Bearbeiten")) }
                if (!linked) OutlinedButton({ open(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, event.title).putExtra(CalendarContract.Events.DESCRIPTION, event.notes)
                    .putExtra(CalendarContract.Events.EVENT_LOCATION, event.location).putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, if (event.allDay) occurrence.start.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() else occurrence.start.toInstant().toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, if (event.allDay) occurrence.end.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() else occurrence.end.toInstant().toEpochMilli())) }, Modifier.fillMaxWidth()) { Text(t("In Android-Kalender übernehmen")) }
                OutlinedButton({ open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "${event.title}\n${occurrence.start.withZoneSameInstant(ZoneId.systemDefault()).toLocalDate()} · ${occurrence.timeLabel(t)}\n${event.location}\n${event.notes}"), t("Termin teilen"))) }, Modifier.fillMaxWidth()) { Text(t("Termin teilen")) }
                if (event.location.isNotBlank()) TextButton({ open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(event.location)))) }, Modifier.fillMaxWidth()) { Text(t("Ort in Karten öffnen")) }
                extras()
                if (integrationError) Text(t("Keine passende App gefunden."), color = MaterialTheme.colorScheme.error)
                if (!linked) {
                    TextButton({ templateDialog = true }, Modifier.fillMaxWidth()) { Text(t("Als Vorlage speichern")) }
                    OutlinedButton(onDuplicate, Modifier.fillMaxWidth(), enabled = !busy) { Text(t("Duplizieren")) }
                    TextButton({ action = "delete" }, Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Delete, null); Spacer(Modifier.width(8.dp)); Text(if (recurring) t("Gesamte Serie entfernen") else t("Termin entfernen")) }
                }
            }
        }
    }
    if (templateDialog) AlertDialog(onDismissRequest = { templateDialog = false }, title = { Text(t("Als Vorlage speichern")) }, text = { OutlinedTextField(templateName, { templateName = it.take(60) }, label = { Text(t("Name")) }) },
        confirmButton = { TextButton({ onTemplate(templateName); templateDialog = false }, enabled = !busy && templateName.isNotBlank()) { Text(t("Speichern")) } }, dismissButton = { TextButton({ templateDialog = false }) { Text(t("Abbrechen")) } })
    action?.let { selected -> AlertDialog(onDismissRequest = { if (!busy) action = null }, title = { Text(t(when (selected) {
        "skip" -> "Dieses Vorkommen auslassen?"; "restore" -> "Original wiederherstellen?"; else -> if (recurring) "Gesamte Serie entfernen?" else "Termin entfernen?"
    })) }, text = { Text(when (selected) {
        "skip" -> t("Nur dieses Vorkommen wird ausgelassen. Die übrige Serie bleibt erhalten.")
        "restore" -> t("Die Einzeländerung wird entfernt. Das Vorkommen folgt wieder der ursprünglichen Serie.")
        else -> t("„%1\$s“ wird endgültig entfernt.", event.title) + if (recurring) " " + t("Auch alle vergangenen und zukünftigen Wiederholungen werden entfernt.") else ""
    }) }, confirmButton = { TextButton({ when (selected) { "skip" -> onSkip(); "restore" -> onRestore(); else -> onDelete() } }, enabled = !busy) {
        Text(t(when (selected) { "skip" -> "Auslassen"; "restore" -> "Wiederherstellen"; else -> "Entfernen" }))
    } }, dismissButton = { TextButton({ action = null }, enabled = !busy) { Text(t("Abbrechen")) } }) }
}
