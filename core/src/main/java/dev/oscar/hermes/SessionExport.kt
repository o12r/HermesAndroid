package dev.oscar.hermes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** FileProvider authority; declared in core's AndroidManifest.xml, paths in res/xml/file_paths.xml. */
const val FILE_AUTHORITY = "dev.oscar.hermes.files"

/** A session as Markdown: a title, then **You** / **Hermes** blocks. Tool rows, internal rows and MEDIA lines are dropped. */
fun sessionMarkdown(title: String, msgs: List<Msg>): String {
    val sb = StringBuilder("# ").append(title.ifBlank { "Hermes session" }).append("\n\n")
    for (m in msgs) {
        if (m.role != "user" && m.role != "assistant") continue
        val clean = Internal.clean(m.role, m.content) ?: continue
        val text = clean.lines().filterNot { it.trimStart().startsWith("MEDIA:") }.joinToString("\n").trim()
        if (text.isEmpty()) continue
        sb.append(if (m.role == "user") "**You**" else "**Hermes**").append("\n\n").append(text).append("\n\n")
    }
    return sb.toString()
}

/** A file name safe on Android: no path separators or reserved characters, bounded length. */
fun safeExportName(title: String): String =
    title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(80).ifBlank { "session" }

/**
 * Fetches the whole history of [s] (profile [prof]) off the main thread, writes it to cache/exports/<title>.md
 * and opens the share sheet. The open chat is not touched.
 */
fun exportSessionMarkdown(vm: ChatViewModel, ctx: Context, scope: CoroutineScope, prof: String, s: Session) {
    val title = s.title ?: s.preview ?: s.id
    Toast.makeText(ctx, ctx.getString(R.string.export_working), Toast.LENGTH_SHORT).show()
    scope.launch {
        val file = try {
            withContext(Dispatchers.IO) {
                val msgs = vm.c.apiFor(prof).messages(s.id)
                val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                File(dir, safeExportName(title) + ".md").apply { writeText(sessionMarkdown(title, msgs)) }
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.export_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
            return@launch
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, FILE_AUTHORITY, file)
            val send = Intent(Intent.ACTION_SEND).setType("text/markdown")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("", uri)
            ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, ctx.getString(R.string.export_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
        }
    }
}
