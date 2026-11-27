package dev.oscar.hermes

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.net.URLEncoder

/** Downloads [name] into the phone's Downloads folder, then removes it from the PC. Returns the saved file's Uri. */
suspend fun Inbox.save(ctx: Context, store: Store, name: String, progress: (Int) -> Unit = {}): android.net.Uri {
    var saved: android.net.Uri? = null
    val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    // Parallel, resumable download into the cache first; a failed save into Downloads retries from this file.
    val dir = java.io.File(ctx.cacheDir, "inbox").apply { mkdirs() }
    val tmp = java.io.File(dir, java.security.MessageDigest.getInstance("SHA-1").digest(name.toByteArray())
        .joinToString("") { "%02x".format(it) })
    if (!tmp.exists() || java.io.File(tmp.path + ".parts").exists())
        Downloader.fetch(http, tmp, req = { req(store, "/outbox/$enc") ?: throw ApiException("PC relay not reachable") }, progress = progress)
    run {
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
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }.also { ctx.contentResolver.update(uri, it, null, null) }
            }
            saved = uri
        } catch (e: Exception) {
            ctx.contentResolver.delete(uri, null, null)
            throw e
        }
    }
    tmp.delete()
    // Saved on the phone: clear it from the PC. A failure here only leaves the file listed.
    runCatching { http.newCall(req(store, "/outbox/$enc")!!.newBuilder().delete().build()).execute().close() }
    return saved!!
}

