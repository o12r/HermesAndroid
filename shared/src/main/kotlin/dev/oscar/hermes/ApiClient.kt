package dev.oscar.hermes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Session(val id: String, val title: String?, val preview: String?, val lastActive: Double?, val count: Int, val source: String?)
data class ModelChoice(val provider: String, val providerName: String, val model: String, val reasoning: Boolean) {
    val key get() = "$provider|$model"
}
data class ModelOptions(val currentProvider: String, val currentModel: String, val choices: List<ModelChoice>)
data class Skill(val name: String, val description: String)
data class Msg(val role: String, val content: String, val id: Int? = null, val steps: List<Step> = emptyList(), val ts: Long = 0L)

/** A window of history from the newest end. [msgs] are whole turns, oldest first. [nextOffset] is the
 *  newest-end offset where the next older batch starts; [turns] = user turns in the window; [hasOlder] = rows remain before it. */
data class HistoryPage(val msgs: List<Msg>, val nextOffset: Int, val turns: Int, val hasOlder: Boolean)

/** One step of Hermes's work shown (collapsed) above a reply: thinking text or a tool call with its output. */
data class Step(val kind: String, val name: String, val detail: String, val output: String? = null, val toolId: String? = null)

object Steps {
    /** Short, readable form of a tool's arguments (the command for terminal, the code for execute_code, ...). */
    fun argsText(name: String, args: Any?): String {
        val o = when (args) { is JSONObject -> args; is String -> runCatching { JSONObject(args) }.getOrNull(); else -> null }
            ?: return (args?.toString() ?: "").take(600)
        for (k in listOf("command", "code", "query", "url", "path", "goal", "question")) if (o.has(k) && !o.isNull(k)) {
            val v = o.opt(k); return (if (v is String) v else v.toString()).take(1200)
        }
        if (name == "clarify") o.optJSONArray("questions")?.let { q ->
            return (0 until q.length()).mapNotNull { q.optJSONObject(it)?.optString("question") }.joinToString(" / ") }
        return o.toString().take(600)
    }

    /** Tool output for display; clarify results become "question → answer". */
    fun outputText(name: String, raw: String): String {
        if (name == "clarify") runCatching {
            val r = JSONObject(raw).optJSONArray("responses") ?: JSONArray()
            return (0 until r.length()).mapNotNull { r.optJSONObject(it) }.joinToString("\n") { q ->
                val a = q.opt("user_response")
                val ans = when (a) { is JSONArray -> (0 until a.length()).joinToString(", ") { a.optString(it) }; null, JSONObject.NULL -> "(" + q.optString("status") + ")"; else -> a.toString() }
                q.optString("question") + " → " + ans }
        }
        val pretty = runCatching { JSONObject(raw).let { o -> o.optString("output").ifBlank { null } ?: o.optString("result").ifBlank { null } ?: raw } }.getOrDefault(raw)
        return if (pretty.length > 3000) pretty.take(3000) + "\n…(" + (pretty.length - 3000) + " more chars)" else pretty
    }

    fun thinkingOf(row: JSONObject): String {
        for (k in listOf("reasoning", "reasoning_content")) if (!row.isNull(k)) row.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        val rd = row.opt("reasoning_details").let { if (it is String) runCatching { JSONArray(it) }.getOrNull() else it as? JSONArray } ?: return ""
        val out = StringBuilder()
        fun walk(x: Any?) { when (x) {
            is JSONObject -> { if (x.optString("type") == "thinking") x.optString("thinking").takeIf { it.isNotBlank() }?.let { out.append(it).append("\n") }
                if (x.optString("type") == "reasoning.text") x.optString("text").takeIf { it.isNotBlank() }?.let { out.append(it).append("\n") }
                x.keys().forEach { walk(x.opt(it)) } }
            is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
        } }
        walk(rd); return out.toString().trim()
    }
}

class ApiException(msg: String, val unreachable: Boolean = false) : IOException(msg)

/**
 * HTTP side of the phone relay: forwards /api/... to the gateway's native (desktop) HTTP API.
 * Every request carries `profile`; the relay mints a single-use gateway ticket per request.
 */
