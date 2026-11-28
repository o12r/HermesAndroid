package dev.oscar.hermes

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URLEncoder

/**
 * Downloads [name] from the PC outbox into the app cache (parallel, resumable) and returns the cached file's uri.
 * Nothing is written to Downloads here; the PC keeps the file until the user saves it ([Inbox.ack]).
 */
suspend fun Inbox.fetchToCache(ctx: Context, store: Store, name: String, key: String, progress: (Int) -> Unit = {}): Uri {
    val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    val tmp = MediaCache.fileFor(ctx, key)
    try {
        if (!tmp.exists() || File(tmp.path + ".parts").exists())
            Downloader.fetch(http, tmp, req = { req(store, "/outbox/$enc") ?: throw ApiException("PC relay not reachable") }, progress = progress)
        currentCoroutineContext().ensureActive()
    } catch (e: CancellationException) {
        // Cancelled by the user: drop the partial copy.
        tmp.delete(); File(tmp.path + ".parts").delete(); throw e
    }
    return MediaCache.uriFor(ctx, tmp)
}
