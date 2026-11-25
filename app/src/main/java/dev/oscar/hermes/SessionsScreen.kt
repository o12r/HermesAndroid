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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(vm: ChatViewModel) {
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    var moving by remember { mutableStateOf<Session?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deletingProject by remember { mutableStateOf<Project?>(null) }
    var changingFolder by remember { mutableStateOf<Project?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    var selMode by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var deletingMany by remember { mutableStateOf(false) }
    var movingMany by remember { mutableStateOf(false) }
    val picked = vm.sessions.filter { it.id in sel }
    fun toggleSel(id: String) { sel = if (id in sel) sel - id else sel + id; if (sel.isEmpty()) selMode = false }
    androidx.activity.compose.BackHandler(selMode || reordering) { selMode = false; sel = emptySet(); reordering = false }
    // Collapsed state per project; the active project (or Home) starts open.
    val open = remember { mutableStateMapOf<String, Boolean>() }
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
        topBar = { Column { HermesTopBar(vm,
            title = { Text(if (selMode) stringResource(R.string.n_selected, picked.size) else stringResource(R.string.app_name), maxLines = 1, softWrap = false, style = MaterialTheme.typography.titleMedium) },
            nav = if (selMode) ({ IconButton(onClick = { selMode = false; sel = emptySet() }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, null) } }) else null,
            onRefresh = { vm.scanProfiles(); vm.refreshSessions() },
            actions = {
            if (reordering) TextButton(onClick = { reordering = false }) { Text(stringResource(R.string.reorder_done)) }
            else if (selMode) {
                IconButton(onClick = { movingMany = true }, enabled = picked.isNotEmpty()) { Icon(Icons.Default.Folder, stringResource(R.string.move_to_project)) }
                IconButton(onClick = { deletingMany = true }, enabled = picked.isNotEmpty()) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            } else {
            }
        })
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            ChatTabs(vm, onList = true) } },
        floatingActionButton = { if (!selMode && !reordering) ExtendedFloatingActionButton(onClick = { vm.newChat() }, icon = { Icon(Icons.Default.Add, null) },
            text = { Text(vm.fabProject?.let { stringResource(R.string.new_chat_in, if (it.isHome) stringResource(R.string.home) else it.name) } ?: stringResource(R.string.new_chat)) }) },
        contentWindowInsets = WindowInsets(0)
    ) { pad ->
        val cs = MaterialTheme.colorScheme
        // Compact rows (plain Row instead of ListItem: fewer slots/measure passes per row, fixed heights).
        if (reordering) ReorderProfiles(vm, Modifier.padding(pad).fillMaxSize()) else
        PullToRefreshBox(isRefreshing = vm.loading, onRefresh = { vm.scanProfiles(); vm.refreshSessions() },
            modifier = Modifier.padding(pad).fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
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
                    if (!vm.liveKeys[prof].isNullOrEmpty()) Text("●", Modifier.padding(end = 4.dp), color = cs.tertiary, style = MaterialTheme.typography.labelMedium)
                    Box { IconButton(onClick = { menu = true }, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_project)) }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                onClick = { menu = false; vm.enterProfile(prof); creating = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_chat_here)) }, leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { menu = false; vm.enterProfile(prof); vm.newChatIn(null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reorder_profiles)) }, leadingIcon = { Icon(Icons.Default.DragHandle, null) },
                                onClick = { menu = false; selMode = false; sel = emptySet(); reordering = true })
                        } }
                }
                HorizontalDivider()
            }
            if (profOpen && !isCur && vm.otherData[prof] == null) item(key = "load:$prof") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (profOpen) groups.forEach { (p, list) ->
                val okey = prof + ":" + p.id
                val isOpen = p.id == "__all" || p.isHome || (open[okey] ?: false)
                if (p.id != "__all" && !p.isHome) item(key = "h:" + okey, contentType = "proj") {
                    var menu by remember { mutableStateOf(false) }
                    val active = p.id == activeOf
                    val fabPicked = isCur && vm.fabProject?.id == p.id
                    Row(Modifier.fillMaxWidth().height(32.dp)
                        .background(if (active || fabPicked) cs.primaryContainer else cs.surfaceContainerLow)
                        .combinedClickable(onLongClick = { menu = true }) { if (!isCur) vm.enterProfile(prof); open[okey] = !isOpen; vm.lastProject = p }.padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(cs.secondary))
                        Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.padding(start = 4.dp, end = 6.dp).size(16.dp))
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (p.isHome) stringResource(R.string.home) else p.name), Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodySmall, color = cs.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(list.size.toString(), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                        if (list.any { x -> x.id in vm.liveKeys[prof].orEmpty() }) Text("●", Modifier.padding(end = 4.dp), color = cs.tertiary, style = MaterialTheme.typography.labelMedium)
                        Box { IconButton(onClick = { menu = true }, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp)) }
                            DropdownMenu(menu, { menu = false }) {
                                p.path?.let { DropdownMenuItem(text = { Text(it, style = MaterialTheme.typography.labelSmall) }, enabled = false, onClick = {}) }
                                DropdownMenuItem(text = { Text(stringResource(R.string.new_chat_here)) }, leadingIcon = { Icon(Icons.Default.Add, null) },
                                    onClick = { menu = false; vm.enterProfile(prof); vm.newChatIn(p) })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(if (active) R.string.unset_default else R.string.set_default)) },
                                    leadingIcon = { Icon(Icons.Default.Star, null) }, onClick = { menu = false; vm.enterProfile(prof); vm.setActiveProject(if (active) null else p) })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(if (p.archived) R.string.unarchive else R.string.archive)) },
                                    leadingIcon = { Icon(Icons.Default.Archive, null) }, onClick = { menu = false; vm.enterProfile(prof); vm.archiveProject(p, restore = p.archived) })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(R.string.pf_change_folder)) },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { menu = false; vm.enterProfile(prof); changingFolder = p })
                                if (!p.isHome && !p.isAuto) DropdownMenuItem(text = { Text(stringResource(R.string.delete_project)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; vm.enterProfile(prof); deletingProject = p })
                            } }
                    }
                }
                if (isOpen) items(list, key = { okey + ":" + it.id }, contentType = { "sess" }) { s ->
                    var menu by remember { mutableStateOf(false) }
                    val time = remember(s.lastActive, Formats.clock) { s.lastActive?.let { Formats.fmtTime((it * 1000).toLong()) } ?: "" }
                    val ctxRow = vm.sessionCtx[s.id]
                    val isSel = isCur && s.id in sel
                    val isActiveSess = s.id in vm.liveKeys[prof].orEmpty()
                    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp)
                        .then(if (isSel) Modifier.background(cs.primaryContainer) else Modifier)
                        .combinedClickable(onLongClick = { if (selMode) { if (isCur) toggleSel(s.id) } else menu = true },
                            onDoubleClick = { if (!selMode) { vm.enterProfile(prof); renaming = s } }) {
                            if (selMode) { if (isCur) toggleSel(s.id) }
                            else { if (!isCur) vm.enterProfile(prof); if (p.id != "__all") vm.lastProject = p; vm.open(s) } }
                        .padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        // context used/max sits in the indent column (left of the title), under profile > project
                        Box(Modifier.width(if (p.isHome) 56.dp else 72.dp).padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) {
                            if (ctxRow != null) Text(fmtTok(ctxRow.first) + "/" + fmtTok(ctxRow.second),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), maxLines = 1, softWrap = false,
                                color = if (ctxRow.first * 100 / ctxRow.second >= 80) cs.error else cs.onSurfaceVariant)
                        }
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (s.id in busy) "⏳ " else "") + (s.title ?: s.preview ?: s.id), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(time + " · " + s.count, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                        if (isActiveSess) Text("●", Modifier.padding(start = 4.dp), color = cs.tertiary, style = MaterialTheme.typography.labelMedium)
                        if (selMode) Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { if (isSel) Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        else Box { IconButton(onClick = { menu = true }, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.select_multiple)) }, leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                    onClick = { menu = false; vm.enterProfile(prof); sel = setOf(s.id); selMode = true })
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
    }
    if (deletingMany) AlertDialog(onDismissRequest = { deletingMany = false }, title = { Text(stringResource(R.string.delete_n_sessions_q, picked.size)) },
        confirmButton = { TextButton(onClick = { vm.deleteMany(picked); deletingMany = false; selMode = false; sel = emptySet() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deletingMany = false }) { Text(stringResource(R.string.cancel)) } })
    if (movingMany || moving != null) LaunchedEffect(Unit) { vm.loadPcHome() }
    if (movingMany) AlertDialog(onDismissRequest = { movingMany = false }, title = { Text(stringResource(R.string.move_to_project)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            vm.moveTargets().forEach { p ->
                val can = p.path != null || p.homeCwd != null
                ListItem(headlineContent = { Text(if (p.isHome) stringResource(R.string.home) else p.name) },
                    supportingContent = { Text(p.path ?: p.homeCwd ?: stringResource(R.string.move_no_folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable(enabled = can) { vm.moveManyToProject(picked, p); movingMany = false; selMode = false; sel = emptySet() })
            }
        } },
        confirmButton = {}, dismissButton = { TextButton(onClick = { movingMany = false }) { Text(stringResource(R.string.cancel)) } })
    moving?.let { s ->
        val current = vm.projects.find { s.id in it.sessionIds }?.let { c -> vm.moveTargets().find { it.id == c.id || (it.isHome && c.isHome) } ?: c }
        AlertDialog(onDismissRequest = { moving = null }, title = { Text(stringResource(R.string.move_to_project)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                vm.moveTargets().forEach { p ->
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
        var folder by remember { mutableStateOf("") }
        var browseNew by remember { mutableStateOf(true) }   // the folder picker opens straight away when a project is created
        if (browseNew) PcFileBrowser(vm, pick = true, start = folder.ifBlank { vm.pcHome }, onPick = { folder = it; if (name.isBlank()) name = it.trimEnd('\\').substringAfterLast('\\'); browseNew = false }, onClose = { browseNew = false })
        if (!browseNew) AlertDialog(onDismissRequest = { creating = false }, title = { Text(stringResource(R.string.new_project)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.project_name)) }, singleLine = true)
                OutlinedTextField(folder, { folder = it }, label = { Text(stringResource(R.string.project_folder)) }, singleLine = true,
                    trailingIcon = { IconButton(onClick = { browseNew = true }) { Icon(Icons.Default.FolderOpen, stringResource(R.string.pf_browse)) } },
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
    changingFolder?.let { p -> PcFileBrowser(vm, pick = true, start = p.path, onPick = { vm.changeProjectFolder(p, it); changingFolder = null }, onClose = { changingFolder = null }) }
    deletingProject?.let { p ->
        var withFolder by remember(p.id) { mutableStateOf(false) }
        var withSessions by remember(p.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { deletingProject = null }, title = { Text(stringResource(R.string.delete_project_q, p.name)) },
            text = { Column {
                Text(stringResource(R.string.delete_project_note))
                if (p.sessionIds.isNotEmpty()) Row(Modifier.fillMaxWidth().clickable { withSessions = !withSessions }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withSessions, { withSessions = it })
                    Text(stringResource(R.string.delete_project_sessions, p.sessionIds.size), color = if (withSessions) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                }
                if (p.path != null) Row(Modifier.fillMaxWidth().clickable { withFolder = !withFolder }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withFolder, { withFolder = it })
                    Column { Text(stringResource(R.string.delete_project_folder), color = if (withFolder) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                        Text(p.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p, withFolder, withSessions); deletingProject = null }) { Text(stringResource(R.string.delete)) } },
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
