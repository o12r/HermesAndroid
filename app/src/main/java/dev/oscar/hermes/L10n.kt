package dev.oscar.hermes

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** In-app language override: "system", "en" or "zh". */
object L10n {
    val LANGS = listOf("system", "en", "zh")

    fun locale(lang: String): Locale? = when (lang) {
        "en" -> Locale.ENGLISH
        "zh" -> Locale.SIMPLIFIED_CHINESE
        else -> null
    }

    /** A context whose resources use [lang] (unchanged for "system"). */
    fun wrap(base: Context, lang: String): Context {
        val loc = locale(lang) ?: return base
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration).apply { setLocale(loc) }
        return base.createConfigurationContext(cfg)
    }

    /** Context in the user's chosen app language (for services / notifications). */
    fun ctx(base: Context): Context = wrap(base, SecureStore(base).lang)

    /** True when the effective UI language is Chinese. */
    fun isZh(ctx: Context): Boolean = ctx.resources.configuration.locales[0].language == "zh"
}
