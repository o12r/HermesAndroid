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

/**
 * Everything goes through the PC relay: lists/history/rename/delete via the gateway's native HTTP API;
 * chat turns, streaming, approvals, models and real slash commands via the desktop protocol (so desktop-created sessions work too).
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val store = SecureStore(app)
    /** What is on screen is the active tab: a page tab (sessions / pc / settings) or, when [activePage] is null, the chat [cur]. */
    val screen: Screen get() = when (activePage?.page) { null -> Screen.Chat; "sessions" -> Screen.Sessions; "pc" -> Screen.Screens; "files" -> Screen.Files; else -> Screen.Settings }
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
        }.onFailure { platform.log("HermesRpc", "profiles.list: $it") }
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
            it.title = s(when (kind) { "pc" -> Str.pc; "files" -> Str.pf_title; "sessions" -> Str.sessions; else -> Str.settings }); tabs.add(it) }
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
    fun seen() { if (screen == Screen.Chat && platform.visible) platform.clearAlerts(cur.id) }

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
    private val platform: Platform = object : Platform {
        override val visible get() = Notify.visible
        override fun clearAlerts(session: String?, vararg kinds: String) = Notify.clear(getApplication(), session, *kinds)
        override fun elapsedMs() = android.os.SystemClock.elapsedRealtime()
        override fun log(tag: String, msg: String) { android.util.Log.w(tag, msg) }
    }
    private val engine: ChatEngine by lazy { ChatEngine(object : EngineHost {
        override val chats get() = this@ChatViewModel.chats
        override val cur get() = this@ChatViewModel.cur
        override val screen get() = this@ChatViewModel.screen
        override var error: String? get() = this@ChatViewModel.error; set(v) { this@ChatViewModel.error = v }
        override val platform get() = this@ChatViewModel.platform
        override val relay get() = this@ChatViewModel.relay
        override val hasKey get() = store.hasKey
        override fun s(key: String, vararg a: Any) = this@ChatViewModel.s(key, *a)
        override fun alert(title: String, text: String, force: Boolean, profile: String?, session: String?, kind: String) =
            Notify.alert(getApplication<Application>(), title, text, force = force, profile = profile, session = session, kind = kind)
        override suspend fun refreshIds(c: Chat) = this@ChatViewModel.refreshIds(c)
        override fun refreshSessions() { this@ChatViewModel.refreshSessions() }
        override fun drainQueue(c: Chat) = this@ChatViewModel.drainQueue(c)
        override fun sendMedia(c: Chat, rawText: String) = this@ChatViewModel.sendMedia(c, rawText)
        override fun adoptServerModel(c: Chat, info: JSONObject) = this@ChatViewModel.adoptServerModel(c, info)
        override suspend fun gateway(profile: String) = gatewayFor(profile)
        override suspend fun ensureLive(g: GatewayWs, c: Chat) = this@ChatViewModel.ensureLive(g, c)
        override fun fail(e: Throwable) = this@ChatViewModel.fail(e)
        override fun noteSent(text: String, atts: List<Attachment>) { lastSent = text to atts }
        override fun respond(reqId: String, result: JSONObject) { gw?.respond(reqId, result) }
        override fun saveReplyStat(sid: String, text: String, v: List<Float?>) { store.setReplyStat(sid, text, v); pushReplyStats(sid) }
        override suspend fun gatewayCall(profile: String, method: String, params: JSONObject) = gatewayFor(profile).call(method, params)
    }, viewModelScope) }
    private val res get() = L10n.wrap(getApplication(), store.lang)
    fun s(id: Int, vararg a: Any): String = res.getString(id, *a)
    /** String by resource name (see [Str]); resolved against the user's chosen app language. */
    fun s(key: String, vararg a: Any): String = res.getString(res.resources.getIdentifier(key, "string", res.packageName), *a)
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

    private fun api() = ApiClient(store.relayBase(), profile, store.apiKey ?: throw ApiException(s(Str.err_set_key)))

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch { if (store.hasKey) { delay(800); pickRelay() }; if (offline) reconnect() } }
        override fun onLost(network: Network) { viewModelScope.launch { status = s(Str.network_changed) } }
    }
    init {
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb)
        viewModelScope.launch { inboxWatch() }
        if (store.hasKey) viewModelScope.launch { pickRelay(); refreshSessions(); loadMeta() }
        checkUpdates(manual = false)
    }

    var clock by mutableStateOf(store.clock.also { Formats.clock = it; Formats.is24h = { android.text.format.DateFormat.is24HourFormat(app.applicationContext) } })
    fun chooseClock(v: String) { clock = v; store.clock = v; Formats.clock = v }
    fun chooseTheme(t: String) { theme = t; store.theme = t }
    var powerMode by mutableStateOf(store.powerMode.also { GatewayWs.pingSec = if (KeepAliveService.isSaver(getApplication())) 60L else 30L })
    fun choosePower(m: String) {
        powerMode = m; store.powerMode = m; GatewayWs.pingSec = if (KeepAliveService.isSaver(getApplication())) 60L else 30L
        KeepAliveService.start(getApplication())      // re-applies the wake lock policy
    }

    /** Files the agent sent to the phone: download progress (0-100) per file name, shown on its chat bubble. */
    val fileProgress = mutableStateMapOf<String, Int>()
    suspend fun saveInboxNow(name: String, session: String?, anchor: Long = 0L): android.net.Uri {
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
        val sid = outboxSession(session)
        if (!found) (chats.firstOrNull { it.id == sid } ?: cur.takeIf { sid == null || it.id == sid })
            ?.let { ch -> engine.placeByTime(ch, UiMsg("file", name, file = u, session = sid, ts = if (anchor > 0) anchor + 1 else System.currentTimeMillis())) }
        sid?.let { store.upsertSessionFile(it, name, u, if (anchor > 0) anchor + 1 else 0L) }
        return uri
    }

    fun saveInbox(name: String, session: String?) = viewModelScope.launch {
        if (name in fileProgress) return@launch
        try { saveInboxNow(name, session) } catch (e: Exception) { fail(e) }
    }

    /** Chat a PC-sent file belongs to: its own session (kept in the store and shown when that chat is open), else the open chat. */
    private fun outboxSession(s: String?): String? = s?.takeIf { it.isNotBlank() } ?: cur.id

    private fun showPendingFile(item: Inbox.Item) {
        val sid = outboxSession(item.session)
        (chats.firstOrNull { it.id == sid } ?: cur.takeIf { sid == null || it.id == sid })
            ?.let { ch -> engine.placeByTime(ch, UiMsg("file", item.name, file = null, session = sid, ts = if (item.anchor > 0) item.anchor + 1 else System.currentTimeMillis())) }
        sid?.let { store.upsertSessionFile(it, item.name, "", if (item.anchor > 0) item.anchor + 1 else 0L) }
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
                        if (isImageName(item.name)) saveInboxNow(item.name, item.session, item.anchor) else showPendingFile(item)
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
        if (manual) updateStatus = s(Str.checking)
        val u = runCatching { updates.check(updateSource) }.getOrElse { if (manual) updateStatus = s(Str.update_failed, it.message ?: "?"); return@launch }
        store.lastUpdateCheck = System.currentTimeMillis()
        update = u
        if (manual) updateStatus = if (u == null) s(Str.up_to_date) else null
    }

    var updateFileBytes by mutableStateOf(updates.downloadedSize())
    private var installJob: kotlinx.coroutines.Job? = null
    fun installUpdate() {
        val u = update ?: return
        installJob = viewModelScope.launch {
            updateProgress = 0; updateStatus = null
            runCatching { updates.install(u) { p -> viewModelScope.launch(Dispatchers.Main) { updateProgress = p } } }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) updateStatus = s(Str.update_failed, it.message ?: "?") }
            updateProgress = null; updateFileBytes = updates.downloadedSize()
        }
    }
    /** Stop the running download; the partial file is kept so a later install resumes it. */
    fun cancelUpdate() {
        installJob?.cancel(); installJob = null
        updateProgress = null; updateStatus = s(Str.update_cancelled)
        viewModelScope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(300); withContext(Dispatchers.Main) { updateFileBytes = updates.downloadedSize() } }
    }
    fun deleteUpdateFile() {
        installJob?.cancel(); installJob = null; updateProgress = null
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300); updates.deleteFiles()
            withContext(Dispatchers.Main) { updateFileBytes = updates.downloadedSize(); updateStatus = s(Str.update_file_deleted) }
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
        error = if (m.contains("storage_unavailable")) s(Str.err_session_corrupt) + " (storage_unavailable)" else m
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
                    notifiedDrop = true; Notify.alert(getApplication<Application>(), s(Str.n_disconnected), s(Str.n_disconnected_text))
                }
                // In the background: 3 failed tries and we stop (no endless wake-ups); the foreground reconnects.
                if (!platform.visible && ++bgFails >= 3) {
                    gaveUp.add(prof)
                    if (!notifiedDrop) { notifiedDrop = true; Notify.alert(getApplication<Application>(), s(Str.n_disconnected), s(Str.n_disconnected_text)) }
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
                else if (running) { c.streaming = true; c.status = s(Str.reconnected_running) }
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
            onEvent = { t, sid, p -> viewModelScope.launch(Dispatchers.Main) { engine.onEvent(prof, t, sid, p) } },
            onServerRequest = { id, m, p -> viewModelScope.launch(Dispatchers.Main) { onServerRequest(g, id, m, p) } },
            onClosed = { why -> viewModelScope.launch(Dispatchers.Main) {
                if (gws[prof] !== g) return@launch      // replaced or closed on purpose
                gws.remove(prof)
                for (c in chats.filter { it.profile == prof }) {
                    c.liveId = null
                    if (c.streaming) { c.dropped = true; c.status = s(Str.reconnecting) }
                }
                if (prof == profile && cur.streaming) why?.let { error = it; offline = true }
                scheduleReconnect(prof)
            } }, logger = { tag, msg -> platform.log(tag, msg) })
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
            viewModelScope.launch(Dispatchers.Main) { engine.onEvent(c.profile, pr.optString("kind") + ".request", live, pr) } } }
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
    private fun applyUsage(c: Chat, u: JSONObject): Pair<Long, Long>? = engine.applyUsage(c, u)

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
    fun helpText() = s(Str.help_header) + "\n" + localCommands().joinToString("\n") { "${it.first} — ${it.second}" } + "\n\n" + s(Str.help_footer)
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
                modelConfirm = r.optString("confirm_message").ifBlank { s(Str.confirm_model) } to
                    JSONObject(payload.toString()).put("confirm", token)
            } else (r.optString("note").ifBlank { r.optString("warning") }).ifBlank { null }?.let { c.messages.add(UiMsg("system", it)) }
            syncFromPc(g, c, sid)
            if (op == "model" && modelConfirm == null) runCatching {
                applyUsage(c, g.call("session.stats", JSONObject().put("session_id", sid)))
                if (c.ctxMax > 0 && c.ctxUsed > c.ctxMax) compressAsk = c
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = s(Str.switch_failed) + ": " + (e.message ?: e.javaClass.simpleName)
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
    private val relay = RelayClient({ store.relayBase() }, { store.apiKey }, { s(Str.err_set_key) })
    private fun relayJson(method: String, path: String, body: String?): String? = relay.json(method, path, body)
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
                val b = async { runCatching { fetchProjects(gateway()) }.onFailure { platform.log("HermesRpc", "loadProjects: $it") }.getOrNull() }
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
    fun testConnections(relay: String, lan: String) = viewModelScope.launch {
        val key = store.apiKey ?: run { status = s(Str.err_set_key); return@launch }
        probing = true; probes = emptyList()
        try {
            val gwUrl = localNet()?.second?.let { "http://$it:8643" }
            val urls = listOf(relay.trim().trimEnd('/'), lan.trim().trimEnd('/'), gwUrl).filterNotNull().filter { it.isNotBlank() }.distinct()
            val active = store.activeRelay
            probes = coroutineScope {
                urls.map { u -> async(Dispatchers.IO) {
                    val t0 = platform.elapsedMs()
                    val r = runCatching { withTimeout(5000) { ApiClient(u, profile, key).health() } }
                    Probe(pathLabel(u), u, r.isSuccess, platform.elapsedMs() - t0, r.isSuccess && u == (active ?: relay.trim().trimEnd('/')),
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
        }.onFailure { platform.log("HermesRpc", "loadOther $p: $it") }
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
        val cwd = p.path ?: p.homeCwd ?: pcHome
        if (cwd == null) { error = s(Str.move_no_folder); return@launch }
        try {
            if (!moveOne(s, cwd)) { error = s(Str.move_busy); return@launch }
            status = s(Str.moved_to, p.name); refreshSessions()
        } catch (e: Exception) { fail(e) }
    }

    /** Move several sessions of the current profile into [p] (busy ones are skipped). */
    fun moveManyToProject(list: List<Session>, p: Project) = viewModelScope.launch {
        val cwd = p.path ?: p.homeCwd ?: pcHome
        if (cwd == null) { error = s(Str.move_no_folder); return@launch }
        try {
            var skipped = 0
            for (x in list) if (!moveOne(x, cwd)) skipped++
            if (skipped > 0) error = s(Str.move_busy) else status = s(Str.moved_to, p.name)
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

    /** Share this device's reply stats with the PC relay (best effort; an older relay just 404s). */
    private fun pushReplyStats(sid: String) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { relayJson("POST", "/stats", JSONObject().put("session", sid).put("stats", store.replyStatsJson(sid)).toString()) }
    }
    /** Pull stats other devices measured and push ours the relay lacks. Blocking: call from IO. */
    private fun syncReplyStats(sid: String) {
        runCatching {
            val remote = JSONObject(relayJson("GET", "/stats?session=" + java.net.URLEncoder.encode(sid, "UTF-8"), null) ?: return)
            val mine = store.replyStatsJson(sid)
            store.mergeReplyStats(sid, remote)
            if (mine.keys().asSequence().any { !remote.has(it) }) relayJson("POST", "/stats", JSONObject().put("session", sid).put("stats", mine).toString())
        }
    }
    private fun relayCall(method: String, path: String): String? = relay.call(method, path)

    suspend fun pollElevate() {
        while (true) {
            val list = runCatching {
                withContext(Dispatchers.IO) { relayCall("GET", "/elevate/pending") }?.let { org.json.JSONArray(it) }
            }.getOrNull()
            elevate = if (list == null) emptyList() else List(list.length()) { list.getJSONObject(it) }
            kotlinx.coroutines.delay(if (platform.visible) 3000L else 20_000L)     // background: poll rarely
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

    private fun relayRequest(path: String, body: JSONObject): okhttp3.Request = relay.request(path, body)

    private val relayHttp get() = relay.http

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
                        val label = if (p.optBoolean("isNoProject")) s(Str.home) else p.optString("label")
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
                val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
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
                val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
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
                val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
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
                        .onFailure { platform.log("HermesRpc", "ctx $id: $it") }.getOrNull()
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
        val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
        val url = store.relayBase().trimEnd('/') + "/fs/" + op
        val body = JSONObject().put("path", path).put("keep", JSONArray(keep)).toString().toRequestBody("application/json".toMediaType())
        okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build()).execute().use { r ->
            val o = runCatching { JSONObject(r.body!!.string()) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw ApiException(o.optString("error").ifBlank { "HTTP ${r.code}" })
            o
        }
    }

    /** Relay file-system call (list / mkdir / rename / delete) with an arbitrary JSON body. */
    suspend fun fsJson(op: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
        val rb = body.toString().toRequestBody("application/json".toMediaType())
        okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + "/fs/" + op).header("Authorization", "Bearer $key").post(rb).build()).execute().use { r ->
            val o = runCatching { JSONObject(r.body!!.string()) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw ApiException(if (r.code == 404 && o.optString("error").lowercase().let { it.isBlank() || it == "not found" }) s(Str.pf_old_relay) else o.optString("error").ifBlank { "HTTP ${r.code}" })
            o
        }
    }

    /** Folder shown in the PC files tab (kept while the tab is open). */
    var filesPath by mutableStateOf("")

    /** PC file -> the phone's Downloads folder. */
    suspend fun fsDownload(pcPath: String, name: String): Uri = withContext(Dispatchers.IO) {
        val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
        val ctx = getApplication<Application>()
        val http = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
        val url = store.relayBase().trimEnd('/') + "/fs/download?path=" + java.net.URLEncoder.encode(pcPath, "UTF-8")
        val tmp = java.io.File(ctx.cacheDir, "pcfile-" + System.nanoTime()).also { it.parentFile?.mkdirs() }
        try {
            http.newCall(okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $key").build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(runCatching { JSONObject(r.body!!.string()).optString("error") }.getOrNull().orEmpty().ifBlank { "HTTP ${r.code}" })
                tmp.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            }
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name); put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw ApiException("Couldn't create the file in Downloads")
            try {
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                ctx.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { ctx.contentResolver.delete(uri, null, null); throw e }
            uri
        } finally { tmp.delete() }
    }

    /** Phone file -> a PC folder (throws ApiException("exists") when the name is taken and [overwrite] is false). */
    suspend fun fsUpload(uri: Uri, folder: String, overwrite: Boolean) = withContext(Dispatchers.IO) {
        val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
        val cr = getApplication<Application>().contentResolver
        var name = "file"
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: okio.BufferedSink) { cr.openInputStream(uri)!!.use { ins -> val buf = ByteArray(65536); while (true) { val n = ins.read(buf); if (n < 0) break; sink.write(buf, 0, n) } } }
        }
        val url = store.relayBase().trimEnd('/') + "/fs/upload?path=" + java.net.URLEncoder.encode(folder, "UTF-8") + "&name=" + java.net.URLEncoder.encode(name, "UTF-8") + if (overwrite) "&overwrite=1" else ""
        val http = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
        http.newCall(okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(r.body!!.string()).optString("error") }.getOrNull().orEmpty()
                throw ApiException(msg.ifBlank { if (r.code == 404) s(Str.pf_old_relay) else "HTTP ${r.code}" })
            }
        }
    }

    /** The PC user's home folder (target for "move to home"), learned from the relay. */
    var pcHome by mutableStateOf<String?>(null)
    fun loadPcHome() { if (pcHome != null) return; viewModelScope.launch { pcHome = runCatching { fsJson("list", JSONObject().put("path", "")).optString("home").ifBlank { null } }.getOrNull() } }

    /** Move-to-project targets: always lists home (unfiled) first, even when the tree has no usable home node yet. */
    fun moveTargets(): List<Project> {
        val l = projects.filter { !it.archived }
        val h = l.find { it.isHome }
        val home = (h ?: Project("__home", "home", null, true, false, false, emptyList())).let { it.copy(homeCwd = it.homeCwd ?: pcHome) }
        return listOf(home) + l.filter { !it.isHome }
    }

    /** Point a project at another folder: attach it as the primary path and detach the old one. */
    fun changeProjectFolder(p: Project, folder: String) = viewModelScope.launch {
        try {
            val f = folder.trim()
            gateway().call("projects.add_folder", JSONObject().put("id", p.id).put("path", f).put("is_primary", true))
            val old = p.path
            if (old != null && old != f) runCatching { gateway().call("projects.remove_folder", JSONObject().put("id", p.id).put("path", old)) }
            status = s(Str.pf_folder_changed, p.name); refreshSessions()
        } catch (e: Exception) { fail(e) }
    }

    fun createProject(name: String, folder: String, mkdir: Boolean = false) = viewModelScope.launch {
        try {
            val f = folder.trim()
            if (mkdir) fsCall("mkdir", f)
            else {
                val st = runCatching { fsCall("stat", f) }.getOrNull()   // older relay without /fs: skip the check
                if (st != null && !st.optBoolean("exists")) { missingFolder = name to f; return@launch }
                if (st != null && !st.optBoolean("dir")) throw ApiException(s(Str.not_a_folder, f))
            }
            gateway().call("projects.create", JSONObject().put("name", name.trim()).put("folders", JSONArray().put(f)).put("primary_path", f))
            refreshSessions()
        } catch (e: Exception) { fail(e) }
    }
    fun archiveProject(p: Project, restore: Boolean = false) = viewModelScope.launch {
        try { gateway().call("projects.archive", JSONObject().put("id", p.id).put("restore", restore)); refreshSessions() } catch (e: Exception) { fail(e) }
    }
    /** Deletes the project grouping; with [deleteFolder] also its folder on the PC (via the relay, with guards). */
    fun deleteProject(p: Project, deleteFolder: Boolean = false, deleteSessions: Boolean = false) = viewModelScope.launch {
        try {
            if (deleteSessions) {
                val ids = p.sessionIds.toSet()
                for (id in ids) runCatching { api().delete(id) }
                chats.removeAll { it.profile == profile && it.id in ids }; tabs.removeAll { it.profile == profile && it.id in ids }
                if (currentId in ids) newChat()
            }
            if (deleteFolder && p.path != null) {
                fsCall("rmdir", p.path!!, projects.filter { it.id != p.id && !it.isHome }.mapNotNull { it.path })
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

    private fun apiFor(prof: String) = ApiClient(store.relayBase(), prof, store.apiKey ?: throw ApiException(s(Str.err_set_key)))

    private suspend fun loadMessages(id: String) { if (cur.id == id) loadMessagesInto(cur) }

    /** Reload [c]'s stored transcript; keeps unsent (failed) messages at the end. */
    /** "MEDIA:<path>" in an agent reply: have the relay copy that PC file to the phone Inbox (once per session+path); it then shows in the chat. */
    private fun sendMedia(c: Chat, text: String, sid: String? = c.id, anchor: Long = c.messages.lastOrNull { it.role == "assistant" }?.ts ?: 0L) {
        if (!text.contains("MEDIA:")) return
        for (path in Internal.mediaPaths(text)) {
            val key = "${sid.orEmpty()}|$path"
            if (store.mediaDone(key)) continue
            store.markMedia(key)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { relayJson("POST", "/outbox/send", JSONObject().put("path", path).put("session", sid.orEmpty()).put("ts", anchor).toString()) }
                    .onFailure { store.unmarkMedia(key) }
                    .onSuccess { if (it == null) store.unmarkMedia(key) }
            }
        }
    }

    private suspend fun loadMessagesInto(c: Chat) {
        val id = c.id ?: return
        try {
            syncReplyStats(id)
            val m = apiFor(c.profile).messages(id)
            m.filter { it.role == "assistant" }.forEach { sendMedia(c, it.content, id, it.ts) }
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
            "help" -> { note(helpText()); if (serverCommands.isNotEmpty()) note(s(Str.note_hermes_cmds, serverCommands.joinToString(" ") { it.first })) }
            "model" -> if (arg.isBlank()) modelMenuOpen = true else {
                val opts = modelOptions?.choices.orEmpty()
                val hit = if (arg == "default") "" else opts.firstOrNull { it.model.equals(arg, true) || it.key.equals(arg, true) }?.key
                    ?: opts.firstOrNull { it.model.contains(arg, true) }?.key
                if (hit == null) note(s(Str.note_no_model, arg)) else { chooseModel(hit); note(s(Str.note_model, modelLabel())) }
            }
            "think", "thinking", "effort" -> {
                val e = if (arg == "default") "" else arg.lowercase()
                if (e !in SecureStore.EFFORTS) note(s(Str.note_levels, SecureStore.EFFORTS.drop(1).joinToString(", ")))
                else { chooseEffort(e); note(s(Str.note_thinking, e.ifBlank { s(Str.default_) })) }
            }
            "stop" -> stop()
            "profile" -> if (arg in profileNames()) setProfileTo(arg) else note(s(Str.note_profiles, profileNames().joinToString(", ")))
            "sessions" -> { openPage("sessions") }
            "delete" -> {
                val id = currentId
                if (id == null) note(s(Str.note_no_delete))
                else if (arg != "confirm") note(s(Str.note_delete_confirm))
                else viewModelScope.launch { try { api().delete(id); newChat(); openPage("sessions") } catch (e: Exception) { fail(e) } }
            }
            "new" -> { newChat(); if (arg.isNotBlank()) sendMessage(arg, emptyList()) }
            "retry" -> { val l = lastSent; if (l == null) note(s(Str.note_nothing_retry)) else {
                val cont = agentWorkedAfter(cur, cur.messages.indexOfLast { it.role == "user" })
                sendMessage(if (cont) s(Str.continue_msg) else l.first, if (cont) emptyList() else l.second) } }
            // Like the desktop: these are session mutations, not slash.exec commands.
            "compress" -> compressChat(arg)
            "branch", "fork" -> branchWhole(arg)
            else -> if (hotfix.serverSlash) serverSlash(t) else note(s(Str.err_desktop_only, t))
        }
    }

    /** /compress [focus]: summarise older context into a continuation session (Hermes moves this chat to it). */
    private fun compressChat(focus: String) {
        if (currentId == null) { note(s(Str.note_nothing_to_compress)); return }
        if (streaming) { note(s(Str.move_busy)); return }
        messages.add(UiMsg("user", "/compress" + if (focus.isNotBlank()) " $focus" else ""))
        status = s(Str.compressing)
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
                note(if (noop) s(Str.compress_noop) + if (detail.isNotBlank()) "\n$detail" else ""
                    else s(Str.compressed) + if (detail.isNotBlank()) "\n$detail" else "")
                if (!noop) Notify.alert(getApplication<Application>(), s(Str.n_compress_done), detail.ifBlank { s(Str.compressed) }, profile = prof0, session = currentId, kind = "compress")
                refreshSessions()
            } catch (e: Exception) { status = null; fail(e) }
        }
    }

    /** /branch [title]: copy the whole conversation into a new session and open it. */
    private fun branchWhole(title: String) {
        if (currentId == null) { note(s(Str.note_nothing_to_compress)); return }
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
            if (e.message == "unsupported_command") note(s(Str.err_desktop_only, t)) else fail(e)
        }
    }

    private fun sendMessage(text: String, atts: List<Attachment>, c: Chat = cur) = engine.sendMessage(text, atts, c)

    /** Red ! menu: send a failed message again / drop it. Edit = put its text back via [editFailed]. */
    private fun agentWorkedAfter(c: Chat, idx: Int) = engine.agentWorkedAfter(c, idx)
    fun resendFailed(m: UiMsg) {
        val cont = agentWorkedAfter(cur, cur.messages.indexOfFirst { it.uid == m.uid })
        cur.messages.removeAll { it.uid == m.uid }
        sendMessage(if (cont) s(Str.continue_msg) else m.resend ?: m.text, emptyList())
    }
    fun deleteFailed(m: UiMsg) { cur.messages.removeAll { it.uid == m.uid } }
    fun editFailed(m: UiMsg, newText: String) { cur.messages.removeAll { it.uid == m.uid }; if (newText.isNotBlank()) sendMessage(newText, emptyList()) }

    private fun dropEmpty(c: Chat = cur) = engine.dropEmpty(c)
    private fun updateLast(c: Chat, f: (UiMsg) -> UiMsg) = engine.updateLast(c, f)

    fun kickProcPoll(c: Chat = cur) = engine.kickProcPoll(c)

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

    private fun placeByTime(c: Chat, m: UiMsg) = engine.placeByTime(c, m)
    private fun placeCard(c: Chat, card: UiMsg) = engine.placeCard(c, card)

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

    fun answerApproval(choice: String) = engine.answerApproval(choice)
    fun answerClarify(answers: Map<String, String>?) = engine.answerClarify(answers)

    private suspend fun submit(g: GatewayWs, sid: String, text: String, atts: List<Attachment>, c: Chat = cur) = engine.submit(g, sid, text, atts, c)
    private fun steer(t: String) = engine.steer(t)
    fun stop() = engine.stop()

    // ---------- attachments ----------
    /** Progress notice while a big file streams to the PC ("Uploading large file... 42%"); null when idle. */
    var uploadStatus by mutableStateOf<String?>(null)
    private var uploadsActive = 0

    fun addAttachments(uris: List<Uri>) = viewModelScope.launch {
        if (!hotfix.attachments) { error = s(Str.attachments_disabled); return@launch }
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

    private fun setUploadStatus(name: String, pct: Int) { uploadStatus = s(Str.uploading_large, name, pct) }

    /** Chunked, parallel (4 connections x 4 MB) upload through the relay: /upload/init, PUT /upload/<id>?offset=N, /upload/<id>/done. */
    private suspend fun uploadToPc(uri: Uri, name: String, size0: Long): Attachment = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
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
                if (r.code == 404) throw ApiException(s(Str.upload_relay_old))
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
                gateway(); status = s(Str.connected_relay)
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
