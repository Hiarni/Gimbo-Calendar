package de.luma.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.integrations.*
import java.time.format.DateTimeFormatter

@Composable fun DeviceSourceManager(state: CalendarState, model: CalendarViewModel, onBack: () -> Unit, onAdd: () -> Unit) {
    val t = texts()
    var remove by remember { mutableStateOf<DeviceCalendar?>(null) }
    Column(Modifier.fillMaxSize().testTag("device-source-manager")) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Back, t("Zurück")) }
            Text(t("Verbundene Gerätekalender"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(t("Vorhandene Android-Kalender anzeigen. Schreibbare Einzeltermine mit zusätzlicher Freigabe bearbeiten; Serien und Einladungen in der ursprünglichen App verwalten."))
                OutlinedButton(onAdd, Modifier.fillMaxWidth(), enabled = !state.busy && state.deviceSources.sources.size < DeviceCalendarLinksStore.MAX_SOURCES) { Text(t("Gerätekalender verbinden")) }
                OutlinedButton(model::refreshDeviceSources, Modifier.fillMaxWidth(), enabled = !state.sourcesLoading) { Text(t("Jetzt aktualisieren")) }
                if (state.sourcesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.deviceSources.sources.isEmpty()) item { Text(t("Noch keine Gerätekalender verbunden.")) }
            items(state.deviceSources.sources, key = { it.calendar.displayId }) { source ->
                val calendar = source.calendar
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.testTag("device-source-${calendar.displayId}")) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(calendar.name, style = MaterialTheme.typography.titleMedium)
                        if (calendar.account.isNotBlank()) Text(calendar.account, style = MaterialTheme.typography.bodySmall)
                        Text(t(source.status.label), color = if (source.status == SourceStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        if (source.status == SourceStatus.NO_PERMISSION) TextButton(onAdd) { Text(t("Kalenderfreigabe erlauben")) }
                        if (source.status == SourceStatus.READY) TextButton({ model.beginProviderEdit(calendar) }, enabled = !state.busy) { Text(t("Termin im Gerätekalender anlegen")) }
                        TextButton({ remove = calendar }, enabled = !state.busy) { Text(t("Verbindung entfernen")) }
                    }
                }
            }
            item {
                val format = DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(t.locale)
                Text(t("Angezeigter Zeitraum: %1\$s bis %2\$s", state.deviceSources.from.format(format), state.deviceSources.until.minusDays(1).format(format)), style = MaterialTheme.typography.bodySmall)
                Text(t("Ein Jahr vor und nach dem ausgewählten Datum. Widgets lesen die nächsten drei Monate. Höchstens 10 verbundene Quellen und insgesamt 10.000 Vorkommen pro Ansicht."), style = MaterialTheme.typography.bodySmall)
            }
            item { Text(t("Verbinden erzeugt keine lokalen Kopien. Die Gimbo-Sicherung enthält diese Quellen nicht. Ohne Lesefreigabe werden ihre Termine ausgeblendet; lokale Termine bleiben nutzbar."), style = MaterialTheme.typography.bodySmall) }
        }
    }
    remove?.let { calendar -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(t("Verbindung entfernen?")) },
        text = { Text(t("„%1\$s“ wird in Gimbo Kalender ausgeblendet. Der ursprüngliche Kalender und seine Termine bleiben erhalten.", calendar.name)) },
        confirmButton = { TextButton({ model.disconnectDeviceCalendar(calendar.displayId); remove = null }, enabled = !state.busy) { Text(t("Verbindung entfernen")) } },
        dismissButton = { TextButton({ remove = null }, enabled = !state.busy) { Text(t("Abbrechen")) } }) }
}
