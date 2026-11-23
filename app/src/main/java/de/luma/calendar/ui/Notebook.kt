package de.luma.calendar.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun TaskManager(state: CalendarState, model: CalendarViewModel, onNotifications: () -> Unit = {}, createRequest: Long = 0, showTasksRequest: Long = 0) {
    val t = texts()
    val context = LocalContext.current
    var tasks by rememberSaveable { mutableStateOf(false) }
    var archive by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<CalendarNote?>(null) }
    var removing by rememberSaveable { mutableStateOf<CalendarNote?>(null) }
    var adding by remember { mutableStateOf(false) }
    var taskRequestConsumed by rememberSaveable { mutableLongStateOf(0) }
    var completion by rememberSaveable { mutableLongStateOf(state.noteCompletion) }
    var consumed by rememberSaveable { mutableLongStateOf(0) }
    fun create(kind: NoteKind) {
        if (state.notes.size >= CalendarNote.MAX_COUNT || state.loading || state.calendars.isEmpty()) return
        model.clearNoteError()
        editing = CalendarNote(kind = kind, calendarId = state.calendars.firstOrNull { it.id == state.settings.defaultCalendarId }?.id ?: state.calendars.firstOrNull()?.id ?: 1)
    }
    LaunchedEffect(state.noteCompletion) { if (completion != state.noteCompletion) { completion = state.noteCompletion; editing = null } }
    LaunchedEffect(showTasksRequest) { if (showTasksRequest > taskRequestConsumed) { taskRequestConsumed = showTasksRequest; tasks = true } }
    LaunchedEffect(createRequest) { if (createRequest > consumed) { consumed = createRequest; tasks = false; create(NoteKind.TEXT) } }
    val tabStates = rememberSaveableStateHolder()
    val viewStates = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().testTag("notebook")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Notes, null, tint = MaterialTheme.colorScheme.primary)
            Text(t("Notizblock"), Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.headlineSmall)
            Box {
                IconButton({ adding = true }, Modifier.testTag("notebook-add"), enabled = !state.busy && !state.loading && state.notes.size < CalendarNote.MAX_COUNT) { Icon(Icons.Add, t("Neue Notiz")) }
                DropdownMenu(adding, { adding = false }) {
                    DropdownMenuItem({ Text(t("Textnotiz")) }, { adding = false; create(NoteKind.TEXT) }, leadingIcon = { Icon(Icons.Notes, null) })
                    DropdownMenuItem({ Text(t("Checkliste")) }, { adding = false; create(NoteKind.CHECKLIST) }, leadingIcon = { Icon(Icons.Check, null) })
                }
            }
        }
        TabRow(if (tasks) 1 else 0) {
            Tab(!tasks, { tasks = false }, text = { Text(t("Notizen")) }, modifier = Modifier.testTag("notebook-notes-tab"))
            Tab(tasks, { tasks = true }, text = { Text(t("Aufgaben")) }, modifier = Modifier.testTag("notebook-tasks-tab"))
        }
        tabStates.SaveableStateProvider(tasks) {
        if (tasks) Box(Modifier.weight(1f)) { TaskList(state, model, onNotifications) }
        else {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it.take(200) }, Modifier.weight(1f), label = { Text(t("Notizen suchen")) }, singleLine = true, leadingIcon = { Icon(Icons.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Close, t("Suche leeren")) } })
                IconButton({ grid = !grid }) { Icon(if (grid) Icons.Agenda else Icons.Overview, t(if (grid) "Liste" else "Raster")) }
            }
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(archive, { archive = !archive }, { Text(t("Archiv")) }, leadingIcon = { Icon(Icons.Archive, null, Modifier.size(18.dp)) })
                Spacer(Modifier.width(12.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(label == null, { label = null }, { Text(t("Alle")) })
                    state.notes.flatMap { it.labels }.distinct().sorted().forEach { value -> FilterChip(label == value, { label = if (label == value) null else value }, { Text(value) }) }
                }
            }
            val words = query.trim().lowercase(t.locale).split(Regex("\\s+")).filter(String::isNotBlank)
            val notes = state.notes.filter { note -> note.archived == archive && (label == null || label in note.labels) && words.all { (note.title + " " + note.body + " " + note.items.joinToString(" ") { it.text } + " " + note.labels.joinToString(" ")).lowercase(t.locale).contains(it) } }
            if (notes.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(t(if (query.isBlank() && label == null) "Noch keine Notizen." else "Keine passenden Notizen.")) }
            else {
                viewStates.SaveableStateProvider(grid) {
                    if (grid) LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f).testTag("notes-grid"), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(notes, key = { it.id }) { note -> NoteCard(note, state.busy, { model.clearNoteError(); editing = note }, { model.toggleNoteItem(note.id, it) }, { model.pinNote(note.id) }, { model.archiveNote(note.id) }, { shareNote(context, t, note) }, { removing = note }) }
                    } else LazyColumn(Modifier.weight(1f).testTag("notes-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(notes, key = { it.id }) { note -> NoteCard(note, state.busy, { model.clearNoteError(); editing = note }, { model.toggleNoteItem(note.id, it) }, { model.pinNote(note.id) }, { model.archiveNote(note.id) }, { shareNote(context, t, note) }, { removing = note }) }
                    }
                }
            }
        }
    }
    }
    editing?.let { note -> NoteEditor(note, state.calendars, state.busy, state.noteError, { editing = null; model.clearNoteError() }, model::saveNote) }
    removing?.let { note -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(t("Notiz entfernen?")) }, text = { Text(note.title.ifBlank { note.body.take(200) }) },
        confirmButton = { TextButton({ model.deleteNote(note.id); removing = null }, enabled = !state.busy) { Text(t("Entfernen")) } }, dismissButton = { TextButton({ removing = null }) { Text(t("Abbrechen")) } }) }
}

