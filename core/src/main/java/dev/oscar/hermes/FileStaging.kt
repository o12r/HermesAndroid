package dev.oscar.hermes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.io.RandomAccessFile

/** Attach button: a picked file becomes a phone path on a FILE: line in the draft (no chip). Reads need all-files access. */
object FileStaging {
    /** True when this app has all-files access (MANAGE_EXTERNAL_STORAGE). */
    fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()

    /** Opens this app's all-files settings page; falls back to the general all-files list if that page is missing. */
    fun requestAllFiles(context: Context) {
        val app = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(app) }.isFailure)
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The real, readable phone path for the picked [uri], or null (no copy). Blocking: call off the main thread. */
    fun pathFor(ctx: Context, uri: Uri): String? = candidates(ctx, uri).firstOrNull { readable(it) }

    /**
     * Paths this document may map to, most direct first: externalstorage ("primary:Download/x"), downloads ("raw:/...",
     * "msf:123", plain ids), media documents ("image:123"), picker / MediaStore URIs ending in an _id, the URI's own _data,
     * and MediaStore's RELATIVE_PATH + DISPLAY_NAME. Never copies anything.
     */
    private fun candidates(ctx: Context, uri: Uri): List<String> {
        val out = ArrayList<String>()
        var mediaId: Long? = null
        if (runCatching { DocumentsContract.isDocumentUri(ctx, uri) }.getOrDefault(false)) {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault("")
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    val vol = id.substringBefore(':'); val rel = id.substringAfter(':', "")
                    if (rel.isNotEmpty()) out += if (vol.equals("primary", ignoreCase = true)) "/storage/emulated/0/$rel" else "/storage/$vol/$rel"
                }
                "com.android.providers.downloads.documents" -> {
                    val n = id.substringAfterLast(':').toLongOrNull()
                    if (id.startsWith("raw:")) {
                        out += id.removePrefix("raw:")
                    } else if (n != null) {
                        mediaId = n
                        for (base in listOf("content://downloads/all_downloads/", "content://downloads/public_downloads/"))
                            dataOf(ctx, Uri.parse(base + n))?.let { out += it }
                    }
                }
                "com.android.providers.media.documents" -> {
                    mediaId = id.substringAfter(':', "").toLongOrNull()
                }
                else -> {
                    if (id.startsWith("raw:")) out += id.removePrefix("raw:")
                }
            }
        }
        // Photo Picker / MediaStore URIs (content://media/picker/.../media/<id>, content://media/external/file/<id>) end in the _id.
        if (mediaId == null && (uri.authority == "media" || uri.authority?.contains("photopicker") == true))
            mediaId = uri.lastPathSegment?.toLongOrNull()
        if (uri.scheme == "file") uri.path?.let { out += it }
        dataOf(ctx, uri)?.let { out += it }
        mediaId?.let { out += mediaPaths(ctx, it) }
        return out
    }

    /** The _data column of [uri] (a provider or MediaStore row), or null when the provider gives none. */
    private fun dataOf(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** One MediaStore row by _id (images, video, audio and documents share the Files id space): its _data, then RELATIVE_PATH + DISPLAY_NAME on the primary volume. */
    private fun mediaPaths(ctx: Context, id: Long): List<String> = runCatching {
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf("_data", "relative_path", "_display_name"), "_id = ?", arrayOf(id.toString()), null
        )?.use { c ->
            val out = ArrayList<String>()
            if (c.moveToFirst()) {
                c.getString(0)?.let { out += it }
                val rel = c.getString(1)?.trim('/'); val name = c.getString(2)
                if (!rel.isNullOrEmpty() && !name.isNullOrEmpty()) out += "/storage/emulated/0/$rel/$name"
            }
            out
        } ?: emptyList<String>()
    }.getOrDefault(emptyList<String>())

    private fun readable(p: String): Boolean {
        val f = File(p)
        return f.isFile && runCatching { RandomAccessFile(f, "r").close() }.isSuccess
    }

    /** [draft] with \"FILE: <path>\" on its own last line. */
    fun withLine(draft: String, path: String): String = (if (draft.isBlank()) "" else draft.trimEnd() + "\n") + "FILE: " + path
}
