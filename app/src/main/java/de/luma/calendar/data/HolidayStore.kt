package de.luma.calendar.data

import android.content.Context
import android.util.AtomicFile
import de.luma.calendar.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

class HolidayStore(context: Context, private val download: (String) -> String = ::downloadHttps) {
    private val directory = java.io.File(context.filesDir, "holidays").apply { mkdirs() }
    fun read(): HolidaySnapshot = synchronized(storageLock) { HolidaySnapshot(directory.listFiles().orEmpty().filter { it.extension == "json" }.flatMap { file ->
        runCatching { decode(AtomicFile(file).openRead().use { boundedText(it) }) }.getOrDefault(emptyList())
    }) }
    fun fetch(country: String, year: Int) {
        require(country in COUNTRIES && year in 1900..2100) { "Ungültige Feiertagsdaten." }
        val holidays = parseApiV4(download("https://nagerholidays.com/api/v4/Holidays/$country/$year"), country, year)
        write("$country-$year.json", holidays)
    }
    fun importCalendarIcs(text: String, from: LocalDate = LocalDate.now().withDayOfYear(1)) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Die Feiertagsdatei ist zu groß (maximal 2 MB)." }
        val backup = IcsFiles.read(text, java.time.ZoneOffset.UTC)
        require(backup.events.all { it.allDay } && backup.exceptions.all { it.replacement?.allDay != false }) { "Nur ganztägige Feiertagstermine sind erlaubt." }
        val singles = backup.events.filter { it.repeat == Repeat.NONE }.map { event ->
            Holiday("import/${event.id}/${event.start}", java.time.LocalDateTime.parse(event.start).toLocalDate(), java.time.LocalDateTime.parse(event.end).toLocalDate(), event.title, imported = true)
        }
        val recurring = EventEngine.occurrences(backup.events.filter { it.repeat != Repeat.NONE }, from, from.plusYears(5), java.time.ZoneOffset.UTC, backup.exceptions, maxOccurrences = 10001)
        val entries = singles + recurring.map { o -> Holiday("import/${o.key}", o.start.toLocalDate(), o.end.toLocalDate(), o.event.title, imported = true) }

