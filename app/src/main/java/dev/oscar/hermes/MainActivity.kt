package dev.oscar.hermes

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        super.attachBaseContext(L10n.wrap(newBase, SecureStore(newBase).lang))
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

    override fun onStart() { super.onStart(); Notify.visible = true }
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
                "dark" -> dynamicDarkColorScheme(ctx)
                "black" -> trueBlack(dynamicDarkColorScheme(ctx))
                else -> if (sysDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            }
            val d = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(d.density, d.fontScale * vm.fontScale)) {
                MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { App(vm) { recreate() } } }
            }
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

@Composable
fun App(vm: ChatViewModel, recreate: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reconnect(); vm.checkUpdates(manual = false) }
    BackHandler(vm.screen != Screen.Sessions && vm.store.hasKey) { vm.screen = Screen.Sessions; vm.refreshSessions() }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        HotfixBanner(vm)
        Banner(vm)
        when (vm.screen) {
            Screen.Settings -> SettingsScreen(vm, recreate)
            Screen.Sessions -> SessionsScreen(vm)
            Screen.Chat -> ChatScreen(vm)
        }
    }
    UpdateDialog(vm)
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
        dismissButton = { if (!force) TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.later)) } })
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
            vm.profiles.forEach { (p, desc) -> DropdownMenuItem(text = { Column { Text(p)
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
    val busy = vm.busyIds()
    // Group sessions; anything the tree doesn't know about goes to Home.
    val groups = remember(vm.sessions, vm.projects, showArchived) {
        val byId = vm.sessions.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val list = vm.projects.filter { showArchived || !it.archived }.map { p -> p to p.sessionIds.mapNotNull { byId[it] }.also { l -> seen += l.map { it.id } } }
        val rest = vm.sessions.filter { it.id !in seen && vm.projects.none { p -> it.id in p.sessionIds } }
        val home = list.find { it.first.isHome }
        // Projects not loaded yet: one open, header-less list instead of a collapsed "Home".
        val homeP = home?.first ?: Project(if (vm.projects.isEmpty()) "__all" else "__home", "Home", null, true, false, false, emptyList())
        val homeList = (home?.second.orEmpty() + rest).distinctBy { it.id }.sortedByDescending { it.lastActive ?: 0.0 }
        list.filter { !it.first.isHome }.map { (p, l) -> p to l.sortedByDescending { it.lastActive ?: 0.0 } }
            .sortedWith(compareByDescending<Pair<Project, List<Session>>> { it.first.id == vm.activeProject }.thenByDescending { it.second.firstOrNull()?.lastActive ?: 0.0 }) +
            listOf(homeP to homeList)
    }
    Scaffold(
        topBar = { Column { TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
            ProfileMenu(vm)
            IconButton(onClick = { creating = true }) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.new_project)) }
            IconButton(onClick = { vm.openInbox() }) { Icon(Icons.Default.Inbox, stringResource(R.string.inbox)) }
            IconButton(onClick = { vm.refreshSessions() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            IconButton(onClick = { vm.screen = Screen.Settings }) {
                BadgedBox(badge = { if (vm.update != null) Badge() }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } } })
            ChatTabs(vm, onList = true) } },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { vm.newChat() }, icon = { Icon(Icons.Default.Add, null) },
            text = { Text(vm.fabProject?.let { stringResource(R.string.new_chat_in, if (it.isHome) stringResource(R.string.home) else it.name) } ?: stringResource(R.string.new_chat)) }) },
        contentWindowInsets = WindowInsets(0)
    ) { pad ->
        if (vm.loading && vm.sessions.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(pad))
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            groups.forEach { (p, list) ->
                val isOpen = p.id == "__all" || (open[p.id] ?: (p.id == vm.activeProject || (vm.activeProject == null && p.isHome)))
                if (p.id != "__all") item(key = "h:" + p.id) {
                    var menu by remember { mutableStateOf(false) }
                    val active = p.id == vm.activeProject
                    ListItem(
                        headlineContent = { Text((if (p.isHome) stringResource(R.string.home) else p.name) + "  (" + list.size + ")",
                            style = MaterialTheme.typography.titleSmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        supportingContent = { listOfNotNull(p.path, if (p.archived) stringResource(R.string.archived) else null,
                            if (p.isAuto) stringResource(R.string.auto_group) else null).joinToString(" · ").ifBlank { null }?.let {
                            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) } },
                        leadingContent = { Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                        trailingContent = { Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.new_chat_here)) }, leadingIcon = { Icon(Icons.Default.Add, null) },
                                    onClick = { menu = false; vm.newChatIn(p) })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(if (active) R.string.unset_default else R.string.set_default)) },
                                    leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = { menu = false; vm.setActiveProject(if (active) null else p) })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(if (p.archived) R.string.unarchive else R.string.archive)) },
                                    leadingIcon = { Icon(Icons.Default.Archive, null) }, onClick = { menu = false; vm.archiveProject(p, restore = p.archived) })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(R.string.delete_project)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; deletingProject = p })
                            } } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { open[p.id] = !isOpen; vm.lastProject = p })
                    HorizontalDivider()
                }
                if (isOpen) items(list, key = { p.id + ":" + it.id }) { s ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text((if (s.id in busy) "⏳ " else "") + (s.title ?: s.preview ?: s.id), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(listOfNotNull(s.lastActive?.let { fmt.format(Date((it * 1000).toLong())) },
                            stringResource(R.string.msgs, s.count)).joinToString(" · ")) },
                        trailingContent = { Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_to_project)) }, onClick = { menu = false; moving = s })
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = s })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleting = s })
                            } } },
                        modifier = Modifier.clickable { if (p.id != "__all") vm.lastProject = p; vm.open(s) }.padding(start = 16.dp))
                    HorizontalDivider()
                }
            }
            item(key = "archived-toggle") {
                if (vm.projects.any { it.archived }) TextButton(onClick = { showArchived = !showArchived }, Modifier.padding(8.dp)) {
                    Text(stringResource(if (showArchived) R.string.hide_archived else R.string.show_archived)) }
                Spacer(Modifier.height(88.dp))
            }
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
    if (vm.inboxOpen) AlertDialog(onDismissRequest = { vm.inboxOpen = false }, title = { Text(stringResource(R.string.inbox)) },
        text = { Column {
            Text(stringResource(R.string.inbox_help), style = MaterialTheme.typography.bodySmall)
            if (vm.inboxBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            if (vm.inboxFiles.isEmpty() && !vm.inboxBusy) Text(stringResource(R.string.inbox_empty), Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(vm.inboxFiles, key = { it.name }) { f ->
                    ListItem(headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(Inbox.sizeText(f.size)) },
                        trailingContent = { TextButton(onClick = { vm.saveInbox(f.name, f.session) }, enabled = !vm.inboxBusy) { Text(stringResource(R.string.inbox_save)) } })
                }
            }
        } },
        confirmButton = { TextButton(onClick = { vm.refreshInbox() }, enabled = !vm.inboxBusy) { Text(stringResource(R.string.refresh)) } },
        dismissButton = { TextButton(onClick = { vm.inboxOpen = false }) { Text(stringResource(R.string.inbox_close)) } })
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
fun ChatScreen(vm: ChatViewModel) {
    // The chat.draft is the current chat's draft, so typed text survives switching chats / going back.
    val chat = vm.cur
    val list = rememberLazyListState()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> vm.addAttachment(u, true) } }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> vm.addAttachment(u, false) } }
    val last = vm.messages.lastOrNull()
    LaunchedEffect(vm.messages.size, last?.text?.length) { if (vm.messages.isNotEmpty()) list.scrollToItem(vm.messages.lastIndex, Int.MAX_VALUE / 2) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Column {
                Text(vm.currentTitle ?: stringResource(if (vm.currentId == null) R.string.new_chat else R.string.chat), maxLines = 1, overflow = TextOverflow.Ellipsis)
                vm.projects.find { p -> vm.currentId != null && vm.currentId in p.sessionIds }?.let { p ->
                    Text("📁 " + (if (p.isHome) stringResource(R.string.home) else p.name), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1) }
            } },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.Sessions; vm.refreshSessions() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { ProfileMenu(vm) }, windowInsets = WindowInsets(0))
        ChatTabs(vm)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(vm.messages) { _, m -> Bubble(m, vm) }
        }
        vm.status?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium) }
        if (vm.streaming) LinearProgressIndicator(Modifier.fillMaxWidth())
        SlashSuggestions(vm, chat.draft) { chat.draft = it }
        RuntimeChips(vm)
        if (vm.attachments.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp)) {
            vm.attachments.forEach { a -> InputChip(selected = false, onClick = { vm.attachments.remove(a) }, label = { Text(a.name, maxLines = 1) },
                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }, modifier = Modifier.padding(end = 4.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            var attachMenu by remember { mutableStateOf(false) }
            if (vm.hotfix.attachments) Box {
                IconButton(onClick = { attachMenu = true }, enabled = !vm.streaming) { Icon(Icons.Default.AttachFile, stringResource(R.string.attach)) }
                DropdownMenu(attachMenu, { attachMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.image)) }, leadingIcon = { Icon(Icons.Default.Image, null) },
                        onClick = { attachMenu = false; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.text_file)) }, leadingIcon = { Icon(Icons.Default.Description, null) },
                        onClick = { attachMenu = false; pickFile.launch("*/*") })
                } }
            // Plain Compose text field: goes through Android's InputConnection, so Gboard / WeChat IME composition works.
            var focused by remember { mutableStateOf(false) }
            var full by remember { mutableStateOf(false) }
            val half = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 2).dp
            OutlinedTextField(chat.draft, { chat.draft = it }, Modifier.weight(1f).heightIn(max = half)
                    .onFocusChanged { focused = it.isFocused }.animateContentSize(),
                placeholder = { Text(stringResource(if (vm.streaming) R.string.steer_hint else R.string.message_hint)) },
                minLines = if (focused) 5 else 1, maxLines = if (focused) Int.MAX_VALUE else 6,
                trailingIcon = { if (focused || chat.draft.length > 80) IconButton(onClick = { full = true }) {
                    Icon(Icons.Default.OpenInFull, stringResource(R.string.expand_editor)) } },
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
            if (vm.streaming) IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) }
            // While Hermes works, Send steers the running turn instead of waiting.
            if (!vm.streaming || chat.draft.isNotBlank()) IconButton(onClick = { vm.send(chat.draft); chat.draft = "" },
                enabled = chat.draft.isNotBlank() || (!vm.streaming && vm.attachments.isNotEmpty())) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(if (vm.streaming) R.string.steer else R.string.send)) }
        }
    }
}

