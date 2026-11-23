package de.luma.calendar.integrations

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import de.luma.calendar.data.HolidayStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File
import java.util.Locale

data class WeatherPlace(val name: String, val point: WeatherPoint)

class PlaceSearch(context: Context, private val download: (String) -> String = ::download) {
    private val directory = File(context.filesDir,"places").apply { mkdirs() }
    suspend fun search(query: String, language: String): List<WeatherPlace> = mutex.withLock {
        val q = query.trim(); require(q.length in 2..200 && language in setOf("de","en")) { "Bitte einen Ort mit 2 bis 200 Zeichen eingeben." }
        val file = AtomicFile(File(directory,java.util.UUID.nameUUIDFromBytes("$language/$q".toByteArray()).toString()+".json"))
        val cached = runCatching { file.openRead().use { HolidayStore.boundedText(it) } }.getOrNull()
        if (cached != null) return@withLock parse(cached)
        val gap = 1000 - (System.nanoTime() - lastRequest) / 1000000
        if (gap > 0) delay(gap); lastRequest = System.nanoTime()
        val text = download("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&accept-language=$language&q=${Uri.encode(q)}")
        val places = parse(text); val out = file.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(out) } catch(e: Exception) { file.failWrite(out); throw e }
        directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.drop(20).forEach { AtomicFile(it).delete() }
        places
    }
    suspend fun clear() = mutex.withLock { directory.listFiles().orEmpty().forEach { it.delete() }; Unit }
    companion object {
        private val mutex = Mutex(); private var lastRequest = System.nanoTime() - 1000000000L
        fun parse(text: String): List<WeatherPlace> {
            require(text.toByteArray().size <= HolidayStore.MAX_BYTES); val array = JSONArray(text); require(array.length() <= 5)
            return (0 until array.length()).map { i -> val row=array.getJSONObject(i); val name=row.getString("display_name").trim(); require(name.isNotEmpty() && name.length <= 1000); WeatherPlace(name,WeatherPoint(row.getString("lat").toDouble(),row.getString("lon").toDouble()).checked()) }
        }
        private fun download(url: String): String {
            val connection=java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
            connection.connectTimeout=15000; connection.readTimeout=15000; connection.instanceFollowRedirects=false
            connection.setRequestProperty("User-Agent","Gimbo-Kalender/${de.luma.calendar.BuildConfig.VERSION_NAME} (+https://github.com/Hiarni/Gimbo-Calendar)"); connection.setRequestProperty("Accept","application/json")
            try { check(connection.responseCode==200) { "Ortssuche derzeit nicht verfügbar. Koordinaten oder Gerätestandort verwenden." }; return connection.inputStream.use { HolidayStore.boundedText(it) } } finally { connection.disconnect() }
        }
    }
}
