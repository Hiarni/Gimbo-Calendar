package de.luma.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.luma.calendar.CalendarState
import de.luma.calendar.data.HolidayStore
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import java.time.LocalDate
import java.util.Locale

@Composable
fun HolidaySettings(state: CalendarState, onSettings: (AppSettings) -> Unit, onImport: () -> Unit,
    onFetch: (Int) -> Unit, onRemoveImport: () -> Unit) {
    val t = texts()
    val settings = state.settings
    var countryPicker by remember { mutableStateOf(false) }
    var regionPicker by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var yearText by rememberSaveable { mutableStateOf(LocalDate.now().year.toString()) }
    val year = yearText.toIntOrNull()?.takeIf { it in 1900..2100 }
    val countryName = countryName(settings.holidayCountry, t.locale)
    val regions = state.holidays.regions(settings.holidayCountry)
    val years = state.holidays.years(settings.holidayCountry)
    val imported = state.holidays.holidays.count { it.imported }
    val holidays = state.holidays.visible(settings.copy(holidays = true, importedHolidays = false)).filter { it.date.year == year }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("FEIERTAGE"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(t("Feiertage anzeigen"), Modifier.weight(1f)); Switch(settings.holidays, { onSettings(settings.copy(holidays = it)) })
        }
        OutlinedButton({ countryPicker = true }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(t("Land: %1\$s", countryName)) }
        TextButton({
            val code = AppLocale.systemLocale().country
            if (code.length == 2) onSettings(settings.copy(holidayCountry = code, holidayRegion = ""))
        }, enabled = !state.busy && AppLocale.systemLocale().country.length == 2) { Text(t("Geräteland übernehmen")) }
        OutlinedButton({ regionPicker = true }, Modifier.fillMaxWidth(), enabled = !state.busy && regions.isNotEmpty()) {
            Text(if (settings.holidayRegion.isEmpty()) t("Nur landesweite Feiertage") else regionName(settings.holidayRegion))
        }
        Text(t("Gemeindespezifische Feiertage und Sonderregeln können fehlen. Region nach dem ersten Abruf auswählen."), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(yearText, { yearText = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true,
                label = { Text(t("Jahr")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button({ year?.let(onFetch) }, enabled = year != null && settings.holidayCountry in HolidayStore.COUNTRIES && !state.busy) { Text(t(if (state.busy) "Lädt…" else "Feiertage abrufen")) }
        }
        Text(t("Feiertage werden erst beim Abrufen geladen. Nager.Date erhält Land, Jahr und deine IP-Adresse, keine Termine. Geladene Daten bleiben offline verfügbar."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (years.isEmpty()) t("Noch keine Feiertage geladen.") else t("Geladene Jahre: %1\$s", years.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
        if (years.isNotEmpty()) OutlinedButton({ preview = true }, enabled = year != null) { Text(t("%1\$s Feiertage · %2\$s", holidays.size, year ?: "")) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(t("Ganztägige ICS-Serien werden für fünf Jahre ab diesem Jahr übernommen. Einzeltermine behalten ihr Datum. Nicht unterstützte Regeln werden abgelehnt. Anbieterfeiertage verwenden deutsche Namen, soweit zugeordnet; sonst den englischen Namen."), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onImport, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(t("Feiertagsdatei importieren (.ics)")) }
        Text(t("Der Import ersetzt nur die Feiertagsdatei, keine persönlichen Termine. Für beliebige Länder nutzbar."), style = MaterialTheme.typography.bodySmall)
        if (imported > 0) {
            Text(t("Importierte Einträge: %1\$s", imported), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(t("Importierte Feiertage anzeigen"), Modifier.weight(1f)); Switch(settings.importedHolidays, { onSettings(settings.copy(importedHolidays = it)) })
            }
            TextButton(onRemoveImport, enabled = !state.busy) { Text(t("Importierte Feiertage entfernen")) }
        }
    }
    if (countryPicker) {
        var query by remember { mutableStateOf("") }
        val codes = (HolidayStore.COUNTRIES + settings.holidayCountry + AppLocale.systemLocale().country).filter { it.length == 2 }
            .sortedBy { countryName(it, t.locale) }.filter { countryName(it, t.locale).contains(query, true) || it.contains(query, true) }
        AlertDialog(onDismissRequest = { countryPicker = false }, title = { Text(t("Land wählen")) }, text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text(t("Suche")) }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 320.dp)) { items(codes) { code ->
                    TextButton({ onSettings(settings.copy(holidayCountry = code, holidayRegion = "")); countryPicker = false }, Modifier.fillMaxWidth()) {
                        Text(countryName(code, t.locale) + " · $code")
                    }
                } }
            }
        }, confirmButton = { TextButton({ countryPicker = false }) { Text(t("Schließen")) } })
    }
    if (regionPicker) AlertDialog(onDismissRequest = { regionPicker = false }, title = { Text(t("Bundesland / Region wählen")) }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            item { TextButton({ onSettings(settings.copy(holidayRegion = "")); regionPicker = false }) { Text(t("Nur landesweite Feiertage")) } }
            items(regions) { code -> TextButton({ onSettings(settings.copy(holidayRegion = code)); regionPicker = false }, Modifier.fillMaxWidth()) { Text(regionName(code)) } }
        }
    }, confirmButton = { TextButton({ regionPicker = false }) { Text(t("Schließen")) } })
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text(t("Vorschau") + " · $countryName · $yearText") }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp)) { items(holidays, key = { it.uid }) { holiday ->
            Column(Modifier.padding(vertical = 8.dp)) { Text(holiday.date.pretty(t.locale)); Text(holiday.event(t.locale).title, style = MaterialTheme.typography.bodySmall) }
        } }
    }, confirmButton = { TextButton({ preview = false }) { Text(t("Schließen")) } })
}
fun countryName(code: String, locale: Locale): String = Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }
fun regionName(code: String): String = (regionNames[code]?.let { "$it · " } ?: "") + code
private val regionNames = mapOf(
    "DE-BW" to "Baden-Württemberg", "DE-BY" to "Bayern", "DE-BE" to "Berlin", "DE-BB" to "Brandenburg",
    "DE-HB" to "Bremen", "DE-HH" to "Hamburg", "DE-HE" to "Hessen", "DE-MV" to "Mecklenburg-Vorpommern",
    "DE-NI" to "Niedersachsen", "DE-NW" to "Nordrhein-Westfalen", "DE-RP" to "Rheinland-Pfalz", "DE-SL" to "Saarland",
    "DE-SN" to "Sachsen", "DE-ST" to "Sachsen-Anhalt", "DE-SH" to "Schleswig-Holstein", "DE-TH" to "Thüringen",
    "AT-1" to "Burgenland", "AT-2" to "Kärnten", "AT-3" to "Niederösterreich", "AT-4" to "Oberösterreich",
    "AT-5" to "Salzburg", "AT-6" to "Steiermark", "AT-7" to "Tirol", "AT-8" to "Vorarlberg", "AT-9" to "Wien",
    "CH-AG" to "Aargau", "CH-AI" to "Appenzell Innerrhoden", "CH-AR" to "Appenzell Ausserrhoden", "CH-BE" to "Bern",
    "CH-BL" to "Basel-Landschaft", "CH-BS" to "Basel-Stadt", "CH-FR" to "Fribourg / Freiburg", "CH-GE" to "Genève",
    "CH-GL" to "Glarus", "CH-GR" to "Graubünden", "CH-JU" to "Jura", "CH-LU" to "Luzern", "CH-NE" to "Neuchâtel",
    "CH-NW" to "Nidwalden", "CH-OW" to "Obwalden", "CH-SG" to "St. Gallen", "CH-SH" to "Schaffhausen",
    "CH-SO" to "Solothurn", "CH-SZ" to "Schwyz", "CH-TG" to "Thurgau", "CH-TI" to "Ticino", "CH-UR" to "Uri",
    "CH-VD" to "Vaud", "CH-VS" to "Valais / Wallis", "CH-ZG" to "Zug", "CH-ZH" to "Zürich",
    "GB-ENG" to "England", "GB-SCT" to "Scotland", "GB-WLS" to "Wales", "GB-NIR" to "Northern Ireland",
)
