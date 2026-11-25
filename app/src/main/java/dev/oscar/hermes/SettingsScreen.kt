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
    var relay by remember { mutableStateOf(vm.store.relayUrl) }
    var lan by remember { mutableStateOf(vm.store.lanUrl) }
    // Grouped cards; each opens its own page. Without a key, go straight to Connection.
    var sub by remember { mutableStateOf(if (vm.store.hasKey) "" else "conn") }
    LaunchedEffect(vm.settingsTarget) { vm.settingsTarget?.let { sub = it; vm.settingsTarget = null } }
    BackHandler(sub != "" && vm.store.hasKey) { sub = "" }
    val titles = mapOf("conn" to R.string.connection, "look" to R.string.appearance, "chat" to R.string.ctx_title,
        "model" to R.string.default_models, "screens" to R.string.vdisplay, "upd" to R.string.updates, "about" to R.string.about)
    Column(Modifier.fillMaxSize()) {
        HermesTopBar(vm, title = { Text(stringResource(titles[sub] ?: R.string.settings), maxLines = 1, style = MaterialTheme.typography.titleMedium) },
            nav = if (vm.store.hasKey) ({ IconButton(onClick = { if (sub != "") sub = "" else vm.leavePage() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) else null,
            onRefresh = { vm.scanProfiles(); vm.loadMeta() })
        if (vm.store.hasKey) ChatTabs(vm, onList = true)
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          when (sub) {
            "" -> {
                @Composable fun Group(id: String, icon: androidx.compose.ui.graphics.vector.ImageVector, summary: String, badge: Boolean = false) =
                    Card(Modifier.fillMaxWidth().clickable { if (id == "screens") vm.openScreens(Screen.Settings) else sub = id }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            BadgedBox(badge = { if (badge) Badge() }) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) { Text(stringResource(titles[id]!!), style = MaterialTheme.typography.titleSmall)
                                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Icon(Icons.Default.KeyboardArrowRight, null)
                        } }
                Group("conn", Icons.Default.Link, (vm.store.activeRelay ?: vm.store.relayUrl) + " · " + vm.profile)
                Group("look", Icons.Default.Palette, stringResource(R.string.appearance_summary))
                LaunchedEffect(Unit) { if (vm.store.hasKey) { vm.loadCtxSettings(); vm.loadModelSettings() } }
                Group("chat", Icons.Default.DataUsage, vm.ctxSettings?.let { c -> listOfNotNull(c.optString("compression_model").takeIf { it.isNotBlank() && it != "null" },
                    c.opt("context_length")?.takeIf { it != JSONObject.NULL }?.toString()).joinToString(" · ") } ?: stringResource(R.string.ctx_all_profiles))
                Group("model", Icons.Default.Memory, vm.modelSettings?.optJSONObject("main")?.opt("model")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.server_default))
                Group("screens", Icons.Default.Monitor, stringResource(R.string.screens_manage))
                Group("upd", Icons.Default.SystemUpdate, stringResource(R.string.version, vm.updates.currentName, vm.updates.currentCode), badge = vm.update != null)
                Group("about", Icons.Default.Info, "Hermes Android")
            }
            "conn" -> {
            var lanOn by remember { mutableStateOf(vm.store.allowLan) }
            var lanWarn by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.allow_lan)); Text(stringResource(R.string.allow_lan_help), style = MaterialTheme.typography.labelSmall) }
                Switch(lanOn, { if (it) lanWarn = true else { lanOn = false; vm.store.allowLan = false } })
            }
            if (lanWarn) AlertDialog(onDismissRequest = { lanWarn = false },
                title = { Text(stringResource(R.string.allow_lan)) }, text = { Text(stringResource(R.string.allow_lan_warn)) },
                confirmButton = { TextButton(onClick = { lanOn = true; vm.store.allowLan = true; lanWarn = false }) { Text(stringResource(R.string.allow_lan_ok)) } },
                dismissButton = { TextButton(onClick = { lanWarn = false }) { Text(stringResource(R.string.cancel)) } })
            val act = LocalContext.current as? MainActivity
            Button(onClick = { act?.scanPairCode() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_pair)) }
            OutlinedTextField(relay, { relay = it }, label = { Text(stringResource(R.string.server_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.server_url_help, SecureStore.DEFAULT_RELAY)) })
            OutlinedTextField(lan, { lan = it }, label = { Text(stringResource(R.string.lan_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://192.168.1.20:8643") }, supportingText = { Text(stringResource(R.string.lan_url_help)) })
            vm.store.activeRelay?.let { Text(stringResource(R.string.using_relay, it), style = MaterialTheme.typography.labelSmall) }
            OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.api_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(stringResource(if (vm.store.hasKey) R.string.key_saved else R.string.key_new)) })
            Text(stringResource(R.string.profile)); ProfileMenu(vm)
            Button(onClick = { vm.saveSettings(relay, lan, key); key = "" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_test)) }
            OutlinedButton(onClick = { vm.testConnections(relay, lan) }, enabled = !vm.probing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.test_paths)) }
            if (vm.probing) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.probes.forEach { p ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (p.ok) Icons.Default.CheckCircle else Icons.Default.Cancel, null,
                            tint = if (p.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(p.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (p.ok) "${p.ms} ms" else stringResource(R.string.unreachable), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(p.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.active) Text(stringResource(R.string.in_use), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    else if (!p.ok && p.error.isNotBlank()) Text(p.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } }
            }
            vm.status?.let { Text(it) }

            }
            "look" -> {
            ChoiceRow(listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light),
                "dark" to stringResource(R.string.theme_dark), "black" to stringResource(R.string.theme_black)), vm.theme) { vm.chooseTheme(it) }
            Text(stringResource(R.string.clock_format))
            ChoiceRow(listOf("system" to stringResource(R.string.clock_system), "12" to stringResource(R.string.clock_12), "24" to stringResource(R.string.clock_24)), vm.clock) { vm.chooseClock(it) }
            Text(stringResource(R.string.power_mode))
            ChoiceRow(listOf("system" to stringResource(R.string.power_system), "balanced" to stringResource(R.string.power_balanced), "saver" to stringResource(R.string.power_saver)), vm.powerMode) { vm.choosePower(it) }
            Text(stringResource(when (vm.powerMode) { "saver" -> R.string.power_saver_hint; "system" -> R.string.power_system_hint; else -> R.string.power_balanced_hint }), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.app_icon))
            ChoiceRow(listOf("dark" to stringResource(R.string.icon_dark), "light" to stringResource(R.string.icon_light)), vm.appIcon) { vm.chooseIcon(it) }
            var previewPct by remember { mutableStateOf((vm.fontScale * 100).toInt()) }
            Text(stringResource(R.string.font_size, previewPct), style = MaterialTheme.typography.titleSmall)
            Slider(
                modifier = Modifier.padding(horizontal = 24.dp).systemGestureExclusion(),
                value = (previewPct - 50).toFloat(),
                onValueChange = { previewPct = (it.toInt() + 50).coerceIn(50, 150) },
                onValueChangeFinished = { if (previewPct / 100f != vm.fontScale) { vm.chooseFontScale(previewPct / 100f); recreate() } },
                valueRange = 0f..100f)
            Text(stringResource(R.string.font_preview), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(listOf("system" to stringResource(R.string.lang_system), "en" to stringResource(R.string.lang_en),
                "zh" to stringResource(R.string.lang_zh)), vm.store.lang) { if (it != vm.store.lang) { vm.store.lang = it; recreate() } }

            }
            "chat" -> ContextSettingsGroup(vm)
            "model" -> ModelSettingsGroup(vm)
            "upd" -> {
            Text(stringResource(R.string.version, vm.updates.currentName, vm.updates.currentCode))
            Text(stringResource(R.string.update_source), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(listOf("auto" to stringResource(R.string.update_source_auto), "github" to stringResource(R.string.source_github), "pc" to stringResource(R.string.source_pc)), vm.updateSource) { vm.chooseUpdateSource(it) }
            vm.update?.let { u -> Text(stringResource(R.string.update_available, u.versionName, u.versionCode), color = MaterialTheme.colorScheme.primary)
                Button(onClick = { vm.installUpdate() }, enabled = vm.updateProgress == null) { Text(stringResource(R.string.install)) } }
            vm.updateProgress?.let { p -> LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()); Text(stringResource(R.string.downloading, p))
                OutlinedButton(onClick = { vm.cancelUpdate() }) { Text(stringResource(R.string.cancel_update)) } }
            if (vm.updateFileBytes > 0 && vm.updateProgress == null)
                OutlinedButton(onClick = { vm.deleteUpdateFile() }) { Text(stringResource(R.string.delete_update_file, "%.1f".format(vm.updateFileBytes / 1048576.0))) }
            OutlinedButton(onClick = { vm.checkUpdates(manual = true) }) { Text(stringResource(R.string.check_updates)) }
            vm.updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            "about" -> {
                Text("Hermes Android " + vm.updates.currentName + " (" + vm.updates.currentCode + ")", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
                Text("https://github.com/o12r/HermesAndroid", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
          }
        }
    }
}
