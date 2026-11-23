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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.scrollBy
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
import java.text.DateFormat
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
        vm.openFromNotification(intent.getStringExtra(Notify.EXTRA_PROFILE), id)
        intent.removeExtra(Notify.EXTRA_SESSION)
    }

    /** hermes://pair?url=&lan=&key= from the PC installer: save the connection and connect. */
    private fun handlePair(intent: android.content.Intent?) {
        val u = intent?.data ?: return
        if (u.scheme != "hermes" || u.host != "pair") return
        val url = u.getQueryParameter("url").orEmpty(); val key = u.getQueryParameter("key").orEmpty()
        if (url.isBlank() || key.isBlank()) return
        vm.saveSettings(url, u.getQueryParameter("lan").orEmpty(), key)
        android.widget.Toast.makeText(this, getString(R.string.paired), android.widget.Toast.LENGTH_LONG).show()
        setIntent(android.content.Intent())   // don't re-apply on rotation
    }

    override fun onStart() { super.onStart(); Notify.visible = true; vm.seen(); vm.onForeground() }
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
            val scheme = when (vm.theme) {
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
fun ChatViewModel.openContextSettings() { openSettingsSection("chat") }
/** Jump to Settings → default-models group (the chat page wires its model button to this). */
fun ChatViewModel.openModelSettings() { openSettingsSection("model") }

@Composable
fun App(vm: ChatViewModel, recreate: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reconnect(); vm.checkUpdates(manual = false) }
    BackHandler(vm.screen != Screen.Sessions && vm.store.hasKey) {
        if (vm.screen == Screen.Screens) vm.screen = vm.screensBack
        else if (vm.screen == Screen.Settings && vm.settingsBack == Screen.Chat) { vm.screen = Screen.Chat; vm.settingsBack = Screen.Sessions }
        else { vm.screen = Screen.Sessions; vm.refreshSessions() } }
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
        } }
        // Bottom navigation: sessions / screens / settings. The chat is full-screen (its tabs lead back).
        if (vm.screen != Screen.Chat && vm.store.hasKey && !(vm.screen == Screen.Settings && vm.settingsBack == Screen.Chat))
            NavigationBar(windowInsets = WindowInsets(0)) {
                NavigationBarItem(vm.screen == Screen.Sessions, { vm.screen = Screen.Sessions; vm.refreshSessions() },
                    icon = { Icon(Icons.Default.Forum, null) }, label = { Text(stringResource(R.string.sessions)) })
                NavigationBarItem(vm.screen == Screen.Screens, { if (vm.screen != Screen.Screens) vm.openScreens(Screen.Sessions) },
                    icon = { Icon(Icons.Default.Monitor, null) }, label = { Text(stringResource(R.string.pc)) })
                NavigationBarItem(vm.screen == Screen.Settings, { vm.settingsBack = Screen.Sessions; vm.screen = Screen.Settings },
                    icon = { BadgedBox(badge = { if (vm.update != null) Badge() }) { Icon(Icons.Default.Settings, null) } },
                    label = { Text(stringResource(R.string.settings)) })
            }
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

@Composable
fun HotfixBanner(vm: ChatViewModel) {
    val zh = L10n.isZh(LocalContext.current)
    val msg = vm.hotfix.banner(zh) ?: return
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Campaign, null)
        Text(msg, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun UpdateDialog(vm: ChatViewModel) {
    val u = vm.update
    val force = vm.forceUpdate
    var dismissed by remember(u?.versionCode) { mutableStateOf(false) }
    if ((u == null || dismissed) && !force) return
    val src = when (u?.source) { "pc" -> stringResource(R.string.source_pc); "github" -> stringResource(R.string.source_github); else -> "" }
    AlertDialog(onDismissRequest = { if (!force) dismissed = true },
        title = { Text(if (u != null) stringResource(R.string.update_available, u.versionName, u.versionCode) else stringResource(R.string.updates)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (force) Text(stringResource(R.string.update_required), color = MaterialTheme.colorScheme.error)
            if (u != null) { Text(stringResource(R.string.update_from, src), style = MaterialTheme.typography.labelMedium)
                if (u.notes.isNotBlank()) Text(u.notes, maxLines = 12, overflow = TextOverflow.Ellipsis) }
            vm.updateProgress?.let { p -> LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.downloading, p)) }
            vm.updateStatus?.let { Text(it) }
        } },
        confirmButton = { if (u != null) Button(onClick = { vm.installUpdate() }, enabled = vm.updateProgress == null) { Text(stringResource(R.string.install)) }
            else TextButton(onClick = { vm.checkUpdates(manual = true) }) { Text(stringResource(R.string.check_updates)) } },
        dismissButton = {
            if (vm.updateProgress != null) TextButton(onClick = { vm.cancelUpdate() }) { Text(stringResource(R.string.cancel_update)) }
            else if (!force) TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.later)) } })
}