@Composable
fun RuntimeChips(vm: ChatViewModel) {
    var effortOpen by remember { mutableStateOf(false) }
    val def = stringResource(R.string.default_)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            val label = if (vm.model.isBlank()) stringResource(R.string.model_default, vm.modelOptions?.currentModel?.ifBlank { null } ?: "…")
                else vm.model.substringAfter('|')
            FilterChip(selected = vm.model.isNotBlank(), onClick = { vm.modelMenuOpen = true; if (vm.modelOptions == null) vm.loadMeta() },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Memory, null, Modifier.size(16.dp)) }, modifier = Modifier.widthIn(max = 260.dp))
            DropdownMenu(vm.modelMenuOpen, { vm.modelMenuOpen = false }, Modifier.heightIn(max = 480.dp)) {
                val cur = vm.modelOptions?.currentModel.orEmpty()
                DropdownMenuItem(text = { Text(stringResource(R.string.server_default) + if (cur.isNotBlank()) " ($cur)" else "") },
                    onClick = { vm.chooseModel(""); vm.modelMenuOpen = false }, trailingIcon = { if (vm.model.isBlank()) Icon(Icons.Default.Check, null) })
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
            FilterChip(selected = vm.effort.isNotBlank(), onClick = { effortOpen = true },
                label = { Text(stringResource(R.string.thinking, vm.effort.ifBlank { def })) },
                leadingIcon = { Icon(Icons.Default.Psychology, null, Modifier.size(16.dp)) })
            DropdownMenu(effortOpen, { effortOpen = false }) {
                SecureStore.EFFORTS.forEach { e -> DropdownMenuItem(text = { Text(e.ifBlank { def }) },
                    onClick = { vm.chooseEffort(e); effortOpen = false }, trailingIcon = { if (vm.effort == e) Icon(Icons.Default.Check, null) }) }
            }
        }
    }
}

