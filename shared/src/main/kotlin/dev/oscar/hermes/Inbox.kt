package dev.oscar.hermes

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Files the agent sent from the PC (pc/relay/send-to-phone.py): listed here; each platform saves them its own way. */
object Inbox {
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    data class Item(val name: String, val size: Long, val time: Long, val session: String, val anchor: Long = 0L) {
        /** Identity of this outbox entry; the relay returns it again only if the file is re-sent. */
        val key: String get() = "$name|$time"
    }

    fun req(store: Store, path: String): Request? {
        val key = store.apiKey ?: return null
        val base = store.relayBase().trimEnd('/')
        if (!ApiClient.isTailscaleHost(java.net.URI(base).host ?: "") && base.startsWith("http:")) return null
        return Request.Builder().url(base + path).header("Authorization", "Bearer $key").build()
    }

    /** Files waiting on the PC relay. With [wait] > 0 and [known] keys it long-polls. Throws if the relay can't be reached. */
    fun list(store: Store, wait: Int = 0, known: Set<String> = emptySet()): List<Item> {
        val q = if (wait > 0) "?wait=$wait&known=" + URLEncoder.encode(known.joinToString(","), "UTF-8") else ""
        val r = req(store, "/outbox$q") ?: throw ApiException("PC relay not reachable")
        http.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("Inbox failed (HTTP ${resp.code})")
            val arr = JSONObject(resp.body!!.string()).optJSONArray("files") ?: return emptyList()
            return (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Item(it.getString("name"), it.optLong("size"), it.optLong("time"), it.optString("session"), it.optLong("anchor")) } }
        }
    }

    /** Removes [name] from the PC outbox once it is saved to the phone's Downloads. A failure only leaves the file listed. */
    fun ack(store: Store, name: String) {
        val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        runCatching { req(store, "/outbox/$enc")?.let { http.newCall(it.newBuilder().delete().build()).execute().close() } }
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
