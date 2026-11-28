package dev.oscar.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private val CTX_FIELDS = listOf("compression_model" to R.string.ctx_comp_model, "compression_provider" to R.string.ctx_comp_provider,
    "context_length" to R.string.ctx_length, "threshold" to R.string.ctx_threshold,
    "target_ratio" to R.string.ctx_target, "protect_last_n" to R.string.ctx_keep_last)

/** Context / compression settings as inline fields (replaces the old dialog). Save waits for the PC's answer. */
@Composable
fun ContextSettingsGroup(vm: ChatViewModel) {
    val cur = vm.c.proj.ctxSettings
    val edit = remember(cur) { mutableStateMapOf<String, String>().apply {
        CTX_FIELDS.forEach { (k, _) -> put(k, cur?.opt(k)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()) } } }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.c.proj.loadCtxSettings(); if (vm.c.proj.modelOptions == null) vm.c.proj.loadMeta() }
    LaunchedEffect(vm.c.proj.ctxBusy) { if (saving && !vm.c.proj.ctxBusy) saving = false }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.ctx_all_profiles), style = MaterialTheme.typography.bodySmall)
        if (vm.c.proj.ctxBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.c.proj.ctxError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (cur != null) ModelPickerField(vm, edit["compression_provider"]?.takeIf { it.isNotBlank() }.let { it to edit["compression_model"]?.takeIf { m -> m.isNotBlank() } },
            stringResource(R.string.ctx_comp_model)) { p, m -> edit["compression_provider"] = p.orEmpty(); edit["compression_model"] = m.orEmpty() }
        if (cur != null) CTX_FIELDS.filter { it.first != "compression_model" && it.first != "compression_provider" }.forEach { (k, label) ->
            OutlinedTextField(edit[k].orEmpty(), { edit[k] = it }, label = { Text(stringResource(label)) }, singleLine = true,
                enabled = !vm.c.proj.ctxBusy, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = if (k.startsWith("compression_")) KeyboardOptions.Default
                    else KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Button(enabled = cur != null && !vm.c.proj.ctxBusy, onClick = {
            val o = JSONObject()
            CTX_FIELDS.forEach { (k, _) -> val v = edit[k].orEmpty().trim()
                val old = cur?.opt(k)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
                if (v != old) o.put(k, if (v.isEmpty()) JSONObject.NULL else v) }
            if (o.length() > 0) { saving = true; vm.c.proj.loadCtxSettings(o) }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
    }
}

/** Read a models entry ({model, provider}) as (provider, model). */
private fun entryOf(o: JSONObject?): Pair<String?, String?> = o?.let {
    it.opt("provider").takeIf { p -> p != JSONObject.NULL }?.toString()?.takeIf { v -> v.isNotBlank() } to
        it.opt("model").takeIf { m -> m != JSONObject.NULL }?.toString()?.takeIf { v -> v.isNotBlank() }
} ?: (null to null)

/**
 * Default models shared by every profile: main, delegation (sub-agent), and the auxiliary tasks the PC reports.
 * The auxiliary task list comes from the GET response, so it renders whatever the PC has.
 */
@Composable
fun ModelSettingsGroup(vm: ChatViewModel) {
    val cur = vm.c.proj.modelSettings
    LaunchedEffect(vm.c.store.profile) { vm.c.proj.loadModelSettings(); vm.c.proj.loadCtxSettings(); if (vm.c.proj.modelOptions == null) vm.c.proj.loadMeta() }
    // Compression model lives in the context settings; shown here too (unless the PC already lists it as an auxiliary task).
    val ctx = vm.c.proj.ctxSettings
    fun ctxStr(k: String) = ctx?.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
    var comp by remember(ctx) { mutableStateOf(ctxStr("compression_provider") to ctxStr("compression_model")) }
    val compInAux = cur?.optJSONObject("auxiliary")?.has("compression") == true
    // Local editable entries keyed by slot ("main", "delegation", "aux:<task>"), value = (provider, model).
    val slots = remember(cur) { mutableStateMapOf<String, Pair<String?, String?>>().apply {
        put("main", entryOf(cur?.optJSONObject("main")))
        put("delegation", entryOf(cur?.optJSONObject("delegation")))
        cur?.optJSONObject("auxiliary")?.let { aux ->
            aux.keys().forEach { t -> put("aux:$t", entryOf(aux.optJSONObject(t))) }
        }
    } }
    fun effOf(o: JSONObject?) = o?.opt("reasoning_effort")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
    val effs = remember(cur) { mutableStateMapOf<String, String>().apply {
        put("main", effOf(cur?.optJSONObject("main")).ifBlank { vm.c.store.effortFor(vm.c.store.profile) })
        put("delegation", effOf(cur?.optJSONObject("delegation")))
        put("aux:compression", effOf(cur?.optJSONObject("auxiliary")?.optJSONObject("compression")))
        cur?.optJSONObject("auxiliary")?.let { aux -> aux.keys().forEach { t -> put("aux:$t", effOf(aux.optJSONObject(t))) } }
    } }
    val defLbl = stringResource(R.string.default_)
    @Composable fun Row2(slot: String, label: String, picker: Pair<String?, String?>?, onPick: (String?, String?) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1.4f)) { ModelPickerField(vm, picker, label, onPick) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.effort_short), style = MaterialTheme.typography.bodyMedium)
                ChoiceField(effs[slot].orEmpty(), SecureStore.EFFORTS.map { it to it.ifBlank { defLbl } }) { e ->
                    effs[slot] = e; if (slot == "main") vm.c.store.setEffortFor(vm.c.store.profile, e) }
            }
        }
    }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(vm.c.proj.modelBusy) { if (saving && !vm.c.proj.modelBusy) saving = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.models_this_profile, vm.c.store.profile.ifBlank { "default" }), style = MaterialTheme.typography.bodySmall)
        if (vm.c.proj.modelBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.c.proj.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (cur != null) {
            Row2("main", stringResource(R.string.model_main), slots["main"]) { p, m -> slots["main"] = p to m }
            Row2("delegation", stringResource(R.string.model_delegation), slots["delegation"]) { p, m -> slots["delegation"] = p to m }
            if (ctx != null && !compInAux) Row2("aux:compression", stringResource(R.string.ctx_comp_model), comp) { p, m -> comp = p to m }
            val auxKeys = slots.keys.filter { it.startsWith("aux:") }.sorted()
            if (auxKeys.isNotEmpty()) Text(stringResource(R.string.model_auxiliary), style = MaterialTheme.typography.titleSmall)
            auxKeys.forEach { k ->
                Row2(k, k.removePrefix("aux:"), slots[k]) { p, m -> slots[k] = p to m }
            }
            fun doSave(all: Boolean) {
                fun putEntry(slot: String, entry: Pair<String?, String?>?): JSONObject {
                    val e = JSONObject()
                    e.put("model", entry?.second?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    e.put("provider", entry?.first?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    e.put("reasoning_effort", effs[slot]?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    return JSONObject().put(slot, e)
                }
                val o = JSONObject()
                o.put("main", putEntry("main", slots["main"]).getJSONObject("main"))
                o.put("delegation", putEntry("delegation", slots["delegation"]).getJSONObject("delegation"))
                val aux = JSONObject()
                slots.keys.filter { it.startsWith("aux:") }.forEach { k -> aux.put(k.removePrefix("aux:"), putEntry(k, slots[k]).getJSONObject(k)) }
                if (!compInAux && ctx != null) aux.put("compression", JSONObject().put("reasoning_effort", effs["aux:compression"]?.takeIf { it.isNotBlank() } ?: JSONObject.NULL))
                o.put("auxiliary", aux)
                saving = true; vm.c.proj.loadModelSettings(o, vm.c.store.profile, all)
                if (ctx != null && !compInAux && (comp.first != ctxStr("compression_provider") || comp.second != ctxStr("compression_model")))
                    vm.c.proj.loadCtxSettings(JSONObject().put("compression_provider", comp.first ?: JSONObject.NULL).put("compression_model", comp.second ?: JSONObject.NULL))
            }
            var confirmAll by remember { mutableStateOf(false) }
            Button(enabled = !vm.c.proj.modelBusy, onClick = { doSave(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
            OutlinedButton(enabled = !vm.c.proj.modelBusy, onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_apply_all)) }
            if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false }, title = { Text(stringResource(R.string.models_apply_all)) },
                text = { Text(stringResource(R.string.models_apply_all_confirm)) },
                confirmButton = { TextButton(onClick = { confirmAll = false; doSave(true) }) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.cancel)) } })
        }
    }
}