/** Open chats as tabs: ⏳ = Hermes working, ✎ = unsent draft, × closes (the PC session is kept). */
@Composable
fun ChatTabs(vm: ChatViewModel, onList: Boolean = false) {
    // In a chat, one tab (itself) adds nothing; on the session list any open chat is worth a tab.
    if (vm.tabs.size < (if (onList) 1 else 2)) return
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
                if (c.approval != null || c.clarify != null) append("❓ ")
                if (c.profile != vm.profile) append(c.profile).append(" · ")
                append(c.title ?: newChat)
                if (!sel && c.draft.isNotBlank()) append(" ✎")
            }
            InputChip(selected = sel, onClick = { if (!sel) vm.switchTo(c) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.close_tab), Modifier.size(16.dp).clickable { vm.closeTab(c) }) })
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
fun Bubble(m: UiMsg, vm: ChatViewModel) {
    if (m.role == "system") {
        Text(m.text, Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary, fontStyle = FontStyle.Italic)
        return
    }
    if (m.role == "file") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { FileBubble(m.text, m.file) { vm.saveInbox(m.text, m.session) } }
        return
    }
    val user = m.role == "user"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = RoundedCornerShape(14.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = if (user) 320.dp else 600.dp)) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 2.dp), horizontalAlignment = Alignment.Start) {
                if (m.steps.isNotEmpty()) StepsView(m.steps, live = vm.cur.streaming && m === vm.cur.messages.lastOrNull()) else if (m.tools.isNotEmpty()) Text("🔧 " + m.tools.joinToString(", "), style = MaterialTheme.typography.labelSmall)
                if (user) Text(m.text) else Markdown(if (m.text.isEmpty()) "…" else m.text)
                if (m.failed) FailedActions(m, vm) else MessageActions(m, vm)
            }
        }
    }
}

