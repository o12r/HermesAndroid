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

/**
 * Everything goes through the PC relay: lists/history/rename/delete via the gateway's native HTTP API;
 * chat turns, streaming, approvals, models and real slash commands via the desktop protocol (so desktop-created sessions work too).
 */
class ChatController(val store: Store, val platform: Platform, val scope: CoroutineScope, val main: CoroutineDispatcher) {
    /** What is on screen is the active tab: a page tab (sessions / pc / settings) or, when [activePage] is null, the chat [cur]. */
    val screen: Screen get() = when (activePage?.page) { null -> Screen.Chat; "sessions" -> Screen.Sessions; "settings" -> Screen.Settings; else -> Screen.Feature }
    var profile by mutableStateOf(store.profile)
    /** All open chats (any profile); [cur] is the one on screen. */
    val chats = mutableListOf<Chat>()
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
    fun addTab(c: Chat) { if (tabs.none { it === c }) tabs.add(c) }
    /** The page tab being shown (null = the chat [cur] is shown). */
    var activePage by mutableStateOf<Chat?>(null)

    /** Switch to an open tab (switches profile too if the tab belongs to another one). */
    fun switchTo(c: Chat) {
        if (c.page != null) { activePage = c; pageEntered(c.page!!, true); return }
        if (c.profile != profile) {
            profile = c.profile; store.profile = c.profile
            proj.serverCommands = emptyList(); proj.projects = emptyList(); conn.refreshSessions(); proj.loadMeta()
        }
        cur = c; c.unread = false; activePage = null; seen()
        if (c.id != null) scope.launch {
            // Reload the transcript whenever a tab is opened (not just the first time): a turn that finished
            // elsewhere (e.g. the desktop) never delivered message.complete to a background tab, so its
            // MEDIA: lines would only be copied to the outbox here. sendMedia() is deduped per session+path.
            if (!c.streaming) msg.loadMessagesInto(c)
            runCatching { conn.ensureLive(conn.gatewayFor(c.profile), c) }
            loadChatCtx(c)
            if (c === cur) msg.kickProcPoll(c)
        }
    }

    /** Context used/max of a chat from the PC (session.stats), on first entry and on refresh. */
    suspend fun loadChatCtx(c: Chat) {
        val sid = c.liveId ?: return
        runCatching { withTimeout(8000) { val g = conn.gatewayFor(c.profile)
            val r = g.call("session.stats", JSONObject().put("session_id", sid))
            conn.applyUsage(c, r)
            // A new chat has no session id yet, and an agent not built yet reports no context; the
            // resume snapshot can still carry stored figures if the stats path came up empty.
            if (c.ctxMax <= 0 && c.id != null) {
                val rr = g.call("session.resume", JSONObject().put("session_id", c.id).put("omit_messages", true))
                (rr.optJSONObject("usage") ?: rr).let { conn.applyUsage(c, it) }
            }
        } }
    }

