package dev.oscar.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

enum class Screen { Sessions, Chat, Settings, Feature, Assets }
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
    val task: TaskInfo? = null,
    /** Assistant reply as stored, MEDIA lines included (rendered inline by segments); [text] is the stripped copy. */
    val raw: String? = null)
/** Notice card for a sub-agent / background task: [chat] is its read-only transcript tab. */
data class TaskInfo(val key: String, val kind: String, val status: String, val startedAt: Long, val endedAt: Long, val chat: Chat,
    /** kind "proc" (terminal background process): parsed progress 0-100 (null = unknown) and its last output line. */
    val percent: Double? = null, val last: String = "",
    /** Id the Force stop action needs: subagent id (kind "sa") or process id (kind "proc"); blank = not stoppable. */
    val ref: String = "")
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
