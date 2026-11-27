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

/** Full-screen, static screenshot: pinch to zoom, drag to pan, tap ✕ to close. */
@Composable
fun ZoomImage(img: ImageBitmap, label: String, busy: Boolean, refresh: () -> Unit, close: () -> Unit) {
    // Forced landscape while the screenshot is maximised; restored on exit.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        var c = ctx; while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val act = c as? android.app.Activity
        val old = act?.requestedOrientation
        act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (old != null) act.requestedOrientation = old }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Hide the system bars for the maximised view.
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            val w = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            if (w != null) androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            onDispose { }
        }
        var scale by remember { mutableStateOf(1f) }
        var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val st = androidx.compose.foundation.gestures.rememberTransformableState { z, pan, _ ->
            scale = (scale * z).coerceIn(1f, 8f); off = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else off + pan }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(img, label, Modifier.fillMaxSize().androidx_transform(st).graphicsLayerZoom(scale, off), contentScale = ContentScale.Fit)
            IconButton(onClick = close, Modifier.align(Alignment.TopStart).padding(4.dp)) { Icon(Icons.Default.Close, stringResource(R.string.back), tint = Color.White) }
            IconButton(onClick = refresh, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp), enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, stringResource(R.string.screen_refresh), tint = Color.White) }
        }
    }
}

private fun Modifier.androidx_transform(st: androidx.compose.foundation.gestures.TransformableState) =
    this.then(Modifier.transformable(st))
private fun Modifier.graphicsLayerZoom(s: Float, o: androidx.compose.ui.geometry.Offset) =
    this.then(Modifier.graphicsLayer(scaleX = s, scaleY = s, translationX = o.x, translationY = o.y))