        require(entries.isNotEmpty() && entries.size <= 10000) { "Keine Feiertage in dieser Datei." }
        write("import.json", entries)
    }
    fun importIcs(text: String) { write("import.json", parseIcs(text)) }
    fun removeImport() = synchronized(storageLock) { AtomicFile(java.io.File(directory, "import.json")).delete() }
    private fun write(name: String, holidays: List<Holiday>) = synchronized(storageLock) {
        val bytes = encode(holidays).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Die Feiertagsdatei ist zu groß (maximal 2 MB)." }
        val file = AtomicFile(java.io.File(directory, name))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object {
        private val storageLock = Any()
        const val MAX_BYTES = 2 * 1024 * 1024

        val COUNTRIES = setOf("AT", "DE", "CH", "GB", "US", "CA", "AU", "NZ", "IE", "FR", "IT", "ES", "NL", "BE", "DK", "SE", "NO", "FI", "PL", "PT", "CZ", "HU", "SK", "SI", "HR", "LU")
        fun boundedText(input: InputStream): String {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Die Feiertagsdatei ist zu groß (maximal 2 MB)." }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }

        fun parseApiV4(text: String, country: String, year: Int): List<Holiday> {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Die Feiertagsdatei ist zu groß (maximal 2 MB)." }
            val values = JSONArray(text)
            require(values.length() in 1..1000) { "Ungültige Feiertagsdaten." }
            val normalized = JSONArray()
            for (i in 0 until values.length()) {
                val row = values.getJSONObject(i)
                normalized.put(JSONObject(row.toString()).put("types", row.getJSONArray("holidayTypes"))
                    .put("counties", row.optJSONArray("subdivisionCodes") ?: JSONObject.NULL).put("global", row.getBoolean("nationalHoliday"))
                    .put("localName", germanHolidayName(row.getString("name"))))
            }
            return parseApi(normalized.toString(), country, year)
        }
        private fun germanHolidayName(name: String): String = when (name.lowercase(java.util.Locale.ROOT)) {
            "new year's day" -> "Neujahr"; "epiphany" -> "Heilige Drei Könige"; "good friday" -> "Karfreitag"
            "easter sunday" -> "Ostersonntag"; "easter monday" -> "Ostermontag"; "labour day", "labor day" -> "Tag der Arbeit"
            "ascension day" -> "Christi Himmelfahrt"; "pentecost", "pentecost sunday", "whit sunday" -> "Pfingstsonntag"
            "whit monday", "pentecost monday" -> "Pfingstmontag"; "corpus christi" -> "Fronleichnam"; "assumption day", "assumption of mary" -> "Mariä Himmelfahrt"
            "german unity day" -> "Tag der Deutschen Einheit"; "reformation day" -> "Reformationstag"; "all saints' day", "all saints day" -> "Allerheiligen"
            "national day" -> "Nationalfeiertag"; "swiss national day" -> "Schweizer Bundesfeier"; "immaculate conception" -> "Mariä Empfängnis"
            "christmas day" -> "Weihnachten"; "st. stephen's day", "saint stephen's day", "boxing day" -> "Zweiter Weihnachtsfeiertag"
            "new year's eve" -> "Silvester"; else -> name
        }
        fun parseApi(text: String, country: String, year: Int): List<Holiday> {
            require(text.toByteArray().size <= MAX_BYTES) { "Die Feiertagsdatei ist zu groß (maximal 2 MB)." }
            val array = JSONArray(text)
            require(array.length() in 1..1000) { "Ungültige Feiertagsdaten." }
            val holidays = (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                require(item.getString("countryCode") == country) { "Ungültige Feiertagsdaten." }
                val date = LocalDate.parse(item.getString("date"))
                require(date.year == year) { "Ungültige Feiertagsdaten." }
                val types = item.getJSONArray("types")
                if ((0 until types.length()).none { types.getString(it) == "Public" }) return@mapNotNull null
                val regions = item.optJSONArray("counties")?.let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }.orEmpty()
                require(regions.all { it.startsWith("$country-") && it.length <= 20 }) { "Ungültige Feiertagsdaten." }
                val name = item.getString("name").trim()
                val local = item.getString("localName").trim()
                require(name.isNotEmpty() && name.length <= 200 && local.isNotEmpty() && local.length <= 200) { "Ungültige Feiertagsdaten." }
                Holiday("api/$country/$date/$name/${regions.sorted()}", date, name = name, localName = local,
                    country = country, regions = regions, national = item.getBoolean("global"))
            }
            require(holidays.isNotEmpty()) { "Ungültige Feiertagsdaten." }
            return holidays.distinctBy { it.uid }
        }

        fun parseIcs(text: String): List<Holiday> {
            val invalid = "Keine unterstützte Feiertagsdatei. Nur ganztägige ICS-Einträge ohne Wiederholungsregeln sind erlaubt."
            require(text.toByteArray().size <= MAX_BYTES) { "Die Feiertagsdatei ist zu groß (maximal 2 MB)." }
            val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
                .replace(Regex("\n[ \t]"), "").lines().filter { it.isNotEmpty() }
            require(lines.firstOrNull() == "BEGIN:VCALENDAR" && lines.lastOrNull() == "END:VCALENDAR") { invalid }
            val result = mutableListOf<Holiday>()
            var properties: MutableMap<String, String>? = null
            var ignoredDepth = 0
            for (line in lines.drop(1).dropLast(1)) {
                when {
                    line == "BEGIN:VEVENT" -> { require(properties == null) { invalid }; properties = mutableMapOf() }
                    line == "END:VEVENT" -> {
                        val p = properties ?: error(invalid)
                        require(ignoredDepth == 0 && p.keys.none { it in listOf("RRULE", "RDATE", "EXDATE", "RECURRENCE-ID", "DURATION") }) { invalid }
                        val start = LocalDate.parse(p["DTSTART;VALUE=DATE"] ?: error(invalid), DateTimeFormatter.BASIC_ISO_DATE)
                        val end = p["DTEND;VALUE=DATE"]?.let { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) } ?: start.plusDays(1)
                        val name = unescape(p["SUMMARY"] ?: error(invalid)).trim()
                        require(start.year in 1900..2100 && end > start && end <= start.plusDays(366) && name.isNotEmpty() && name.length <= 200) { invalid }
                        val uid = p["UID"] ?: "$start/$end/$name"
                        result += Holiday("import/$uid", start, end, name, imported = true)
                        require(result.size <= 10000) { invalid }
                        properties = null
                    }
                    properties != null && line.startsWith("BEGIN:") -> ignoredDepth++
                    properties != null && line.startsWith("END:") -> { require(ignoredDepth > 0) { invalid }; ignoredDepth-- }
                    properties != null && ignoredDepth == 0 -> {
                        val colon = line.indexOf(':'); require(colon > 0) { invalid }
                        val raw = line.substring(0, colon)
                        val key = if (raw.substringBefore(';') in listOf("DTSTART", "DTEND")) raw else raw.substringBefore(';')
                        require(properties.put(key, line.substring(colon + 1)) == null) { invalid }
                    }
                }
            }
            require(properties == null && result.isNotEmpty()) { if (result.isEmpty()) "Keine Feiertage in dieser Datei." else invalid }

            return result.distinctBy { "${it.uid}/${it.date}" }.map { it.copy(uid = "${it.uid}/${it.date}") }
        }
        private fun unescape(value: String): String {
            val result = StringBuilder(); var escaped = false
            value.forEach { c ->
                if (escaped) { result.append(if (c == 'n' || c == 'N') '\n' else c); escaped = false }
                else if (c == '\\') escaped = true else result.append(c)
            }
            if (escaped) result.append('\\')
            return result.toString()
        }
        fun encode(holidays: List<Holiday>): String = JSONArray().also { array -> holidays.forEach { h ->
            array.put(JSONObject().put("uid", h.uid).put("date", h.date.toString()).put("end", h.end.toString())
                .put("name", h.name).put("localName", h.localName).put("country", h.country)
                .put("regions", JSONArray(h.regions.toList())).put("national", h.national).put("imported", h.imported))
        } }.toString()
        fun decode(text: String): List<Holiday> {
            val array = JSONArray(text)
            return (0 until array.length()).map { index -> val h = array.getJSONObject(index)
                val regions = h.getJSONArray("regions")
                Holiday(h.getString("uid"), LocalDate.parse(h.getString("date")), LocalDate.parse(h.getString("end")),
                    h.getString("name"), h.getString("localName"), h.getString("country"),
                    (0 until regions.length()).map { regions.getString(it) }.toSet(), h.getBoolean("national"), h.getBoolean("imported"))
            }
        }
        private fun downloadHttps(url: String): String {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            try {
                check(connection.responseCode == 200) { "Feiertage konnten nicht geladen werden. Bereits geladene Daten bleiben erhalten." }
                return connection.inputStream.use { boundedText(it) }
            } finally { connection.disconnect() }
        }
    }
}
