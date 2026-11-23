package de.luma.calendar.integrations

import android.content.Context
import android.location.*
import android.os.*
import android.util.AtomicFile
import de.luma.calendar.data.HolidayStore
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.time.*
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WeatherPoint(val latitude: Double, val longitude: Double) {
    fun checked() = also { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Breitengrad: −90 bis 90; Längengrad: −180 bis 180." } }
    val key get() = String.format(Locale.US, "%.4f,%.4f", latitude, longitude)
}
data class WeatherForecast(val point: WeatherPoint, val date: LocalDate, val zone: String, val minimum: Double, val maximum: Double, val rain: Double?, val from: Instant, val until: Instant, val fetched: Instant)

class WeatherStore(context: Context, private val download: (String) -> String = ::download) {
    private val directory = File(context.filesDir, "weather").apply { mkdirs() }
    private fun file(point: WeatherPoint, date: LocalDate, zone: String) = AtomicFile(File(directory, java.util.UUID.nameUUIDFromBytes("${point.key}/$date/$zone".toByteArray()).toString() + ".json"))
    fun read(point: WeatherPoint, date: LocalDate, zone: String): WeatherForecast? = synchronized(lock) { runCatching {
        val root = JSONObject(file(point.checked(), date, zone).openRead().use { HolidayStore.boundedText(it) })
        parse(root.getJSONObject("data").toString(), point, date, zone, Instant.parse(root.getString("fetched")))
    }.getOrNull() }
    fun fetch(point: WeatherPoint, date: LocalDate, zone: String): WeatherForecast {
        point.checked(); ZoneId.of(zone); require(date.year in 1900..2100)
        val coords = point.key.split(','); val text = download("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${coords[0]}&lon=${coords[1]}")
        val now = Instant.now(); val forecast = parse(text, point, date, zone, now)
        val bytes = JSONObject().put("fetched", now.toString()).put("data", JSONObject(text)).toString().toByteArray(); require(bytes.size <= HolidayStore.MAX_BYTES)
        synchronized(lock) { val cache = file(point, date, zone); val out = cache.startWrite(); try { out.write(bytes); cache.finishWrite(out) } catch (e: Exception) { cache.failWrite(out); throw e }
            directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.drop(8).forEach { AtomicFile(it).delete() }
        }
        return forecast
    }
    fun clear() = synchronized(lock) { directory.listFiles().orEmpty().forEach { it.delete() } }
    companion object {
        private val lock = Any()
        fun parse(text: String, point: WeatherPoint, date: LocalDate, zone: String, fetched: Instant): WeatherForecast {
            point.checked(); val timeZone = ZoneId.of(zone); require(text.toByteArray().size <= HolidayStore.MAX_BYTES)
            val array = JSONObject(text).getJSONObject("properties").getJSONArray("timeseries"); require(array.length() in 1..1000)
            val rows = (0 until array.length()).map { array.getJSONObject(it) }.filter { Instant.parse(it.getString("time")).atZone(timeZone).toLocalDate() == date }.sortedBy { it.getString("time") }
            require(rows.isNotEmpty()) { "Für dieses Datum ist noch keine Wettervorhersage verfügbar." }
            val temperatures = rows.map { it.getJSONObject("data").getJSONObject("instant").getJSONObject("details").getDouble("air_temperature").also { value -> require(value.isFinite() && value in -100.0..70.0) } }

            var next = Instant.MIN; var rain = 0.0; var rainKnown = false
            rows.forEach { r -> val time = Instant.parse(r.getString("time")); if (time >= next) {
                val data = r.getJSONObject("data"); val hours = listOf(1, 6).firstOrNull { data.optJSONObject("next_${it}_hours") != null }
                if (hours != null) { val details = data.getJSONObject("next_${hours}_hours").getJSONObject("details"); val end = time.plusSeconds(hours * 3600L)
                    if (details.has("precipitation_amount") && end <= date.plusDays(1).atStartOfDay(timeZone).toInstant()) { val amount = details.getDouble("precipitation_amount"); require(amount.isFinite() && amount in 0.0..1000.0); rain += amount; rainKnown = true; next = end } }
            } }
            return WeatherForecast(point, date, zone, temperatures.min(), temperatures.max(), rain.takeIf { rainKnown }, Instant.parse(rows.first().getString("time")), Instant.parse(rows.last().getString("time")), fetched)
        }
        private fun download(url: String): String {
            val connection = java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Gimbo-Kalender/${de.luma.calendar.BuildConfig.VERSION_NAME} (+https://github.com/Hiarni/Gimbo-Calendar)")
            connection.setRequestProperty("Accept", "application/json")
            try { check(connection.responseCode == 200) { "Wetter konnte nicht geladen werden. Gespeicherte Daten bleiben erhalten." }; return connection.inputStream.use { HolidayStore.boundedText(it) } } finally { connection.disconnect() }
        }
        suspend fun location(context: Context): WeatherPoint = withTimeout(12000) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("Standortfreigabe erforderlich.")
            val manager = context.getSystemService(LocationManager::class.java)
            require(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { "Kein Standort verfügbar. Koordinaten bitte eingeben." }
            suspendCancellableCoroutine { continuation ->
                fun result(location: Location?) { if (continuation.isActive) { if (location == null) continuation.resumeWithException(IllegalStateException("Kein Standort verfügbar. Koordinaten bitte eingeben.")) else continuation.resume(WeatherPoint(location.latitude, location.longitude).checked()) } }
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal(); continuation.invokeOnCancellation { signal.cancel() }
                    try { manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, signal, context.mainExecutor, ::result) } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                } else {
                    val listener = object : LocationListener { override fun onLocationChanged(location: Location) { manager.removeUpdates(this); result(location) }; @Deprecated("Legacy API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {} }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    try { @Suppress("DEPRECATION") manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper()) } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        }
    }
}
