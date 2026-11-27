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
class ProjectController(val hub: ChatController) {
    val store get() = hub.store
    val scope get() = hub.scope
    val platform get() = hub.platform
    val main get() = hub.main
    fun s(key: String, vararg a: Any): String = hub.s(key, *a)

    /** Project groups from projects.tree (Home = sessions outside any project). */
    var projects by mutableStateOf<List<Project>>(emptyList())
    var activeProject by mutableStateOf<String?>(null)
    init { ApiClient.allowLanHttp = store.allowLan; loadSnap(store.profile)?.let { hub.conn.sessions = it.first; projects = it.second; activeProject = it.third } }
    /** Project the user last tapped on the session list; the "New chat" button uses it before the default project. */
    var lastProject by mutableStateOf<Project?>(null)
    val fabProject: Project? get() = lastProject?.let { l -> projects.find { it.id == l.id } ?: l } ?: projects.find { it.id == activeProject }
    /** Folder for the next new chat (project primary path), null = Hermes default. */
    var newChatCwd: String? = null
    var newChatNone = false
    var modelOptions by mutableStateOf<ModelOptions?>(null)
    var skills by mutableStateOf<List<Skill>>(emptyList())
    var serverCommands by mutableStateOf<List<Pair<String, String>>>(emptyList())
    suspend fun loadCatalog() {
        runCatching {
            val g = hub.conn.gateway(); val sid = hub.liveId
            val r = g.call("commands.catalog", if (sid != null) JSONObject().put("session_id", sid) else JSONObject())
            val pairs = r.optJSONArray("pairs") ?: JSONArray()
            val desc = (0 until pairs.length()).mapNotNull { i -> pairs.optJSONArray(i)?.let { it.optString(0) to it.optString(1) } }.toMap()
            val sk = r.optJSONObject("skills")
            skills = sk?.keys()?.asSequence()?.map { Skill(it.removePrefix("/"), desc[it].orEmpty()) }?.toList().orEmpty()
            serverCommands = (0 until pairs.length()).mapNotNull { i -> pairs.optJSONArray(i)?.let { it.optString(0) to it.optString(1) } }
                .filter { (c, _) -> c.startsWith("/") && SLASH_COMMANDS.none { it.first == c } }
        }
    }

    // ---------- meta ----------
    fun loadMeta() = scope.launch {
        runCatching { modelOptions = ApiClient.parseModelOptions(hub.conn.gateway().call("model.options", JSONObject())) }
        loadCatalog()
        hub.conn.scanProfiles()
    }
    fun modelLabel(): String = if (hub.model.isBlank()) "Default: " + (modelOptions?.currentModel?.ifBlank { null } ?: "server")
        else hub.model.substringAfter('|')
    /** True while a model/effort switch is in flight to the PC; the chips show the PC's state until it confirms. */
    var switching by mutableStateOf(false)

    /**
     * Model pick. A chat that already has a PC session switches it on the PC (config.set) and then re-reads the
     * session, so the chip only changes once the PC confirms. A not-yet-created chat keeps the pick locally; it goes
     * out with the first prompt.
     */
    fun chooseModel(key: String) {
        store.setModelFor(hub.profile, key)
        val c = hub.cur
        if (c.id == null || c.createdHere && c.serverModel == null) { hub.model = key; return }
        val m = key.substringAfter('|', "").ifBlank { null }
        val pr = key.substringBefore('|', "").ifBlank { null }
        val payload = JSONObject().put("model", m ?: "reset")
        if (m != null && pr != null) payload.put("provider", pr)
        switchOnPc(c, "model", payload)
    }
    fun chooseEffort(e: String) {
        store.setEffortFor(hub.profile, e)
        val c = hub.cur
        if (c.id == null || c.createdHere && c.serverModel == null) { hub.effort = e; return }
        switchOnPc(c, "reasoning", JSONObject().put("effort", e.ifBlank { JSONObject.NULL }))
    }

