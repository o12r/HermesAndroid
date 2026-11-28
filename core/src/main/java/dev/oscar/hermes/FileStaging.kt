package dev.oscar.hermes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
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

    /** Paths this document may map to: externalstorage ("primary:Download/x"), downloads ("raw:/..."), MediaStore's _data column. */
    private fun candidates(ctx: Context, uri: Uri): List<String> {
        val out = ArrayList<String>()
        if (runCatching { DocumentsContract.isDocumentUri(ctx, uri) }.getOrDefault(false)) {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault("")
            if (uri.authority == "com.android.externalstorage.documents") {
                val vol = id.substringBefore(':'); val rel = id.substringAfter(':', "")
                if (rel.isNotEmpty()) out += if (vol.equals("primary", ignoreCase = true)) "/storage/emulated/0/$rel" else "/storage/$vol/$rel"
            } else if (id.startsWith("raw:")) {
                out += id.removePrefix("raw:")
            }
        }
        if (uri.scheme == "file") uri.path?.let { out += it }
        runCatching {   // MediaStore.MediaColumns.DATA, by name (deprecated constant)
            ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0)?.let { p -> out += p } }
        }
        return out
    }

    private fun readable(p: String): Boolean {
        val f = File(p)
        return f.isFile && runCatching { RandomAccessFile(f, "r").close() }.isSuccess
    }

    /** [draft] with \"FILE: <path>\" on its own last line. */
    fun withLine(draft: String, path: String): String = (if (draft.isBlank()) "" else draft.trimEnd() + "\n") + "FILE: " + path
}
