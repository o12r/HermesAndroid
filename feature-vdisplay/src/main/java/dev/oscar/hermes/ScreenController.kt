package dev.oscar.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.UUID
class ScreenController(val hub: ChatController) {
    val store get() = hub.store
    val scope get() = hub.scope
    val platform get() = hub.platform
    val main get() = hub.main
    fun s(key: String, vararg a: Any): String = hub.s(key, *a)

    /** Elevation requests from the PC agent (phone-approved, no UAC prompt on the PC). */
    var elevate by mutableStateOf<List<JSONObject>>(emptyList())

    suspend fun pollElevate() {
        while (true) {
            val list = runCatching {
                withContext(Dispatchers.IO) { hub.relayCall("GET", "/elevate/pending") }?.let { org.json.JSONArray(it) }
            }.getOrNull()
            elevate = if (list == null) emptyList() else List(list.length()) { list.getJSONObject(it) }
            kotlinx.coroutines.delay(if (platform.visible) 3000L else 20_000L)     // background: poll rarely
        }
    }

    fun decideElevate(id: String, ok: Boolean) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { hub.relayCall("POST", "/elevate/" + (if (ok) "approve/" else "deny/") + id) } }
        elevate = elevate.filter { it.optString("id") != id }
    }

    /** PC virtual screens: null = unknown/old relay, "none" = driver not installed, else "ok".
     *  Agent screen and screen-off are independent switches. */
    var vdMode by mutableStateOf<String?>(null)
    var vdAgent by mutableStateOf(false)
    var vdScreenOff by mutableStateOf(false)
    var vdScreenOffAvailable by mutableStateOf(true)
    var vdBusy by mutableStateOf(false)
    /** Sessions that have an agent screen open on the PC (each session gets its own). */
    var vdAgents by mutableStateOf<Set<String>>(emptySet())

    // ---- screen manager (relay /vdisplay/screens, /vdisplay/shot, /vdisplay/off)
    var pcScreens by mutableStateOf<List<PcScreen>>(emptyList())
    var screensBusy by mutableStateOf(false)

    fun openScreens(from: Screen) { hub.openPage("pc") }

    fun loadScreens() = scope.launch {
        screensBusy = true
        try {
            val o = withContext(Dispatchers.IO) {
                hub.relayHttp.newCall(hub.relayRequest("/vdisplay/screens", JSONObject())).execute().use { r ->
                    if (!r.isSuccessful) throw ApiException("HTTP ${r.code}")
                    JSONObject(r.body!!.string())
                }
            }
            val a = o.optJSONArray("screens") ?: JSONArray()
            fun JSONObject.optS(k: String) = optString(k).ifBlank { null }
            val raw = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { j ->
                PcScreen(j.optString("name"), j.optString("kind"), j.optString("role"), j.optInt("index"), j.optBoolean("on"),
                    j.optInt("w"), j.optInt("h"), j.optInt("hz"), j.optS("session"), j.optS("profile"), j.optS("cwd"))
            }
            pcScreens = raw
            vdAgents = raw.mapNotNull { it.session }.toSet()
            // Name each agent screen after its session, grouped by that session's profile and project.
            val key = store.apiKey ?: return@launch
            val named = raw.filter { it.kind == "agent" }.groupBy { it.profile ?: "default" }.flatMap { (prof, list) ->
                val titles = runCatching { ApiClient(store.relayBase(), prof, key).sessions().associate { it.id to it.title } }
                    .getOrDefault(emptyMap())
                val projs = runCatching {
                    val t = hub.conn.gatewayFor(prof).call("projects.tree", JSONObject().put("preview_limit", 0).put("session_limit", 1000))
                    val n = t.optJSONArray("projects") ?: JSONArray()
                    (0 until n.length()).mapNotNull { n.optJSONObject(it) }.flatMap { p ->
                        val label = if (p.optBoolean("isNoProject")) s(Str.home) else p.optString("label")
                        val ids = p.optJSONArray("sessionIds") ?: JSONArray()
                        (0 until ids.length()).map { ids.optString(it) to label }
                    }.toMap()
                }.getOrDefault(emptyMap())
                list.map { it.copy(title = titles[it.session], project = projs[it.session]) }
            }
            pcScreens = raw.filter { it.kind != "agent" } + named
        } catch (e: Exception) { hub.status = e.message } finally { screensBusy = false }
    }

    /** JPEG of one PC screen, decoded. */
    suspend fun screenShotBytes(name: String): ByteArray = withContext(Dispatchers.IO) {
        hub.relayHttp.newCall(hub.relayRequest("/vdisplay/shot", JSONObject().put("display", name))).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(runCatching { JSONObject(r.body!!.string()).optString("error") }
                .getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}")
            r.body!!.bytes()
        }
    }

    /** Close a session's agent screen (only the agent opens one; the phone may close it). */
    fun closeAgentScreen(session: String) = scope.launch {
        screensBusy = true
        try {
            withContext(Dispatchers.IO) {
                hub.relayHttp.newCall(hub.relayRequest("/vdisplay/off", JSONObject().put("session", session))).execute().close()
            }
        } catch (e: Exception) { hub.status = e.message } finally { screensBusy = false }
        if (session == hub.cur.id) vdAgent = false
        loadScreens()
    }

    fun vdisplay(op: String, display: String? = null) = scope.launch {
        vdBusy = true
        try {
            val o = withContext(Dispatchers.IO) {
                val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
                val url = store.relayBase().trimEnd('/') + "/vdisplay/" + op
                okhttp3.OkHttpClient.Builder().readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $key")
                        .post(JSONObject().put("session", hub.cur.id ?: "").apply { display?.let { put("display", it) } }.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                        if (r.code == 404) return@withContext null
                        runCatching { JSONObject(r.body!!.string()) }.getOrDefault(JSONObject())
                    }
            }
            vdMode = when {
                o == null -> null
                o.has("installed") && !o.optBoolean("installed") -> "none"
                else -> "ok"
            }
            if (o != null) {
                val m = o.optString("mode")
                vdAgent = if (o.has("agent")) o.optBoolean("agent") else m == "extend"
                vdScreenOff = if (o.has("screen_off")) o.optBoolean("screen_off") else m == "second-only"
                if (o.has("user_screen_available")) vdScreenOffAvailable = o.optBoolean("user_screen_available")
                o.optJSONObject("agents")?.let { a -> vdAgents = a.keys().asSequence().toSet() }
                    ?: hub.cur.id?.let { id -> vdAgents = if (vdAgent) vdAgents + id else vdAgents - id }
            }
            o?.optString("error")?.takeIf { it.isNotBlank() }?.let { hub.status = it }
        } catch (e: Exception) { hub.status = e.message } finally { vdBusy = false }
        if (op.startsWith("screen-") && hub.activePage?.page == "pc") loadScreens()
    }

}
