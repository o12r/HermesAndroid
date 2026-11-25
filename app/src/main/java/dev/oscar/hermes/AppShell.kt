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
fun App(vm: ChatViewModel, recreate: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reconnect(); vm.checkUpdates(manual = false) }
    BackHandler(vm.screen != Screen.Sessions && vm.store.hasKey) {
        if (vm.screen == Screen.Screens || vm.screen == Screen.Settings) vm.leavePage()
        else vm.openPage("sessions") }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        HotfixBanner(vm)
        Banner(vm)
        Box(Modifier.weight(1f)) { when (vm.screen) {
            Screen.Settings -> SettingsScreen(vm, recreate)
            Screen.Sessions -> SessionsScreen(vm)
            Screen.Chat -> ChatScreen(vm,
                onOpenContextSettings = { vm.openSettingsSection("chat") },
                onOpenModelSettings = { vm.openSettingsSection("model") })
            Screen.Screens -> ScreensScreen(vm)
            Screen.Files -> PcFileBrowser(vm, pick = false, start = null, onPick = {}, onClose = { vm.leavePage() }, asPage = true)
        } }
    }
    UpdateDialog(vm)
    LaunchedEffect(Unit) { vm.pollElevate() }
    vm.elevate.firstOrNull()?.let { e ->
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.elevate_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.elevate_body))
                Text(e.optString("exe"), fontFamily = FontFamily.Monospace)
                val args = e.optJSONArray("args")
                if (args != null && args.length() > 0) Text((0 until args.length()).joinToString(" ") { args.optString(it) }, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.elevate_publisher, e.optString("publisher")), style = MaterialTheme.typography.bodySmall)
                Text("SHA-256 " + e.optString("sha256").take(16) + "…", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton({ vm.decideElevate(e.optString("id"), true) }) { Text(stringResource(R.string.elevate_allow)) } },
            dismissButton = { OutlinedButton({ vm.decideElevate(e.optString("id"), false) }) { Text(stringResource(R.string.deny)) } })
    }
    vm.modelConfirm?.let { (msg, _) ->
        AlertDialog(onDismissRequest = { vm.confirmModel(false) }, title = { Text(stringResource(R.string.confirm_model_title)) },
            text = { Text(msg) },
            confirmButton = { TextButton({ vm.confirmModel(true) }) { Text(stringResource(R.string.switch_anyway)) } },
            dismissButton = { TextButton({ vm.confirmModel(false) }) { Text(stringResource(android.R.string.cancel)) } })
    }
    vm.compressAsk?.let { c ->
        AlertDialog(onDismissRequest = { vm.answerCompress(false) }, title = { Text(stringResource(R.string.compress_ask_title)) },
            text = { Text(stringResource(R.string.compress_ask_msg, c.ctxUsed, c.ctxMax)) },
            confirmButton = { TextButton({ vm.answerCompress(true) }) { Text(stringResource(R.string.compress_now)) } },
            dismissButton = { TextButton({ vm.answerCompress(false) }) { Text(stringResource(android.R.string.cancel)) } })
    }
    vm.approval?.let { a ->
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.approve_q)) },
            text = { Column { if (a.description.isNotBlank()) Text(a.description); Spacer(Modifier.height(8.dp))
                Text(a.command, fontFamily = FontFamily.Monospace) } },
            confirmButton = { Row { a.choices.filter { it != "deny" }.forEach { c ->
                TextButton(onClick = { vm.answerApproval(c) }) { Text(when (c) {
                    "once" -> stringResource(R.string.approve_once); "session" -> stringResource(R.string.approve_session)
                    "always" -> stringResource(R.string.approve_always); else -> c }) } } } },
            dismissButton = { OutlinedButton(onClick = { vm.answerApproval("deny") }) { Text(stringResource(R.string.deny)) } })
    }
    vm.clarify?.let { c ->
        val answers = remember(c.reqId) { mutableStateMapOf<String, String>() }
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.hermes_asks)) },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                c.questions.forEach { q ->
                    Text(q.question)
                    if (q.multi) {
                        Text(stringResource(R.string.pick_any), style = MaterialTheme.typography.labelSmall)
                        q.choices.forEach { ch ->
                            val key = q.qid + "\u0000" + ch
                            val on = answers[key] == "1"
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { answers[key] = if (on) "" else "1" }) {
                                Checkbox(checked = on, onCheckedChange = { answers[key] = if (it) "1" else "" }); Text(ch) }
                        }
                        OutlinedTextField(answers[q.qid] ?: "", { answers[q.qid] = it },
                            placeholder = { Text(stringResource(R.string.other)) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        q.choices.forEach { ch -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { answers[q.qid] = ch }) {
                            RadioButton(selected = answers[q.qid] == ch, onClick = { answers[q.qid] = ch }); Text(ch) } }
                        OutlinedTextField(if (q.choices.contains(answers[q.qid])) "" else answers[q.qid] ?: "", { answers[q.qid] = it },
                            placeholder = { Text(stringResource(if (q.choices.isEmpty()) R.string.answer else R.string.other)) }, modifier = Modifier.fillMaxWidth())
                    }
                } } },
            confirmButton = { Button(onClick = { vm.answerClarify(c.questions.associate { q ->
                // Multi-select: Hermes takes a JSON array of the chosen labels (typed text, if any, is its own entry).
                q.qid to if (!q.multi) (answers[q.qid] ?: "") else {
                    val picked = q.choices.filter { answers[q.qid + "\u0000" + it] == "1" } + listOfNotNull(answers[q.qid]?.trim()?.ifBlank { null })
                    if (picked.isEmpty()) "" else org.json.JSONArray(picked).toString()
                } }) }) { Text(stringResource(R.string.submit)) } },
            dismissButton = { TextButton(onClick = { vm.answerClarify(null) }) { Text(stringResource(R.string.skip)) } })
    }
}
