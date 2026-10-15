package com.datawithart.beatster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.datawithart.beatster.data.ProjectStore
import com.datawithart.beatster.model.FxSettings
import com.datawithart.beatster.model.PadConfig
import com.datawithart.beatster.model.SynthSettings
import com.datawithart.beatster.model.Track
import com.datawithart.beatster.ui.theme.RecordRed
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .then(SheetPadding)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    steps: Int = 0,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

private fun percent(v: Float) = "${(v * 100).roundToInt()}%"

private fun hz(v: Float) = if (v >= 1000f) "${"%.1f".format(v / 1000f)} kHz" else "${v.roundToInt()} Hz"

// Cutoff sliders work in log space so the useful low range is not squashed.
private fun cutoffToSlider(hz: Float) = (kotlin.math.ln(hz / 20f) / kotlin.math.ln(1000f)).coerceIn(0f, 1f)
private fun sliderToCutoff(v: Float) = 20f * kotlin.math.exp(v * kotlin.math.ln(1000f))

// ------------------------------------------------------------------------- pad

@Composable
fun PadSheet(
    pad: PadConfig,
    looping: Boolean,
    sampling: Boolean,
    onChange: (PadConfig) -> Unit,
    onLoadFile: () -> Unit,
    onSampleStart: () -> Unit,
    onSampleStop: () -> Unit,
    onToggleLoop: () -> Unit,
    onPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    StudioSheet(title = "Pad ${pad.index + 1}: ${pad.name}", onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilledTonalButton(onClick = onPreview) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Play")
            }
            OutlinedButton(onClick = onLoadFile) {
                Icon(Icons.Filled.AudioFile, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Load file")
            }
            FilterChip(
                selected = looping,
                onClick = onToggleLoop,
                enabled = pad.source != null,
                label = { Text(if (looping) "Looping" else "Loop") },
                leadingIcon = { Icon(Icons.Filled.Loop, contentDescription = null) },
            )
        }
        HoldToRecordButton(sampling = sampling, onStart = onSampleStart, onStop = onSampleStop)
        HorizontalDivider()
        LabeledSlider("Volume", pad.volume, 0f..1.5f, percent(pad.volume)) { onChange(pad.copy(volume = it)) }
        LabeledSlider("Pitch", pad.pitch, -24f..24f, "${pad.pitch.roundToInt()} st", steps = 47) {
            onChange(pad.copy(pitch = it.roundToInt().toFloat()))
        }
        LabeledSlider("Pan", pad.pan, -1f..1f, if (pad.pan == 0f) "C" else percent(pad.pan)) { onChange(pad.copy(pan = it)) }
        SwitchRow("Play only while held (gate)", pad.gate) { onChange(pad.copy(gate = it)) }
        SectionLabel("Choke group: pads in the same group cut each other off")
        ChoiceRow(listOf(0 to "None", 1 to "1", 2 to "2", 3 to "3", 4 to "4"), pad.chokeGroup) {
            onChange(pad.copy(chokeGroup = it))
        }
    }
}

