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

/** Open chats as tabs: ⏳ = Hermes working, ✎ = unsent draft, × closes (the PC session is kept). */
/** Compact top bar shared by every page: [nav] title ... [extra actions] refresh | PC | settings (the last three always in the same place). */
@Composable
fun HermesTopBar(vm: ChatViewModel, title: @Composable () -> Unit, nav: (@Composable () -> Unit)? = null,
                 onRefresh: () -> Unit, refreshEnabled: Boolean = true, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (nav != null) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { nav() }
        Box(Modifier.weight(1f).padding(horizontal = if (nav == null) 10.dp else 4.dp)) { title() }
        actions()
        IconButton(onClick = onRefresh, enabled = refreshEnabled, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
        if (vm.uiFeatures.any { it.hasPage("pc") }) IconButton(onClick = { vm.c.openPage("pc") }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Monitor, stringResource(R.string.pc)) }
        IconButton(onClick = { vm.c.openPage("settings") }, modifier = Modifier.size(40.dp)) {
            BadgedBox(badge = { if (vm.c.update != null) Badge() }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } }
    }
}

@Composable
fun ChatTabs(vm: ChatViewModel, onList: Boolean = false) {
    // Tabs replace the back button in a chat, so they show even for a single chat; ＋ goes to the session list.
    if (onList && vm.c.tabs.isEmpty()) return
    val state = vm.tabsState
    val pageScreen = vm.c.screen != Screen.Chat
    val idx = vm.c.tabs.indexOfFirst { it === (vm.c.activePage ?: vm.c.cur) }
    LaunchedEffect(idx) { if (idx >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == idx }) state.animateScrollToItem(idx) }
    val newChat = stringResource(R.string.new_chat)
    var dragKey by remember { mutableStateOf<Int?>(null) }
    var dragOff by remember { mutableStateOf(0f) }
    androidx.compose.foundation.lazy.LazyRow(state = state, contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        items(vm.c.tabs.toList(), key = { System.identityHashCode(it) }) { c ->
            val sel = if (c.page != null) c === vm.c.activePage else !pageScreen && c === vm.c.cur
            val label = buildString {
                if (c.readOnly && c.page == null) append("🔒 ")
                if (c.streaming) append("⏳ ")
                c.id?.let { cid -> vm.c.features.firstNotNullOfOrNull { f -> f.tabBadge(cid) }?.let { append(it) } }
                if (c.approval != null || c.clarify != null) append("❓ ")
                append(c.title ?: newChat)
                if (!sel && c.draft.isNotBlank()) append(" ✎")
            }
            if (sel) LaunchedEffect(c.unread) { c.unread = false }
            val dragging = dragKey == System.identityHashCode(c)
            Box(Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer { translationX = if (dragging) dragOff else 0f }) {
            InputChip(selected = sel, onClick = { if (!sel) vm.c.switchTo(c) },
                leadingIcon = if (c.page != null) ({ Icon(vm.uiFeatures.firstNotNullOfOrNull { f -> f.pageIcon(c.page.orEmpty()) } ?: when (c.page) { "sessions" -> Icons.Default.Forum; else -> Icons.Default.Settings }, null, Modifier.size(18.dp)) }) else ({ Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Text(c.profile.trim().take(1).uppercase().ifEmpty { "?" }, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSecondaryContainer) } }),
                colors = InputChipDefaults.inputChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .pointerInput(c) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragKey = System.identityHashCode(c); dragOff = 0f },
                            onDragEnd = { dragKey = null; dragOff = 0f },
                            onDragCancel = { dragKey = null; dragOff = 0f },
                            onDrag = { ch, d ->
                                ch.consume(); dragOff += d.x
                                val i = vm.c.tabs.indexOfFirst { it === c }
                                val items = state.layoutInfo.visibleItemsInfo
                                val me = items.firstOrNull { it.key == System.identityHashCode(c) }
                                if (i >= 0 && me != null) {
                                    val center = me.offset + me.size / 2f + dragOff
                                    val other = items.firstOrNull { it.index != i && it.index < vm.c.tabs.size && center >= it.offset && center <= it.offset + it.size }
                                    if (other != null) {
                                        val shift = other.size + 6.dp.toPx()
                                        vm.c.tabs.add(other.index, vm.c.tabs.removeAt(i))
                                        dragOff += if (other.index > i) -shift else shift
                                    }
                                }
                            })
                    },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp)) },
                trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.close_tab), Modifier.size(16.dp).clickable { vm.c.closeTab(c) }) })
            if (!sel && c.unread) Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 1.dp, end = 1.dp).size(16.dp)
                .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
            }
        }
        item(key = "plus") {
            InputChip(selected = false, onClick = { vm.c.openPage("sessions") },
                label = { Icon(Icons.Default.Add, stringResource(R.string.sessions)) })
        }
    }
}

/** Autocomplete for phone + Hermes slash commands and skills while the input is a single "/word". */
@Composable
fun SlashSuggestions(vm: ChatViewModel, input: String, set: (String) -> Unit) {
    if (!input.startsWith("/") || input.contains(' ') || input.contains('\n')) return
    val q = input.drop(1).lowercase()
    val items = vm.c.msg.commandList()
        .filter { it.first.drop(1).lowercase().contains(q) }.sortedBy { !it.first.drop(1).lowercase().startsWith(q) }.take(30)
    if (items.isEmpty()) return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(horizontal = 8.dp)) {
        LazyColumn {
            items(items.distinctBy { it.first }, key = { it.first }) { (c, d) ->
                ListItem(headlineContent = { Text(c) }, supportingContent = { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { set("$c ") })
            }
        }
    }
}
