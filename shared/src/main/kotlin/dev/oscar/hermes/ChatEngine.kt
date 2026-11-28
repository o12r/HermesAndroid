package dev.oscar.hermes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** What [ChatEngine] needs from the app: chat list, current chat, UI flags and a few services. Android and desktop each implement it. */
interface EngineHost {
    val chats: List<Chat>
    val cur: Chat
    val screen: Screen
    var error: String?
    val platform: Platform
    val relay: RelayClient
    val hasKey: Boolean
    fun s(key: String, vararg a: Any): String
    /** Heads-up notification (suppressed when the app is visible unless [force]). */
    fun alert(title: String, text: String, force: Boolean = false, profile: String? = null, session: String? = null, kind: String = "other")
    suspend fun refreshIds(c: Chat)
    fun refreshSessions()
    fun drainQueue(c: Chat)
    fun sendMedia(c: Chat, rawText: String)
    fun adoptServerModel(c: Chat, info: JSONObject)
    suspend fun gateway(profile: String): GatewayWs
    suspend fun ensureLive(g: GatewayWs, c: Chat): String
    fun fail(e: Throwable)
    fun noteSent(text: String, atts: List<Attachment>)
    fun respond(reqId: String, result: JSONObject)
    fun saveReplyStat(sid: String, text: String, v: List<Float?>)
    suspend fun gatewayCall(profile: String, method: String, params: JSONObject): JSONObject
    /** Uploads the phone file at [path] like a picked attachment; null when it does not exist / cannot be read. */
    suspend fun uploadFile(path: String): Attachment?
}

/** Routes gateway events into chat state: streaming text, tools, approvals, sub-agent cards, background-process cards. */
class ChatEngine(private val host: EngineHost, private val scope: CoroutineScope) {
    private val platform get() = host.platform

