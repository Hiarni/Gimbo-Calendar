package de.luma.calendar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.integrations.*
import java.time.LocalDate

@Composable fun IntegrationSettings(state: CalendarState, model: CalendarViewModel, onBack: () -> Unit) {
    val t = texts(); val context = LocalContext.current; var picker by remember { mutableStateOf<String?>(null) }; var year by rememberSaveable { mutableStateOf(LocalDate.now().year.toString()) }
    val archive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(model::exportArchive) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::prepareArchive) }
    val tasksOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { it?.let(model::exportTasks) }
    val tasksIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::prepareTasks) }
    val s = state.settings; val school = state.school
    Column(Modifier.fillMaxSize()) {
        Row { IconButton(onBack) { Icon(Icons.Back, t("Zurück")) }; Text(t("Verbindungen & Erweiterungen"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionLabel(t("SCHULFERIEN"))
                SettingSwitch(t("Schulferien anzeigen"), s.schoolHolidays) { model.settings(s.copy(schoolHolidays = it)) }
                Text(t("OpenHolidays liefert regionale Ferien. Daten werden nur auf Abruf geladen; Abdeckung und Schulgruppen unterscheiden sich je Land."), style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ picker = "country" }) { Text(t("Land: %1\$s", countryName(s.schoolCountry, t.locale))) }
                OutlinedButton(model::loadSchoolMetadata, enabled = !state.busy) { Text(t("Länder, Regionen und Gruppen laden")) }
                OutlinedButton({ picker = "region" }) { Text(t("Region: %1\$s", school.regions.firstOrNull { it.code == s.schoolRegion }?.label(t.locale) ?: s.schoolRegion.ifBlank { t("Alle Regionen") })) }
                OutlinedButton({ picker = "group" }) { Text(t("Schulgruppe: %1\$s", school.groups.firstOrNull { it.code == s.schoolGroup }?.label(t.locale) ?: s.schoolGroup.ifBlank { t("Alle Gruppen") })) }
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text(t("Jahr")) })
                OutlinedButton({ model.fetchSchool(year.toInt()) }, enabled = !state.busy && year.toIntOrNull() in 1900..2100) { Text(t("Schulferien laden")) }
                Text(t("Geladene Jahre: %1\$s", school.loadedYears.joinToString().ifBlank { t("Keine") }), style = MaterialTheme.typography.bodySmall)
                if (school.loadedYears.isNotEmpty() && school.events(s.copy(schoolHolidays = true), t.locale).isEmpty()) Text(t("Für diese Auswahl meldet der Anbieter keine Ferien. Die Quelle deckt nicht jede Schule ab."))
                Text(t("Quelle: OpenHolidays · ODbL 1.0"), style = MaterialTheme.typography.bodySmall)
                TextButton({ runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openholidaysapi.org/"))) } }) { Text(t("Quelle und Datenlizenz öffnen")) }
            }
            item {
                SectionLabel(t("DATEIEN & GALERIE")); Text(t("Bilder, PDFs und Textdateien lassen sich in lokalen Termindetails hinzufügen. In der Galerie über Teilen → Gimbo Kalender einen neuen Termin aus einem Bild erstellen."))
                OutlinedButton({ archive.launch("Gimbo-Kalender-${LocalDate.now()}.zip") }, enabled = !state.busy && !state.loading) { Text(t("ZIP-Sicherung mit Anhängen speichern")) }
                OutlinedButton({ restore.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !state.busy) { Text(t("ZIP-Sicherung importieren")) }
                Text(t("ZIP enthält lokale Kalender, Termine, Serien, Aufgaben, Vorlagen, private Anhänge und Abfahrtserinnerungen. Verbindungen und Anbieter-Caches werden separat eingerichtet."), style = MaterialTheme.typography.bodySmall)
            }
            item {
                SectionLabel(t("AUFGABEN-AUSTAUSCH"))
                Text(t("VTODO-Dateien mit Tagesdatum und einfachen Wiederholungen austauschen. Wiederimport gleicht dieselben UIDs ab und schützt lokale Änderungen. Geeignete CalDAV-Aufgaben-Apps können diese Dateien übernehmen."))
                OutlinedButton({ tasksOut.launch("Gimbo-Kalender-Aufgaben.ics") }, enabled = !state.busy && !state.loading) { Text(t("Aufgaben exportieren (.ics)")) }
                OutlinedButton({ tasksIn.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream")) }, enabled = !state.busy && !state.loading) { Text(t("Aufgaben importieren (.ics)")) }
                Text(t("Der Dateiaustausch ersetzt keinen automatischen CalDAV-Aufgabensync."), style = MaterialTheme.typography.bodySmall)
            }
            item { OutlinedButton(model::clearWeather, enabled = !state.busy) { Text(t("Wettercache entfernen")) }; SectionLabel(t("REISE & WETTER")); Text(t("In Termindetails Route öffnen, eigene Fahrzeit mit Puffer und Abfahrtserinnerung festlegen. Wetter wird nur auf bewusste Abfrage für eingegebene Koordinaten oder einen freigegebenen aktuellen Standort geladen.")) }
        }
    }
    picker?.let { type ->
        val choices = when (type) { "country" -> school.countries.ifEmpty { listOf("DE", "AT", "CH").map { HolidayChoice(it, countryName(it, java.util.Locale.GERMAN), countryName(it, java.util.Locale.ENGLISH)) } }; "region" -> school.regions; else -> school.groups }
        AlertDialog(onDismissRequest = { picker = null }, title = { Text(t(if (type == "country") "Land wählen" else if (type == "region") "Region wählen" else "Schulgruppe wählen")) }, text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (type != "country") item { TextButton({ model.settings(if (type == "region") s.copy(schoolRegion = "") else s.copy(schoolGroup = "")); picker = null }) { Text(t(if (type == "region") "Alle Regionen" else "Alle Gruppen")) } }
                items(choices, key = { it.code }) { c -> TextButton({ model.settings(when (type) { "country" -> s.copy(schoolCountry = c.code, schoolRegion = "", schoolGroup = ""); "region" -> s.copy(schoolRegion = c.code); else -> s.copy(schoolGroup = c.code) }); picker = null }) { Text(c.label(t.locale) + " · " + c.code) } }
            }
        }, confirmButton = { TextButton({ picker = null }) { Text(t("Schließen")) } })
    }
}
