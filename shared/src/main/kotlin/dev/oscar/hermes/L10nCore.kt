package dev.oscar.hermes

import java.util.Locale

/** Pure part of the in-app language override. */
object L10nCore {
    val LANGS = listOf("system", "en", "zh")

    fun locale(lang: String): Locale? = when (lang) {
        "en" -> Locale.ENGLISH
        "zh" -> Locale.SIMPLIFIED_CHINESE
        else -> null
    }

    fun isZh(lang: String): Boolean = (locale(lang) ?: Locale.getDefault()).language == "zh"
}
