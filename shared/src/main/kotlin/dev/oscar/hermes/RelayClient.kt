package dev.oscar.hermes

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Authenticated calls to the PC relay's own endpoints (/vdisplay, /fs, /tabs, /elevate ...). Blocking: call from IO. */
class RelayClient(
    private val base: () -> String,
    private val key: () -> String?,
    /** Message for the exception thrown when no API key is set. */
    private val noKey: () -> String,
) {
    private val jsonType = "application/json".toMediaType()

    /** Shared client with the long (90 s) read timeout used by screen/config calls. */
    val http: OkHttpClient by lazy { OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build() }

    /** Short call; null when there is no key or the relay answered non-2xx. POST sends [body] (default "{}"). */
    fun json(method: String, path: String, body: String?): String? {
        val k = key() ?: return null
        val rb = Request.Builder().url(base().trimEnd('/') + path).header("Authorization", "Bearer $k")
        if (method == "POST") rb.post((body ?: "{}").toRequestBody(jsonType))
        return OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build().newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    /** Like [json] with an empty POST body and a 30 s read timeout. */
    fun call(method: String, path: String): String? {
        val k = key() ?: return null
        val rb = Request.Builder().url(base().trimEnd('/') + path).header("Authorization", "Bearer $k")
        if (method == "POST") rb.post("{}".toRequestBody(jsonType))
        return OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
            .newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    /** POST request with a JSON body; throws ApiException when no key is set. */
    fun request(path: String, body: JSONObject): Request {
        val k = key() ?: throw ApiException(noKey())
        return Request.Builder().url(base().trimEnd('/') + path).header("Authorization", "Bearer $k")
            .post(body.toString().toRequestBody(jsonType)).build()
    }
}
