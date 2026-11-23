package de.luma.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.integrations.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SharedCalendarHub(state: CalendarState, model: CalendarViewModel, onAdd: () -> Unit) {
    val t = texts()
    val context = LocalContext.current
    val store = remember(context) { SharedCalendarStore(context) }
    var background by rememberSaveable { mutableStateOf(store.background) }
    var revision by remember { mutableIntStateOf(0) }
    var selectedPurpose by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<DeviceCalendar?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun open(intent: android.content.Intent) {
        try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { message = t("Keine passende App verfügbar.") }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("shared-calendars"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.People, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(t("Gemeinsam planen"), style = MaterialTheme.typography.headlineSmall)
                    Text(t("Partner, Familie und Team"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ model.refreshDeviceSources() }, enabled = !state.sourcesLoading) { Icon(Icons.Sync, t("Jetzt aktualisieren")) }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t("Ein Kalender für alle"), style = MaterialTheme.typography.titleLarge)
                    Text(t("Einen Kalender bei Google, Microsoft oder deinem CalDAV-Anbieter erstellen und dort Personen einladen. Danach das Konto auf Android einrichten und den freigegebenen Kalender hier verbinden."))
                    Button(onAdd, Modifier.fillMaxWidth(), enabled = !state.busy) { Icon(Icons.Add, null); Spacer(Modifier.width(8.dp)); Text(t("Kalender verbinden")) }
                    TextButton({ open(SharedCalendars.accountSettings()) }, Modifier.fillMaxWidth()) { Text(t("Konten und Synchronisierung")) }
                }
            }
        }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selectedPurpose == null, { selectedPurpose = null }, { Text(t("Alle")) })
            CalendarPurpose.entries.forEach { purpose -> FilterChip(selectedPurpose == purpose.name, { selectedPurpose = purpose.name }, { Text(t(purpose.label)) }) }
        } }
        if (state.sourcesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val sources = state.deviceSources.sources.filter { selectedPurpose == null || store.purpose(it.calendar).name == selectedPurpose }
        if (sources.isEmpty()) item { Text(t("Noch kein Kalender in dieser Gruppe. Verbinde einen Kalender und ordne ihn zu."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(sources, key = { it.calendar.identity }) { source ->
            val calendar = source.calendar
            val purpose = remember(calendar.identity, revision) { store.purpose(calendar) }
            val capability by produceState(CalendarCapabilities(), calendar.identity, state.deviceSources, revision) {
                value = withContext(Dispatchers.IO) { SharedCalendars.capabilities(context, calendar) }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(Color(calendar.color), CircleShape))
                        Text(calendar.name, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    if (calendar.account.isNotBlank()) Text(calendar.account, style = MaterialTheme.typography.bodySmall)
                    Text(t(source.status.label), color = if (source.status == SourceStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text(t(capability.access.label), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CalendarPurpose.entries.forEach { value ->
                        FilterChip(purpose == value, { store.purpose(calendar, value); revision++ }, { Text(t(value.label)) })
                    } }
                    if (source.status == SourceStatus.READY) {
                        if (capability.access in listOf(CalendarAccess.OWNER, CalendarAccess.EDITOR)) FilledTonalButton({ model.beginProviderEdit(calendar) }, enabled = !state.busy) { Text(t("Termin gemeinsam anlegen")) }
                        OutlinedButton({ model.syncDeviceCalendar(calendar) }, enabled = !state.busy) { Icon(Icons.Sync, null); Spacer(Modifier.width(8.dp)); Text(t("Anbieter synchronisieren")) }
                        TextButton({ open(SharedCalendars.sharing(calendar)) }) { Text(t(if (capability.access == CalendarAccess.OWNER) "Personen und Freigaben verwalten" else "Kalender beim Anbieter öffnen")) }
                    }
                    TextButton({ removing = calendar }, enabled = !state.busy) { Text(t("Verbindung entfernen")) }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("Aktuell bleiben"), style = MaterialTheme.typography.titleMedium)
                    Text(t("Änderungen erscheinen, sobald Android sie vom Anbieter empfängt. Während die App geöffnet ist, werden Kalenderänderungen beobachtet und regelmäßig neu gelesen. Die Verbindung und der Anbieter bestimmen, wie schnell andere Personen Änderungen sehen."), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("Abgleich im Hintergrund"), Modifier.weight(1f))
                        Switch(background, { value -> background = value; store.background = value; SharedCalendars.configureBackground(context) })
                    }
                    Text(t("Optional alle 15 Minuten einen Anbieterabgleich anfordern und Widgets erneuern. Android kann den Zeitpunkt verschieben. Lokale Kalender werden dadurch nicht geteilt; Gruppen sind persönliche Zuordnungen."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (message != null) item { Text(message!!, color = MaterialTheme.colorScheme.error) }
    }
    removing?.let { calendar -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(t("Verbindung entfernen?")) },
        text = { Text(t("Die Verbindung zu „%1\$s“ wird entfernt. Der Kalender und seine Termine bleiben beim Anbieter erhalten.", calendar.name)) },
        confirmButton = { TextButton({ model.disconnectDeviceCalendar(calendar.displayId); revision++; removing = null }, enabled = !state.busy) { Text(t("Verbindung entfernen")) } },
        dismissButton = { TextButton({ removing = null }) { Text(t("Abbrechen")) } }) }
}