@Composable
fun Banner(vm: ChatViewModel) {
    val msg = vm.error ?: return
    val c = if (vm.offline) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Row(Modifier.fillMaxWidth().background(c).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (vm.offline) Icons.Default.CloudOff else Icons.Default.ErrorOutline, null)
        Text(msg, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { vm.error = null; vm.reconnect() }) { Text(stringResource(R.string.retry)) }
        TextButton(onClick = { vm.cancelRequest() }) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
fun ProfileMenu(vm: ChatViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true; vm.scanProfiles() }, label = { Text(vm.profile) }, leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(18.dp)) })
        DropdownMenu(open, { open = false }) {
            vm.orderedProfiles.forEach { (p, desc) -> DropdownMenuItem(text = { Column { Text(p)
                    if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                onClick = { open = false; vm.setProfileTo(p) }, modifier = Modifier.widthIn(max = 360.dp),
                trailingIcon = { if (p == vm.profile) Icon(Icons.Default.Check, null) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(vm: ChatViewModel) {
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    var moving by remember { mutableStateOf<Session?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deletingProject by remember { mutableStateOf<Project?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    // Collapsed state per project; the active project (or Home) starts open.
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val busy by remember { derivedStateOf { vm.busyIds() } }
    // Group sessions; anything the tree doesn't know about goes to Home.
    fun buildGroups(sessions: List<Session>, projects: List<Project>, activeProject: String?): List<Pair<Project, List<Session>>> {
        val byId = sessions.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val list = projects.filter { showArchived || !it.archived }.map { p -> p to p.sessionIds.mapNotNull { byId[it] }.also { l -> seen += l.map { it.id } } }
        val rest = sessions.filter { it.id !in seen && projects.none { p -> it.id in p.sessionIds } }
        val home = list.find { it.first.isHome }
        // Projects not loaded yet: one open, header-less list instead of a collapsed "Home".
        val homeP = home?.first ?: Project(if (projects.isEmpty()) "__all" else "__home", "Home", null, true, false, false, emptyList())
        val homeList = (home?.second.orEmpty() + rest).distinctBy { it.id }.sortedByDescending { it.lastActive ?: 0.0 }
        return list.filter { !it.first.isHome }.map { (p, l) -> p to l.sortedByDescending { it.lastActive ?: 0.0 } }
            .sortedWith(compareByDescending<Pair<Project, List<Session>>> { it.first.id == activeProject }.thenByDescending { it.second.firstOrNull()?.lastActive ?: 0.0 }) +
            listOf(homeP to homeList)
    }
    val allGroups = remember(vm.sessions, vm.projects, vm.activeProject, vm.otherData, vm.profile, showArchived) {
        vm.orderedProfiles.associate { (pr, _) ->
            pr to (if (pr == vm.profile) buildGroups(vm.sessions, vm.projects, vm.activeProject)
                   else vm.otherData[pr]?.let { buildGroups(it.first, it.second, it.third) } ?: emptyList())
        }
    }
    Scaffold(
        topBar = { Column { TopAppBar(title = { Text(stringResource(R.string.app_name), maxLines = 1, softWrap = false) }, actions = {
            IconButton(onClick = { vm.scanProfiles(); vm.refreshSessions() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
        }, windowInsets = WindowInsets(0))
            ChatTabs(vm, onList = true) } },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { vm.newChat() }, icon = { Icon(Icons.Default.Add, null) },
            text = { Text(vm.fabProject?.let { stringResource(R.string.new_chat_in, if (it.isHome) stringResource(R.string.home) else it.name) } ?: stringResource(R.string.new_chat)) }) },
        contentWindowInsets = WindowInsets(0)
    ) { pad ->
        if (vm.loading && vm.sessions.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(pad))
        val cs = MaterialTheme.colorScheme
        // Compact rows (plain Row instead of ListItem: fewer slots/measure passes per row, fixed heights).
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
          vm.orderedProfiles.forEach { (prof, desc) ->
            val isCur = prof == vm.profile
            val profOpen = prof in vm.expandedProfiles
            val groups = allGroups[prof].orEmpty()
            val projsOf = if (isCur) vm.projects else vm.otherData[prof]?.second.orEmpty()
            val activeOf = if (isCur) vm.activeProject else vm.otherData[prof]?.third
            item(key = "prof:$prof", contentType = "prof") {
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().height(if (isCur) 42.dp else 34.dp)
                    .background(if (isCur) cs.primaryContainer.copy(alpha = 0.45f) else cs.surface)
                    .combinedClickable(onLongClick = { menu = true }) { if (isCur) vm.toggleProfileExpanded(prof) else { vm.enterProfile(prof) } }
                    .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(cs.primary))
                    Icon(if (profOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.padding(start = 8.dp, end = 6.dp).size(18.dp))
                    Column {
                        Text(prof, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = cs.primary)
                        if (profOpen) Text(stringResource(R.string.projects_count, projsOf.count { !it.isHome && !it.archived }),
                            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    Box { IconButton(onClick = { menu = true }, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_project)) }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                onClick = { menu = false; vm.enterProfile(prof); creating = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_chat_here)) }, leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { menu = false; vm.enterProfile(prof); vm.newChatIn(null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                                onClick = { menu = false; vm.moveProfile(prof, -1) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                                onClick = { menu = false; vm.moveProfile(prof, 1) })
                        } }
                }
                HorizontalDivider()
            }
            if (profOpen) groups.forEach { (p, list) ->
                val okey = prof + ":" + p.id
                val isOpen = p.id == "__all" || (open[okey] ?: (p.id == activeOf || (activeOf == null && p.isHome)))
                if (p.id != "__all") item(key = "h:" + okey, contentType = "proj") {
                    var menu by remember { mutableStateOf(false) }
                    val active = p.id == activeOf
                    val picked = isCur && vm.fabProject?.id == p.id
                    Row(Modifier.fillMaxWidth().height(32.dp)
                        .background(if (active || picked) cs.primaryContainer else cs.surfaceContainerLow)
                        .combinedClickable(onLongClick = { menu = true }) { if (!isCur) vm.enterProfile(prof); open[okey] = !isOpen; vm.lastProject = p }.padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(cs.secondary))
                        Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.padding(start = 4.dp, end = 6.dp).size(16.dp))
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (p.isHome) stringResource(R.string.home) else p.name, Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodySmall, color = cs.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(list.size.toString(), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                        Box { IconButton(onClick = { menu = true }, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp)) }
                            DropdownMenu(menu, { menu = false }) {
                                p.path?.let { DropdownMenuItem(text = { Text(it, style = MaterialTheme.typography.labelSmall) }, enabled = false, onClick = {}) }
                                DropdownMenuItem(text = { Text(stringResource(R.string.new_chat_here)) }, leadingIcon = { Icon(Icons.Default.Add, null) },
                                    onClick = { menu = false; vm.enterProfile(prof); vm.newChatIn(p) })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(if (active) R.string.unset_default else R.string.set_default)) },
                                    leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = { menu = false; vm.enterProfile(prof); vm.setActiveProject(if (active) null else p) })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(if (p.archived) R.string.unarchive else R.string.archive)) },
                                    leadingIcon = { Icon(Icons.Default.Archive, null) }, onClick = { menu = false; vm.enterProfile(prof); vm.archiveProject(p, restore = p.archived) })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(R.string.delete_project)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; vm.enterProfile(prof); deletingProject = p })
                            } }
                    }
                }
                if (isOpen) items(list, key = { okey + ":" + it.id }, contentType = { "sess" }) { s ->
                    var menu by remember { mutableStateOf(false) }
                    val time = remember(s.lastActive) { s.lastActive?.let { fmt.format(Date((it * 1000).toLong())) } ?: "" }
                    val ctxRow = vm.sessionCtx[s.id]
                    Row(Modifier.fillMaxWidth().heightIn(min = if (ctxRow != null) 46.dp else 36.dp)
                        .combinedClickable(onLongClick = { menu = true }, onDoubleClick = { renaming = s }) { if (!isCur) vm.enterProfile(prof); if (p.id != "__all") vm.lastProject = p; vm.open(s) }
                        .padding(start = 49.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (s.id in busy) "⏳ " else "") + (s.title ?: s.preview ?: s.id),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (ctxRow != null) Text(fmtTok(ctxRow.first) + "/" + fmtTok(ctxRow.second) + " · " + (ctxRow.first * 100 / ctxRow.second) + "%",
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                                color = if (ctxRow.first * 100 / ctxRow.second >= 80) cs.error else cs.onSurfaceVariant)
                        }
                        Text(time + " · " + s.count, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                        Box { IconButton(onClick = { menu = true }, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_to_project)) }, onClick = { menu = false; vm.enterProfile(prof); moving = s })
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; vm.enterProfile(prof); renaming = s })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; vm.enterProfile(prof); deleting = s })
                            } }
                    }
                }
            }
            if (profOpen) item(key = "archived-toggle:$prof") {
                if (projsOf.any { it.archived }) TextButton(onClick = { showArchived = !showArchived }, Modifier.padding(4.dp)) {
                    Text(stringResource(if (showArchived) R.string.hide_archived else R.string.show_archived)) }
            }
          }
          item(key = "fab-space") { Spacer(Modifier.height(88.dp)) }
        }
    }
    moving?.let { s ->
        val current = vm.projects.find { s.id in it.sessionIds }
        AlertDialog(onDismissRequest = { moving = null }, title = { Text(stringResource(R.string.move_to_project)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                vm.projects.filter { !it.archived }.forEach { p ->
                    val can = p.path != null || p.homeCwd != null
                    ListItem(headlineContent = { Text(if (p.isHome) stringResource(R.string.home) else p.name) },
                        supportingContent = { Text(p.path ?: p.homeCwd ?: stringResource(R.string.move_no_folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { if (p == current) Icon(Icons.Default.Check, null) },
                        modifier = Modifier.clickable(enabled = can && p != current) { vm.moveToProject(s, p); moving = null })
                }
            } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { moving = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var folder by remember { mutableStateOf("C:\\Users\\oscar\\Projects\\") }
        AlertDialog(onDismissRequest = { creating = false }, title = { Text(stringResource(R.string.new_project)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.project_name)) }, singleLine = true)
                OutlinedTextField(folder, { folder = it }, label = { Text(stringResource(R.string.project_folder)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.project_folder_help)) })
            } },
            confirmButton = { TextButton(onClick = { vm.createProject(name, folder); creating = false }, enabled = name.isNotBlank() && folder.isNotBlank()) {
                Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } })
    }
    vm.missingFolder?.let { (name, path) ->
        AlertDialog(onDismissRequest = { vm.missingFolder = null }, title = { Text(stringResource(R.string.folder_missing_title)) },
            text = { Text(stringResource(R.string.folder_missing_text, path)) },
            confirmButton = { Button(onClick = { vm.missingFolder = null; vm.createProject(name, path, mkdir = true) }) { Text(stringResource(R.string.create_folder)) } },
            dismissButton = { TextButton(onClick = { vm.missingFolder = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deletingProject?.let { p ->
        AlertDialog(onDismissRequest = { deletingProject = null }, title = { Text(stringResource(R.string.delete_project_q, p.name)) },
            text = { Text(stringResource(R.string.delete_project_note)) },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p); deletingProject = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deletingProject = null }) { Text(stringResource(R.string.cancel)) } })
    }
    renaming?.let { s ->
        var t by remember { mutableStateOf(s.title ?: "") }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(s, t); renaming = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleting?.let { s ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_session_q)) }, text = { Text(s.title ?: s.id) },
            confirmButton = { TextButton(onClick = { vm.delete(s); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenContextSettings: () -> Unit = {}, onOpenModelSettings: () -> Unit = {}) {
    // The chat.draft is the current chat's draft, so typed text survives switching chats / going back.
    val chat = vm.cur
    val list = remember(chat) { androidx.compose.foundation.lazy.LazyListState(if (chat.follow) 0 else chat.scrollIdx, if (chat.follow) 0 else chat.scrollOff) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> vm.addAttachment(u, true) } }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> vm.addAttachment(u, false) } }
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
        TopAppBar(title = { Column {
                Text(vm.currentTitle ?: stringResource(if (vm.currentId == null) R.string.new_chat else R.string.chat), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.combinedClickable(onDoubleClick = { if (vm.currentId != null) renamingTitle = true }) {})
                val proj = vm.projects.find { p ->
                    (chat.projectId != null && p.id == chat.projectId) || (vm.currentId != null && vm.currentId in p.sessionIds)
                }?.let { p -> if (p.isHome) stringResource(R.string.home) else p.name }
                Text(listOfNotNull(chat.profile, proj).joinToString(" › "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } },
            actions = {
                IconButton(onClick = { vm.refreshChat() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                IconButton(onClick = { vm.settingsBack = Screen.Chat; vm.screen = Screen.Settings }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
            }, windowInsets = WindowInsets(0))
        if (renamingTitle) {
            var t by remember { mutableStateOf(vm.currentTitle ?: "") }
            AlertDialog(onDismissRequest = { renamingTitle = false }, title = { Text(stringResource(R.string.rename)) },
                text = { OutlinedTextField(t, { t = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.currentId?.let { vm.rename(Session(it, null, null, null, 0, null), t) }; renamingTitle = false }) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { renamingTitle = false }) { Text(stringResource(R.string.cancel)) } })
        }
        ChatTabs(vm)
        val jumpScope = rememberCoroutineScope()
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(vm.messages, key = { _, m -> m.uid }) { i, m ->
                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = androidx.compose.ui.graphics.Color(0xFF9E9E9E).copy(alpha = 0.45f))
                Bubble(m, vm, live = vm.streaming && i == vm.messages.lastIndex) }
        }
        if (!follow && list.canScrollForward && vm.messages.isNotEmpty())
            SmallFloatingActionButton(onClick = { follow = true
                    jumpScope.launch { list.scrollToItem(vm.messages.lastIndex); list.scrollBy(1_000_000f) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.jump_newest)) }
        }
        vm.status?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium) }
        if (vm.streaming) LinearProgressIndicator(Modifier.fillMaxWidth())
        SlashSuggestions(vm, chat.draft) { chat.draft = it }
        RuntimeChips(vm, onOpenContextSettings, onOpenModelSettings)
        if (chat.queue.isNotEmpty()) Column(Modifier.padding(horizontal = 8.dp)) {
            chat.queue.toList().forEachIndexed { i, q -> InputChip(selected = false, onClick = { chat.queue.removeAt(i) },
                label = { Text(stringResource(R.string.queued, q), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.unqueue), Modifier.size(16.dp)) }) }
        }
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
                if (focused) {
                    IconButton(onClick = { full = true }, small) { Icon(Icons.Default.OpenInFull, stringResource(R.string.expand_editor)) }
                    if (vm.streaming) IconButton(onClick = { vm.stop() }, small) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) }
                    // Queue: send after the running turn ends.
                    if (vm.streaming && chat.draft.isNotBlank()) IconButton(onClick = { vm.queueMsg(chat.draft); chat.draft = "" }, small) {
                        Icon(Icons.Default.Schedule, stringResource(R.string.queue)) }
                    var attachMenu by remember { mutableStateOf(false) }
                    if (vm.hotfix.attachments) Box {
                        IconButton(onClick = { attachMenu = true }, small, enabled = !vm.streaming) { Icon(Icons.Default.AttachFile, stringResource(R.string.attach)) }
                        DropdownMenu(attachMenu, { attachMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.image)) }, leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = { attachMenu = false; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.text_file)) }, leadingIcon = { Icon(Icons.Default.Description, null) },
                                onClick = { attachMenu = false; pickFile.launch("*/*") })
                        } }
                } else if (vm.streaming) {
                    // One line while streaming: stop only, no steer.
                    IconButton(onClick = { vm.stop() }, small) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) }
                }
                // While Hermes works, Send steers the running turn instead of waiting (only when expanded).
                if (!vm.streaming || (focused && chat.draft.isNotBlank())) IconButton(onClick = { vm.send(chat.draft); chat.draft = "" }, small,
                    enabled = chat.draft.isNotBlank() || (!vm.streaming && vm.attachments.isNotEmpty())) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(if (vm.streaming) R.string.steer else R.string.send)) }
            }
        }
    }
}

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
                label = { Text(stringResource(R.string.thinking, vm.effort.ifBlank { def }), style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.Psychology, null, Modifier.size(16.dp)) })
            DropdownMenu(effortOpen, { effortOpen = false }) {
                SecureStore.EFFORTS.forEach { e -> DropdownMenuItem(text = { Text(e.ifBlank { def }) },
                    onClick = { vm.chooseEffort(e); effortOpen = false }, trailingIcon = { if (vm.effort == e) Icon(Icons.Default.Check, null) }) }
            }
        }
        val c = vm.cur
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

