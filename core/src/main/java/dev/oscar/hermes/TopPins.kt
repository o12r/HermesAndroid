package dev.oscar.hermes

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Title of every Settings page group (core groups plus the "screens" group that the PC feature adds). */
val settingsGroupTitles: Map<String, Int> = mapOf(
    "conn" to R.string.connection, "look" to R.string.appearance, "chat" to R.string.ctx_title,
    "model" to R.string.default_models, "screens" to R.string.vdisplay, "modules" to R.string.modules,
    "upd" to R.string.updates, "about" to R.string.about, "assets" to R.string.assets)

private val settingsGroupIcons: Map<String, ImageVector> = mapOf(
    "conn" to Icons.Default.Link, "look" to Icons.Default.Palette, "chat" to Icons.Default.DataUsage,
    "model" to Icons.Default.Memory, "screens" to Icons.Default.Monitor, "modules" to Icons.Default.Extension,
    "upd" to Icons.Default.SystemUpdate, "about" to Icons.Default.Info, "assets" to Icons.Default.AttachFile)

/** Core Settings groups that can be pinned; "screens" and "admin" come from their feature modules. */
private val corePinIds = listOf("conn", "look", "chat", "model", "modules", "upd", "about", "assets")

/** One entry the top bar shows once pinned. [id] is what the pin set stores; [open] runs on tap. */
class PinItem(val id: String, @StringRes val title: Int, val icon: ImageVector, val open: (ChatController) -> Unit)

/**
 * Pinned entries in pin order. Core Settings groups always exist; feature entries only while their module is on,
 * so a pin of a missing or switched-off module is skipped. Unknown or stale ids are ignored.
 */
fun ChatViewModel.topBarPins(): List<PinItem> {
    val all = HashMap<String, PinItem>()
    corePinIds.forEach { id -> all[id] = PinItem(id, settingsGroupTitles[id]!!, settingsGroupIcons[id]!!) { if (id == "assets") it.openPage("assets") else it.openSettingsSection(id) } }
    uiFeatures.forEach { f -> f.pinItems().forEach { all[it.id] = it } }
    return c.pinned.mapNotNull { all[it] }
}

/** One Settings page row. Tap opens it; long-press offers "Pin to top bar" / "Unpin from top bar". */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsGroupCard(vm: ChatViewModel, id: String, @StringRes title: Int, icon: ImageVector, summary: String,
                      badge: Boolean = false, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val pinned = vm.c.isPinned(id)
    Box(Modifier.fillMaxWidth()) {
        Card(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BadgedBox(badge = { if (badge) Badge() }) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.KeyboardArrowRight, null)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.unpin_top else R.string.pin_top)) },
                onClick = { vm.c.setPinned(id, !pinned); menu = false })
        }
    }
}