    /** A guarded model pick the PC wants confirmed: (message, payload to resend with its confirm token). */
    var modelConfirm by mutableStateOf<Pair<String, JSONObject>?>(null)
    /** Set when a model switch left the chat's context above the new window: the UI offers to compress. */
    var compressAsk by mutableStateOf<Chat?>(null)
    fun answerCompress(yes: Boolean) {
        val c = compressAsk ?: return; compressAsk = null
        if (yes && c === hub.cur) hub.msg.compressChat("")
    }
    fun confirmModel(yes: Boolean) {
        val p = modelConfirm ?: return; modelConfirm = null
        if (yes) switchOnPc(hub.cur, "model", p.second)
    }

    /** Model/effort pick as the PC's revision-fenced session.mutate (what the desktop does), then re-read the session. */
    fun switchOnPc(c: Chat, op: String, payload: JSONObject) = scope.launch {
        switching = true
        try {
            val g = hub.conn.gatewayFor(c.profile); val sid = hub.conn.ensureLive(g, c)
            val r = hub.msg.mutate(op, payload)
            if (r.optString("status") == "confirmation_required" || r.optBoolean("confirm_required")) {
                val token = r.optString("confirm")
                modelConfirm = r.optString("confirm_message").ifBlank { s(Str.confirm_model) } to
                    JSONObject(payload.toString()).put("confirm", token)
            } else (r.optString("note").ifBlank { r.optString("warning") }).ifBlank { null }?.let { c.messages.add(UiMsg("system", it)) }
            syncFromPc(g, c, sid)
            if (op == "model" && modelConfirm == null) runCatching {
                hub.conn.applyUsage(c, g.call("session.stats", JSONObject().put("session_id", sid)))
                if (c.ctxMax > 0 && c.ctxUsed > c.ctxMax) compressAsk = c
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { hub.error = s(Str.switch_failed) + ": " + (e.message ?: e.javaClass.simpleName)
            runCatching { syncFromPc(hub.conn.gatewayFor(c.profile), c, c.liveId ?: return@runCatching) }
        } finally { switching = false }
    }

    /** Re-read model/effort from the PC session (the only source of truth once it exists). */
    suspend fun syncFromPc(g: GatewayWs, c: Chat, sid: String) {
        val info = runCatching { g.call("session.info", JSONObject().put("session_id", sid)) }.getOrNull()
            ?: g.call("session.resume", JSONObject().put("session_id", c.id).put("omit_messages", true)).optJSONObject("info")
            ?: return
        c.createdHere = false
        hub.conn.adoptServerModel(c, info)
    }

    suspend fun loadProjects() {
        val (list, active) = fetchProjects(hub.conn.gateway())
        activeProject = active; projects = list
    }

    /** Sessions + projects of every other expanded profile (cached; refreshed with the session list). */
    var otherData by mutableStateOf<Map<String, Triple<List<Session>, List<Project>, String?>>>(emptyMap())
    /** Profiles whose project tree is expanded on the session page (several can be open at once). */
    var expandedProfiles by mutableStateOf(setOf(store.profile))

    fun toggleProfileExpanded(p: String) {
        expandedProfiles = if (p in expandedProfiles) expandedProfiles - p else expandedProfiles + p
        if (p in expandedProfiles && p != hub.profile) { cachedFor(p)?.let { otherData = otherData + (p to it) }; loadOther(p) }
    }

    fun cachedFor(p: String) = otherData[p] ?: loadSnap(p)

    fun saveSnap(p: String, ss: List<Session>, pr: List<Project>, act: String?) = runCatching {
        val o = JSONObject().put("active", act ?: "")
        o.put("sessions", JSONArray().also { a -> ss.forEach { s -> a.put(JSONObject().put("id", s.id).put("title", s.title ?: JSONObject.NULL)
            .put("preview", s.preview ?: JSONObject.NULL).put("la", s.lastActive ?: JSONObject.NULL).put("n", s.count).put("src", s.source ?: JSONObject.NULL)) } })
        o.put("projects", JSONArray().also { a -> pr.forEach { x -> a.put(JSONObject().put("id", x.id).put("name", x.name).put("path", x.path ?: JSONObject.NULL)
            .put("home", x.isHome).put("auto", x.isAuto).put("arch", x.archived).put("ids", JSONArray(x.sessionIds)).put("cwd", x.homeCwd ?: JSONObject.NULL)) } })
        store.setSnap(p, o.toString())
    }

    fun loadSnap(p: String): Triple<List<Session>, List<Project>, String?>? = runCatching {
        val o = JSONObject(store.snap(p) ?: return null)
        fun JSONObject.s(k: String) = if (isNull(k)) null else optString(k)
        val sa = o.getJSONArray("sessions"); val pa = o.getJSONArray("projects")
        Triple((0 until sa.length()).map { sa.getJSONObject(it) }.map { Session(it.getString("id"), it.s("title"), it.s("preview"),
                if (it.isNull("la")) null else it.getDouble("la"), it.optInt("n"), it.s("src")) },
            (0 until pa.length()).map { pa.getJSONObject(it) }.map { x -> Project(x.getString("id"), x.getString("name"), x.s("path"), x.optBoolean("home"),
                x.optBoolean("auto"), x.optBoolean("arch"), x.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }, x.s("cwd")) },
            o.optString("active").ifBlank { null })
    }.getOrNull()

    fun loadOther(p: String) = scope.launch {
        runCatching {
            val (ss, pj) = kotlinx.coroutines.coroutineScope {
                val a = async { hub.apiFor(p).sessions() }; val b = async { fetchProjects(hub.conn.gatewayFor(p)) }
                a.await() to b.await()
            }
            otherData = otherData + (p to Triple(ss, pj.first, pj.second)); saveSnap(p, ss, pj.first, pj.second)
        }.onFailure { platform.log("HermesRpc", "loadOther $p: $it") }
    }

    /** Make [p] the current profile without leaving the session page; its cached lists show at once. */
    fun enterProfile(p: String) {
        if (p == hub.profile) return
        otherData = otherData + (hub.profile to Triple(hub.conn.sessions, projects, activeProject))
        val d = cachedFor(p)
        serverCommands = emptyList(); hub.profile = p; store.profile = p
        hub.cur = hub.fresh(p); hub.conn.sessions = d?.first ?: emptyList(); projects = d?.second ?: emptyList(); activeProject = d?.third
        expandedProfiles = expandedProfiles + p
        hub.conn.refreshSessions(); loadMeta()
    }

    suspend fun fetchProjects(g: GatewayWs): Pair<List<Project>, String?> {
        var active: String? = null
        val listCall = kotlinx.coroutines.coroutineScope {
            val l = async { runCatching { g.call("projects.list", JSONObject()) }.getOrNull() }
            val t = async { g.call("projects.tree", JSONObject().put("preview_limit", 1).put("session_limit", 1000)) }
            l.await() to t.await()
        }
        val archivedIds = listCall.first?.let { r ->
            active = r.str("active_id").ifBlank { null }
            val a = r.optJSONArray("projects") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optBoolean("archived") }.map { it.optString("id") }.toSet()
        } ?: emptySet()
        val tree = listCall.second
        val nodes = tree.optJSONArray("projects") ?: JSONArray()
        // Auto projects (folder-derived groups, gone when their sessions leave) are off: their sessions show as unfiled.
        val list = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }.filter { !it.optBoolean("isAuto") }.map { n ->
            val ids = n.optJSONArray("sessionIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val home = n.optBoolean("isNoProject")
            val homeCwd = if (home) n.optJSONArray("previewSessions")?.optJSONObject(0)?.str("cwd")?.ifBlank { null } else null
            Project(n.optString("id"), n.optString("label"), n.str("path").ifBlank { null }, home, n.optBoolean("isAuto"),
                n.optString("id") in archivedIds, ids, homeCwd)
        }
        return list to active
    }

