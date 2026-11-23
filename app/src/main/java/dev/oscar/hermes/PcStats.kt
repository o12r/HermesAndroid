package dev.oscar.hermes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Live PC resource usage: CPU / memory / GPU percentages; tap to expand into per-metric history graphs. */
@Composable
fun PcStatsBar(vm: ChatViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val last = vm.pcStats.lastOrNull()
    // Poll only while this composable is on screen (it is composed only when the PC page is visible).
    LaunchedEffect(Unit) {
        while (true) {
            vm.fetchPcStats()?.let { vm.addPcStat(it) }
            delay(1000)
        }
    }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pc_stats), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                StatValue("CPU", last?.cpu)
                StatValue(stringResource(R.string.pc_mem), last?.mem)
                StatValue(stringResource(R.string.pc_gpu), last?.gpu)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(R.string.pc_stats_expand),
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatGraph("CPU", vm.pcStats.map { it.cpu }, MaterialTheme.colorScheme.primary)
                    StatGraph(stringResource(R.string.pc_mem), vm.pcStats.map { it.mem }, MaterialTheme.colorScheme.secondary)
                    StatGraph(stringResource(R.string.pc_gpu), vm.pcStats.mapNotNull { it.gpu }, MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

/** "42%" or "N/A" when the metric is absent (e.g. GPU on a machine without a GPU). */
@Composable
private fun StatValue(label: String, pct: Double?) {
    val v = pct?.let { it.toInt().toString() + "%" } ?: stringResource(R.string.pc_na)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label + " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small line graph of the last N samples (0..100 scaled to the height); empty = "N/A". */
@Composable
private fun StatGraph(label: String, values: List<Double?>, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val pts = values.mapNotNull { it }
        if (pts.isEmpty()) { Text(stringResource(R.string.pc_na), style = MaterialTheme.typography.labelMedium); return }
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val step = size.width / maxOf(1, pts.size - 1)
            val path = Path()
            pts.forEachIndexed { i, v ->
                val x = i * step
                val y = size.height * (1f - (v / 100.0).coerceIn(0.0, 1.0).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2f))
        }
    }
}
