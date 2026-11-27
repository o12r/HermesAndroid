package dev.oscar.hermes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.UUID
import androidx.compose.runtime.Composable

data class PcStat(val cpu: Double?, val mem: Double?, val gpu: Double?, val ts: Long, val gpus: List<Pair<String, Double>> = emptyList())

/** Optional feature: CPU/memory/GPU graphs of the PC, shown at the top of the PC page ("pc.top" slot). */
class PcStatsFeature : UiFeature {
    override val id = "pcstats"
    lateinit var hub: ChatController
    val store get() = hub.store
    override fun attach(hub: ChatController) { this.hub = hub }
    @Composable override fun Slot(vm: ChatViewModel, name: String) { if (name == "pc.top") PcStatsBar(vm) }

    /** PC resource samples (CPU/mem/GPU %), polled by the PC page while it is visible; keeps the last 300 points. */
    var pcStats by mutableStateOf<List<PcStat>>(emptyList())
    suspend fun fetchPcStats(): PcStat? = withContext(Dispatchers.IO) {
        val key = store.apiKey ?: return@withContext null
        runCatching {
            hub.relayHttp.newCall(okhttp3.Request.Builder().url(store.relayBase().trimEnd('/') + "/pc/stats")
                .header("Authorization", "Bearer $key").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val j = JSONObject(r.body!!.string())
                    PcStat(j.optDoubleOrNull("cpu"), j.optDoubleOrNull("mem"), j.optDoubleOrNull("gpu"), j.optLong("ts", 0),
                        j.optJSONArray("gpus")?.let { a -> List(a.length()) { a.getJSONObject(it).let { g -> g.optString("name") to g.optDouble("util", 0.0) } } } ?: emptyList())
                }
        }.getOrNull()
    }
    fun addPcStat(s: PcStat) { pcStats = (pcStats + s).takeLast(300) }
}

val ChatController.pcs: PcStatsFeature get() = feature<PcStatsFeature>()!!
