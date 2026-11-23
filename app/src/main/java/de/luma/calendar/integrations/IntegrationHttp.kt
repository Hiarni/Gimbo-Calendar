package de.luma.calendar.integrations

import de.luma.calendar.data.HolidayStore
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object IntegrationHttp {
    fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        try { check(connection.responseCode == 200) { "Daten konnten nicht geladen werden. Bereits gespeicherte Daten bleiben erhalten." }
            return connection.inputStream.use { HolidayStore.boundedText(it) }
        } finally { connection.disconnect() }
    }
}
