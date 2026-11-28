package dev.oscar.hermes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Admin page: read-only views of the PC dashboard (skills, memory, toolsets, plugins, usage).
 * Data comes from GET /dash/<path> on the relay, so a dashboard that is still starting shows a retry hint.
 */
@Composable
fun AdminScreen(vm: ChatViewModel) {
    val f = vm.c.features.filterIsInstance<AdminFeature>().firstOrNull() ?: return
    var section by rememberSaveable { mutableStateOf("skills") }
    var usageSel by rememberSaveable { mutableStateOf(ADMIN_ALL_PROFILES) }
    var logFile by rememberSaveable { mutableStateOf("agent") }
    var picked by remember { mutableStateOf<ToggleTarget?>(null) }
    val prof = vm.c.profile
    val usageProfile = if (usageSel == ADMIN_CURRENT_PROFILE) prof else usageSel
    Column(Modifier.fillMaxSize()) {
        HermesTopBar(vm, title = { Text(stringResource(R.string.admin), maxLines = 1, style = MaterialTheme.typography.titleMedium) },
            nav = { IconButton(onClick = { vm.c.leavePage() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            onRefresh = { f.refresh() })
        ChatTabs(vm, onList = true)
        Text(stringResource(R.string.admin_profile_line, prof), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SECTIONS.forEach { (id, label) ->
                FilterChip(selected = section == id, onClick = { section = id }, label = { Text(stringResource(label)) })
            }
        }
        if (section == "usage") ProfileChips(vm.c.conn.orderedProfiles.map { it.first }.ifEmpty { listOf(prof) }, prof, usageSel) { usageSel = it }
        if (section == "logs") LogFileChips(logFile) { logFile = it }
        Loaded(f, dashPath(section, logFile), if (section == "usage") usageProfile else prof) { r -> when (section) {
            "skills" -> SkillsList(r.arr()) { picked = ToggleTarget(true, it) }
            "memory" -> MemoryView(r.obj())
            "tools" -> ToolsetsList(r.arr()) { picked = ToggleTarget(false, it) }
            "plugins" -> PluginsList(r.arr())
            "cron" -> CronList(r.obj())
            "env" -> EnvList(r.obj())
            "mcp" -> McpList(r.obj())
            "logs" -> LogsView(r.obj())
            else -> UsageView(r.obj())
        } }
    }
    picked?.let { t -> ToggleDialog(f, t, vm.c.conn.orderedProfiles.map { it.first }.ifEmpty { listOf(prof) }) { picked = null } }
}

private val SECTIONS: List<Pair<String, Int>> = listOf(
    "skills" to R.string.admin_skills,
    "memory" to R.string.admin_memory,
    "tools" to R.string.admin_tools,
    "plugins" to R.string.admin_plugins,
    "usage" to R.string.admin_usage,
    "cron" to R.string.admin_cron,
    "env" to R.string.admin_env,
    "mcp" to R.string.admin_mcp,
    "logs" to R.string.admin_logs,
)

/** Log-file selector for the Logs section (allow-listed by the relay: agent, errors, gateway). */
@Composable
private fun LogFileChips(selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("agent" to R.string.admin_log_agent, "errors" to R.string.admin_log_errors, "gateway" to R.string.admin_log_gateway)
            .forEach { (id, label) ->
                FilterChip(selected = selected == id, onClick = { onPick(id) }, label = { Text(stringResource(label)) })
            }
    }
}

/** Usage profile selector: All profiles first (default, aggregated), then each profile. */
@Composable
private fun ProfileChips(names: List<String>, current: String, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == ADMIN_ALL_PROFILES, onClick = { onPick(ADMIN_ALL_PROFILES) },
            label = { Text(stringResource(R.string.admin_profile_all)) })
        names.forEach { p ->
            FilterChip(selected = selected == p || (selected == ADMIN_CURRENT_PROFILE && p == current),
                onClick = { onPick(p) }, label = { Text(p) })
        }
    }
}

/** Path of a section on the dashboard API (section id == path here). */
private fun dashPath(section: String, logFile: String = "agent"): String = when (section) {
    "tools" -> "tools/toolsets"
    "plugins" -> "dashboard/plugins"
    "usage" -> "analytics/usage"
    "cron" -> "cron/jobs"
    "mcp" -> "mcp/servers"
    "logs" -> "logs?file=" + logFile
    else -> section
}

