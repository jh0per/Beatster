package com.datawithart.beatster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.datawithart.beatster.model.PadConfig
import com.datawithart.beatster.ui.theme.PadOrange

/**
 * 4x4 pad grid. Each pad tracks its own pointer, so several fingers can hold different pads at
 * once (finger drumming). In edit mode a tap opens the pad's settings instead of playing it.
 */
@Composable
fun PadGrid(
    pads: List<PadConfig>,
    loading: Set<Int>,
    looping: Set<Int>,
    editMode: Boolean,
    onEditMode: (Boolean) -> Unit,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = editMode,
                onClick = { onEditMode(!editMode) },
                label = { Text(if (editMode) "Tap a pad to edit it" else "Edit pads") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
        // MPC layout: pad 1 bottom-left, pad 16 top-right.
        for (row in 3 downTo 0) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (col in 0 until 4) {
                    val index = row * 4 + col
                    val pad = pads.getOrNull(index) ?: continue
                    Pad(
                        pad = pad,
                        loading = index in loading,
                        looping = index in looping,
                        editMode = editMode,
                        onDown = { onDown(index) },
                        onUp = { onUp(index) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Pad(
    pad: PadConfig,
    loading: Boolean,
    looping: Boolean,
    editMode: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val hasSound = pad.source != null
    val shape = RoundedCornerShape(12.dp)
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    pressed -> PadOrange
                    looping -> PadOrange.copy(alpha = 0.45f)
                    hasSound -> base
                    else -> MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .border(1.dp, if (editMode) PadOrange else PadOrange.copy(alpha = 0.25f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    down()
                    // Ends on lift or when the gesture is cancelled (e.g. finger slides off).
                    waitForUpOrCancellation()?.consume()
                    pressed = false
                    up()
                }
            }
            .padding(8.dp),
    ) {
        Text(
            text = "${pad.index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (looping) {
            Icon(
                Icons.Filled.Loop,
                contentDescription = "Looping",
                modifier = Modifier.align(Alignment.TopEnd).size(14.dp),
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(
                text = if (hasSound) pad.name else "empty",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}
