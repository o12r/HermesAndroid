package dev.oscar.hermes

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Display name of a feature id this build can carry; 0 when the id is unknown (the raw id is shown instead). */
@StringRes
fun moduleLabel(id: String): Int = when (id) {
    "vdisplay" -> R.string.module_vdisplay
    "pcfiles" -> R.string.module_pcfiles
    "pcstats" -> R.string.module_pcstats
    "admin" -> R.string.module_admin
    else -> 0
}

/** One-line description of a feature id; 0 when unknown. */
@StringRes
fun moduleDescription(id: String): Int = when (id) {
    "vdisplay" -> R.string.module_vdisplay_desc
    "pcfiles" -> R.string.module_pcfiles_desc
    "pcstats" -> R.string.module_pcstats_desc
    "admin" -> R.string.module_admin_desc
    else -> 0
}

/**
 * Settings → Modules: one switch per optional feature module actually packaged in this build (the classpath list),
 * so a build without a module never shows its switch. Off hides every entry point of the module and stops its work.
 */
@Composable
fun FeatureModules(vm: ChatViewModel) {
    Text(stringResource(R.string.modules_help), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val features = vm.c.features.sortedBy { it.id }
    if (features.isEmpty()) {
        Text(stringResource(R.string.modules_none), style = MaterialTheme.typography.bodyMedium)
        return
    }
    features.forEach { f ->
        val label = moduleLabel(f.id)
        val desc = moduleDescription(f.id)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (label != 0) stringResource(label) else f.id, style = MaterialTheme.typography.titleSmall)
                    if (desc != 0) Text(stringResource(desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = vm.c.featureOn(f.id), onCheckedChange = { vm.c.setFeatureOn(f.id, it) })
            }
        }
    }
}
