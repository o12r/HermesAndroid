package dev.oscar.hermes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional feature: read-only Admin page (skills, memory, toolsets, dashboard plugins, usage) fed by the PC
 * relay's GET /dash/<path> endpoints, plus a top-bar button and a Settings group as entry points.
 */
class AdminFeature : UiFeature {
    override val id = "admin"
    lateinit var hub: ChatController
    private val api by lazy { AdminApi(hub) }

    /** Last response per (profile, /dash path), see [key]; kept so flipping between sections does not refetch. */
    val data = mutableStateMapOf<String, AdminResp>()
    var loadingPath by mutableStateOf<String?>(null)
        private set
    /** Bumped by refresh / entering the page; the page refetches on change. */
    var reloadKey by mutableStateOf(0)
        private set

    override fun attach(hub: ChatController) { this.hub = hub }
    override fun hasPage(kind: String) = kind == "admin"
    override fun pageTitleKey(kind: String) = if (kind == "admin") Str.admin else null
    override fun pageIcon(kind: String): ImageVector? = if (kind == "admin") Icons.Default.AdminPanelSettings else null
    override fun pageEntered(kind: String) { if (kind == "admin") reloadKey++ }
    override fun close() {}

    /** Cache key for one dashboard path of one profile (or of [ADMIN_ALL_PROFILES]). */
    fun key(path: String, profile: String) = profile + "|" + path

    /** Fetch one section of [profile] unless it is cached and already good. [ADMIN_ALL_PROFILES] aggregates usage. */
    internal suspend fun load(path: String, profile: String, force: Boolean = false) {
        val k = key(path, profile)
        if (!force && data[k]?.ok == true) return
        loadingPath = path
        try {
            data[k] = if (profile == ADMIN_ALL_PROFILES) usageAcrossProfiles() else api.get(path, profile)
        } finally { loadingPath = null }
    }

    /** analytics/usage of every profile, merged client-side; profiles that fail are skipped and listed under "skipped". */
    private suspend fun usageAcrossProfiles(): AdminResp {
        val names = hub.conn.profileNames().ifEmpty { listOf(hub.profile) }
        val parts = ArrayList<Pair<String, JSONObject>>()
        val skipped = ArrayList<String>()
        var lastError: AdminResp? = null
        for (p in names) {
            val r = api.get("analytics/usage", p)
            val o = if (r.ok) runCatching { r.obj() }.getOrNull() else null
            if (o != null) parts.add(Pair(p, o)) else { skipped.add(p); lastError = r }
        }
        if (parts.isEmpty()) return lastError ?: AdminResp(0, "", "")
        return AdminResp(200, mergeUsage(parts.map { it.second }, skipped).toString())
    }

    /** Uncached GET of a section for [profile]: the per-profile switch state in the toggle dialog. */
    internal suspend fun fetchFresh(path: String, profile: String): AdminResp = api.get(path, profile)

    /** name -> enabled for every item of one section of one profile; remembered so dialogs open instantly and later dialogs of the same profile need no fetch. */
    private val stateCache = HashMap<String, Map<String, Boolean>>()
    internal fun cachedStates(path: String, profile: String): Map<String, Boolean>? = stateCache["$path|$profile"]

