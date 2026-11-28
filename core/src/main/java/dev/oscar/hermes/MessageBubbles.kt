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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Bubble(m: UiMsg, vm: ChatViewModel, live: Boolean = false) {
    if (m.role == "system") {
        Text(m.text, Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary, fontStyle = FontStyle.Italic)
        return
    }
    if (m.role == "task" && m.task != null) { TaskCard(m.task!!, { vm.c.msg.openTask(m.task!!) }); return }
    if (m.role == "file") {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
            FileBubble(m.text, m.file, vm.c.pc.fileProgress[m.text], onCancel = { vm.c.pc.cancelDownload(m.text) }, onDownload = { u -> vm.c.pc.exportInbox(m.text, u) }) { vm.c.pc.saveInbox(m.text, m.session) } }
        return
    }
    val user = m.role == "user"
    // Full-width colour bands (no rounded bubbles) so each line fits more text.
    Box(Modifier.fillMaxWidth()) {
        Surface(shape = androidx.compose.ui.graphics.RectangleShape, color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp), horizontalAlignment = Alignment.Start) {
                if (user) UserBody(m.text) else AssistantBody(m, live, vm)
                if (m.failed) FailedActions(m, vm) else MessageActions(m, vm)
            }
        }
    }
}

/** Notice card for a sub-agent / background task; tap opens its read-only tab. */
@Composable
fun TaskCard(t: TaskInfo, onOpen: () -> Unit, onStop: (() -> Unit)? = null) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    if (t.status == "running") LaunchedEffect(t) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val secs = ((if (t.status == "running") now else t.endedAt) - t.startedAt).coerceAtLeast(0) / 1000
    val status = stringResource(when (t.status) { "running" -> R.string.task_running; "failed" -> R.string.task_failed; "stopped" -> R.string.task_stopped; else -> R.string.task_done })
    val goal = t.chat.title.orEmpty()
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clickable(onClick = onOpen)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (t.kind == "sa") "🤖" else "⚙", Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(when (t.kind) { "bg" -> R.string.task_background; "proc" -> R.string.task_process; else -> R.string.task_subagent }) + if (goal.isNotBlank() && t.kind != "bg") " · $goal" else "",
                    style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status + if (secs > 0 || t.status == "running") " · " + (if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s") else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (t.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
                if (t.kind == "proc" && t.status == "running") {
                    val pc = t.percent
                    if (pc != null) LinearProgressIndicator(progress = { (pc / 100.0).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                if (t.kind == "proc" && t.last.isNotBlank())
                    Text((if (t.percent != null && t.status == "running") "${t.percent!!.toInt()}% · " else "") + t.last, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Text("🔒", Modifier.padding(start = 6.dp))
        }
    }
}

/** An assistant reply: every text segment in the order it happened, then the collapsible thinking/tool details at the bottom. */
@Composable
fun AssistantBody(m: UiMsg, live: Boolean, vm: ChatViewModel) {
    val textSteps = m.steps.filter { it.kind == "text" }
    val details = m.steps.filter { it.kind != "text" }
    if (textSteps.isNotEmpty()) textSteps.forEach { st -> ReplyText(st.detail, live, vm) }
    else ReplyText(m.raw ?: m.text, live, vm, placeholder = true)
    if (details.isNotEmpty()) StepsView(details, live = live)
    else if (m.tools.isNotEmpty()) Text("🔧 " + m.tools.joinToString(", "), style = MaterialTheme.typography.labelSmall)
}

/** A user message: its text, with each FILE: line shown as a compact file chip (copy still uses the raw text). */
@Composable
fun UserBody(text: String) {
    val parts = remember(text) { Internal.userParts(text) }
    if (parts.none { it is UserPart.File }) { Text(text); return }
    parts.forEach { p -> when (p) {
        is UserPart.Text -> Text(p.s)
        is UserPart.File -> Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(vertical = 2.dp)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp))
                Text(Internal.fileName(p.path), Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            }
        }
    } }
}

/** Reply text in order, with each MEDIA reference replaced by its file card at that spot. */
@Composable
fun ReplyText(raw: String, live: Boolean, vm: ChatViewModel, placeholder: Boolean = false) {
    val segs = remember(raw, live) { Internal.segments(raw, live).filter { it !is ReplyPart.Text || it.s.isNotBlank() } }
    if (segs.isEmpty()) { if (placeholder) Markdown("…"); return }
    segs.forEach { s -> when (s) {
        is ReplyPart.Text -> Markdown(s.s)
        is ReplyPart.Media -> InlineFile(Internal.fileName(s.path), vm)
    } }
}

