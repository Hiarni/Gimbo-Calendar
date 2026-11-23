package de.luma.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*

@Composable fun TemplateManager(state: CalendarState, model: CalendarViewModel, onBack: () -> Unit) {
    val t = texts(); var remove by remember { mutableStateOf<EventTemplate?>(null) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(onBack) { Text(t("Zurück")) }; Text(t("Vorlagen"), style = MaterialTheme.typography.headlineSmall)
        LazyColumn { if (state.templates.isEmpty()) item { Text(t("Noch keine Vorlagen. Im Termindetail lassen sich Vorlagen speichern.")) }
            items(state.templates, key = { it.id }) { template -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(template.name, Modifier.weight(1f)); IconButton({ remove = template }) { Icon(Icons.Delete, t("Entfernen")) } } }
        }
    }
    remove?.let { template -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(t("Vorlage entfernen?")) }, text = { Text(template.name) }, confirmButton = { TextButton({ model.deleteTemplate(template.id); remove = null }, enabled = !state.busy) { Text(t("Entfernen")) } }, dismissButton = { TextButton({ remove = null }) { Text(t("Abbrechen")) } }) }
}
