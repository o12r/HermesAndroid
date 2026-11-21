package dev.oscar.hermes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

enum class Screen { Sessions, Chat, Settings }
data class UiMsg(val role: String, val text: String, val tools: List<String> = emptyList(), val id: Int? = null)
data class Attachment(val name: String, val isImage: Boolean, val payload: String)
data class Approval(val reqId: String, val command: String, val description: String, val choices: List<String>, val gen: Int? = null)
data class Project(val id: String, val name: String, val path: String?, val isHome: Boolean, val isAuto: Boolean,
    val archived: Boolean, val sessionIds: List<String>, val homeCwd: String? = null)
data class ClarifyQ(val qid: String, val question: String, val choices: List<String>, val multi: Boolean = false)
data class Clarify(val reqId: String, val questions: List<ClarifyQ>, val gen: Int? = null)  // gen != null: canonical prompt

/**
 * Everything goes through the PC relay: lists/history/rename/delete via the gateway's native HTTP API;
 * chat turns, streaming, approvals, models and real slash commands via the desktop protocol (so desktop-created sessions work too).
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val store = SecureStore(app)
    var screen by mutableStateOf(if (store.hasKey) Screen.Sessions else Screen.Settings)
    var profile by mutableStateOf(store.profile)
    var sessions by mutableStateOf<List<Session>>(emptyList())
    /** Profiles found on the PC (name to description); cached so the menu works offline. */
    var profiles by mutableStateOf(store.profileCache.ifEmpty { SecureStore.PROFILES.map { it to "" } })
    fun profileNames() = profiles.map { it.first }

    /** Scan the PC for Hermes profiles (profiles.list over the relay). */
    fun scanProfiles() = viewModelScope.launch {
        runCatching {
            val r = gateway().call("profiles.list", JSONObject())
            val a = r.optJSONArray("profiles") ?: JSONArray()
            val found = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
                o.optString("name") to o.str("display_name").ifBlank { o.str("description") }
            }.filter { it.first.isNotBlank() }
            if (found.isNotEmpty()) { profiles = found; store.profileCache = found }
        }.onFailure { android.util.Log.w("HermesRpc", "profiles.list: $it") }
    }
    /** Project groups from projects.tree (Home = sessions outside any project). */
    var projects by mutableStateOf<List<Project>>(emptyList())
    var activeProject by mutableStateOf<String?>(null)
    /** Folder for the next new chat (project primary path), null = Hermes default. */
    private var newChatCwd: String? = null
    var currentId by mutableStateOf<String?>(null)
    var currentTitle by mutableStateOf<String?>(null)
    val messages = mutableStateListOf<UiMsg>()
    val attachments = mutableStateListOf<Attachment>()
    var streaming by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var offline by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var approval by mutableStateOf<Approval?>(null)
    var clarify by mutableStateOf<Clarify?>(null)
    var status by mutableStateOf<String?>(null)
    var modelOptions by mutableStateOf<ModelOptions?>(null)
    var skills by mutableStateOf<List<Skill>>(emptyList())
    var serverCommands by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var model by mutableStateOf(store.modelFor(store.profile))      // "provider|model" or ""
    var effort by mutableStateOf(store.effortFor(store.profile))    // "" = server default
    var modelMenuOpen by mutableStateOf(false)
    private var lastSent: Pair<String, List<Attachment>>? = null

    // ---- localisation / appearance / updates ----
    private val res get() = L10n.wrap(getApplication(), store.lang)
    fun s(id: Int, vararg a: Any): String = res.getString(id, *a)
    var theme by mutableStateOf(store.theme)
    val updates = UpdateManager(app, store)
    var update by mutableStateOf<UpdateInfo?>(null)
    var updateStatus by mutableStateOf<String?>(null)
    var updateProgress by mutableStateOf<Int?>(null)
    var hotfix by mutableStateOf(Hotfix.NONE)
    val forceUpdate get() = hotfix.minVersionCode > updates.currentCode

    // desktop-protocol state
    private var gw: GatewayWs? = null
    private val gwLock = Mutex()
    private var liveId: String? = null        // live session id from session.resume/create
    private var generation = 0
    private var revision = 0
    private var dropped = false

    private fun api() = ApiClient(store.relayBase(), profile, store.apiKey ?: throw ApiException(s(R.string.err_set_key)))

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch { if (offline) reconnect() } }
        override fun onLost(network: Network) { viewModelScope.launch { status = s(R.string.network_changed) } }
    }
    init {
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb)
        if (store.hasKey) viewModelScope.launch { pickRelay(); refreshSessions(); loadMeta() }
        checkUpdates(manual = false)
    }

    fun chooseTheme(t: String) { theme = t; store.theme = t }
    var fontScale by mutableStateOf(store.fontScale)
    fun chooseFontScale(f: Float) { fontScale = f; store.fontScale = f }

    /** Hotfix config every launch/resume; update check at most every 6 h unless [manual]. */
    fun checkUpdates(manual: Boolean) = viewModelScope.launch {
        runCatching { updates.hotfix() }.getOrNull()?.let { h ->
            hotfix = h
            if (h.relayUrl != store.hotfixRelay) store.hotfixRelay = h.relayUrl
        }
        if (!manual && !forceUpdate && System.currentTimeMillis() - store.lastUpdateCheck < 6 * 3600_000L) return@launch
        if (manual) updateStatus = s(R.string.checking)
        val u = runCatching { updates.check() }.getOrElse { if (manual) updateStatus = s(R.string.update_failed, it.message ?: "?"); return@launch }
        store.lastUpdateCheck = System.currentTimeMillis()
        update = u
        if (manual) updateStatus = if (u == null) s(R.string.up_to_date) else null
    }

    fun installUpdate() {
        val u = update ?: return
        viewModelScope.launch {
            updateProgress = 0
            runCatching { updates.install(u) { p -> viewModelScope.launch(Dispatchers.Main) { updateProgress = p } } }
                .onFailure { updateStatus = s(R.string.update_failed, it.message ?: "?") }
            updateProgress = null
        }
    }
    override fun onCleared() {
        runCatching { getApplication<Application>().getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCb) }
        gw?.close()
    }

    private fun fail(e: Throwable) {
        offline = (e as? ApiException)?.unreachable == true
        error = e.message ?: e.toString()
    }

    /** Use the main (Tailscale) URL if it answers, else the LAN URL; remembers the winner. */
    private suspend fun pickRelay(): Boolean {
        val key = store.apiKey ?: return false
        for (u in listOf(store.relayUrl, store.lanUrl).filter { it.isNotBlank() }.distinct()) {
            if (runCatching { withTimeout(4000) { ApiClient(u, profile, key).health() } }.isSuccess) {
                if (store.activeRelay != u) { store.activeRelay = u; gw?.close(); gw = null; liveId = null }
                return true
            }
        }
        return false
    }

    /** Keep the relay connection up (also in the background): reconnect with backoff and re-subscribe. */
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var notifiedDrop = false
    private fun scheduleReconnect(wasStreaming: Boolean) {
        if (!store.hasKey || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var delayMs = 2000L
            while (true) {
                kotlinx.coroutines.delay(delayMs)
                val ok = runCatching { pickRelay(); val g = gateway(); if (currentId != null && screen == Screen.Chat) ensureLive(g) }.isSuccess
                if (ok) {
                    offline = false; error = null; notifiedDrop = false
                    if (dropped && currentId != null) { dropped = false; currentId?.let { loadMessages(it) } }
                    return@launch
                }
                if (!notifiedDrop && (wasStreaming || delayMs >= 16000)) {
                    notifiedDrop = true; Notify.alert(getApplication<Application>(), s(R.string.n_disconnected), s(R.string.n_disconnected_text))
                }
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    // ---------- gateway connection ----------
    private suspend fun gateway(): GatewayWs = gwLock.withLock {
        gw?.takeIf { it.open }?.let { return it }
        gw?.close(); liveId = null
        val g = GatewayWs(store.relayBase(), profile, store.apiKey ?: throw ApiException("Set the API key in Settings"),
            onEvent = { t, sid, p -> viewModelScope.launch(Dispatchers.Main) { onEvent(t, sid, p) } },
            onServerRequest = { id, m, p -> viewModelScope.launch(Dispatchers.Main) { onServerRequest(id, m, p) } },
            onClosed = { why -> viewModelScope.launch(Dispatchers.Main) {
                val wasStreaming = streaming
                if (streaming) { dropped = true; streaming = false; status = null; why?.let { error = it; offline = true } }
                scheduleReconnect(wasStreaming)
            } })
        withContext(Dispatchers.IO) { g.connect() }
        gw = g
        if (serverCommands.isEmpty()) viewModelScope.launch { loadCatalog() }
        g
    }

    /** Attach the gateway to the current session (create one if this is a new chat). */
    private suspend fun ensureLive(g: GatewayWs): String {
        liveId?.let { return it }
        val snap = currentId?.let { g.call("session.resume", JSONObject().put("session_id", it).put("omit_messages", true)) }
            ?: g.call("session.create", JSONObject().put("source", "gui").apply {
                    newChatCwd?.let { put("cwd", it).put("cwd_explicit", true) } }).also { s ->
                newChatCwd = null
                currentId = s.optString("stored_session_id").ifBlank { s.getString("session_id") }
            }
        generation = snap.optInt("execution_generation", generation)
        revision = snap.optInt("revision", revision)
        val live = snap.getString("session_id").also { liveId = it }
        snap.optJSONArray("prompts")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { pr ->
            viewModelScope.launch(Dispatchers.Main) { onEvent(pr.optString("kind") + ".request", live, pr) } } }
        if (snap.optBoolean("running")) viewModelScope.launch(Dispatchers.Main) { streaming = true }
        return live
    }

    private suspend fun loadCatalog() {
        runCatching {
            val g = gateway(); val sid = liveId
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
    fun loadMeta() = viewModelScope.launch {
        runCatching { modelOptions = ApiClient.parseModelOptions(gateway().call("model.options", JSONObject())) }
        loadCatalog()
        scanProfiles()
    }
    fun localCommands(): List<Pair<String, String>> = SLASH_COMMANDS.map { (c, id) -> c to s(id) }
    fun helpText() = s(R.string.help_header) + "\n" + localCommands().joinToString("\n") { "${it.first} — ${it.second}" } + "\n\n" + s(R.string.help_footer)
    fun commandList(): List<Pair<String, String>> {
        val names = (localCommands() + serverCommands).map { it.first }.toSet()
        return localCommands() + serverCommands + skills.filter { "/" + it.name !in names }.map { "/" + it.name to it.description }
    }
    fun modelLabel(): String = if (model.isBlank()) "Default: " + (modelOptions?.currentModel?.ifBlank { null } ?: "server")
        else model.substringAfter('|')
    fun chooseModel(key: String) { model = key; store.setModelFor(profile, key) }
    fun chooseEffort(e: String) { effort = e; store.setEffortFor(profile, e) }

    private fun modelOverride(): JSONObject? {
        val m = model.substringAfter('|', "").ifBlank { null }
        val p = model.substringBefore('|', "").ifBlank { null }
        if (m == null && effort.isBlank()) return null
        val mm = m ?: modelOptions?.currentModel?.ifBlank { null } ?: return null
        return JSONObject().put("model", mm).apply {
            (p ?: modelOptions?.currentProvider?.ifBlank { null })?.let { put("provider", it) }
            if (effort.isNotBlank()) put("reasoning", effort)
        }
    }

    private fun note(text: String) { messages.add(UiMsg("system", text)) }

    // ---------- sessions ----------
    fun reconnect() {
        if (!store.hasKey || streaming) return
        viewModelScope.launch {
            try { pickRelay(); api().health(); offline = false; error = null; status = null
                refreshSessions(); if (modelOptions == null) loadMeta()
                if (dropped) { dropped = false; currentId?.let { loadMessages(it) } }
            } catch (e: Exception) { fail(e) }
        }
    }

    fun setProfileTo(p: String) {
        if (p == profile) return
        gw?.close(); gw = null; liveId = null; serverCommands = emptyList()
        profile = p; store.profile = p
        model = store.modelFor(p); effort = store.effortFor(p)
        currentId = null; messages.clear(); screen = Screen.Sessions; refreshSessions(); loadMeta()
    }

    fun refreshSessions() = viewModelScope.launch {
        loading = true
        try {
            sessions = api().sessions(); offline = false; error = null
            runCatching { loadProjects() }.onFailure { android.util.Log.w("HermesRpc", "loadProjects: $it") }
            currentId?.let { id -> sessions.find { it.id == id }?.title?.let { currentTitle = it } }
        } catch (e: Exception) { fail(e) }
        loading = false
    }

    private suspend fun loadProjects() {
        val g = gateway()
        val archivedIds = runCatching { g.call("projects.list", JSONObject()) }.getOrNull()?.let { r ->
            activeProject = r.str("active_id").ifBlank { null }
            val a = r.optJSONArray("projects") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optBoolean("archived") }.map { it.optString("id") }.toSet()
        } ?: emptySet()
        val tree = g.call("projects.tree", JSONObject().put("preview_limit", 1).put("session_limit", 1000))
        val nodes = tree.optJSONArray("projects") ?: JSONArray()
        projects = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }.map { n ->
            val ids = n.optJSONArray("sessionIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val home = n.optBoolean("isNoProject")
            val homeCwd = if (home) n.optJSONArray("previewSessions")?.optJSONObject(0)?.str("cwd")?.ifBlank { null } else null
            Project(n.optString("id"), n.optString("label"), n.str("path").ifBlank { null }, home, n.optBoolean("isAuto"),
                n.optString("id") in archivedIds, ids, homeCwd)
        }
    }

    /** New chat whose working folder is the project's folder (so it lands in that project). */
    fun newChatIn(p: Project?) { newChat(); newChatCwd = p?.path ?: p?.homeCwd }

    /** Re-home a stored session into a project by switching its working folder (what the desktop does). */
    fun moveToProject(s: Session, p: Project) = viewModelScope.launch {
        val cwd = p.path ?: p.homeCwd
        if (cwd == null) { error = s(R.string.move_no_folder); return@launch }
        try {
            val g = gateway()
            val snap = g.call("session.resume", JSONObject().put("session_id", s.id).put("omit_messages", true))
            fun params(rev: Int, gen: Int) = JSONObject().put("session_id", snap.getString("session_id")).put("request_id", UUID.randomUUID().toString())
                .put("expected_revision", rev).put("expected_generation", gen).put("operation", "workspace").put("payload", JSONObject().put("cwd", cwd))
            try { g.call("session.mutate", params(snap.optInt("revision"), snap.optInt("execution_generation"))) }
            catch (e: ApiException) {
                if (e.message == "session_busy") { error = s(R.string.move_busy); return@launch }
                val again = g.call("session.resume", JSONObject().put("session_id", s.id).put("omit_messages", true))
                g.call("session.mutate", params(again.optInt("revision"), again.optInt("execution_generation")))
            }
            if (currentId == s.id) liveId = null
            status = s(R.string.moved_to, p.name); refreshSessions()
        } catch (e: Exception) { fail(e) }
    }

    fun createProject(name: String, folder: String) = viewModelScope.launch {
        try {
            val f = folder.trim()
            gateway().call("projects.create", JSONObject().put("name", name.trim()).put("folders", JSONArray().put(f)).put("primary_path", f))
            refreshSessions()
        } catch (e: Exception) { fail(e) }
    }
    fun archiveProject(p: Project, restore: Boolean = false) = viewModelScope.launch {
        try { gateway().call("projects.archive", JSONObject().put("id", p.id).put("restore", restore)); refreshSessions() } catch (e: Exception) { fail(e) }
    }
    /** Deletes the project grouping only (its folders and sessions stay). */
    fun deleteProject(p: Project) = viewModelScope.launch {
        try { gateway().call("projects.delete", JSONObject().put("id", p.id)); refreshSessions() } catch (e: Exception) { fail(e) }
    }
    fun setActiveProject(p: Project?) = viewModelScope.launch {
        runCatching { gateway().call("projects.set_active", if (p == null || p.isHome) JSONObject() else JSONObject().put("id", p.id)) }
        activeProject = p?.takeUnless { it.isHome }?.id
    }

    fun open(s: Session) {
        currentId = s.id; currentTitle = s.title; liveId = null; approval = null; clarify = null; screen = Screen.Chat
        viewModelScope.launch {
            loadMessages(s.id)
            // Subscribe right away so Hermes's questions, approvals and replies from a turn
            // started elsewhere (e.g. the desktop) show up live.
            runCatching { val g = gateway(); if (currentId == s.id) ensureLive(g) }.onFailure { fail(it) }
        }
    }

    /** Fill in stored row ids (needed for edit/branch) after a turn, keeping local notes. */
    private suspend fun refreshIds() {
        val id = currentId ?: return
        val stored = runCatching { api().messages(id) }.getOrNull() ?: return
        var j = 0
        for (i in messages.indices) {
            val m = messages[i]; if (m.role == "system") continue
            while (j < stored.size && stored[j].role != m.role) j++
            if (j >= stored.size) break
            if (m.id == null) messages[i] = m.copy(id = stored[j].id)
            j++
        }
    }

    private suspend fun mutate(operation: String, payload: JSONObject): JSONObject {
        val g = gateway(); val sid = ensureLive(g)
        fun params() = JSONObject().put("session_id", sid).put("request_id", UUID.randomUUID().toString())
            .put("expected_revision", revision).put("expected_generation", generation).put("operation", operation).put("payload", payload)
        return try { g.call("session.mutate", params()) } catch (e: ApiException) {
            if (e.message != "revision_conflict" && e.message != "stale_generation") throw e
            val snap = g.call("session.resume", JSONObject().put("session_id", sid).put("omit_messages", true))
            revision = snap.optInt("revision", revision); generation = snap.optInt("execution_generation", generation)
            g.call("session.mutate", params())
        }.also { r -> if (r.has("revision")) revision = r.optInt("revision", revision) }
    }

    /** Replace a sent user message: rewind to it (drops it and everything after), then send the new text. */
    fun editMessage(m: UiMsg, newText: String) {
        val target = m.id ?: return
        if (streaming || newText.isBlank()) return
        viewModelScope.launch {
            try {
                mutate("rewind", JSONObject().put("target_message_id", target))
                val idx = messages.indexOfFirst { it.id == target }
                if (idx >= 0) while (messages.size > idx) messages.removeAt(messages.lastIndex)
                sendMessage(newText, emptyList())
            } catch (e: Exception) { fail(e) }
        }
    }

    /** New session with the conversation up to and including [m]; opens it. */
    fun branchFrom(m: UiMsg) {
        val through = m.id ?: return
        viewModelScope.launch {
            try {
                val r = mutate("branch", JSONObject().put("through_message_id", through))
                val newId = r.optString("branched_session_id").ifBlank { r.optString("session_id") }
                refreshSessions().join()
                val s = sessions.find { it.id == newId } ?: Session(newId, r.optString("title").ifBlank { null }, null, null, 0, "local")
                open(s)
            } catch (e: Exception) { fail(e) }
        }
    }
    fun newChat() { newChatCwd = projects.find { it.id == activeProject }?.path; currentId = null; liveId = null; currentTitle = null; messages.clear(); attachments.clear(); screen = Screen.Chat }

    private suspend fun loadMessages(id: String) {
        try { val m = api().messages(id); messages.clear(); messages.addAll(m.mapNotNull { r -> Internal.clean(r.role, r.content)?.let { UiMsg(r.role, it, id = r.id) } }); offline = false; error = null }
        catch (e: Exception) { fail(e) }
    }

    fun rename(s: Session, title: String) = viewModelScope.launch {
        try { api().rename(s.id, title); refreshSessions() } catch (e: Exception) { fail(e) } }
    fun delete(s: Session) = viewModelScope.launch {
        try { api().delete(s.id); if (currentId == s.id) newChat(); refreshSessions() } catch (e: Exception) { fail(e) } }

    // ---------- sending ----------
    fun send(text: String) {
        val t = text.trim()
        if (t.startsWith("/") && attachments.isEmpty()) { slash(t); return }
        if (streaming && liveId != null && t.isNotEmpty()) { steer(t); return }
        sendMessage(text, attachments.toList()); attachments.clear()
    }

    /** App-side commands first (model/think/profile/... need the phone UI); everything else runs on Hermes. */
    private fun slash(t: String) {
        val cmd = t.substringBefore(' ').drop(1).lowercase()
        val arg = t.substringAfter(' ', "").trim()
        when (cmd) {
            "help" -> { note(helpText()); if (serverCommands.isNotEmpty()) note(s(R.string.note_hermes_cmds, serverCommands.joinToString(" ") { it.first })) }
            "model" -> if (arg.isBlank()) modelMenuOpen = true else {
                val opts = modelOptions?.choices.orEmpty()
                val hit = if (arg == "default") "" else opts.firstOrNull { it.model.equals(arg, true) || it.key.equals(arg, true) }?.key
                    ?: opts.firstOrNull { it.model.contains(arg, true) }?.key
                if (hit == null) note(s(R.string.note_no_model, arg)) else { chooseModel(hit); note(s(R.string.note_model, modelLabel())) }
            }
            "think", "thinking", "effort" -> {
                val e = if (arg == "default") "" else arg.lowercase()
                if (e !in SecureStore.EFFORTS) note(s(R.string.note_levels, SecureStore.EFFORTS.drop(1).joinToString(", ")))
                else { chooseEffort(e); note(s(R.string.note_thinking, e.ifBlank { s(R.string.default_) })) }
            }
            "stop" -> stop()
            "profile" -> if (arg in profileNames()) setProfileTo(arg) else note(s(R.string.note_profiles, profileNames().joinToString(", ")))
            "sessions" -> { screen = Screen.Sessions; refreshSessions() }
            "delete" -> {
                val id = currentId
                if (id == null) note(s(R.string.note_no_delete))
                else if (arg != "confirm") note(s(R.string.note_delete_confirm))
                else viewModelScope.launch { try { api().delete(id); newChat(); screen = Screen.Sessions; refreshSessions() } catch (e: Exception) { fail(e) } }
            }
            "new" -> { newChat(); if (arg.isNotBlank()) sendMessage(arg, emptyList()) }
            "retry" -> { val l = lastSent; if (l == null) note(s(R.string.note_nothing_retry)) else sendMessage(l.first, l.second) }
            // Like the desktop: these are session mutations, not slash.exec commands.
            "compress" -> compressChat(arg)
            "branch", "fork" -> branchWhole(arg)
            else -> if (hotfix.serverSlash) serverSlash(t) else note(s(R.string.err_desktop_only, t))
        }
    }

    /** /compress [focus]: summarise older context into a continuation session (Hermes moves this chat to it). */
    private fun compressChat(focus: String) {
        if (currentId == null) { note(s(R.string.note_nothing_to_compress)); return }
        if (streaming) { note(s(R.string.move_busy)); return }
        messages.add(UiMsg("user", "/compress" + if (focus.isNotBlank()) " $focus" else ""))
        status = s(R.string.compressing)
        viewModelScope.launch {
            try {
                val r = mutate("compress", if (focus.isNotBlank()) JSONObject().put("focus", focus) else JSONObject())
                val target = r.str("target_session_id").ifBlank { r.str("session_id") }.ifBlank { null }
                status = null
                // Hermes's own summary, e.g. "No changes from compression: 4 messages" / token estimate.
                val sum = r.optJSONObject("summary")
                val detail = listOfNotNull(sum?.str("headline")?.ifBlank { null }, sum?.str("token_line")?.ifBlank { null },
                    sum?.str("note")?.ifBlank { null }).joinToString("\n")
                val noop = r.str("status") == "noop" || sum?.optBoolean("noop") == true
                if (!noop) {
                    // Reload the compacted transcript, then show the result after it (reloading clears notes).
                    if (target != null && target != currentId) { currentId = target; liveId = null }
                    currentId?.let { loadMessages(it); runCatching { val g = gateway(); ensureLive(g) } }
                }
                note(if (noop) s(R.string.compress_noop) + if (detail.isNotBlank()) "\n$detail" else ""
                    else s(R.string.compressed) + if (detail.isNotBlank()) "\n$detail" else "")
                refreshSessions()
            } catch (e: Exception) { status = null; fail(e) }
        }
    }

    /** /branch [title]: copy the whole conversation into a new session and open it. */
    private fun branchWhole(title: String) {
        if (currentId == null) { note(s(R.string.note_nothing_to_compress)); return }
        viewModelScope.launch {
            try {
                val r = mutate("branch", if (title.isNotBlank()) JSONObject().put("title", title) else JSONObject())
                val newId = r.str("branched_session_id").ifBlank { r.str("session_id") }
                refreshSessions().join()
                open(sessions.find { it.id == newId } ?: Session(newId, title.ifBlank { null }, null, null, 0, "local"))
            } catch (e: Exception) { fail(e) }
        }
    }

    private fun serverSlash(t: String) = viewModelScope.launch {
        messages.add(UiMsg("user", t))
        try {
            val g = gateway(); val sid = ensureLive(g)
            val r = g.call("slash.exec", JSONObject().put("session_id", sid).put("command", t))
            val type = r.optString("type")
            val msg = r.optString("message").ifBlank { null }
            when {
                type == "skill" && msg != null -> submit(g, sid, msg, emptyList())
                msg != null && type == "send" -> submit(g, sid, msg, emptyList())
                else -> listOf(r.optString("output"), r.optString("notice"), r.optString("warning"), msg.orEmpty())
                    .filter { it.isNotBlank() && it != "null" }.joinToString("\n\n").ifBlank { "✓ $t" }.let { messages.add(UiMsg("assistant", it)) }
            }
            if (t.startsWith("/title") || t.startsWith("/new")) refreshSessions()
        } catch (e: Exception) {
            if (e.message == "unsupported_command") note(s(R.string.err_desktop_only, t)) else fail(e)
        }
    }

    private fun sendMessage(text: String, atts: List<Attachment>) {
        if (streaming || (text.isBlank() && atts.isEmpty())) return
        lastSent = text to atts
        val shown = (atts.map { if (it.isImage) "🖼️ ${it.name}" else "📎 ${it.name}" } + text).filter { it.isNotBlank() }.joinToString("\n")
        messages.add(UiMsg("user", shown))
        viewModelScope.launch {
            try { val g = gateway(); submit(g, ensureLive(g), text, atts) } catch (e: Exception) { fail(e); streaming = false }
        }
    }

    private suspend fun submit(g: GatewayWs, sid: String, text: String, atts: List<Attachment>) {
        error = null; streaming = true; status = null
        messages.add(UiMsg("assistant", ""))
        try {
            for (a in atts.filter { it.isImage }) {
                g.call("image.attach_bytes", JSONObject().put("session_id", sid).put("filename", a.name)
                    .put("content_base64", a.payload.substringAfter("base64,")))
            }
            val files = atts.filter { !it.isImage }.joinToString("") { "File `${it.name}`:\n```\n${it.payload}\n```\n\n" }
            val p = JSONObject().put("session_id", sid).put("text", files + text).put("submission_id", UUID.randomUUID().toString())
            modelOverride()?.let { p.put("model_override", it) }
            g.call("prompt.submit", p)
        } catch (e: Exception) { streaming = false; dropEmpty(); throw e }
    }

    private fun dropEmpty() {
        if (messages.lastOrNull()?.let { it.role == "assistant" && it.text.isEmpty() } == true) messages.removeAt(messages.lastIndex)
    }

    private fun updateLast(f: (UiMsg) -> UiMsg) {
        val i = messages.lastIndex
        if (i >= 0 && messages[i].role == "assistant") messages[i] = f(messages[i]) else messages.add(f(UiMsg("assistant", "")))
    }

    private fun onEvent(type: String, sid: String, p: JSONObject) {
        if (sid.isNotEmpty() && sid != liveId) return
        when (type) {
            "session.info", "session.control.update", "session.updated" -> {
                if (p.has("execution_generation")) generation = p.optInt("execution_generation", generation)
                if (p.has("revision")) revision = p.optInt("revision", revision)
                if (p.has("running") && !p.optBoolean("running") && streaming && type == "session.info") { /* turn end handled by message.complete */ }
            }
            "message.start" -> { streaming = true
                if (messages.lastOrNull()?.role != "assistant") messages.add(UiMsg("assistant", "")) }
            "message.delta" -> { status = null; updateLast { it.copy(text = it.text + p.str("text")) } }
            "thinking.delta", "reasoning.delta" -> status = s(R.string.thinking_status)
            "tool.start" -> { val n = p.str("name").ifBlank { p.str("tool_name") }.ifBlank { "tool" }; status = "🔧 $n"; updateLast { it.copy(tools = it.tools + n) } }
            "message.complete" -> {
                val c = p.str("text").ifBlank { p.str("content") }
                if (c.isNotBlank()) updateLast { it.copy(text = c) }
                Notify.alert(getApplication<Application>(), s(R.string.n_reply_done, currentTitle ?: s(R.string.chat)), c.ifBlank { "…" })
                streaming = false; status = null; dropEmpty()
                viewModelScope.launch { refreshIds() }
                if (p.optString("outcome") !in setOf("", "completed")) error = "Turn ended: ${p.optString("outcome")}"
                if (currentTitle == null) refreshSessions()
            }
            "approval.request" -> {
                val ch = p.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("once", "deny")
                approval = Approval(p.optString("prompt_id"), p.optString("command"), p.optString("description"), ch,
                    p.optInt("execution_generation", generation))
                Notify.alert(getApplication<Application>(), s(R.string.n_approval), p.optString("command"))
            }
            "clarify.request" -> {
                val ch = p.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                clarify = Clarify(p.optString("prompt_id"), listOf(ClarifyQ("q", p.optString("question"), ch, p.optBoolean("multi_select") && ch.isNotEmpty())),
                    p.optInt("execution_generation", generation))
                Notify.alert(getApplication<Application>(), s(R.string.n_clarify), p.optString("question"))
            }
            "approval.settled" -> if (approval?.reqId == p.optString("prompt_id")) approval = null
            "clarify.settled" -> if (clarify?.reqId == p.optString("prompt_id")) clarify = null
            "session.title" -> p.optString("title").ifBlank { null }?.let { currentTitle = it }
            "error" -> { error = p.optString("message").ifBlank { "Hermes error" }; streaming = false; dropEmpty()
                Notify.alert(getApplication<Application>(), s(R.string.n_error), error ?: "") }
        }
    }

    private fun onServerRequest(id: String, method: String, p: JSONObject) {
        when (method) {
            "approval" -> {
                val ch = p.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("once", "deny")
                approval = Approval(id, p.optString("command"), p.optString("description"), ch)
            }
            "clarify" -> {
                val qs = p.optJSONArray("questions") ?: JSONArray()
                clarify = Clarify(id, (0 until qs.length()).map { qs.getJSONObject(it) }.map { q ->
                    val ch = q.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                    ClarifyQ(q.getString("qid"), q.optString("question"), ch, q.optBoolean("multi_select") && ch.isNotEmpty())
                })
            }
            else -> gw?.respond(id, null, "not supported on the phone")
        }
    }

    fun answerApproval(choice: String) {
        val a = approval ?: return; approval = null
        if (a.gen == null) { gw?.respond(a.reqId, JSONObject().put("choice", choice)); return }
        viewModelScope.launch { runCatching { gateway().call("approval.respond", JSONObject().put("session_id", liveId)
            .put("execution_generation", a.gen).put("prompt_id", a.reqId).put("choice", choice)) }.onFailure { fail(it) } }
    }
    fun answerClarify(answers: Map<String, String>?) {
        val c = clarify ?: return; clarify = null
        if (c.gen != null) {
            viewModelScope.launch { runCatching { gateway().call("clarify.respond", JSONObject().put("session_id", liveId)
                .put("execution_generation", c.gen).put("prompt_id", c.reqId).put("answer", answers?.values?.firstOrNull().orEmpty())) }
                .onFailure { fail(it) } }
            return
        }
        gw?.respond(c.reqId, if (answers == null) JSONObject().put("outcome", "cancelled")
            else JSONObject().put("answers", JSONObject(answers as Map<*, *>)).put("outcome", "submitted"))
    }

    /** Inject text into the running turn (Hermes sees it with the next tool result) without interrupting. */
    private fun steer(t: String) {
        val sid = liveId ?: return
        messages.add(messages.size.coerceAtLeast(1) - 1, UiMsg("user", t))
        viewModelScope.launch {
            try {
                val g = gateway()
                var r = runCatching { g.call("session.steer", JSONObject().put("session_id", sid).put("text", t).put("execution_generation", generation)) }
                if (r.isFailure) {  // generation moved on: refresh once and retry
                    val info = g.call("session.info", JSONObject().put("session_id", sid))
                    generation = info.optInt("execution_generation", generation)
                    r = runCatching { g.call("session.steer", JSONObject().put("session_id", sid).put("text", t).put("execution_generation", generation)) }
                }
                val res = r.getOrThrow()
                if (res.optString("status") == "rejected") throw ApiException("steer_rejected")
                status = s(R.string.steered)
            } catch (e: Exception) {
                // Turn may have just ended: send it as a normal message instead.
                if (!streaming) { messages.removeAll { it.role == "user" && it.text == t && it.id == null }; sendMessage(t, emptyList()) } else fail(e)
            }
        }
    }

    fun stop() {
        val sid = liveId ?: return
        viewModelScope.launch {
            val g = runCatching { gateway() }.getOrElse { fail(it); return@launch }
            val r = runCatching { g.call("session.interrupt", JSONObject().put("session_id", sid).put("execution_generation", generation)) }
            if (r.exceptionOrNull()?.message == "stale_generation") {
                // Refresh the fence from a snapshot and retry once.
                runCatching {
                    generation = g.call("session.resume", JSONObject().put("session_id", sid).put("omit_messages", true)).optInt("execution_generation", generation)
                    g.call("session.interrupt", JSONObject().put("session_id", sid).put("execution_generation", generation))
                }.onFailure { fail(it) }
            } else r.onFailure { fail(it) }
            status = s(R.string.stopping)
        }
    }

    // ---------- attachments ----------
    fun addAttachment(uri: Uri, image: Boolean) = viewModelScope.launch {
        if (!hotfix.attachments) { error = s(R.string.attachments_disabled); return@launch }
        try { attachments.add(withContext(Dispatchers.IO) { readAttachment(uri, image) }) } catch (e: Exception) { error = e.message }
    }

    private fun readAttachment(uri: Uri, image: Boolean): Attachment {
        val cr = getApplication<Application>().contentResolver
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
        val mime = cr.getType(uri).orEmpty()
        if (image || mime.startsWith("image/")) {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
                val s = info.size; val m = maxOf(s.width, s.height)
                if (m > 1600) dec.setTargetSize(s.width * 1600 / m, s.height * 1600 / m)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return Attachment(name.substringBeforeLast('.') + ".jpg", true, "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }
        val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
        if (bytes.size > 200_000) throw ApiException("$name is too large (max 200 KB for text files)")
        if (bytes.take(4000).any { it == 0.toByte() }) throw ApiException("$name looks binary; only images and text files are supported")
        return Attachment(name, false, String(bytes, Charsets.UTF_8))
    }

    fun saveSettings(relay: String, lan: String, key: String?) {
        store.relayUrl = relay; store.lanUrl = lan; store.activeRelay = null; if (!key.isNullOrBlank()) store.apiKey = key
        gw?.close(); gw = null; liveId = null
        viewModelScope.launch {
            try {
                pickRelay(); api().health(); api().sessions()
                gateway(); status = s(R.string.connected_relay)
                error = null; offline = false; screen = Screen.Sessions; refreshSessions(); loadMeta()
            } catch (e: Exception) { fail(e) }
        }
    }
}

/** Phone-side commands: name to description string resource. */
val SLASH_COMMANDS = listOf(
    "/new" to R.string.cmd_new, "/model" to R.string.cmd_model, "/think" to R.string.cmd_think,
    "/retry" to R.string.cmd_retry, "/stop" to R.string.cmd_stop, "/profile" to R.string.cmd_profile,
    "/sessions" to R.string.cmd_sessions, "/delete" to R.string.cmd_delete, "/help" to R.string.cmd_help,
)

/** optString that treats JSON null as empty (org.json returns the literal "null"). */
private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)


/** Hermes stores gateway/system notices (background-process results, compaction notes, ...) as "user"
 *  rows. Hide those in the phone UI; unwrap real out-of-band user messages. */
object Internal {
    private val HIDE = listOf("[IMPORTANT: Background process", "[IMPORTANT: Background", "[INTERNAL NOTIFICATION",
        "[System note:", "[CONTEXT COMPACTION", "[PRIOR CONTEXT", "[Context from the interrupted", "[SYSTEM:")
    private val OOB = Regex("""\[OUT-OF-BAND USER MESSAGE[^\]]*\]\s*([\s\S]*?)\s*\[/OUT-OF-BAND USER MESSAGE\]""")
    fun clean(role: String, text: String): String? {
        if (role == "tool") return null
        if (role != "user") return text.takeUnless { it.trim() == "[SILENT]" }
        val t = text.trimStart()
        OOB.find(t)?.let { return it.groupValues[1].trim().ifBlank { null } }
        if (HIDE.any { t.startsWith(it) } || t.contains("[INTERNAL NOTIFICATION")) return null
        return text
    }
}
