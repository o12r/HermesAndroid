package dev.oscar.hermes

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-connection download into [dest]. The file is split into [parts] byte ranges fetched in parallel (HTTP Range);
 * progress per part is kept in "<dest>.parts", so an interrupted download resumes each part where it stopped.
 * Falls back to one plain stream when the server doesn't do ranges or the size is unknown.
 * [req] is called for every request so the caller can re-resolve the address (Tailscale <-> Wi-Fi).
 */
object Downloader {
    suspend fun fetch(http: OkHttpClient, dest: File, parts: Int = 4, req: () -> Request, progress: (Int) -> Unit = {}) {
        val meta = File(dest.path + ".parts")
        val total = probe(http, req())
        if (total <= 0) { meta.delete(); single(http, dest, req, progress); return }
        // Resume state: "total\nstart end done\n..." (done = bytes already written in that part).
        var ranges = runCatching {
            val l = meta.readLines()
            if (l.first().toLong() != total || dest.length() != total) null
            else l.drop(1).filter { it.isNotBlank() }.map { it.split(' ').map(String::toLong).let { (a, b, c) -> longArrayOf(a, b, c) } }
        }.getOrNull()
        if (ranges == null) {
            RandomAccessFile(dest, "rw").use { it.setLength(total) }
            val n = if (total < 1L shl 20) 1 else parts
            val step = (total + n - 1) / n
            ranges = (0 until n).map { i -> longArrayOf(i * step, minOf(total, (i + 1) * step) - 1, 0) }.filter { it[0] <= it[1] }
        }
        fun save() = synchronized(meta) { meta.writeText("$total\n" + ranges.joinToString("\n") { "${it[0]} ${it[1]} ${it[2]}" }) }
        save()
        val done = AtomicLong(ranges.sumOf { it[2] })
        coroutineScope {
            ranges.map { r ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    var attempt = 0
                    while (r[0] + r[2] <= r[1]) {
                        try {
                            val from = r[0] + r[2]
                            http.newCall(req().newBuilder().header("Range", "bytes=$from-${r[1]}").build()).execute().use { resp ->
                                if (resp.code != 206) throw ApiException("Download failed (HTTP ${resp.code})")
                                RandomAccessFile(dest, "rw").use { f ->
                                    f.seek(from)
                                    val src = resp.body!!.byteStream(); val buf = ByteArray(64 * 1024); var n = 0; var since = 0L
                                    while (r[0] + r[2] <= r[1] && src.read(buf, 0, minOf(buf.size.toLong(), r[1] - r[0] - r[2] + 1).toInt()).also { n = it } > 0) {
                                        f.write(buf, 0, n); r[2] += n
                                        progress((done.addAndGet(n.toLong()) * 100 / total).toInt())
                                        since += n; if (since >= 1L shl 20) { since = 0; save() }
                                    }
                                }
                            }
                            save(); attempt = 0
                        } catch (e: java.io.IOException) {
                            save()
                            if (++attempt > 8) throw ApiException("Download interrupted: ${e.message}")
                            delay(minOf(30_000L, 1500L shl (attempt - 1)))
                        }
                    }
                }
            }.awaitAll()
        }
        meta.delete()
    }

    /** Size if the server supports ranges, else -1. */
    private fun probe(http: OkHttpClient, r: Request): Long = runCatching {
        http.newCall(r.newBuilder().header("Range", "bytes=0-0").build()).execute().use { resp ->
            if (resp.code != 206) return@use -1L
            resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
        }
    }.getOrDefault(-1L)

    private suspend fun single(http: OkHttpClient, dest: File, req: () -> Request, progress: (Int) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                http.newCall(req()).execute().use { resp ->
                    if (!resp.isSuccessful) throw ApiException("Download failed (HTTP ${resp.code})")
                    val body = resp.body!!; val len = body.contentLength()
                    dest.outputStream().use { out ->
                        val src = body.byteStream(); val buf = ByteArray(64 * 1024); var n: Int; var got = 0L
                        while (src.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); got += n; if (len > 0) progress((got * 100 / len).toInt()) }
                    }
                }
                return
            } catch (e: java.io.IOException) {
                if (++attempt > 8) throw ApiException("Download interrupted: ${e.message}")
                delay(minOf(30_000L, 1500L shl (attempt - 1)))
            }
        }
    }
}