    /** New chat whose working folder is the project's folder (so it lands in that project). */
    fun newChatIn(p: Project?) {
        // null = "uncategorised": the Home project (no project), never the current/default one.
        val t = p ?: projects.find { it.isHome }
        newChatNone = p == null && t == null
        newChatCwd = t?.path ?: t?.homeCwd; hub.msg.newChat()
    }

    /** Re-home a stored session into a project by switching its working folder (what the desktop does). */
    fun moveToProject(s: Session, p: Project) = scope.launch {
        val cwd = p.path ?: p.homeCwd ?: hub.pc.pcHome
        if (cwd == null) { hub.error = s(Str.move_no_folder); return@launch }
        try {
            if (!moveOne(s, cwd)) { hub.error = s(Str.move_busy); return@launch }
            hub.status = s(Str.moved_to, p.name); hub.conn.refreshSessions()
        } catch (e: Exception) { hub.conn.fail(e) }
    }

    /** Move several sessions of the current profile into [p] (busy ones are skipped). */
    fun moveManyToProject(list: List<Session>, p: Project) = scope.launch {
        val cwd = p.path ?: p.homeCwd ?: hub.pc.pcHome
        if (cwd == null) { hub.error = s(Str.move_no_folder); return@launch }
        try {
            var skipped = 0
            for (x in list) if (!moveOne(x, cwd)) skipped++
            if (skipped > 0) hub.error = s(Str.move_busy) else hub.status = s(Str.moved_to, p.name)
            hub.conn.refreshSessions()
        } catch (e: Exception) { hub.conn.fail(e); hub.conn.refreshSessions() }
    }

