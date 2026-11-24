package dev.oscar.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** A piece of a reply: markdown prose, or a code/data block shown monospace (lang may be blank). */
sealed class Seg {
    data class Md(val text: String) : Seg()
    data class Code(val lang: String, val text: String) : Seg()
}

/**
 * Split a reply into prose and code blocks so XML, JSON, HTML, YAML etc. render as data instead of being mangled by
 * markdown (angle brackets eaten, indentation collapsed). Handles ``` / ~~~ fences (also unterminated while
 * streaming), a whole reply that is bare JSON/XML, and bare XML/JSON runs between paragraphs.
 */
object Formats {
    /** Clock preference: "system" | "12" | "24". Compose state so visible times refresh when changed. */
    var clock by androidx.compose.runtime.mutableStateOf("system")
    var appCtx: android.content.Context? = null

    private fun use24(): Boolean = when (clock) {
        "12" -> false
        "24" -> true
        else -> appCtx?.let { android.text.format.DateFormat.is24HourFormat(it) } ?: true
    }

    /**
     * Unified time display: today -> time; within 10 days -> "N days ago + time"; within a year -> month/day
     * ("Last year" prefix when not this year); older -> year + month.
     */
    fun fmtTime(ms: Long): String {
        val zh = java.util.Locale.getDefault().language == "zh"
        val loc = java.util.Locale.getDefault()
        fun sdf(p: String) = java.text.SimpleDateFormat(p, loc)
        val d = java.util.Date(ms)
        val time = sdf(if (use24()) "HH:mm" else if (zh) "ah:mm" else "h:mm a").format(d)
        val now = java.util.Calendar.getInstance()
        val t = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        fun dayStart(c: java.util.Calendar) = (c.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val days = Math.round((dayStart(now).timeInMillis - dayStart(t).timeInMillis) / 86_400_000.0).toInt()
        val yearGap = now.get(java.util.Calendar.YEAR) - t.get(java.util.Calendar.YEAR)
        return when {
            days <= 0 -> time
            days <= 10 -> (if (zh) "$days 天前 " else if (days == 1) "1 day ago " else "$days days ago ") + time
            days <= 365 -> if (yearGap <= 0) sdf(if (zh) "M月d日" else "MMM d").format(d)
                else (if (zh) "去年" else "Last year ") + sdf(if (zh) "M月d日" else "MMM d").format(d)
            else -> sdf(if (zh) "yyyy年M月" else "MMM yyyy").format(d)
        }
    }

    private val fence = Regex("^ {0,3}(`{3,}|~{3,})\\s*([\\w+#.-]*).*$")

    fun split(src: String): List<Seg> {
        val out = mutableListOf<Seg>()
        val prose = StringBuilder()
        fun flushProse() { if (prose.isNotBlank()) out += bare(prose.toString()); prose.clear() }
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val m = fence.matchEntire(lines[i])
            if (m == null) { prose.append(lines[i]).append('\n'); i++; continue }
            flushProse()
            val mark = m.groupValues[1]; val lang = m.groupValues[2].lowercase()
            val body = StringBuilder(); i++
            while (i < lines.size && !lines[i].trimStart().let { it.startsWith(mark) && it.trim().all { c -> c == mark[0] } }) {
                body.append(lines[i]).append('\n'); i++
            }
            i++   // closing fence (or end while streaming)
            out += code(lang, body.toString().trimEnd('\n'))
        }
        flushProse()
        return out
    }

    /** Prose without fences: pull out bare JSON documents and XML runs. */
    private fun bare(text: String): List<Seg> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        jsonPretty(t)?.let { return listOf(Seg.Code("json", it)) }
        if (looksXml(t)) return listOf(Seg.Code("xml", t))
        // paragraph-level: XML / JSON paragraphs inside prose
        val out = mutableListOf<Seg>(); val md = StringBuilder()
        for (para in text.split(Regex("\n\\s*\n"))) {
            val p = para.trim()
            val j = if (p.startsWith("{") || p.startsWith("[")) jsonPretty(p) else null
            when {
                j != null -> { if (md.isNotBlank()) out += Seg.Md(md.toString()); md.clear(); out += Seg.Code("json", j) }
                looksXml(p) -> { if (md.isNotBlank()) out += Seg.Md(md.toString()); md.clear(); out += Seg.Code("xml", p) }
                else -> md.append(para).append("\n\n")
            }
        }
        if (md.isNotBlank()) out += Seg.Md(md.toString())
        return out
    }

    private fun code(lang: String, body: String): Seg.Code {
        val l = lang.ifBlank { when { jsonPretty(body) != null -> "json"; looksXml(body) -> "xml"; else -> "" } }
        return Seg.Code(l, if (l == "json") jsonPretty(body) ?: body else body)
    }

    private fun looksXml(s: String): Boolean {
        val t = s.trim()
        if (!t.startsWith("<") || !t.endsWith(">") || t.length < 7) return false
        if (t.startsWith("<?xml") || t.startsWith("<!DOCTYPE", true)) return true
        val open = Regex("^<([A-Za-z_][\\w:.-]*)").find(t)?.groupValues?.get(1) ?: return false
        return t.endsWith("</$open>") || (t.lines().size == 1 && t.endsWith("/>"))
    }

    fun jsonPretty(s: String): String? {
        val t = s.trim()
        if (!(t.startsWith("{") && t.endsWith("}") || t.startsWith("[") && t.endsWith("]"))) return null
        return runCatching {
            when (val v = JSONTokener(t).nextValue()) {
                is JSONObject -> v.toString(2); is JSONArray -> v.toString(2); else -> null
            }
        }.getOrNull()?.replace("\\/", "/")
    }
}