/** Loading / error / 503 "dashboard still starting" handling around one section. */
@Composable
private fun Loaded(f: AdminFeature, path: String, profile: String, content: @Composable (AdminResp) -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(path, profile, f.reloadKey) { f.load(path, profile) }
    val r = f.data[f.key(path, profile)]
    val body = r?.body?.trimStart().orEmpty()
    when {
        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(stringResource(if (profile == ADMIN_ALL_PROFILES) R.string.admin_loading_all else R.string.admin_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        !r.ok -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (r.code == 503) stringResource(R.string.admin_starting)
                else stringResource(R.string.admin_error, r.error.ifBlank { "HTTP ${r.code}" }),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp))
            Button(onClick = { scope.launch { f.load(path, profile, force = true) } }) { Text(stringResource(R.string.admin_retry)) }
        }
        body.isEmpty() || (body[0] != '{' && body[0] != '[') -> EmptyBox()
        else -> content(r)
    }
}

@Composable
private fun EmptyBox() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(stringResource(R.string.admin_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Small pill for a boolean flag (on/off, locked, available, configured). */
@Composable
private fun Flag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.16f), contentColor = color, shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(start = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

@Composable
private fun Muted(text: String, maxLines: Int = 3) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

// ---------------------------------------------------------------- skills

@Composable
private fun SkillsList(a: JSONArray, onPick: (JSONObject) -> Unit) {
    val groups = remember(a) {
        val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        all.groupBy { it.optString("category") }.toSortedMap()
    }
    if (groups.isEmpty()) { EmptyBox(); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { (cat, list) ->
            item(key = "cat:" + cat) {
                Text(if (cat.isBlank()) stringResource(R.string.admin_category_other) else cat,
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
            items(list, key = { "skill:" + it.optString("name") }) { s -> SkillRow(s, onPick) }
        }
    }
}

@Composable
private fun SkillRow(s: JSONObject, onPick: (JSONObject) -> Unit) {
    val enabled = s.optBoolean("enabled")
    Card(Modifier.fillMaxWidth().clickable { onPick(s) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.optString("name"), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Flag(if (enabled) stringResource(R.string.admin_enabled) else stringResource(R.string.admin_disabled),
                    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                if (s.optBoolean("locked")) Flag(stringResource(R.string.admin_locked), MaterialTheme.colorScheme.tertiary)
            }
            val d = s.optString("description")
            if (d.isNotBlank()) Muted(d)
            val meta = buildList {
                s.optString("provenance").takeIf { it.isNotBlank() }?.let { add(it) }
                val used = s.optInt("usage", 0)
                if (used > 0) add(stringResource(R.string.admin_used_n, used))
            }.joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- skill / toolset switches (per profile)

/** The Skills or Tools row that was tapped: [isSkill] picks the PC routes, [item] is that row of the selected profile. */
private class ToggleTarget(val isSkill: Boolean, val item: JSONObject)

/** One profile's switch state in [ToggleDialog]: [enabled] is null while loading, when missing, or after a failed fetch. */
private data class ProfileToggle(
    val loading: Boolean = true, val enabled: Boolean? = null, val missing: Boolean = false,
    val busy: Boolean = false, val error: String = "", val toggleError: String = "")

/** Detail of one skill / toolset with a Switch per profile; every change asks first, then PUTs it to the PC. */
@Composable
private fun ToggleDialog(f: AdminFeature, target: ToggleTarget, profiles: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val name = target.item.text("name")
    val label = target.item.text("label").ifBlank { name }
    val path = if (target.isSkill) "skills" else "tools/toolsets"
    val states = remember(name) {
        mutableStateMapOf<String, ProfileToggle>().also { m ->
            profiles.forEach { p -> m[p] = f.cachedStates(path, p)?.let { c -> c[name]?.let { ProfileToggle(loading = false, enabled = it) } ?: ProfileToggle(loading = false, missing = true) } ?: ProfileToggle() }
        }
    }
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(name, profiles) {
        coroutineScope {
            profiles.forEach { p ->
                launch {
                    val (m, r) = f.fetchStates(path, p)
                    val cur = states[p]
                    if (cur?.busy == true) return@launch
                    states[p] = when {
                        m == null -> if (cur?.enabled != null) cur else ProfileToggle(loading = false, error = r.error.ifBlank { "HTTP ${r.code}" })
                        m[name] == null -> ProfileToggle(loading = false, missing = true)
                        else -> ProfileToggle(loading = false, enabled = m[name])
                    }
                }
            }
        }
    }
    fun commitAll(want: Boolean) {
        val targets = profiles.filter { states[it]?.missing != true && states[it]?.loading != true }
        targets.forEach { p -> states[p] = (states[p] ?: ProfileToggle(loading = false)).copy(busy = true, toggleError = "") }
        scope.launch {
            coroutineScope {
                targets.forEach { p ->
                    launch {
                        val r = f.setEnabled(target.isSkill, name, p, want, reload = false)
                        val now = states[p] ?: ProfileToggle(loading = false)
                        states[p] = now.copy(busy = false, enabled = if (r.ok) want else now.enabled,
                            toggleError = if (r.ok) "" else r.error.ifBlank { "HTTP ${r.code}" })
                    }
                }
            }
            f.bumpReload()
        }
    }
    fun commit(p: String, want: Boolean) {
        if (p == "*") { commitAll(want); return }
        val cur = states[p] ?: ProfileToggle(loading = false)
        states[p] = cur.copy(busy = true, toggleError = "")
        scope.launch {
            val r = f.setEnabled(target.isSkill, name, p, want)
            val now = states[p] ?: cur
            states[p] = now.copy(busy = false, enabled = if (r.ok) want else now.enabled,
                toggleError = if (r.ok) "" else r.error.ifBlank { "HTTP ${r.code}" })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val on = target.item.optBoolean("enabled")
                    Flag(if (on) stringResource(R.string.admin_enabled) else stringResource(R.string.admin_disabled),
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
                target.item.text("description").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                SectionTitle(stringResource(R.string.admin_profiles_switch))
                if (profiles.size > 1) {
                    val usable = profiles.mapNotNull { states[it] }.filter { !it.missing && it.error.isBlank() }
                    val ready = usable.isNotEmpty() && usable.none { it.loading || it.enabled == null }
                    val busyAny = usable.any { it.busy }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.admin_all_profiles), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (!ready || busyAny) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Switch(checked = usable.all { it.enabled == true }, onCheckedChange = { pending = "*" to it })
                    }
                }
                profiles.forEach { p ->
                    val st = states[p] ?: ProfileToggle()
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when {
                                st.loading || st.busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                st.missing -> Muted(stringResource(R.string.admin_not_listed), 1)
                                else -> Switch(checked = st.enabled == true, enabled = st.enabled != null,
                                    onCheckedChange = { pending = p to it })
                            }
                        }
                        if (st.error.isNotBlank()) Muted(stringResource(R.string.admin_error, st.error), 2)
                        if (st.toggleError.isNotBlank()) Muted(stringResource(R.string.admin_toggle_failed, st.toggleError), 2)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.admin_close)) } })
    pending?.let { (p, want) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.admin_confirm_title)) },
            text = { Text(stringResource(if (want) R.string.admin_confirm_on else R.string.admin_confirm_off, label, if (p == "*") stringResource(R.string.admin_all_profiles) else p)) },
            confirmButton = { TextButton(onClick = { pending = null; commit(p, want) }) { Text(stringResource(R.string.admin_confirm_ok)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

// ---------------------------------------------------------------- memory

@Composable
private fun MemoryView(o: JSONObject) {
    val providers = remember(o) { o.optJSONArray("providers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty() }
    val files = remember(o) { o.optJSONObject("builtin_files") ?: JSONObject() }
    val active = o.optString("active")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SectionTitle(stringResource(R.string.admin_providers)) }
        item {
            Muted(if (active.isBlank()) stringResource(R.string.admin_none_active)
            else stringResource(R.string.admin_active_provider, active), maxLines = 2)
        }
        items(providers, key = { "prov:" + it.optString("name") }) { p -> ProviderRow(p) }
        item { SectionTitle(stringResource(R.string.admin_builtin_files)) }
        val names = files.keys().asSequence().toList()
        if (names.isEmpty()) item { Muted(stringResource(R.string.admin_empty), 1) }
        items(names, key = { "file:" + it }) { k ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.admin_chars, files.optLong(k, 0L)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(p: JSONObject) {
    val available = p.optBoolean("available")
    val configured = p.optBoolean("configured")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.optString("name"), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Flag(if (available) stringResource(R.string.admin_available) else stringResource(R.string.admin_unavailable),
                    if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Flag(if (configured) stringResource(R.string.admin_configured) else stringResource(R.string.admin_unavailable),
                    if (configured) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)
            }
            val d = p.optString("description")
            if (d.isNotBlank()) Muted(d)
            val st = p.optString("status")
            if (st.isNotBlank()) Text(stringResource(R.string.admin_provider_status, st),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- toolsets

@Composable
private fun ToolsetsList(a: JSONArray, onPick: (JSONObject) -> Unit) {
    val list = remember(a) { (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    if (list.isEmpty()) { EmptyBox(); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { "toolset:" + it.optString("name") }) { o ->
            val enabled = o.optBoolean("enabled")
            val available = o.optBoolean("available")
            val configured = o.optBoolean("configured")
            val tools = o.optJSONArray("tools")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()
            Card(Modifier.fillMaxWidth().clickable { onPick(o) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.optString("label").ifBlank { o.optString("name") }, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Flag(if (enabled) stringResource(R.string.admin_enabled) else stringResource(R.string.admin_disabled),
                            if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Flag(if (available) stringResource(R.string.admin_available) else stringResource(R.string.admin_unavailable),
                            if (available) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                        Flag(if (configured) stringResource(R.string.admin_configured) else stringResource(R.string.admin_unavailable),
                            if (configured) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.optString("name") + " · " + o.optString("platform_label", o.optString("platform")),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.admin_tools_n, tools.size),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val d = o.optString("description")
                    if (d.isNotBlank()) Muted(d, 2)
                    if (tools.isNotEmpty()) Text(tools.joinToString(", "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- plugins

@Composable
private fun PluginsList(a: JSONArray) {
    val list = remember(a) { (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    if (list.isEmpty()) { EmptyBox(); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { "plugin:" + it.optString("name") }) { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.optString("label").ifBlank { o.optString("name") }, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.admin_version, o.optString("version")),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val d = o.optString("description")
                    if (d.isNotBlank()) Muted(d, 2)
                    val tab = o.optJSONObject("tab")?.optString("path").orEmpty()
                    if (tab.isNotBlank()) Text(stringResource(R.string.admin_plugin_tab, tab),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- usage

@Composable
private fun UsageView(o: JSONObject) {
    val totals = remember(o) { o.optJSONObject("totals") ?: JSONObject() }
    val daily = remember(o) { o.optJSONArray("daily") ?: JSONArray() }
    val byModel = remember(o) { o.optJSONArray("by_model") ?: JSONArray() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Muted(stringResource(R.string.admin_period, o.optInt("period_days", 30)), 1)
        o.optJSONArray("skipped")?.takeIf { it.length() > 0 }?.let { sk ->
            Muted(stringResource(R.string.admin_skipped, (0 until sk.length()).joinToString(", ") { sk.optString(it) }), 2)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle(stringResource(R.string.admin_totals))
                Row(Modifier.fillMaxWidth()) {
                    Stat(stringResource(R.string.admin_in), num(totals.optLong("total_input")), Modifier.weight(1f))
                    Stat(stringResource(R.string.admin_out), num(totals.optLong("total_output")), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Stat(stringResource(R.string.admin_cache), num(totals.optLong("total_cache_read")), Modifier.weight(1f))
                    Stat(stringResource(R.string.admin_cost), cost(totals.optDouble("total_estimated_cost", 0.0)), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Stat(stringResource(R.string.admin_sessions_count), totals.optLong("total_sessions").toString(), Modifier.weight(1f))
                    Stat(stringResource(R.string.admin_api_calls), totals.optLong("total_api_calls").toString(), Modifier.weight(1f))
                }
            }
        }
        DailyChart(daily)
        SectionTitle(stringResource(R.string.admin_by_model))
        (0 until byModel.length()).mapNotNull { byModel.optJSONObject(it) }.forEach { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.optString("model"), style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cost(m.optDouble("estimated_cost", 0.0)), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Muted(stringResource(R.string.admin_in_out, num(m.optLong("input_tokens")), num(m.optLong("output_tokens"))), 1)
                    Muted(stringResource(R.string.admin_usage_meta, m.optLong("sessions").toString(), m.optLong("api_calls").toString()), 1)
                }
            }
        }
        if (byModel.length() == 0) Muted(stringResource(R.string.admin_empty), 1)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

/** Stacked bar chart of the daily input/output tokens (theme colours only, so it works on the black AMOLED theme). */
@Composable
private fun DailyChart(a: JSONArray) {
    val days = remember(a) { (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    if (days.isEmpty()) return
    val maxV = days.maxOf { it.optLong("input_tokens") + it.optLong("output_tokens") }.coerceAtLeast(1L)
    val inColor = MaterialTheme.colorScheme.secondary
    val outColor = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.admin_daily))
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val n = days.size
                val slot = size.width / n
                val w = (slot * 0.62f).coerceAtLeast(1.5f)
                listOf(0f, 0.5f, 1f).forEach { fr ->
                    val y = (size.height - 1f) * fr
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                days.forEachIndexed { i, d ->
                    val outV = d.optLong("output_tokens")
                    val inV = d.optLong("input_tokens")
                    val hOut = size.height * (outV.toFloat() / maxV)
                    val hIn = size.height * (inV.toFloat() / maxV)
                    val x = i * slot + (slot - w) / 2f
                    if (hOut > 0f) drawRect(outColor, Offset(x, size.height - hOut), Size(w, hOut))
                    if (hIn > 0f) drawRect(inColor, Offset(x, size.height - hOut - hIn), Size(w, hIn))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(days.first().optString("day"), style = MaterialTheme.typography.labelSmall, color = label)
                Spacer(Modifier.weight(1f))
                Text(days.last().optString("day"), style = MaterialTheme.typography.labelSmall, color = label)
            }
        }
    }
}

/** 1234 -> "1.2k", 1500000 -> "1.5M" (locale-independent). */
private fun num(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

private fun cost(d: Double): String = "$" + String.format(Locale.US, "%.3f", d)

/** optString that yields "" for JSON null (optString would give the text "null"). */
private fun JSONObject.text(k: String): String = if (isNull(k)) "" else optString(k)

private fun JSONObject.objects(k: String): List<JSONObject> =
    optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

// ---------------------------------------------------------------- cron (read-only)

@Composable
private fun CronList(o: JSONObject) {
    val list = remember(o) { o.objects("jobs") }
    if (list.isEmpty()) { EmptyBox(); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { "cron:" + it.text("profile") + ":" + it.text("id") }) { j ->
            val enabled = j.optBoolean("enabled")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(j.text("name").ifBlank { j.text("id") }, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Flag(if (enabled) stringResource(R.string.admin_enabled) else stringResource(R.string.admin_disabled),
                            if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                    j.text("schedule_display").takeIf { it.isNotBlank() }?.let { Muted(stringResource(R.string.admin_schedule, it), 1) }
                    j.text("next_run_at").takeIf { it.isNotBlank() }?.let { Muted(stringResource(R.string.admin_next_run, it), 1) }
                    j.text("last_run_at").takeIf { it.isNotBlank() }?.let { Muted(stringResource(R.string.admin_last_run, it), 1) }
                    val meta = buildList {
                        j.text("last_status").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.admin_last_status, it)) }
                        j.text("profile").takeIf { it.isNotBlank() }?.let { add(it) }
                    }.joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- env (names and is-set only)

@Composable
private fun EnvList(o: JSONObject) {
    // The relay strips values; only NAME, is_set and labels arrive here.
    val list = remember(o) { o.objects("vars").sortedWith(compareByDescending<JSONObject> { it.optBoolean("is_set") }.thenBy { it.text("name") }) }
    if (list.isEmpty()) { EmptyBox(); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Muted(stringResource(R.string.admin_env_hidden), 2) }
        items(list, key = { "env:" + it.text("name") }) { v ->
            val set = v.optBoolean("is_set")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(v.text("name"), style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Flag(if (set) stringResource(R.string.admin_env_set) else stringResource(R.string.admin_env_unset),
                            if (set) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                    val label = v.text("provider_label").ifBlank { v.text("category") }
                    if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val d = v.text("description")
                    if (d.isNotBlank()) Muted(d, 2)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- MCP servers (no commands, URLs, args or env)

@Composable
private fun McpList(o: JSONObject) {
    val list = remember(o) { o.objects("servers") }
    if (list.isEmpty()) { EmptyBox(); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { "mcp:" + it.text("name") }) { s ->
            val enabled = s.optBoolean("enabled")
            val tools = s.optJSONArray("tools")?.let { t -> (0 until t.length()).map { t.optString(it) } }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.text("name"), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Flag(if (enabled) stringResource(R.string.admin_enabled) else stringResource(R.string.admin_disabled),
                            if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                    val meta = buildList {
                        s.text("transport").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.admin_mcp_transport, it)) }
                        s.text("source").takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.admin_mcp_source, it)) }
                        s.text("auth").takeIf { it.isNotBlank() }?.let { add(it) }
                        tools?.let { add(stringResource(R.string.admin_tools_n, it.size)) }
                    }.joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!tools.isNullOrEmpty()) Muted(tools.joinToString(", "), 2)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- logs (bounded tail)

@Composable
private fun LogsView(o: JSONObject) {
    val lines = remember(o) { o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }
    if (lines.isEmpty()) { EmptyBox(); return }
    val trimmed = o.optBoolean("truncated")
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        item {
            Muted(stringResource(R.string.admin_log_lines, lines.size), 1)
            if (trimmed) Muted(stringResource(R.string.admin_log_trimmed), 1)
        }
        items(lines) { line ->
            Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}
