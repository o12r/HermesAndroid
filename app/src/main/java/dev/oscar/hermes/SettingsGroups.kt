package dev.oscar.hermes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    LaunchedEffect(Unit) { vm.c.proj.loadModelSettings(); vm.c.proj.loadCtxSettings(); if (vm.c.proj.modelOptions == null) vm.c.proj.loadMeta() }
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
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(vm.c.proj.modelBusy) { if (saving && !vm.c.proj.modelBusy) saving = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.models_all_profiles), style = MaterialTheme.typography.bodySmall)
        if (vm.c.proj.modelBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.c.proj.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (cur != null) {
            ModelPickerField(vm, slots["main"], stringResource(R.string.model_main)) { p, m -> slots["main"] = p to m }
            ModelPickerField(vm, slots["delegation"], stringResource(R.string.model_delegation)) { p, m -> slots["delegation"] = p to m }
            if (ctx != null && !compInAux) ModelPickerField(vm, comp, stringResource(R.string.ctx_comp_model)) { p, m -> comp = p to m }
            val auxKeys = slots.keys.filter { it.startsWith("aux:") }.sorted()
            if (auxKeys.isNotEmpty()) Text(stringResource(R.string.model_auxiliary), style = MaterialTheme.typography.titleSmall)
            auxKeys.forEach { k ->
                ModelPickerField(vm, slots[k], k.removePrefix("aux:")) { p, m -> slots[k] = p to m }
            }
            Button(enabled = !vm.c.proj.modelBusy, onClick = {
                fun putEntry(slot: String, entry: Pair<String?, String?>?): JSONObject {
                    val e = JSONObject()
                    e.put("model", entry?.second?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    e.put("provider", entry?.first?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    return JSONObject().put(slot, e)
                }
                val o = JSONObject()
                o.put("main", putEntry("main", slots["main"]).getJSONObject("main"))
                o.put("delegation", putEntry("delegation", slots["delegation"]).getJSONObject("delegation"))
                val aux = JSONObject()
                slots.keys.filter { it.startsWith("aux:") }.forEach { k -> aux.put(k.removePrefix("aux:"), putEntry(k, slots[k]).getJSONObject(k)) }
                o.put("auxiliary", aux)
                saving = true; vm.c.proj.loadModelSettings(o)
                if (ctx != null && !compInAux && (comp.first != ctxStr("compression_provider") || comp.second != ctxStr("compression_model")))
                    vm.c.proj.loadCtxSettings(JSONObject().put("compression_provider", comp.first ?: JSONObject.NULL).put("compression_model", comp.second ?: JSONObject.NULL))
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        }
    }
}
