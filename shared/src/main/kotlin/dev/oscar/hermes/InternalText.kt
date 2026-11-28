package dev.oscar.hermes

import org.json.JSONObject

/** optString that treats JSON null as empty (org.json returns the literal "null"). */
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

/** optDouble that returns null for a missing / null / non-numeric field (optDouble yields NaN otherwise). */
fun JSONObject.optDoubleOrNull(k: String): Double? =
    if (!has(k) || isNull(k)) null else optDouble(k).takeIf { it.isFinite() }


/** One piece of an assistant reply: plain text, or a MEDIA file shown as a card at that spot. */
sealed class ReplyPart {
    data class Text(val s: String) : ReplyPart()
    data class Media(val path: String) : ReplyPart()
}

/** One piece of a user message: plain text, or a FILE: line (a phone file to attach) shown as a chip. */
sealed class UserPart {
    data class Text(val s: String) : UserPart()
    data class File(val path: String) : UserPart()
}

/** Hermes stores gateway/system notices (background-process results, compaction notes, ...) as "user"
 *  rows. Hide those in the phone UI; unwrap real out-of-band user messages. */
object Internal {
    private val HIDE = listOf("[IMPORTANT: Background process", "[IMPORTANT: Background", "[INTERNAL NOTIFICATION",
        "[System note:", "[CONTEXT COMPACTION", "[PRIOR CONTEXT", "[END OF PRIOR CONTEXT", "[Context from the interrupted", "[SYSTEM:")
    /** Compaction scaffolding that the model/handoff can store as an assistant row (the summary dump). */
    private val COMPACTION = listOf("[PRIOR CONTEXT", "[END OF PRIOR CONTEXT", "[CONTEXT COMPACTION")
    private val OOB = Regex("""\[OUT-OF-BAND USER MESSAGE[^\]]*\]\s*([\s\S]*?)\s*\[/OUT-OF-BAND USER MESSAGE\]""")
    /** Hermes' own file syntax: a line "MEDIA:<pc path>". The phone shows it as an Inbox file instead of raw text. */
    private val MEDIA = Regex("""(?m)^[ \t]*MEDIA:[ \t]*(.+?)[ \t]*$""")
    /** MEDIA: in the middle of a line (after text, in a list item, in backticks/quotes): the path is the quoted part or the next token. */
    private val MEDIA_INLINE = Regex("""(?<![\w/\\])MEDIA:[ \t]*(?:`([^`\n]+)`|"([^"\n]+)"|'([^'\n]+)'|([^\s`"'<>|]+))""")
    /** A trailing, still-incomplete "MEDIA" / "MEDIA:" prefix at the end of a stream (group 1 = the partial word). */
    private val PARTIAL_MEDIA = Regex("""(?:^|[\s`"'(>*-])(M|ME|MED|MEDI|MEDIA)$""")
    private fun inlinePath(m: MatchResult): String = (m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "").trim().trimEnd('.', ',', ';', ')', ']', '!', '?')
    fun mediaPaths(text: String): List<String> {
        val lines = MEDIA.findAll(text).map { it.groupValues[1].trim().trim('"', '\'', '`') }.toList()
        val rest = MEDIA.replace(text, "")
        return (lines + MEDIA_INLINE.findAll(rest).map { inlinePath(it) }.toList()).filter { it.isNotBlank() }.distinct()
    }
    fun fileName(path: String): String = path.substringAfterLast('\\').substringAfterLast('/')

    /** Splits a reply at its MEDIA references (same regexes as [mediaPaths]). While [live] (streaming) an unfinished MEDIA
     *  line/token at the end is held back, so no raw path flashes; it shows once the line is complete. */
    fun segments(text: String, live: Boolean = false): List<ReplyPart> {
        var body = text
        if (live) {
            val k = text.lastIndexOf("MEDIA:")
            if (k >= 0 && !text.substring(k).contains('\n')) body = text.substring(0, k)
            else PARTIAL_MEDIA.find(text)?.groups?.get(1)?.let { body = text.substring(0, it.range.first) }
        }
        if (!body.contains("MEDIA:")) return listOf(ReplyPart.Text(body))
        val lines = MEDIA.findAll(body).toList()
        val spans = ArrayList<Pair<IntRange, String>>()
        lines.forEach { spans += it.range to it.groupValues[1].trim().trim('\"', '\'', '`') }
        MEDIA_INLINE.findAll(body).forEach { m -> if (lines.none { m.range.first in it.range }) spans += m.range to inlinePath(m) }
        spans.sortBy { it.first.first }
        val out = ArrayList<ReplyPart>()
        var pos = 0
        for ((r, p) in spans) {
            if (r.first < pos) continue
            if (r.first > pos) out += ReplyPart.Text(body.substring(pos, r.first))
            if (p.isNotBlank()) out += ReplyPart.Media(p)
            pos = r.last + 1
        }
        if (pos < body.length) out += ReplyPart.Text(body.substring(pos))
        return out.mapNotNull { if (it is ReplyPart.Text) it.s.trim('\n').takeIf { s -> s.isNotBlank() }?.let { s -> ReplyPart.Text(s) } else it }
    }

