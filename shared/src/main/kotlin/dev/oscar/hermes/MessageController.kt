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
class MessageController(val hub: ChatController) {
    val store get() = hub.store
    val scope get() = hub.scope
    val platform get() = hub.platform
    val main get() = hub.main
    fun s(key: String, vararg a: Any): String = hub.s(key, *a)

    var lastSent: Pair<String, List<Attachment>>? = null

    fun localCommands(): List<Pair<String, String>> = SLASH_COMMANDS.map { (c, id) -> c to s(id) }
    fun helpText() = s(Str.help_header) + "\n" + localCommands().joinToString("\n") { "${it.first} — ${it.second}" } + "\n\n" + s(Str.help_footer)
    fun commandList(): List<Pair<String, String>> {
        val names = (localCommands() + hub.proj.serverCommands).map { it.first }.toSet()
        return localCommands() + hub.proj.serverCommands + hub.proj.skills.filter { "/" + it.name !in names }.map { "/" + it.name to it.description }
    }
    fun note(text: String) { hub.messages.add(UiMsg("system", text)) }

    /** Share this device's reply stats with the PC relay (best effort; an older relay just 404s). */
    fun pushReplyStats(sid: String) = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { hub.relayJson("POST", "/stats", JSONObject().put("session", sid).put("stats", store.replyStatsJson(sid)).toString()) }
    }
    /** Pull stats other devices measured and push ours the relay lacks. Blocking: call from IO. */
    fun syncReplyStats(sid: String) {
        runCatching {
            val remote = JSONObject(hub.relayJson("GET", "/stats?session=" + java.net.URLEncoder.encode(sid, "UTF-8"), null) ?: return)
            val mine = store.replyStatsJson(sid)
            store.mergeReplyStats(sid, remote)
            if (mine.keys().asSequence().any { !remote.has(it) }) hub.relayJson("POST", "/stats", JSONObject().put("session", sid).put("stats", mine).toString())
        }
    }
    /** From a notification tap: open [id] in [prof] (switching profile if needed). */
    fun openFromNotification(prof: String?, id: String) {
        val p = prof ?: hub.profile
        hub.chats.find { it.profile == p && it.id == id }?.let { hub.addTab(it); hub.switchTo(it); return }
        if (p != hub.profile) {
            hub.profile = p; store.profile = p
            hub.proj.serverCommands = emptyList(); hub.proj.projects = emptyList(); hub.proj.loadMeta()
        }
        open(Session(id, null, null, null, 0, null)); hub.conn.refreshSessions()
    }

    fun open(s: Session) {
        val c = hub.chatFor(hub.profile, s.id, s.title)
        if (s.title != null) c.title = s.title
        c.projectId = hub.proj.projects.find { s.id in it.sessionIds }?.id ?: c.projectId
        hub.cur = c; hub.addTab(c); hub.activePage = null; hub.seen()
        if (c.ctxMax <= 0) hub.proj.sessionCtx[s.id]?.let { (u, m) -> c.ctxUsed = u; c.ctxMax = m }  // instant figure from the list, refined below
        scope.launch {
            // A chat that is still streaming keeps its live transcript; otherwise load the stored one.
            if (!c.streaming || !c.loaded) loadMessagesInto(c)
            // Subscribe right away so Hermes's questions, approvals and replies from a turn
            // started elsewhere (e.g. the desktop) show up live.
            runCatching { val g = hub.conn.gatewayFor(c.profile); hub.conn.ensureLive(g, c) }.onFailure { if (hub.cur === c) hub.conn.fail(it) }
            hub.loadChatCtx(c)
        }
    }

    /** Fill in stored row ids (needed for edit/branch) after a turn, keeping local notes. */
    suspend fun refreshIds(c: Chat = hub.cur) {
        val id = c.id ?: return
        val stored = runCatching { hub.apiFor(c.profile).messages(id) }.getOrNull() ?: return
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

    suspend fun mutate(operation: String, payload: JSONObject): JSONObject {
        val g = hub.conn.gateway(); val sid = hub.conn.ensureLive(g)
        fun params() = JSONObject().put("session_id", sid).put("request_id", UUID.randomUUID().toString())
            .put("expected_revision", hub.revision).put("expected_generation", hub.generation).put("operation", operation).put("payload", payload)
        return try { g.call("session.mutate", params()) } catch (e: ApiException) {
            if (e.message != "revision_conflict" && e.message != "stale_generation") throw e
            val snap = g.call("session.resume", JSONObject().put("session_id", sid).put("omit_messages", true))
            hub.revision = snap.optInt("revision", hub.revision); hub.generation = snap.optInt("execution_generation", hub.generation)
            g.call("session.mutate", params())
        }.also { r -> if (r.has("revision")) hub.revision = r.optInt("revision", hub.revision) }
    }

    /** Replace a sent user message: rewind to it (drops it and everything after), then send the new text. */
    fun editMessage(m: UiMsg, newText: String) {
        val target = m.id ?: return
        if (hub.streaming || newText.isBlank()) return
        scope.launch {
            try {
                mutate("rewind", JSONObject().put("target_message_id", target))
                val idx = hub.messages.indexOfFirst { it.id == target }
                if (idx >= 0) while (hub.messages.size > idx) hub.messages.removeAt(hub.messages.lastIndex)
                sendMessage(newText, emptyList())
            } catch (e: Exception) { hub.conn.fail(e) }
        }
    }

    /** New session with the conversation up to and including [m]; opens it. */
    fun branchFrom(m: UiMsg) {
        val through = m.id ?: return
        scope.launch {
            try {
                val r = mutate("branch", JSONObject().put("through_message_id", through))
                val newId = r.optString("branched_session_id").ifBlank { r.optString("session_id") }
                hub.conn.refreshSessions().join()
                val s = hub.conn.sessions.find { it.id == newId } ?: Session(newId, r.optString("title").ifBlank { null }, null, null, 0, "local")
                open(s)
            } catch (e: Exception) { hub.conn.fail(e) }
        }
    }
    fun newChat() {
        val project = hub.proj.newChatCwd?.let { cwd -> hub.proj.projects.find { it.path == cwd || it.homeCwd == cwd } } ?: (if (hub.proj.newChatNone) null else hub.proj.fabProject)
        val c = hub.fresh(hub.profile)
        c.newCwd = hub.proj.newChatCwd ?: project?.let { p -> p.path ?: p.homeCwd }
        c.projectId = project?.id
        hub.cur = c; hub.chats.add(c); hub.addTab(c)
        hub.proj.newChatCwd = null; hub.proj.newChatNone = false; hub.attachments.clear(); hub.activePage = null
    }

    suspend fun loadMessages(id: String) { if (hub.cur.id == id) loadMessagesInto(hub.cur) }

    /** Reload [c]'s stored transcript; keeps unsent (failed) messages at the end. */
    /** "MEDIA:<path>" in an agent reply: have the relay copy that PC file to the phone Inbox (once per session+path); it then shows in the chat. */
    fun sendMedia(c: Chat, text: String, sid: String? = c.id, anchor: Long = c.messages.lastOrNull { it.role == "assistant" }?.ts ?: 0L) {
        if (!text.contains("MEDIA:")) return
        for (path in Internal.mediaPaths(text)) {
            val key = "${sid.orEmpty()}|$path"
            if (store.mediaDone(key)) continue
            store.markMedia(key)
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { hub.relayJson("POST", "/outbox/send", JSONObject().put("path", path).put("session", sid.orEmpty()).put("ts", anchor).toString()) }
                    .onFailure { store.unmarkMedia(key) }
                    .onSuccess { if (it == null) store.unmarkMedia(key) }
            }
        }
    }

    suspend fun loadMessagesInto(c: Chat) {
        val id = c.id ?: return
        try {
            syncReplyStats(id)
            val m = hub.apiFor(c.profile).messages(id)
            m.filter { it.role == "assistant" }.forEach { sendMedia(c, it.content, id, it.ts) }
            val failed = c.messages.filter { it.failed }
            val cards = c.messages.filter { it.task != null }   // sub-agent / background / process cards are not in the server history
            c.messages.clear(); c.messages.addAll(m.mapNotNull { r ->
                (Internal.clean(r.role, r.content) ?: if (r.steps.isNotEmpty()) "" else null)?.let { t -> val st = if (r.role == "assistant") store.replyStat(id, t) else null
                    UiMsg(r.role, t, id = r.id, steps = r.steps, ts = r.ts, tokIn = st?.get(0)?.toLong(), tokOut = st?.get(1)?.toLong(), prefillTps = st?.get(2), genTps = st?.get(3)) } })
            val files = store.sessionFiles(id)
            files.forEach { (n, u, t) -> placeByTime(c, UiMsg("file", n, file = u.ifBlank { null }, session = id, ts = if (t > 0) t else legacyFileTime(u))) }
            c.messages.addAll(failed); cards.forEach { placeCard(c, it) }; c.loaded = true
            if (c === hub.cur) { hub.offline = false; hub.error = null }
        } catch (e: Exception) { if (c === hub.cur) hub.conn.fail(e) }
    }

    fun rename(s: Session, title: String) = scope.launch {
        try { hub.api().rename(s.id, title); hub.conn.refreshSessions() } catch (e: Exception) { hub.conn.fail(e) } }
    fun delete(s: Session) = scope.launch {
        try { hub.api().delete(s.id); hub.chats.removeAll { it.profile == hub.profile && it.id == s.id }; hub.tabs.removeAll { it.profile == hub.profile && it.id == s.id }; if (hub.currentId == s.id) newChat(); hub.conn.refreshSessions() } catch (e: Exception) { hub.conn.fail(e) } }

    // ---------- sending ----------
    fun send(text: String) {
        val t = text.trim()
        if (t.startsWith("/") && hub.attachments.isEmpty()) { slash(t); return }
        if (hub.streaming && t.isNotEmpty()) { steer(t); return }
        sendMessage(text, hub.attachments.toList()); hub.attachments.clear()
    }

    /** App-side commands first (model/think/profile/... need the phone UI); everything else runs on Hermes. */
    fun slash(t: String) {
        val cmd = t.substringBefore(' ').drop(1).lowercase()
        val arg = t.substringAfter(' ', "").trim()
        when (cmd) {
            "help" -> { note(helpText()); if (hub.proj.serverCommands.isNotEmpty()) note(s(Str.note_hermes_cmds, hub.proj.serverCommands.joinToString(" ") { it.first })) }
            "model" -> if (arg.isBlank()) hub.modelMenuOpen = true else {
                val opts = hub.proj.modelOptions?.choices.orEmpty()
                val hit = if (arg == "default") "" else opts.firstOrNull { it.model.equals(arg, true) || it.key.equals(arg, true) }?.key
                    ?: opts.firstOrNull { it.model.contains(arg, true) }?.key
                if (hit == null) note(s(Str.note_no_model, arg)) else { hub.proj.chooseModel(hit); note(s(Str.note_model, hub.proj.modelLabel())) }
            }
            "think", "thinking", "effort" -> {
                val e = if (arg == "default") "" else arg.lowercase()
                if (e !in Store.EFFORTS) note(s(Str.note_levels, Store.EFFORTS.drop(1).joinToString(", ")))
                else { hub.proj.chooseEffort(e); note(s(Str.note_thinking, e.ifBlank { s(Str.default_) })) }
            }
            "stop" -> stop()
            "profile" -> if (arg in hub.conn.profileNames()) hub.conn.setProfileTo(arg) else note(s(Str.note_profiles, hub.conn.profileNames().joinToString(", ")))
            "sessions" -> { hub.openPage("sessions") }
            "delete" -> {
                val id = hub.currentId
                if (id == null) note(s(Str.note_no_delete))
                else if (arg != "confirm") note(s(Str.note_delete_confirm))
                else scope.launch { try { hub.api().delete(id); newChat(); hub.openPage("sessions") } catch (e: Exception) { hub.conn.fail(e) } }
            }
            "new" -> { newChat(); if (arg.isNotBlank()) sendMessage(arg, emptyList()) }
            "retry" -> { val l = lastSent; if (l == null) note(s(Str.note_nothing_retry)) else {
                val cont = agentWorkedAfter(hub.cur, hub.cur.messages.indexOfLast { it.role == "user" })
                sendMessage(if (cont) s(Str.continue_msg) else l.first, if (cont) emptyList() else l.second) } }
            // Like the desktop: these are session mutations, not slash.exec commands.
            "compress" -> compressChat(arg)
            "branch", "fork" -> branchWhole(arg)
            else -> if (hub.hotfix.serverSlash) serverSlash(t) else note(s(Str.err_desktop_only, t))
        }
    }

    /** /compress [focus]: summarise older context into a continuation session (Hermes moves this chat to it). */
    fun compressChat(focus: String) {
        if (hub.currentId == null) { note(s(Str.note_nothing_to_compress)); return }
        if (hub.streaming) { note(s(Str.move_busy)); return }
        hub.messages.add(UiMsg("user", "/compress" + if (focus.isNotBlank()) " $focus" else ""))
        hub.status = s(Str.compressing)
        val prof0 = hub.profile
        scope.launch {
            try {
                val r = mutate("compress", if (focus.isNotBlank()) JSONObject().put("focus", focus) else JSONObject())
                val target = r.str("target_session_id").ifBlank { r.str("session_id") }.ifBlank { null }
                hub.status = null
                // Hermes's own summary, e.g. "No changes from compression: 4 messages" / token estimate.
                val sum = r.optJSONObject("summary")
                val detail = listOfNotNull(sum?.str("headline")?.ifBlank { null }, sum?.str("token_line")?.ifBlank { null },
                    sum?.str("note")?.ifBlank { null }).joinToString("\n")
                val noop = r.str("status") == "noop" || sum?.optBoolean("noop") == true
                if (!noop) {
                    // Reload the compacted transcript, then show the result after it (reloading clears notes).
                    if (target != null && target != hub.currentId) { hub.currentId = target; hub.liveId = null }
                    hub.currentId?.let { loadMessages(it); runCatching { val g = hub.conn.gateway(); hub.conn.ensureLive(g) } }
                }
                note(if (noop) s(Str.compress_noop) + if (detail.isNotBlank()) "\n$detail" else ""
                    else s(Str.compressed) + if (detail.isNotBlank()) "\n$detail" else "")
                if (!noop) platform.alert(s(Str.n_compress_done), detail.ifBlank { s(Str.compressed) }, profile = prof0, session = hub.currentId, kind = "compress")
                hub.conn.refreshSessions()
            } catch (e: Exception) { hub.status = null; hub.conn.fail(e) }
        }
    }

    /** /branch [title]: copy the whole conversation into a new session and open it. */
    fun branchWhole(title: String) {
        if (hub.currentId == null) { note(s(Str.note_nothing_to_compress)); return }
        scope.launch {
            try {
                val r = mutate("branch", if (title.isNotBlank()) JSONObject().put("title", title) else JSONObject())
                val newId = r.str("branched_session_id").ifBlank { r.str("session_id") }
                hub.conn.refreshSessions().join()
                open(hub.conn.sessions.find { it.id == newId } ?: Session(newId, title.ifBlank { null }, null, null, 0, "local"))
            } catch (e: Exception) { hub.conn.fail(e) }
        }
    }

    fun serverSlash(t: String) = scope.launch {
        hub.messages.add(UiMsg("user", t))
        try {
            val g = hub.conn.gateway(); val sid = hub.conn.ensureLive(g)
            val r = g.call("slash.exec", JSONObject().put("session_id", sid).put("command", t))
            val type = r.optString("type")
            val msg = r.optString("message").ifBlank { null }
            when {
                type == "skill" && msg != null -> submit(g, sid, msg, emptyList())
                msg != null && type == "send" -> submit(g, sid, msg, emptyList())
                else -> listOf(r.optString("output"), r.optString("notice"), r.optString("warning"), msg.orEmpty())
                    .filter { it.isNotBlank() && it != "null" }.joinToString("\n\n").ifBlank { "✓ $t" }.let { hub.messages.add(UiMsg("assistant", it)) }
            }
            if (t.startsWith("/title") || t.startsWith("/new")) hub.conn.refreshSessions()
        } catch (e: Exception) {
            if (e.message == "unsupported_command") note(s(Str.err_desktop_only, t)) else hub.conn.fail(e)
        }
    }

    fun sendMessage(text: String, atts: List<Attachment>, c: Chat = hub.cur) = hub.engine.sendMessage(text, atts, c)

    /** Red ! menu: send a failed message again / drop it. Edit = put its text back via [editFailed]. */
    fun agentWorkedAfter(c: Chat, idx: Int) = hub.engine.agentWorkedAfter(c, idx)
    fun resendFailed(m: UiMsg) {
        val cont = agentWorkedAfter(hub.cur, hub.cur.messages.indexOfFirst { it.uid == m.uid })
        hub.cur.messages.removeAll { it.uid == m.uid }
        sendMessage(if (cont) s(Str.continue_msg) else m.resend ?: m.text, emptyList())
    }
    fun deleteFailed(m: UiMsg) { hub.cur.messages.removeAll { it.uid == m.uid } }
    fun editFailed(m: UiMsg, newText: String) { hub.cur.messages.removeAll { it.uid == m.uid }; if (newText.isNotBlank()) sendMessage(newText, emptyList()) }

    fun dropEmpty(c: Chat = hub.cur) = hub.engine.dropEmpty(c)
    fun updateLast(c: Chat, f: (UiMsg) -> UiMsg) = hub.engine.updateLast(c, f)

    fun kickProcPoll(c: Chat = hub.cur) = hub.engine.kickProcPoll(c)

    /** Put a task card where it started: before the first message sent after its start (not always at the bottom, e.g. after a restart). */
    /** Insert [m] before the first user/assistant message that is newer than it (files without a known time go last). */
    /** Files saved before arrival times were recorded: use the saved file's own modified/added time (MAX = unknown, goes last). */
    fun legacyFileTime(uri: String): Long = platform.fileTime(uri)

    fun placeByTime(c: Chat, m: UiMsg) = hub.engine.placeByTime(c, m)
    fun placeCard(c: Chat, card: UiMsg) = hub.engine.placeCard(c, card)

    /** Open a task card's read-only transcript as a tab. */
    fun openTask(t: TaskInfo) { hub.addTab(t.chat); hub.switchTo(t.chat) }

    fun onServerRequest(g: GatewayWs, id: String, method: String, p: JSONObject) {
        when (method) {
            "approval" -> {
                val ch = p.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("once", "deny")
                hub.approval = Approval(id, p.optString("command"), p.optString("description"), ch)
            }
            "clarify" -> {
                val qs = p.optJSONArray("questions") ?: JSONArray()
                hub.clarify = Clarify(id, (0 until qs.length()).map { qs.getJSONObject(it) }.map { q ->
                    val ch = q.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                    ClarifyQ(q.getString("qid"), q.optString("question"), ch, q.optBoolean("multi_select") && ch.isNotEmpty())
                })
            }
            else -> g.respond(id, null, "not supported on the phone")
        }
    }

    fun answerApproval(choice: String) = hub.engine.answerApproval(choice)
    fun answerClarify(answers: Map<String, String>?) = hub.engine.answerClarify(answers)

    suspend fun submit(g: GatewayWs, sid: String, text: String, atts: List<Attachment>, c: Chat = hub.cur) = hub.engine.submit(g, sid, text, atts, c)
    fun steer(t: String) = hub.engine.steer(t)
    fun stop() = hub.engine.stop()

}
