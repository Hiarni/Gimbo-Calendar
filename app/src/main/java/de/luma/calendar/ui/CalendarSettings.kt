package de.luma.calendar.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.luma.calendar.CalendarState
import de.luma.calendar.data.HolidayStore
import de.luma.calendar.model.*
import de.luma.calendar.i18n.*
import de.luma.calendar.reminders.ReminderScheduler

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarSettings(state: CalendarState, onSettings: (AppSettings) -> Unit, onBack: () -> Unit, onNotifications: () -> Unit,
    onPrecise: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onIcs: () -> Unit, onWidget: (Boolean) -> Unit, onLanguage: (AppLanguage) -> Unit, onHolidayImport: () -> Unit, onFetchHolidays: (Int) -> Unit, onRemoveHolidayImport: () -> Unit, onManageCalendars: () -> Unit, onManageSeries: () -> Unit, onEventImport: () -> Unit = {}, onDeviceCalendars: () -> Unit = {}, onManageTemplates: () -> Unit = {}, onContactBirthdays: () -> Unit = {}, onManageSources: () -> Unit = {}, onIntegrations: () -> Unit = {}, onManageCategories: () -> Unit = {}, onSharedCalendars: () -> Unit = {}) {
    val t = texts()
    val locale = t.locale
    val settings = state.settings
    val context = LocalContext.current
    var privacy by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var permissionsRevision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { permissionsRevision++; onPauseOrDispose { } }
    val notifications = remember(permissionsRevision) { ReminderScheduler.notificationsAllowed(context) }
    val precise = remember(permissionsRevision) { ReminderScheduler.precise(context) }
    if (privacy) PrivacyInformation { privacy = false }
    if (licenses) OpenSourceLicenses { licenses = false }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Back, t("Zurück")) }
            Text(t("Ganz dein Stil."), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(modifier = Modifier.testTag("settings-list"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                SectionLabel(t("SPRACHE"))
                LanguagePicker(settings.language, onLanguage)
                Text(t("Deutsch und Englisch sind vollständig übersetzt. Weitere Sprachen haben übersetzte Hauptnavigation und Bedienelemente; übrige Texte erscheinen auf Englisch."), style = MaterialTheme.typography.bodySmall)
            }
            item { HolidaySettings(state, onSettings, onHolidayImport, onFetchHolidays, onRemoveHolidayImport) }
            item {
                SectionLabel(t("DARSTELLUNG"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.entries.forEach { mode -> FilterChip(settings.theme == mode, { onSettings(settings.copy(theme = mode)) }, { Text(t(mode.label)) }) } }
                Text(t("Akzentfarbe"), style = MaterialTheme.typography.titleSmall)
                ColorPicker(settings.accent, !state.busy) { onSettings(settings.copy(accent = it)) }
                Text(t("Monatsdarstellung"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MonthStyle.entries.forEach { mode -> FilterChip(settings.monthStyle == mode, { onSettings(settings.copy(monthStyle = mode)) }, { Text(t(mode.label)) }) } }
                SettingSwitch(t("Feiertage hervorheben"), settings.highlightHolidays) { onSettings(settings.copy(highlightHolidays = it)) }
                SettingSwitch(t("Wochenenden hervorheben"), settings.highlightWeekends) { onSettings(settings.copy(highlightWeekends = it)) }
                SettingSwitch(t("Terminanzahl anzeigen"), settings.eventCounts) { onSettings(settings.copy(eventCounts = it)) }
            }
            item {
                SectionLabel(t("DEIN KALENDER"))
                OutlinedButton(onManageCalendars, Modifier.fillMaxWidth()) { Text(t("Kalender verwalten")) }
                OutlinedButton(onIntegrations, Modifier.fillMaxWidth()) { Text(t("Verbindungen & Erweiterungen")) }
                OutlinedButton(onManageCategories, Modifier.fillMaxWidth()) { Text(t("Kategorien")) }
                OutlinedButton(onSharedCalendars, Modifier.fillMaxWidth()) { Text(t("Gemeinsam planen")) }
                OutlinedButton(onManageSources, Modifier.fillMaxWidth()) { Text(t("Verbundene Gerätekalender")) }
                OutlinedButton(onManageTemplates, Modifier.fillMaxWidth()) { Text(t("Vorlagen")) }
                OutlinedButton(onManageSeries, Modifier.fillMaxWidth()) { Text(t("Serien & Einzeländerungen")) }
                SettingSwitch(t("Woche beginnt am Montag"), settings.mondayFirst) { onSettings(settings.copy(mondayFirst = it)) }
                SettingSwitch(t("ISO-Kalenderwochen anzeigen"), settings.weekNumbers) { onSettings(settings.copy(weekNumbers = it)) }
                Text(t("Startansicht"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CalendarView.entries.forEach { view -> FilterChip(settings.startView == view, { onSettings(settings.copy(startView = view)) }, { Text(t(view.label)) }) } }
            }
            item {
                SectionLabel(t("AUF DEINEM HOMESCREEN"))
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(t("Deine Pläne im Blick."), fontWeight = FontWeight.SemiBold)
                        Text(t("Monat, Woche, Tag oder Agenda – individuell für jedes Widget."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton({ onWidget(false) }, Modifier.fillMaxWidth()) { Text(t("Agenda-Widget hinzufügen")) }
                        OutlinedButton({ onWidget(true) }, Modifier.fillMaxWidth()) { Text(t("Monats-Widget hinzufügen")) }
                        Text(t("Ansicht, Kalender, Transparenz, Farben, Schriftgröße, Abstände und Aufgaben lassen sich pro Widget einstellen. Größe und Position änderst du auf dem Startbildschirm."), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                SectionLabel(t("ERINNERUNGEN"))
                Text(if (notifications) t("Benachrichtigungen sind erlaubt.") else t("Benachrichtigungen sind noch nicht erlaubt."), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onNotifications, enabled = !notifications) { Text(if (notifications) t("Benachrichtigungen aktiv") else t("Benachrichtigungen erlauben")) }
                Text(if (precise) t("Genaue Alarme sind erlaubt.") else t("Android kann Erinnerungen derzeit verzögern."), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onPrecise, enabled = !precise) { Text(if (precise) t("Genaue Alarme aktiv") else t("Genaue Alarme erlauben")) }
                Text(t("Bei Geräten mit starker Akkuoptimierung zusätzlich die Android-Einstellungen für Gimbo Kalender prüfen. Erinnerungen werden nach Neustart und Uhrzeitänderungen neu geplant."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SectionLabel(t("SICHERN & MITNEHMEN"))
                Text(t("Die Gimbo-Sicherung enthält lokale Kalender, Kategorien, Termine mit Serien und Einzeländerungen, Notizen und Checklisten, Aufgaben mit Erinnerungen, Vorlagen und Abfahrtserinnerungen. Für Anhangdateien die ZIP-Sicherung unter Verbindungen & Erweiterungen verwenden."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onBackup, Modifier.fillMaxWidth(), enabled = !state.busy && !state.loading && state.error == null) { Text(t("Terminsicherung speichern")) }
                OutlinedButton(onRestore, Modifier.fillMaxWidth(), enabled = !state.busy && !state.loading && state.error == null) { Text(t("Gimbo-Sicherung importieren")) }
                OutlinedButton(onContactBirthdays, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(t("Geburtstage aus Kontakten importieren")) }
                Text(t("Geburtstage werden nach Kontaktfreigabe als lokale jährliche Termine übernommen. Erneuter Import erzeugt Duplikate; Kontakte werden nicht verändert. Schalttage erscheinen alle vier Jahre."), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onDeviceCalendars, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(t("Gerätekalender importieren")) }
                OutlinedButton(onEventImport, Modifier.fillMaxWidth(), enabled = !state.busy && !state.loading && state.error == null) { Text(t("Termine importieren (.ics)")) }
                OutlinedButton(onIcs, Modifier.fillMaxWidth(), enabled = !state.busy && !state.loading && state.error == null) { Text(t("Kalender exportieren (.ics)")) }
                Text(t("ICS enthält vollständige Serien, Einzeländerungen und IANA-Zeitzonen. Unterstützt werden tägliche, wöchentliche, monatliche und jährliche Serien. Andere Regeln werden beim Import erklärt. Erneuter Import fügt Termine nochmals hinzu. Den Speicherort wählst du selbst."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedButton({ privacy = true }, Modifier.fillMaxWidth()) { Text(t("Datenschutz & Datenquellen")) }
                OutlinedButton({ licenses = true }, Modifier.fillMaxWidth()) { Text(t("Open-Source-Lizenzen")) }
                SectionLabel(t("ENTWICKLUNGSFASSUNG · %1\$s", de.luma.calendar.BuildConfig.VERSION_NAME))
                Text(t("Alle Funktionen sind frei nutzbar. Kein Gimbo Kalender-Konto nötig. Gerätekalender verwenden vorhandene Android-Konten."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t("© 2026 Hiarni · Alle Rechte vorbehalten."), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable
internal fun SectionLabel(text: String) { Text(text, Modifier.padding(bottom = 10.dp), fontSize = 11.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable
internal fun SettingSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, change) }
}
