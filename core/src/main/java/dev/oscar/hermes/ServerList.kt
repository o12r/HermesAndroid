package dev.oscar.hermes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Server addresses: any number, tried together (list order wins among similar speeds), status dot on the right, long-press + drag to reorder. */
@Composable
fun ServerList(vm: ChatViewModel, servers: SnapshotStateList<String>) {
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    var rowH by remember { mutableFloatStateOf(0f) }
    var newUrl by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (vm.c.store.hasKey) vm.c.conn.testConnections(servers.toList()) }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.servers_title), style = MaterialTheme.typography.titleSmall)
        InfoIcon(stringResource(R.string.servers_title), stringResource(R.string.servers_hint))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        servers.toList().forEach { url ->
            key(url) {
                val lifted = dragging == url
                val ok = vm.c.conn.pathOk[url]
                val dot = when (ok) { true -> Color(0xFF2EBE50); false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.outline }
                val ms = vm.c.conn.probes.firstOrNull { it.url == url && it.ok }?.ms
                Card(Modifier.fillMaxWidth().zIndex(if (lifted) 1f else 0f).onSizeChanged { rowH = it.height + gap }
                    .graphicsLayer { translationY = if (lifted) dy else 0f }.then(if (lifted) Modifier.shadow(8.dp) else Modifier)
                    .pointerInput(url) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = url; dy = 0f },
                            onDrag = { c, d ->
                                c.consume(); dy += d.y
                                val i = servers.indexOf(url)
                                if (rowH > 0f && dy > rowH / 2 && i < servers.lastIndex) { servers.add(i + 1, servers.removeAt(i)); dy -= rowH }
                                else if (rowH > 0f && dy < -rowH / 2 && i > 0) { servers.add(i - 1, servers.removeAt(i)); dy += rowH }
                            },
                            onDragEnd = { dragging = null; dy = 0f }, onDragCancel = { dragging = null; dy = 0f })
                    }) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(vm.c.conn.pathLabel(url) + (if (url == vm.c.store.activeRelay) " · " + stringResource(R.string.in_use) else "") + (ms?.let { " · $it ms" } ?: ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text(url, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.size(10.dp).background(dot, CircleShape))
                        IconButton(onClick = { servers.remove(url) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, stringResource(R.string.cancel), Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(newUrl, { newUrl = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.servers_add)) },
            placeholder = { Text("http://192.168.1.20:8643") })
        IconButton(enabled = newUrl.isNotBlank(), onClick = {
            var u = newUrl.trim().trimEnd('/'); if (!u.contains("://")) u = "http://$u"
            if (u !in servers) servers.add(u); newUrl = ""
        }) { Icon(Icons.Default.Add, stringResource(R.string.servers_add)) }
    }
}
