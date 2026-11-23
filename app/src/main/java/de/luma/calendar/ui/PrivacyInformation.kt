package de.luma.calendar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.luma.calendar.BuildConfig
import de.luma.calendar.i18n.texts

@Composable fun PrivacyInformation(onClose: () -> Unit) {
    val t = texts(); val context = LocalContext.current; var failed by remember { mutableStateOf(false) }
    fun open(url: String) { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { failed = true } }
    val policy = BuildConfig.PRIVACY_POLICY_URL.takeIf { runCatching { java.net.URI(it).let { u -> u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null && u.port in listOf(-1,443) } }.getOrDefault(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text(t("Datenschutz & Datenquellen")) }, text = {
        LazyColumn(Modifier.testTag("privacy-information").heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text(t("Deine Termine, Aufgaben und Anhangkopien liegen privat auf dem Gerät. Kein Gimbo Kalender-Konto oder eigener Server. Deinstallation löscht lokale Daten; bewusst exportierte Dateien bleiben bestehen.")) }
            item { Text(t("JSON, ZIP und ICS sind unverschlüsselt. Nur ZIP enthält Anhangdateien. Wähle den Speicherort bewusst; Android-Appbackup ist deaktiviert.")) }
            item { Text(t("Kalender, Kontakte und ungefährer Standort benötigen freiwillige Freigaben. Kontokalender und ihre Änderungen synchronisiert der Android-Anbieter. Anhänge werden nur aus gewählten Dateien kopiert.")) }
            item { Text(t("Auf bewussten Abruf: Nager erhält Land und Jahr, OpenHolidays zusätzlich Region und Schulgruppe, Nominatim den Suchort und die Sprache, MET Norway Wetterkoordinaten. Anbieter sehen auch IP-Adresse und technische Anfragedaten. Keine automatische Übertragung deiner Kontakte, Anhänge oder gesamten Termintexte.")) }
            item { Text(t("Wetter- und Ortssuchcache kannst du in Verbindungen & Erweiterungen entfernen. Karten, Besprechungslinks und Teilen öffnen externe Apps mit dem gewählten Inhalt; dort gelten deren Datenschutzregeln.")) }
            item { Text(t("Datenquellen: OpenHolidays und OpenStreetMap · ODbL 1.0; MET Norway · CC BY 4.0. Gimbo fasst Wetterwerte zusammen.")) }
            item { TextButton({ open("https://www.openholidaysapi.org/") }) { Text(t("OpenHolidays: Quelle und Lizenz")) }; TextButton({ open("https://www.openstreetmap.org/copyright") }) { Text(t("OpenStreetMap: Quelle und Lizenz")) }; TextButton({ open("https://api.met.no/doc/TermsOfService") }) { Text(t("MET Norway: Quelle und Bedingungen")) } }
            item { if (policy != null) TextButton({ open(policy) }) { Text(t("Datenschutzerklärung öffnen")) } else Text(t("Technische Datenschutzinformationen dieser Entwicklungsfassung. Herausgeberangaben und veröffentlichte Datenschutzerklärung werden vor Veröffentlichung ergänzt."), style = MaterialTheme.typography.bodySmall)
                if (BuildConfig.PUBLISHER_CONTACT.isNotBlank()) Text(BuildConfig.PUBLISHER_CONTACT)
                if (failed) Text(t("Keine passende App zum Öffnen gefunden."), color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = { TextButton(onClose) { Text(t("Schließen")) } })
}
