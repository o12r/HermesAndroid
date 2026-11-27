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
fun HotfixBanner(vm: ChatViewModel) {
    val zh = L10n.isZh(LocalContext.current)
    val msg = vm.c.hotfix.banner(zh) ?: return
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Campaign, null)
        Text(msg, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun UpdateDialog(vm: ChatViewModel) {
    val u = vm.c.update
    val force = vm.c.forceUpdate
    var dismissed by remember(u?.versionCode) { mutableStateOf(false) }
    if ((u == null || dismissed) && !force) return
    val src = when (u?.source) { "pc" -> stringResource(R.string.source_pc); "github" -> stringResource(R.string.source_github); else -> "" }
    AlertDialog(onDismissRequest = { if (!force) dismissed = true },
        title = { Text(if (u != null) stringResource(R.string.update_available, u.versionName, u.versionCode) else stringResource(R.string.updates)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (force) Text(stringResource(R.string.update_required), color = MaterialTheme.colorScheme.error)
            if (u != null) { Text(stringResource(R.string.update_from, src), style = MaterialTheme.typography.labelMedium)
                if (u.notes.isNotBlank()) Text(u.notes, maxLines = 12, overflow = TextOverflow.Ellipsis) }
            vm.c.updateProgress?.let { p -> LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.downloading, p)) }
            vm.c.updateStatus?.let { Text(it) }
        } },
        confirmButton = { if (u != null) Button(onClick = { vm.c.installUpdate() }, enabled = vm.c.updateProgress == null) { Text(stringResource(R.string.install)) }
            else TextButton(onClick = { vm.c.checkUpdates(manual = true) }) { Text(stringResource(R.string.check_updates)) } },
        dismissButton = {
            if (vm.c.updateProgress != null) TextButton(onClick = { vm.c.cancelUpdate() }) { Text(stringResource(R.string.cancel_update)) }
            else if (!force) TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.later)) } })
}

@Composable
fun Banner(vm: ChatViewModel) {
    val msg = vm.c.error ?: return
    val c = if (vm.c.offline) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Row(Modifier.fillMaxWidth().background(c).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (vm.c.offline) Icons.Default.CloudOff else Icons.Default.ErrorOutline, null)
        Text(msg, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        val ctx = LocalContext.current
        IconButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(msg)); android.widget.Toast.makeText(ctx, ctx.getString(R.string.copied), android.widget.Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ContentCopy, stringResource(R.string.copy), modifier = Modifier.size(18.dp)) }
        TextButton(onClick = { vm.c.error = null; vm.c.reconnect() }) { Text(stringResource(R.string.retry)) }
        TextButton(onClick = { vm.c.cancelRequest() }) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
fun ProfileMenu(vm: ChatViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true; vm.c.scanProfiles() }, label = { Text(vm.c.profile) }, leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(18.dp)) })
        DropdownMenu(open, { open = false }) {
            vm.c.orderedProfiles.forEach { (p, desc) -> DropdownMenuItem(text = { Column { Text(p)
                    if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                onClick = { open = false; vm.c.setProfileTo(p) }, modifier = Modifier.widthIn(max = 360.dp),
                trailingIcon = { if (p == vm.c.profile) Icon(Icons.Default.Check, null) }) }
        }
    }
}

/** Profile order editor: drag a row by its handle; the order is saved when the drag ends. */
@Composable
fun ReorderProfiles(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val order = remember { mutableStateListOf<String>().apply { addAll(vm.c.orderedProfiles.map { it.first }) } }
    var dragging by remember { mutableStateOf<String?>(null) }
    var off by remember { mutableFloatStateOf(0f) }
    val rowH = 52.dp
    val hPx = with(androidx.compose.ui.platform.LocalDensity.current) { rowH.toPx() }
    val cs = MaterialTheme.colorScheme
    Column(modifier.verticalScroll(rememberScrollState())) {
        order.toList().forEach { prof -> key(prof) {
            val isDrag = dragging == prof
            Row(Modifier.fillMaxWidth().height(rowH).zIndex(if (isDrag) 1f else 0f)
                .offset { androidx.compose.ui.unit.IntOffset(0, if (isDrag) off.toInt() else 0) }
                .background(if (isDrag) cs.primaryContainer else cs.surface).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(prof, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.DragHandle, null, Modifier.size(40.dp).padding(8.dp).pointerInput(prof) {
                    detectDragGestures(onDragStart = { dragging = prof; off = 0f },
                        onDragEnd = { dragging = null; off = 0f; vm.c.saveProfileOrder(order.toList()) },
                        onDragCancel = { dragging = null; off = 0f; vm.c.saveProfileOrder(order.toList()) }) { c, d ->
                        c.consume(); off += d.y
                        val i = order.indexOf(prof)
                        if (off > hPx / 2 && i < order.lastIndex) { order.add(i + 1, order.removeAt(i)); off -= hPx }
                        else if (off < -hPx / 2 && i > 0) { order.add(i - 1, order.removeAt(i)); off += hPx }
                    }
                })
            }
            HorizontalDivider()
        } }
    }
}
