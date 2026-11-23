package de.luma.calendar.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.luma.calendar.*
import de.luma.calendar.i18n.*
import de.luma.calendar.integrations.*
import de.luma.calendar.model.*
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun TravelWeather(event: Event, state: CalendarState, model: CalendarViewModel, onNotifications: () -> Unit) {
    val t = texts(); val context = LocalContext.current; val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    var minutes by rememberSaveable(event.id) { mutableStateOf(state.travel.firstOrNull { it.eventId == event.id }?.minutes?.toString() ?: "30") }
    var buffer by rememberSaveable(event.id) { mutableStateOf(state.travel.firstOrNull { it.eventId == event.id }?.buffer?.toString() ?: "10") }
    val prefs = remember(context) { context.getSharedPreferences("weather-inputs", 0) }
    val savedPoint = remember(event.id) { prefs.getString(event.id.toString(), "").orEmpty() }
    var latitude by rememberSaveable(event.id) { mutableStateOf(savedPoint.substringBefore(',', "")) }; var longitude by rememberSaveable(event.id) { mutableStateOf(savedPoint.substringAfter(',', "")) }
    val routeLocation = remember(event.location) { MeetingLinks.read(event.location).fold(event.location) { text, link -> text.replace(link, "") }.trim(' ', '.', ',', '(', ')') }
    var search by rememberSaveable(event.id) { mutableStateOf(routeLocation.take(200)) }
    var places by remember { mutableStateOf<List<WeatherPlace>?>(null) }
    var coordinates by rememberSaveable(event.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }; var weather by remember(event.id, event.start) { mutableStateOf<WeatherForecast?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    val date = java.time.LocalDateTime.parse(event.start).toLocalDate()
    fun point() = WeatherPoint(latitude.replace(',', '.').toDouble(), longitude.replace(',', '.').toDouble()).checked()
    fun locate() { if (busy) return; busy = true; scope.launch {
        try { val p = WeatherStore.location(context); latitude = p.key.substringBefore(','); longitude = p.key.substringAfter(','); weather = null; error = null }
        catch (e: TimeoutCancellationException) { error = t("Kein Standort verfügbar. Koordinaten bitte eingeben.") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = t.error(e.message) }
        finally { busy = false }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) locate() else error = t("Ohne Standortfreigabe kannst du Koordinaten manuell eingeben.") }
    HorizontalDivider()
    TextButton({ expanded = !expanded }) { Text(t("Route, Abfahrt & Wetter")) }
    if (expanded) {
        if (routeLocation.isNotBlank()) {
            Text(t("Route in Karten-App planen"), style = MaterialTheme.typography.titleSmall)
            Row {
                listOf("driving" to "Auto", "transit" to "Öffentlicher Verkehr", "walking" to "Zu Fuß").forEach { (mode, label) ->
                    TextButton({ try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(routeLocation)}&travelmode=$mode"))) } catch (_: android.content.ActivityNotFoundException) { error = t("Keine passende App gefunden.") } }) { Text(t(label)) }
                }
            }
        }
        if (event.id > 0 && !event.allDay) {
            Text(t("Abfahrtserinnerung"), style = MaterialTheme.typography.titleSmall)
            if (state.events.any { it.id == event.id && it.repeat != Repeat.NONE }) Text(t("Diese Abfahrtserinnerung gilt für die gesamte Serie."), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text(t("Fahrzeit in Minuten")) })
            OutlinedTextField(buffer, { buffer = it.filter(Char::isDigit).take(4) }, label = { Text(t("Puffer in Minuten")) })
            val lead = (minutes.toIntOrNull() ?: 0) + (buffer.toIntOrNull() ?: -1)
            OutlinedButton({ model.saveTravel(TravelPlan(event.id, minutes.toInt(), buffer.toInt())); onNotifications() }, enabled = !state.busy && minutes.toIntOrNull() in 1..1440 && buffer.toIntOrNull() in 0..1440 && lead in 1..1440) { Text(t("Abfahrtserinnerung speichern")) }
            if (state.travel.any { it.eventId == event.id }) TextButton({ model.removeTravel(event.id) }, enabled = !state.busy) { Text(t("Abfahrtserinnerung entfernen")) }
            Text(t("Die Fahrzeit gibst du selbst ein. Gimbo erinnert vor Terminbeginn abzüglich Fahrzeit und Puffer; Android kann die Zustellung verzögern."), style = MaterialTheme.typography.bodySmall)
        }
        Text(t("Wetter am Terminort"), style = MaterialTheme.typography.titleSmall)
        Text(t("Koordinaten werden nur beim Abruf an MET Norway gesendet. Standort übernehmen setzt sie ein und startet noch keine Wetterabfrage."), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(search, { search = it.take(200) }, label = { Text(t("Wetterort suchen")) })
        Text(t("Der Suchort wird beim Suchen an OpenStreetMap Nominatim gesendet. Erst nach der Ortsauswahl wird Wetter auf Anfrage geladen."), style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ busy = true; error = null; scope.launch {
            try { places = withContext(Dispatchers.IO) { PlaceSearch(context).search(search, t.locale.language) }; if (places?.isEmpty() == true) error = t("Kein Ort gefunden. Suche genauer eingeben oder Koordinaten verwenden.") }
            catch(e: CancellationException) { throw e } catch(e: Exception) { error = t.error(e.message) } finally { busy = false }
        } }, enabled = !busy && search.trim().length >= 2) { Text(t("Ort suchen")) }
        TextButton({ coordinates = !coordinates }) { Text(t("Koordinaten bearbeiten")) }
        if (coordinates) { OutlinedTextField(latitude, { latitude = it.take(18); weather = null }, label = { Text(t("Breitengrad")) }); OutlinedTextField(longitude, { longitude = it.take(18); weather = null }, label = { Text(t("Längengrad")) }) }
        if (latitude.isNotBlank() && longitude.isNotBlank()) Text(t("Gewählte Position: %1\$s, %2\$s", latitude, longitude), style = MaterialTheme.typography.bodySmall)
        TextButton({ if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate() else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, enabled = !busy) { Text(t("Gerätestandort übernehmen")) }
        val valid = runCatching { point() }.isSuccess
        OutlinedButton({ busy = true; error = null; val p = point(); prefs.edit().putString(event.id.toString(), p.key).apply(); scope.launch {
            try { weather = withContext(Dispatchers.IO) { WeatherStore(context).fetch(p, date, event.zone) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { weather = withContext(Dispatchers.IO) { WeatherStore(context).read(p, date, event.zone) }; error = t.error(e.message) }
            finally { busy = false }
        } }, enabled = !busy && valid) { Text(t("Wetter für %1\$s laden", date.pretty(t.locale))) }
        TextButton({ val p = point(); busy = true; scope.launch { try { weather = withContext(Dispatchers.IO) { WeatherStore(context).read(p, date, event.zone) }; if (weather == null) error = t("Keine gespeicherte Wettervorhersage.") } finally { busy = false } } }, enabled = !busy && valid) { Text(t("Gespeichertes Wetter anzeigen")) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        weather?.let { w ->
            Text(t("Vorhersage verfügbarer Stunden: %1\$s bis %2\$s °C", String.format(t.locale, "%.1f", w.minimum), String.format(t.locale, "%.1f", w.maximum)))
            Text(if (w.rain == null) t("Keine Niederschlagsdaten verfügbar.") else t("Niederschlag in den verfügbaren Intervallen: %1\$s mm", String.format(t.locale, "%.1f", w.rain)))
            val f = DateTimeFormatter.ofPattern("d MMM HH:mm", t.locale).withZone(ZoneId.of(w.zone))
            Text(t("Zeitraum: %1\$s – %2\$s · %3\$s", f.format(w.from), f.format(w.until), w.zone), style = MaterialTheme.typography.bodySmall)
            Text(t("Abgerufen: %1\$s", f.format(w.fetched)), style = MaterialTheme.typography.bodySmall)
            if (w.fetched < Instant.now().minusSeconds(24 * 3600)) Text(t("Gespeicherte Vorhersage ist älter als 24 Stunden."), color = MaterialTheme.colorScheme.error)
        }
        Text(t("Ortssuche: OpenStreetMap Nominatim · ODbL 1.0"), style = MaterialTheme.typography.bodySmall)
        Text(t("Quelle: MET Norway · CC BY 4.0. Vorhersagen reichen nur begrenzt in die Zukunft; Wetter ist keine verbindliche Reiseauskunft."), style = MaterialTheme.typography.bodySmall)
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
    }
    places?.takeIf { it.isNotEmpty() }?.let { rows -> AlertDialog(onDismissRequest = { places = null }, title = { Text(t("Wetterort auswählen")) }, text = {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) { items(rows.size) { i -> val p=rows[i]; TextButton({ latitude = p.point.key.substringBefore(','); longitude = p.point.key.substringAfter(','); search = p.name.take(200); weather = null; places = null }) { Text(p.name) } } }
    }, confirmButton = { TextButton({ places = null }) { Text(t("Abbrechen")) } }) }
}
