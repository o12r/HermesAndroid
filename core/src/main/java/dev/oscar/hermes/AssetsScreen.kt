package dev.oscar.hermes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One file saved into a session. [uri] is blank while the file is still only on the PC. */
data class AssetEntry(val name: String, val uri: String, val ts: Long, val session: Session)

/**
 * Every file saved into the sessions of the current profile, newest first. Filter chips pick one session;
 * tap opens a file, long-press offers Open / Share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(vm: ChatViewModel) {
    val ctx = LocalContext.current
    // Not remembered: the file list lives in prefs and must be re-read whenever this page is shown.
    val all = vm.c.conn.sessions.flatMap { s ->
        vm.c.store.sessionFiles(s.id).map { (n, u, t) -> AssetEntry(n, u, t, s) }
    }.sortedByDescending { it.ts }
    var filter by remember { mutableStateOf<String?>(null) }
    val shown = all.filter { filter == null || it.session.id == filter }
    val withFiles = all.map { it.session }.distinctBy { it.id }
    Column(Modifier.fillMaxSize()) {
        HermesTopBar(vm, title = { Text(stringResource(R.string.assets), maxLines = 1, style = MaterialTheme.typography.titleMedium) },
            nav = null, onRefresh = { vm.c.conn.refreshSessions() })
        ChatTabs(vm, onList = true)
        if (withFiles.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.assets_all)) })
            withFiles.forEach { s ->
                FilterChip(selected = filter == s.id, onClick = { filter = s.id },
                    label = { Text(s.title ?: s.preview ?: s.id, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
        if (shown.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.assets_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.session.id + "\u0000" + it.name + "\u0000" + it.ts }) { a -> AssetRow(ctx, a) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetRow(ctx: Context, a: AssetEntry) {
    var menu by remember { mutableStateOf(false) }
    val onPc = a.uri.isBlank()
    val uri = if (onPc) null else Uri.parse(a.uri)
    val mime = mimeOf(a.name)
    val title = a.session.title ?: a.session.preview ?: a.session.id
    val time = if (a.ts > 0) Formats.fmtTime(a.ts) else ""
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()
            .combinedClickable(enabled = !onPc, onClick = { uri?.let { assetOpen(ctx, it, mime) } }, onLongClick = { menu = true })
            .padding(horizontal = 16.dp, vertical = 10.dp).alpha(if (onPc) 0.5f else 1f),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isImageName(a.name)) Icons.Default.Image else Icons.Default.InsertDriveFile, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(title, time).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (onPc) stringResource(R.string.assets_on_pc) else a.name.substringAfterLast('.', "").uppercase(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        DropdownMenu(menu, { menu = false }) {
            if (uri != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.assets_open)) }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
                    onClick = { menu = false; assetOpen(ctx, uri, mime) })
                DropdownMenuItem(text = { Text(stringResource(R.string.assets_share)) }, leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = { menu = false; assetShare(ctx, uri, mime, a.name) })
            }
        }
    }
}

private fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"

/** Opens a saved file (MediaStore uri) through the system app chooser. */
private fun assetOpen(ctx: Context, uri: Uri, mime: String) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    view.clipData = ClipData.newRawUri("", uri)
    runCatching { ctx.startActivity(Intent.createChooser(view, ctx.getString(R.string.file_open_with)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Shares a saved file (MediaStore uri) with read permission granted to the receiving app. */
private fun assetShare(ctx: Context, uri: Uri, mime: String, name: String) {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    runCatching { ctx.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
