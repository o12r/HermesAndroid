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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
class PcController(val hub: ChatController) {
    val store get() = hub.store
    val scope get() = hub.scope
    val platform get() = hub.platform
    val main get() = hub.main
    fun s(key: String, vararg a: Any): String = hub.s(key, *a)

    /** Files the agent sent to the phone: download progress (0-100) per file name, shown on its chat bubble. */
    val fileProgress = mutableStateMapOf<String, Int>()
    /** Phone copies of PC files by "session|name" (empty = known, not downloaded yet): drives the inline MEDIA cards. */
    val fileUris = mutableStateMapOf<String, String>()
    /** Downloads in flight by file name: [cancelDownload] stops one of them. */
    private val downloads = HashMap<String, kotlinx.coroutines.Deferred<String>>()
    /** Cancels a download: its HTTP call and partial copy are dropped, the card goes back to pending (no error shown). */
    fun cancelDownload(name: String) {
        fileProgress.remove(name)
        downloads.remove(name)?.cancel()
    }
    /** Fetches [name] into the app cache and shows it in the chat. Nothing is written to Downloads. Returns the cached file's uri. */
    suspend fun fetchInboxNow(name: String, session: String?, anchor: Long = 0L, key: String = "$name|${System.nanoTime()}"): String {
        fileProgress[name] = 0
        // Own Deferred per name, so cancelDownload() stops this download without cancelling the caller (e.g. inboxWatch).
        val dl = scope.async(Dispatchers.IO) { platform.fetchInbox(name, key) { p ->
                scope.launch(main) { if (name in fileProgress) fileProgress[name] = p } } }
        downloads[name] = dl
        val uri = try { dl.await() }
            finally { dl.cancel(); if (downloads[name] === dl) { downloads.remove(name); fileProgress.remove(name) } }
        val u = uri
        var found = false
        for (c in (hub.chats + hub.cur).distinct()) {
            val i = c.messages.indexOfFirst { it.role == "file" && it.text == name && it.file == null }
            if (i >= 0) { c.messages[i] = c.messages[i].copy(file = u); found = true }
        }
        val sid = outboxSession(session)
        fileUris["${sid.orEmpty()}|$name"] = u
        if (!found) (hub.chats.firstOrNull { it.id == sid } ?: hub.cur.takeIf { sid == null || it.id == sid })
            ?.takeIf { !Internal.mediaNamed(it.messages, name) }
            ?.let { ch -> hub.engine.placeByTime(ch, UiMsg("file", name, file = u, session = sid, ts = if (anchor > 0) anchor + 1 else System.currentTimeMillis())) }
        sid?.let { store.upsertSessionFile(it, name, u, if (anchor > 0) anchor + 1 else 0L) }
        return uri
    }

    /** The card's Save button (file not on the phone yet): fetch it into the cache if needed, then copy it to Downloads. */
    fun saveInbox(name: String, session: String?) = scope.launch {
        if (name in fileProgress) return@launch
        try { exportToDownloads(name, fetchInboxNow(name, session)) } catch (e: CancellationException) { throw e } catch (e: Exception) { hub.conn.fail(e) }
    }

    /** The Download button on a cached file: copy it to Downloads. */
    fun exportInbox(name: String, uri: String) = scope.launch {
        if (name in fileProgress) return@launch
        try { exportToDownloads(name, uri) } catch (e: CancellationException) { throw e } catch (e: Exception) { hub.conn.fail(e) }
    }

    /** Cache file -> the user's Downloads as [name], then the file is removed from the PC outbox. Returns the Downloads uri. */
    suspend fun exportToDownloads(name: String, uri: String): String {
        val saved = withContext(Dispatchers.IO) { platform.saveFromCache(uri, name).also { Inbox.ack(store, name) } }
        hub.status = platform.string("pf_downloaded", name)
        return saved
    }

    /** Chat a PC-sent file belongs to: its own session (kept in the store and shown when that chat is open), else the open chat. */
    fun outboxSession(s: String?): String? = s?.takeIf { it.isNotBlank() } ?: hub.cur.id