    /** Entering a page tab: PC refreshes its screens, the session list reloads. */
    fun pageEntered(kind: String, refresh: Boolean) {
        if (!refresh) return
        if (kind == "sessions") conn.refreshSessions() else features.forEach { it.pageEntered(kind) }
    }
    /** Open (or switch to) a page tab - "sessions" (the start page), "pc" or "settings" - in the same row as the chats. */
    fun openPage(kind: String, refresh: Boolean = true) {
        val t = tabs.firstOrNull { it.page == kind } ?: Chat(profile, null, null).also {
            it.page = kind; it.readOnly = true; it.loaded = true
            it.title = s(features.firstNotNullOfOrNull { f -> f.pageTitleKey(kind) } ?: when (kind) { "sessions" -> Str.sessions; else -> Str.settings }); tabs.add(it) }
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
    fun drainQueue(c: Chat) { if (!c.streaming && c.queue.isNotEmpty()) msg.sendMessage(c.queue.removeAt(0), emptyList(), c) }

    /** Chats with a turn running (for the session list). */
    fun busyIds(): Set<String> = chats.filter { it.streaming && it.profile == profile }.mapNotNull { it.id }.toSet()

    fun chatFor(profile: String, id: String?, title: String? = null): Chat =
        (if (id != null) chats.find { it.profile == profile && it.id == id } else null)
            ?: Chat(profile, id, title).initModel().also { chats.add(it); if (chats.size > 30) chats.removeAll { c -> !c.streaming && c !== cur && c !== it && chats.indexOf(c) < 10 } }
    // Model and effort belong to the current chat. The last choice per profile is the default for new chats.
    var model: String get() = cur.model; set(v) { cur.model = v }
    var effort: String get() = cur.effort; set(v) { cur.effort = v }
    /** Give a chat its model: the one saved for that session, else the last one picked in this profile. */
    fun fresh(p: String, id: String? = null, title: String? = null): Chat = Chat(p, id, title).initModel()
    fun Chat.initModel(): Chat = apply {
        model = id?.let { store.sessionModel(it) } ?: store.modelFor(profile)
        effort = id?.let { store.sessionEffort(it) } ?: store.effortFor(profile)
    }
    var modelMenuOpen by mutableStateOf(false)
    // ---- localisation / appearance / updates ----
    val engine: ChatEngine by lazy { ChatEngine(object : EngineHost {
        override val chats get() = this@ChatController.chats
        override val cur get() = this@ChatController.cur
        override val screen get() = this@ChatController.screen
        override var error: String? get() = this@ChatController.error; set(v) { this@ChatController.error = v }
        override val platform get() = this@ChatController.platform
        override val relay get() = this@ChatController.relay
        override val hasKey get() = store.hasKey
        override fun s(key: String, vararg a: Any) = this@ChatController.s(key, *a)
        override fun alert(title: String, text: String, force: Boolean, profile: String?, session: String?, kind: String) =
            platform.alert(title, text, force = force, profile = profile, session = session, kind = kind)
        override suspend fun refreshIds(c: Chat) = this@ChatController.msg.refreshIds(c)
        override fun refreshSessions() { this@ChatController.conn.refreshSessions() }
        override fun drainQueue(c: Chat) = this@ChatController.drainQueue(c)
        override fun sendMedia(c: Chat, rawText: String) = this@ChatController.msg.sendMedia(c, rawText)
        override fun adoptServerModel(c: Chat, info: JSONObject) = this@ChatController.conn.adoptServerModel(c, info)
        override suspend fun gateway(profile: String) = conn.gatewayFor(profile)
        override suspend fun ensureLive(g: GatewayWs, c: Chat) = this@ChatController.conn.ensureLive(g, c)
        override fun fail(e: Throwable) = this@ChatController.conn.fail(e)
        override fun noteSent(text: String, atts: List<Attachment>) { msg.lastSent = text to atts }
        override fun respond(reqId: String, result: JSONObject) { conn.gw?.respond(reqId, result) }
        override fun saveReplyStat(sid: String, text: String, v: List<Float?>) { store.setReplyStat(sid, text, v); msg.pushReplyStats(sid) }
        override suspend fun gatewayCall(profile: String, method: String, params: JSONObject) = conn.gatewayFor(profile).call(method, params)
    }, scope) }
    fun s(key: String, vararg a: Any): String = platform.string(key, *a)
    var theme by mutableStateOf(store.theme)
    /** Update source preference ("auto" | "github" | "pc"); auto = PC first, then GitHub. */
    var updateSource by mutableStateOf(store.updateSource)
    fun chooseUpdateSource(v: String) { store.updateSource = v; updateSource = store.updateSource }
    val updates: Updater get() = platform.updater
    var update by mutableStateOf<UpdateInfo?>(null)
    var updateStatus by mutableStateOf<String?>(null)
    var updateProgress by mutableStateOf<Int?>(null)
    var hotfix by mutableStateOf(Hotfix.NONE)
    val forceUpdate get() = hotfix.minVersionCode > updates.currentCode

    var liveId: String? get() = cur.liveId; set(v) { cur.liveId = v }
    var generation: Int get() = cur.generation; set(v) { cur.generation = v }
    var revision: Int get() = cur.revision; set(v) { cur.revision = v }
    fun api() = ApiClient(store.relayBase(), profile, store.apiKey ?: throw ApiException(s(Str.err_set_key)))


    var clock by mutableStateOf(store.clock.also { Formats.clock = it; Formats.is24h = { platform.is24h() } })
    fun chooseClock(v: String) { clock = v; store.clock = v; Formats.clock = v }
    fun chooseTheme(t: String) { theme = t; store.theme = t }
    var powerMode by mutableStateOf(store.powerMode.also { GatewayWs.pingSec = if (platform.isSaver()) 60L else 30L })
    fun choosePower(m: String) {
        powerMode = m; store.powerMode = m; GatewayWs.pingSec = if (platform.isSaver()) 60L else 30L
        platform.keepAliveStart()      // re-applies the wake lock policy
    }

    var fontScale by mutableStateOf(store.fontScale)
    fun chooseFontScale(f: Float) { fontScale = f; store.fontScale = f }

    /** Hotfix config every launch/resume; update check at most every 6 h unless [manual]. */
    fun checkUpdates(manual: Boolean) = scope.launch {
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
    var installJob: kotlinx.coroutines.Job? = null
    fun installUpdate() {
        val u = update ?: return
        installJob = scope.launch {
            updateProgress = 0; updateStatus = null
            runCatching { updates.install(u) { p -> scope.launch(main) { updateProgress = p } } }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) updateStatus = s(Str.update_failed, it.message ?: "?") }
            updateProgress = null; updateFileBytes = updates.downloadedSize()
        }
    }
    /** Stop the running download; the partial file is kept so a later install resumes it. */
    fun cancelUpdate() {
        installJob?.cancel(); installJob = null
        updateProgress = null; updateStatus = s(Str.update_cancelled)
        scope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(300); withContext(main) { updateFileBytes = updates.downloadedSize() } }
    }
    fun deleteUpdateFile() {
        installJob?.cancel(); installJob = null; updateProgress = null
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300); updates.deleteFiles()
            withContext(main) { updateFileBytes = updates.downloadedSize(); updateStatus = s(Str.update_file_deleted) }
        }
    }
    fun close() {
        platform.unwatchNetwork()
        conn.gws.values.forEach { it.close() }
        features.forEach { it.close() }
    }

    val deviceId: String by lazy { platform.deviceId }
    val relay = RelayClient({ store.relayBase() }, { store.apiKey }, { s(Str.err_set_key) })
    fun relayJson(method: String, path: String, body: String?): String? = relay.json(method, path, body)
    fun relayCall(method: String, path: String): String? = relay.call(method, path)

    fun relayRequest(path: String, body: JSONObject): okhttp3.Request = relay.request(path, body)

    val relayHttp get() = relay.http

    fun apiFor(prof: String) = ApiClient(store.relayBase(), prof, store.apiKey ?: throw ApiException(s(Str.err_set_key)))


    val conn = ConnectionController(this)
    val proj = ProjectController(this)
    val pc = PcController(this)
    val msg = MessageController(this)

    /** Optional features found on the classpath (see [ChatFeature]); absent modules just are not in the list. */
    val features: List<ChatFeature> = java.util.ServiceLoader.load(ChatFeature::class.java, ChatFeature::class.java.classLoader).toList()
    inline fun <reified T : ChatFeature> feature(): T? = features.filterIsInstance<T>().firstOrNull()
    init { features.forEach { it.attach(this) } }

    init { openPage(if (store.hasKey) "sessions" else "settings", refresh = false) }
    init {
        platform.watchNetwork({ scope.launch { if (store.hasKey) { delay(800); conn.pickRelay() }; if (offline) conn.reconnect() } }, { scope.launch { status = s(Str.network_changed) } })
        scope.launch { pc.inboxWatch() }
        if (store.hasKey) scope.launch { conn.pickRelay(); conn.refreshSessions(); proj.loadMeta() }
        checkUpdates(manual = false)
    }
}

/** Phone-side commands: name to description string key. */
val SLASH_COMMANDS = listOf(
    "/new" to "cmd_new", "/model" to "cmd_model", "/think" to "cmd_think",
    "/retry" to "cmd_retry", "/stop" to "cmd_stop", "/profile" to "cmd_profile",
    "/sessions" to "cmd_sessions", "/delete" to "cmd_delete", "/help" to "cmd_help",
)
