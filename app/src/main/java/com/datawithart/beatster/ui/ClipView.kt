package com.datawithart.beatster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.datawithart.beatster.model.BEATS_PER_BAR
import com.datawithart.beatster.model.Track
import com.datawithart.beatster.model.TrackType
import com.datawithart.beatster.studio.TransportState
import com.datawithart.beatster.ui.theme.RecordRed

/**
 * Overview of the selected clip: notes (drum rows by pad, synth rows by pitch) or the audio
 * waveform, with bar lines and the play head. The play head is read in the draw phase only, so
 * the 60 fps transport updates redraw this canvas without recomposing the screen.
 */
@Composable
fun ClipView(track: Track, peaks: FloatArray?, transport: State<TransportState>, modifier: Modifier = Modifier) {
    val accent = track.type.color()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surfaceContainer
    Canvas(modifier = modifier.clip(RoundedCornerShape(10.dp)).background(background)) {
        val beats = track.lengthBeats
        val beatWidth = size.width / beats.toFloat()

        // Beat and bar lines.
        for (b in 0 until beats.toInt()) {
            val x = b * beatWidth
            val bar = b % BEATS_PER_BAR == 0
            drawLine(
                color = if (bar) grid else grid.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = if (bar) 2f else 1f,
            )
        }

        when (track.type) {
            TrackType.Audio -> if (peaks != null && peaks.isNotEmpty()) {
                // The take may be shorter or longer than the clip if the length changed later.
                val takeBeats = track.audioBeats ?: beats
                val takeWidth = (takeBeats / beats).toFloat().coerceAtMost(1f) * size.width
                val step = takeWidth / peaks.size
                val mid = size.height / 2
                for (i in peaks.indices) {
                    val h = peaks[i] * size.height * 0.9f / 2
                    drawLine(accent, Offset(i * step, mid - h), Offset(i * step, mid + h), strokeWidth = maxOf(step * 0.7f, 1f))
                }
            }
            else -> if (track.notes.isNotEmpty()) {
                val lowest = track.notes.minOf { it.pitch }
                val highest = track.notes.maxOf { it.pitch }
                val rows = (highest - lowest + 1).coerceAtLeast(if (track.type == TrackType.Drum) 4 else 12)
                val rowHeight = size.height / rows
                for (note in track.notes) {
                    if (note.start >= beats) continue
                    val x = (note.start * beatWidth).toFloat()
                    val w = (minOf(note.length, beats - note.start) * beatWidth).toFloat().coerceAtLeast(3f)
                    val y = size.height - (note.pitch - lowest + 1) * rowHeight
                    drawRoundRect(
                        color = accent.copy(alpha = 0.35f + 0.65f * note.velocity),
                        topLeft = Offset(x, y + 1f),
                        size = Size(w, (rowHeight - 2f).coerceAtLeast(2f)),
                        cornerRadius = CornerRadius(3f, 3f),
                    )
                }
            }
        }

        val t = transport.value
        if (t.playing && t.beat >= 0) {
            val x = (t.beat.mod(beats) * beatWidth).toFloat()
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
    }
}

/** Audio track controls: what will happen when you press record, and the input level. */
@Composable
fun AudioPanel(
    track: Track,
    transport: State<TransportState>,
    recordEnabled: Boolean,
    micEnabled: Boolean,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = transport.value
    val armedHere = t.recordingTrack == track.index
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        FilledIconButton(
            onClick = onRecord,
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (recordEnabled) RecordRed else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (recordEnabled) Color.White else RecordRed,
            ),
        ) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record audio", modifier = Modifier.size(48.dp))
        }
        Text(
            text = when {
                armedHere && t.rolling -> "Recording… sing or play now"
                armedHere -> "Armed: recording starts at the top of the loop"
                track.audioFile != null || track.audioBeats != null -> "Press record to replace the take"
                else -> "Press record: after the count-in the mic records one clip (${track.lengthBars} bars), then it loops"
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (micEnabled) {
            Box(modifier = Modifier.fillMaxWidth(0.6f).height(10.dp)) {
                LevelMeterHorizontal(level = t.inputLevel, modifier = Modifier.fillMaxSize())
            }
            Text("Mic level", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "Tip: use headphones so the beat does not bleed into the mic. Add reverb or delay with the effects button.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LevelMeterHorizontal(level: Float, modifier: Modifier = Modifier) {
    val color = if (level > 0.95f) RecordRed else MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier.clip(RoundedCornerShape(5.dp))) {
        drawRect(track)
        drawRect(color, size = Size(size.width * level.coerceIn(0f, 1f), size.height))
    }
}
