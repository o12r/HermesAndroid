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
fun screenLabel(sc: PcScreen): String {
    val base = if (sc.role == "main") stringResource(R.string.screen_main) else stringResource(R.string.screen_secondary, maxOf(1, sc.index))
    return when (sc.kind) {
        "screen-off" -> stringResource(R.string.screen_off_for, base)
        "agent" -> sc.title ?: sc.session ?: "?"
        else -> base
    }
}

/** PC screens: physical ones as main / secondary 1, 2...; agent screens by profile -> project, named after their session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreensScreen(vm: ChatViewModel) {
    var open by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun toggle(sc: PcScreen) { open = if (sc.name in open) open - sc.name else open + sc.name }
    LaunchedEffect(Unit) { vm.vdisplay("status") }
    Column(Modifier.fillMaxSize()) {
        HermesTopBar(vm, title = { Text(stringResource(R.string.pc), maxLines = 1, style = MaterialTheme.typography.titleMedium) },
            nav = { IconButton(onClick = { vm.leavePage() }, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            onRefresh = { vm.loadScreens() }, refreshEnabled = !vm.screensBusy)
        ChatTabs(vm, onList = true)
        PcStatsBar(vm)
        if (vm.screensBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val pc = vm.pcScreens.filter { it.kind != "agent" }
        val agents = vm.pcScreens.filter { it.kind == "agent" }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LaunchedEffect(Unit) { vm.vdisplay("status") }
        when (vm.vdMode) {
            null -> Text(stringResource(R.string.vd_unavailable), style = MaterialTheme.typography.bodySmall)
            "none" -> Text(stringResource(R.string.vd_no_driver), style = MaterialTheme.typography.bodySmall)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vd_agent_chat, vm.currentTitle ?: stringResource(R.string.chat)), Modifier.weight(1f))
                    // Only the agent opens its screen (when it needs a UI); the phone can see it and close it.
                    if (vm.vdAgent) TextButton(onClick = { vm.vdisplay("off") }, enabled = !vm.vdBusy) { Text(stringResource(R.string.vd_close)) }
                    else Text(stringResource(R.string.vd_none), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.vd_hint), style = MaterialTheme.typography.bodySmall)
                if (vm.vdScreenOff) OutlinedButton(onClick = { vm.vdisplay("screen-on") }, enabled = !vm.vdBusy) {
                    Text(stringResource(R.string.vd_wake_all)) }
                Text(stringResource(if (vm.vdScreenOffAvailable) R.string.vd_screen_off_hint else R.string.vd_screen_off_need_driver),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (vm.vdBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
            } }
            item { ListItem(leadingContent = { Icon(Icons.Default.Folder, null) }, headlineContent = { Text(stringResource(R.string.pf_title)) },
                supportingContent = { Text(stringResource(R.string.pf_hint)) }, modifier = Modifier.clickable { vm.openPage("files") }) }
            item { Text(stringResource(R.string.screens_pc), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(pc, key = { "pc:" + it.name }) { sc -> Column { ScreenRow(screenLabel(sc), sc, sc.on, screenActions(vm, sc)) { toggle(sc) }
                if (sc.name in open) ScreenInline(vm, sc) { open = open - sc.name } } }
            item { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.screens_agent), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            if (agents.isEmpty()) item { Text(stringResource(R.string.screens_no_agent), style = MaterialTheme.typography.bodySmall) }
            agents.groupBy { it.profile ?: "default" }.toSortedMap().forEach { (prof, byProf) ->
                item { Text("👤 $prof", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)) }
                byProf.groupBy { it.project ?: it.cwd?.trimEnd('\\', '/')?.substringAfterLast('\\')?.substringAfterLast('/') ?: "—" }
                    .toSortedMap().forEach { (proj, l) ->
                        item { Text("📁 $proj", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp)) }
                        items(l, key = { "ag:" + it.name + (it.session ?: "") }) { sc -> Column(Modifier.padding(start = 12.dp)) { ScreenRow(screenLabel(sc), sc, true, screenActions(vm, sc)) { toggle(sc) }
                            if (sc.name in open) ScreenInline(vm, sc) { open = open - sc.name } } }
                    }
            }
        }
    }
}

@Composable
fun screenActions(vm: ChatViewModel, sc: PcScreen): List<Pair<String, () -> Unit>> = when {
    sc.kind == "physical" && sc.on -> listOf(stringResource(R.string.vd_screen_off_this) to { vm.vdisplay("screen-off", sc.name) })
    sc.kind == "physical" -> listOf(stringResource(R.string.vd_screen_on_this) to { vm.vdisplay("screen-on", sc.name) })
    sc.kind == "agent" && sc.session != null -> listOf(stringResource(R.string.vd_close) to { vm.closeAgentScreen(sc.session!!) })
    else -> emptyList()
}

/** Physical = primary, screen-off stand-in = secondary, agent = tertiary. */
@Composable
fun screenColor(sc: PcScreen) = when (sc.kind) {
    "agent" -> MaterialTheme.colorScheme.tertiary
    "screen-off" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun ScreenRow(label: String, sc: PcScreen, enabled: Boolean, actions: List<Pair<String, () -> Unit>> = emptyList(), onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().combinedClickable(enabled = enabled || actions.isNotEmpty(), onLongClick = { if (actions.isNotEmpty()) menu = true }) { if (enabled) onClick() }) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(36.dp).background(screenColor(sc)))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Monitor, null, tint = screenColor(sc))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (sc.on) "${sc.w}×${sc.h} @${sc.hz}Hz" else stringResource(R.string.screen_is_off),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (actions.isNotEmpty()) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(menu, { menu = false }) {
                    actions.forEach { (t, f) -> DropdownMenuItem(text = { Text(t) }, onClick = { menu = false; f() }) } }
            } else Spacer(Modifier.width(12.dp))
        }
    }
}

/** Expands under the row: screenshot (tap to zoom), refresh, then the row's menu actions as buttons. */
@Composable
fun ScreenInline(vm: ChatViewModel, sc: PcScreen, close: () -> Unit) {
    var bmp by remember(sc.name) { mutableStateOf<ImageBitmap?>(null) }
    var err by remember(sc.name) { mutableStateOf<String?>(null) }
    var busy by remember(sc.name) { mutableStateOf(false) }
    var n by remember(sc.name) { mutableStateOf(0) }
    LaunchedEffect(sc.name, n) {
        busy = true; err = null
        try { bmp = vm.screenShot(sc.name).asImageBitmap() } catch (e: Exception) { err = e.message }
        busy = false
    }
    val label = screenLabel(sc)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        var zoom by remember { mutableStateOf(false) }
        bmp?.let { Image(it, label, Modifier.fillMaxWidth().clickable { zoom = true }, contentScale = ContentScale.FillWidth)
            if (zoom) ZoomImage(it, label, busy, { n++ }) { zoom = false } }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { n++ }, enabled = !busy) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.screen_refresh)) }
            screenActions(vm, sc).forEach { (t, f) -> OutlinedButton(onClick = { f(); close() }, enabled = !vm.vdBusy) { Text(t) } }
        }
        Text("${sc.name.removePrefix("\\\\.\\")} · ${sc.w}×${sc.h} @${sc.hz}Hz", style = MaterialTheme.typography.bodySmall)
        sc.cwd?.let { Text("📁 $it", style = MaterialTheme.typography.bodySmall) }
        sc.session?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