    fun deleteMany(list: List<Session>) = scope.launch {
        try {
            val ids = list.map { it.id }.toSet()
            for (x in list) hub.api().delete(x.id)
            hub.chats.removeAll { it.profile == hub.profile && it.id in ids }; hub.tabs.removeAll { it.profile == hub.profile && it.id in ids }
            if (hub.currentId in ids) hub.msg.newChat()
        } catch (e: Exception) { hub.conn.fail(e) }
        hub.conn.refreshSessions()
    }

    /** false = the session is busy and was not moved. */
    suspend fun moveOne(s: Session, cwd: String): Boolean {
        run {
            val g = hub.conn.gateway()
            val snap = g.call("session.resume", JSONObject().put("session_id", s.id).put("omit_messages", true))
            fun params(rev: Int, gen: Int) = JSONObject().put("session_id", snap.getString("session_id")).put("request_id", UUID.randomUUID().toString())
                .put("expected_revision", rev).put("expected_generation", gen).put("operation", "workspace").put("payload", JSONObject().put("cwd", cwd))
            try { g.call("session.mutate", params(snap.optInt("revision"), snap.optInt("execution_generation"))) }
            catch (e: ApiException) {
                if (e.message == "session_busy") return false
                val again = g.call("session.resume", JSONObject().put("session_id", s.id).put("omit_messages", true))
                g.call("session.mutate", params(again.optInt("revision"), again.optInt("execution_generation")))
            }
            hub.chats.find { it.profile == hub.profile && it.id == s.id }?.liveId = null
        }
        return true
    }

    /** New project whose folder doesn't exist on the PC yet: ask before creating it. */
    var missingFolder by mutableStateOf<Pair<String, String>?>(null)

    /** Context / compression settings shared by every profile; the phone shows only what the PC reports back. */
    var ctxSettings by mutableStateOf<JSONObject?>(null)
    var ctxBusy by mutableStateOf(false)
    var ctxError by mutableStateOf<String?>(null)
    fun loadCtxSettings(values: JSONObject? = null) = scope.launch {
        ctxBusy = true; ctxError = null
        try {
            val o = withContext(Dispatchers.IO) {
                val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
                val b = okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + "/config/context").header("Authorization", "Bearer $key")
                if (values != null) b.post(values.toString().toRequestBody("application/json".toMediaType()))
                hub.relayHttp.newCall(b.build()).execute().use { r ->
                    val j = runCatching { JSONObject(r.body!!.string()) }.getOrDefault(JSONObject())
                    if (!r.isSuccessful) throw ApiException(j.optString("error").ifBlank { "HTTP ${r.code}" })
                    j
                }
            }
            ctxSettings = o.optJSONObject("settings") ?: JSONObject()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { ctxError = e.message ?: e.javaClass.simpleName } finally { ctxBusy = false }
    }

