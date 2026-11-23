package de.luma.calendar.integrations

import java.net.URI

object MeetingLinks {
    fun read(vararg text: String): List<String> = Regex("https://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).findAll(text.joinToString("\n"))
        .map { it.value.trimEnd('.', ',', ';', ':', '!', '?').let { raw ->
            if (raw.endsWith(')') && raw.count { it == ')' } > raw.count { it == '(' }) raw.dropLast(1) else raw
        } }.filter { raw -> runCatching {
            val uri = URI(raw)
            uri.scheme.equals("https", true) && uri.host != null && uri.rawUserInfo == null && uri.port in listOf(-1, 443) && raw.length <= 2048 && !raw.contains('\\')
        }.getOrDefault(false) }.distinct().take(10).toList()
    fun label(url: String): String = URI(url).host.lowercase(java.util.Locale.ROOT).removePrefix("www.")
}