    /** Text of an assistant message that may carry MEDIA references (raw content and its text steps). */
    fun replyTexts(m: UiMsg): List<String> = listOfNotNull(m.raw) + m.steps.filter { it.kind == "text" }.map { it.detail }

    /** True when a reply in [msgs] references a file called [name] via MEDIA: that file is shown inline, not as a separate card. */
    fun mediaNamed(msgs: List<UiMsg>, name: String): Boolean =
        msgs.any { m -> m.role == "assistant" && replyTexts(m).any { t -> t.contains("MEDIA:") && mediaPaths(t).any { fileName(it) == name } } }

    fun stripMedia(text: String): String = if (text.contains("MEDIA:")) MEDIA_INLINE.replace(MEDIA.replace(text, ""), "").replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim() else text
    /** Phone-file syntax in a user message: a line "FILE:<absolute phone path>", or inline (quoted/backticked/next token).
     *  Sending attaches the file (uploaded like a picked file); the line stays in the text and is shown as a chip. */
    private val FILE_LINE = Regex("""(?m)^[ \t]*FILE:[ \t]*[`"']?(/[^\r\n]*?)[`"']?[ \t\r]*$""")
    private val FILE_INLINE = Regex("""(?<![\w/\\])FILE:[ \t]*(?:`(/[^`\n]+)`|"(/[^"\n]+)"|'(/[^'\n]+)'|(/[^\s`"'<>|]+))""")
    /** (range, absolute path) of each FILE reference, in order; lines win over inline matches that overlap them. */
    private fun fileSpans(text: String): List<Pair<IntRange, String>> {
        if (!text.contains("FILE:")) return emptyList()
        val spans = ArrayList<Pair<IntRange, String>>()
        FILE_LINE.findAll(text).forEach { spans += it.range to it.groupValues[1].trim() }
        FILE_INLINE.findAll(text).forEach { m ->
            if (spans.none { m.range.first <= it.first.last && it.first.first <= m.range.last })
                spans += m.range to (m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "").trimEnd('.', ',', ';', ')', ']', '!', '?')
        }
        spans.sortBy { it.first.first }
        return spans.filter { it.second.startsWith("/") }
    }
    /** Absolute paths named by FILE: references in [text] (distinct, in order). */
    fun filePaths(text: String): List<String> = fileSpans(text).map { it.second }.filter { it.isNotBlank() }.distinct()
    /** [text] split at its FILE references (chips) and the plain text between them. */
    fun userParts(text: String): List<UserPart> {
        val spans = fileSpans(text)
        if (spans.isEmpty()) return listOf(UserPart.Text(text))
        val out = ArrayList<UserPart>(); var pos = 0
        for ((r, p) in spans) {
            if (r.first > pos) out += UserPart.Text(text.substring(pos, r.first))
            out += UserPart.File(p); pos = r.last + 1
        }
        if (pos < text.length) out += UserPart.Text(text.substring(pos))
        return out.mapNotNull { if (it is UserPart.Text) it.s.trim().takeIf { s -> s.isNotBlank() }?.let { s -> UserPart.Text(s) } else it }
    }
    /** [text] without the FILE lines whose paths are in [drop] (the files that were attached); other FILE: lines stay. */
    fun stripFiles(text: String, drop: Collection<String>): String {
        val spans = fileSpans(text).filter { it.second in drop }
        if (spans.isEmpty()) return text
        val sb = StringBuilder(); var pos = 0
        for ((r, _) in spans) { sb.append(text, pos, r.first); pos = r.last + 1 }
        sb.append(text, pos, text.length)
        return sb.toString().replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun clean(role: String, text: String): String? {
        if (role == "tool") return null
        if (role == "assistant" && COMPACTION.any { text.trimStart().startsWith(it) }) return null
        if (role != "user") return stripMedia(text).takeUnless { it.trim() == "[SILENT]" || (it.isBlank() && text.isNotBlank()) }
        val t = text.trimStart()
        OOB.find(t)?.let { return it.groupValues[1].trim().ifBlank { null } }
        if (HIDE.any { t.startsWith(it) } || t.contains("[INTERNAL NOTIFICATION")) return null
        return text
    }
}
