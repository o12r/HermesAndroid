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
fun RuntimeChips(vm: ChatViewModel, onOpenContextSettings: () -> Unit, onOpenModelSettings: () -> Unit) {
    var effortOpen by remember { mutableStateOf(false) }
    val def = stringResource(R.string.default_)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f, fill = false)) {
            val label = if (vm.model.isBlank()) stringResource(R.string.model_default, vm.modelOptions?.currentModel?.ifBlank { null } ?: "…")
                else vm.model.substringAfter('|')
            FilterChip(selected = vm.model.isNotBlank(), enabled = !vm.switching, onClick = { vm.modelMenuOpen = true; if (vm.modelOptions == null) vm.loadMeta() },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.Memory, null, Modifier.size(16.dp)) }, modifier = Modifier.widthIn(max = 260.dp))
            DropdownMenu(vm.modelMenuOpen, { vm.modelMenuOpen = false }, Modifier.heightIn(max = 480.dp)) {
                val cur = vm.modelOptions?.currentModel.orEmpty()
                DropdownMenuItem(text = { Text(stringResource(R.string.server_default) + if (cur.isNotBlank()) " ($cur)" else "") },
                    onClick = { vm.chooseModel(""); vm.modelMenuOpen = false },
                    trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.model.isBlank()) Icon(Icons.Default.Check, null)
                        IconButton(onClick = { vm.modelMenuOpen = false; onOpenModelSettings() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Settings, stringResource(R.string.model_settings), Modifier.size(18.dp))
                        }
                    } })
                val groups = vm.modelOptions?.choices.orEmpty().groupBy { it.providerName }
                if (vm.modelOptions == null) DropdownMenuItem(text = { Text(stringResource(R.string.loading_models)) }, onClick = {}, enabled = false)
                val expanded = remember(vm.modelMenuOpen) { mutableStateMapOf<String, Boolean>() }
                val activeKey = vm.model.ifBlank { null }
                groups.forEach { (provider, ms) ->
                    HorizontalDivider()
                    val isCurrent = ms.any { it.key == activeKey || (activeKey == null && it.model == cur) }
                    val open = expanded[provider] == true
                    DropdownMenuItem(
                        text = { Text(provider + "  (" + ms.size + ")", style = MaterialTheme.typography.labelLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        leadingIcon = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                        trailingIcon = { if (isCurrent) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = if (isCurrent) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
                        onClick = { expanded[provider] = !open })
                    if (open) ms.forEach { m ->
                        val sel = m.key == activeKey || (activeKey == null && m.model == cur)
                        DropdownMenuItem(text = { Text(m.model, color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            onClick = { vm.chooseModel(m.key); vm.modelMenuOpen = false }, contentPadding = PaddingValues(start = 40.dp, end = 12.dp),
                            trailingIcon = { if (sel) Icon(Icons.Default.Check, null) })
                    }
                }
            }
        }
        Box {
            FilterChip(selected = vm.effort.isNotBlank(), enabled = !vm.switching, onClick = { effortOpen = true },
                label = { Text(vm.effort.ifBlank { def }, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.Psychology, null, Modifier.size(16.dp)) })
            DropdownMenu(effortOpen, { effortOpen = false }) {
                SecureStore.EFFORTS.forEach { e -> DropdownMenuItem(text = { Text(e.ifBlank { def }) },
                    onClick = { vm.chooseEffort(e); effortOpen = false }, trailingIcon = { if (vm.effort == e) Icon(Icons.Default.Check, null) }) }
            }
        }
        val c = vm.cur
        val tasks = c.messages.mapNotNull { it.task }
        if (tasks.isNotEmpty()) {
            var tasksOpen by remember { mutableStateOf(false) }
            val running = tasks.count { it.status == "running" }
            Box {
                AssistChip(onClick = { tasksOpen = true },
                    label = { Text(running.toString(), style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Default.Schedule, stringResource(R.string.tasks_chip), Modifier.size(16.dp)) })
                DropdownMenu(tasksOpen, { tasksOpen = false }) {
                    tasks.forEach { t ->
                        val st = stringResource(when (t.status) { "running" -> R.string.task_running; "failed" -> R.string.task_failed; else -> R.string.task_done })
                        val kind = stringResource(when (t.kind) { "bg" -> R.string.task_background; "proc" -> R.string.task_process; else -> R.string.task_subagent })
                        DropdownMenuItem(text = { Text(kind + (t.chat.title?.takeIf { it.isNotBlank() && t.kind != "bg" }?.let { " · " + it.take(40) } ?: "") + " — " + st,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) },
                            onClick = { tasksOpen = false; vm.openTask(t) })
                    }
                }
            }
        }
        val cfgMax = vm.ctxSettings?.opt("context_length")?.toString()?.toDoubleOrNull()?.toLong() ?: 0L
        LaunchedEffect(c.id == null) { if (c.id == null && vm.ctxSettings == null && vm.store.hasKey) vm.loadCtxSettings() }
        val usage = if (c.ctxMax <= 0 && c.ctxUsed <= 0 && c.id == null) (if (cfgMax > 0) "0/" + fmtTok(cfgMax) else "—")
            else if (c.ctxMax > 0) fmtTok(c.ctxUsed) + "/" + fmtTok(c.ctxMax)
            else if (c.ctxUsed > 0) fmtTok(c.ctxUsed) else stringResource(R.string.ctx_title)
        AssistChip(onClick = onOpenContextSettings,
            label = { Text(usage, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall,
                color = if (c.ctxMax > 0 && c.ctxUsed * 100 / c.ctxMax >= 80) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = { Icon(Icons.Default.DataUsage, null, Modifier.size(16.dp)) })
    }
}
