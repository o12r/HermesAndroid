package dev.oscar.hermes

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
