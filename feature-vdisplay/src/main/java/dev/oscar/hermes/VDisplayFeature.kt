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

/** Optional feature: PC screens + virtual display page, elevation prompts, the 🖥 tab marker. */
class VDisplayFeature : UiFeature {
    override val id = "vdisplay"
    lateinit var ctl: ScreenController
    override fun attach(hub: ChatController) { ctl = ScreenController(hub) }
    override fun hasPage(kind: String) = kind == "pc"
    override fun pageTitleKey(kind: String) = if (kind == "pc") Str.pc else null
    override fun pageEntered(kind: String) { if (kind == "pc") ctl.loadScreens() }
    override fun tabBadge(chatId: String) = if (chatId in ctl.vdAgents) "\uD83D\uDDA5 " else null
    override fun pageIcon(kind: String) = if (kind == "pc") Icons.Default.Monitor else null
    override fun pinItems() = listOf(PinItem("screens", R.string.vdisplay, Icons.Default.Monitor) { it.openPage("pc") })
    @Composable override fun Page(vm: ChatViewModel, kind: String) { ScreensScreen(vm) }
    @Composable override fun Overlay(vm: ChatViewModel) {
    LaunchedEffect(Unit) { vm.c.scr.pollElevate() }
    vm.c.scr.elevate.firstOrNull()?.let { e ->
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.elevate_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.elevate_body))
                Text(e.optString("exe"), fontFamily = FontFamily.Monospace)
                val args = e.optJSONArray("args")
                if (args != null && args.length() > 0) Text((0 until args.length()).joinToString(" ") { args.optString(it) }, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.elevate_publisher, e.optString("publisher")), style = MaterialTheme.typography.bodySmall)
                Text("SHA-256 " + e.optString("sha256").take(16) + "…", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton({ vm.c.scr.decideElevate(e.optString("id"), true) }) { Text(stringResource(R.string.elevate_allow)) } },
            dismissButton = { OutlinedButton({ vm.c.scr.decideElevate(e.optString("id"), false) }) { Text(stringResource(R.string.deny)) } })
    }
    }
}

val ChatController.scr: ScreenController get() = feature<VDisplayFeature>()!!.ctl
