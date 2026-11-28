package dev.oscar.hermes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/** One /dash response: HTTP status, raw JSON body, and a short error text (blank when ok). */
data class AdminResp(val code: Int, val body: String, val error: String = "") {
    val ok: Boolean get() = code in 200..299
    fun obj(): JSONObject = JSONObject(body)
    fun arr(): JSONArray = JSONArray(body)
}

/**
 * Read-only dashboard API of the PC relay: GET /dash/<path> (skills, memory, tools/toolsets, dashboard/plugins,
 * analytics/usage, status). Same host, port and Bearer key as every other relay call of the app.
 */
class AdminApi(private val hub: ChatController) {

    /** GET /dash/<path> for [profile] (the app's selected profile, or any other one for usage). */
    suspend fun get(path: String, profile: String): AdminResp = withContext(Dispatchers.IO) {
        val key = hub.store.apiKey ?: return@withContext AdminResp(401, "", hub.s(Str.err_set_key))
        runCatching {
            hub.relayHttp.newCall(Request.Builder().url(dashUrl(path, profile)).header("Authorization", "Bearer $key").build())
                .execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    AdminResp(r.code, text, if (r.isSuccessful) "" else errorText(r.code, text))
                }
        }.getOrElse { AdminResp(0, "", it.message ?: it.javaClass.simpleName) }
    }

    /** PUT /dash/<path> with a JSON body. The relay allows only skills/toggle and tools/toolsets/<name>. */
    suspend fun put(path: String, profile: String, body: JSONObject): AdminResp = withContext(Dispatchers.IO) {
        val key = hub.store.apiKey ?: return@withContext AdminResp(401, "", hub.s(Str.err_set_key))
        val req = Request.Builder().url(dashUrl(path, profile)).header("Authorization", "Bearer $key")
            .put(body.toString().toRequestBody(JSON_TYPE)).build()
        runCatching {
            hub.relayHttp.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                AdminResp(r.code, text, if (r.isSuccessful) "" else errorText(r.code, text))
            }
        }.getOrElse { AdminResp(0, "", it.message ?: it.javaClass.simpleName) }
    }

    /** Relay URL of /dash/<path>; a path may already carry a query (e.g. "logs?file=errors"), the profile is then added with '&'. */
    private fun dashUrl(path: String, profile: String): String {
        val pq = profileQuery(profile).removePrefix("?")
        val sep = if (pq.isEmpty()) "" else if (path.contains('?')) "&$pq" else "?$pq"
        return hub.store.relayBase().trimEnd('/') + "/dash/" + path.trimStart('/') + sep
    }

    /** ?profile=<name>, URL-encoded; omitted for 'default' (the dashboard's own default when absent). */
    internal fun profileQuery(profile: String): String =
        if (profile.isBlank() || profile == "default") "" else "?profile=" + URLEncoder.encode(profile, "UTF-8")

    /** Relay error JSON's "error" field when present, else the status; 503 means the dashboard is still starting. */
    private fun errorText(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return msg.ifBlank { if (code == 503) "" else "HTTP $code" }
    }
}