/** A MEDIA file inside a reply: the same card as an Inbox file; its state comes from the PC file records by name. */
@Composable
fun InlineFile(name: String, vm: ChatViewModel) {
    val pc = vm.c.pc
    val sid = vm.c.cur.id.orEmpty()
    val key = "$sid|$name"
    val known = pc.fileUris[key]
    val stored = remember(key) { pc.store.sessionFiles(sid).lastOrNull { it.first == name }?.second }
    val uri = (known ?: stored)?.takeIf { it.isNotBlank() }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        FileBubble(name, uri, pc.fileProgress[name], onCancel = { pc.cancelDownload(name) }, onDownload = { u -> pc.exportInbox(name, u) }) { pc.saveInbox(name, vm.c.cur.id) }
    }
}

/** Hermes's thinking and tool calls behind a reply: collapsed by default, each tool's output collapsible too. */
@Composable
fun StepsView(steps: List<Step>, live: Boolean = false) {
    var userOpen by remember { mutableStateOf<Boolean?>(null) }
    // Collapse once the reply completes; while streaming, leave the user's explicit choice alone.
    LaunchedEffect(live) { if (!live) userOpen = false }
    val open = userOpen ?: false
    val tools = steps.count { it.kind == "tool" }; val thinking = steps.any { it.kind == "thinking" }
    val summary = listOfNotNull(if (thinking) stringResource(R.string.steps_thinking) else null,
        if (tools > 0) stringResource(R.string.steps_tools, tools) else null).joinToString(" · ")
    Row(Modifier.clickable { userOpen = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(" " + summary + if (!open && tools > 0) "  ·  " + steps.filter { it.kind == "tool" }.joinToString(", ") { it.name }.take(80) else "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) androidx.compose.foundation.text.selection.SelectionContainer { Column(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { st ->
            if (st.kind == "thinking") Text("💭 " + st.detail.take(4000), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                var outOpen by remember(st.toolId ?: st.name) { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text("🔧 " + st.name, style = MaterialTheme.typography.labelMedium)
                        if (st.detail.isNotBlank()) Text(st.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
                        val stOut = st.output
                        when {
                            stOut == null -> Text(stringResource(R.string.steps_running), style = MaterialTheme.typography.labelSmall)
                            stOut.isNotBlank() -> {
                                Text((if (outOpen) "▾ " else "▸ ") + stringResource(R.string.steps_output), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { outOpen = !outOpen }.padding(top = 4.dp))
                                if (outOpen) Text(stOut, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    } }
}

/** Unsent message: red ! with resend / edit / delete. */
@Composable
fun ColumnScope.FailedActions(m: UiMsg, vm: ChatViewModel) {
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Box(Modifier.align(Alignment.End)) {
        Row(Modifier.clickable { menu = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, stringResource(R.string.send_failed), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(" " + stringResource(R.string.send_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.resend)) }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                onClick = { menu = false; vm.c.msg.resendFailed(m) }, enabled = !vm.c.streaming)
            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { menu = false; editing = true }, enabled = !vm.c.streaming)
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { menu = false; vm.c.msg.deleteFailed(m) })
        }
    }
    if (editing) {
        var t by remember { mutableStateOf(m.resend ?: m.text) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.edit_message)) },
            text = { OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), maxLines = 10) },
            confirmButton = { Button(onClick = { editing = false; vm.c.msg.editFailed(m, t) }, enabled = t.isNotBlank()) { Text(stringResource(R.string.send)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Send time: see [Formats.fmtTime]. */

/** Copy / edit (own messages) / branch from here. Edit and branch need the stored row id. */
@Composable
fun ColumnScope.MessageActions(m: UiMsg, vm: ChatViewModel) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.copied)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        val mod = Modifier.size(32.dp)
        if (m.ts > 0) Text(Formats.fmtTime(m.ts), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        if (m.tokIn != null || m.tokOut != null) Text(
            listOfNotNull(m.prefillTps?.let { "prefill " + fmtSpeed(it) }, m.genTps?.let { "generation " + fmtSpeed(it) },
                fmtTok((m.tokIn ?: 0) + (m.tokOut ?: 0)) + " tokens").joinToString(" · "),
            maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(Internal.stripMedia(m.text)))
            android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show() }, mod) {
            Icon(Icons.Default.ContentCopy, stringResource(R.string.copy), Modifier.size(16.dp)) }
        if (m.id != null && !vm.c.streaming) {
            if (m.role == "user") IconButton(onClick = { editing = true }, mod) { Icon(Icons.Default.Edit, stringResource(R.string.edit), Modifier.size(16.dp)) }
            IconButton(onClick = { vm.c.msg.branchFrom(m) }, mod) { Icon(Icons.Default.CallSplit, stringResource(R.string.branch), Modifier.size(16.dp)) }
        }
    }
    if (editing) {
        var t by remember { mutableStateOf(m.text) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.edit_message)) },
            text = { Column { Text(stringResource(R.string.edit_warning), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp)); OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), maxLines = 10) } },
            confirmButton = { Button(onClick = { editing = false; vm.c.msg.editMessage(m, t) }, enabled = t.isNotBlank()) { Text(stringResource(R.string.send)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