    /** Model defaults shared by every profile (main / delegation / auxiliary.<task>); shows only what the PC reports. */
    var modelSettings by mutableStateOf<JSONObject?>(null)
    var modelBusy by mutableStateOf(false)
    var modelError by mutableStateOf<String?>(null)
    fun loadModelSettings(models: JSONObject? = null) = scope.launch {
        modelBusy = true; modelError = null
        try {
            val o = withContext(Dispatchers.IO) {
                val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
                val b = okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + "/config/models").header("Authorization", "Bearer $key")
                if (models != null) b.post(models.toString().toRequestBody("application/json".toMediaType()))
                hub.relayHttp.newCall(b.build()).execute().use { r ->
                    val j = runCatching { JSONObject(r.body!!.string()) }.getOrDefault(JSONObject())
                    if (!r.isSuccessful) throw ApiException(j.optString("error").ifBlank { "HTTP ${r.code}" })
                    j
                }
            }
            modelSettings = o.optJSONObject("settings") ?: JSONObject()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { modelError = e.message ?: e.javaClass.simpleName } finally { modelBusy = false }
    }

    /** Per-session context window (used to max tokens) from session.stats (older gateways: session.usage); only sessions with a known max are listed. */
    var sessionCtx by mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap())
    /** lastActive each session had when its context was last fetched: unchanged sessions are not asked again (a refresh used to fire ~100 RPCs). */
    var lastCtxProf = ""
    /** Context numbers cached on disk: the first refresh after the app starts shows them at once and only asks about sessions that changed. */
    fun primeCtx(prof: String) {
        if (lastCtxProf == prof) return
        lastCtxProf = prof; ctxStamp.clear()
        val m = mutableMapOf<String, Pair<Long, Long>>()
        runCatching {
            val o = JSONObject(store.ctxCache(prof) ?: return@runCatching)
            o.keys().forEach { id -> val a = o.getJSONArray(id); ctxStamp[id] = a.getDouble(2)
                if (a.getLong(1) > 0) m[id] = a.getLong(0) to a.getLong(1) }
        }
        sessionCtx = m
    }
    fun saveCtx(prof: String) = runCatching {
        val o = JSONObject()
        ctxStamp.forEach { (id, st) -> val c = sessionCtx[id]; o.put(id, JSONArray().put(c?.first ?: 0).put(c?.second ?: 0).put(st)) }
        store.setCtxCache(prof, o.toString())
    }
    val ctxStamp = java.util.concurrent.ConcurrentHashMap<String, Double>()
    fun loadSessionCtx() = scope.launch {
        val all = hub.conn.sessions
        val ids = all.map { it.id }
        if (ids.isEmpty()) { sessionCtx = emptyMap(); return@launch }
        val g = runCatching { hub.conn.gateway() }.getOrNull() ?: return@launch
        val prof = hub.profile
        val out = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
        out.putAll(sessionCtx.filterKeys { it in ids })
        val sem = Semaphore(4)
        val liveNow = runCatching { hub.conn.gatewayFor(prof).call("session.active_list", JSONObject()).optJSONArray("sessions") }.getOrNull()
            ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("session_key") }.toSet() }
        val busyNow = hub.busyIds()
        val stale = all.withIndex().filter { (_, x) -> x.id in busyNow || ctxStamp[x.id] != (x.lastActive ?: 0.0) }
        val jobs = stale.map { (idx, x) -> val id = x.id; launch {
            sem.withPermit {
                // session.stats only knows live sessions; for stored ones resume (no messages) returns the same figures.
                fun pick(r: JSONObject?): Boolean {
                    val u = r?.optJSONObject("usage") ?: r ?: return false
                    val max = u.optLong("context_max", 0).takeIf { it > 0 } ?: u.optLong("context_length", 0)
                    if (max <= 0) return false
                    val used = u.optLong("context_used", -1).takeIf { it >= 0 } ?: u.optLong("context_tokens", 0)
                    out[id] = used.coerceAtLeast(0) to max; return true
                }
                val ok = pick(runCatching { g.call("session.stats", JSONObject().put("session_id", id)) }.getOrNull())
                if (!ok && idx < 30) {
                    val rr = runCatching { g.call("session.resume", JSONObject().put("session_id", id).put("omit_messages", true)) }
                        .onFailure { platform.log("HermesRpc", "ctx $id: $it") }.getOrNull()
                    pick(rr)
                    // The probe made this session live on the PC; close it again (unless someone really has it open), or it would show as active.
                    val lid = rr?.optString("session_id")?.ifBlank { null }
                    if (lid != null && liveNow != null && id !in liveNow && hub.chats.none { it.id == id }) runCatching { g.call("session.close", JSONObject().put("session_id", lid)) }
                }
                ctxStamp[id] = x.lastActive ?: 0.0
                if (prof == hub.profile) { sessionCtx = out.toMap(); out[id]?.let { (u, m) -> hub.chats.find { it.profile == prof && it.id == id }?.let { c -> c.ctxUsed = u; c.ctxMax = m } } }
            }
        } }
        jobs.forEach { it.join() }
        if (prof == hub.profile) saveCtx(prof)
    }

    /** Move-to-project targets: always lists home (unfiled) first, even when the tree has no usable home node yet. */
    fun moveTargets(): List<Project> {
        val l = projects.filter { !it.archived }
        val h = l.find { it.isHome }
        val home = (h ?: Project("__home", "home", null, true, false, false, emptyList())).let { it.copy(homeCwd = it.homeCwd ?: hub.pc.pcHome) }
        return listOf(home) + l.filter { !it.isHome }
    }

    /** Point a project at another folder: attach it as the primary path and detach the old one. */
    fun changeProjectFolder(p: Project, folder: String) = scope.launch {
        try {
            val f = folder.trim()
            hub.conn.gateway().call("projects.add_folder", JSONObject().put("id", p.id).put("path", f).put("is_primary", true))
            val old = p.path
            if (old != null && old != f) runCatching { hub.conn.gateway().call("projects.remove_folder", JSONObject().put("id", p.id).put("path", old)) }
            hub.status = s(Str.pf_folder_changed, p.name); hub.conn.refreshSessions()
        } catch (e: Exception) { hub.conn.fail(e) }
    }

    fun createProject(name: String, folder: String, mkdir: Boolean = false) = scope.launch {
        try {
            val f = folder.trim()
            if (mkdir) hub.pc.fsCall("mkdir", f)
            else {
                val st = runCatching { hub.pc.fsCall("stat", f) }.getOrNull()   // older relay without /fs: skip the check
                if (st != null && !st.optBoolean("exists")) { missingFolder = name to f; return@launch }
                if (st != null && !st.optBoolean("dir")) throw ApiException(s(Str.not_a_folder, f))
            }
            hub.conn.gateway().call("projects.create", JSONObject().put("name", name.trim()).put("folders", JSONArray().put(f)).put("primary_path", f))
            hub.conn.refreshSessions()
        } catch (e: Exception) { hub.conn.fail(e) }
    }
    fun archiveProject(p: Project, restore: Boolean = false) = scope.launch {
        try { hub.conn.gateway().call("projects.archive", JSONObject().put("id", p.id).put("restore", restore)); hub.conn.refreshSessions() } catch (e: Exception) { hub.conn.fail(e) }
    }
    /** Deletes the project grouping; with [deleteFolder] also its folder on the PC (via the relay, with guards). */
    fun deleteProject(p: Project, deleteFolder: Boolean = false, deleteSessions: Boolean = false) = scope.launch {
        try {
            if (deleteSessions) {
                val ids = p.sessionIds.toSet()
                for (id in ids) runCatching { hub.api().delete(id) }
                hub.chats.removeAll { it.profile == hub.profile && it.id in ids }; hub.tabs.removeAll { it.profile == hub.profile && it.id in ids }
                if (hub.currentId in ids) hub.msg.newChat()
            }
            if (deleteFolder && p.path != null) {
                hub.pc.fsCall("rmdir", p.path!!, projects.filter { it.id != p.id && !it.isHome }.mapNotNull { it.path })
            }
            hub.conn.gateway().call("projects.delete", JSONObject().put("id", p.id)); hub.conn.refreshSessions()
        } catch (e: Exception) { hub.conn.fail(e); hub.conn.refreshSessions() }
    }
    fun setActiveProject(p: Project?) = scope.launch {
        runCatching { hub.conn.gateway().call("projects.set_active", if (p == null || p.isHome) JSONObject() else JSONObject().put("id", p.id)) }
        activeProject = p?.takeUnless { it.isHome }?.id
    }

}