    fun applyUsage(c: Chat, u: JSONObject): Pair<Long, Long>? {
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

    fun dropEmpty(c: Chat) {
        val messages = c.messages
        if (messages.lastOrNull()?.let { it.role == "assistant" && it.text.isEmpty() && it.steps.isEmpty() } == true) messages.removeAt(messages.lastIndex)
    }

    fun updateLast(c: Chat, f: (UiMsg) -> UiMsg) {
        val messages = c.messages
        val i = messages.lastIndex
        if (i >= 0 && messages[i].role == "assistant") messages[i] = f(messages[i]) else messages.add(f(UiMsg("assistant", "")))
    }


    /** Route an event to the chat whose live session it belongs to (any profile, on host.screen or not). */
    fun onEvent(prof: String, type: String, sid: String, p: JSONObject) {
        val c = if (sid.isEmpty()) (if (host.cur.profile == prof) host.cur else return)
            else host.chats.find { it.profile == prof && (it.liveId == sid || it.id == sid) } ?: return
        val away = !platform.visible || c !== host.cur          // not looking at this chat
        val name = c.title ?: host.s(Str.chat)
        if (type == "tool.complete" || type == "message.complete") kickProcPoll(c)   // a command may have started a background process
        when (type) {
            "session.usage" -> (p.optJSONObject("usage") ?: p).let { u -> applyUsage(c, u) }
            "session.info", "session.control.update", "session.updated" -> {
                if (p.has("execution_generation")) c.generation = p.optInt("execution_generation", c.generation)
                if (p.has("revision")) c.revision = p.optInt("revision", c.revision)
                if (type == "session.info") host.adoptServerModel(c, p)
            }
            "message.start" -> { c.streaming = true; c.status = null
                c.turnStartAt = platform.elapsedMs(); c.firstTokenAt = 0L
                platform.clearAlerts(c.id, "reply")       // a new turn (from the phone or the PC): the last reply was seen
                if (c.messages.lastOrNull()?.role != "assistant") c.messages.add(UiMsg("assistant", "")) }
            "message.delta" -> { c.status = null; val t = p.str("text")
                if (c.firstTokenAt == 0L && t.isNotEmpty()) c.firstTokenAt = platform.elapsedMs()
                updateLast(c) { m ->
                    val closed = m.steps.map { s -> if (s.kind == "thinking" && s.output == null) s.copy(output = "") else s }
                    val steps = if (t.isEmpty()) closed
                        else if (closed.lastOrNull()?.kind == "text") closed.dropLast(1) + closed.last().copy(detail = closed.last().detail + t)
                        else closed + Step("text", "", t)
                    m.copy(text = m.text + t, steps = steps) } }
            "thinking.delta", "reasoning.delta" -> { c.status = host.s(Str.thinking_status)
                if (c.firstTokenAt == 0L && p.str("text").isNotEmpty()) c.firstTokenAt = platform.elapsedMs()
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
                host.sendMedia(c, rawText)
                val text = Internal.stripMedia(rawText)
                if (rawText.isNotBlank()) updateLast(c) { m -> m.copy(text = text, raw = rawText,
                    steps = if (m.steps.any { it.kind == "text" }) m.steps else m.steps + Step("text", "", rawText)) }
                (p.optJSONObject("usage")?.takeIf { it.length() > 0 }).let { u -> if (u == null) return@let
                    // No baseline yet (first reply of a fresh chat): count from zero. Counters that went down (compression / new
                    // counters) restart from the new value instead of dropping the stats.
                    val fresh = c.totIn == null && c.messages.count { it.role == "assistant" } <= 1
                    val i0 = c.totIn ?: if (fresh) 0L else null; val o0 = c.totOut ?: if (fresh) 0L else null
                    applyUsage(c, u)?.let { (i, o) ->
                        if (i0 != null && o0 != null && (i != i0 || o != o0)) {
                            val di = if (i >= i0) i - i0 else i; val dO = if (o >= o0) o - o0 else o
                            val end = platform.elapsedMs()
                            val ttft = if (c.firstTokenAt > 0 && c.turnStartAt > 0) (c.firstTokenAt - c.turnStartAt) / 1000f else 0f
                            val gen = if (c.firstTokenAt > 0) (end - c.firstTokenAt) / 1000f else 0f
                            val pre = if (ttft > 0f && di > 0) di / ttft else null
                            val gt = if (gen >= 0.2f && dO > 0) dO / gen else null
                            updateLast(c) { it.copy(tokIn = di, tokOut = dO, prefillTps = pre, genTps = gt) }
                            c.id?.let { sid -> c.messages.lastOrNull { it.role == "assistant" }?.let { host.saveReplyStat(sid, it.text, listOf(di.toFloat(), dO.toFloat(), pre, gt)) } }
                        }
                        c.totIn = i; c.totOut = o } }
                if (away && text.isNotBlank()) {
                    val title = host.s(Str.n_reply_done, name)
                    // Background: notification with an inline Reply box (Android). Foreground on another chat: the plain alert.
                    if (platform.visible) host.alert(title, text, force = c !== host.cur, profile = c.profile, session = c.id, kind = "reply")
                    else platform.notifyReply(c.profile, c.id.orEmpty(), title, text)
                }
                c.streaming = false; c.status = null; c.dropped = false; dropEmpty(c)
                if (c !== host.cur || host.screen != Screen.Chat) c.unread = true
                scope.launch { host.refreshIds(c) }
                if (p.optString("outcome") !in setOf("", "completed") && c === host.cur) host.error = "Turn ended: ${p.optString("outcome")}"
                if (c.title == null) host.refreshSessions()
                if (p.optString("outcome") in setOf("", "completed")) host.drainQueue(c)   // stopped: keep the queue
            }
            "approval.request" -> {
                val ch = p.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("once", "deny")
                c.approval = Approval(p.optString("prompt_id"), p.optString("command"), p.optString("description"), ch,
                    p.optInt("execution_generation", c.generation))
                if (away) host.alert(host.s(Str.n_approval) + " · " + name, p.optString("command"), force = c !== host.cur, profile = c.profile, session = c.id, kind = "approval")
            }
            "clarify.request" -> {
                val ch = p.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                c.clarify = Clarify(p.optString("prompt_id"), listOf(ClarifyQ("q", p.optString("question"), ch, p.optBoolean("multi_select") && ch.isNotEmpty())),
                    p.optInt("execution_generation", c.generation))
                if (away) host.alert(host.s(Str.n_clarify) + " · " + name, p.optString("question"), force = c !== host.cur, profile = c.profile, session = c.id, kind = "clarify")
            }
            // Answered here or on the PC: the question's notification is stale either way.
            "approval.settled" -> { if (c.approval?.reqId == p.optString("prompt_id")) c.approval = null; platform.clearAlerts(c.id, "approval") }
            "clarify.settled" -> { if (c.clarify?.reqId == p.optString("prompt_id")) c.clarify = null; platform.clearAlerts(c.id, "clarify") }
            "subagent.spawn_requested", "subagent.start", "subagent.thinking", "subagent.text", "subagent.tool",
            "subagent.progress", "subagent.complete", "background.complete" -> taskEvent(c, type, p)
            "session.title" -> p.optString("title").ifBlank { null }?.let { c.title = it }
            "error" -> { val msg = p.optString("message").ifBlank { "Hermes error" }
                c.streaming = false; c.status = null; dropEmpty(c); c.messages.add(UiMsg("system", "⚠ $msg"))
                if (c === host.cur) host.error = msg
                if (away) host.alert(host.s(Str.n_error) + " · " + name, msg, force = c !== host.cur, profile = c.profile, session = c.id) }
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
                it.title = host.s(if (bg) Str.task_background else Str.task_subagent) } }
        val goal = p.str("goal").ifBlank { p.str("preview") }
        var status = c.messages.firstOrNull { it.task?.key == key && it.task!!.chat === ro }?.task?.status ?: "running"
        var started = c.messages.firstOrNull { it.task?.key == key && it.task!!.chat === ro }?.task?.startedAt ?: now
        val ref = p.str("subagent_id").ifBlank { c.messages.firstOrNull { it.task?.key == key && it.task!!.chat === ro }?.task?.ref.orEmpty() }
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
                status = if (status == "stopped") "stopped" else if ((bg && body.startsWith("error:")) || p.has("failure_reason") || st in setOf("failed", "error", "timeout", "timed_out", "interrupted")) "failed" else "done"
                val dur = p.optDouble("duration_seconds", 0.0)
                if (dur > 0) started = now - (dur * 1000).toLong()
                ended = now
                if (bg && ro.messages.none { it.role == "system" }) ro.messages.add(0, UiMsg("system", p.str("task_id")))
                if (body.isNotBlank()) roAppend(ro) { m -> m.copy(text = body, steps = m.steps + Step("text", "", body)) }
                ro.streaming = false
                if (ro !== host.cur) ro.unread = true }
        }
        val card = UiMsg("task", ro.title ?: "", uid = "task:" + System.identityHashCode(ro), task = TaskInfo(key, if (bg) "bg" else "sa", status, started, ended, ro, ref = ref))
        val i = c.messages.indexOfFirst { it.uid == card.uid }
        if (i >= 0) c.messages[i] = card
        else placeCard(c, card)   // at its start time; keeps the streaming assistant message last
    }

    /** Card button Force stop: kills a running sub-agent (subagent.interrupt) or a background process of this session (process.kill).
     *  Already finished is not an error: no popup, the card refreshes from the gateway. */
    fun forceStop(c: Chat, t: TaskInfo) {
        val sid = c.liveId ?: return
        if (t.status != "running" || t.ref.isBlank() || t.kind == "bg") return
        scope.launch {
            val killed = runCatching {
                if (t.kind == "proc") {
                    withTimeout(8000) { host.gatewayCall(c.profile, "process.kill", JSONObject().put("session_id", sid).put("process_id", t.ref)) }.optString("status") == "killed"
                } else {
                    withTimeout(8000) { host.gatewayCall(c.profile, "subagent.interrupt", JSONObject().put("session_id", sid).put("subagent_id", t.ref)) }.optBoolean("found", false)
                }
            }.getOrDefault(false)
            if (killed) markStopped(c, t)
            if (t.kind == "proc") runCatching { pollProcs(c, sid) }   // the process row now reports its exit
        }
    }

    private fun markStopped(c: Chat, t: TaskInfo) {
        val i = c.messages.indexOfFirst { it.task?.key == t.key && it.task!!.chat === t.chat }
        if (i >= 0) c.messages[i] = c.messages[i].copy(task = c.messages[i].task!!.copy(status = "stopped", endedAt = System.currentTimeMillis()))
        t.chat.streaming = false
    }

    // ---- background terminal processes: progress cards -------------------------------------------------------------
    // The gateway lists a session's processes (RPC process.list); the relay (POST /bgtasks) parses progress from their output.
    // Polled every 3s only while the app is in the foreground, the chat is the open one and a process is still running.
    private val procJobs = HashMap<Chat, kotlinx.coroutines.Job>()

    fun kickProcPoll(c: Chat) {
        if (c.readOnly || c.liveId == null || !host.hasKey || procJobs[c]?.isActive == true) return
        procJobs[c] = scope.launch {
            while (platform.visible && c === host.cur) {
                val sid = c.liveId ?: break
                if (!runCatching { pollProcs(c, sid) }.getOrDefault(false) && !c.streaming) break   // keep looking while a turn runs: the process may not exist yet
                delay(3000)
            }
        }
    }

    /** One poll; true while any process of the session is still running. */
    private suspend fun pollProcs(c: Chat, sid: String): Boolean {
        val r = withTimeout(8000) { host.gatewayCall(c.profile, "process.list", JSONObject().put("session_id", sid)) }
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
            host.relay.http.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build().newCall(host.relay.request("/bgtasks", body)).execute().use { r ->
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
        val old = c.messages.firstOrNull { it.task?.key == key && it.task!!.chat === ro }?.task
        val running = row.str("status") == "running"
        val failed = !running && !row.isNull("exit_code") && row.optInt("exit_code", 0) != 0
        val status = if (running) "running" else if (old?.status == "stopped") "stopped" else if (failed) "failed" else "done"
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
        val card = UiMsg("task", ro.title ?: "", uid = "task:" + System.identityHashCode(ro), task = TaskInfo(key, "proc", status, started, ended, ro, pct, last, ref = pid))
        val i = c.messages.indexOfFirst { it.uid == card.uid }
        if (i >= 0) { if (c.messages[i].task != card.task) c.messages[i] = card }
        else placeCard(c, card)
    }


    fun placeByTime(c: Chat, m: UiMsg) {
        val i = c.messages.indexOfFirst { (it.role == "user" || it.role == "assistant" || it.role == "file") && it.ts > m.ts }
        if (i >= 0) c.messages.add(i, m) else c.messages.add(m)
    }

    fun placeCard(c: Chat, card: UiMsg) {
        val started = card.task?.startedAt ?: 0L
        val i = if (started > 0) c.messages.indexOfFirst { it.task == null && it.ts > started && (it.role == "user" || it.role == "assistant") } else -1
        if (i >= 0) { c.messages.add(i, card); return }
        val l = c.messages.lastIndex
        if (l >= 0 && c.messages[l].role == "assistant" && c.streaming) c.messages.add(l, card) else c.messages.add(card)
    }

    fun sendMessage(text: String, atts: List<Attachment>, c: Chat = host.cur) {
        if (c.streaming || (text.isBlank() && atts.isEmpty())) return
        host.noteSent(text, atts)
        val shown = (atts.map { if (it.isImage) "🖼️ ${it.name}" else "📎 ${it.name}" } + text).filter { it.isNotBlank() }.joinToString("\n")
        val msg = UiMsg("user", shown, resend = text)
        c.messages.add(msg)
        scope.launch {
            try {
                // FILE: lines: upload each existing phone file like an attachment; the line stays in the chat text.
                // Missing files get a note and their line is sent as plain text.
                val files = ArrayList<Attachment>(); val found = ArrayList<String>()
                for (p in Internal.filePaths(text)) {
                    val a = host.uploadFile(p)
                    if (a == null) c.messages.add(UiMsg("system", host.s(if (host.platform.hasAllFiles) Str.file_not_found else Str.file_not_found_all_files, p))) else { files += a; found += p }
                }
                val prompt = Internal.stripFiles(text, found)
                val all = atts + files
                val g = host.gateway(c.profile)
                try { submit(g, host.ensureLive(g, c), prompt, all, c) }
                catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    // Stale live session (e.g. the gateway reaped it while the agent was busy): re-attach once and resend.
                    if (c.id == null) throw e
                    c.liveId = null; submit(g, host.ensureLive(g, c), prompt, all, c)
                }
            }
            catch (e: Exception) {
                c.streaming = false; c.status = null
                val i = c.messages.indexOfFirst { it.uid == msg.uid }
                if (i >= 0) c.messages[i] = c.messages[i].copy(failed = true)
                if (c === host.cur) host.fail(e)
                host.alert(host.s(Str.n_send_failed), text.take(120))
            }
        }
    }


    /** True when the agent already did tool work after message [idx]: a retry then just says "continue" instead of repeating the request. */
    fun agentWorkedAfter(c: Chat, idx: Int): Boolean = idx >= 0 && c.messages.drop(idx + 1).takeWhile { it.role != "user" }
        .any { it.role == "assistant" && (it.tools.isNotEmpty() || it.steps.any { st -> st.kind != "thinking" && st.kind != "text" }) }

    suspend fun submit(g: GatewayWs, sid: String, text: String, atts: List<Attachment>, c: Chat) {
        if (c === host.cur) host.error = null
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


    fun answerApproval(choice: String) {
        val c = host.cur; val a = c.approval ?: return; c.approval = null; host.platform.clearAlerts(c.id, "approval")
        if (a.gen == null) { host.respond(a.reqId, JSONObject().put("choice", choice)); return }
        scope.launch { runCatching { host.gateway(c.profile).call("approval.respond", JSONObject().put("session_id", c.liveId)
            .put("execution_generation", a.gen).put("prompt_id", a.reqId).put("choice", choice)) }.onFailure { host.fail(it) } }
    }
    fun answerClarify(answers: Map<String, String>?) {
        val chat = host.cur; val c = chat.clarify ?: return; chat.clarify = null; host.platform.clearAlerts(chat.id, "clarify")
        if (c.gen != null) {
            scope.launch { runCatching { host.gateway(chat.profile).call("clarify.respond", JSONObject().put("session_id", chat.liveId)
                .put("execution_generation", c.gen).put("prompt_id", c.reqId).put("answer", answers?.values?.firstOrNull().orEmpty())) }
                .onFailure { host.fail(it) } }
            return
        }
        host.respond(c.reqId, if (answers == null) JSONObject().put("outcome", "cancelled")
            else JSONObject().put("answers", JSONObject(answers as Map<*, *>)).put("outcome", "submitted"))
    }


    /** Inject text into the running turn (Hermes sees it with the next tool result) without interrupting. */
    fun steer(t: String) {
        val c = host.cur
        // FILE: lines need the upload step first: send as a normal message, or queue behind the running turn.
        if (Internal.filePaths(t).isNotEmpty()) { if (c.streaming) { c.queue.add(t); c.status = host.s(Str.steered) } else sendMessage(t, emptyList(), c); return }
        // Mid-turn note: appears where it was sent (after what the agent has said so far); later output continues in a new reply below it.
        c.messages.add(UiMsg("user", t))
        c.messages.add(UiMsg("assistant", ""))
        scope.launch {
            var generation by object { operator fun getValue(a: Any?, b: Any?) = c.generation; operator fun setValue(a: Any?, b: Any?, v: Int) { c.generation = v } }
            try {
                val g = host.gateway(c.profile)
                val sid = c.liveId ?: host.ensureLive(g, c)
                var r = runCatching { g.call("session.steer", JSONObject().put("session_id", sid).put("text", t).put("execution_generation", generation)) }
                if (r.isFailure) {  // generation moved on: refresh once and retry
                    val info = g.call("session.info", JSONObject().put("session_id", sid))
                    generation = info.optInt("execution_generation", generation)
                    r = runCatching { g.call("session.steer", JSONObject().put("session_id", sid).put("text", t).put("execution_generation", generation)) }
                }
                val res = r.getOrThrow()
                if (res.optString("status") == "rejected") throw ApiException("steer_rejected")
                c.status = host.s(Str.steered)
            } catch (e: Exception) {
                // Steer refused (generation moved on / turn ending): drop the optimistic bubbles and send it as a normal message,
                // or queue it behind the running turn, instead of leaving a dead message and an error.
                val i = c.messages.indexOfLast { it.role == "user" && it.text == t && it.id == null }
                if (i >= 0) { c.messages.removeAt(i); if (c.messages.getOrNull(i)?.let { it.role == "assistant" && it.text.isEmpty() && it.steps.isEmpty() } == true) c.messages.removeAt(i) }
                if (!c.streaming) sendMessage(t, emptyList(), c) else { c.queue.add(t); c.status = host.s(Str.steered) }
            }
        }
    }

    fun stop() {
        val c = host.cur
        val sid = c.liveId ?: return
        scope.launch {
            var generation by object { operator fun getValue(a: Any?, b: Any?) = c.generation; operator fun setValue(a: Any?, b: Any?, v: Int) { c.generation = v } }
            val g = runCatching { host.gateway(c.profile) }.getOrElse { host.fail(it); return@launch }
            val r = runCatching { g.call("session.interrupt", JSONObject().put("session_id", sid).put("execution_generation", generation)) }
            if (r.exceptionOrNull()?.message == "stale_generation") {
                // Refresh the fence from a snapshot and retry once.
                runCatching {
                    generation = g.call("session.resume", JSONObject().put("session_id", sid).put("omit_messages", true)).optInt("execution_generation", generation)
                    g.call("session.interrupt", JSONObject().put("session_id", sid).put("execution_generation", generation))
                }.onFailure { host.fail(it) }
            } else r.onFailure { host.fail(it) }
            c.status = host.s(Str.stopping)
        }
    }
}