/** Hermes's thinking and tool calls behind a reply: collapsed by default, each tool's output collapsible too. */
@Composable
fun StepsView(steps: List<Step>, live: Boolean = false) {
    // Open by itself while the model is thinking (the text streams in), closes again once thinking ends;
    // a tap overrides that until the next thinking phase.
    val thinkingNow = live && steps.lastOrNull()?.let { it.kind == "thinking" && it.output == null } == true
    var userOpen by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(thinkingNow) { userOpen = null }
    val open = userOpen ?: thinkingNow
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
                var outOpen by remember { mutableStateOf(false) }
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
    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End) {
        val mod = Modifier.size(32.dp)
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

@Composable
fun Markdown(md: String) {
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale / LocalContext.current.resources.configuration.fontScale
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
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, windowInsets = WindowInsets(0), navigationIcon = {
            if (vm.store.hasKey) IconButton(onClick = { vm.screen = Screen.Sessions }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

            HorizontalDivider()
            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light),
                "dark" to stringResource(R.string.theme_dark), "black" to stringResource(R.string.theme_black)), vm.theme) { vm.chooseTheme(it) }
            Text(stringResource(R.string.app_icon))
            ChoiceRow(listOf("dark" to stringResource(R.string.icon_dark), "light" to stringResource(R.string.icon_light)), vm.appIcon) { vm.chooseIcon(it) }
            Text(stringResource(R.string.font_size, (vm.fontScale * 100).toInt()), style = MaterialTheme.typography.titleSmall)
            Slider(vm.fontScale, { vm.chooseFontScale((it * 20).toInt() / 20f) }, valueRange = 0.8f..1.5f, steps = 13)
            Text(stringResource(R.string.font_preview), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
            ChoiceRow(listOf("system" to stringResource(R.string.lang_system), "en" to stringResource(R.string.lang_en),
                "zh" to stringResource(R.string.lang_zh)), vm.store.lang) { if (it != vm.store.lang) { vm.store.lang = it; recreate() } }

            HorizontalDivider()
            Text(stringResource(R.string.updates), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.version, vm.updates.currentName, vm.updates.currentCode))
            vm.update?.let { u -> Text(stringResource(R.string.update_available, u.versionName, u.versionCode), color = MaterialTheme.colorScheme.primary)
                Button(onClick = { vm.installUpdate() }, enabled = vm.updateProgress == null) { Text(stringResource(R.string.install)) } }
            vm.updateProgress?.let { p -> LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()); Text(stringResource(R.string.downloading, p)) }
            OutlinedButton(onClick = { vm.checkUpdates(manual = true) }) { Text(stringResource(R.string.check_updates)) }
            vm.updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
