package dev.oscar.hermes

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Random-access view of one picked file (Android: a ParcelFileDescriptor or a spooled cache copy; desktop: a plain file). */
interface FileReader : Closeable {
    /** Total bytes. */
    val size: Long
    /** Fill [buf] from absolute [pos]; -1 at EOF. Must be safe to call from several threads. */
    fun read(buf: ByteBuffer, pos: Long): Int
}

/** One file the user picked, independent of Android's Uri. */
interface AttachmentSource {
    val name: String
    /** Bytes when known, -1 otherwise. */
    val size: Long
    val mime: String
    /** JPEG bytes (longest side <= 1600 px) for a decodable picture; null = not a picture, send the file itself. */
    fun imageJpeg(): ByteArray?
    /** Open for chunked reading (spool to a temp file first if the provider only gives a pipe); the caller closes it. */
    fun open(): FileReader
}

/** Turns a picked file into an [Attachment]: pictures inline as JPEG, everything else uploaded to the PC inbox via the relay. */
class PcUploader(
    private val base: () -> String,
    private val key: () -> String?,
    private val s: (String, Array<out Any>) -> String,
    /** (file name, percent) while uploading; null when no upload is running. */
    private val onStatus: (String?, Int) -> Unit,
    private val encodeBase64: (ByteArray) -> String,
) {
    private var active = 0
    private val json = "application/json".toMediaType()

    suspend fun read(src: AttachmentSource): Attachment {
        if (src.mime.startsWith("image/")) {
            try {
                src.imageJpeg()?.let { return Attachment(src.name.substringBeforeLast('.') + ".jpg", true, "data:image/jpeg;base64," + encodeBase64(it)) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* not decodable: send the file itself */ }
        }
        return upload(src)
    }

    /** Chunked, parallel (4 connections x 4 MB) upload through the relay: /upload/init, PUT /upload/<id>?offset=N, /upload/<id>/done. */
    private suspend fun upload(src: AttachmentSource): Attachment {
        val k = key() ?: throw ApiException(s(Str.err_set_key, emptyArray()))
        val base = base().trimEnd('/')
        val name = src.name
        val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
        synchronized(this) { active++ }
        onStatus(name, 0)
        try {
            src.open().use { rd ->
                val size = rd.size
                fun call(rb: Request.Builder): String = http.newCall(rb.header("Authorization", "Bearer $k").build()).execute().use { r ->
                    if (r.code == 404) throw ApiException(s(Str.upload_relay_old, emptyArray()))
                    if (!r.isSuccessful) throw ApiException("upload: HTTP ${r.code} ${r.body?.string().orEmpty().take(200)}")
                    r.body?.string().orEmpty()
                }
                val init = JSONObject(call(Request.Builder().url("$base/upload/init").post(JSONObject().put("name", name).put("size", size).toString().toRequestBody(json))))
                val id = init.getString("id")
                val chunk = init.optLong("chunk", 4L shl 20).coerceAtLeast(1L shl 20)
                val sent = AtomicLong(0)
                val sem = Semaphore(4)
                coroutineScope {
                    (0 until size step chunk).map { off ->
                        async {
                            sem.withPermit {
                                val len = minOf(chunk, size - off).toInt()
                                val buf = ByteBuffer.allocate(len)
                                var pos = off
                                while (buf.hasRemaining()) { val n = rd.read(buf, pos); if (n < 0) break; pos += n }
                                val bytes = buf.array()
                                var attempt = 0
                                while (true) {
                                    try { call(Request.Builder().url("$base/upload/$id?offset=$off").put(bytes.toRequestBody("application/octet-stream".toMediaType()))); break }
                                    catch (e: ApiException) { throw e }
                                    catch (e: Exception) { if (++attempt >= 4) throw e; delay(500L * attempt) }
                                }
                                onStatus(name, (sent.addAndGet(len.toLong()) * 100 / maxOf(size, 1)).toInt())
                            }
                        }
                    }.awaitAll()
                }
                val path = JSONObject(call(Request.Builder().url("$base/upload/$id/done").post("{}".toRequestBody(json)))).getString("path")
                return Attachment(name, false, "", path)
            }
        } finally {
            val idle = synchronized(this) { active = (active - 1).coerceAtLeast(0); active == 0 }
            if (idle) onStatus(null, 0)
        }
    }
}
