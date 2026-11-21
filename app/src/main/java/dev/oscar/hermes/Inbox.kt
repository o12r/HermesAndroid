package dev.oscar.hermes

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Files the agent sent from the PC (pc/relay/send-to-phone.py): listed and saved to Downloads. */
object Inbox {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    data class Item(val name: String, val size: Long)

    private fun req(store: SecureStore, path: String): Request? {
        val key = store.apiKey ?: return null
        val base = store.relayBase().trimEnd('/')
        if (!ApiClient.isTailscaleHost(java.net.URI(base).host ?: "") && base.startsWith("http:")) return null
        return Request.Builder().url(base + path).header("Authorization", "Bearer $key").build()
    }

    /** Files waiting on the PC relay. Throws if the relay can't be reached. */
    fun list(store: SecureStore): List<Item> {
        val r = req(store, "/outbox") ?: throw ApiException("PC relay not reachable")
        http.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("Inbox failed (HTTP ${resp.code})")
            val arr = JSONObject(resp.body!!.string()).optJSONArray("files") ?: return emptyList()
            return (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Item(it.getString("name"), it.optLong("size")) } }
        }
    }

    /** Downloads [name] into the phone's Downloads folder, then removes it from the PC. Returns the saved name. */
    fun save(ctx: Context, store: SecureStore, name: String): String {
        val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val r = req(store, "/outbox/$enc") ?: throw ApiException("PC relay not reachable")
        http.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("Download failed (HTTP ${resp.code})")
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw ApiException("Couldn't create the file in Downloads")
            try {
                ctx.contentResolver.openOutputStream(uri)!!.use { out -> resp.body!!.byteStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }.also { ctx.contentResolver.update(uri, it, null, null) }
                }
            } catch (e: Exception) {
                ctx.contentResolver.delete(uri, null, null)
                throw e
            }
        }
        // Saved on the phone: clear it from the PC. A failure here only leaves the file listed.
        runCatching { http.newCall(req(store, "/outbox/$enc")!!.newBuilder().delete().build()).execute().close() }
        return name
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