private fun shareNote(context: android.content.Context, t: Texts, note: CalendarNote) {
    val text = listOf(note.title, note.body, note.items.joinToString("\n") { (if (it.checked) "[x] " else "[ ] ") + it.text }).filter(String::isNotBlank).joinToString("\n\n")
    try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, note.title), t("Teilen"))) }
    catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, t("Keine passende App verfügbar."), Toast.LENGTH_SHORT).show() }
}

@Composable private fun NoteCard(note: CalendarNote, busy: Boolean, open: () -> Unit, toggle: (String) -> Unit, pin: () -> Unit, archive: () -> Unit, share: () -> Unit, remove: () -> Unit) {
    val t = texts()
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = open, shape = RoundedCornerShape(22.dp), color = Color(note.color).copy(alpha = .16f), border = BorderStroke(1.dp, Color(note.color).copy(alpha = .45f)), modifier = Modifier.fillMaxWidth().testTag("note-${note.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.title.ifBlank { t(if (note.kind == NoteKind.CHECKLIST) "Checkliste" else "Textnotiz") }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (note.pinned) Icon(Icons.Pin, t("Angeheftet"), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Box {
                    IconButton({ menu = true }, Modifier.size(48.dp)) { Icon(Icons.More, t("Notizaktionen"), Modifier.size(20.dp)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(t(if (note.pinned) "Loslösen" else "Anheften")) }, { menu = false; pin() }, enabled = !busy, leadingIcon = { Icon(Icons.Pin, null) })
                        DropdownMenuItem({ Text(t(if (note.archived) "Wiederherstellen" else "Archivieren")) }, { menu = false; archive() }, enabled = !busy, leadingIcon = { Icon(Icons.Archive, null) })
                        DropdownMenuItem({ Text(t("Teilen")) }, { menu = false; share() }, leadingIcon = { Icon(Icons.Share, null) })
                        DropdownMenuItem({ Text(t("Entfernen")) }, { menu = false; remove() }, enabled = !busy, leadingIcon = { Icon(Icons.Delete, null) })
                    }
                }
            }
            if (note.body.isNotBlank()) Text(note.body, maxLines = 7, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            note.items.take(4).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.checked, { toggle(item.id) }, enabled = !busy, modifier = Modifier.size(48.dp).testTag("note-item-${note.id}-${item.id}").semantics { contentDescription = item.text })
                    Text(item.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
            if (note.kind == NoteKind.CHECKLIST) Text(t("%1\$s von %2\$s erledigt", note.items.count { it.checked }, note.items.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (note.labels.isNotEmpty()) Text(note.labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NoteEditor(note: CalendarNote, calendars: List<UserCalendar>, busy: Boolean, remoteError: String?, onDismiss: () -> Unit, onSave: (CalendarNote) -> Unit) {
    val t = texts()
    var title by rememberSaveable { mutableStateOf(note.title) }
    var body by rememberSaveable { mutableStateOf(note.body) }
    var labels by rememberSaveable { mutableStateOf(note.labels.joinToString(", ")) }
    var items by rememberSaveable { mutableStateOf(note.items) }
    var color by rememberSaveable { mutableLongStateOf(note.color) }
    var calendar by rememberSaveable { mutableLongStateOf(note.calendarId) }
    var entry by rememberSaveable { mutableStateOf("") }
    var discard by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val changed = title != note.title || body != note.body || items != note.items || labels != note.labels.joinToString(", ") || color != note.color || calendar != note.calendarId || entry.isNotBlank()
    fun close() { if (!busy) { if (changed) discard = true else onDismiss() } }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ close() }, enabled = !busy) { Icon(Icons.Back, t("Zurück")) }
                    Text(t(if (note.id == 0L) "Neue Notiz" else "Notiz bearbeiten"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Button({ try {
                        val pending = if (entry.isNotBlank() && note.kind == NoteKind.CHECKLIST) items + NoteItem(text = entry) else items
                        onSave(note.copy(title = title, body = body, items = pending, labels = labels.split(',', '،').map(String::trim).filter(String::isNotBlank), color = color, calendarId = calendar).checked())
                    } catch (e: Exception) { error = t.error(e.message) } }, enabled = !busy) { Text(t("Speichern")) }
                }
                if (error != null || remoteError != null) Text(error ?: remoteError!!, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f).testTag("note-editor"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { OutlinedTextField(title, { title = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(t("Titel")) }, singleLine = true, enabled = !busy) }
                    item { OutlinedTextField(body, { body = it.take(20000) }, Modifier.fillMaxWidth(), label = { Text(t("Inhalt")) }, minLines = if (note.kind == NoteKind.TEXT) 6 else 2, enabled = !busy) }
                    if (note.kind == NoteKind.CHECKLIST) {
                        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(item.checked, { checked -> items = items.map { if (it.id == item.id) it.copy(checked = checked) else it } }, enabled = !busy)
                                    OutlinedTextField(item.text, { value -> items = items.map { if (it.id == item.id) it.copy(text = value.take(500)) else it } }, Modifier.weight(1f), label = { Text(t("Inhalt")) }, enabled = !busy)
                                    IconButton({ items = items.filterNot { it.id == item.id } }, enabled = !busy) { Icon(Icons.Delete, t("Eintrag entfernen")) }
                                }
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    IconButton({ items = items.toMutableList().also { java.util.Collections.swap(it, index, index - 1) } }, enabled = !busy && index > 0) { Icon(Icons.Up, t("Nach oben")) }
                                    IconButton({ items = items.toMutableList().also { java.util.Collections.swap(it, index, index + 1) } }, enabled = !busy && index < items.lastIndex) { Icon(Icons.Down, t("Nach unten")) }
                                }
                            }
                        }
                        item { Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(entry, { entry = it.take(500) }, Modifier.weight(1f).testTag("note-new-item"), label = { Text(t("Eintrag hinzufügen")) }, enabled = !busy)
                            IconButton({ items = items + NoteItem(text = entry.trim()); entry = "" }, enabled = !busy && entry.isNotBlank() && items.size < 200, modifier = Modifier.testTag("note-add-item")) { Icon(Icons.Add, t("Eintrag hinzufügen")) }
                        } }
                    }
                    item { OutlinedTextField(labels, { labels = it.take(420) }, Modifier.fillMaxWidth(), label = { Text(t("Etiketten (mit Komma trennen)")) }, enabled = !busy) }
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { calendars.forEach { c -> FilterChip(calendar == c.id, { calendar = c.id }, { Text(c.displayName { t(it) }) }, enabled = !busy) } } }
                    item { ColorPicker(color, !busy) { color = it } }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(t("Änderungen verwerfen?")) }, confirmButton = { TextButton(onDismiss) { Text(t("Verwerfen")) } }, dismissButton = { TextButton({ discard = false }) { Text(t("Weiter bearbeiten")) } })
}
