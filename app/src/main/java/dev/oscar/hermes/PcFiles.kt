package dev.oscar.hermes

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONObject

/** [path] is the full path when the relay sends one (drives, Quick access); else folder path + [name]. */
data class FsEntry(val name: String, val dir: Boolean, val size: Long, val mtime: Long, val path: String? = null)

private fun joinPath(base: String, name: String) = if (base.endsWith("\\")) base + name else "$base\\$name"

/** Full-screen browser of the PC's file system (all drives), through the relay. [pick] = choose a folder (files hidden);
 *  otherwise a file manager: open folders, download to the phone, upload, rename, delete, new folder. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PcFileBrowser(vm: ChatViewModel, pick: Boolean, start: String?, onPick: (String) -> Unit, onClose: () -> Unit, asPage: Boolean = false) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var quick by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var nameDlg by remember { mutableStateOf<Pair<String, String?>?>(null) }   // (title res tag, entry being renamed or null = new folder)
    var deleting by remember { mutableStateOf<FsEntry?>(null) }
    var overwrite by remember { mutableStateOf<Uri?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun parse(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }.map {
        FsEntry(it.optString("name"), it.optBoolean("dir"), it.optLong("size"), it.optLong("mtime"), it.optString("path").ifBlank { null })
    }
    fun load(p: String, fallback: Boolean = false) {
        scope.launch {
            busy = true; err = null
            try {
                val j = vm.fsJson("list", JSONObject().put("path", p))
                path = j.optString("path"); typed = path; parent = if (j.isNull("parent")) null else j.optString("parent")
                items = parse(j.optJSONArray("items")); quick = if (path.isEmpty()) parse(j.optJSONArray("quick")) else emptyList()
                if (!pick) vm.filesPath = path
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (fallback && p.isNotEmpty()) { load("") } else err = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { load((if (pick) start else vm.filesPath).orEmpty(), fallback = true) }
    BackHandler(asPage && path.isNotEmpty()) { load(parent ?: "") }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    fun copyPath(p: String) { clipboard.setText(AnnotatedString(p)); toast(ctx.getString(R.string.pf_path_copied)) }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty() || path.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                for (u in uris) {
                    try { vm.fsUpload(u, path, false) } catch (e: ApiException) { if (e.message == "exists") overwrite = u else throw e }
                }
                toast(ctx.getString(R.string.pf_uploaded)); load(path)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { err = e.message ?: e.toString() }
            finally { busy = false }
        }
    }

    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().then(if (asPage) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())) {
            if (pick) Text(stringResource(R.string.pf_choose_folder), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            var more by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pick) IconButton(onClick = onClose, Modifier.size(40.dp)) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                IconButton(onClick = { load(parent ?: "") }, Modifier.size(40.dp), enabled = path.isNotEmpty() && !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.pf_up)) }
                IconButton(onClick = { load("C:\\") }, Modifier.size(40.dp), enabled = !busy) { Icon(Icons.Default.Home, stringResource(R.string.pf_home)) }
                androidx.compose.foundation.text.BasicTextField(typed, { typed = it }, Modifier.weight(1f).height(36.dp), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { load(typed.trim().trim('"')) }),
                    decorationBox = { inner ->
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                                if (typed.isEmpty()) Text(stringResource(R.string.pf_drives), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                        }
                    })
                IconButton(onClick = { load(typed.trim().trim('"')) }, Modifier.size(40.dp)) { Icon(Icons.Default.ArrowForward, stringResource(R.string.pf_go)) }
                IconButton(onClick = { load(path) }, Modifier.size(40.dp), enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.pf_refresh)) }
                Box {
                    IconButton(onClick = { more = true }, Modifier.size(40.dp), enabled = path.isNotEmpty() && !busy) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pf_new_folder)) }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { more = false; nameDlg = "new" to null })
                        if (!pick) DropdownMenuItem(text = { Text(stringResource(R.string.pf_upload)) }, leadingIcon = { Icon(Icons.Default.Upload, null) },
                            onClick = { more = false; upload.launch(arrayOf("*/*")) })
                    }
                }
            }
            if (asPage) ChatTabs(vm, onList = true)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            val shown = if (pick) items.filter { it.dir } else items

            @Composable fun row(e: FsEntry, section: String) {
                val full = e.path ?: if (path.isEmpty()) e.name else joinPath(path, e.name)
                val canEdit = !pick && path.isNotEmpty()
                val mk = "$section|$full"
                Box {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .combinedClickable(onClick = { if (e.dir) load(full) else if (!pick) menuFor = mk }, onLongClick = { menuFor = mk })
                        .padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.dir) (if (section == "quick") Icons.Default.PushPin else Icons.Default.Folder) else Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            val sub = if (e.dir) (if (section == "quick") full else null) else Inbox.sizeText(e.size)
                            if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(menuFor == mk, { menuFor = null }) {
                        if (!e.dir && canEdit) DropdownMenuItem(text = { Text(stringResource(R.string.pf_download)) }, leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = {
                                menuFor = null
                                scope.launch {
                                    busy = true
                                    try { vm.fsDownload(full, e.name); toast(ctx.getString(R.string.pf_downloaded, e.name)) }
                                    catch (x: kotlinx.coroutines.CancellationException) { throw x }
                                    catch (x: Exception) { err = x.message ?: x.toString() }
                                    finally { busy = false }
                                }
                            })
                        if (e.dir) DropdownMenuItem(text = { Text(stringResource(if (section == "quick") R.string.pf_unpin else R.string.pf_pin)) },
                            leadingIcon = { Icon(Icons.Default.PushPin, null) },
                            onClick = {
                                menuFor = null
                                scope.launch {
                                    try { vm.fsJson(if (section == "quick") "unpin" else "pin", JSONObject().put("path", full)); load(path) }
                                    catch (x: kotlinx.coroutines.CancellationException) { throw x }
                                    catch (x: Exception) { err = x.message ?: x.toString() }
                                }
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pf_copy_path)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menuFor = null; copyPath(full) })
                        if (canEdit) DropdownMenuItem(text = { Text(stringResource(R.string.pf_rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuFor = null; nameDlg = "rename" to e.name })
                        if (canEdit) DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuFor = null; deleting = e })
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (path.isEmpty() && quick.isNotEmpty()) item(key = "h-drives") { Text(stringResource(R.string.pf_drives), Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                if (shown.isEmpty() && !busy && err == null) item(key = "empty") { Text(stringResource(R.string.pf_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                items(shown, key = { "i|" + (it.path ?: it.name) }) { row(it, "item") }
                if (path.isEmpty() && quick.isNotEmpty()) {
                    item(key = "h-quick") { Text(stringResource(R.string.pf_quick), Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    items(quick, key = { "q|" + it.path }) { row(it, "quick") }
                }
            }
            if (pick) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(path.ifEmpty { stringResource(R.string.pf_drives) }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onPick(path) }, enabled = path.isNotEmpty()) { Text(stringResource(R.string.pf_select_folder)) }
                }
            }
        }
    }
    if (asPage) body()
    else Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize()) { body() } }

    nameDlg?.let { (kind, old) ->
        var name by remember(kind, old) { mutableStateOf(old ?: "") }
        AlertDialog(onDismissRequest = { nameDlg = null },
            title = { Text(stringResource(if (kind == "new") R.string.pf_new_folder else R.string.pf_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.pf_name)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                val n = name.trim(); nameDlg = null
                scope.launch {
                    try {
                        if (kind == "new") vm.fsJson("mkdir", JSONObject().put("path", joinPath(path, n)))
                        else vm.fsJson("rename", JSONObject().put("path", joinPath(path, old!!)).put("new", n))
                        load(path)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { err = e.message ?: e.toString() }
                }
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { nameDlg = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleting?.let { e ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.pf_delete_q, e.name)) },
            text = { Text(stringResource(if (e.dir) R.string.pf_delete_folder_text else R.string.pf_delete_file_text)) },
            confirmButton = { TextButton(onClick = {
                deleting = null
                scope.launch {
                    try { vm.fsJson("delete", JSONObject().put("path", joinPath(path, e.name))); load(path) }
                    catch (x: kotlinx.coroutines.CancellationException) { throw x }
                    catch (x: Exception) { err = x.message ?: x.toString() }
                }
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
    overwrite?.let { u ->
        AlertDialog(onDismissRequest = { overwrite = null },
            title = { Text(stringResource(R.string.pf_exists_title)) }, text = { Text(stringResource(R.string.pf_exists_text)) },
            confirmButton = { TextButton(onClick = {
                overwrite = null
                scope.launch {
                    try { vm.fsUpload(u, path, true); load(path) }
                    catch (x: kotlinx.coroutines.CancellationException) { throw x }
                    catch (x: Exception) { err = x.message ?: x.toString() }
                }
            }) { Text(stringResource(R.string.pf_overwrite)) } },
            dismissButton = { TextButton(onClick = { overwrite = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