    /** Fetches (and caches) the states; null on failure. */
    internal suspend fun fetchStates(path: String, profile: String): Pair<Map<String, Boolean>?, AdminResp> {
        val r = api.get(path, profile)
        if (!r.ok) return null to r
        val a = runCatching { r.arr() }.getOrNull() ?: return null to r
        val m = HashMap<String, Boolean>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> val n = o.optString("name"); if (n.isNotBlank()) m[n] = o.optBoolean("enabled") }
        stateCache["$path|$profile"] = m
        return m to r
    }
    internal fun bumpReload() { reloadKey++ }

    /**
     * Enables/disables one skill (skills/toggle) or toolset (tools/toolsets/<name>) on the PC for [profile].
     * On success the cached section of that profile is dropped, so the list refetches on screen.
     */
    internal suspend fun setEnabled(isSkill: Boolean, name: String, profile: String, enabled: Boolean, reload: Boolean = true): AdminResp {
        val r = if (isSkill) api.put("skills/toggle", profile, JSONObject().put("name", name).put("enabled", enabled))
            else api.put("tools/toolsets/" + name, profile, JSONObject().put("enabled", enabled))
        if (r.ok) {
            data.remove(key(if (isSkill) "skills" else "tools/toolsets", profile))
            (stateCache[(if (isSkill) "skills" else "tools/toolsets") + "|" + profile] as? MutableMap<String, Boolean>)?.put(name, enabled)
            if (reload) reloadKey++
        }
        return r
    }

    /** Forget everything and refetch (refresh button / pull). */
    fun refresh() { data.clear(); reloadKey++ }

    @Composable override fun Page(vm: ChatViewModel, kind: String) { AdminScreen(vm) }

    /** Offered as a pin (Settings long-press) for the top bar; shown there only while this module is on. */
    override fun pinItems() = listOf(PinItem("admin", R.string.admin, Icons.Default.AdminPanelSettings) { it.openPage("admin") })

    /** Settings group card, in the same style as the other Settings cards. */
    @Composable override fun Slot(vm: ChatViewModel, name: String) {
        if (name != "settings.groups") return
        SettingsGroupCard(vm, "admin", R.string.admin, Icons.Default.AdminPanelSettings, stringResource(R.string.admin_summary)) {
            vm.c.openPage("admin")
        }
    }
}

val ChatController.admin: AdminFeature get() = feature<AdminFeature>()!!

/** Usage-selector sentinels; profile names cannot contain '*', so they never clash with a real profile. */
internal const val ADMIN_CURRENT_PROFILE = "*current*"
internal const val ADMIN_ALL_PROFILES = "*all*"

private val USAGE_TOTAL_KEYS = listOf("total_input", "total_output", "total_cache_read", "total_reasoning",
    "total_estimated_cost", "total_actual_cost", "total_sessions", "total_api_calls")
private val DAILY_KEYS = listOf("input_tokens", "output_tokens", "cache_read_tokens", "reasoning_tokens",
    "estimated_cost", "actual_cost", "sessions", "api_calls")
private val MODEL_KEYS = listOf("input_tokens", "output_tokens", "estimated_cost", "sessions", "api_calls")
private val COST_KEYS = setOf("total_estimated_cost", "total_actual_cost", "estimated_cost", "actual_cost")

/** Adds one numeric field of [src] into [acc] (costs as double, counts as long). */
private fun addNum(acc: JSONObject, k: String, src: JSONObject) {
    if (k in COST_KEYS) acc.put(k, acc.optDouble(k, 0.0) + src.optDouble(k, 0.0))
    else acc.put(k, acc.optLong(k, 0L) + src.optLong(k, 0L))
}

/**
 * Sums analytics/usage responses in the same shape the usage page renders: totals summed, daily merged by day
 * (ascending), by_model merged by model (sorted by name). [skipped] profile names are kept for a note.
 */
internal fun mergeUsage(list: List<JSONObject>, skipped: List<String>): JSONObject {
    val totals = JSONObject()
    val days = java.util.TreeMap<String, JSONObject>()
    val models = java.util.TreeMap<String, JSONObject>()
    for (o in list) {
        o.optJSONObject("totals")?.let { t -> USAGE_TOTAL_KEYS.forEach { addNum(totals, it, t) } }
        o.optJSONArray("daily")?.let { a ->
            (0 until a.length()).forEach { i ->
                val d = a.optJSONObject(i) ?: return@forEach
                val day = d.optString("day")
                val acc = days.getOrPut(day) { JSONObject().put("day", day) }
                DAILY_KEYS.forEach { addNum(acc, it, d) }
            }
        }
        o.optJSONArray("by_model")?.let { a ->
            (0 until a.length()).forEach { i ->
                val m = a.optJSONObject(i) ?: return@forEach
                val name = m.optString("model")
                val acc = models.getOrPut(name) { JSONObject().put("model", name) }
                MODEL_KEYS.forEach { addNum(acc, it, m) }
            }
        }
    }
    val dailyArr = JSONArray()
    days.values.forEach { dailyArr.put(it) }
    val modelArr = JSONArray()
    models.values.forEach { modelArr.put(it) }
    val skippedArr = JSONArray()
    skipped.forEach { skippedArr.put(it) }
    return JSONObject()
        .put("period_days", list.firstOrNull()?.optInt("period_days", 30) ?: 30)
        .put("totals", totals)
        .put("daily", dailyArr)
        .put("by_model", modelArr)
        .put("skipped", skippedArr)
}
