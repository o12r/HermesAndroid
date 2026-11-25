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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

enum class Screen { Sessions, Chat, Settings, Screens }
data class UiMsg(val role: String, val text: String, val tools: List<String> = emptyList(), val id: Int? = null,
    /** Thinking / tool calls / tool output behind this reply (shown collapsed). */
    val steps: List<Step> = emptyList(),
    /** Send failed: shows a red ! with resend / edit / delete. [resend] is the original text. */
    val failed: Boolean = false, val resend: String? = null, val uid: String = UUID.randomUUID().toString(),
    /** A file the phone saved from the Inbox (role "file"): content Uri, shown inline. */
    val file: String? = null,
    /** Session the file belongs to (file messages only). */
    val session: String? = null,
    /** Tokens this reply used (input/output), from the PC's session totals; null if unknown. */
    val tokIn: Long? = null, val tokOut: Long? = null,
    /** Speeds measured on the phone for this reply: prompt tokens / time-to-first-token, output tokens / generation time (tok/s). */
    val prefillTps: Float? = null, val genTps: Float? = null,
    /** Send time (epoch ms): stored timestamp for history, creation time for live messages; 0 = unknown. */
    val ts: Long = System.currentTimeMillis(),
    /** Sub-agent / background task card (role "task"). */
    val task: TaskInfo? = null)
/** Notice card for a sub-agent / background task: [chat] is its read-only transcript tab. */
data class TaskInfo(val key: String, val kind: String, val status: String, val startedAt: Long, val endedAt: Long, val chat: Chat,
    /** kind "proc" (terminal background process): parsed progress 0-100 (null = unknown) and its last output line. */
    val percent: Double? = null, val last: String = "")
data class Attachment(val name: String, val isImage: Boolean, val payload: String, val pcPath: String? = null)
data class Approval(val reqId: String, val command: String, val description: String, val choices: List<String>, val gen: Int? = null)
data class Project(val id: String, val name: String, val path: String?, val isHome: Boolean, val isAuto: Boolean,
    val archived: Boolean, val sessionIds: List<String>, val homeCwd: String? = null)
/** A PC screen for the screen manager. kind: physical | screen-off | agent; role: main | secondary (index 1, 2, ...). */
data class PcScreen(val name: String, val kind: String, val role: String, val index: Int, val on: Boolean,
    val w: Int, val h: Int, val hz: Int, val session: String?, val profile: String?, val cwd: String?,
    val title: String? = null, val project: String? = null)
data class ClarifyQ(val qid: String, val question: String, val choices: List<String>, val multi: Boolean = false)
/** One PC resource sample from the relay's GET /pc/stats (percentages, gpu null when unavailable). */
data class PcStat(val cpu: Double?, val mem: Double?, val gpu: Double?, val ts: Long, val gpus: List<Pair<String, Double>> = emptyList())
data class Clarify(val reqId: String, val questions: List<ClarifyQ>, val gen: Int? = null)  // gen != null: canonical prompt

/** State of one conversation. Kept per chat so several can run at once and events never land in the wrong one. */
class Chat(val profile: String, id: String?, title: String?) {
    /** Non-null for a page tab ("settings" / "pc"), not a conversation. */
    var page: String? = null
    var id by mutableStateOf(id)                 // stored session id (null until a new chat is created)
    var title by mutableStateOf(title)
    val messages = mutableStateListOf<UiMsg>()
    // Reading position, restored when the chat is shown again; follow=true means pinned to the newest output.
    var scrollIdx = 0
    var scrollOff = 0
    var follow = true
    var streaming by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var approval by mutableStateOf<Approval?>(null)
    var clarify by mutableStateOf<Clarify?>(null)
    var liveId: String? = null                   // live id from session.resume/create on this profile's connection
    var generation = 0
    var revision = 0
    var dropped = false                          // a turn was running when the connection dropped
    var newCwd: String? = null
    var projectId: String? = null               // project this chat was created/opened in (shown before its session exists)
    var model by mutableStateOf("")              // per chat: "provider|model" or "" (server default)
    /** What the PC session actually runs ("provider|model", effort); null until the server reports it. */
    var serverModel: String? = null
    var serverEffort: String? = null
    /** Created on the phone and no prompt sent yet: the phone's model pick wins until the first turn. */
    var createdHere = false
    var effort by mutableStateOf("")
    /** Context in use / context window of the PC session (0 = unknown). */
    var ctxUsed by mutableStateOf(0L)
    var ctxMax by mutableStateOf(0L)
    /** Session token totals at the last reply, to work out what each reply used. */
    var totIn: Long? = null
    var turnStartAt = 0L
    var firstTokenAt = 0L
    var totOut: Long? = null
    var loaded = false
    /** Transcript of a sub-agent / background task: no input box, never sent to. */
    var readOnly by mutableStateOf(false)
    /** Unsent text in the input box; survives leaving and re-opening the chat. */
    var draft by mutableStateOf("")
    /** A reply finished while this chat wasn't on screen; shown as a red dot on its tab. */
    var unread by mutableStateOf(false)
    /** Messages waiting for the running turn to finish; sent one by one, in order. */
    val queue = mutableStateListOf<String>()
}

