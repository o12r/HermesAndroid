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

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    // In-app language override: every resource lookup in this activity uses the chosen locale.
    override fun attachBaseContext(newBase: Context) {
        val langCtx = L10n.wrap(newBase, SecureStore(newBase).lang)
        val cfg = android.content.res.Configuration(langCtx.resources.configuration)
        cfg.fontScale = cfg.fontScale * SecureStore(newBase).fontScale
        super.attachBaseContext(langCtx.createConfigurationContext(cfg))
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handlePair(intent); handleOpen(intent) }

    /** Notification tap: jump to the chat that needs an answer. */
    private fun handleOpen(intent: android.content.Intent?) {
        val id = intent?.getStringExtra(Notify.EXTRA_SESSION) ?: return
        vm.c.msg.openFromNotification(intent.getStringExtra(Notify.EXTRA_PROFILE), id)
        intent.removeExtra(Notify.EXTRA_SESSION)
    }

    private val scanLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("text")?.let { handlePair(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(it))) }
    }
    fun scanPairCode() = scanLauncher.launch(android.content.Intent(this, ScanActivity::class.java))

    /** hermes://pair?url=&lan=&key= from the PC installer: save the connection and connect. */
    private fun handlePair(intent: android.content.Intent?) {
        val u = intent?.data ?: return
        if (u.scheme != "hermes" || u.host != "pair") return
        val url = u.getQueryParameter("url").orEmpty(); val key = u.getQueryParameter("key").orEmpty()
        if (url.isBlank() || key.isBlank()) return
        vm.c.conn.saveSettings(url, u.getQueryParameter("lan").orEmpty(), key)
        android.widget.Toast.makeText(this, getString(R.string.paired), android.widget.Toast.LENGTH_LONG).show()
        setIntent(android.content.Intent())   // don't re-apply on rotation
    }

    override fun onStart() { super.onStart(); Notify.visible = true; vm.c.seen(); vm.c.conn.onForeground() }
    override fun onStop() { Notify.visible = false; super.onStop() }
    override fun onDestroy() { if (isFinishing) KeepAliveService.stop(this); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        KeepAliveService.start(this)
        handlePair(intent); handleOpen(intent)
        KeepAliveService.askBatteryExemption(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        setContent {
            val ctx = LocalContext.current
            val sysDark = isSystemInDarkTheme()
            val scheme = when (vm.c.theme) {
                "light" -> dynamicLightColorScheme(ctx)
                "dark" -> darkGray(dynamicDarkColorScheme(ctx))
                "black" -> trueBlack(dynamicDarkColorScheme(ctx))
                else -> if (sysDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            }
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { App(vm) { recreate() } } }
        }
    }
}

/** AMOLED true black: pure-black backgrounds, near-black containers, the dynamic accents kept. */
fun trueBlack(d: ColorScheme) = d.copy(
    background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceBright = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF080808), surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF151515), surfaceContainerHighest = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFF161616), inverseOnSurface = Color.Black,
)

/** Standard dark theme: dark-grey surfaces (clearly lighter than AMOLED black), the dynamic accents kept. */
fun darkGray(d: ColorScheme) = d.copy(
    background = Color(0xFF1A1A1A), surface = Color(0xFF1A1A1A), surfaceDim = Color(0xFF141414), surfaceBright = Color(0xFF2E2E2E),
    surfaceContainerLowest = Color(0xFF121212), surfaceContainerLow = Color(0xFF1C1C1C), surfaceContainer = Color(0xFF222222),
    surfaceContainerHigh = Color(0xFF292929), surfaceContainerHighest = Color(0xFF303030),
    surfaceVariant = Color(0xFF262626), inverseOnSurface = Color(0xFF141414),
)

/** Jump to Settings → context group (the chat page wires its context button to this). */
fun ChatViewModel.openContextSettings() { c.openSettingsSection("chat") }
/** Jump to Settings → default-models group (the chat page wires its model button to this). */
fun ChatViewModel.openModelSettings() { c.openSettingsSection("model") }
