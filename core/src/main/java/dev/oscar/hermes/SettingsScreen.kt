package dev.oscar.hermes

import org.json.JSONObject
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import android.content.Context
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import java.util.Date

@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, recreate: () -> Unit) {
    var key by remember { mutableStateOf("") }
    val servers = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(vm.c.store.servers) } }
    // Grouped cards; each opens its own page. Without a key, go straight to Connection.
    var sub by remember { mutableStateOf(if (vm.c.store.hasKey) "" else "conn") }
    LaunchedEffect(vm.c.settingsTarget) { vm.c.settingsTarget?.let { sub = it; vm.c.settingsTarget = null } }
    BackHandler(sub != "" && vm.c.store.hasKey) { sub = "" }
    val titles = settingsGroupTitles
    Column(Modifier.fillMaxSize()) {
        HermesTopBar(vm, title = { Text(stringResource(titles[sub] ?: R.string.settings), maxLines = 1, style = MaterialTheme.typography.titleMedium) },
            nav = if (vm.c.store.hasKey) ({ IconButton(onClick = { if (sub != "") sub = "" else vm.c.leavePage() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) else null,
            onRefresh = { vm.c.conn.scanProfiles(); vm.c.proj.loadMeta() })
        if (vm.c.store.hasKey) ChatTabs(vm, onList = true)
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          when (sub) {
            "" -> {
                @Composable fun Group(id: String, icon: androidx.compose.ui.graphics.vector.ImageVector, summary: String, badge: Boolean = false) =
                    SettingsGroupCard(vm, id, titles[id]!!, icon, summary, badge) { if (id == "screens") vm.c.openPage("pc") else if (id == "assets") vm.c.openPage("assets") else sub = id }
                Group("conn", Icons.Default.Link, (vm.c.store.activeRelay ?: vm.c.store.relayUrl))
                Group("look", Icons.Default.Palette, stringResource(R.string.appearance_summary))
                LaunchedEffect(Unit) { if (vm.c.store.hasKey) { vm.c.proj.loadCtxSettings(); vm.c.proj.loadModelSettings() } }
                Group("chat", Icons.Default.DataUsage, vm.c.proj.ctxSettings?.let { c -> listOfNotNull(c.optString("compression_model").takeIf { it.isNotBlank() && it != "null" },
                    c.opt("context_length")?.takeIf { it != JSONObject.NULL }?.toString()).joinToString(" · ") } ?: stringResource(R.string.ctx_all_profiles))
                Group("model", Icons.Default.Memory, vm.c.proj.modelSettings?.optJSONObject("main")?.opt("model")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.server_default))
                if (vm.uiFeatures.any { it.hasPage("pc") }) Group("screens", Icons.Default.Monitor, stringResource(R.string.screens_manage))
                vm.uiFeatures.forEach { it.Slot(vm, "settings.groups") }
                Group("assets", Icons.Default.AttachFile, stringResource(R.string.assets_summary))
                Group("modules", Icons.Default.Extension, stringResource(R.string.modules_summary))
                Group("upd", Icons.Default.SystemUpdate, stringResource(R.string.version, vm.c.updates.currentName, vm.c.updates.currentCode), badge = vm.c.update != null)
                Group("about", Icons.Default.Info, "Hermes Android")
            }
            "conn" -> {
            var lanOn by remember { mutableStateOf(vm.c.store.allowLan) }
            var lanWarn by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.allow_lan)); Text(stringResource(R.string.allow_lan_help), style = MaterialTheme.typography.labelSmall) }
                Switch(lanOn, { if (it) lanWarn = true else { lanOn = false; vm.c.store.allowLan = false } })
            }
            var replyOn by remember { mutableStateOf(vm.c.store.replyNotifyOn()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.reply_notify)); Text(stringResource(R.string.reply_notify_help), style = MaterialTheme.typography.labelSmall) }
                Switch(replyOn, { replyOn = it; vm.c.store.setReplyNotifyOn(it) })
            }
            if (lanWarn) AlertDialog(onDismissRequest = { lanWarn = false },
                title = { Text(stringResource(R.string.allow_lan)) }, text = { Text(stringResource(R.string.allow_lan_warn)) },
                confirmButton = { TextButton(onClick = { lanOn = true; vm.c.store.allowLan = true; lanWarn = false }) { Text(stringResource(R.string.allow_lan_ok)) } },
                dismissButton = { TextButton(onClick = { lanWarn = false }) { Text(stringResource(R.string.cancel)) } })
            val act = LocalContext.current as? MainActivity
            Button(onClick = { act?.scanPairCode() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_pair)) }
            ServerList(vm, servers)
            vm.c.store.activeRelay?.let { Text(stringResource(R.string.using_relay, it), style = MaterialTheme.typography.labelSmall) }
            OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.api_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(stringResource(if (vm.c.store.hasKey) R.string.key_saved else R.string.key_new)) })
            Button(onClick = { vm.c.conn.saveSettings(servers.toList(), key); key = "" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_test)) }
            OutlinedButton(onClick = { vm.c.conn.testConnections(servers.toList()) }, enabled = !vm.c.conn.probing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.test_paths)) }
            if (vm.c.conn.probing) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.c.status?.let { Text(it) }

            }
            "look" -> {
            ChoiceRow(listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light),
                "dark" to stringResource(R.string.theme_dark), "black" to stringResource(R.string.theme_black)), vm.c.theme) { vm.c.chooseTheme(it) }
            Text(stringResource(R.string.clock_format))
            ChoiceRow(listOf("system" to stringResource(R.string.clock_system), "12" to stringResource(R.string.clock_12), "24" to stringResource(R.string.clock_24)), vm.c.clock) { vm.c.chooseClock(it) }
            Text(stringResource(R.string.power_mode))
            ChoiceRow(listOf("system" to stringResource(R.string.power_system), "balanced" to stringResource(R.string.power_balanced), "saver" to stringResource(R.string.power_saver)), vm.c.powerMode) { vm.c.choosePower(it) }
            Text(stringResource(when (vm.c.powerMode) { "saver" -> R.string.power_saver_hint; "system" -> R.string.power_system_hint; else -> R.string.power_balanced_hint }), style = MaterialTheme.typography.bodySmall)
            var histTurns by remember { mutableStateOf(vm.c.store.historyTurns()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hist_turns_title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                InfoIcon(stringResource(R.string.hist_turns_title), stringResource(R.string.hist_turns_info))
            }
            ChoiceField(histTurns.toString(), listOf(3, 5, 10, 20, 30).map { it.toString() to it.toString() }) { v ->
                histTurns = v.toInt(); vm.c.store.setHistoryTurns(v.toInt()) }
            Text(stringResource(R.string.app_icon))
            ChoiceRow(listOf("dark" to stringResource(R.string.icon_dark), "light" to stringResource(R.string.icon_light)), vm.appIcon) { vm.chooseIcon(it) }
            var previewPct by remember { mutableStateOf((vm.c.fontScale * 100).toInt()) }
            Text(stringResource(R.string.font_size, previewPct), style = MaterialTheme.typography.titleSmall)
            Slider(
                modifier = Modifier.padding(horizontal = 24.dp).systemGestureExclusion(),
                value = (previewPct - 50).toFloat(),
                onValueChange = { previewPct = (it.toInt() + 50).coerceIn(50, 150) },
                onValueChangeFinished = { if (previewPct / 100f != vm.c.fontScale) { vm.c.chooseFontScale(previewPct / 100f); recreate() } },
                valueRange = 0f..100f)
            Text(stringResource(R.string.font_preview), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(listOf("system" to stringResource(R.string.lang_system), "en" to stringResource(R.string.lang_en),
                "zh" to stringResource(R.string.lang_zh)), vm.c.store.lang) { if (it != vm.c.store.lang) { vm.c.store.lang = it; recreate() } }

            }
            "modules" -> FeatureModules(vm)
            "chat" -> ContextSettingsGroup(vm)
            "model" -> { ModelSettingsGroup(vm); DelegationSettingsGroup(vm); AdvancedDelegationGroup(vm) }
            "upd" -> {
            Text(stringResource(R.string.version, vm.c.updates.currentName, vm.c.updates.currentCode))
            Text(stringResource(R.string.update_source), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(listOf("auto" to stringResource(R.string.update_source_auto), "github" to stringResource(R.string.source_github), "pc" to stringResource(R.string.source_pc)), vm.c.updateSource) { vm.c.chooseUpdateSource(it) }
            vm.c.update?.let { u -> Text(stringResource(R.string.update_available, u.versionName, u.versionCode), color = MaterialTheme.colorScheme.primary)
                Button(onClick = { vm.c.installUpdate() }, enabled = vm.c.updateProgress == null) { Text(stringResource(R.string.install)) } }
            vm.c.updateProgress?.let { p -> LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()); Text(stringResource(R.string.downloading, p))
                OutlinedButton(onClick = { vm.c.cancelUpdate() }) { Text(stringResource(R.string.cancel_update)) } }
            if (vm.c.updateFileBytes > 0 && vm.c.updateProgress == null)
                OutlinedButton(onClick = { vm.c.deleteUpdateFile() }) { Text(stringResource(R.string.delete_update_file, "%.1f".format(vm.c.updateFileBytes / 1048576.0))) }
            OutlinedButton(onClick = { vm.c.checkUpdates(manual = true) }) { Text(stringResource(R.string.check_updates)) }
            vm.c.updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            "about" -> {
                Text("Hermes Android " + vm.c.updates.currentName + " (" + vm.c.updates.currentCode + ")", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
                Text("https://github.com/o12r/HermesAndroid", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
          }
        }
    }
}
