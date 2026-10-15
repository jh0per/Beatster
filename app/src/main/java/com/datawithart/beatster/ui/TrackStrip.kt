package com.datawithart.beatster.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.datawithart.beatster.model.MAX_TRACKS
import com.datawithart.beatster.model.Track
import com.datawithart.beatster.model.TrackType
import com.datawithart.beatster.studio.TransportState
import com.datawithart.beatster.ui.theme.AudioPink
import com.datawithart.beatster.ui.theme.PadOrange
import com.datawithart.beatster.ui.theme.RecordRed
import com.datawithart.beatster.ui.theme.SynthTeal

fun TrackType.color(): Color = when (this) {
    TrackType.Drum -> PadOrange
    TrackType.Synth -> SynthTeal
    TrackType.Audio -> AudioPink
}

fun TrackType.icon(): ImageVector = when (this) {
    TrackType.Drum -> Icons.Filled.GridView
    TrackType.Synth -> Icons.Filled.MusicNote
    TrackType.Audio -> Icons.Filled.Mic
}

/** Clip position of [track] in 0..1, or 0 when stopped. */
fun loopProgress(track: Track, transport: TransportState): Float {
    if (!transport.playing || transport.beat < 0) return 0f
    return (transport.beat.mod(track.lengthBeats) / track.lengthBeats).toFloat()
}

@Composable
fun TrackStrip(
    tracks: List<Track>,
    selected: Int,
    transport: State<TransportState>,
    recordEnabled: Boolean,
    onSelect: (Int) -> Unit,
    onMute: (Track) -> Unit,
    onSolo: (Track) -> Unit,
    onAdd: (TrackType) -> Unit,
    onDelete: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
) {
    var menuFor by remember { mutableStateOf<Track?>(null) }
    var renaming by remember { mutableStateOf<Track?>(null) }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tracks, key = { it.index }) { track ->
            Box {
                TrackCard(
                    track = track,
                    selected = track.index == selected,
                    recording = recordEnabled && track.index == selected,
                    progress = { loopProgress(track, transport.value) },
                    onClick = { onSelect(track.index) },
                    onLongClick = { menuFor = track },
                    onMute = { onMute(track) },
                    onSolo = { onSolo(track) },
                )
                DropdownMenu(expanded = menuFor == track, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        renaming = track
                        menuFor = null
                    })
                    DropdownMenuItem(text = { Text("Delete track") }, onClick = {
                        onDelete(track.index)
                        menuFor = null
                    })
                }
            }
        }
        if (tracks.size < MAX_TRACKS) {
            item { AddTrackButton(onAdd) }
        }
    }

    renaming?.let { track ->
        var name by remember(track.index) { mutableStateOf(track.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename track") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    onRename(track.index, name)
                    renaming = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackCard(
    track: Track,
    selected: Boolean,
    recording: Boolean,
    progress: () -> Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
) {
    val accent = track.type.color()
    Card(
        modifier = Modifier
            .width(132.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = when {
            recording -> BorderStroke(2.dp, RecordRed)
            selected -> BorderStroke(2.dp, accent)
            else -> null
        },
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(track.type.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    track.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${track.lengthBars} bar${if (track.lengthBars > 1) "s" else ""}" +
                    if (track.type != TrackType.Audio) " · ${track.notes.size} notes" else "",
                style = MaterialTheme.typography.labelSmall,
            )
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = accent,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallToggle("M", track.mute, MaterialTheme.colorScheme.error, onMute)
                SmallToggle("S", track.solo, SynthTeal, onSolo)
            }
        }
    }
}

@Composable
private fun SmallToggle(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(28.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = if (on) color else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun AddTrackButton(onAdd: (TrackType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier.width(64.dp).height(112.dp),
            onClick = { open = true },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "Add track")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TrackType.entries.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (type) {
                                TrackType.Drum -> "Drum track (pads)"
                                TrackType.Synth -> "Synth track (MIDI keys)"
                                TrackType.Audio -> "Audio track (mic / voice)"
                            },
                        )
                    },
                    leadingIcon = { Icon(type.icon(), contentDescription = null, tint = type.color()) },
                    onClick = {
                        onAdd(type)
                        open = false
                    },
                )
            }
        }
    }
}

private val LENGTH_CHOICES = listOf(1, 2, 4, 8, 16)

@Composable
fun TrackToolbar(
    track: Track,
    canUndo: Boolean,
    onLength: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onEffects: () -> Unit,
    onSynth: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Bars", style = MaterialTheme.typography.labelMedium)
        LENGTH_CHOICES.forEach { bars ->
            FilterChip(
                selected = track.lengthBars == bars,
                onClick = { onLength(bars) },
                label = { Text("$bars") },
            )
        }
        if (track.type != TrackType.Audio) {
            IconButton(onClick = onDuplicate, enabled = track.lengthBars < 16) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Double the clip")
            }
        }
        IconButton(onClick = onClear) { Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear clip") }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        if (track.type == TrackType.Synth) {
            IconButton(onClick = onSynth) { Icon(Icons.Filled.GraphicEq, contentDescription = "Synth sound") }
        }
        IconButton(onClick = onEffects) { Icon(Icons.Filled.Tune, contentDescription = "Effects and mix") }
    }
}