    fun showPendingFile(item: Inbox.Item) {
        val sid = outboxSession(item.session)
        if (fileUris["${sid.orEmpty()}|${item.name}"] == null) fileUris["${sid.orEmpty()}|${item.name}"] = ""
        (hub.chats.firstOrNull { it.id == sid } ?: hub.cur.takeIf { sid == null || it.id == sid })
            ?.takeIf { !Internal.mediaNamed(it.messages, item.name) }
            ?.let { ch -> hub.engine.placeByTime(ch, UiMsg("file", item.name, file = null, session = sid, ts = if (item.anchor > 0) item.anchor + 1 else System.currentTimeMillis())) }
        sid?.let { store.upsertSessionFile(it, item.name, "", if (item.anchor > 0) item.anchor + 1 else 0L) }
    }

    /** Real-time inbox: long-polls the PC relay; images are saved at once, other files appear as cards. */
    suspend fun inboxWatch() {
        var known: Set<String> = emptySet()
        while (true) {
            if (!store.hasKey) { delay(3000); continue }
            try {
                val items = withContext(Dispatchers.IO) { Inbox.list(store, wait = 25, known = known) }
                known = items.map { it.key }.toSet()
                for (item in items) {
                    if (store.inboxSeen(item.key)) continue
                    try {
                        if (isImageName(item.name)) fetchInboxNow(item.name, item.session, item.anchor, item.key) else showPendingFile(item)
                        store.markInboxSeen(item.key)
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw e
                        store.markInboxSeen(item.key) // the user cancelled this one: don't fetch it again
                    } catch (e: Exception) { delay(5000) }
                }
            } catch (e: Exception) { delay(5000) }
        }
    }


    suspend fun fsCall(op: String, path: String, keep: List<String> = emptyList()): JSONObject = withContext(Dispatchers.IO) {
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

    /** PC file -> the phone's Downloads folder (returns the saved file's uri). */
    suspend fun fsDownload(pcPath: String, name: String): String = withContext(Dispatchers.IO) {
        val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
        val http = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
        val url = store.relayBase().trimEnd('/') + "/fs/download?path=" + java.net.URLEncoder.encode(pcPath, "UTF-8")
        val tmp = java.io.File(platform.cacheDir, "pcfile-" + System.nanoTime()).also { it.parentFile?.mkdirs() }
        try {
            http.newCall(okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $key").build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(runCatching { JSONObject(r.body!!.string()).optString("error") }.getOrNull().orEmpty().ifBlank { "HTTP ${r.code}" })
                tmp.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            }
            platform.saveToDownloads(tmp, name)
        } finally { tmp.delete() }
    }

    /** Phone file -> a PC folder (throws ApiException("exists") when the name is taken and [overwrite] is false). */
    suspend fun fsUpload(name: String, open: () -> java.io.InputStream, folder: String, overwrite: Boolean) = withContext(Dispatchers.IO) {
        val key = store.apiKey ?: throw ApiException(s(Str.err_set_key))
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: okio.BufferedSink) { open().use { ins -> val buf = ByteArray(65536); while (true) { val n = ins.read(buf); if (n < 0) break; sink.write(buf, 0, n) } } }
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
    fun loadPcHome() { if (pcHome != null) return; scope.launch { pcHome = runCatching { fsJson("list", JSONObject().put("path", "")).optString("home").ifBlank { null } }.getOrNull() } }

    // ---------- attachments ----------
    /** Progress notice while a big file streams to the PC ("Uploading large file... 42%"); null when idle. */
    var uploadStatus by mutableStateOf<String?>(null)
    val uploader = PcUploader(
        base = { store.relayBase() }, key = { store.apiKey }, s = { k, a -> s(k, *a) },
        onStatus = { n, pct -> uploadStatus = if (n == null) null else s(Str.uploading_large, n, pct) },
        encodeBase64 = { java.util.Base64.getEncoder().encodeToString(it) })

    /** Reads each source on IO and queues it as an attachment; [makers] build the platform sources lazily. */
    fun addAttachments(makers: List<() -> AttachmentSource>) = scope.launch {
        if (!hub.hotfix.attachments) { hub.error = s(Str.attachments_disabled); return@launch }
        for (m in makers) {
            try { hub.attachments.add(withContext(Dispatchers.IO) { uploader.read(m()) }) } catch (e: CancellationException) { throw e } catch (e: Exception) { hub.error = e.message ?: e.toString() }
        }
    }

}
