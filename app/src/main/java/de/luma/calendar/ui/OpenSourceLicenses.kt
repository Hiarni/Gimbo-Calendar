package de.luma.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luma.calendar.R
import de.luma.calendar.i18n.texts

private val libraries = listOf(
    "AndroidX Activity Compose 1.10.1",
    "AndroidX Compose UI, Foundation, Material 3 (BOM 2025.04.01)",
    "AndroidX Core KTX 1.16.0",
    "AndroidX Fragment 1.8.9",
    "AndroidX Lifecycle 2.9.0",
    "AndroidX Room 2.7.2",
    "AndroidX WorkManager 2.10.1",
    "Kotlin Standard Library, kotlinx.coroutines",
)

@Composable fun OpenSourceLicenses(onClose: () -> Unit) {
    val t = texts(); val context = LocalContext.current
    val license = remember { context.resources.openRawResource(R.raw.apache_license_2_0).bufferedReader().use { it.readText() } }
    AlertDialog(onDismissRequest = onClose, title = { Text(t("Open-Source-Lizenzen")) }, text = {
        LazyColumn(Modifier.testTag("open-source-licenses").heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(t("© 2026 Hiarni · Alle Rechte vorbehalten.")) }
            item { Text(t("Gimbo Kalender verwendet die folgenden Open-Source-Bibliotheken. Sie stehen unter der Apache License 2.0, deren vollständiger Text unten folgt.")) }
            items(libraries.size) { Text("• " + libraries[it], style = MaterialTheme.typography.bodySmall) }
            item { Text(t("Android ist eine Marke von Google LLC. Weitere genannte Marken gehören ihren jeweiligen Inhabern."), style = MaterialTheme.typography.bodySmall) }
            item { Text(license, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp) }
        }
    }, confirmButton = { TextButton(onClose) { Text(t("Schließen")) } })
}
