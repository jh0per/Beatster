package com.datawithart.beatster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.datawithart.beatster.ui.theme.SynthTeal

private val WHITE_OFFSETS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteName(pitch: Int): String = NOTE_NAMES[pitch.mod(12)] + (pitch / 12 - 1)

/** Two-octave piano keyboard for synth tracks; each key holds its note while pressed. */
@Composable
fun Keyboard(onKeyDown: (Int) -> Unit, onKeyUp: (Int) -> Unit, modifier: Modifier = Modifier) {
    var octave by rememberSaveable { mutableIntStateOf(4) }  // C4 = MIDI 60
    val base = (octave + 1) * 12
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (octave > 1) octave-- }) { Icon(Icons.Filled.Remove, contentDescription = "Octave down") }
            Text("${noteName(base)} – ${noteName(base + 23)}", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { if (octave < 7) octave++ }) { Icon(Icons.Filled.Add, contentDescription = "Octave up") }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val whiteCount = 14
            val whiteWidth = maxWidth / whiteCount
            val blackWidth = whiteWidth * 0.62f
            Row(modifier = Modifier.fillMaxSize()) {
                for (i in 0 until whiteCount) {
                    val pitch = base + (i / 7) * 12 + WHITE_OFFSETS[i % 7]
                    Key(
                        pitch = pitch,
                        black = false,
                        onDown = onKeyDown,
                        onUp = onKeyUp,
                        modifier = Modifier.width(whiteWidth).fillMaxHeight().padding(horizontal = 1.dp),
                    )
                }
            }
            for (i in 0 until whiteCount) {
                val offsetInOctave = WHITE_OFFSETS[i % 7]
                val hasBlack = offsetInOctave != 4 && offsetInOctave != 11  // no black key after E and B
                if (!hasBlack || i == whiteCount - 1) continue
                val pitch = base + (i / 7) * 12 + offsetInOctave + 1
                Key(
                    pitch = pitch,
                    black = true,
                    onDown = onKeyDown,
                    onUp = onKeyUp,
                    modifier = Modifier
                        .offset(x = whiteWidth * (i + 1) - blackWidth / 2)
                        .width(blackWidth)
                        .fillMaxHeight(0.6f),
                )
            }
        }
    }
}

@Composable
private fun Key(pitch: Int, black: Boolean, onDown: (Int) -> Unit, onUp: (Int) -> Unit, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val currentPitch by rememberUpdatedState(pitch)
    val shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    pressed -> SynthTeal
                    black -> Color(0xFF0B0B0D)
                    else -> Color(0xFFECECEC)
                },
            )
            .border(1.dp, Color(0xFF3A3A40), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    val held = currentPitch
                    pressed = true
                    down(held)
                    waitForUpOrCancellation()?.consume()
                    pressed = false
                    up(held)
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (!black && pitch % 12 == 0) {
            Text(
                noteName(pitch),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF55555C),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