/** Open chats as tabs: ⏳ = Hermes working, ✎ = unsent draft, × closes (the PC session is kept). */
@Composable
fun ChatTabs(vm: ChatViewModel, onList: Boolean = false) {
    // Tabs replace the back button in a chat, so they show even for a single chat; ＋ goes to the session list.
    if (onList && vm.tabs.isEmpty()) return
    val state = rememberLazyListState()
    val idx = vm.tabs.indexOfFirst { it === vm.cur }
    LaunchedEffect(idx) { if (idx >= 0) state.animateScrollToItem(idx) }
    val newChat = stringResource(R.string.new_chat)
    androidx.compose.foundation.lazy.LazyRow(state = state, contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        items(vm.tabs.toList(), key = { System.identityHashCode(it) }) { c ->
            val sel = !onList && c === vm.cur
            val label = buildString {
                if (c.streaming) append("⏳ ")
                if (c.id != null && c.id in vm.vdAgents) append("🖥 ")
                if (c.approval != null || c.clarify != null) append("❓ ")
                if (c.profile != vm.profile) append(c.profile).append(" · ")
                append(c.title ?: newChat)
                if (!sel && c.draft.isNotBlank()) append(" ✎")
            }
            InputChip(selected = sel, onClick = { if (!sel) vm.switchTo(c) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.close_tab), Modifier.size(16.dp).clickable { vm.closeTab(c) }) })
        }
        if (!onList) item(key = "plus") {
            InputChip(selected = false, onClick = { vm.screen = Screen.Sessions; vm.refreshSessions() },
                label = { Icon(Icons.Default.Add, stringResource(R.string.sessions)) })
        }
    }
}

