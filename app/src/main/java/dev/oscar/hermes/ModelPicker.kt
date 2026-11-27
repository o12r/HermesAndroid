package dev.oscar.hermes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A model picker for a settings field, reusing the chat page's model list (vm.c.modelOptions).
 * [current] is the current (provider, model); a null/blank model means "server default".
 * [onPick] receives (provider, model); null/blank values reset to the server default.
 */
@Composable
fun ModelPickerField(
    vm: ChatViewModel,
    current: Pair<String?, String?>?,
    label: String,
    onPick: (provider: String?, model: String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val choices = vm.c.modelOptions?.choices.orEmpty()
    val curKey = current?.second?.takeIf { !it.isNullOrBlank() }?.let { (current.first ?: "") + "|" + it }
    val shown = current?.second?.takeIf { !it.isNullOrBlank() } ?: stringResource(R.string.server_default)
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { open = true; if (vm.c.modelOptions == null) vm.c.loadMeta() }, modifier = Modifier.fillMaxWidth()) {
                Text(shown, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.server_default)) },
                    onClick = { open = false; onPick(null, null) },
                    trailingIcon = { if (curKey == null) Icon(Icons.Default.Check, null) },
                )
                if (vm.c.modelOptions == null) DropdownMenuItem(text = { Text(stringResource(R.string.loading_models)) }, onClick = {}, enabled = false)
                val expanded = remember(open) { mutableStateMapOf<String, Boolean>() }
                choices.groupBy { it.providerName }.forEach { (provider, ms) ->
                    HorizontalDivider()
                    val isCurrent = ms.any { it.key == curKey }
                    val isOpen = expanded[provider] == true
                    DropdownMenuItem(
                        text = { Text(provider + "  (" + ms.size + ")", style = MaterialTheme.typography.labelLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        leadingIcon = { Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                        trailingIcon = { if (isCurrent) Icon(Icons.Default.Check, null) },
                        modifier = if (isCurrent) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
                        onClick = { expanded[provider] = !isOpen })
                    if (isOpen) ms.forEach { m ->
                        val sel = m.key == curKey
                        DropdownMenuItem(text = { Text(m.model, color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            onClick = { open = false; onPick(m.provider, m.model) }, contentPadding = PaddingValues(start = 40.dp, end = 12.dp),
                            trailingIcon = { if (sel) Icon(Icons.Default.Check, null) })
                    }
                }
            }
        }
    }
}
