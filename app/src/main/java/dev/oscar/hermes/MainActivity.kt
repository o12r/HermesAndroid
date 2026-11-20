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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val sysDark = isSystemInDarkTheme()
            val scheme = when (vm.theme) {
                "light" -> dynamicLightColorScheme(ctx)
                "dark" -> dynamicDarkColorScheme(ctx)
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
                    q.choices.forEach { ch -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { answers[q.qid] = ch }) {
                        RadioButton(selected = answers[q.qid] == ch, onClick = { answers[q.qid] = ch }); Text(ch) } }
                    OutlinedTextField(if (q.choices.contains(answers[q.qid])) "" else answers[q.qid] ?: "", { answers[q.qid] = it },
                        placeholder = { Text(stringResource(if (q.choices.isEmpty()) R.string.answer else R.string.other)) }, modifier = Modifier.fillMaxWidth())
                } } },
            confirmButton = { Button(onClick = { vm.answerClarify(c.questions.associate { it.qid to (answers[it.qid] ?: "") }) }) { Text(stringResource(R.string.submit)) } },
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
    }
}

@Composable
fun ProfileMenu(vm: ChatViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(vm.profile) }, leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(18.dp)) })
        DropdownMenu(open, { open = false }) {
            SecureStore.PROFILES.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { open = false; vm.setProfileTo(p) },
                trailingIcon = { if (p == vm.profile) Icon(Icons.Default.Check, null) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(vm: ChatViewModel) {
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
            ProfileMenu(vm)
            IconButton(onClick = { vm.refreshSessions() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            IconButton(onClick = { vm.screen = Screen.Settings }) {
                BadgedBox(badge = { if (vm.update != null) Badge() }) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { vm.newChat() }, icon = { Icon(Icons.Default.Add, null) },
            text = { Text(stringResource(R.string.new_chat)) }) },
        contentWindowInsets = WindowInsets(0)
    ) { pad ->
        if (vm.loading && vm.sessions.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(pad))
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(vm.sessions, key = { it.id }) { s ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(s.title ?: s.preview ?: s.id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull(s.lastActive?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((it * 1000).toLong())) },
                        stringResource(R.string.msgs, s.count)).joinToString(" · ")) },
                    trailingContent = { Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = s })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleting = s })
                        } } },
                    modifier = Modifier.clickable { vm.open(s) })
                HorizontalDivider()
            }
        }
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
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> vm.addAttachment(u, true) } }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> vm.addAttachment(u, false) } }
    val last = vm.messages.lastOrNull()
    LaunchedEffect(vm.messages.size, last?.text?.length) { if (vm.messages.isNotEmpty()) list.scrollToItem(vm.messages.lastIndex, Int.MAX_VALUE / 2) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(vm.currentTitle ?: stringResource(if (vm.currentId == null) R.string.new_chat else R.string.chat),
                maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.Sessions; vm.refreshSessions() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { ProfileMenu(vm) }, windowInsets = WindowInsets(0))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(vm.messages) { _, m -> Bubble(m, vm) }
        }
        vm.status?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium) }
        if (vm.streaming) LinearProgressIndicator(Modifier.fillMaxWidth())
        SlashSuggestions(vm, input) { input = it }
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
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(if (vm.streaming) R.string.steer_hint else R.string.message_hint)) }, maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            if (vm.streaming) IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) }
            // While Hermes works, Send steers the running turn instead of waiting.
            if (!vm.streaming || input.isNotBlank()) IconButton(onClick = { vm.send(input); input = "" },
                enabled = input.isNotBlank() || (!vm.streaming && vm.attachments.isNotEmpty())) {
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
    val user = m.role == "user"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = RoundedCornerShape(14.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = if (user) 320.dp else 600.dp)) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 2.dp), horizontalAlignment = Alignment.Start) {
                if (m.tools.isNotEmpty()) Text("🔧 " + m.tools.joinToString(", "), style = MaterialTheme.typography.labelSmall)
                if (user) Text(m.text) else Markdown(if (m.text.isEmpty()) "…" else m.text)
                MessageActions(m, vm)
            }
        }
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
    val color = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val ctx = LocalContext.current
    val markwon = remember { Markwon.builder(ctx).usePlugin(SoftBreakAddsNewLinePlugin.create()).usePlugin(TablePlugin.create(ctx))
        .usePlugin(StrikethroughPlugin.create()).build() }
    AndroidView(factory = { TextView(it).apply { setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance(); textSize = 15f } },
        update = { it.setTextColor(color); markwon.setMarkdown(it, md) })
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