/** Autocomplete for phone + Hermes slash commands and skills while the input is a single "/word". */
@Composable
fun SlashSuggestions(vm: ChatViewModel, input: String, set: (String) -> Unit) {
    if (!input.startsWith("/") || input.contains(' ') || input.contains('\n')) return
    val q = input.drop(1).lowercase()
    val items = vm.commandList()
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Bubble(m: UiMsg, vm: ChatViewModel, live: Boolean = false) {
    if (m.role == "system") {
        Text(m.text, Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary, fontStyle = FontStyle.Italic)
        return
    }
    if (m.role == "file") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { FileBubble(m.text, m.file, vm.fileProgress[m.text]) { vm.saveInbox(m.text, m.session) } }
        return
    }
    val user = m.role == "user"
    // Full-width colour bands (no rounded bubbles) so each line fits more text.
    Box(Modifier.fillMaxWidth()) {
        Surface(shape = androidx.compose.ui.graphics.RectangleShape, color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp), horizontalAlignment = Alignment.Start) {
                if (user) Text(m.text) else AssistantBody(m, live)
                if (m.failed) FailedActions(m, vm) else MessageActions(m, vm)
            }
        }
    }
}

/** An assistant reply: every text segment in the order it happened, then the collapsible thinking/tool details at the bottom. */
@Composable
fun AssistantBody(m: UiMsg, live: Boolean) {
    val textSteps = m.steps.filter { it.kind == "text" }
    val details = m.steps.filter { it.kind != "text" }
    if (textSteps.isNotEmpty()) textSteps.forEach { Markdown(it.detail) }
    else Markdown(if (m.text.isEmpty()) "…" else m.text)
    if (details.isNotEmpty()) StepsView(details, live = live)
    else if (m.tools.isNotEmpty()) Text("🔧 " + m.tools.joinToString(", "), style = MaterialTheme.typography.labelSmall)
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
    if (open) Column(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { st ->
            if (st.kind == "thinking") Text("💭 " + st.detail.take(4000), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                var outOpen by remember(st.toolId ?: st.name) { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text("🔧 " + st.name, style = MaterialTheme.typography.labelMedium)
                        if (st.detail.isNotBlank()) Text(st.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
                        when {
                            st.output == null -> Text(stringResource(R.string.steps_running), style = MaterialTheme.typography.labelSmall)
                            st.output.isNotBlank() -> {
                                Text((if (outOpen) "▾ " else "▸ ") + stringResource(R.string.steps_output), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { outOpen = !outOpen }.padding(top = 4.dp))
                                if (outOpen) Text(st.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
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
                onClick = { menu = false; vm.resendFailed(m) }, enabled = !vm.streaming)
            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { menu = false; editing = true }, enabled = !vm.streaming)
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { menu = false; vm.deleteFailed(m) })
        }
    }
    if (editing) {
        var t by remember { mutableStateOf(m.resend ?: m.text) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.edit_message)) },
            text = { OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), maxLines = 10) },
            confirmButton = { Button(onClick = { editing = false; vm.editFailed(m, t) }, enabled = t.isNotBlank()) { Text(stringResource(R.string.send)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Copy / edit (own messages) / branch from here. Edit and branch need the stored row id. */
@Composable
fun ColumnScope.MessageActions(m: UiMsg, vm: ChatViewModel) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.copied)
    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        val mod = Modifier.size(32.dp)
        if (m.tokIn != null || m.tokOut != null) Text(
            listOfNotNull(m.prefillTps?.let { "prefill " + fmtSpeed(it) }, m.genTps?.let { "gen " + fmtSpeed(it) },
                fmtTok((m.tokIn ?: 0) + (m.tokOut ?: 0)) + " tokens").joinToString(" · "),
            maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
        IconButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(m.text))
            android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show() }, mod) {
            Icon(Icons.Default.ContentCopy, stringResource(R.string.copy), Modifier.size(16.dp)) }
        if (m.id != null && !vm.streaming) {
            if (m.role == "user") IconButton(onClick = { editing = true }, mod) { Icon(Icons.Default.Edit, stringResource(R.string.edit), Modifier.size(16.dp)) }
            IconButton(onClick = { vm.branchFrom(m) }, mod) { Icon(Icons.Default.CallSplit, stringResource(R.string.branch), Modifier.size(16.dp)) }
        }
    }
    if (editing) {
        var t by remember { mutableStateOf(m.text) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.edit_message)) },
            text = { Column { Text(stringResource(R.string.edit_warning), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp)); OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), maxLines = 10) } },
            confirmButton = { Button(onClick = { editing = false; vm.editMessage(m, t) }, enabled = t.isNotBlank()) { Text(stringResource(R.string.send)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Reply body: markdown prose plus XML/JSON/code blocks shown monospace, scrollable sideways, copyable. */
@Composable
fun Markdown(md: String) {
    val segs = remember(md) { Formats.split(md) }
    if (segs.size == 1 && segs[0] is Seg.Md) { MarkdownText((segs[0] as Seg.Md).text); return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (sg in segs) when (sg) {
            is Seg.Md -> MarkdownText(sg.text)
            is Seg.Code -> CodeBlock(sg.lang, sg.text)
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lang.ifBlank { "text" }.uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.copy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { clip.setText(androidx.compose.ui.text.AnnotatedString(code)) }.padding(4.dp))
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(code, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
fun MarkdownText(md: String) {
    val scale = 1f
    val color = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val ctx = LocalContext.current
    val markwon = remember { Markwon.builder(ctx).usePlugin(SoftBreakAddsNewLinePlugin.create()).usePlugin(TablePlugin.create(ctx))
        .usePlugin(StrikethroughPlugin.create()).build() }
    AndroidView(factory = { TextView(it).apply { setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance(); textSize = 15f } },
        update = { it.textSize = 15f * scale; it.setTextColor(color); markwon.setMarkdown(it, md) })
}

@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, recreate: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var relay by remember { mutableStateOf(vm.store.relayUrl) }
    var lan by remember { mutableStateOf(vm.store.lanUrl) }
    // Grouped cards; each opens its own page. Without a key, go straight to Connection.
    var sub by remember { mutableStateOf(if (vm.store.hasKey) "" else "conn") }
    LaunchedEffect(vm.settingsTarget) { vm.settingsTarget?.let { sub = it; vm.settingsTarget = null } }
    BackHandler(sub != "" && vm.store.hasKey) { sub = "" }
    val titles = mapOf("conn" to R.string.connection, "look" to R.string.appearance, "chat" to R.string.ctx_title,
        "model" to R.string.default_models, "screens" to R.string.vdisplay, "upd" to R.string.updates, "about" to R.string.about)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(titles[sub] ?: R.string.settings)) }, windowInsets = WindowInsets(0), navigationIcon = {
            if (sub != "" && vm.store.hasKey) IconButton(onClick = { sub = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            else if (vm.store.hasKey && vm.settingsBack == Screen.Chat) IconButton(onClick = { vm.screen = Screen.Chat; vm.settingsBack = Screen.Sessions }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          when (sub) {
            "" -> {
                @Composable fun Group(id: String, icon: androidx.compose.ui.graphics.vector.ImageVector, summary: String, badge: Boolean = false) =
                    Card(Modifier.fillMaxWidth().clickable { if (id == "screens") vm.openScreens(Screen.Settings) else sub = id }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            BadgedBox(badge = { if (badge) Badge() }) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) { Text(stringResource(titles[id]!!), style = MaterialTheme.typography.titleSmall)
                                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Icon(Icons.Default.KeyboardArrowRight, null)
                        } }
                Group("conn", Icons.Default.Link, (vm.store.activeRelay ?: vm.store.relayUrl) + " · " + vm.profile)
                Group("look", Icons.Default.Palette, stringResource(R.string.appearance_summary))
                LaunchedEffect(Unit) { if (vm.store.hasKey) { vm.loadCtxSettings(); vm.loadModelSettings() } }
                Group("chat", Icons.Default.DataUsage, vm.ctxSettings?.let { c -> listOfNotNull(c.optString("compression_model").takeIf { it.isNotBlank() && it != "null" },
                    c.opt("context_length")?.takeIf { it != JSONObject.NULL }?.toString()).joinToString(" · ") } ?: stringResource(R.string.ctx_all_profiles))
                Group("model", Icons.Default.Memory, vm.modelSettings?.optJSONObject("main")?.opt("model")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.server_default))
                Group("screens", Icons.Default.Monitor, stringResource(R.string.screens_manage))
                Group("upd", Icons.Default.SystemUpdate, stringResource(R.string.version, vm.updates.currentName, vm.updates.currentCode), badge = vm.update != null)
                Group("about", Icons.Default.Info, "Hermes Android")
            }
            "conn" -> {
            OutlinedTextField(relay, { relay = it }, label = { Text(stringResource(R.string.server_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.server_url_help, SecureStore.DEFAULT_RELAY)) })
            OutlinedTextField(lan, { lan = it }, label = { Text(stringResource(R.string.lan_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://192.168.1.20:8643") }, supportingText = { Text(stringResource(R.string.lan_url_help)) })
            vm.store.activeRelay?.let { Text(stringResource(R.string.using_relay, it), style = MaterialTheme.typography.labelSmall) }
            OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.api_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(stringResource(if (vm.store.hasKey) R.string.key_saved else R.string.key_new)) })
            Text(stringResource(R.string.profile)); ProfileMenu(vm)
            Button(onClick = { vm.saveSettings(relay, lan, key); key = "" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_test)) }
            vm.status?.let { Text(it) }

            }
            "look" -> {
            ChoiceRow(listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light),
                "dark" to stringResource(R.string.theme_dark), "black" to stringResource(R.string.theme_black)), vm.theme) { vm.chooseTheme(it) }
            Text(stringResource(R.string.power_mode))
            ChoiceRow(listOf("balanced" to stringResource(R.string.power_balanced), "saver" to stringResource(R.string.power_saver)), vm.powerMode) { vm.choosePower(it) }
            Text(stringResource(if (vm.powerMode == "saver") R.string.power_saver_hint else R.string.power_balanced_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.app_icon))
            ChoiceRow(listOf("dark" to stringResource(R.string.icon_dark), "light" to stringResource(R.string.icon_light)), vm.appIcon) { vm.chooseIcon(it) }
            var previewPct by remember { mutableStateOf((vm.fontScale * 100).toInt()) }
            Text(stringResource(R.string.font_size, previewPct), style = MaterialTheme.typography.titleSmall)
            Slider(
                modifier = Modifier.padding(horizontal = 24.dp).systemGestureExclusion(),
                value = (previewPct - 80).toFloat(),
                onValueChange = { previewPct = (it.toInt() + 80).coerceIn(80, 150) },
                onValueChangeFinished = { if (previewPct / 100f != vm.fontScale) { vm.chooseFontScale(previewPct / 100f); recreate() } },
                valueRange = 0f..70f)
            Text(stringResource(R.string.font_preview), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(listOf("system" to stringResource(R.string.lang_system), "en" to stringResource(R.string.lang_en),
                "zh" to stringResource(R.string.lang_zh)), vm.store.lang) { if (it != vm.store.lang) { vm.store.lang = it; recreate() } }

            }
            "chat" -> ContextSettingsGroup(vm)
            "model" -> ModelSettingsGroup(vm)
            "upd" -> {
            Text(stringResource(R.string.version, vm.updates.currentName, vm.updates.currentCode))
            Text(stringResource(R.string.update_source), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(listOf("auto" to stringResource(R.string.update_source_auto), "github" to stringResource(R.string.source_github), "pc" to stringResource(R.string.source_pc)), vm.updateSource) { vm.chooseUpdateSource(it) }
            vm.update?.let { u -> Text(stringResource(R.string.update_available, u.versionName, u.versionCode), color = MaterialTheme.colorScheme.primary)
                Button(onClick = { vm.installUpdate() }, enabled = vm.updateProgress == null) { Text(stringResource(R.string.install)) } }
            vm.updateProgress?.let { p -> LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()); Text(stringResource(R.string.downloading, p))
                OutlinedButton(onClick = { vm.cancelUpdate() }) { Text(stringResource(R.string.cancel_update)) } }
            if (vm.updateFileBytes > 0 && vm.updateProgress == null)
                OutlinedButton(onClick = { vm.deleteUpdateFile() }) { Text(stringResource(R.string.delete_update_file, "%.1f".format(vm.updateFileBytes / 1048576.0))) }
            OutlinedButton(onClick = { vm.checkUpdates(manual = true) }) { Text(stringResource(R.string.check_updates)) }
            vm.updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            "about" -> {
                Text("Hermes Android " + vm.updates.currentName + " (" + vm.updates.currentCode + ")", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
                Text("https://github.com/o12r/HermesAndroid", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
          }
        }
    }
}

/** Tokens per second: 45.3 -> "45 t/s", 1234 -> "1.2k t/s". */
fun fmtSpeed(v: Float): String = (if (v >= 1000f) String.format(java.util.Locale.US, "%.1fk", v / 1000f).replace(".0k", "k") else v.toInt().toString()) + " t/s"

/** 1234 -> 1.2k, 1234567 -> 1.2M. */
fun fmtTok(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0).replace(".0k", "k")
    else -> n.toString()
}


@Composable
fun screenLabel(sc: PcScreen): String {
    val base = if (sc.role == "main") stringResource(R.string.screen_main) else stringResource(R.string.screen_secondary, maxOf(1, sc.index))
    return when (sc.kind) {
        "screen-off" -> stringResource(R.string.screen_off_for, base)
        "agent" -> sc.title ?: sc.session ?: "?"
        else -> base
    }
}

/** PC screens: physical ones as main / secondary 1, 2...; agent screens by profile -> project, named after their session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreensScreen(vm: ChatViewModel) {
    var open by remember { mutableStateOf<PcScreen?>(null) }
    BackHandler(open != null) { open = null }
    open?.let { sc -> ScreenDetail(vm, sc) { open = null }; return }
    LaunchedEffect(Unit) { vm.vdisplay("status") }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.pc)) },
            actions = { IconButton(onClick = { vm.loadScreens() }, enabled = !vm.screensBusy) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } },
            windowInsets = WindowInsets(0))
        PcStatsBar(vm)
        if (vm.screensBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val pc = vm.pcScreens.filter { it.kind != "agent" }
        val agents = vm.pcScreens.filter { it.kind == "agent" }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LaunchedEffect(Unit) { vm.vdisplay("status") }
        when (vm.vdMode) {
            null -> Text(stringResource(R.string.vd_unavailable), style = MaterialTheme.typography.bodySmall)
            "none" -> Text(stringResource(R.string.vd_no_driver), style = MaterialTheme.typography.bodySmall)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vd_agent_chat, vm.currentTitle ?: stringResource(R.string.chat)), Modifier.weight(1f))
                    // Only the agent opens its screen (when it needs a UI); the phone can see it and close it.
                    if (vm.vdAgent) TextButton(onClick = { vm.vdisplay("off") }, enabled = !vm.vdBusy) { Text(stringResource(R.string.vd_close)) }
                    else Text(stringResource(R.string.vd_none), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.vd_hint), style = MaterialTheme.typography.bodySmall)
                if (vm.vdScreenOff) OutlinedButton(onClick = { vm.vdisplay("screen-on") }, enabled = !vm.vdBusy) {
                    Text(stringResource(R.string.vd_wake_all)) }
                Text(stringResource(if (vm.vdScreenOffAvailable) R.string.vd_screen_off_hint else R.string.vd_screen_off_need_driver),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (vm.vdBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
            } }
            item { Text(stringResource(R.string.screens_pc), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(pc) { sc -> ScreenRow(screenLabel(sc), sc, sc.on, screenActions(vm, sc)) { open = sc } }
            item { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.screens_agent), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            if (agents.isEmpty()) item { Text(stringResource(R.string.screens_no_agent), style = MaterialTheme.typography.bodySmall) }
            agents.groupBy { it.profile ?: "default" }.toSortedMap().forEach { (prof, byProf) ->
                item { Text("👤 $prof", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)) }
                byProf.groupBy { it.project ?: it.cwd?.trimEnd('\\', '/')?.substringAfterLast('\\')?.substringAfterLast('/') ?: "—" }
                    .toSortedMap().forEach { (proj, l) ->
                        item { Text("📁 $proj", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp)) }
                        items(l) { sc -> Box(Modifier.padding(start = 12.dp)) { ScreenRow(screenLabel(sc), sc, true, screenActions(vm, sc)) { open = sc } } }
                    }
            }
        }
    }
}

@Composable
fun screenActions(vm: ChatViewModel, sc: PcScreen): List<Pair<String, () -> Unit>> = when {
    sc.kind == "physical" && sc.on -> listOf(stringResource(R.string.vd_screen_off_this) to { vm.vdisplay("screen-off", sc.name) })
    sc.kind == "physical" -> listOf(stringResource(R.string.vd_screen_on_this) to { vm.vdisplay("screen-on", sc.name) })
    sc.kind == "agent" && sc.session != null -> listOf(stringResource(R.string.vd_close) to { vm.closeAgentScreen(sc.session) })
    else -> emptyList()
}

/** Physical = primary, screen-off stand-in = secondary, agent = tertiary. */
@Composable
fun screenColor(sc: PcScreen) = when (sc.kind) {
    "agent" -> MaterialTheme.colorScheme.tertiary
    "screen-off" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun ScreenRow(label: String, sc: PcScreen, enabled: Boolean, actions: List<Pair<String, () -> Unit>> = emptyList(), onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(36.dp).background(screenColor(sc)))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Monitor, null, tint = screenColor(sc))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (sc.on) "${sc.w}×${sc.h} @${sc.hz}Hz" else stringResource(R.string.screen_is_off),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (actions.isNotEmpty()) Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(menu, { menu = false }) {
                    actions.forEach { (t, f) -> DropdownMenuItem(text = { Text(t) }, onClick = { menu = false; f() }) } }
            } else Spacer(Modifier.width(12.dp))
        }
    }
}

/** One screen's screenshot, refreshable; agent screens can be closed from here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenDetail(vm: ChatViewModel, sc: PcScreen, back: () -> Unit) {
    var bmp by remember(sc.name) { mutableStateOf<ImageBitmap?>(null) }
    var err by remember(sc.name) { mutableStateOf<String?>(null) }
    var busy by remember(sc.name) { mutableStateOf(false) }
    var n by remember(sc.name) { mutableStateOf(0) }
    LaunchedEffect(sc.name, n) {
        busy = true; err = null
        try { bmp = vm.screenShot(sc.name).asImageBitmap() } catch (e: Exception) { err = e.message }
        busy = false
    }
    val label = screenLabel(sc)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = { n++ }, enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.screen_refresh)) }
            }, windowInsets = WindowInsets(0))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            var zoom by remember { mutableStateOf(false) }
            bmp?.let { Image(it, label, Modifier.fillMaxWidth().clickable { zoom = true }, contentScale = ContentScale.FillWidth)
                if (zoom) ZoomImage(it, label) { zoom = false } }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                screenActions(vm, sc).forEach { (t, f) -> OutlinedButton(onClick = { f(); back() }, enabled = !vm.vdBusy) { Text(t) } } }
            Text("${sc.name.removePrefix("\\\\.\\")} · ${sc.w}×${sc.h} @${sc.hz}Hz", style = MaterialTheme.typography.bodySmall)
            sc.cwd?.let { Text("📁 $it", style = MaterialTheme.typography.bodySmall) }
            sc.session?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Full-screen, static screenshot: pinch to zoom, drag to pan, tap ✕ to close. */
@Composable
fun ZoomImage(img: ImageBitmap, label: String, close: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val st = androidx.compose.foundation.gestures.rememberTransformableState { z, pan, _ ->
            scale = (scale * z).coerceIn(1f, 8f); off = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else off + pan }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(img, label, Modifier.fillMaxSize().androidx_transform(st).graphicsLayerZoom(scale, off), contentScale = ContentScale.Fit)
            IconButton(onClick = close, Modifier.align(Alignment.TopEnd).systemBarsPadding()) { Icon(Icons.Default.Close, null, tint = Color.White) }
        }
    }
}

private fun Modifier.androidx_transform(st: androidx.compose.foundation.gestures.TransformableState) =
    this.then(Modifier.transformable(st))
private fun Modifier.graphicsLayerZoom(s: Float, o: androidx.compose.ui.geometry.Offset) =
    this.then(Modifier.graphicsLayer(scaleX = s, scaleY = s, translationX = o.x, translationY = o.y))
