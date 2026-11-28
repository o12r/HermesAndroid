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
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** Pastes longer than this (chars, one change) ask whether to attach them as a paste.txt file. */
private const val PASTE_FILE_CHARS = 2000

/** The part of [after] that was inserted relative to [before] (common prefix/suffix removed). */
private fun insertedText(before: String, after: String): String {
    var pre = 0
    val max = minOf(before.length, after.length)
    while (pre < max && before[pre] == after[pre]) pre++
    var suf = 0
    while (suf < max - pre && before[before.length - 1 - suf] == after[after.length - 1 - suf]) suf++
    return after.substring(pre, after.length - suf)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenContextSettings: () -> Unit = {}, onOpenModelSettings: () -> Unit = {}) {
    // The chat.draft is the current chat's draft, so typed text survives switching chats / going back.
    val chat = vm.c.cur
    val list = remember(chat) { androidx.compose.foundation.lazy.LazyListState(if (chat.follow) 0 else chat.scrollIdx, if (chat.follow) 0 else chat.scrollOff) }
    // Picked files become FILE: lines in the draft (no chip).
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.stageFiles(uris) { p -> chat.draft = FileStaging.withLine(chat.draft, p) } }
    // Without all-files access the attach button explains and offers settings instead of picking.
    var showAllFiles by remember { mutableStateOf(false) }
    val allFilesCtx = LocalContext.current
    if (showAllFiles) AlertDialog(
        onDismissRequest = { showAllFiles = false },
        title = { Text(stringResource(R.string.all_files_title)) },
        text = { Text(stringResource(R.string.all_files_body)) },
        confirmButton = { TextButton(onClick = { showAllFiles = false; FileStaging.requestAllFiles(allFilesCtx) }) { Text(stringResource(R.string.all_files_open)) } },
        dismissButton = { TextButton(onClick = { showAllFiles = false }) { Text(stringResource(R.string.cancel)) } })
    // Follow new output only while the user is at the bottom; scrolling up to read stops it, scrolling back resumes.
    var follow by remember(chat) { mutableStateOf(chat.follow) }
    var renamingTitle by remember { mutableStateOf(false) }
    // A long paste asks first: (text before the paste, text after it). Decided via the dialog below, never re-asked.
    var pendingPaste by remember(chat) { mutableStateOf<Pair<String, String>?>(null) }
    val onDraftChange: (String) -> Unit = { new ->
        if (vm.c.hotfix.attachments && !vm.c.streaming && new.length - chat.draft.length > PASTE_FILE_CHARS) pendingPaste = chat.draft to new
        else chat.draft = new
    }
    LaunchedEffect(list, chat) { snapshotFlow { list.isScrollInProgress }.drop(1).collect { if (!it) follow = !list.canScrollForward } }
    // Remember where this chat was left (position + whether it was pinned to the bottom).
    LaunchedEffect(list, chat) {
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, follow) }.collect { (i, o, f) ->
            if (vm.c.messages.isNotEmpty()) { chat.scrollIdx = i; chat.scrollOff = o; chat.follow = f }
        }
    }
    // Older turns load when the list nears the top. Prepended rows shift the first visible index by the count added, so
    // the view is put back on the same message (rows are keyed by uid, so the keys stay stable).
    LaunchedEffect(chat) {
        snapshotFlow { list.firstVisibleItemIndex < 3 && chat.loaded && chat.hasMoreHistory && !chat.loadingOlder }
            .distinctUntilChanged()
            .collect { near ->
                if (!near) return@collect
                val before = list.firstVisibleItemIndex
                val off = list.firstVisibleItemScrollOffset
                val added = vm.c.msg.loadOlder(chat)
                if (added > 0) {
                    kotlinx.coroutines.delay(32)   // let the list lay out the prepended rows
                    if (list.firstVisibleItemIndex == before) list.scrollToItem(before + added, off)
                }
            }
    }
    // Throttled auto-scroll: never per token; a final jump once the turn ends, and always after a user message.
    LaunchedEffect(chat) {
        var lastScroll = 0L
        snapshotFlow {
            val m = vm.c.messages.lastOrNull()
            Triple(vm.c.messages.size, m?.text?.length ?: 0, m?.steps?.size ?: 0)
        }.collect { (size, _, _) ->
            if (size == 0) return@collect
            if (vm.c.messages.lastOrNull()?.role == "user") follow = true
            if (!follow) return@collect
            if (!vm.c.streaming) { list.scrollToItem(size - 1); list.scrollBy(1_000_000f); kotlinx.coroutines.delay(60) }
            val now = android.os.SystemClock.uptimeMillis()
            if (!vm.c.streaming || now - lastScroll >= 120) { lastScroll = now; list.scrollToItem(size - 1); list.scrollBy(1_000_000f) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        HermesTopBar(vm, title = { Column(Modifier.horizontalScroll(rememberScrollState())) {
                Text(vm.c.currentTitle ?: stringResource(if (vm.c.currentId == null) R.string.new_chat else R.string.chat), maxLines = 1, softWrap = false,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.combinedClickable(onDoubleClick = { if (vm.c.currentId != null) renamingTitle = true }) {})
                val proj = vm.c.proj.projects.find { p ->
                    (chat.projectId != null && p.id == chat.projectId) || (vm.c.currentId != null && vm.c.currentId in p.sessionIds)
                }?.let { p -> if (p.isHome) stringResource(R.string.home) else p.name }
                Text(listOfNotNull(chat.profile, proj).joinToString(" › "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
            } }, onRefresh = { vm.c.conn.refreshChat() })
        if (renamingTitle) {
            var t by remember { mutableStateOf(vm.c.currentTitle ?: "") }
            AlertDialog(onDismissRequest = { renamingTitle = false }, title = { Text(stringResource(R.string.rename)) },
                text = { OutlinedTextField(t, { t = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.c.currentId?.let { vm.c.msg.rename(Session(it, null, null, null, 0, null), t) }; renamingTitle = false }) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { renamingTitle = false }) { Text(stringResource(R.string.cancel)) } })
        }
        if (vm.c.conn.chatRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        ChatTabs(vm)
        val jumpScope = rememberCoroutineScope()
        Box(Modifier.weight(1f).fillMaxWidth()) {
        PullToRefreshBox(isRefreshing = vm.c.conn.chatRefreshing, onRefresh = { vm.c.conn.refreshChat() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(vm.c.messages, key = { _, m -> m.uid }) { i, m ->
                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = androidx.compose.ui.graphics.Color(0xFF9E9E9E).copy(alpha = 0.45f))
                Bubble(m, vm, live = vm.c.streaming && i == vm.c.messages.lastIndex) }
        }
        }
        if (chat.loadingOlder) Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.hist_loading_older), style = MaterialTheme.typography.labelSmall)
        }
        if (!follow && list.canScrollForward && vm.c.messages.isNotEmpty())
            SmallFloatingActionButton(onClick = { follow = true
                    jumpScope.launch { list.scrollToItem(vm.c.messages.lastIndex); list.scrollBy(1_000_000f) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.jump_newest)) }
        }
        vm.c.status?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium) }
        if (vm.c.streaming) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (chat.readOnly) {
            Text("🔒 " + stringResource(R.string.task_readonly), Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val task = vm.c.chats.flatMap { it.messages }.firstNotNullOfOrNull { m -> m.task?.takeIf { it.chat === chat } }
            if (task != null && task.status == "running" && task.kind != "bg" && task.ref.isNotBlank())
                TextButton(onClick = { vm.c.msg.forceStop(task) }, Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.force_stop), color = MaterialTheme.colorScheme.error) }
        }
        else {
        SlashSuggestions(vm, chat.draft) { chat.draft = it }
        RuntimeChips(vm, onOpenContextSettings, onOpenModelSettings)
        if (chat.queue.isNotEmpty()) Column(Modifier.padding(horizontal = 8.dp)) {
            chat.queue.toList().forEachIndexed { i, q -> InputChip(selected = false, onClick = { chat.queue.removeAt(i) },
                label = { Text(stringResource(R.string.queued, q), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.unqueue), Modifier.size(16.dp)) }) }
        }
        vm.c.pc.uploadStatus?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        if (vm.c.attachments.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp)) {
            vm.c.attachments.forEach { a -> InputChip(selected = false, onClick = { vm.c.attachments.remove(a) }, label = { Text(a.name, maxLines = 1) },
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
            OutlinedTextField(chat.draft, onDraftChange, Modifier.weight(1f).heightIn(max = half)
                    .onFocusChanged { focused = it.isFocused }.animateContentSize(),
                placeholder = { Text(stringResource(if (vm.c.streaming) R.string.steer_hint else R.string.message_hint)) },
                minLines = if (focused) 5 else 1, maxLines = if (focused) Int.MAX_VALUE else 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            if (full) androidx.compose.ui.window.Dialog(onDismissRequest = { full = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { full = false }) { Icon(Icons.Default.CloseFullscreen, stringResource(R.string.collapse_editor)) }
                        Text(stringResource(R.string.expand_editor), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { full = false; vm.c.msg.send(chat.draft); chat.draft = "" }, enabled = chat.draft.isNotBlank()) {
                            Text(stringResource(if (vm.c.streaming) R.string.steer else R.string.send)) }
                    }
                    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                    OutlinedTextField(chat.draft, onDraftChange, Modifier.fillMaxWidth().weight(1f).focusRequester(fr),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    LaunchedEffect(Unit) { fr.requestFocus() }
                } }
            }
            pendingPaste?.let { (before, after) ->
                val pasted = insertedText(before, after)
                AlertDialog(
                    onDismissRequest = { pendingPaste = null; chat.draft = after },
                    title = { Text(stringResource(R.string.paste_title)) },
                    text = { Text(stringResource(R.string.paste_text)) },
                    confirmButton = { TextButton(onClick = { pendingPaste = null; chat.draft = before; vm.attachPaste(pasted) }) { Text(stringResource(R.string.paste_attach)) } },
                    dismissButton = { TextButton(onClick = { pendingPaste = null; chat.draft = after }) { Text(stringResource(R.string.paste_keep)) } })
            }
            // Buttons stacked outside the field, on its right. Collapsed (one line): only send or stop.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val small = Modifier.size(40.dp)
                // Buttons that would be disabled are hidden instead of shown greyed out.
                if (vm.c.hotfix.attachments && !vm.c.streaming) IconButton(onClick = { if (FileStaging.hasAllFiles()) pickFiles.launch(arrayOf("*/*")) else showAllFiles = true }, small) {
                    Icon(Icons.Default.AttachFile, stringResource(R.string.attach)) }
                if (focused) {
                    IconButton(onClick = { full = true }, small) { Icon(Icons.Default.OpenInFull, stringResource(R.string.expand_editor)) }
                    if (vm.c.streaming) IconButton(onClick = { vm.c.msg.stop() }, small) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) }
                    // Queue: send after the running turn ends.
                    if (vm.c.streaming && chat.draft.isNotBlank()) IconButton(onClick = { vm.c.queueMsg(chat.draft); chat.draft = "" }, small) {
                        Icon(Icons.Default.Schedule, stringResource(R.string.queue)) }
                } else if (vm.c.streaming) {
                    // One line while streaming: stop only, no steer.
                    IconButton(onClick = { vm.c.msg.stop() }, small) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) }
                }
                // While Hermes works, Send steers the running turn instead of waiting (only when expanded).
                if (chat.draft.isNotBlank() || (!vm.c.streaming && vm.c.attachments.isNotEmpty())) IconButton(onClick = { vm.c.msg.send(chat.draft); chat.draft = "" }, small) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(if (vm.c.streaming) R.string.steer else R.string.send)) }
            }
        }
        }
    }
}
