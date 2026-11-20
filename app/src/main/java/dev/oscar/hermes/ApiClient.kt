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
data class Msg(val role: String, val content: String, val id: Int? = null)

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

    suspend fun health(): String = call(Request.Builder().url("$root/health").build()).optString("relay")

    suspend fun sessions(): List<Session> {
        val a = call(req("/api/sessions", mapOf("limit" to "100", "order" to "recent")).build()).optJSONArray("sessions") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Session(it.getString("id"), it.optStringOrNull("title"), it.optStringOrNull("preview"),
                it.optDouble("last_active").takeIf { d -> !d.isNaN() }, it.optInt("message_count"), it.optStringOrNull("source"))
        }
    }

    suspend fun messages(id: String): List<Msg> {
        val a = call(req("/api/sessions/$id/messages").build()).optJSONArray("messages") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("role") in setOf("user", "assistant") }
            .map { Msg(it.getString("role"), contentText(it.opt("content")), it.optInt("id", 0).takeIf { i -> i > 0 }) }
            .filter { it.content.isNotBlank() }
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
        fun isTailscaleHost(h: String): Boolean {
            if (h.endsWith(".ts.net") || h.endsWith(".local")) return true
            val p = h.split(".").mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return false
            return (p[0] == 100 && p[1] in 64..127) || p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
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
