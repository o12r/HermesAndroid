package dev.oscar.hermes

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONObject

data class FsEntry(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

private fun joinPath(base: String, name: String) = if (base.endsWith("\\")) base + name else "$base\\$name"

/** Full-screen browser of the PC's file system (all drives), through the relay. [pick] = choose a folder (files hidden);
 *  otherwise a file manager: open folders, download to the phone, upload, rename, delete, new folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcFileBrowser(vm: ChatViewModel, pick: Boolean, start: String?, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var nameDlg by remember { mutableStateOf<Pair<String, String?>?>(null) }   // (title res tag, entry being renamed or null = new folder)
    var deleting by remember { mutableStateOf<FsEntry?>(null) }
    var overwrite by remember { mutableStateOf<Uri?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun load(p: String, fallback: Boolean = false) {
        scope.launch {
            busy = true; err = null
            try {
                val j = vm.fsJson("list", JSONObject().put("path", p))
                path = j.optString("path"); parent = if (j.isNull("parent")) null else j.optString("parent")
                val a = j.optJSONArray("items")
                items = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }.map {
                    FsEntry(it.optString("name"), it.optBoolean("dir"), it.optLong("size"), it.optLong("mtime"))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (fallback && p.isNotEmpty()) { load("") } else err = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { load(start.orEmpty(), fallback = true) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
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

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopAppBar(
                    title = { Column {
                        Text(stringResource(if (pick) R.string.pf_choose_folder else R.string.pf_title), style = MaterialTheme.typography.titleMedium)
                        Text(path.ifEmpty { stringResource(R.string.pf_drives) }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) } },
                    actions = {
                        IconButton(onClick = { load(parent ?: "") }, enabled = path.isNotEmpty() && !busy) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.pf_up)) }
                        IconButton(onClick = { nameDlg = "new" to null }, enabled = path.isNotEmpty() && !busy) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.pf_new_folder)) }
                        if (!pick) IconButton(onClick = { upload.launch(arrayOf("*/*")) }, enabled = path.isNotEmpty() && !busy) { Icon(Icons.Default.Upload, stringResource(R.string.pf_upload)) }
                        IconButton(onClick = { load(path) }, enabled = !busy) { Icon(Icons.Default.Refresh, stringResource(R.string.pf_refresh)) }
                    })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                val shown = if (pick) items.filter { it.dir } else items
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (shown.isEmpty() && !busy && err == null) item { Text(stringResource(R.string.pf_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                    items(shown, key = { it.name }) { e ->
                        val full = if (path.isEmpty()) e.name else joinPath(path, e.name)
                        ListItem(
                            leadingContent = { Icon(if (e.dir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (path.isEmpty() || e.dir) null else ({ Text(Inbox.sizeText(e.size)) }),
                            trailingContent = if (pick || path.isEmpty()) null else ({
                                Box {
                                    IconButton(onClick = { menuFor = e.name }) { Icon(Icons.Default.MoreVert, null) }
                                    DropdownMenu(menuFor == e.name, { menuFor = null }) {
                                        if (!e.dir) DropdownMenuItem(text = { Text(stringResource(R.string.pf_download)) }, leadingIcon = { Icon(Icons.Default.Download, null) },
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
                                        DropdownMenuItem(text = { Text(stringResource(R.string.pf_rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            onClick = { menuFor = null; nameDlg = "rename" to e.name })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                            onClick = { menuFor = null; deleting = e })
                                    }
                                }
                            }),
                            modifier = Modifier.clickable { if (e.dir) load(full) else if (!pick) menuFor = e.name })
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
    }

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
