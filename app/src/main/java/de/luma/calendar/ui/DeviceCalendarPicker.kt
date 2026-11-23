package de.luma.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.luma.calendar.integrations.DeviceCalendar
import de.luma.calendar.i18n.*

@Composable fun DeviceCalendarPicker(calendars: List<DeviceCalendar>, busy: Boolean, onDismiss: () -> Unit, onSelect: (DeviceCalendar) -> Unit, connect: Boolean = false, connected: Set<String> = emptySet()) {
    val t = texts()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(t("Gerätekalender auswählen")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t(if (connect) "Zeigt diesen Kalender schreibgeschützt an. Änderungen werden beim Öffnen und bei gemeldeten Kalenderänderungen aktualisiert. Erinnerungen übernimmt die ursprüngliche Kalender-App."
                else "Importiert einzelne Vorkommen der nächsten 12 Monate als lokale Kopie. Spätere Änderungen werden nicht synchronisiert. Erinnerungen bleiben beim ursprünglichen Kalender; die Kopie hat zunächst keine Erinnerung."))
            if (calendars.isEmpty()) Text(t("Keine Gerätekalender gefunden. Konten lassen sich in den Android-Einstellungen hinzufügen."))
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(calendars, key = { it.id }) { c -> OutlinedButton({ onSelect(c) }, Modifier.fillMaxWidth(), enabled = !busy && (!connect || c.identity !in connected)) { Column { Text(c.name); if (c.account.isNotBlank()) Text(c.account, style = MaterialTheme.typography.bodySmall); if (connect && c.identity in connected) Text(t("Bereits verbunden")) } } } }
        }
    }, confirmButton = { TextButton(onDismiss, enabled = !busy) { Text(t("Schließen")) } })
}