/**
 * Everything goes through the PC relay: lists/history/rename/delete via the gateway's native HTTP API;
 * chat turns, streaming, approvals, models and real slash commands via the desktop protocol (so desktop-created sessions work too).
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val store = SecureStore(app)
    /** What is on screen is the active tab: a page tab (sessions / pc / settings) or, when [activePage] is null, the chat [cur]. */
    val screen: Screen get() = when (activePage?.page) { null -> Screen.Chat; "sessions" -> Screen.Sessions; "pc" -> Screen.Screens; else -> Screen.Settings }
    var profile by mutableStateOf(store.profile)
    var sessions by mutableStateOf<List<Session>>(emptyList())
    /** Profiles found on the PC (name to description); cached so the menu works offline. */
    var profiles by mutableStateOf(store.profileCache.ifEmpty { SecureStore.PROFILES.map { it to "" } })
    fun profileNames() = profiles.map { it.first }

    /** Scan the PC for Hermes profiles (profiles.list over the relay). */
    /** Fixed profile order: the user's saved order, then alphabetical; never by recent use. */
    var profileOrder by mutableStateOf(store.profileOrder)
    val orderedProfiles: List<Pair<String, String>> get() = profiles.sortedWith(
        compareBy<Pair<String, String>> { p -> profileOrder.indexOf(p.first).let { if (it < 0) Int.MAX_VALUE else it } }.thenBy { it.first.lowercase() })
    fun saveProfileOrder(names: List<String>) { profileOrder = names; store.profileOrder = names }
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
    init { ApiClient.allowLanHttp = store.allowLan; loadSnap(store.profile)?.let { sessions = it.first; projects = it.second; activeProject = it.third } }
    /** Project the user last tapped on the session list; the "New chat" button uses it before the default project. */
    var lastProject by mutableStateOf<Project?>(null)
    val fabProject: Project? get() = lastProject?.let { l -> projects.find { it.id == l.id } ?: l } ?: projects.find { it.id == activeProject }
    /** Folder for the next new chat (project primary path), null = Hermes default. */
    private var newChatCwd: String? = null
    private var newChatNone = false
    /** All open chats (any profile); [cur] is the one on screen. */
    private val chats = mutableListOf<Chat>()
    var cur by mutableStateOf(Chat(store.profile, null, null).apply { model = store.modelFor(profile); effort = store.effortFor(profile) })
    var currentId: String? get() = cur.id; set(v) { cur.id = v }
    var currentTitle: String? get() = cur.title; set(v) { cur.title = v }
    val messages get() = cur.messages
    var streaming: Boolean get() = cur.streaming; set(v) { cur.streaming = v }
    var status: String? get() = cur.status; set(v) { cur.status = v }
    var approval: Approval? get() = cur.approval; set(v) { cur.approval = v }
    var clarify: Clarify? get() = cur.clarify; set(v) { cur.clarify = v }
    val attachments = mutableStateListOf<Attachment>()
    var error by mutableStateOf<String?>(null)
    var offline by mutableStateOf(false)
    var loading by mutableStateOf(false)
    /** Open chats shown as tabs at the top of the chat screen (any profile). */
    val tabs = mutableStateListOf<Chat>()
    /** Scroll state of the tab row, shared by every screen so switching pages does not rebuild it (and re-scroll from the start). */
    val tabsState = androidx.compose.foundation.lazy.LazyListState()
    private fun addTab(c: Chat) { if (tabs.none { it === c }) tabs.add(c) }
    /** The page tab being shown (null = the chat [cur] is shown). */
    var activePage by mutableStateOf<Chat?>(null)
    init { openPage(if (store.hasKey) "sessions" else "settings", refresh = false) }

    /** Switch to an open tab (switches profile too if the tab belongs to another one). */
    fun switchTo(c: Chat) {
        if (c.page != null) { activePage = c; pageEntered(c.page!!, true); return }
        if (c.profile != profile) {
            profile = c.profile; store.profile = c.profile
            serverCommands = emptyList(); projects = emptyList(); refreshSessions(); loadMeta()
        }
        cur = c; c.unread = false; activePage = null; seen()
        if (c.id != null) viewModelScope.launch {
            if (!c.streaming && !c.loaded) loadMessagesInto(c)
            runCatching { ensureLive(gatewayFor(c.profile), c) }
            loadChatCtx(c)
            if (c === cur) kickProcPoll(c)
        }
    }

    /** Context used/max of a chat from the PC (session.stats), on first entry and on refresh. */
    private suspend fun loadChatCtx(c: Chat) {
        val sid = c.liveId ?: return
        runCatching { withTimeout(8000) { val g = gatewayFor(c.profile)
            val r = g.call("session.stats", JSONObject().put("session_id", sid))
            applyUsage(c, r)
            // A new chat has no session id yet, and an agent not built yet reports no context; the
            // resume snapshot can still carry stored figures if the stats path came up empty.
            if (c.ctxMax <= 0 && c.id != null) {
                val rr = g.call("session.resume", JSONObject().put("session_id", c.id).put("omit_messages", true))
                (rr.optJSONObject("usage") ?: rr).let { applyUsage(c, it) }
            }
        } }
    }

    /** Entering a page tab: PC refreshes its screens, the session list reloads. */
    private fun pageEntered(kind: String, refresh: Boolean) {
        if (!refresh) return
        when (kind) { "pc" -> loadScreens(); "sessions" -> refreshSessions() }
    }
    /** Open (or switch to) a page tab - "sessions" (the start page), "pc" or "settings" - in the same row as the chats. */
    fun openPage(kind: String, refresh: Boolean = true) {
        val t = tabs.firstOrNull { it.page == kind } ?: Chat(profile, null, null).also {
            it.page = kind; it.readOnly = true; it.loaded = true
            it.title = s(when (kind) { "pc" -> R.string.pc; "sessions" -> R.string.sessions; else -> R.string.settings }); tabs.add(it) }
        activePage = t
        pageEntered(kind, refresh)
    }
    /** Back from Settings / PC: the chat that was open, else the session list. */
    fun leavePage() {
        if (tabs.any { it === cur }) { activePage = null; seen() } else openPage("sessions")
    }
    /** Close a tab; a running turn keeps going on the PC. Closing the shown tab moves to its neighbour; closing the last one opens the session list (start page). */
    fun closeTab(c: Chat) {
        val i = tabs.indexOfFirst { it === c }; if (i < 0) return
        tabs.removeAt(i)
        val shown = if (c.page != null) activePage === c else activePage == null && c === cur
        if (c.page == null && c === cur && !shown) { cur = Chat(profile, null, null).initModel(); return }
        if (!shown) return
        val next = tabs.getOrNull(i) ?: tabs.getOrNull(i - 1)
        if (c.page == null && (next == null || next.page != null)) cur = Chat(profile, null, null).initModel()
        if (next != null) switchTo(next) else openPage("sessions")
    }

    /** The open chat is on screen: its notifications are read. */
    fun seen() { if (screen == Screen.Chat && Notify.visible) Notify.clear(getApplication(), cur.id) }

    /** Settings group to open when Settings shows (e.g. "chat" = context, "model" = model defaults); read & cleared by SettingsScreen. */
    var settingsTarget by mutableStateOf<String?>(null)
    /** Open Settings from a chat, targeting a group. */
    fun openSettingsSection(target: String?) { settingsTarget = target; openPage("settings") }

    /** Queue [text] in the current chat: sent when the running turn ends. */
    fun queueMsg(text: String) { val t = text.trim(); if (t.isNotEmpty()) cur.queue.add(t) }
    private fun drainQueue(c: Chat) { if (!c.streaming && c.queue.isNotEmpty()) sendMessage(c.queue.removeAt(0), emptyList(), c) }

    /** Chats with a turn running (for the session list). */
    fun busyIds(): Set<String> = chats.filter { it.streaming && it.profile == profile }.mapNotNull { it.id }.toSet()

    private fun chatFor(profile: String, id: String?, title: String? = null): Chat =
        (if (id != null) chats.find { it.profile == profile && it.id == id } else null)
            ?: Chat(profile, id, title).initModel().also { chats.add(it); if (chats.size > 30) chats.removeAll { c -> !c.streaming && c !== cur && c !== it && chats.indexOf(c) < 10 } }
    var modelOptions by mutableStateOf<ModelOptions?>(null)
    var skills by mutableStateOf<List<Skill>>(emptyList())
    var serverCommands by mutableStateOf<List<Pair<String, String>>>(emptyList())
    // Model and effort belong to the current chat. The last choice per profile is the default for new chats.
    var model: String get() = cur.model; set(v) { cur.model = v }
    var effort: String get() = cur.effort; set(v) { cur.effort = v }
    /** Give a chat its model: the one saved for that session, else the last one picked in this profile. */
    private fun Chat.initModel(): Chat = apply {
        model = id?.let { store.sessionModel(it) } ?: store.modelFor(profile)
        effort = id?.let { store.sessionEffort(it) } ?: store.effortFor(profile)
    }
    var modelMenuOpen by mutableStateOf(false)
    private var lastSent: Pair<String, List<Attachment>>? = null

    // ---- localisation / appearance / updates ----
    private val res get() = L10n.wrap(getApplication(), store.lang)
    fun s(id: Int, vararg a: Any): String = res.getString(id, *a)
    var theme by mutableStateOf(store.theme)
    /** Update source preference ("auto" | "github" | "pc"); auto = PC first, then GitHub. */
    var updateSource by mutableStateOf(store.updateSource)
    fun chooseUpdateSource(v: String) { store.updateSource = v; updateSource = store.updateSource }
    val updates = UpdateManager(app, store)
    var update by mutableStateOf<UpdateInfo?>(null)
    var updateStatus by mutableStateOf<String?>(null)
    var updateProgress by mutableStateOf<Int?>(null)
    var hotfix by mutableStateOf(Hotfix.NONE)
    val forceUpdate get() = hotfix.minVersionCode > updates.currentCode

    // desktop-protocol state: one connection per profile
    private val gws = mutableMapOf<String, GatewayWs>()
    private val gwLock = Mutex()
    private var liveId: String? get() = cur.liveId; set(v) { cur.liveId = v }
    private var generation: Int get() = cur.generation; set(v) { cur.generation = v }
    private var revision: Int get() = cur.revision; set(v) { cur.revision = v }
    private val gw get() = gws[profile]

    private fun api() = ApiClient(store.relayBase(), profile, store.apiKey ?: throw ApiException(s(R.string.err_set_key)))

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch { if (store.hasKey) { delay(800); pickRelay() }; if (offline) reconnect() } }
        override fun onLost(network: Network) { viewModelScope.launch { status = s(R.string.network_changed) } }
    }
    init {
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb)
        viewModelScope.launch { inboxWatch() }
        if (store.hasKey) viewModelScope.launch { pickRelay(); refreshSessions(); loadMeta() }
        checkUpdates(manual = false)
    }

    var clock by mutableStateOf(store.clock.also { Formats.clock = it; Formats.appCtx = app.applicationContext })
    fun chooseClock(v: String) { clock = v; store.clock = v; Formats.clock = v }
    fun chooseTheme(t: String) { theme = t; store.theme = t }
    var powerMode by mutableStateOf(store.powerMode.also { GatewayWs.pingSec = if (it == "saver") 60L else 30L })
    fun choosePower(m: String) {
        powerMode = m; store.powerMode = m; GatewayWs.pingSec = if (m == "saver") 60L else 30L
        KeepAliveService.start(getApplication())      // re-applies the wake lock policy
    }

    /** Files the agent sent to the phone: download progress (0-100) per file name, shown on its chat bubble. */
    val fileProgress = mutableStateMapOf<String, Int>()
    suspend fun saveInboxNow(name: String, session: String?): android.net.Uri {
        fileProgress[name] = 0
        val uri = try { withContext(Dispatchers.IO) { Inbox.save(getApplication(), store, name) { p ->
                viewModelScope.launch(Dispatchers.Main) { if (name in fileProgress) fileProgress[name] = p } } } }
            finally { fileProgress.remove(name) }
        val u = uri.toString()
        var found = false
        for (c in (chats + cur).distinct()) {
            val i = c.messages.indexOfFirst { it.role == "file" && it.text == name && it.file == null }
            if (i >= 0) { c.messages[i] = c.messages[i].copy(file = u); found = true }
        }
        val sid = session?.takeIf { it.isNotBlank() } ?: cur.id
        if (!found) (chats.firstOrNull { it.id == sid } ?: cur.takeIf { sid == null || it.id == sid })
            ?.messages?.add(UiMsg("file", name, file = u, session = sid))
        sid?.let { store.upsertSessionFile(it, name, u) }
        return uri
    }

    fun saveInbox(name: String, session: String?) = viewModelScope.launch {
        if (name in fileProgress) return@launch
        try { saveInboxNow(name, session) } catch (e: Exception) { fail(e) }
    }

    private fun showPendingFile(item: Inbox.Item) {
        val sid = item.session.ifBlank { null } ?: cur.id
        (chats.firstOrNull { it.id == sid } ?: cur.takeIf { sid == null || it.id == sid })
            ?.messages?.add(UiMsg("file", item.name, file = null, session = sid))
        sid?.let { store.upsertSessionFile(it, item.name, "") }
    }

    /** Real-time inbox: long-polls the PC relay; images are saved at once, other files appear as cards. */
    private suspend fun inboxWatch() {
        var known: Set<String> = emptySet()
        while (true) {
            if (!store.hasKey) { delay(3000); continue }
            try {
                val items = withContext(Dispatchers.IO) { Inbox.list(store, wait = 25, known = known) }
                known = items.map { it.key }.toSet()
                for (item in items) {
                    if (store.inboxSeen(item.key)) continue
                    try {
                        if (isImageName(item.name)) saveInboxNow(item.name, item.session) else showPendingFile(item)
                        store.markInboxSeen(item.key)
                    } catch (e: Exception) { delay(5000) }
                }
            } catch (e: Exception) { delay(5000) }
        }
    }

    /** Banner Cancel: stop waiting on the current request, drop reconnect attempts, and clear the banner. */
    fun cancelRequest() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        if (cur.streaming) stop()
        error = null; offline = false; status = null
    }

    /** Launcher icon: "dark" (black, white H) or "light" (white, black H); switches which activity-alias is enabled. */
    var appIcon by mutableStateOf(run {
        val pm = getApplication<android.app.Application>().packageManager
        val light = android.content.ComponentName(getApplication<android.app.Application>(), "dev.oscar.hermes.IconLight")
        if (pm.getComponentEnabledSetting(light) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) "light" else "dark"
    })
    fun chooseIcon(v: String) {
        if (v == appIcon) return
        val app = getApplication<android.app.Application>(); val pm = app.packageManager
        fun set(n: String, on: Boolean) = pm.setComponentEnabledSetting(android.content.ComponentName(app, "dev.oscar.hermes.$n"),
            if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP)
        set(if (v == "light") "IconLight" else "IconDark", true); set(if (v == "light") "IconDark" else "IconLight", false)
        appIcon = v
    }
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
        val u = runCatching { updates.check(updateSource) }.getOrElse { if (manual) updateStatus = s(R.string.update_failed, it.message ?: "?"); return@launch }
        store.lastUpdateCheck = System.currentTimeMillis()
        update = u
        if (manual) updateStatus = if (u == null) s(R.string.up_to_date) else null
    }

    var updateFileBytes by mutableStateOf(updates.downloadedSize())
    private var installJob: kotlinx.coroutines.Job? = null
    fun installUpdate() {
        val u = update ?: return
        installJob = viewModelScope.launch {
            updateProgress = 0; updateStatus = null
            runCatching { updates.install(u) { p -> viewModelScope.launch(Dispatchers.Main) { updateProgress = p } } }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) updateStatus = s(R.string.update_failed, it.message ?: "?") }
            updateProgress = null; updateFileBytes = updates.downloadedSize()
        }
    }
    /** Stop the running download; the partial file is kept so a later install resumes it. */
    fun cancelUpdate() {
        installJob?.cancel(); installJob = null
        updateProgress = null; updateStatus = s(R.string.update_cancelled)
        viewModelScope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(300); withContext(Dispatchers.Main) { updateFileBytes = updates.downloadedSize() } }
    }
    fun deleteUpdateFile() {
        installJob?.cancel(); installJob = null; updateProgress = null
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300); updates.deleteFiles()
            withContext(Dispatchers.Main) { updateFileBytes = updates.downloadedSize(); updateStatus = s(R.string.update_file_deleted) }
        }
    }
    override fun onCleared() {
        runCatching { getApplication<Application>().getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCb) }
        gws.values.forEach { it.close() }
    }

    private fun fail(e: Throwable) {
        offline = (e as? ApiException)?.unreachable == true
        val m = e.message ?: e.toString()
        // The gateway answers storage_unavailable when a session's stored creation receipt is missing (e.g. after an orphan reap).
        error = if (m.contains("storage_unavailable")) s(R.string.err_session_corrupt) + " (storage_unavailable)" else m
    }

    /** The phone's own Wi-Fi IPv4 address, or null when it isn't on Wi-Fi. */
    private fun wifiIp(): String? = localNet()?.first
    /** (own IPv4, default gateway) of the LAN-ish network: Wi-Fi, Ethernet, or WSA/emulator's virtual NIC; never VPN or cellular. */
    private fun localNet(): Pair<String, String?>? = runCatching {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
            val ip = lp.linkAddresses.map { it.address }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: return@mapNotNull null
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress
            (if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) 0 else 1) to (ip to gw)
        }.minByOrNull { it.first }?.second
    }.getOrNull() ?: hotspotNet()
    /** This phone is the hotspot (the PC joined it): its own AP interface isn't a Network, so read it directly. */
    private fun hotspotNet(): Pair<String, String?>? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("rmnet") }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress?.let { it to null }
    }.getOrNull()
    private fun sameSubnet(a: String?, b: String?) = a != null && b != null && a.substringBeforeLast('.') == b.substringBeforeLast('.')

    /** Use the main (Tailscale) URL if it answers, else the LAN URL; remembers the winner. On the PC's own Wi-Fi the LAN URL goes first
     *  (direct, cheaper on battery), and the LAN URL is learned automatically (from the relay, or by scanning the subnet). */
    private suspend fun pickRelay(): Boolean {
        val key = store.apiKey ?: return false
        val me = wifiIp()
        val lanFirst = sameSubnet(me, runCatching { java.net.URI(store.lanUrl).host }.getOrNull())
        val gwUrl = localNet()?.second?.let { "http://$it:8643" }
        val order = (if (lanFirst) listOf(store.lanUrl, store.relayUrl, gwUrl) else listOf(store.relayUrl, store.lanUrl, gwUrl)).filterNotNull().filter { it.isNotBlank() }.distinct()
        for (u in order) {
            if (runCatching { withTimeout(4000) { ApiClient(u, profile, key).health() } }.isSuccess) {
                if (store.activeRelay != u) { store.activeRelay = u; gws.values.forEach { it.close() }; gws.clear(); chats.forEach { it.liveId = null } }
                if (me != null) viewModelScope.launch { learnLan(u, key, me) }
                if (u == gwUrl && store.lanUrl.isBlank()) store.lanUrl = u
                return true
            }
        }
        return scanLan(key, me)
    }

    /** Connected through [via]: ask the PC for its LAN addresses and remember the one on our Wi-Fi subnet. */
    private var lanLearnedFor: String? = null
    private suspend fun learnLan(via: String, key: String, me: String) {
        if (lanLearnedFor == me) return
        val ip = runCatching { withTimeout(5000) { ApiClient(via, profile, key).lanIps() } }.getOrNull()?.firstOrNull { sameSubnet(it, me) } ?: return
        lanLearnedFor = me
        val url = "http://$ip:8643"
        if (store.lanUrl != url) { store.lanUrl = url; lanAutoSet = url }
    }
    var lanAutoSet by mutableStateOf<String?>(null)

    /** Neither URL answers but we're on Wi-Fi: look for the relay on our /24 (once a minute at most) and save it as the LAN URL. */
    private var lastScan = 0L
    private suspend fun scanLan(key: String, me: String?): Boolean {
        if (me == null || System.currentTimeMillis() - lastScan < 60_000) return false
        lastScan = System.currentTimeMillis()
        val base = me.substringBeforeLast('.')
        val found: String? = withContext(Dispatchers.IO) {
            val sem = Semaphore(48)
            coroutineScope {
                val jobs = (1..254).map { "$base.$it" }.filter { it != me }.map { ip ->
                    async {
                        sem.withPermit {
                            val name = runCatching { withTimeout(900) { ApiClient("http://$ip:8643", profile, key).health() } }.getOrNull()
                            if (name == "hermes-phone-relay") ip else null
                        }
                    }
                }
                jobs.awaitAll().filterNotNull().firstOrNull()
            }
        } ?: return false
        val url = "http://$found:8643"
        store.lanUrl = url; lanAutoSet = url; store.activeRelay = url
        gws.values.forEach { it.close() }; gws.clear(); chats.forEach { it.liveId = null }
        return true
    }

    /** Keep the relay connection up (also in the background): reconnect with backoff, then re-sync every open chat. */
    private val reconnectJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var notifiedDrop = false
    /** Profiles whose background reconnect gave up after 3 tries; resumed when the app comes to the foreground. */
    private val gaveUp = mutableSetOf<String>()
    fun onForeground() {
        kickProcPoll()
        if (gaveUp.isEmpty()) return
        KeepAliveService.start(getApplication())
        gaveUp.toList().forEach { gaveUp.remove(it); scheduleReconnect(it) }
    }
    private fun scheduleReconnect(prof: String) {
        if (!store.hasKey || reconnectJobs[prof]?.isActive == true) return
        if (chats.none { it.profile == prof && it.id != null } && prof != profile) return
        reconnectJobs[prof] = viewModelScope.launch {
            var delayMs = 1500L
            var bgFails = 0
            while (true) {
                kotlinx.coroutines.delay(delayMs)
                if (runCatching { pickRelay(); gatewayFor(prof); resync(prof) }.isSuccess) {
                    if (prof == profile) { offline = false; error = null }
                    notifiedDrop = false; return@launch
                }
                if (!notifiedDrop) {      // notify at the first failed retry (whether or not the app is open)
                    notifiedDrop = true; Notify.alert(getApplication<Application>(), s(R.string.n_disconnected), s(R.string.n_disconnected_text))
                }
                // In the background: 3 failed tries and we stop (no endless wake-ups); the foreground reconnects.
                if (!Notify.visible && ++bgFails >= 3) {
                    gaveUp.add(prof)
                    if (!notifiedDrop) { notifiedDrop = true; Notify.alert(getApplication<Application>(), s(R.string.n_disconnected), s(R.string.n_disconnected_text)) }
                    KeepAliveService.stop(getApplication()); return@launch
                }
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    /** After a (re)connect: re-attach every open chat of [prof]; a turn that finished meanwhile is reloaded. */
    private suspend fun resync(prof: String) {
        val g = gatewayFor(prof)
        for (c in chats.filter { it.profile == prof && it.id != null && (it === cur || it.dropped || it.streaming) }) {
            runCatching {
                val running = attach(g, c)
                if (!running && (c.dropped || c.streaming)) { c.streaming = false; c.status = null; loadMessagesInto(c) }
                else if (running) { c.streaming = true; c.status = s(R.string.reconnected_running) }
                c.dropped = false
            }
        }
    }

    // ---------- gateway connection ----------
    private suspend fun gateway(): GatewayWs = gatewayFor(profile)

    private suspend fun gatewayFor(prof: String): GatewayWs = gwLock.withLock {
        gws[prof]?.takeIf { it.open }?.let { return it }
        gws.remove(prof)?.close()
        chats.filter { it.profile == prof }.forEach { it.liveId = null }
        lateinit var g: GatewayWs
        g = GatewayWs(store.relayBase(), prof, store.apiKey ?: throw ApiException("Set the API key in Settings"),
            onEvent = { t, sid, p -> viewModelScope.launch(Dispatchers.Main) { onEvent(prof, t, sid, p) } },
            onServerRequest = { id, m, p -> viewModelScope.launch(Dispatchers.Main) { onServerRequest(g, id, m, p) } },
            onClosed = { why -> viewModelScope.launch(Dispatchers.Main) {
                if (gws[prof] !== g) return@launch      // replaced or closed on purpose
                gws.remove(prof)
                for (c in chats.filter { it.profile == prof }) {
                    c.liveId = null
                    if (c.streaming) { c.dropped = true; c.status = s(R.string.reconnecting) }
                }
                if (prof == profile && cur.streaming) why?.let { error = it; offline = true }
                scheduleReconnect(prof)
            } })
        withContext(Dispatchers.IO) { g.connect() }
        gws[prof] = g
        if (prof == profile && serverCommands.isEmpty()) viewModelScope.launch { loadCatalog() }
        g
    }

    /** Attach a chat to its live session (creates one for a new chat). Returns whether a turn is running. */
    private suspend fun attach(g: GatewayWs, c: Chat): Boolean {
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
                catch (e: ApiException) { if (e.unreachable) throw e; android.util.Log.w("HermesRpc", "session.create with cwd failed (${e.message}); retrying without cwd"); g.call("session.create", createParams(false)) }).also { r ->
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
            viewModelScope.launch(Dispatchers.Main) { onEvent(c.profile, pr.optString("kind") + ".request", live, pr) } } }
        val running = snap.optBoolean("running")
        if (running) viewModelScope.launch(Dispatchers.Main) { c.streaming = true }
        return running
    }

    /**
     * The PC session is the source of truth for its model: show what it runs (desktop picks included) so the phone
     * never switches it back by accident. A new chat keeps the phone's pick (it is sent with the first prompt).
     */
    private fun adoptServerModel(c: Chat, info: JSONObject) {
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
    private fun applyUsage(c: Chat, u: JSONObject): Pair<Long, Long>? {
        // The gateway renamed these: session.stats (and session.usage event frames) now carry
        // context_tokens/context_length. Keep reading the legacy names too for older frames.
        val used = u.optLong("context_used", -1).takeIf { it >= 0 } ?: u.optLong("context_tokens", -1)
        val max = u.optLong("context_max", 0).takeIf { it > 0 } ?: u.optLong("context_length", 0)
        used.takeIf { it >= 0 }?.let { c.ctxUsed = it }
        max.takeIf { it > 0 }?.let { c.ctxMax = it }
        // session.stats (and the flat part of session.usage frames) names the counters input_tokens/output_tokens/prompt_tokens;
        // the nested TUI "usage" view (message.complete) uses input/output/prompt. Read both, or the reply baseline never gets set.
        if (!u.has("input") && !u.has("output") && !u.has("input_tokens") && !u.has("output_tokens") && !u.has("prompt_tokens")) return null
        // "input" counts only uncached tokens (often just a few); "prompt" = input + cache reads/writes = what was really sent.
        val inp = u.optLong("prompt", 0).takeIf { it > 0 } ?: u.optLong("prompt_tokens", 0).takeIf { it > 0 }
            ?: u.optLong("input", 0).takeIf { it > 0 } ?: u.optLong("input_tokens", 0)
        val t = inp to (u.optLong("output", 0).takeIf { it > 0 } ?: u.optLong("output_tokens", 0).takeIf { it > 0 } ?: u.optLong("completion_tokens", 0))
        if (c.totIn == null) { c.totIn = t.first; c.totOut = t.second }
        return t
    }

    /** Live id for [c] on its profile's connection. */
    private suspend fun ensureLive(g: GatewayWs, c: Chat = cur): String { c.liveId?.let { return it }; attach(g, c); return c.liveId!! }

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
    /** True while a model/effort switch is in flight to the PC; the chips show the PC's state until it confirms. */
    var switching by mutableStateOf(false)

    /**
     * Model pick. A chat that already has a PC session switches it on the PC (config.set) and then re-reads the
     * session, so the chip only changes once the PC confirms. A not-yet-created chat keeps the pick locally; it goes
     * out with the first prompt.
     */
    fun chooseModel(key: String) {
        store.setModelFor(profile, key)
        val c = cur
        if (c.id == null || c.createdHere && c.serverModel == null) { model = key; return }
        val m = key.substringAfter('|', "").ifBlank { null }
        val pr = key.substringBefore('|', "").ifBlank { null }
        val payload = JSONObject().put("model", m ?: "reset")
        if (m != null && pr != null) payload.put("provider", pr)
        switchOnPc(c, "model", payload)
    }
    fun chooseEffort(e: String) {
        store.setEffortFor(profile, e)
        val c = cur
        if (c.id == null || c.createdHere && c.serverModel == null) { effort = e; return }
        switchOnPc(c, "reasoning", JSONObject().put("effort", e.ifBlank { JSONObject.NULL }))
    }

    /** A guarded model pick the PC wants confirmed: (message, payload to resend with its confirm token). */
    var modelConfirm by mutableStateOf<Pair<String, JSONObject>?>(null)
    /** Set when a model switch left the chat's context above the new window: the UI offers to compress. */
    var compressAsk by mutableStateOf<Chat?>(null)
    fun answerCompress(yes: Boolean) {
        val c = compressAsk ?: return; compressAsk = null
        if (yes && c === cur) compressChat("")
    }
    fun confirmModel(yes: Boolean) {
        val p = modelConfirm ?: return; modelConfirm = null
        if (yes) switchOnPc(cur, "model", p.second)
    }

    /** Model/effort pick as the PC's revision-fenced session.mutate (what the desktop does), then re-read the session. */
    private fun switchOnPc(c: Chat, op: String, payload: JSONObject) = viewModelScope.launch {
        switching = true
        try {
            val g = gatewayFor(c.profile); val sid = ensureLive(g, c)
            val r = mutate(op, payload)
            if (r.optString("status") == "confirmation_required" || r.optBoolean("confirm_required")) {
                val token = r.optString("confirm")
                modelConfirm = r.optString("confirm_message").ifBlank { s(R.string.confirm_model) } to
                    JSONObject(payload.toString()).put("confirm", token)
            } else (r.optString("note").ifBlank { r.optString("warning") }).ifBlank { null }?.let { c.messages.add(UiMsg("system", it)) }
            syncFromPc(g, c, sid)
            if (op == "model" && modelConfirm == null) runCatching {
                applyUsage(c, g.call("session.stats", JSONObject().put("session_id", sid)))
                if (c.ctxMax > 0 && c.ctxUsed > c.ctxMax) compressAsk = c
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = s(R.string.switch_failed) + ": " + (e.message ?: e.javaClass.simpleName)
            runCatching { syncFromPc(gatewayFor(c.profile), c, c.liveId ?: return@runCatching) }
        } finally { switching = false }
    }

    /** Re-read model/effort from the PC session (the only source of truth once it exists). */
    private suspend fun syncFromPc(g: GatewayWs, c: Chat, sid: String) {
        val info = runCatching { g.call("session.info", JSONObject().put("session_id", sid)) }.getOrNull()
            ?: g.call("session.resume", JSONObject().put("session_id", c.id).put("omit_messages", true)).optJSONObject("info")
            ?: return
        c.createdHere = false
        adoptServerModel(c, info)
    }

    private fun note(text: String) { messages.add(UiMsg("system", text)) }

    // ---------- sessions ----------
    /** App resumed / network back: make sure every profile with open chats is connected and in sync. */
    fun reconnect() {
        if (!store.hasKey) return
        viewModelScope.launch {
            try { pickRelay(); api().health(); offline = false; error = null
                refreshSessions(); if (modelOptions == null) loadMeta()
                for (prof in (chats.map { it.profile } + profile).distinct()) runCatching { resync(prof) }
            } catch (e: Exception) { fail(e) }
        }
    }

    fun setProfileTo(p: String) {
        if (p == profile) return
        // Other profiles' connections and chats stay open, so their running turns keep streaming.
        serverCommands = emptyList()
        profile = p; store.profile = p
        cur = Chat(p, null, null).initModel(); projects = emptyList(); sessions = emptyList()
        openPage("sessions"); loadMeta()
    }

    /** Stored session ids that are open in some client (phone, desktop, CLI) per profile, from the gateway's session.active_list. */
    var liveKeys by mutableStateOf<Map<String, Set<String>>>(emptyMap())

    private val deviceId: String by lazy { android.provider.Settings.Secure.getString(getApplication<Application>().contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "phone" }
    private fun relayJson(method: String, path: String, body: String?): String? {
        val key = store.apiKey ?: return null
        val rb = okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + path).header("Authorization", "Bearer $key")
        if (method == "POST") rb.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
        return okhttp3.OkHttpClient.Builder().readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build().newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }
    /** Tell the PC relay which chats this phone has open as tabs (re-sent on every change and every minute), so other devices can mark them active. */
    private val tabReporter = viewModelScope.launch {
        delay(1500)
        launch { while (true) { delay(60_000); reportTabs() } }
        androidx.compose.runtime.snapshotFlow { tabs.map { it.profile to it.id } }.collect { reportTabs() }
    }
    private suspend fun reportTabs() = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray(); tabs.toList().forEach { c -> c.id?.let { arr.put(JSONObject().put("profile", c.profile).put("id", it)) } }
            relayJson("POST", "/tabs", JSONObject().put("device", deviceId).put("tabs", arr).toString())
        }
    }

    private fun loadLive() = viewModelScope.launch {
        val remote: Map<String, Set<String>> = withContext(Dispatchers.IO) {
            runCatching {
                val a = JSONArray(relayJson("GET", "/tabs", null) ?: "[]")
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("device") != deviceId }
                    .groupBy({ it.optString("profile") }, { it.optString("id") }).mapValues { it.value.toSet() }
            }.getOrDefault(emptyMap())
        }
        val profs = (expandedProfiles + profile).toList()
        val res = profs.map { p -> async {
            p to runCatching { withTimeout(8000) {
                val a = gatewayFor(p).call("session.active_list", JSONObject()).optJSONArray("sessions") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.takeIf { o -> o.optString("status") != "idle" }?.optString("session_key")?.ifBlank { null } }.toSet()  // idle = merely kept alive (e.g. by context probes), not in use
            } }.getOrNull() } }.awaitAll()
        val own = tabs.toList().filter { it.id != null }.groupBy({ it.profile }, { it.id!! }).mapValues { it.value.toSet() }
        val gw = res.mapNotNull { (p, s) -> s?.let { p to it } }.toMap()
        liveKeys = (gw.keys + remote.keys + own.keys).associateWith { p -> gw[p].orEmpty() + remote[p].orEmpty() + own[p].orEmpty() }
    }

    fun refreshSessions() = viewModelScope.launch {
        loading = true
        val prof = profile
        primeCtx(prof)
        loadLive()
        expandedProfiles.filter { it != prof }.forEach { loadOther(it) }
        try {
            // Sessions and the project tree load in parallel and are applied together, so the list never
            // shows everything under Home first and re-sorts into projects a moment later.
            val (ss, pj) = kotlinx.coroutines.coroutineScope {
                val a = async { api().sessions() }
                val b = async { runCatching { fetchProjects(gateway()) }.onFailure { android.util.Log.w("HermesRpc", "loadProjects: $it") }.getOrNull() }
                a.await() to b.await()
            }
            if (prof != profile) return@launch
            sessions = ss; offline = false; error = null
            if (pj != null) { projects = pj.first; activeProject = pj.second; saveSnap(prof, ss, pj.first, pj.second) }
            currentId?.let { id -> sessions.find { it.id == id }?.title?.let { currentTitle = it } }
        } catch (e: Exception) { fail(e) } finally { loading = false }
        loadSessionCtx()
    }

    /** Reload the current chat's transcript and the session list (top-bar refresh button). */
    var chatRefreshing by mutableStateOf(false)
    fun refreshChat() = viewModelScope.launch {
        chatRefreshing = true
        try {
            val c = cur
            if (c.id != null && !c.streaming) loadMessagesInto(c)
            if (c.id != null) { runCatching { ensureLive(gatewayFor(c.profile), c) }; loadChatCtx(c) }
            refreshSessions().join()
        } finally { chatRefreshing = false }
    }

    /** One probed path to the PC relay (test-connection feedback). */
    data class Probe(val label: String, val url: String, val ok: Boolean, val ms: Long, val active: Boolean, val error: String)
    var probes by mutableStateOf<List<Probe>>(emptyList())
    var probing by mutableStateOf(false)
    private fun pathLabel(url: String): String {
        val h = runCatching { java.net.URI(url).host }.getOrNull() ?: return s(R.string.path_other)
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        return when {
            p.size == 4 && p[0] == 100 && p[1] in 64..127 -> "Tailscale"
            h.endsWith(".ts.net") -> "Tailscale"
            p.size == 4 && (p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)) -> s(R.string.path_lan)
            else -> s(R.string.path_other)
        }
    }
    /** Probes every known path in parallel (main, LAN, gateway found on our subnet) and shows address, reachability, latency and the one in use. */
    fun testConnections(relay: String, lan: String) = viewModelScope.launch {
        val key = store.apiKey ?: run { status = s(R.string.err_set_key); return@launch }
        probing = true; probes = emptyList()
        try {
            val gwUrl = localNet()?.second?.let { "http://$it:8643" }
            val urls = listOf(relay.trim().trimEnd('/'), lan.trim().trimEnd('/'), gwUrl).filterNotNull().filter { it.isNotBlank() }.distinct()
            val active = store.activeRelay
            probes = coroutineScope {
                urls.map { u -> async(Dispatchers.IO) {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val r = runCatching { withTimeout(5000) { ApiClient(u, profile, key).health() } }
                    Probe(pathLabel(u), u, r.isSuccess, android.os.SystemClock.elapsedRealtime() - t0, r.isSuccess && u == (active ?: relay.trim().trimEnd('/')),
                        r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.TimeoutCancellationException) "timeout" else it.message.orEmpty() }.orEmpty())
                } }.awaitAll()
            }
        } finally { probing = false }
    }

    private suspend fun loadProjects() {
        val (list, active) = fetchProjects(gateway())
        activeProject = active; projects = list
    }

    /** Sessions + projects of every other expanded profile (cached; refreshed with the session list). */
    var otherData by mutableStateOf<Map<String, Triple<List<Session>, List<Project>, String?>>>(emptyMap())
    /** Profiles whose project tree is expanded on the session page (several can be open at once). */
    var expandedProfiles by mutableStateOf(setOf(store.profile))

    fun toggleProfileExpanded(p: String) {
        expandedProfiles = if (p in expandedProfiles) expandedProfiles - p else expandedProfiles + p
        if (p in expandedProfiles && p != profile) { cachedFor(p)?.let { otherData = otherData + (p to it) }; loadOther(p) }
    }

    private fun cachedFor(p: String) = otherData[p] ?: loadSnap(p)

    private fun saveSnap(p: String, ss: List<Session>, pr: List<Project>, act: String?) = runCatching {
        val o = JSONObject().put("active", act ?: "")
        o.put("sessions", JSONArray().also { a -> ss.forEach { s -> a.put(JSONObject().put("id", s.id).put("title", s.title ?: JSONObject.NULL)
            .put("preview", s.preview ?: JSONObject.NULL).put("la", s.lastActive ?: JSONObject.NULL).put("n", s.count).put("src", s.source ?: JSONObject.NULL)) } })
        o.put("projects", JSONArray().also { a -> pr.forEach { x -> a.put(JSONObject().put("id", x.id).put("name", x.name).put("path", x.path ?: JSONObject.NULL)
            .put("home", x.isHome).put("auto", x.isAuto).put("arch", x.archived).put("ids", JSONArray(x.sessionIds)).put("cwd", x.homeCwd ?: JSONObject.NULL)) } })
        store.setSnap(p, o.toString())
    }

    private fun loadSnap(p: String): Triple<List<Session>, List<Project>, String?>? = runCatching {
        val o = JSONObject(store.snap(p) ?: return null)
        fun JSONObject.s(k: String) = if (isNull(k)) null else optString(k)
        val sa = o.getJSONArray("sessions"); val pa = o.getJSONArray("projects")
        Triple((0 until sa.length()).map { sa.getJSONObject(it) }.map { Session(it.getString("id"), it.s("title"), it.s("preview"),
                if (it.isNull("la")) null else it.getDouble("la"), it.optInt("n"), it.s("src")) },
            (0 until pa.length()).map { pa.getJSONObject(it) }.map { x -> Project(x.getString("id"), x.getString("name"), x.s("path"), x.optBoolean("home"),
                x.optBoolean("auto"), x.optBoolean("arch"), x.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }, x.s("cwd")) },
            o.optString("active").ifBlank { null })
    }.getOrNull()

    fun loadOther(p: String) = viewModelScope.launch {
        runCatching {
            val (ss, pj) = kotlinx.coroutines.coroutineScope {
                val a = async { apiFor(p).sessions() }; val b = async { fetchProjects(gatewayFor(p)) }
                a.await() to b.await()
            }
            otherData = otherData + (p to Triple(ss, pj.first, pj.second)); saveSnap(p, ss, pj.first, pj.second)
        }.onFailure { android.util.Log.w("HermesRpc", "loadOther $p: $it") }
    }

    /** Make [p] the current profile without leaving the session page; its cached lists show at once. */
    fun enterProfile(p: String) {
        if (p == profile) return
        otherData = otherData + (profile to Triple(sessions, projects, activeProject))
        val d = cachedFor(p)
        serverCommands = emptyList(); profile = p; store.profile = p
        cur = Chat(p, null, null).initModel(); sessions = d?.first ?: emptyList(); projects = d?.second ?: emptyList(); activeProject = d?.third
        expandedProfiles = expandedProfiles + p
        refreshSessions(); loadMeta()
    }

    private suspend fun fetchProjects(g: GatewayWs): Pair<List<Project>, String?> {
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
        newChatCwd = t?.path ?: t?.homeCwd; newChat()
    }

    /** Re-home a stored session into a project by switching its working folder (what the desktop does). */
    fun moveToProject(s: Session, p: Project) = viewModelScope.launch {
        val cwd = p.path ?: p.homeCwd
        if (cwd == null) { error = s(R.string.move_no_folder); return@launch }
        try {
            if (!moveOne(s, cwd)) { error = s(R.string.move_busy); return@launch }
            status = s(R.string.moved_to, p.name); refreshSessions()
        } catch (e: Exception) { fail(e) }
    }

    /** Move several sessions of the current profile into [p] (busy ones are skipped). */
    fun moveManyToProject(list: List<Session>, p: Project) = viewModelScope.launch {
        val cwd = p.path ?: p.homeCwd
        if (cwd == null) { error = s(R.string.move_no_folder); return@launch }
        try {
            var skipped = 0
            for (x in list) if (!moveOne(x, cwd)) skipped++
            if (skipped > 0) error = s(R.string.move_busy) else status = s(R.string.moved_to, p.name)
            refreshSessions()
        } catch (e: Exception) { fail(e); refreshSessions() }
    }

    fun deleteMany(list: List<Session>) = viewModelScope.launch {
        try {
            val ids = list.map { it.id }.toSet()
            for (x in list) api().delete(x.id)
            chats.removeAll { it.profile == profile && it.id in ids }; tabs.removeAll { it.profile == profile && it.id in ids }
            if (currentId in ids) newChat()
        } catch (e: Exception) { fail(e) }
        refreshSessions()
    }

    /** false = the session is busy and was not moved. */
    private suspend fun moveOne(s: Session, cwd: String): Boolean {
        run {
            val g = gateway()
            val snap = g.call("session.resume", JSONObject().put("session_id", s.id).put("omit_messages", true))
            fun params(rev: Int, gen: Int) = JSONObject().put("session_id", snap.getString("session_id")).put("request_id", UUID.randomUUID().toString())
                .put("expected_revision", rev).put("expected_generation", gen).put("operation", "workspace").put("payload", JSONObject().put("cwd", cwd))
            try { g.call("session.mutate", params(snap.optInt("revision"), snap.optInt("execution_generation"))) }
            catch (e: ApiException) {
                if (e.message == "session_busy") return false
                val again = g.call("session.resume", JSONObject().put("session_id", s.id).put("omit_messages", true))
                g.call("session.mutate", params(again.optInt("revision"), again.optInt("execution_generation")))
            }
            chats.find { it.profile == profile && it.id == s.id }?.liveId = null
        }
        return true
    }

    /** New project whose folder doesn't exist on the PC yet: ask before creating it. */
    var missingFolder by mutableStateOf<Pair<String, String>?>(null)

    /** Elevation requests from the PC agent (phone-approved, no UAC prompt on the PC). */
    var elevate by mutableStateOf<List<JSONObject>>(emptyList())

    private fun relayCall(method: String, path: String): String? {
        val key = store.apiKey ?: return null
        val rb = okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + path).header("Authorization", "Bearer $key")
        if (method == "POST") rb.post("{}".toRequestBody("application/json".toMediaType()))
        return okhttp3.OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    suspend fun pollElevate() {
        while (true) {
            val list = runCatching {
                withContext(Dispatchers.IO) { relayCall("GET", "/elevate/pending") }?.let { org.json.JSONArray(it) }
            }.getOrNull()
            elevate = if (list == null) emptyList() else List(list.length()) { list.getJSONObject(it) }
            kotlinx.coroutines.delay(if (Notify.visible) 3000L else 20_000L)     // background: poll rarely
        }
    }

    fun decideElevate(id: String, ok: Boolean) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { relayCall("POST", "/elevate/" + (if (ok) "approve/" else "deny/") + id) } }
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

    private fun relayRequest(path: String, body: JSONObject): okhttp3.Request {
        val key = store.apiKey ?: throw ApiException(s(R.string.err_set_key))
        return okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + path).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    private val relayHttp by lazy { okhttp3.OkHttpClient.Builder().readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build() }

    fun openScreens(from: Screen) { openPage("pc") }

    fun loadScreens() = viewModelScope.launch {
        screensBusy = true
        try {
            val o = withContext(Dispatchers.IO) {
                relayHttp.newCall(relayRequest("/vdisplay/screens", JSONObject())).execute().use { r ->
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
                    val t = gatewayFor(prof).call("projects.tree", JSONObject().put("preview_limit", 0).put("session_limit", 1000))
                    val n = t.optJSONArray("projects") ?: JSONArray()
                    (0 until n.length()).mapNotNull { n.optJSONObject(it) }.flatMap { p ->
                        val label = if (p.optBoolean("isNoProject")) s(R.string.home) else p.optString("label")
                        val ids = p.optJSONArray("sessionIds") ?: JSONArray()
                        (0 until ids.length()).map { ids.optString(it) to label }
                    }.toMap()
                }.getOrDefault(emptyMap())
                list.map { it.copy(title = titles[it.session], project = projs[it.session]) }
            }
            pcScreens = raw.filter { it.kind != "agent" } + named
        } catch (e: Exception) { status = e.message } finally { screensBusy = false }
    }

    /** JPEG of one PC screen, decoded. */
    suspend fun screenShot(name: String): android.graphics.Bitmap = withContext(Dispatchers.IO) {
        relayHttp.newCall(relayRequest("/vdisplay/shot", JSONObject().put("display", name))).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(runCatching { JSONObject(r.body!!.string()).optString("error") }
                .getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}")
            android.graphics.BitmapFactory.decodeStream(r.body!!.byteStream()) ?: throw ApiException("bad image")
        }
    }

    /** Close a session's agent screen (only the agent opens one; the phone may close it). */
    fun closeAgentScreen(session: String) = viewModelScope.launch {
        screensBusy = true
        try {
            withContext(Dispatchers.IO) {
                relayHttp.newCall(relayRequest("/vdisplay/off", JSONObject().put("session", session))).execute().close()
            }
        } catch (e: Exception) { status = e.message } finally { screensBusy = false }
        if (session == cur.id) vdAgent = false
        loadScreens()
    }

    fun vdisplay(op: String, display: String? = null) = viewModelScope.launch {
        vdBusy = true
        try {
            val o = withContext(Dispatchers.IO) {
                val key = store.apiKey ?: throw ApiException(s(R.string.err_set_key))
                val url = store.relayBase().trimEnd('/') + "/vdisplay/" + op
                okhttp3.OkHttpClient.Builder().readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $key")
                        .post(JSONObject().put("session", cur.id ?: "").apply { display?.let { put("display", it) } }.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
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
                    ?: cur.id?.let { id -> vdAgents = if (vdAgent) vdAgents + id else vdAgents - id }
            }
            o?.optString("error")?.takeIf { it.isNotBlank() }?.let { status = it }
        } catch (e: Exception) { status = e.message } finally { vdBusy = false }
        if (op.startsWith("screen-") && screen == Screen.Screens) loadScreens()
    }

    /** Context / compression settings shared by every profile; the phone shows only what the PC reports back. */
    var ctxSettings by mutableStateOf<JSONObject?>(null)
    var ctxBusy by mutableStateOf(false)
    var ctxError by mutableStateOf<String?>(null)
    fun loadCtxSettings(values: JSONObject? = null) = viewModelScope.launch {
        ctxBusy = true; ctxError = null
        try {
            val o = withContext(Dispatchers.IO) {
                val key = store.apiKey ?: throw ApiException(s(R.string.err_set_key))
                val b = okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + "/config/context").header("Authorization", "Bearer $key")
                if (values != null) b.post(values.toString().toRequestBody("application/json".toMediaType()))
                relayHttp.newCall(b.build()).execute().use { r ->
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
    fun loadModelSettings(models: JSONObject? = null) = viewModelScope.launch {
        modelBusy = true; modelError = null
        try {
            val o = withContext(Dispatchers.IO) {
                val key = store.apiKey ?: throw ApiException(s(R.string.err_set_key))
                val b = okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + "/config/models").header("Authorization", "Bearer $key")
                if (models != null) b.post(models.toString().toRequestBody("application/json".toMediaType()))
                relayHttp.newCall(b.build()).execute().use { r ->
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
    private var lastCtxProf = ""
    /** Context numbers cached on disk: the first refresh after the app starts shows them at once and only asks about sessions that changed. */
    private fun primeCtx(prof: String) {
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
    private fun saveCtx(prof: String) = runCatching {
        val o = JSONObject()
        ctxStamp.forEach { (id, st) -> val c = sessionCtx[id]; o.put(id, JSONArray().put(c?.first ?: 0).put(c?.second ?: 0).put(st)) }
        store.setCtxCache(prof, o.toString())
    }
    private val ctxStamp = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private fun loadSessionCtx() = viewModelScope.launch {
        val all = sessions
        val ids = all.map { it.id }
        if (ids.isEmpty()) { sessionCtx = emptyMap(); return@launch }
        val g = runCatching { gateway() }.getOrNull() ?: return@launch
        val prof = profile
        val out = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
        out.putAll(sessionCtx.filterKeys { it in ids })
        val sem = Semaphore(4)
        val liveNow = runCatching { gatewayFor(prof).call("session.active_list", JSONObject()).optJSONArray("sessions") }.getOrNull()
            ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("session_key") }.toSet() }
        val busyNow = busyIds()
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
                        .onFailure { android.util.Log.w("HermesRpc", "ctx $id: $it") }.getOrNull()
                    pick(rr)
                    // The probe made this session live on the PC; close it again (unless someone really has it open), or it would show as active.
                    val lid = rr?.optString("session_id")?.ifBlank { null }
                    if (lid != null && liveNow != null && id !in liveNow && chats.none { it.id == id }) runCatching { g.call("session.close", JSONObject().put("session_id", lid)) }
                }
                ctxStamp[id] = x.lastActive ?: 0.0
                if (prof == profile) { sessionCtx = out.toMap(); out[id]?.let { (u, m) -> chats.find { it.profile == prof && it.id == id }?.let { c -> c.ctxUsed = u; c.ctxMax = m } } }
            }
        } }
        jobs.forEach { it.join() }
        if (prof == profile) saveCtx(prof)
    }

    /** PC resource samples (CPU/mem/GPU %), polled by the PC page while it is visible; keeps the last 300 points. */
    var pcStats by mutableStateOf<List<PcStat>>(emptyList())
    suspend fun fetchPcStats(): PcStat? = withContext(Dispatchers.IO) {
        val key = store.apiKey ?: return@withContext null
        runCatching {
            relayHttp.newCall(okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + "/pc/stats")
                .header("Authorization", "Bearer $key").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val j = JSONObject(r.body!!.string())
                    PcStat(j.optDoubleOrNull("cpu"), j.optDoubleOrNull("mem"), j.optDoubleOrNull("gpu"), j.optLong("ts", 0),
                        j.optJSONArray("gpus")?.let { a -> List(a.length()) { a.getJSONObject(it).let { g -> g.optString("name") to g.optDouble("util", 0.0) } } } ?: emptyList())
                }
        }.getOrNull()
    }
    fun addPcStat(s: PcStat) { pcStats = (pcStats + s).takeLast(300) }

    private suspend fun fsCall(op: String, path: String, keep: List<String> = emptyList()): JSONObject = withContext(Dispatchers.IO) {
        val key = store.apiKey ?: throw ApiException(s(R.string.err_set_key))
        val url = store.relayBase().trimEnd('/') + "/fs/" + op
        val body = JSONObject().put("path", path).put("keep", JSONArray(keep)).toString().toRequestBody("application/json".toMediaType())
        okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build()).execute().use { r ->
            val o = runCatching { JSONObject(r.body!!.string()) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw ApiException(o.optString("error").ifBlank { "HTTP ${r.code}" })
            o
        }
    }

    fun createProject(name: String, folder: String, mkdir: Boolean = false) = viewModelScope.launch {
        try {
            val f = folder.trim()
            if (mkdir) fsCall("mkdir", f)
            else {
                val st = runCatching { fsCall("stat", f) }.getOrNull()   // older relay without /fs: skip the check
                if (st != null && !st.optBoolean("exists")) { missingFolder = name to f; return@launch }
                if (st != null && !st.optBoolean("dir")) throw ApiException(s(R.string.not_a_folder, f))
            }
            gateway().call("projects.create", JSONObject().put("name", name.trim()).put("folders", JSONArray().put(f)).put("primary_path", f))
            refreshSessions()
        } catch (e: Exception) { fail(e) }
    }
    fun archiveProject(p: Project, restore: Boolean = false) = viewModelScope.launch {
        try { gateway().call("projects.archive", JSONObject().put("id", p.id).put("restore", restore)); refreshSessions() } catch (e: Exception) { fail(e) }
    }
    /** Deletes the project grouping; with [deleteFolder] also its folder on the PC (via the relay, with guards). */
    fun deleteProject(p: Project, deleteFolder: Boolean = false) = viewModelScope.launch {
        try {
            if (deleteFolder && p.path != null) {
                fsCall("rmdir", p.path, projects.filter { it.id != p.id && !it.isHome }.mapNotNull { it.path })
            }
            gateway().call("projects.delete", JSONObject().put("id", p.id)); refreshSessions()
        } catch (e: Exception) { fail(e); refreshSessions() }
    }
    fun setActiveProject(p: Project?) = viewModelScope.launch {
        runCatching { gateway().call("projects.set_active", if (p == null || p.isHome) JSONObject() else JSONObject().put("id", p.id)) }
        activeProject = p?.takeUnless { it.isHome }?.id
    }

    /** From a notification tap: open [id] in [prof] (switching profile if needed). */
    fun openFromNotification(prof: String?, id: String) {
        val p = prof ?: profile
        chats.find { it.profile == p && it.id == id }?.let { addTab(it); switchTo(it); return }
        if (p != profile) {
            profile = p; store.profile = p
            serverCommands = emptyList(); projects = emptyList(); loadMeta()
        }
        open(Session(id, null, null, null, 0, null)); refreshSessions()
    }

    fun open(s: Session) {
        val c = chatFor(profile, s.id, s.title)
        if (s.title != null) c.title = s.title
        c.projectId = projects.find { s.id in it.sessionIds }?.id ?: c.projectId
        cur = c; addTab(c); activePage = null; seen()
        if (c.ctxMax <= 0) sessionCtx[s.id]?.let { (u, m) -> c.ctxUsed = u; c.ctxMax = m }  // instant figure from the list, refined below
        viewModelScope.launch {
            // A chat that is still streaming keeps its live transcript; otherwise load the stored one.
            if (!c.streaming || !c.loaded) loadMessagesInto(c)
            // Subscribe right away so Hermes's questions, approvals and replies from a turn
            // started elsewhere (e.g. the desktop) show up live.
            runCatching { val g = gatewayFor(c.profile); ensureLive(g, c) }.onFailure { if (cur === c) fail(it) }
            loadChatCtx(c)
        }
    }

    /** Fill in stored row ids (needed for edit/branch) after a turn, keeping local notes. */
    private suspend fun refreshIds(c: Chat = cur) {
        val id = c.id ?: return
        val stored = runCatching { apiFor(c.profile).messages(id) }.getOrNull() ?: return
        val messages = c.messages
        var j = 0
        for (i in messages.indices) {
            val m = messages[i]; if (m.role == "system" || m.failed) continue
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
    fun newChat() {
        val project = newChatCwd?.let { cwd -> projects.find { it.path == cwd || it.homeCwd == cwd } } ?: (if (newChatNone) null else fabProject)
        val c = Chat(profile, null, null).initModel()
        c.newCwd = newChatCwd ?: project?.let { p -> p.path ?: p.homeCwd }
        c.projectId = project?.id
        cur = c; chats.add(c); addTab(c)
        newChatCwd = null; newChatNone = false; attachments.clear(); activePage = null
    }

    private fun apiFor(prof: String) = ApiClient(store.relayBase(), prof, store.apiKey ?: throw ApiException(s(R.string.err_set_key)))

    private suspend fun loadMessages(id: String) { if (cur.id == id) loadMessagesInto(cur) }

    /** Reload [c]'s stored transcript; keeps unsent (failed) messages at the end. */
    /** "MEDIA:<path>" in an agent reply: have the relay copy that PC file to the phone Inbox (once per session+path); it then shows in the chat. */
    private fun sendMedia(c: Chat, text: String, sid: String? = c.id) {
        if (!text.contains("MEDIA:")) return
        for (path in Internal.mediaPaths(text)) {
            val key = "${sid.orEmpty()}|$path"
            if (store.mediaDone(key)) continue
            store.markMedia(key)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { relayJson("POST", "/outbox/send", JSONObject().put("path", path).put("session", sid.orEmpty()).toString()) }
                    .onFailure { store.unmarkMedia(key) }
                    .onSuccess { if (it == null) store.unmarkMedia(key) }
            }
        }
    }

    private suspend fun loadMessagesInto(c: Chat) {
        val id = c.id ?: return
        try {
            val m = apiFor(c.profile).messages(id)
            m.filter { it.role == "assistant" }.forEach { sendMedia(c, it.content, id) }
            val failed = c.messages.filter { it.failed }
            val cards = c.messages.filter { it.task != null }   // sub-agent / background / process cards are not in the server history
            c.messages.clear(); c.messages.addAll(m.mapNotNull { r ->
                (Internal.clean(r.role, r.content) ?: if (r.steps.isNotEmpty()) "" else null)?.let { t -> val st = if (r.role == "assistant") store.replyStat(id, t) else null
                    UiMsg(r.role, t, id = r.id, steps = r.steps, ts = r.ts, tokIn = st?.get(0)?.toLong(), tokOut = st?.get(1)?.toLong(), prefillTps = st?.get(2), genTps = st?.get(3)) } })
            val files = store.sessionFiles(id)
            files.forEach { (n, u, t) -> placeByTime(c, UiMsg("file", n, file = u.ifBlank { null }, session = id, ts = if (t > 0) t else legacyFileTime(u))) }
            c.messages.addAll(failed); cards.forEach { placeCard(c, it) }; c.loaded = true
            if (c === cur) { offline = false; error = null }
        } catch (e: Exception) { if (c === cur) fail(e) }
    }

    fun rename(s: Session, title: String) = viewModelScope.launch {
        try { api().rename(s.id, title); refreshSessions() } catch (e: Exception) { fail(e) } }
    fun delete(s: Session) = viewModelScope.launch {
        try { api().delete(s.id); chats.removeAll { it.profile == profile && it.id == s.id }; tabs.removeAll { it.profile == profile && it.id == s.id }; if (currentId == s.id) newChat(); refreshSessions() } catch (e: Exception) { fail(e) } }

    // ---------- sending ----------
    fun send(text: String) {
        val t = text.trim()
        if (t.startsWith("/") && attachments.isEmpty()) { slash(t); return }
        if (streaming && t.isNotEmpty()) { steer(t); return }
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
            "sessions" -> { openPage("sessions") }
            "delete" -> {
                val id = currentId
                if (id == null) note(s(R.string.note_no_delete))
                else if (arg != "confirm") note(s(R.string.note_delete_confirm))
                else viewModelScope.launch { try { api().delete(id); newChat(); openPage("sessions") } catch (e: Exception) { fail(e) } }
            }
            "new" -> { newChat(); if (arg.isNotBlank()) sendMessage(arg, emptyList()) }
            "retry" -> { val l = lastSent; if (l == null) note(s(R.string.note_nothing_retry)) else {
                val cont = agentWorkedAfter(cur, cur.messages.indexOfLast { it.role == "user" })
                sendMessage(if (cont) s(R.string.continue_msg) else l.first, if (cont) emptyList() else l.second) } }
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
        val prof0 = profile
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
                if (!noop) Notify.alert(getApplication<Application>(), s(R.string.n_compress_done), detail.ifBlank { s(R.string.compressed) }, profile = prof0, session = currentId, kind = "compress")
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

    private fun sendMessage(text: String, atts: List<Attachment>, c: Chat = cur) {
        if (c.streaming || (text.isBlank() && atts.isEmpty())) return
        lastSent = text to atts
        val shown = (atts.map { if (it.isImage) "🖼️ ${it.name}" else "📎 ${it.name}" } + text).filter { it.isNotBlank() }.joinToString("\n")
        val msg = UiMsg("user", shown, resend = text)
        c.messages.add(msg)
        viewModelScope.launch {
            try {
                val g = gatewayFor(c.profile)
                try { submit(g, ensureLive(g, c), text, atts, c) }
                catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    // Stale live session (e.g. the gateway reaped it while the agent was busy): re-attach once and resend.
                    if (c.id == null) throw e
                    c.liveId = null; submit(g, ensureLive(g, c), text, atts, c)
                }
            }
            catch (e: Exception) {
                c.streaming = false; c.status = null
                val i = c.messages.indexOfFirst { it.uid == msg.uid }
                if (i >= 0) c.messages[i] = c.messages[i].copy(failed = true)
                if (c === cur) fail(e)
                Notify.alert(getApplication<Application>(), s(R.string.n_send_failed), text.take(120))
            }
        }
    }

    /** Red ! menu: send a failed message again / drop it. Edit = put its text back via [editFailed]. */
    /** True when the agent already did tool work after message [idx]: a retry then just says "continue" instead of repeating the request. */
    private fun agentWorkedAfter(c: Chat, idx: Int): Boolean = idx >= 0 && c.messages.drop(idx + 1).takeWhile { it.role != "user" }
        .any { it.role == "assistant" && (it.tools.isNotEmpty() || it.steps.any { st -> st.kind != "thinking" && st.kind != "text" }) }
    fun resendFailed(m: UiMsg) {
        val cont = agentWorkedAfter(cur, cur.messages.indexOfFirst { it.uid == m.uid })
        cur.messages.removeAll { it.uid == m.uid }
        sendMessage(if (cont) s(R.string.continue_msg) else m.resend ?: m.text, emptyList())
    }
    fun deleteFailed(m: UiMsg) { cur.messages.removeAll { it.uid == m.uid } }
    fun editFailed(m: UiMsg, newText: String) { cur.messages.removeAll { it.uid == m.uid }; if (newText.isNotBlank()) sendMessage(newText, emptyList()) }

    private suspend fun submit(g: GatewayWs, sid: String, text: String, atts: List<Attachment>, c: Chat = cur) {
        if (c === cur) error = null
        c.streaming = true; c.status = null
        val messages = c.messages
        messages.add(UiMsg("assistant", ""))
        try {
            for (a in atts.filter { it.isImage }) {
                g.call("image.attach_bytes", JSONObject().put("session_id", sid).put("filename", a.name)
                    .put("content_base64", a.payload.substringAfter("base64,")))
            }
            val files = atts.filter { !it.isImage }.joinToString("") {
                if (it.pcPath != null) "File `${it.name}` was uploaded to this PC at: ${it.pcPath}\n\n"
                else "File `${it.name}`:\n```\n${it.payload}\n```\n\n" }
            val p = JSONObject().put("session_id", sid).put("text", files + text).put("submission_id", UUID.randomUUID().toString())
            g.call("prompt.submit", p)
            c.createdHere = false
        } catch (e: Exception) { c.streaming = false; dropEmpty(c); throw e }
    }

    private fun dropEmpty(c: Chat = cur) {
        val messages = c.messages
        if (messages.lastOrNull()?.let { it.role == "assistant" && it.text.isEmpty() && it.steps.isEmpty() } == true) messages.removeAt(messages.lastIndex)
    }

    private fun updateLast(c: Chat, f: (UiMsg) -> UiMsg) {
        val messages = c.messages
        val i = messages.lastIndex
        if (i >= 0 && messages[i].role == "assistant") messages[i] = f(messages[i]) else messages.add(f(UiMsg("assistant", "")))
    }

    /** Route an event to the chat whose live session it belongs to (any profile, on screen or not). */
    private fun onEvent(prof: String, type: String, sid: String, p: JSONObject) {
        val c = if (sid.isEmpty()) (if (cur.profile == prof) cur else return)
            else chats.find { it.profile == prof && (it.liveId == sid || it.id == sid) } ?: return
        val app = getApplication<Application>()
        val away = !Notify.visible || c !== cur          // not looking at this chat
        val name = c.title ?: s(R.string.chat)
        if (type == "tool.complete" || type == "message.complete") kickProcPoll(c)   // a command may have started a background process
        when (type) {
            "session.usage" -> (p.optJSONObject("usage") ?: p).let { u -> applyUsage(c, u) }
            "session.info", "session.control.update", "session.updated" -> {
                if (p.has("execution_generation")) c.generation = p.optInt("execution_generation", c.generation)
                if (p.has("revision")) c.revision = p.optInt("revision", c.revision)
                if (type == "session.info") adoptServerModel(c, p)
            }
            "message.start" -> { c.streaming = true; c.status = null
                c.turnStartAt = android.os.SystemClock.elapsedRealtime(); c.firstTokenAt = 0L
                Notify.clear(app, c.id, "reply")       // a new turn (from the phone or the PC): the last reply was seen
                if (c.messages.lastOrNull()?.role != "assistant") c.messages.add(UiMsg("assistant", "")) }
            "message.delta" -> { c.status = null; val t = p.str("text")
                if (c.firstTokenAt == 0L && t.isNotEmpty()) c.firstTokenAt = android.os.SystemClock.elapsedRealtime()
                updateLast(c) { m ->
                    val closed = m.steps.map { s -> if (s.kind == "thinking" && s.output == null) s.copy(output = "") else s }
                    val steps = if (t.isEmpty()) closed
                        else if (closed.lastOrNull()?.kind == "text") closed.dropLast(1) + closed.last().copy(detail = closed.last().detail + t)
                        else closed + Step("text", "", t)
                    m.copy(text = m.text + t, steps = steps) } }
            "thinking.delta", "reasoning.delta" -> { c.status = s(R.string.thinking_status)
                if (c.firstTokenAt == 0L && p.str("text").isNotEmpty()) c.firstTokenAt = android.os.SystemClock.elapsedRealtime()
                val t = p.str("text"); if (t.isNotEmpty()) updateLast(c) { m ->
                    val last = m.steps.lastOrNull()
                    m.copy(steps = if (last?.kind == "thinking" && last.output == null) m.steps.dropLast(1) + last.copy(detail = last.detail + t) else m.steps + Step("thinking", "", t)) } }
            "tool.start" -> { val n = p.str("name").ifBlank { p.str("tool_name") }.ifBlank { "tool" }; c.status = "🔧 $n"
                val detail = p.str("args_text").ifBlank { Steps.argsText(n, p.optJSONObject("args")) }.ifBlank { p.str("context").ifBlank { p.str("preview") } }
                updateLast(c) { it.copy(tools = it.tools + n, steps = it.steps.map { s -> if (s.kind == "thinking" && s.output == null) s.copy(output = "") else s } +
                    Step("tool", n, detail, null, p.str("tool_id"))) } }
            "tool.complete" -> { val tid = p.str("tool_id"); val n = p.str("name")
                val raw = p.str("result_text").ifBlank { p.str("summary") }.ifBlank { p.opt("result")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty() }
                updateLast(c) { m -> m.copy(steps = m.steps.map { s -> if (s.kind == "tool" && (s.toolId == tid || (tid.isEmpty() && s.output == null && s.name == n))) s.copy(output = Steps.outputText(s.name, raw)) else s }) } }
            "message.complete" -> {
                val rawText = p.str("text").ifBlank { p.str("content") }
                sendMedia(c, rawText)
                val text = Internal.stripMedia(rawText)
                if (rawText.isNotBlank()) updateLast(c) { m -> m.copy(text = text,
                    steps = if (text.isBlank() || m.steps.any { it.kind == "text" }) m.steps else m.steps + Step("text", "", text)) }
                (p.optJSONObject("usage")?.takeIf { it.length() > 0 }).let { u -> if (u == null) return@let
                    // No baseline yet (first reply of a fresh chat): count from zero. Counters that went down (compression / new
                    // counters) restart from the new value instead of dropping the stats.
                    val fresh = c.totIn == null && c.messages.count { it.role == "assistant" } <= 1
                    val i0 = c.totIn ?: if (fresh) 0L else null; val o0 = c.totOut ?: if (fresh) 0L else null
                    applyUsage(c, u)?.let { (i, o) ->
                        if (i0 != null && o0 != null && (i != i0 || o != o0)) {
                            val di = if (i >= i0) i - i0 else i; val dO = if (o >= o0) o - o0 else o
                            val end = android.os.SystemClock.elapsedRealtime()
                            val ttft = if (c.firstTokenAt > 0 && c.turnStartAt > 0) (c.firstTokenAt - c.turnStartAt) / 1000f else 0f
                            val gen = if (c.firstTokenAt > 0) (end - c.firstTokenAt) / 1000f else 0f
                            val pre = if (ttft >= 0.05f && di > 0) di / ttft else null
                            val gt = if (gen >= 0.2f && dO > 0) dO / gen else null
                            updateLast(c) { it.copy(tokIn = di, tokOut = dO, prefillTps = pre, genTps = gt) }
                            c.id?.let { sid -> c.messages.lastOrNull { it.role == "assistant" }?.let { store.setReplyStat(sid, it.text, listOf(di.toFloat(), dO.toFloat(), pre, gt)) } }
                        }
                        c.totIn = i; c.totOut = o } }
                if (away && text.isNotBlank()) Notify.alert(app, s(R.string.n_reply_done, name), text, force = c !== cur, profile = c.profile, session = c.id, kind = "reply")
                c.streaming = false; c.status = null; c.dropped = false; dropEmpty(c)
                if (c !== cur || screen != Screen.Chat) c.unread = true
                viewModelScope.launch { refreshIds(c) }
                if (p.optString("outcome") !in setOf("", "completed") && c === cur) error = "Turn ended: ${p.optString("outcome")}"
                if (c.title == null) refreshSessions()
                if (p.optString("outcome") in setOf("", "completed")) drainQueue(c)   // stopped: keep the queue
            }
            "approval.request" -> {
                val ch = p.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("once", "deny")
                c.approval = Approval(p.optString("prompt_id"), p.optString("command"), p.optString("description"), ch,
                    p.optInt("execution_generation", c.generation))
                if (away) Notify.alert(app, s(R.string.n_approval) + " · " + name, p.optString("command"), force = c !== cur, profile = c.profile, session = c.id, kind = "approval")
            }
            "clarify.request" -> {
                val ch = p.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                c.clarify = Clarify(p.optString("prompt_id"), listOf(ClarifyQ("q", p.optString("question"), ch, p.optBoolean("multi_select") && ch.isNotEmpty())),
                    p.optInt("execution_generation", c.generation))
                if (away) Notify.alert(app, s(R.string.n_clarify) + " · " + name, p.optString("question"), force = c !== cur, profile = c.profile, session = c.id, kind = "clarify")
            }
            // Answered here or on the PC: the question's notification is stale either way.
            "approval.settled" -> { if (c.approval?.reqId == p.optString("prompt_id")) c.approval = null; Notify.clear(app, c.id, "approval") }
            "clarify.settled" -> { if (c.clarify?.reqId == p.optString("prompt_id")) c.clarify = null; Notify.clear(app, c.id, "clarify") }
            "subagent.spawn_requested", "subagent.start", "subagent.thinking", "subagent.text", "subagent.tool",
            "subagent.progress", "subagent.complete", "background.complete" -> taskEvent(c, type, p)
            "session.title" -> p.optString("title").ifBlank { null }?.let { c.title = it }
            "error" -> { val msg = p.optString("message").ifBlank { "Hermes error" }
                c.streaming = false; c.status = null; dropEmpty(c); c.messages.add(UiMsg("system", "⚠ $msg"))
                if (c === cur) error = msg
                if (away) Notify.alert(app, s(R.string.n_error) + " · " + name, msg, force = c !== cur, profile = c.profile, session = c.id) }
        }
    }

    /** Read-only transcripts, keyed by parent chat + task key. */
    private val taskChats = HashMap<String, Chat>()

    private fun roAppend(ro: Chat, f: (UiMsg) -> UiMsg) {
        val i = ro.messages.lastIndex
        if (i >= 0 && ro.messages[i].role == "assistant") ro.messages[i] = f(ro.messages[i]) else ro.messages.add(f(UiMsg("assistant", "")))
    }

    /** subagent.* / background.complete on the parent session: keep a card in the stream and a read-only transcript behind it. */
    private fun taskEvent(c: Chat, type: String, p: JSONObject) {
        val bg = type == "background.complete"
        val key = if (bg) "bg:" + p.str("task_id") else "sa:" + p.str("subagent_id").ifBlank { p.str("child_session_id") }.ifBlank { p.optInt("task_index", 0).toString() }
        val now = System.currentTimeMillis()
        val ro = taskChats.getOrPut(System.identityHashCode(c).toString() + key) {
            Chat(c.profile, null, null).also { it.readOnly = true; it.loaded = true
                it.title = s(if (bg) R.string.task_background else R.string.task_subagent) } }
        val goal = p.str("goal").ifBlank { p.str("preview") }
        var status = c.messages.firstOrNull { it.task?.key == key && it.task.chat === ro }?.task?.status ?: "running"
        var started = c.messages.firstOrNull { it.task?.key == key && it.task.chat === ro }?.task?.startedAt ?: now
        var ended = 0L
        when (type) {
            "subagent.spawn_requested", "subagent.start" -> {
                if (goal.isNotBlank() && ro.messages.none { it.role == "system" }) {
                    ro.messages.add(0, UiMsg("system", goal)); ro.title = goal.replace('\n', ' ').take(40) }
                ro.streaming = true; status = "running" }
            "subagent.thinking" -> p.str("text").takeIf { it.isNotEmpty() }?.let { t -> roAppend(ro) { m ->
                val l = m.steps.lastOrNull()
                m.copy(steps = if (l?.kind == "thinking") m.steps.dropLast(1) + l.copy(detail = l.detail + t) else m.steps + Step("thinking", "", t)) } }
            "subagent.text", "subagent.progress" -> p.str("text").ifBlank { goal }.takeIf { it.isNotEmpty() }?.let { t -> roAppend(ro) { m ->
                m.copy(text = m.text + t + "\n", steps = m.steps + Step("text", "", t)) } }
            "subagent.tool" -> { val n = p.str("tool_name").ifBlank { "tool" }
                roAppend(ro) { m -> m.copy(tools = m.tools + n, steps = m.steps + Step("tool", n, p.str("tool_preview").ifBlank { p.str("text") }, "")) } }
            "subagent.complete", "background.complete" -> {
                val body = if (bg) p.str("text") else p.str("summary").ifBlank { p.str("text") }.ifBlank { goal }
                val st = p.str("status")
                status = if ((bg && body.startsWith("error:")) || p.has("failure_reason") || st in setOf("failed", "error", "timeout", "timed_out", "interrupted")) "failed" else "done"
                val dur = p.optDouble("duration_seconds", 0.0)
                if (dur > 0) started = now - (dur * 1000).toLong()
                ended = now
                if (bg && ro.messages.none { it.role == "system" }) ro.messages.add(0, UiMsg("system", p.str("task_id")))
                if (body.isNotBlank()) roAppend(ro) { m -> m.copy(text = body, steps = m.steps + Step("text", "", body)) }
                ro.streaming = false
                if (ro !== cur) ro.unread = true }
        }
        val card = UiMsg("task", ro.title ?: "", uid = "task:" + System.identityHashCode(ro), task = TaskInfo(key, if (bg) "bg" else "sa", status, started, ended, ro))
        val i = c.messages.indexOfFirst { it.uid == card.uid }
        if (i >= 0) c.messages[i] = card
        else placeCard(c, card)   // at its start time; keeps the streaming assistant message last
    }

    // ---- background terminal processes: progress cards -------------------------------------------------------------
    // The gateway lists a session's processes (RPC process.list); the relay (POST /bgtasks) parses progress from their output.
    // Polled every 3s only while the app is in the foreground, the chat is the open one and a process is still running.
    private val procJobs = HashMap<Chat, kotlinx.coroutines.Job>()

    fun kickProcPoll(c: Chat = cur) {
        if (c.readOnly || c.liveId == null || !store.hasKey || procJobs[c]?.isActive == true) return
        procJobs[c] = viewModelScope.launch {
            while (Notify.visible && c === cur) {
                val sid = c.liveId ?: break
                if (!runCatching { pollProcs(c, sid) }.getOrDefault(false) && !c.streaming) break   // keep looking while a turn runs: the process may not exist yet
                delay(3000)
            }
        }
    }

    /** One poll; true while any process of the session is still running. */
    private suspend fun pollProcs(c: Chat, sid: String): Boolean {
        val r = withTimeout(8000) { gatewayFor(c.profile).call("process.list", JSONObject().put("session_id", sid)) }
        val arr = r.optJSONArray("processes") ?: return false
        val rows = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { row ->
            row.str("status") == "running" || c.messages.any { it.task?.key == "proc:" + row.str("session_id") } }
        if (rows.isEmpty()) return false
        val prog = fetchBgProgress(rows)
        rows.forEach { applyProc(c, it, prog[it.str("session_id")]) }
        return rows.any { it.str("status") == "running" }
    }

    private suspend fun fetchBgProgress(rows: List<JSONObject>): Map<String, JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("processes", JSONArray().also { a -> rows.forEach { a.put(it) } })
            relayHttp.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build().newCall(relayRequest("/bgtasks", body)).execute().use { r ->
                if (!r.isSuccessful) return@use emptyMap<String, JSONObject>()
                val a = JSONObject(r.body!!.string()).optJSONArray("tasks") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.str("id") }
            }
        }.getOrDefault(emptyMap())
    }

    private fun applyProc(c: Chat, row: JSONObject, pr: JSONObject?) {
        val pid = row.str("session_id"); val key = "proc:$pid"
        val now = System.currentTimeMillis()
        val cmd = row.str("command").replace('\n', ' ').trim()
        val ro = taskChats.getOrPut(System.identityHashCode(c).toString() + key) {
            Chat(c.profile, null, null).also { it.readOnly = true; it.loaded = true; it.title = cmd.take(40).ifBlank { pid } } }
        val old = c.messages.firstOrNull { it.task?.key == key && it.task.chat === ro }?.task
        val running = row.str("status") == "running"
        val failed = !running && !row.isNull("exit_code") && row.optInt("exit_code", 0) != 0
        val status = if (running) "running" else if (failed) "failed" else "done"
        val started = old?.startedAt ?: (now - row.optLong("uptime_seconds", 0) * 1000)
        val ended = if (running) 0L else old?.endedAt?.takeIf { it > 0 } ?: now
        val tail = pr?.optJSONArray("tail")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: row.str("output_tail").split(Regex("[\r\n]+")).filter { it.isNotBlank() }.takeLast(30)
        val pct = pr?.optDoubleOrNull("percent")
        val last = pr?.str("detail").orEmpty().ifBlank { pr?.str("last").orEmpty() }.ifBlank { tail.lastOrNull().orEmpty() }.take(160)
        if (ro.messages.none { it.role == "system" }) ro.messages.add(0, UiMsg("system", cmd.ifBlank { pid }))
        val text = "```\n" + tail.joinToString("\n") + "\n```"
        if (tail.isNotEmpty() && ro.messages.lastOrNull()?.takeIf { it.role == "assistant" }?.text != text) {
            if (ro.messages.lastOrNull()?.role == "assistant") ro.messages[ro.messages.lastIndex] = ro.messages.last().copy(text = text)
            else ro.messages.add(UiMsg("assistant", text)) }
        ro.streaming = running
        val card = UiMsg("task", ro.title ?: "", uid = "task:" + System.identityHashCode(ro), task = TaskInfo(key, "proc", status, started, ended, ro, pct, last))
        val i = c.messages.indexOfFirst { it.uid == card.uid }
        if (i >= 0) { if (c.messages[i].task != card.task) c.messages[i] = card }
        else placeCard(c, card)
    }

    /** Put a task card where it started: before the first message sent after its start (not always at the bottom, e.g. after a restart). */
    /** Insert [m] before the first user/assistant message that is newer than it (files without a known time go last). */
    /** Files saved before arrival times were recorded: use the saved file's own modified/added time (MAX = unknown, goes last). */
    private fun legacyFileTime(uri: String): Long = runCatching {
        if (uri.isBlank()) return Long.MAX_VALUE
        val u = android.net.Uri.parse(uri)
        getApplication<Application>().contentResolver.query(u, null, null, null, null)?.use { q ->
            if (!q.moveToFirst()) return@use null
            val ms = q.getColumnIndex("last_modified").takeIf { it >= 0 }?.let { q.getLong(it) }?.takeIf { it > 0 }
            val sec = listOf("date_modified", "date_added").map { q.getColumnIndex(it) }.firstOrNull { it >= 0 }?.let { q.getLong(it) * 1000 }?.takeIf { it > 0 }
            ms ?: sec
        }
    }.getOrNull() ?: Long.MAX_VALUE

    private fun placeByTime(c: Chat, m: UiMsg) {
        val i = c.messages.indexOfFirst { (it.role == "user" || it.role == "assistant" || it.role == "file") && it.ts > m.ts }
        if (i >= 0) c.messages.add(i, m) else c.messages.add(m)
    }

    private fun placeCard(c: Chat, card: UiMsg) {
        val started = card.task?.startedAt ?: 0L
        val i = if (started > 0) c.messages.indexOfFirst { it.task == null && it.ts > started && (it.role == "user" || it.role == "assistant") } else -1
        if (i >= 0) { c.messages.add(i, card); return }
        val l = c.messages.lastIndex
        if (l >= 0 && c.messages[l].role == "assistant" && c.streaming) c.messages.add(l, card) else c.messages.add(card)
    }

    /** Open a task card's read-only transcript as a tab. */
    fun openTask(t: TaskInfo) { addTab(t.chat); switchTo(t.chat) }

    private fun onServerRequest(g: GatewayWs, id: String, method: String, p: JSONObject) {
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
            else -> g.respond(id, null, "not supported on the phone")
        }
    }

    fun answerApproval(choice: String) {
        val c = cur; val a = c.approval ?: return; c.approval = null; Notify.clear(getApplication(), c.id, "approval")
        if (a.gen == null) { gw?.respond(a.reqId, JSONObject().put("choice", choice)); return }
        viewModelScope.launch { runCatching { gatewayFor(c.profile).call("approval.respond", JSONObject().put("session_id", c.liveId)
            .put("execution_generation", a.gen).put("prompt_id", a.reqId).put("choice", choice)) }.onFailure { fail(it) } }
    }
    fun answerClarify(answers: Map<String, String>?) {
        val chat = cur; val c = chat.clarify ?: return; chat.clarify = null; Notify.clear(getApplication(), chat.id, "clarify")
        if (c.gen != null) {
            viewModelScope.launch { runCatching { gatewayFor(chat.profile).call("clarify.respond", JSONObject().put("session_id", chat.liveId)
                .put("execution_generation", c.gen).put("prompt_id", c.reqId).put("answer", answers?.values?.firstOrNull().orEmpty())) }
                .onFailure { fail(it) } }
            return
        }
        gw?.respond(c.reqId, if (answers == null) JSONObject().put("outcome", "cancelled")
            else JSONObject().put("answers", JSONObject(answers as Map<*, *>)).put("outcome", "submitted"))
    }

    /** Inject text into the running turn (Hermes sees it with the next tool result) without interrupting. */
    private fun steer(t: String) {
        val c = cur
        // Mid-turn note: appears where it was sent (after what the agent has said so far); later output continues in a new reply below it.
        c.messages.add(UiMsg("user", t))
        c.messages.add(UiMsg("assistant", ""))
        viewModelScope.launch {
            var generation by object { operator fun getValue(a: Any?, b: Any?) = c.generation; operator fun setValue(a: Any?, b: Any?, v: Int) { c.generation = v } }
            try {
                val g = gatewayFor(c.profile)
                val sid = c.liveId ?: ensureLive(g, c)
                var r = runCatching { g.call("session.steer", JSONObject().put("session_id", sid).put("text", t).put("execution_generation", generation)) }
                if (r.isFailure) {  // generation moved on: refresh once and retry
                    val info = g.call("session.info", JSONObject().put("session_id", sid))
                    generation = info.optInt("execution_generation", generation)
                    r = runCatching { g.call("session.steer", JSONObject().put("session_id", sid).put("text", t).put("execution_generation", generation)) }
                }
                val res = r.getOrThrow()
                if (res.optString("status") == "rejected") throw ApiException("steer_rejected")
                c.status = s(R.string.steered)
            } catch (e: Exception) {
                // Steer refused (generation moved on / turn ending): drop the optimistic bubbles and send it as a normal message,
                // or queue it behind the running turn, instead of leaving a dead message and an error.
                val i = c.messages.indexOfLast { it.role == "user" && it.text == t && it.id == null }
                if (i >= 0) { c.messages.removeAt(i); if (c.messages.getOrNull(i)?.let { it.role == "assistant" && it.text.isEmpty() && it.steps.isEmpty() } == true) c.messages.removeAt(i) }
                if (!c.streaming) sendMessage(t, emptyList(), c) else { c.queue.add(t); c.status = s(R.string.steered) }
            }
        }
    }

    fun stop() {
        val c = cur
        val sid = c.liveId ?: return
        viewModelScope.launch {
            var generation by object { operator fun getValue(a: Any?, b: Any?) = c.generation; operator fun setValue(a: Any?, b: Any?, v: Int) { c.generation = v } }
            val g = runCatching { gatewayFor(c.profile) }.getOrElse { fail(it); return@launch }
            val r = runCatching { g.call("session.interrupt", JSONObject().put("session_id", sid).put("execution_generation", generation)) }
            if (r.exceptionOrNull()?.message == "stale_generation") {
                // Refresh the fence from a snapshot and retry once.
                runCatching {
                    generation = g.call("session.resume", JSONObject().put("session_id", sid).put("omit_messages", true)).optInt("execution_generation", generation)
                    g.call("session.interrupt", JSONObject().put("session_id", sid).put("execution_generation", generation))
                }.onFailure { fail(it) }
            } else r.onFailure { fail(it) }
            c.status = s(R.string.stopping)
        }
    }

    // ---------- attachments ----------
    /** Progress notice while a big file streams to the PC ("Uploading large file... 42%"); null when idle. */
    var uploadStatus by mutableStateOf<String?>(null)
    private var uploadsActive = 0

    fun addAttachments(uris: List<Uri>) = viewModelScope.launch {
        if (!hotfix.attachments) { error = s(R.string.attachments_disabled); return@launch }
        for (u in uris) {
            try { attachments.add(readAttachment(u)) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: e.toString() }
        }
    }

    /** Images -> JPEG (existing path); everything else (text included) -> uploaded to the PC inbox via the relay. */
    private suspend fun readAttachment(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val cr = getApplication<Application>().contentResolver
        var name = "file"; var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) { it.getString(0)?.let { n -> name = n }; if (!it.isNull(1)) size = it.getLong(1) } }
        val mime = cr.getType(uri).orEmpty()
        if (mime.startsWith("image/")) {
            try {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
                    val s = info.size; val m = maxOf(s.width, s.height)
                    if (m > 1600) dec.setTargetSize(s.width * 1600 / m, s.height * 1600 / m)
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                return@withContext Attachment(name.substringBeforeLast('.') + ".jpg", true, "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            } catch (e: Exception) { /* not decodable as a picture: send the file itself */ }
        }
        uploadToPc(uri, name, size)
    }

    private fun setUploadStatus(name: String, pct: Int) { uploadStatus = s(R.string.uploading_large, name, pct) }

    /** Chunked, parallel (4 connections x 4 MB) upload through the relay: /upload/init, PUT /upload/<id>?offset=N, /upload/<id>/done. */
    private suspend fun uploadToPc(uri: Uri, name: String, size0: Long): Attachment = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val key = store.apiKey ?: throw ApiException(s(R.string.err_set_key))
        val base = store.relayBase().trimEnd('/')
        val http = okhttp3.OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
        var tmp: java.io.File? = null
        uploadsActive++; setUploadStatus(name, 0)
        try {
            var pfd = app.contentResolver.openFileDescriptor(uri, "r")
            val seekable = pfd != null && size0 >= 0 && runCatching { java.io.FileInputStream(pfd!!.fileDescriptor).channel.position(0) }.isSuccess
            var size = size0
            if (!seekable) {   // provider gave a pipe / no size: spool to cache so chunks can be read at any offset
                pfd?.close()
                val f = java.io.File(app.cacheDir, "upload-" + UUID.randomUUID()); tmp = f
                app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                size = f.length(); pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            }
            val ch = java.io.FileInputStream(pfd!!.fileDescriptor).channel
            val json = "application/json".toMediaType()
            fun call(rb: okhttp3.Request.Builder): String = http.newCall(rb.header("Authorization", "Bearer $key").build()).execute().use { r ->
                if (r.code == 404) throw ApiException(s(R.string.upload_relay_old))
                if (!r.isSuccessful) throw ApiException("upload: HTTP ${r.code} ${r.body?.string().orEmpty().take(200)}")
                r.body?.string().orEmpty() }
            val init = JSONObject(call(okhttp3.Request.Builder().url("$base/upload/init").post(JSONObject().put("name", name).put("size", size).toString().toRequestBody(json))))
            val id = init.getString("id"); val chunk = init.optLong("chunk", 4L shl 20).coerceAtLeast(1L shl 20)
            val sent = java.util.concurrent.atomic.AtomicLong(0)
            val sem = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                (0 until size step chunk).map { off ->
                    async {
                        sem.withPermit {
                            val len = minOf(chunk, size - off).toInt()
                            val buf = java.nio.ByteBuffer.allocate(len)
                            var pos = off
                            while (buf.hasRemaining()) { val n = ch.read(buf, pos); if (n < 0) break; pos += n }
                            val bytes = buf.array()
                            var attempt = 0
                            while (true) {
                                try { call(okhttp3.Request.Builder().url("$base/upload/$id?offset=$off").put(bytes.toRequestBody("application/octet-stream".toMediaType()))); break }
                                catch (e: ApiException) { throw e }
                                catch (e: Exception) { if (++attempt >= 4) throw e; kotlinx.coroutines.delay(500L * attempt) }
                            }
                            setUploadStatus(name, (sent.addAndGet(len.toLong()) * 100 / maxOf(size, 1)).toInt())
                        }
                    }
                }.awaitAll()
            }
            pfd.close()
            val path = JSONObject(call(okhttp3.Request.Builder().url("$base/upload/$id/done").post("{}".toRequestBody(json)))).getString("path")
            Attachment(name, false, "", path)
        } finally {
            tmp?.delete()
            if (--uploadsActive <= 0) { uploadsActive = 0; uploadStatus = null }
        }
    }

    fun saveSettings(relay: String, lan: String, key: String?) {
        store.relayUrl = relay; store.lanUrl = lan; store.activeRelay = null; if (!key.isNullOrBlank()) store.apiKey = key
        gws.values.forEach { it.close() }; gws.clear(); chats.forEach { it.liveId = null }
        viewModelScope.launch {
            try {
                pickRelay(); api().health(); api().sessions()
                gateway(); status = s(R.string.connected_relay)
                error = null; offline = false; openPage("sessions"); loadMeta()
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

/** optDouble that returns null for a missing / null / non-numeric field (optDouble yields NaN otherwise). */
private fun JSONObject.optDoubleOrNull(k: String): Double? =
    if (!has(k) || isNull(k)) null else optDouble(k).takeIf { it.isFinite() }


/** Hermes stores gateway/system notices (background-process results, compaction notes, ...) as "user"
 *  rows. Hide those in the phone UI; unwrap real out-of-band user messages. */
object Internal {
    private val HIDE = listOf("[IMPORTANT: Background process", "[IMPORTANT: Background", "[INTERNAL NOTIFICATION",
        "[System note:", "[CONTEXT COMPACTION", "[PRIOR CONTEXT", "[Context from the interrupted", "[SYSTEM:")
    private val OOB = Regex("""\[OUT-OF-BAND USER MESSAGE[^\]]*\]\s*([\s\S]*?)\s*\[/OUT-OF-BAND USER MESSAGE\]""")
    /** Hermes' own file syntax: a line "MEDIA:<pc path>". The phone shows it as an Inbox file instead of raw text. */
    private val MEDIA = Regex("""(?m)^[ \t]*MEDIA:[ \t]*(.+?)[ \t]*$""")
    fun mediaPaths(text: String): List<String> = MEDIA.findAll(text).map { it.groupValues[1].trim().trim('"', '\'', '`') }.filter { it.isNotBlank() }.distinct().toList()
    fun stripMedia(text: String): String = if (text.contains("MEDIA:")) MEDIA.replace(text, "").replace(Regex("\n{3,}"), "\n\n").trim() else text
    fun clean(role: String, text: String): String? {
        if (role == "tool") return null
        if (role != "user") return stripMedia(text).takeUnless { it.trim() == "[SILENT]" || (it.isBlank() && text.isNotBlank()) }
        val t = text.trimStart()
        OOB.find(t)?.let { return it.groupValues[1].trim().ifBlank { null } }
        if (HIDE.any { t.startsWith(it) } || t.contains("[INTERNAL NOTIFICATION")) return null
        return text
    }
}
