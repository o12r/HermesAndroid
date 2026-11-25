package dev.oscar.hermes

import org.json.JSONObject

/** optString that treats JSON null as empty (org.json returns the literal "null"). */
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

/** optDouble that returns null for a missing / null / non-numeric field (optDouble yields NaN otherwise). */
fun JSONObject.optDoubleOrNull(k: String): Double? =
    if (!has(k) || isNull(k)) null else optDouble(k).takeIf { it.isFinite() }


/** Hermes stores gateway/system notices (background-process results, compaction notes, ...) as "user"
 *  rows. Hide those in the phone UI; unwrap real out-of-band user messages. */
object Internal {
    private val HIDE = listOf("[IMPORTANT: Background process", "[IMPORTANT: Background", "[INTERNAL NOTIFICATION",
        "[System note:", "[CONTEXT COMPACTION", "[PRIOR CONTEXT", "[Context from the interrupted", "[SYSTEM:")
    private val OOB = Regex("""\[OUT-OF-BAND USER MESSAGE[^\]]*\]\s*([\s\S]*?)\s*\[/OUT-OF-BAND USER MESSAGE\]""")
    /** Hermes' own file syntax: a line "MEDIA:<pc path>". The phone shows it as an Inbox file instead of raw text. */
    private val MEDIA = Regex("""(?m)^[ \t]*MEDIA:[ \t]*(.+?)[ \t]*$""")
    /** MEDIA: in the middle of a line (after text, in a list item, in backticks/quotes): the path is the quoted part or the next token. */
    private val MEDIA_INLINE = Regex("""(?<![\w/\\])MEDIA:[ \t]*(?:`([^`\n]+)`|"([^"\n]+)"|'([^'\n]+)'|([^\s`"'<>|]+))""")
    private fun inlinePath(m: MatchResult): String = (m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "").trim().trimEnd('.', ',', ';', ')', ']', '!', '?')
    fun mediaPaths(text: String): List<String> {
        val lines = MEDIA.findAll(text).map { it.groupValues[1].trim().trim('"', '\'', '`') }.toList()
        val rest = MEDIA.replace(text, "")
        return (lines + MEDIA_INLINE.findAll(rest).map { inlinePath(it) }.toList()).filter { it.isNotBlank() }.distinct()
    }
    fun stripMedia(text: String): String = if (text.contains("MEDIA:")) MEDIA_INLINE.replace(MEDIA.replace(text, ""), "").replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim() else text
    fun clean(role: String, text: String): String? {
        if (role == "tool") return null
        if (role != "user") return stripMedia(text).takeUnless { it.trim() == "[SILENT]" || (it.isBlank() && text.isNotBlank()) }
        val t = text.trimStart()
        OOB.find(t)?.let { return it.groupValues[1].trim().ifBlank { null } }
        if (HIDE.any { t.startsWith(it) } || t.contains("[INTERNAL NOTIFICATION")) return null
        return text
    }
}
