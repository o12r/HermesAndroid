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
class ConnectionController(val hub: ChatController) {
    val store get() = hub.store
    val scope get() = hub.scope
    val platform get() = hub.platform
    val main get() = hub.main
    fun s(key: String, vararg a: Any): String = hub.s(key, *a)

    var sessions by mutableStateOf<List<Session>>(emptyList())
    /** Profiles found on the PC (name to description); cached so the menu works offline. */
    var profiles by mutableStateOf(store.profileCache.ifEmpty { listOf("default").map { it to "" } })
    fun profileNames() = profiles.map { it.first }

    /** Scan the PC for Hermes profiles (profiles.list over the relay). */
    /** Fixed profile order: the user's saved order, then alphabetical; never by recent use. */
    var profileOrder by mutableStateOf(store.profileOrder)
    val orderedProfiles: List<Pair<String, String>> get() = profiles.sortedWith(
        compareBy<Pair<String, String>> { p -> profileOrder.indexOf(p.first).let { if (it < 0) Int.MAX_VALUE else it } }.thenBy { it.first.lowercase() })
    fun saveProfileOrder(names: List<String>) { profileOrder = names; store.profileOrder = names }
    fun scanProfiles() = scope.launch {
        runCatching {
            val r = gateway().call("profiles.list", JSONObject())
            val a = r.optJSONArray("profiles") ?: JSONArray()
            val found = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
                o.optString("name") to o.str("display_name").ifBlank { o.str("description") }
            }.filter { it.first.isNotBlank() }
            if (found.isNotEmpty()) { profiles = found; store.profileCache = found }
        }.onFailure { platform.log("HermesRpc", "profiles.list: $it") }
    }
    // desktop-protocol state: one connection per profile
    val gws = mutableMapOf<String, GatewayWs>()
    val gwLock = Mutex()
    val gw get() = gws[hub.profile]

    /** Banner Cancel: stop waiting on the current request, drop reconnect attempts, and clear the banner. */
    fun cancelRequest() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        if (hub.cur.streaming) hub.msg.stop()
        hub.error = null; hub.offline = false; hub.status = null
    }

    fun fail(e: Throwable) {
        hub.offline = (e as? ApiException)?.unreachable == true
        val m = e.message ?: e.toString()
        // The gateway answers storage_unavailable when a session's stored creation receipt is missing (e.g. after an orphan reap).
        hub.error = if (m.contains("storage_unavailable")) s(Str.err_session_corrupt) + " (storage_unavailable)" else m
    }

    /** The phone's own Wi-Fi IPv4 address, or null when it isn't on Wi-Fi. */
    fun wifiIp(): String? = localNet()?.first
    fun localNet(): Pair<String, String?>? = platform.localNet()
    fun sameSubnet(a: String?, b: String?) = a != null && b != null && a.substringBeforeLast('.') == b.substringBeforeLast('.')

    /** Use the main (Tailscale) URL if it answers, else the LAN URL; remembers the winner. On the PC's own Wi-Fi the LAN URL goes first
     *  (direct, cheaper on battery), and the LAN URL is learned automatically (from the relay, or by scanning the subnet). */
    suspend fun pickRelay(): Boolean {
        val key = store.apiKey ?: return false
        val me = wifiIp()
        val lanFirst = sameSubnet(me, runCatching { java.net.URI(store.lanUrl).host }.getOrNull())
        val gwUrl = localNet()?.second?.let { "http://$it:8643" }
        val order = (if (lanFirst) listOf(store.lanUrl, store.relayUrl, gwUrl) else listOf(store.relayUrl, store.lanUrl, gwUrl)).filterNotNull().filter { it.isNotBlank() }.distinct()
        for (u in order) {
            if (runCatching { withTimeout(4000) { ApiClient(u, hub.profile, key).health() } }.isSuccess) {
                if (store.activeRelay != u) { store.activeRelay = u; gws.values.forEach { it.close() }; gws.clear(); hub.chats.forEach { it.liveId = null } }
                if (me != null) scope.launch { learnLan(u, key, me) }
                if (u == gwUrl && store.lanUrl.isBlank()) store.lanUrl = u
                return true
            }
        }
        return scanLan(key, me)
    }

    /** Connected through [via]: ask the PC for its LAN addresses and remember the one on our Wi-Fi subnet. */
    var lanLearnedFor: String? = null
    suspend fun learnLan(via: String, key: String, me: String) {
        if (lanLearnedFor == me) return
        val ip = runCatching { withTimeout(5000) { ApiClient(via, hub.profile, key).lanIps() } }.getOrNull()?.firstOrNull { sameSubnet(it, me) } ?: return
        lanLearnedFor = me
        val url = "http://$ip:8643"
        if (store.lanUrl != url) { store.lanUrl = url; lanAutoSet = url }
    }
    var lanAutoSet by mutableStateOf<String?>(null)

    /** Neither URL answers but we're on Wi-Fi: look for the relay on our /24 (once a minute at most) and save it as the LAN URL. */
    var lastScan = 0L
    suspend fun scanLan(key: String, me: String?): Boolean {
        if (me == null || System.currentTimeMillis() - lastScan < 60_000) return false
        lastScan = System.currentTimeMillis()
        val base = me.substringBeforeLast('.')
        val found: String? = withContext(Dispatchers.IO) {
            val sem = Semaphore(48)
            coroutineScope {
                val jobs = (1..254).map { "$base.$it" }.filter { it != me }.map { ip ->
                    async {
                        sem.withPermit {
                            val name = runCatching { withTimeout(900) { ApiClient("http://$ip:8643", hub.profile, key).health() } }.getOrNull()
                            if (name == "hermes-phone-relay") ip else null
                        }
                    }
                }
                jobs.awaitAll().filterNotNull().firstOrNull()
            }
        } ?: return false
        val url = "http://$found:8643"
        store.lanUrl = url; lanAutoSet = url; store.activeRelay = url
        gws.values.forEach { it.close() }; gws.clear(); hub.chats.forEach { it.liveId = null }
        return true
    }

    /** Keep the relay connection up (also in the background): reconnect with backoff, then re-sync every open chat. */
    val reconnectJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    var notifiedDrop = false
    /** Profiles whose background reconnect gave up after 3 tries; resumed when the app comes to the foreground. */
    val gaveUp = mutableSetOf<String>()
    fun onForeground() {
        hub.msg.kickProcPoll()
        if (gaveUp.isEmpty()) return
        platform.keepAliveStart()
        gaveUp.toList().forEach { gaveUp.remove(it); scheduleReconnect(it) }
    }
    fun scheduleReconnect(prof: String) {
        if (!store.hasKey || reconnectJobs[prof]?.isActive == true) return
        if (hub.chats.none { it.profile == prof && it.id != null } && prof != hub.profile) return
        reconnectJobs[prof] = scope.launch {
            var delayMs = 1500L
            var bgFails = 0
            while (true) {
                kotlinx.coroutines.delay(delayMs)
                if (runCatching { pickRelay(); gatewayFor(prof); resync(prof) }.isSuccess) {
                    if (prof == hub.profile) { hub.offline = false; hub.error = null }
                    notifiedDrop = false; return@launch
                }
                if (!notifiedDrop) {      // notify at the first failed retry (whether or not the app is open)
                    notifiedDrop = true; platform.alert(s(Str.n_disconnected), s(Str.n_disconnected_text))
                }
                // In the background: 3 failed tries and we stop (no endless wake-ups); the foreground reconnects.
                if (!platform.visible && ++bgFails >= 3) {
                    gaveUp.add(prof)
                    if (!notifiedDrop) { notifiedDrop = true; platform.alert(s(Str.n_disconnected), s(Str.n_disconnected_text)) }
                    platform.keepAliveStop(); return@launch
                }
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    /** After a (re)connect: re-attach every open chat of [prof]; a turn that finished meanwhile is reloaded. */
    suspend fun resync(prof: String) {
        val g = gatewayFor(prof)
        for (c in hub.chats.filter { it.profile == prof && it.id != null && (it === hub.cur || it.dropped || it.streaming) }) {
            runCatching {
                val running = attach(g, c)
                if (!running && (c.dropped || c.streaming)) { c.streaming = false; c.status = null; hub.msg.loadMessagesInto(c) }
                else if (running) { c.streaming = true; c.status = s(Str.reconnected_running) }
                c.dropped = false
            }
        }
    }

    // ---------- gateway connection ----------
    suspend fun gateway(): GatewayWs = gatewayFor(hub.profile)

    suspend fun gatewayFor(prof: String): GatewayWs = gwLock.withLock {
        gws[prof]?.takeIf { it.open }?.let { return it }
        gws.remove(prof)?.close()
        hub.chats.filter { it.profile == prof }.forEach { it.liveId = null }
        lateinit var g: GatewayWs
        g = GatewayWs(store.relayBase(), prof, store.apiKey ?: throw ApiException("Set the API key in Settings"),
            onEvent = { t, sid, p -> scope.launch(main) { hub.engine.onEvent(prof, t, sid, p) } },
            onServerRequest = { id, m, p -> scope.launch(main) { hub.msg.onServerRequest(g, id, m, p) } },
            onClosed = { why -> scope.launch(main) {
                if (gws[prof] !== g) return@launch      // replaced or closed on purpose
                gws.remove(prof)
                for (c in hub.chats.filter { it.profile == prof }) {
                    c.liveId = null
                    if (c.streaming) { c.dropped = true; c.status = s(Str.reconnecting) }
                }
                if (prof == hub.profile && hub.cur.streaming) why?.let { hub.error = it; hub.offline = true }
                scheduleReconnect(prof)
            } }, logger = { tag, msg -> platform.log(tag, msg) })
        withContext(Dispatchers.IO) { g.connect() }
        gws[prof] = g
        if (prof == hub.profile && hub.proj.serverCommands.isEmpty()) scope.launch { hub.proj.loadCatalog() }
        g
    }

    /** Attach a chat to its live session (creates one for a new chat). Returns whether a turn is running. */
    suspend fun attach(g: GatewayWs, c: Chat): Boolean {
        // A project's remembered cwd may no longer exist on the PC; the gateway then refuses with invalid_params.
        // Fall back to the profile's default workspace instead of failing the new chat.
        fun createParams(withCwd: Boolean) = JSONObject().put("source", "gui").apply {
                    if (withCwd) c.newCwd?.let { put("cwd", it).put("cwd_explicit", true) }
                    // The pick made before the first message is the new session's own model (per-session override,
                    // like the desktop composer). prompt.submit's model_override only lasts one turn.
                    c.model.substringAfter('|', "").ifBlank { null }?.let { m -> put("model", m)
                        c.model.substringBefore('|', "").ifBlank { null }?.let { put("provider", it) } }
                    c.effort.ifBlank { null }?.let { put("reasoning", it) } }
        val snap = c.id?.let { g.call("session.resume", JSONObject().put("session_id", it).put("omit_messages", true)) }
            ?: (if (c.newCwd == null) g.call("session.create", createParams(false))
                else try { g.call("session.create", createParams(true)) }
                catch (e: ApiException) { if (e.unreachable) throw e; platform.log("HermesRpc", "session.create with cwd failed (${e.message}); retrying without cwd"); g.call("session.create", createParams(false)) }).also { r ->
                c.newCwd = null; c.createdHere = true
                c.id = r.optString("stored_session_id").ifBlank { r.getString("session_id") }
                c.id?.let { store.setSessionModel(it, c.model); store.setSessionEffort(it, c.effort) }
            }
        c.generation = snap.optInt("execution_generation", c.generation)
        c.revision = snap.optInt("revision", c.revision)
        snap.optJSONObject("usage")?.let { applyUsage(c, it) }
        snap.optJSONObject("info")?.let { adoptServerModel(c, it) }
        val live = snap.getString("session_id").also { c.liveId = it }
        snap.optJSONArray("prompts")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { pr ->
            scope.launch(main) { hub.engine.onEvent(c.profile, pr.optString("kind") + ".request", live, pr) } } }
        val running = snap.optBoolean("running")
        if (running) scope.launch(main) { c.streaming = true }
        return running
    }

    /**
     * The PC session is the source of truth for its model: show what it runs (desktop picks included) so the phone
     * never switches it back by accident. A new chat keeps the phone's pick (it is sent with the first prompt).
     */
    fun adoptServerModel(c: Chat, info: JSONObject) {
        val m = info.optString("model").trim()
        if (m.isEmpty()) return
        val key = info.optString("provider").trim() + "|" + m
        val eff = info.optString("reasoning_effort").trim()
        c.serverModel = key; c.serverEffort = eff
        if (c.createdHere && c.model.isNotBlank()) return          // new chat: the phone's choice goes out with the first prompt
        c.model = key; c.effort = eff
        c.id?.let { store.setSessionModel(it, key); store.setSessionEffort(it, eff) }
    }

    /** Context bar + reply token baseline from a PC usage object. Returns (input, output) totals. */
    fun applyUsage(c: Chat, u: JSONObject): Pair<Long, Long>? = hub.engine.applyUsage(c, u)

    /** Live id for [c] on its profile's connection. */
    suspend fun ensureLive(g: GatewayWs, c: Chat = hub.cur): String { c.liveId?.let { return it }; attach(g, c); return c.liveId!! }

    // ---------- sessions ----------
    /** App resumed / network back: make sure every profile with open chats is connected and in sync. */
    fun reconnect() {
        if (!store.hasKey) return
        scope.launch {
            try { pickRelay(); hub.api().health(); hub.offline = false; hub.error = null
                refreshSessions(); if (hub.proj.modelOptions == null) hub.proj.loadMeta()
                for (prof in (hub.chats.map { it.profile } + hub.profile).distinct()) runCatching { resync(prof) }
            } catch (e: Exception) { fail(e) }
        }
    }

    fun setProfileTo(p: String) {
        if (p == hub.profile) return
        // Other profiles' connections and chats stay open, so their running turns keep streaming.
        hub.proj.serverCommands = emptyList()
        hub.profile = p; store.profile = p
        hub.cur = hub.fresh(p); hub.proj.projects = emptyList(); sessions = emptyList()
        hub.openPage("sessions"); hub.proj.loadMeta()
    }

    /** Stored session ids that are open in some client (phone, desktop, CLI) per profile, from the gateway's session.active_list. */
    var liveKeys by mutableStateOf<Map<String, Set<String>>>(emptyMap())

    /** Tell the PC relay which chats this phone has open as tabs (re-sent on every change and every minute), so other devices can mark them active. */
    val tabReporter = scope.launch {
        delay(1500)
        launch { while (true) { delay(60_000); reportTabs() } }
        androidx.compose.runtime.snapshotFlow { hub.tabs.map { it.profile to it.id } }.collect { reportTabs() }
    }
    suspend fun reportTabs() = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray(); hub.tabs.toList().forEach { c -> c.id?.let { arr.put(JSONObject().put("profile", c.profile).put("id", it)) } }
            hub.relayJson("POST", "/tabs", JSONObject().put("device", hub.deviceId).put("tabs", arr).toString())
        }
    }

    fun loadLive() = scope.launch {
        val remote: Map<String, Set<String>> = withContext(Dispatchers.IO) {
            runCatching {
                val a = JSONArray(hub.relayJson("GET", "/tabs", null) ?: "[]")
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("device") != hub.deviceId }
                    .groupBy({ it.optString("profile") }, { it.optString("id") }).mapValues { it.value.toSet() }
            }.getOrDefault(emptyMap())
        }
        val profs = (hub.proj.expandedProfiles + hub.profile).toList()
        val res = profs.map { p -> async {
            p to runCatching { withTimeout(8000) {
                val a = gatewayFor(p).call("session.active_list", JSONObject()).optJSONArray("sessions") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.takeIf { o -> o.optString("status") != "idle" }?.optString("session_key")?.ifBlank { null } }.toSet()  // idle = merely kept alive (e.g. by context probes), not in use
            } }.getOrNull() } }.awaitAll()
        val own = hub.tabs.toList().filter { it.id != null }.groupBy({ it.profile }, { it.id!! }).mapValues { it.value.toSet() }
        val gw = res.mapNotNull { (p, s) -> s?.let { p to it } }.toMap()
        liveKeys = (gw.keys + remote.keys + own.keys).associateWith { p -> gw[p].orEmpty() + remote[p].orEmpty() + own[p].orEmpty() }
    }

    fun refreshSessions() = scope.launch {
        hub.loading = true
        val prof = hub.profile
        hub.proj.primeCtx(prof)
        loadLive()
        hub.proj.expandedProfiles.filter { it != prof }.forEach { hub.proj.loadOther(it) }
        try {
            // Sessions and the project tree load in parallel and are applied together, so the list never
            // shows everything under Home first and re-sorts into projects a moment later.
            val (ss, pj) = kotlinx.coroutines.coroutineScope {
                val a = async { hub.api().sessions() }
                val b = async { runCatching { hub.proj.fetchProjects(gateway()) }.onFailure { platform.log("HermesRpc", "loadProjects: $it") }.getOrNull() }
                a.await() to b.await()
            }
            if (prof != hub.profile) return@launch
            sessions = ss; hub.offline = false; hub.error = null
            if (pj != null) { hub.proj.projects = pj.first; hub.proj.activeProject = pj.second; hub.proj.saveSnap(prof, ss, pj.first, pj.second) }
            hub.currentId?.let { id -> sessions.find { it.id == id }?.title?.let { hub.currentTitle = it } }
        } catch (e: Exception) { fail(e) } finally { hub.loading = false }
        hub.proj.loadSessionCtx()
    }

    /** Reload the current chat's transcript and the session list (top-bar refresh button). */
    var chatRefreshing by mutableStateOf(false)
    fun refreshChat() = scope.launch {
        chatRefreshing = true
        try {
            val c = hub.cur
            if (c.id != null && !c.streaming) hub.msg.loadMessagesInto(c)
            if (c.id != null) { runCatching { ensureLive(gatewayFor(c.profile), c) }; hub.loadChatCtx(c) }
            refreshSessions().join()
        } finally { chatRefreshing = false }
    }

    /** One probed path to the PC relay (test-connection feedback). */
    data class Probe(val label: String, val url: String, val ok: Boolean, val ms: Long, val active: Boolean, val error: String)
    var probes by mutableStateOf<List<Probe>>(emptyList())
    var probing by mutableStateOf(false)
    fun pathLabel(url: String): String {
        val h = runCatching { java.net.URI(url).host }.getOrNull() ?: return s(Str.path_other)
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        return when {
            p.size == 4 && p[0] == 100 && p[1] in 64..127 -> "Tailscale"
            h.endsWith(".ts.net") -> "Tailscale"
            p.size == 4 && (p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)) -> s(Str.path_lan)
            else -> s(Str.path_other)
        }
    }
    /** Probes every known path in parallel (main, LAN, gateway found on our subnet) and shows address, reachability, latency and the one in use. */
    fun testConnections(relay: String, lan: String) = scope.launch {
        val key = store.apiKey ?: run { hub.status = s(Str.err_set_key); return@launch }
        probing = true; probes = emptyList()
        try {
            val gwUrl = localNet()?.second?.let { "http://$it:8643" }
            val urls = listOf(relay.trim().trimEnd('/'), lan.trim().trimEnd('/'), gwUrl).filterNotNull().filter { it.isNotBlank() }.distinct()
            val active = store.activeRelay
            probes = coroutineScope {
                urls.map { u -> async(Dispatchers.IO) {
                    val t0 = platform.elapsedMs()
                    val r = runCatching { withTimeout(5000) { ApiClient(u, hub.profile, key).health() } }
                    Probe(pathLabel(u), u, r.isSuccess, platform.elapsedMs() - t0, r.isSuccess && u == (active ?: relay.trim().trimEnd('/')),
                        r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.TimeoutCancellationException) "timeout" else it.message.orEmpty() }.orEmpty())
                } }.awaitAll()
            }
        } finally { probing = false }
    }

    fun saveSettings(relay: String, lan: String, key: String?) {
        store.relayUrl = relay; store.lanUrl = lan; store.activeRelay = null; if (!key.isNullOrBlank()) store.apiKey = key
        gws.values.forEach { it.close() }; gws.clear(); hub.chats.forEach { it.liveId = null }
        scope.launch {
            try {
                pickRelay(); hub.api().health(); hub.api().sessions()
                gateway(); hub.status = s(Str.connected_relay)
                hub.error = null; hub.offline = false; hub.openPage("sessions"); hub.proj.loadMeta()
            } catch (e: Exception) { fail(e) }
        }
    }
}
