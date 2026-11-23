package de.luma.calendar.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import de.luma.calendar.data.SettingsStore
import java.util.Locale

enum class AppLanguage(val tag: String, val label: String) {
    SYSTEM("", "Systemsprache"), GERMAN("de", "Deutsch"), ENGLISH("en", "Englisch"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt", "Português"),
    DUTCH("nl", "Nederlands"),
    POLISH("pl", "Polski"),
    CZECH("cs", "Čeština"),
    SLOVAK("sk", "Slovenčina"),
    HUNGARIAN("hu", "Magyar"),
    ROMANIAN("ro", "Română"),
    GREEK("el", "Ελληνικά"),
    TURKISH("tr", "Türkçe"),
    RUSSIAN("ru", "Русский"),
    UKRAINIAN("uk", "Українська"),
    SWEDISH("sv", "Svenska"),
    DANISH("da", "Dansk"),
    NORWEGIAN("nb", "Norsk bokmål"),
    FINNISH("fi", "Suomi"),
    ARABIC("ar", "العربية"),
    HEBREW("he", "עברית"),
    PERSIAN("fa", "فارسی"),
    HINDI("hi", "हिन्दी"),
    BENGALI("bn", "বাংলা"),
    INDONESIAN("id", "Bahasa Indonesia"),
    MALAY("ms", "Bahasa Melayu"),
    VIETNAMESE("vi", "Tiếng Việt"),
    THAI("th", "ไทย"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "繁體中文");
    companion object {
        fun fromTag(tag: String): AppLanguage {
            if (tag.isBlank()) return SYSTEM
            val locale = Locale.forLanguageTag(tag)
            val language = when (locale.language) { "iw" -> "he"; "in" -> "id"; "no" -> "nb"; else -> locale.language }
            if (language == "zh") return if (locale.script == "Hant" || locale.country in listOf("TW", "HK", "MO")) CHINESE_TRADITIONAL else CHINESE_SIMPLIFIED
            return entries.firstOrNull { it.tag.isNotBlank() && Locale.forLanguageTag(it.tag).language == language } ?: SYSTEM
        }
    }
}

object AppLocale {
    fun language(context: Context): AppLanguage = if (Build.VERSION.SDK_INT >= 33)
        AppLanguage.fromTag(context.getSystemService(LocaleManager::class.java).applicationLocales[0]?.toLanguageTag().orEmpty())
    else SettingsStore(context).storedLanguage()

    fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]
    fun locale(context: Context): Locale {
        val selected = language(context)
        if (selected != AppLanguage.SYSTEM) return Locale.forLanguageTag(selected.tag)
        val system = systemLocale()
        val supported = AppLanguage.fromTag(system.toLanguageTag())
        if (supported == AppLanguage.SYSTEM) return Locale.ENGLISH
        return if (system.language == "zh") Locale.forLanguageTag(supported.tag) else if (supported == AppLanguage.GERMAN) Locale.GERMAN else if (supported == AppLanguage.ENGLISH) Locale.ENGLISH else system
    }
    fun wrap(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale(context)))
        return context.createConfigurationContext(config)
    }
    fun set(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java)
            .applicationLocales = LocaleList.forLanguageTags(language.tag)
    }
}