/** Press and hold to record from the mic onto the pad; release to keep it. */
@Composable
private fun HoldToRecordButton(sampling: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (sampling) RecordRed else MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    start()
                    waitForUpOrCancellation()?.consume()
                    stop()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = if (sampling) Color.White else RecordRed)
            Spacer(Modifier.width(8.dp))
            Text(
                if (sampling) "Recording… release to keep" else "Hold to sample from the mic",
                color = if (sampling) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ------------------------------------------------------------------------- effects

@Composable
fun EffectsSheet(track: Track, onFx: (FxSettings) -> Unit, onMix: (Float, Float) -> Unit, onDismiss: () -> Unit) {
    val fx = track.fx
    StudioSheet(title = "${track.name}: mix and effects", onDismiss = onDismiss) {
        LabeledSlider("Volume", track.volume, 0f..1.5f, percent(track.volume)) { onMix(it, track.pan) }
        LabeledSlider("Pan", track.pan, -1f..1f, if (track.pan == 0f) "C" else percent(track.pan)) { onMix(track.volume, it) }
        HorizontalDivider()

        SwitchRow("Filter", fx.filterOn) { onFx(fx.copy(filterOn = it)) }
        if (fx.filterOn) {
            ChoiceRow(listOf(0 to "Low-pass", 1 to "High-pass", 2 to "Band-pass"), fx.filterMode) { onFx(fx.copy(filterMode = it)) }
            LabeledSlider("Cutoff", cutoffToSlider(fx.filterCutoff), 0f..1f, hz(fx.filterCutoff)) {
                onFx(fx.copy(filterCutoff = sliderToCutoff(it)))
            }
            LabeledSlider("Resonance", fx.filterResonance, 0f..1f, percent(fx.filterResonance)) { onFx(fx.copy(filterResonance = it)) }
        }
        HorizontalDivider()

        SwitchRow("Drive", fx.driveOn) { onFx(fx.copy(driveOn = it)) }
        if (fx.driveOn) {
            LabeledSlider("Amount", fx.driveAmount, 0f..1f, percent(fx.driveAmount)) { onFx(fx.copy(driveAmount = it)) }
        }
        HorizontalDivider()

        SwitchRow("Bit crusher (lo-fi)", fx.crushOn) { onFx(fx.copy(crushOn = it)) }
        if (fx.crushOn) {
            LabeledSlider("Bits", fx.crushBits, 2f..16f, "${fx.crushBits.roundToInt()}", steps = 13) {
                onFx(fx.copy(crushBits = it.roundToInt().toFloat()))
            }
            LabeledSlider("Downsample", fx.crushDownsample, 1f..32f, "×${fx.crushDownsample.roundToInt()}") {
                onFx(fx.copy(crushDownsample = it.roundToInt().toFloat()))
            }
        }
        HorizontalDivider()

        SwitchRow("Delay (tempo-synced)", fx.delayOn) { onFx(fx.copy(delayOn = it)) }
        if (fx.delayOn) {
            ChoiceRow(
                listOf(0.25f to "1/16", 0.5f to "1/8", 0.75f to "1/8.", 1f to "1/4", 1.5f to "1/4.", 2f to "1/2"),
                fx.delayBeats,
            ) { onFx(fx.copy(delayBeats = it)) }
            LabeledSlider("Feedback", fx.delayFeedback, 0f..0.95f, percent(fx.delayFeedback)) { onFx(fx.copy(delayFeedback = it)) }
            LabeledSlider("Mix", fx.delayMix, 0f..1f, percent(fx.delayMix)) { onFx(fx.copy(delayMix = it)) }
        }
        HorizontalDivider()

        SwitchRow("Reverb", fx.reverbOn) { onFx(fx.copy(reverbOn = it)) }
        if (fx.reverbOn) {
            LabeledSlider("Size", fx.reverbSize, 0f..1f, percent(fx.reverbSize)) { onFx(fx.copy(reverbSize = it)) }
            LabeledSlider("Damping", fx.reverbDamping, 0f..1f, percent(fx.reverbDamping)) { onFx(fx.copy(reverbDamping = it)) }
            LabeledSlider("Mix", fx.reverbMix, 0f..1f, percent(fx.reverbMix)) { onFx(fx.copy(reverbMix = it)) }
        }
    }
}

// ------------------------------------------------------------------------- synth

@Composable
fun SynthSheet(track: Track, pads: List<PadConfig>, onChange: (SynthSettings) -> Unit, onDismiss: () -> Unit) {
    val s = track.synth
    StudioSheet(title = "${track.name}: sound", onDismiss = onDismiss) {
        SectionLabel("Source")
        ChoiceRow(
            listOf(-1 to "Synth") + pads.filter { it.source != null }.map { it.index to "Pad ${it.index + 1}: ${it.name}" },
            s.sourcePad,
        ) { onChange(s.copy(sourcePad = it)) }
        if (s.sourcePad < 0) {
            SectionLabel("Wave")
            ChoiceRow(listOf(0 to "Saw", 1 to "Square", 2 to "Sine", 3 to "Triangle"), s.wave) { onChange(s.copy(wave = it)) }
            LabeledSlider("Cutoff", cutoffToSlider(s.cutoff), 0f..1f, hz(s.cutoff)) { onChange(s.copy(cutoff = sliderToCutoff(it))) }
            LabeledSlider("Resonance", s.resonance, 0f..1f, percent(s.resonance)) { onChange(s.copy(resonance = it)) }
            LabeledSlider("Detune", s.detune, 0f..50f, "${s.detune.roundToInt()} ct") { onChange(s.copy(detune = it)) }
        } else {
            Text(
                "Keys play the pad's sample pitched chromatically (C4 = original pitch).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
        LabeledSlider("Attack", s.attack, 0.001f..2f, "${(s.attack * 1000).roundToInt()} ms") { onChange(s.copy(attack = it)) }
        LabeledSlider("Decay", s.decay, 0.01f..3f, "${(s.decay * 1000).roundToInt()} ms") { onChange(s.copy(decay = it)) }
        LabeledSlider("Sustain", s.sustain, 0f..1f, percent(s.sustain)) { onChange(s.copy(sustain = it)) }
        LabeledSlider("Release", s.release, 0.01f..4f, "${(s.release * 1000).roundToInt()} ms") { onChange(s.copy(release = it)) }
    }
}

// ------------------------------------------------------------------------- projects

@Composable
fun ProjectsSheet(
    current: String,
    projects: List<ProjectStore.Entry>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSaveAs: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var saveAsName by remember { mutableStateOf("") }
    StudioSheet(title = "Projects", onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = saveAsName,
                onValueChange = { saveAsName = it },
                label = { Text("Save as…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSaveAs(saveAsName) }, enabled = saveAsName.isNotBlank()) { Text("Save") }
        }
        FilledTonalButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("New project") }
        HorizontalDivider()
        val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        projects.forEach { entry ->
            ListItem(
                headlineContent = {
                    Text(entry.name + if (entry.folder == current) "  (open)" else "")
                },
                supportingContent = { Text(format.format(Date(entry.modified))) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(entry.folder) },
            )
        }
    }
}
