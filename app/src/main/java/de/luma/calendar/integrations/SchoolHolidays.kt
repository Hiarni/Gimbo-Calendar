package de.luma.calendar.integrations

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import de.luma.calendar.data.HolidayStore
import de.luma.calendar.model.*
import org.json.*
import java.io.File
import java.time.LocalDate
import java.util.Locale

data class HolidayChoice(val code: String, val de: String, val en: String) { fun label(locale: Locale) = if (locale.language == "de") de else en }
data class SchoolPeriod(val id: String, val start: LocalDate, val end: LocalDate, val de: String, val en: String, val country: String, val region: String, val group: String) {
    fun event(locale: Locale) = Holiday("school/$country/$region/$group/$id", start, end, en, de).event(locale).copy(notes = if (locale.language == "de") "Quelle: OpenHolidays · ODbL 1.0" else "Source: OpenHolidays · ODbL 1.0")
}
data class SchoolSnapshot(val periods: List<SchoolPeriod> = emptyList(), val countries: List<HolidayChoice> = emptyList(), val regions: List<HolidayChoice> = emptyList(), val groups: List<HolidayChoice> = emptyList(), val loadedYears: List<Int> = emptyList()) {
    fun events(settings: AppSettings, locale: Locale) = if (!settings.schoolHolidays) emptyList() else periods.filter { it.country == settings.schoolCountry && it.region == settings.schoolRegion && it.group == settings.schoolGroup }.distinctBy { it.id }.map { it.event(locale) }
}

class SchoolHolidays(context: Context, private val download: (String) -> String = IntegrationHttp::download) {
    private val directory = File(context.filesDir, "school-holidays").apply { mkdirs() }
    fun metadata(country: String) {
        require(country.matches(Regex("[A-Z]{2}")))
        val countries = download("$BASE/Countries"); choices(countries, true); write("countries.json", countries); val regions = download("$BASE/Subdivisions?countryIsoCode=$country"); val groups = download("$BASE/Groups?countryIsoCode=$country")
        choices(countries, true); choices(regions); choices(groups)
        write("countries.json", countries); write("regions-$country.json", regions); write("groups-$country.json", groups)
    }
    fun fetch(country: String, region: String, group: String, year: Int) {
        validate(country, region, group); require(year in 1900..2100)
        val query = "countryIsoCode=$country&validFrom=$year-01-01&validTo=$year-12-31&subdivisionCode=${Uri.encode(region)}&groupCode=${Uri.encode(group)}"
        val content = download("$BASE/SchoolHolidays?$query")
        parse(content, country, region, group, year)
        val wrapper = JSONObject().put("country", country).put("region", region).put("group", group).put("year", year).put("data", JSONArray(content))
        val key = java.util.UUID.nameUUIDFromBytes("$country/$region/$group/$year".toByteArray()).toString()
        write("periods-$key.json", wrapper.toString())
        synchronized(lock) { directory.listFiles().orEmpty().filter { it.name.startsWith("periods-") }.sortedByDescending { it.lastModified() }.drop(12).forEach { AtomicFile(it).delete() } }
    }
    fun read(settings: AppSettings): SchoolSnapshot = synchronized(lock) {
        fun content(name: String) = runCatching { AtomicFile(File(directory, name)).openRead().use { HolidayStore.boundedText(it) } }.getOrNull()
        val years = mutableListOf<Int>()
        val periods = directory.listFiles().orEmpty().filter { it.name.startsWith("periods-") && it.extension == "json" }.take(12).flatMap { f -> runCatching {
            val root = JSONObject(content(f.name) ?: error("cache")); val c = root.getString("country"); val r = root.getString("region"); val g = root.getString("group"); val y = root.getInt("year")
            val values = parse(root.getJSONArray("data").toString(), c, r, g, y)
            if (c == settings.schoolCountry && r == settings.schoolRegion && g == settings.schoolGroup) years += y
            values
        }.getOrDefault(emptyList()) }
        SchoolSnapshot(periods, content("countries.json")?.let { runCatching { choices(it, true) }.getOrDefault(emptyList()) }.orEmpty(),
            content("regions-${settings.schoolCountry}.json")?.let { runCatching { choices(it) }.getOrDefault(emptyList()) }.orEmpty(),
            content("groups-${settings.schoolCountry}.json")?.let { runCatching { choices(it) }.getOrDefault(emptyList()) }.orEmpty(), years.distinct().sorted())
    }
    private fun write(name: String, text: String) = synchronized(lock) {
        require(text.toByteArray().size <= HolidayStore.MAX_BYTES)
        val file = AtomicFile(File(directory, name)); val out = file.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    companion object {
        const val BASE = "https://openholidaysapi.org"
        private val lock = Any()
        fun validate(country: String, region: String, group: String) { require(country.matches(Regex("[A-Z]{2}")) && listOf(region, group).all { it.isEmpty() || it.length <= 80 && it.matches(Regex("[A-Za-z0-9-]+")) }) }
        fun names(array: JSONArray): Pair<String, String> {
            require(array.length() in 1..30)
            val values = (0 until array.length()).map { i -> array.getJSONObject(i).let { it.getString("language").uppercase(Locale.ROOT) to it.getString("text").trim() } }.toMap()
            require(values.values.all { it.isNotBlank() && it.length <= 200 })
            val en = values["EN"] ?: values.values.first(); return (values["DE"] ?: en) to en
        }
        fun choices(text: String, countries: Boolean = false): List<HolidayChoice> {
            require(text.toByteArray().size <= HolidayStore.MAX_BYTES)
            val result = mutableListOf<HolidayChoice>()
            fun walk(array: JSONArray, depth: Int) { require(depth <= 5 && array.length() <= 1000); for (i in 0 until array.length()) {
                val row = array.getJSONObject(i); val code = row.getString(if (countries) "isoCode" else "code")
                require(if (countries) code.matches(Regex("[A-Z]{2}")) else code.length in 1..80 && code.matches(Regex("[A-Za-z0-9-]+"))); val (de, en) = names(row.getJSONArray("name")); result += HolidayChoice(code, de, en); require(result.size <= 1000)
                row.optJSONArray("children")?.let { walk(it, depth + 1) }
            } }
            walk(JSONArray(text), 0); require(result.map { it.code }.distinct().size == result.size); return result
        }
        fun parse(text: String, country: String, region: String, group: String, year: Int): List<SchoolPeriod> {
            validate(country, region, group); require(year in 1900..2100 && text.toByteArray().size <= HolidayStore.MAX_BYTES)
            val array = JSONArray(text); require(array.length() <= 1000)
            return (0 until array.length()).map { i -> val row = array.getJSONObject(i)
                require(row.getString("type") in setOf("School", "BackToSchool", "EndOfLessons"))
                val start = LocalDate.parse(row.getString("startDate")); val last = LocalDate.parse(row.getString("endDate"))
                require(start.year in 1900..2100 && last >= start && last <= start.plusDays(365) && last >= LocalDate.of(year, 1, 1) && start <= LocalDate.of(year, 12, 31))
                val id = row.getString("id"); java.util.UUID.fromString(id)
                val (de, en) = names(row.getJSONArray("name")); SchoolPeriod(id, start, last.plusDays(1), de, en, country, region, group)
            }.also { require(it.map { p -> p.id }.distinct().size == it.size) }
        }
    }
}