class ApiClient(relayUrl: String, private val profile: String, private val key: String) {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()
    private val root: String = run {
        val u = URI(relayUrl.trim())
        if (u.scheme == "http" && !isTailscaleHost(u.host ?: ""))
            throw ApiException("Plain http is only allowed to Tailscale or private LAN addresses")
        relayUrl.trim().trimEnd('/')
    }

    private fun url(path: String, q: Map<String, String> = emptyMap()) = (root + path).toHttpUrl().newBuilder().apply {
        addQueryParameter("profile", profile); q.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()
    private fun req(path: String, q: Map<String, String> = emptyMap()) =
        Request.Builder().url(url(path, q)).header("Authorization", "Bearer $key")

    private suspend fun call(r: Request): JSONObject = withContext(Dispatchers.IO) {
        try {
            http.newCall(r).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(errorText(resp.code, body))
                if (body.isBlank()) JSONObject() else JSONObject(body)
            }
        } catch (e: IOException) { throw wrap(e) }
    }

    /** The PC's private LAN addresses (relay GET /lan), default-route adapter first. */
    suspend fun lanIps(): List<String> = call(req("/lan").build()).optJSONArray("ips")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()

    suspend fun health(): String = call(Request.Builder().url("$root/health").build()).optString("relay")

    suspend fun sessions(): List<Session> {
        val a = call(req("/api/sessions", mapOf("limit" to "100", "order" to "recent")).build()).optJSONArray("sessions") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Session(it.getString("id"), it.optStringOrNull("title"), it.optStringOrNull("preview"),
                it.optDouble("last_active").takeIf { d -> !d.isNaN() }, it.optInt("message_count"), it.optStringOrNull("source"))
        }
    }

    /** The gateway returns at most 500 rows per call, and with no paging params only the newest 500 (the rest were cut off).
     *  Page oldest-first with limit/offset until a short page. */
    suspend fun messages(id: String): List<Msg> {
        val a = JSONArray()
        val seen = HashSet<Int>()
        var offset = 0
        while (true) {
            // include_compacted=true: the DISPLAY view, so rows archived by context compaction are returned too.
            val page = call(req("/api/sessions/$id/messages", mapOf("limit" to "500", "offset" to "$offset", "order" to "oldest", "include_compacted" to "true")).build())
                .optJSONArray("messages") ?: JSONArray()
            for (k in 0 until page.length()) {
                val row = page.optJSONObject(k) ?: continue
                val rid = row.optInt("id", 0)
                if (rid > 0 && !seen.add(rid)) continue   // de-dup by id across pages
                a.put(row)
            }
            offset += page.length()
            if (page.length() < 500) break
        }
        return fold(a)
    }

    /** Fold chronological raw rows into Msgs: one assistant Msg per turn. */
    private fun fold(a: JSONArray): List<Msg> {
        // Fold one turn into a single assistant Msg whose steps keep the real order: thinking, text, tool calls
        // (with their outputs). Every assistant text segment between tool calls is kept, not just the last.
        val out = mutableListOf<Msg>()
        var pending = mutableListOf<Step>()
        var lastId: Int? = null
        var lastTs = 0L
        // Tool calls made by hidden compaction-handoff rows: their tool outputs are hidden too.
        val hiddenCallIds = HashSet<String>()
        fun flush() {
            if (pending.isEmpty()) { lastId = null; return }
            val text = pending.filter { it.kind == "text" }.joinToString("") { it.detail }
            out.add(Msg("assistant", text, lastId, pending.toList(), lastTs))
            pending = mutableListOf(); lastId = null
        }
        for (i in 0 until a.length()) {
            val r = a.optJSONObject(i) ?: continue
            val id = r.optInt("id", 0).takeIf { it > 0 }
            val ts = r.optDouble("timestamp", 0.0).let { if (it.isNaN()) 0L else if (it < 1e11) (it * 1000).toLong() else it.toLong() }
            // Compaction scaffolding (handoff / "[PRIOR CONTEXT" rows) is stored with display_kind=hidden: skip it,
            // but a hidden user row still ends the turn.
            if (r.optString("display_kind") == "hidden") {
                if (r.optString("role") == "user") flush()
                if (r.optString("role") == "assistant") {
                    val hc = r.opt("tool_calls").let { if (it is String) runCatching { JSONArray(it) }.getOrNull() else it as? JSONArray }
                    if (hc != null) for (j in 0 until hc.length()) hc.optJSONObject(j)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { hiddenCallIds.add(it) }
                }
                continue
            }
            if (r.optString("role") == "tool" && r.optString("tool_call_id") in hiddenCallIds) continue
            when (r.optString("role")) {
                "user" -> {
                    flush()
                    contentText(r.opt("content")).takeIf { it.isNotBlank() }?.let { out.add(Msg("user", it, id, ts = ts)) }
                }
                "assistant" -> {
                    lastId = id ?: lastId; if (ts > 0) lastTs = ts
                    Steps.thinkingOf(r).takeIf { it.isNotBlank() }?.let { pending.add(Step("thinking", "", it)) }
                    contentText(r.opt("content")).takeIf { it.isNotBlank() }?.let { pending.add(Step("text", "", it)) }
                    val calls = r.opt("tool_calls").let { if (it is String) runCatching { JSONArray(it) }.getOrNull() else it as? JSONArray }
                    if (calls != null) for (j in 0 until calls.length()) calls.optJSONObject(j)?.let { c ->
                        val f = c.optJSONObject("function"); val name = f?.optString("name").orEmpty().ifBlank { c.optString("name") }
                        pending.add(Step("tool", name, Steps.argsText(name, f?.opt("arguments")), null, c.optString("id")))
                    }
                }
                "tool" -> {
                    val tid = r.optString("tool_call_id"); val name = r.optString("tool_name")
                    val k = pending.indexOfLast { it.toolId == tid }.takeIf { it >= 0 } ?: pending.indexOfLast { it.kind == "tool" && it.output == null }
                    if (k >= 0) pending[k] = pending[k].copy(output = Steps.outputText(pending[k].name.ifBlank { name }, contentText(r.opt("content"))))
                }
            }
        }
        flush()
        return out
    }

    private fun isTurnStart(r: JSONObject?): Boolean =
        r != null && r.optString("role") == "user" && r.optString("display_kind") != "hidden"

    /** The newest [turns] user turns ending [beforeOffset] rows back from the newest (0 = the newest turns).
     *  Uses order=latest (offset counts back from the newest row; rows come back chronological). The window is cut at
     *  the [turns]-th user row from the end, so it always starts on a turn boundary. */
    suspend fun messagesPage(id: String, turns: Int, beforeOffset: Int = 0): HistoryPage {
        val want = turns.coerceAtLeast(1)
        val chunkRows = 200
        val chunks = ArrayList<JSONArray>()     // newest chunk first; rows inside a chunk are chronological
        var fetched = 0
        var userRows = 0
        var exhausted = false
        while (true) {
            val page = call(req("/api/sessions/$id/messages", mapOf("limit" to "$chunkRows", "offset" to "${beforeOffset + fetched}",
                "order" to "latest", "include_compacted" to "true")).build()).optJSONArray("messages") ?: JSONArray()
            chunks.add(page)
            fetched += page.length()
            for (k in 0 until page.length()) if (isTurnStart(page.optJSONObject(k))) userRows++
            if (page.length() < chunkRows) { exhausted = true; break }
            if (userRows >= want) break
        }
        val all = ArrayList<JSONObject>()
        for (ch in chunks.asReversed()) for (k in 0 until ch.length()) ch.optJSONObject(k)?.let { all.add(it) }
        var cut = 0
        var seen = 0
        for (k in all.indices.reversed()) {
            if (isTurnStart(all[k]) && ++seen == want) { cut = k; break }
        }
        val kept = all.subList(cut, all.size)
        val a = JSONArray()
        for (r in kept) a.put(r)
        return HistoryPage(fold(a), beforeOffset + kept.size, kept.count { isTurnStart(it) }, cut > 0 || !exhausted)
    }

    /** Fenced edit: read the session's revision, then send the change with a fresh request id. */
    private suspend fun identity(id: String): Map<String, Any> {
        val s = call(req("/api/sessions/$id/mutation-snapshot").build())
        return mapOf("request_id" to UUID.randomUUID().toString(),
            "expected_revision" to s.optInt("runtime_revision"), "expected_generation" to s.optInt("runtime_generation"))
    }
    suspend fun rename(id: String, title: String) {
        val b = JSONObject().put("title", title).put("profile", profile)
        identity(id).forEach { (k, v) -> b.put(k, v) }
        call(req("/api/sessions/$id").patch(b.toString().toRequestBody(json)).build())
    }
    suspend fun delete(id: String) {
        call(req("/api/sessions/$id", identity(id).mapValues { it.value.toString() }).delete().build())
    }

    private fun errorText(code: Int, body: String): String {
        val m = runCatching { JSONObject(body).let { o -> o.optJSONObject("error")?.optString("message")
            ?: o.opt("detail")?.toString() ?: o.optString("error") } }.getOrNull()?.takeIf { it.isNotBlank() }
        return when (code) {
            401 -> "Wrong API key (HTTP 401)"
            503 -> "Hermes gateway isn't running on the PC (${m ?: "503"})"
            else -> "HTTP $code: ${m ?: body.take(200)}"
        }
    }

    private fun wrap(e: IOException): IOException = when (e) {
        is ApiException -> e
        is UnknownHostException, is ConnectException, is SocketTimeoutException, is NoRouteToHostException ->
            ApiException("Can't reach the PC relay. Is Tailscale on (phone and PC) and Hermes running?", true)
        else -> ApiException(e.message ?: e.javaClass.simpleName, true)
    }

    companion object {
        /** Plain http is only allowed to Tailscale (100.64.0.0/10, *.ts.net) and private LAN addresses. */
        /** User opt-in (Settings > Connection): also allow plain http to private LAN addresses. Off by default. */
        @Volatile var allowLanHttp = false
        fun isTailscaleHost(h: String): Boolean {
            if (h.endsWith(".ts.net")) return true
            val p = h.split(".").mapNotNull { it.toIntOrNull() }
            if (p.size == 4 && p[0] == 100 && p[1] in 64..127) return true
            if (!allowLanHttp) return false
            if (h.endsWith(".local")) return true
            return p.size == 4 && (p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168))
        }
        fun contentText(c: Any?): String = when (c) {
            null, JSONObject.NULL -> ""
            is String -> c
            is JSONArray -> (0 until c.length()).mapNotNull { i ->
                val p = c.optJSONObject(i) ?: return@mapNotNull c.optString(i)
                when (p.optString("type")) { "text", "input_text" -> p.optString("text"); "image_url", "input_image", "image" -> "🖼️ [image]"; else -> null }
            }.joinToString("\n")
            else -> c.toString()
        }

        /** Parse a `model.options` RPC result. */
        fun parseModelOptions(d: JSONObject): ModelOptions {
            val out = mutableListOf<ModelChoice>()
            val ps = d.optJSONArray("providers") ?: JSONArray()
            for (i in 0 until ps.length()) {
                val p = ps.getJSONObject(i)
                if (!p.optBoolean("authenticated") && !p.optBoolean("is_current")) continue
                val caps = p.optJSONObject("capabilities") ?: JSONObject()
                val ms = p.optJSONArray("models") ?: JSONArray()
                for (j in 0 until ms.length()) {
                    val m = ms.opt(j).let { if (it is JSONObject) it.optString("id") else it.toString() }
                    if (m.isBlank()) continue
                    out += ModelChoice(p.getString("slug"), p.optString("name", p.getString("slug")), m,
                        caps.optJSONObject(m)?.optBoolean("reasoning", true) ?: true)
                }
            }
            return ModelOptions(d.optString("provider"), d.optString("model"), out)
        }
    }
}

private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
