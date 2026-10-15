package com.datawithart.beatster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.datawithart.beatster.model.BEATS_PER_BAR
import com.datawithart.beatster.model.Project
import com.datawithart.beatster.model.QuantizeGrid
import com.datawithart.beatster.studio.TransportState
import com.datawithart.beatster.ui.theme.RecordRed
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun TransportBar(
    project: Project,
    transport: TransportState,
    recordEnabled: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    onBpm: (Double) -> Unit,
    onMetronome: () -> Unit,
    onCountIn: () -> Unit,
    onQuantize: (QuantizeGrid) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(
                onClick = if (transport.playing) onStop else onPlay,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    imageVector = if (transport.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (transport.playing) "Stop" else "Play",
                )
            }
            FilledIconButton(
                onClick = onRecord,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (recordEnabled) RecordRed else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (recordEnabled) Color.White else RecordRed,
                ),
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record")
            }
            PositionDisplay(transport, modifier = Modifier.weight(1f))
            LevelMeter(level = transport.outputLevel, modifier = Modifier.width(8.dp).height(44.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BpmControl(bpm = project.bpm, onBpm = onBpm)
            FilterChip(selected = project.metronome, onClick = onMetronome, label = { Text("Click") })
            FilterChip(selected = project.countIn, onClick = onCountIn, label = { Text("Count-in") })
            QuantizePicker(grid = project.quantize, onQuantize = onQuantize)
        }
    }
}

@Composable
private fun PositionDisplay(transport: TransportState, modifier: Modifier = Modifier) {
    val text = when {
        transport.countingIn -> "Count-in ${floor(transport.beat).toInt() + BEATS_PER_BAR + 1}"
        transport.playing -> {
            val beat = floor(transport.beat).toInt()
            "${beat / BEATS_PER_BAR + 1}.${beat % BEATS_PER_BAR + 1}"
        }
        else -> "1.1"
    }
    val status = when {
        transport.recordingTrack >= 0 && transport.rolling -> "Recording audio"
        transport.recordingTrack >= 0 -> "Armed: waits for the loop start"
        else -> null
    }
    Column(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        )
        if (status != null) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = RecordRed)
        }
    }
}

@Composable
private fun BpmControl(bpm: Double, onBpm: (Double) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        IconButton(onClick = { onBpm(bpm - 1) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Slower")
        }
        Text("${bpm.roundToInt()} BPM", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { onBpm(bpm + 1) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Faster")
        }
    }
}

@Composable
private fun QuantizePicker(grid: QuantizeGrid, onQuantize: (QuantizeGrid) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = grid != QuantizeGrid.Off,
            onClick = { open = true },
            label = { Text("Quantize ${grid.label}") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            QuantizeGrid.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onQuantize(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val clamped = level.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(clamped)
                .background(if (clamped > 0.95f) RecordRed else MaterialTheme.colorScheme.secondary),
        )
    }
}

/** Small helper so sheets and panels share padding. */
internal val SheetPadding = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
