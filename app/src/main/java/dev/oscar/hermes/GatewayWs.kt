package dev.oscar.hermes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JSON-RPC client for the Hermes desktop protocol (hermes-gateway-v1) via the PC's phone relay.
 * Same channel the desktop app uses, so desktop-created sessions accept turns and real slash commands run.
 */
class GatewayWs(
    relayUrl: String, profile: String, private val key: String,
    private val onEvent: (type: String, sessionId: String, payload: JSONObject) -> Unit,
    private val onServerRequest: (id: String, method: String, params: JSONObject) -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    private val url: String = run {
        val u = URI(relayUrl.trim().trimEnd('/'))
        if (u.scheme == "http" && !ApiClient.isTailscaleHost(u.host ?: ""))
            throw ApiException("Plain http is only allowed to Tailscale or private LAN addresses")
        relayUrl.trim().trimEnd('/').replaceFirst("http", "ws") + "/ws?profile=" + profile
    }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val ids = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private var ws: WebSocket? = null
    @Volatile var open = false; private set

    suspend fun connect() {
        val ready = CompletableDeferred<Unit>()
        ws = http.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer $key").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { open = true; ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { text.lineSequence().filter { it.isNotBlank() }.forEach(::handle) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = when (response?.code) {
                    401 -> "Wrong API key for the relay"
                    503 -> "Hermes gateway isn't running on the PC"
                    null -> "Can't reach the PC relay (:8643). Is Tailscale on and the relay running?"
                    else -> "Relay error HTTP ${response.code}"
                }
                fail(why); if (!ready.isCompleted) ready.completeExceptionally(ApiException(why, response == null))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(null) }
        })
        withTimeout(70_000) { ready.await() }
        call("client.capabilities", JSONObject().put("server_requests", true))
    }

    private fun fail(why: String?) {
        open = false
        pending.values.forEach { it.completeExceptionally(ApiException(why ?: "Connection closed", true)) }
        pending.clear(); onClosed(why)
    }

    private fun handle(line: String) {
        val f = runCatching { JSONObject(line) }.getOrNull() ?: return
        val method = f.optString("method")
        val id = f.opt("id")
        when {
            method == "event" -> {
                val p = f.optJSONObject("params") ?: return
                onEvent(p.optString("type"), p.optString("session_id"), p.optJSONObject("payload") ?: JSONObject())
            }
            method.isNotEmpty() && id != null -> onServerRequest(id.toString(), method, f.optJSONObject("params") ?: JSONObject())
            method.isNotEmpty() -> onEvent(method, "", f.optJSONObject("params") ?: JSONObject())
            id is Int -> pending.remove(id)?.complete(f)
        }
    }

    /** Returns `result`; throws ApiException with the server's reason on an error frame. */
    suspend fun call(method: String, params: JSONObject, timeoutMs: Long = 120_000): JSONObject {
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<JSONObject>(); pending[id] = d
        val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        if (ws?.send(frame.toString()) != true) { pending.remove(id); throw ApiException("Not connected to the PC relay", true) }
        val f = withTimeout(timeoutMs) { d.await() }
        f.optJSONObject("error")?.let { e ->
            val reason = e.optJSONObject("data")?.optString("reason")?.ifBlank { null } ?: e.optString("message")
            android.util.Log.w("HermesRpc", "$method failed: $reason (code ${e.optInt("code")})")  // no params/content logged
            throw ApiException(reason)
        }
        return f.optJSONObject("result") ?: JSONObject()
    }

    fun respond(id: String, result: JSONObject?, error: String? = null) {
        val f = JSONObject().put("jsonrpc", "2.0").put("id", id)
        if (error != null) f.put("error", JSONObject().put("code", -32601).put("message", error)) else f.put("result", result ?: JSONObject())
        ws?.send(f.toString())
    }

    fun close() { runCatching { ws?.close(1000, null) }; open = false }
}
