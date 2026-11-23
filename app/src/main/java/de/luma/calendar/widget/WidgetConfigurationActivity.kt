package de.luma.calendar.widget

import android.appwidget.AppWidgetManager
import android.content.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import de.luma.calendar.data.*
import de.luma.calendar.i18n.*
import de.luma.calendar.model.*
import de.luma.calendar.ui.GimboTheme
import kotlinx.coroutines.*

class WidgetConfigurationActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if (id <= 0 || info?.provider?.packageName != packageName) { finish(); return }
        val store = WidgetSettingsStore(this)
        val initial = store.read(id)
        setContent {
            val t = texts()
            val calendars by produceState<List<UserCalendar>?>(null) { value = withContext(Dispatchers.IO) {
                CalendarDatabase.get(this@WidgetConfigurationActivity).calendars().all() + de.luma.calendar.integrations.DeviceCalendarLinksStore(this@WidgetConfigurationActivity).read().map { it.displayCalendar() }
            } }
            var options by rememberSaveable { mutableStateOf(initial) }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            GimboTheme(SettingsStore(this).read()) {
                Scaffold(topBar = { TopAppBar(title = { Text(t("Widget anpassen")) }, navigationIcon = { TextButton({ finish() }, enabled = !saving) { Text(t("Abbrechen")) } }) },
                    bottomBar = { Surface { Button({
                        saving = true
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.write(id, options); CalendarWidgets.update(this@WidgetConfigurationActivity) }
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = t("Widget konnte nicht gespeichert werden."); saving = false }
                        }
                    }, Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), enabled = calendars != null && !saving) { Text(t(if (saving) "Speichert…" else "Speichern")) } } }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item { WidgetStylePreview(options) }
                        item { Text(t("Diese Auswahl gilt nur für dieses Widget."), style = MaterialTheme.typography.bodySmall) }
                        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Darstellung", "Inhalt", "Kalender").forEachIndexed { index, label -> FilterChip(tab == index, { tab = index }, { Text(t(label)) }) }
                        } }
                        if (tab == 0) {
                            item { WidgetSection("Schnellvorlagen") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip({ options = options.copy(opacity = 100, palette = WidgetPalette.SYSTEM, ink = WidgetInk.AUTO, density = WidgetDensity.COMFORTABLE, textScale = 100, corners = 24) }, { Text(t("Klar")) })
                                AssistChip({ options = options.copy(opacity = 25, palette = WidgetPalette.DARK, ink = WidgetInk.LIGHT, corners = 28) }, { Text(t("Glas")) })
                                AssistChip({ options = options.copy(opacity = 0, ink = WidgetInk.LIGHT, density = WidgetDensity.COMPACT) }, { Text(t("Transparent")) })
                            } } }
                            item { WidgetSection("Ansicht") { WidgetChoices(WidgetLayout.entries, options.layout, { t(it.label) }) { options = options.copy(layout = it) } } }
                            item { WidgetSection("Farben") {
                                WidgetChoices(WidgetPalette.entries, options.palette, { t(it.label) }) { options = options.copy(palette = it) }
                                WidgetChoices(WidgetInk.entries, options.ink, { t(it.label) }) { options = options.copy(ink = it) }
                                Text(t("Bei transparentem Hintergrund die Schrift passend zum Hintergrundbild wählen."), style = MaterialTheme.typography.bodySmall)
                            } }
                            item { WidgetSection("Form und Größe") {
                                WidgetSlider(t("Hintergrunddeckung: %1\$s %%", options.opacity), options.opacity, 0..100, 19) { options = options.copy(opacity = it) }
                                WidgetSlider(t("Schriftgröße: %1\$s %%", options.textScale), options.textScale, 80..140, 11) { options = options.copy(textScale = it) }
                                WidgetSlider(t("Runde Ecken: %1\$s", options.corners), options.corners, 0..32, 7) { options = options.copy(corners = it) }
                                WidgetChoices(WidgetDensity.entries, options.density, { t(it.label) }) { options = options.copy(density = it) }
                                Text(t("Die Größe stellst du auf dem Startbildschirm durch langes Drücken und Ziehen ein. Der Inhalt passt sich der verfügbaren Breite und Höhe an."), style = MaterialTheme.typography.bodySmall)
                            } }
                        }
                        if (tab == 1) {
                            item { WidgetSection("Termine und Aufgaben") {
                                WidgetSlider(t("Höchstens %1\$s Einträge", options.entries), options.entries, 1..15, 13) { options = options.copy(entries = it) }
                                WidgetChoices(listOf(1, 7, 14, 30, 90), options.horizon, { t("%1\$s Tage", it) }) { options = options.copy(horizon = it) }
                                WidgetToggle("Aufgaben anzeigen", options.tasks) { options = options.copy(tasks = it) }
                                WidgetToggle("Kalendernamen anzeigen", options.calendarNames) { options = options.copy(calendarNames = it) }
                                WidgetToggle("Orte anzeigen", options.locations) { options = options.copy(locations = it) }
                                Text(t("Die verfügbare Widgethöhe begrenzt die Anzahl zusätzlich."), style = MaterialTheme.typography.bodySmall)
                            } }
                            item { WidgetSection("Bedienelemente") {
                                WidgetToggle("Kopfzeile anzeigen", options.header) { options = options.copy(header = it) }
                                WidgetToggle("Hinzufügen und Anpassen anzeigen", options.actions) { options = options.copy(actions = it) }
                                Text(t("Ausgeblendete Bedienelemente lassen sich über die Widget-Einstellungen des Startbildschirms wieder einschalten."), style = MaterialTheme.typography.bodySmall)
                            } }
                        }
                        if (tab == 2) {
                            item { WidgetSection("Kalenderauswahl") {
                                WidgetToggle("Alle Kalender", options.calendars == null) { options = options.copy(calendars = if (it) null else calendars.orEmpty().map { c -> c.id }.toSet()) }
                                WidgetToggle("Feiertage anzeigen", options.holidays) { options = options.copy(holidays = it) }
                            } }
                            if (options.calendars != null) items(calendars.orEmpty(), key = { it.id }) { c ->
                                WidgetSection(c.displayName { t(it) }, raw = true) { WidgetToggle(if (c.id < 0) "Gerätekalender" else "Anzeigen", c.id in options.calendars.orEmpty()) {
                                    options = options.copy(calendars = if (it) options.calendars.orEmpty() + c.id else options.calendars.orEmpty() - c.id)
                                } }
                            }
                        }
                        if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable private fun WidgetSection(label: String, raw: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val t = texts()
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (raw) label else t(label), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun <T> WidgetChoices(values: List<T>, value: T, label: (T) -> String, select: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { item -> FilterChip(value == item, { select(item) }, { Text(label(item)) }) } }
}
@Composable private fun WidgetToggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    val t = texts()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(t(label), Modifier.weight(1f)); Switch(value, change) }
}
@Composable private fun WidgetSlider(label: String, value: Int, range: IntRange, steps: Int, change: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(value.toFloat(), { change(kotlin.math.round(it).toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps)
}
@Composable private fun WidgetStylePreview(options: WidgetSettings) {
    val t = texts()
    val appDark = MaterialTheme.colorScheme.background.luminance() < .5f
    val dark = options.palette == WidgetPalette.DARK || options.palette == WidgetPalette.SYSTEM && appDark
    val lightInk = options.ink == WidgetInk.LIGHT || options.ink == WidgetInk.AUTO && dark
    val foreground = if (lightInk) Color(0xFFF4F4FA) else Color(0xFF20202A)
    val background = if (dark) Color(0xFF1D1D24) else Color(0xFFF8F7FA)
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF486782), Color(0xFF1F2938))), RoundedCornerShape(28.dp)).padding(16.dp)) {
        Column(Modifier.fillMaxWidth().background(background.copy(alpha = options.opacity / 100f), RoundedCornerShape(options.corners.dp)).padding(if (options.density == WidgetDensity.COMPACT) 12.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (options.header) Text(t("Gimbo Kalender"), color = foreground, fontSize = (18 * options.textScale / 100f).sp)
            Text(t("Vorschau · Beispieldaten"), color = foreground.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall)
            Text(t(options.layout.label), color = foreground, style = MaterialTheme.typography.labelMedium)
            Text("09:00  ·  " + t("Neuer Termin"), color = foreground, fontSize = (14 * options.textScale / 100f).sp)
            if (options.tasks) Text("○  " + t("Aufgabe"), color = foreground, fontSize = (14 * options.textScale / 100f).sp)
        }
    }
}