/** (i) button that opens a short explanation dialog. */
@Composable
fun InfoIcon(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Default.Info, stringResource(R.string.deleg_info_open), Modifier.size(18.dp))
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.deleg_info_close)) } })
}

/** Outlined button over a dropdown. options = (value, label). */
@Composable
fun ChoiceField(current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.first == current }?.second ?: current, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(v) },
                    trailingIcon = { if (v == current) Icon(Icons.Default.Check, null) })
            }
        }
    }
}

/** Global default thinking effort (phone side): applies to every chat/profile with no effort of its own. Saved at once. */
@Composable
fun EffortDefaultField(vm: ChatViewModel) {
    var eff by remember { mutableStateOf(vm.c.store.defaultEffort()) }
    val def = stringResource(R.string.default_)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.default_effort), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            InfoIcon(stringResource(R.string.default_effort), stringResource(R.string.default_effort_info))
        }
        ChoiceField(eff, SecureStore.EFFORTS.map { it to it.ifBlank { def } }) { e -> eff = e; vm.c.store.setDefaultEffort(e) }
    }
}

/** Advanced sub-agent (delegation) settings: one set for every profile, written on the PC through the relay. */
@Composable
fun DelegationSettingsGroup(vm: ChatViewModel) {
    val cur = vm.c.proj.delegSettings
    LaunchedEffect(Unit) { vm.c.proj.loadDelegSettings() }
    val def = stringResource(R.string.default_)
    val fields = listOf("max_concurrent_children" to R.string.deleg_max_children,
        "max_spawn_depth" to R.string.deleg_max_depth, "child_timeout_seconds" to R.string.deleg_child_timeout)
    val edit = remember(cur) { mutableStateMapOf<String, String>().apply {
        fields.forEach { (k, _) -> put(k, cur?.opt(k)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()) } } }
    var effort by remember(cur) { mutableStateOf(cur?.opt("reasoning_effort")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()) }
    val badMsg = stringResource(R.string.deleg_bad_number)
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(vm.c.proj.delegBusy) { if (saving && !vm.c.proj.delegBusy) saving = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.deleg_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            InfoIcon(stringResource(R.string.deleg_title), stringResource(R.string.deleg_info))
        }
        Text(stringResource(R.string.deleg_all_profiles), style = MaterialTheme.typography.bodySmall)
        if (vm.c.proj.delegBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.c.proj.delegError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (cur != null) {
            fields.forEach { (k, label) ->
                OutlinedTextField(edit[k].orEmpty(), { edit[k] = it }, label = { Text(stringResource(label)) }, singleLine = true,
                    enabled = !vm.c.proj.delegBusy, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Text(stringResource(R.string.deleg_effort), style = MaterialTheme.typography.bodyMedium)
            ChoiceField(effort, SecureStore.EFFORTS.map { it to it.ifBlank { def } }) { effort = it }
            Button(enabled = !vm.c.proj.delegBusy, onClick = {
                val o = JSONObject().put("reasoning_effort", if (effort.isBlank()) JSONObject.NULL else effort)
                var bad = false
                fields.forEach { (k, _) ->
                    val v = edit[k].orEmpty().trim()
                    if (v.isEmpty()) { o.put(k, JSONObject.NULL); return@forEach }
                    val n: Any? = if (k == "child_timeout_seconds") v.toDoubleOrNull() else v.toIntOrNull()
                    if (n == null) bad = true else o.put(k, n)
                }
                if (bad) { vm.c.proj.delegError = badMsg; return@Button }
                saving = true; vm.c.proj.loadDelegSettings(o)
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        }
    }
}

private val ADV_NO_TIER = ""

/** Advanced sub-agent routing of one profile (mode, model routes, profile routes), written on the PC through the relay. */
@Composable
fun AdvancedDelegationGroup(vm: ChatViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text((if (open) "\u25BE  " else "\u25B8  ") + stringResource(R.string.advdeleg_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    }
    if (open) AdvancedDelegationBody(vm)
}

@Composable
private fun AdvancedDelegationBody(vm: ChatViewModel) {
    val p = vm.c.proj
    val cur = p.advDeleg
    val defLbl = stringResource(R.string.default_)
    val noneLbl = stringResource(R.string.advdeleg_none)
    var sel by remember { mutableStateOf(vm.c.store.profile) }
    LaunchedEffect(sel) { p.loadAdvDeleg(sel) }
    val ready = cur != null && cur.optString("profile") == sel
    val others = cur?.optJSONArray("others")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val allProfiles = listOf(sel) + others.filter { it != sel }
    val effortKeys = cur?.optJSONArray("efforts")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.takeIf { it.isNotEmpty() }
        ?: listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
    val effortOpts = listOf("" to defLbl) + effortKeys.map { it to it }
    val tierOpts = listOf(ADV_NO_TIER to defLbl, "normal" to stringResource(R.string.advdeleg_tier_normal),
        "fast" to stringResource(R.string.advdeleg_tier_fast), "ultrafast" to stringResource(R.string.advdeleg_tier_ultrafast))
    val mm = cur?.optJSONObject("model_map")
    val pr = cur?.optJSONObject("profile_routes")
    var mode by remember(cur) { mutableStateOf(cur?.optString("mode").orEmpty().ifBlank { "default" }) }
    val routes = remember(cur) { mutableStateListOf<JSONObject>().apply {
        mm?.optJSONArray("options")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(JSONObject(it.toString())) } }
    } }
    var routeDefault by remember(cur) { mutableStateOf(mm?.optString("default").orEmpty()) }
    val profs = remember(cur) { mutableStateMapOf<String, String>().apply {
        pr?.optJSONArray("profiles")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { put(it.optString("name"), it.optString("description")) } }
    } }
    var profDefault by remember(cur) { mutableStateOf(pr?.optString("default").orEmpty()) }
    var dlg by remember { mutableStateOf<Pair<Int, JSONObject>?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    fun body(all: Boolean): JSONObject {
        val opts = JSONArray()
        routes.forEach { opts.put(it) }
        val names = routes.map { it.optString("name") }
        val profArr = JSONArray()
        val profNames = profs.keys.toList()
        profNames.forEach { n -> profArr.put(JSONObject().put("name", n).put("description", profs[n].orEmpty())) }
        return JSONObject().put("mode", mode)
            .put("model_map", JSONObject().put("options", opts).put("default", if (routeDefault in names) routeDefault else ""))
            .put("profile_routes", JSONObject().put("profiles", profArr).put("default", if (profDefault in profNames) profDefault else ""))
            .put("all_profiles", all)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.advdeleg_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            InfoIcon(stringResource(R.string.advdeleg_title), stringResource(R.string.advdeleg_info))
        }
        Text(stringResource(R.string.advdeleg_profile), style = MaterialTheme.typography.bodyMedium)
        ChoiceField(sel, allProfiles.map { it to it }) { sel = it }
        if (p.advBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        p.advError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (ready) {
            Text(stringResource(R.string.advdeleg_mode), style = MaterialTheme.typography.bodyMedium)
            ChoiceField(mode, listOf("default" to stringResource(R.string.advdeleg_mode_default),
                "model_map" to stringResource(R.string.advdeleg_mode_model), "profile_routes" to stringResource(R.string.advdeleg_mode_profile))) { mode = it }
            if (mode == "model_map") {
                Text(stringResource(R.string.advdeleg_routes), style = MaterialTheme.typography.titleSmall)
                if (routes.isEmpty()) Text(stringResource(R.string.advdeleg_no_routes), style = MaterialTheme.typography.bodySmall)
                routes.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.optString("name"), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.optString("description"), style = MaterialTheme.typography.bodySmall)
                            Text(listOf(r.optString("provider"), r.optString("model")).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { dlg = i to JSONObject(r.toString()) }) { Text(stringResource(R.string.advdeleg_edit)) }
                        TextButton(onClick = {
                            if (routeDefault == r.optString("name")) routeDefault = ""
                            routes.removeAt(i)
                        }) { Text(stringResource(R.string.advdeleg_delete)) }
                    }
                }
                OutlinedButton(onClick = { dlg = -1 to JSONObject() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.advdeleg_add_route)) }
                Text(stringResource(R.string.advdeleg_default_route), style = MaterialTheme.typography.bodyMedium)
                ChoiceField(routeDefault, listOf("" to noneLbl) + routes.map { it.optString("name") to it.optString("name") }) { routeDefault = it }
            }
            if (mode == "profile_routes") {
                Text(stringResource(R.string.advdeleg_profiles), style = MaterialTheme.typography.titleSmall)
                allProfiles.forEach { n ->
                    val on = n in profs
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = { c ->
                            if (c) { profs[n] = profs[n] ?: "" } else { profs.remove(n); if (profDefault == n) profDefault = "" }
                        })
                        Text(n, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (on) OutlinedTextField(profs[n].orEmpty(), { profs[n] = it },
                        label = { Text(stringResource(R.string.advdeleg_profile_desc)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.advdeleg_profile_default), style = MaterialTheme.typography.bodyMedium)
                ChoiceField(profDefault, listOf("" to noneLbl) + profs.keys.toList().map { it to it }) { profDefault = it }
            }
            Text(stringResource(R.string.advdeleg_save_note), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !p.advBusy, onClick = { p.loadAdvDeleg(sel, body(false)) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
            OutlinedButton(enabled = !p.advBusy, onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.advdeleg_apply_all)) }
        }
    }
    if (ready) {
        dlg?.let { (idx, start) ->
            AdvRouteDialog(vm, start, effortOpts, tierOpts, onDone = { r ->
                if (idx < 0) routes.add(r) else {
                    val old = routes[idx].optString("name")
                    routes[idx] = r
                    if (routeDefault == old) routeDefault = r.optString("name")
                }
                dlg = null
            }, onDismiss = { dlg = null })
        }
        if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.advdeleg_apply_all_title)) },
            text = { Text(stringResource(R.string.advdeleg_apply_all_text)) },
            confirmButton = { TextButton(onClick = { confirmAll = false; p.loadAdvDeleg(sel, body(true)) }) { Text(stringResource(R.string.advdeleg_apply)) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Add/edit one model route. Fields the phone does not show (base_url, api_key, ...) are kept from [start]. */
@Composable
private fun AdvRouteDialog(vm: ChatViewModel, start: JSONObject, effortOpts: List<Pair<String, String>>,
    tierOpts: List<Pair<String, String>>, onDone: (JSONObject) -> Unit, onDismiss: () -> Unit) {
    val isNew = start.length() == 0
    var name by remember { mutableStateOf(start.optString("name")) }
    var desc by remember { mutableStateOf(start.optString("description")) }
    var pair by remember { mutableStateOf(start.optString("provider").ifBlank { null } to start.optString("model").ifBlank { null }) }
    var effort by remember { mutableStateOf(start.optString("reasoning_effort")) }
    var tier by remember { mutableStateOf(start.optString("service_tier")) }
    var err by remember { mutableStateOf<String?>(null) }
    val needMsg = stringResource(R.string.advdeleg_need_fields)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.advdeleg_route_new else R.string.advdeleg_route_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.advdeleg_route_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text(stringResource(R.string.advdeleg_route_desc)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                ModelPickerField(vm, pair, stringResource(R.string.advdeleg_route_model)) { prov, mdl ->
                    pair = prov?.takeIf { it.isNotBlank() } to mdl?.takeIf { it.isNotBlank() }
                }
                Text(stringResource(R.string.advdeleg_effort), style = MaterialTheme.typography.bodyMedium)
                ChoiceField(effort, effortOpts) { effort = it }
                Text(stringResource(R.string.advdeleg_tier), style = MaterialTheme.typography.bodyMedium)
                ChoiceField(tier, tierOpts) { tier = it }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val n = name.trim(); val d = desc.trim()
            val pv = pair.first.orEmpty().trim(); val md = pair.second.orEmpty().trim()
            if (n.isEmpty() || d.isEmpty() || pv.isEmpty() || md.isEmpty()) { err = needMsg; return@TextButton }
            val r = JSONObject(start.toString())
            r.put("name", n); r.put("description", d); r.put("provider", pv); r.put("model", md)
            if (effort.isBlank()) r.remove("reasoning_effort") else r.put("reasoning_effort", effort)
            if (tier.isBlank()) r.remove("service_tier") else r.put("service_tier", tier)
            onDone(r)
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

