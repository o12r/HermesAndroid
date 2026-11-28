package dev.oscar.hermes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android shell around [ChatController] (all chat logic lives in :shared): lifecycle, Uri-based file access, launcher icon. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    val c = ChatController(store, AndroidPlatform(app, store), viewModelScope, Dispatchers.Main)
    /** Scroll state of the tab row, shared by every screen so switching pages does not rebuild it. */
    val tabsState = LazyListState()

    init {
        // Inline replies from reply notifications go to this controller; replies queued while no controller existed go out now.
        ReplyNotify.deliver = { p, s, t -> sendNotificationReply(p, s, t) }
        ReplyNotify.flushQueued(app) { p, s, t -> sendNotificationReply(p, s, t) }
        Thread { MediaCache.sweep(app) }.start()   // drop received media older than 7 days
    }

    /** Inline reply from a notification: the text goes into chat [sid] of [profile] (queued when that chat is mid-turn). */
    fun sendNotificationReply(profile: String, sid: String, text: String) {
        val chat = c.chatFor(profile, sid)
        if (chat.streaming) chat.queue.add(text) else c.msg.sendMessage(text, emptyList(), chat)
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

    fun addAttachments(uris: List<Uri>) = c.pc.addAttachments(uris.map { u -> { UriAttachment(getApplication(), u) } })

    /** Attach button: each picked file is resolved to its phone path (off the main thread) and handed to [onPath] as a FILE: line; no path = a note, no copy. */
    fun stageFiles(uris: List<Uri>, onPath: (String) -> Unit) = viewModelScope.launch {
        for (u in uris) {
            val p = withContext(Dispatchers.IO) { FileStaging.pathFor(getApplication(), u) }
            if (p == null) c.error = c.s(Str.file_no_path) else onPath(p)
        }
    }

    /** A long paste, saved as cache/paste/paste.txt (paste-2.txt, … if taken) and attached like a picked file. */
    fun attachPaste(text: String) = c.pc.addAttachments(listOf({ PasteAttachment(getApplication(), text) }))

    suspend fun fsDownload(pcPath: String, name: String): Uri = Uri.parse(c.pc.fsDownload(pcPath, name))
    suspend fun fetchInboxNow(name: String, session: String?, anchor: Long = 0L): Uri = Uri.parse(c.pc.fetchInboxNow(name, session, anchor))
    suspend fun fsUpload(uri: Uri, folder: String, overwrite: Boolean) {
        val cr = getApplication<Application>().contentResolver
        var name = "file"
        withContext(Dispatchers.IO) { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name } }
        c.pc.fsUpload(name, { cr.openInputStream(uri)!! }, folder, overwrite)
    }

    override fun onCleared() { ReplyNotify.deliver = null; c.close() }
}
