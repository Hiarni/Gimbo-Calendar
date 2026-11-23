package de.luma.calendar.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun CategoryManager(state: CalendarState, model: CalendarViewModel, onBack: () -> Unit) {
    val t = texts()
    var editing by remember { mutableStateOf<EventCategory?>(null) }
    var removing by remember { mutableStateOf<EventCategory?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Back, t("Zurück")) }
            Text(t("Kategorien"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton({ editing = EventCategory(name = "") }, enabled = !state.busy) { Icon(Icons.Add, t("Kategorie hinzufügen")) }
        }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(t("Kategorien ordnen Termine unabhängig vom Kalender. Farben machen Arbeit, Freizeit oder Gesundheit auf einen Blick erkennbar."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.categories.isEmpty()) item {
                Text(t("Noch keine Kategorien"), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Gesundheit", "Reise", "Sport", "Lernen").forEach { label -> AssistChip({ editing = EventCategory(name = t(label)) }, { Text(t(label)) }) }
                }
            }
            items(state.categories, key = { it.id }) { category ->
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(Color(category.color), CircleShape))
                        Text(category.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                        TextButton({ editing = category }, enabled = !state.busy) { Text(t("Bearbeiten")) }
                        IconButton({ removing = category }, enabled = !state.busy) { Icon(Icons.Delete, t("Entfernen")) }
                    }
                }
            }
        }
    }
    editing?.let { initial ->
        var name by remember(initial) { mutableStateOf(initial.name) }
        var color by remember(initial) { mutableLongStateOf(initial.color) }
        val duplicate = state.categories.any { it.id != initial.id && it.name.equals(name.trim(), true) }
        AlertDialog(onDismissRequest = { if (!state.busy) editing = null }, title = { Text(t(if (initial.id == 0L) "Kategorie hinzufügen" else "Kategorie bearbeiten")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text(t("Name")) }, singleLine = true, isError = duplicate)
                if (duplicate) Text(t("Diese Kategorie gibt es bereits."), color = MaterialTheme.colorScheme.error)
                ColorPicker(color, !state.busy) { color = it }
            }
        }, confirmButton = { TextButton({ model.saveCategory(initial.copy(name = name, color = color)); editing = null }, enabled = !state.busy && name.isNotBlank() && !duplicate) { Text(t("Speichern")) } }, dismissButton = { TextButton({ editing = null }, enabled = !state.busy) { Text(t("Abbrechen")) } })
    }
    removing?.let { value -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(t("Kategorie entfernen?")) }, text = { Text(t("„%1\$s“ wird von Terminen und Vorlagen entfernt. Die Termine bleiben erhalten.", value.name)) },
        confirmButton = { TextButton({ model.removeCategory(value.id); removing = null }, enabled = !state.busy) { Text(t("Entfernen")) } }, dismissButton = { TextButton({ removing = null }) { Text(t("Abbrechen")) } }) }
}
