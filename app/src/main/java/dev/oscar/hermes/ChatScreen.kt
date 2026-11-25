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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenContextSettings: () -> Unit = {}, onOpenModelSettings: () -> Unit = {}) {
    // The chat.draft is the current chat's draft, so typed text survives switching chats / going back.
    val chat = vm.cur
    val list = remember(chat) { androidx.compose.foundation.lazy.LazyListState(if (chat.follow) 0 else chat.scrollIdx, if (chat.follow) 0 else chat.scrollOff) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.addAttachments(it) }
    // Follow new output only while the user is at the bottom; scrolling up to read stops it, scrolling back resumes.
    var follow by remember(chat) { mutableStateOf(chat.follow) }
    var renamingTitle by remember { mutableStateOf(false) }
    LaunchedEffect(list, chat) { snapshotFlow { list.isScrollInProgress }.drop(1).collect { if (!it) follow = !list.canScrollForward } }
    // Remember where this chat was left (position + whether it was pinned to the bottom).
    LaunchedEffect(list, chat) {
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, follow) }.collect { (i, o, f) ->
            if (vm.messages.isNotEmpty()) { chat.scrollIdx = i; chat.scrollOff = o; chat.follow = f }
        }
    }
    // Throttled auto-scroll: never per token; a final jump once the turn ends, and always after a user message.
    LaunchedEffect(chat) {
        var lastScroll = 0L
        snapshotFlow {
            val m = vm.messages.lastOrNull()
            Triple(vm.messages.size, m?.text?.length ?: 0, m?.steps?.size ?: 0)
        }.collect { (size, _, _) ->
            if (size == 0) return@collect
            if (vm.messages.lastOrNull()?.role == "user") follow = true
            if (!follow) return@collect
            if (!vm.streaming) { list.scrollToItem(size - 1); list.scrollBy(1_000_000f); kotlinx.coroutines.delay(60) }
            val now = android.os.SystemClock.uptimeMillis()
            if (!vm.streaming || now - lastScroll >= 120) { lastScroll = now; list.scrollToItem(size - 1); list.scrollBy(1_000_000f) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        HermesTopBar(vm, title = { Column(Modifier.horizontalScroll(rememberScrollState())) {
                Text(vm.currentTitle ?: stringResource(if (vm.currentId == null) R.string.new_chat else R.string.chat), maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.combinedClickable(onDoubleClick = { if (vm.currentId != null) renamingTitle = true }) {})
                val proj = vm.projects.find { p ->
                    (chat.projectId != null && p.id == chat.projectId) || (vm.currentId != null && vm.currentId in p.sessionIds)
                }?.let { p -> if (p.isHome) stringResource(R.string.home) else p.name }
                Text(listOfNotNull(chat.profile, proj).joinToString(" › "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
            } }, onRefresh = { vm.refreshChat() })
        if (renamingTitle) {
            var t by remember { mutableStateOf(vm.currentTitle ?: "") }
            AlertDialog(onDismissRequest = { renamingTitle = false }, title = { Text(stringResource(R.string.rename)) },
                text = { OutlinedTextField(t, { t = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.currentId?.let { vm.rename(Session(it, null, null, null, 0, null), t) }; renamingTitle = false }) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { renamingTitle = false }) { Text(stringResource(R.string.cancel)) } })
        }
        if (vm.chatRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        ChatTabs(vm)
        val jumpScope = rememberCoroutineScope()
        Box(Modifier.weight(1f).fillMaxWidth()) {
        PullToRefreshBox(isRefreshing = vm.chatRefreshing, onRefresh = { vm.refreshChat() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(vm.messages, key = { _, m -> m.uid }) { i, m ->
                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = androidx.compose.ui.graphics.Color(0xFF9E9E9E).copy(alpha = 0.45f))
                Bubble(m, vm, live = vm.streaming && i == vm.messages.lastIndex) }
        }
        }
        if (!follow && list.canScrollForward && vm.messages.isNotEmpty())
            SmallFloatingActionButton(onClick = { follow = true
                    jumpScope.launch { list.scrollToItem(vm.messages.lastIndex); list.scrollBy(1_000_000f) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.jump_newest)) }
        }
        vm.status?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium) }
        if (vm.streaming) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (chat.readOnly) Text("🔒 " + stringResource(R.string.task_readonly), Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
        SlashSuggestions(vm, chat.draft) { chat.draft = it }
        RuntimeChips(vm, onOpenContextSettings, onOpenModelSettings)
        if (chat.queue.isNotEmpty()) Column(Modifier.padding(horizontal = 8.dp)) {
            chat.queue.toList().forEachIndexed { i, q -> InputChip(selected = false, onClick = { chat.queue.removeAt(i) },
                label = { Text(stringResource(R.string.queued, q), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.unqueue), Modifier.size(16.dp)) }) }
        }
        vm.uploadStatus?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        if (vm.attachments.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp)) {
            vm.attachments.forEach { a -> InputChip(selected = false, onClick = { vm.attachments.remove(a) }, label = { Text(a.name, maxLines = 1) },
                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }, modifier = Modifier.padding(end = 4.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            // Plain Compose text field: goes through Android's InputConnection, so Gboard / WeChat IME composition works.
            var focused by remember { mutableStateOf(false) }
            var full by remember { mutableStateOf(false) }
            val half = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 2).dp
            // Collapse to one line when the soft keyboard closes on a phone with no hardware keyboard.
            val imeVisible = WindowInsets.isImeVisible
            val hasHardwareKeyboard = androidx.compose.ui.platform.LocalConfiguration.current.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            LaunchedEffect(imeVisible) { if (!imeVisible && !hasHardwareKeyboard && focused) focusManager.clearFocus() }
            OutlinedTextField(chat.draft, { chat.draft = it }, Modifier.weight(1f).heightIn(max = half)
                    .onFocusChanged { focused = it.isFocused }.animateContentSize(),
                placeholder = { Text(stringResource(if (vm.streaming) R.string.steer_hint else R.string.message_hint)) },
                minLines = if (focused) 5 else 1, maxLines = if (focused) Int.MAX_VALUE else 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            if (full) androidx.compose.ui.window.Dialog(onDismissRequest = { full = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { full = false }) { Icon(Icons.Default.CloseFullscreen, stringResource(R.string.collapse_editor)) }
                        Text(stringResource(R.string.expand_editor), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { full = false; vm.send(chat.draft); chat.draft = "" }, enabled = chat.draft.isNotBlank()) {
                            Text(stringResource(if (vm.streaming) R.string.steer else R.string.send)) }
                    }
                    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                    OutlinedTextField(chat.draft, { chat.draft = it }, Modifier.fillMaxWidth().weight(1f).focusRequester(fr),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    LaunchedEffect(Unit) { fr.requestFocus() }
                } }
            }
            // Buttons stacked outside the field, on its right. Collapsed (one line): only send or stop.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val small = Modifier.size(40.dp)
                // Buttons that would be disabled are hidden instead of shown greyed out.
                if (vm.hotfix.attachments && !vm.streaming) IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, small) {
                    Icon(Icons.Default.AttachFile, stringResource(R.string.attach)) }
                if (focused) {
                    IconButton(onClick = { full = true }, small) { Icon(Icons.Default.OpenInFull, stringResource(R.string.expand_editor)) }
                    if (vm.streaming) IconButton(onClick = { vm.stop() }, small) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) }
                    // Queue: send after the running turn ends.
                    if (vm.streaming && chat.draft.isNotBlank()) IconButton(onClick = { vm.queueMsg(chat.draft); chat.draft = "" }, small) {
                        Icon(Icons.Default.Schedule, stringResource(R.string.queue)) }
                } else if (vm.streaming) {
                    // One line while streaming: stop only, no steer.
                    IconButton(onClick = { vm.stop() }, small) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) }
                }
                // While Hermes works, Send steers the running turn instead of waiting (only when expanded).
                if (chat.draft.isNotBlank() || (!vm.streaming && vm.attachments.isNotEmpty())) IconButton(onClick = { vm.send(chat.draft); chat.draft = "" }, small) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(if (vm.streaming) R.string.steer else R.string.send)) }
            }
        }
        }
    }
}
