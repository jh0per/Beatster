package com.datawithart.beatster.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.datawithart.beatster.StudioViewModel
import com.datawithart.beatster.model.TrackType
import kotlinx.coroutines.isActive

/** Which bottom sheet is open. */
private sealed interface Sheet {
    data class Pad(val index: Int) : Sheet
    data class Effects(val track: Int) : Sheet
    data class Synth(val track: Int) : Sheet
    data object Projects : Sheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(viewModel: StudioViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transportState = viewModel.transport.collectAsStateWithLifecycle()
    val studio = viewModel.studio
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var padEditMode by rememberSaveable { mutableStateOf(false) }
    var pickerPad by rememberSaveable { mutableStateOf(-1) }

    // Poll the engine once per frame while the screen is on screen.
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { }
            viewModel.tick()
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        studio.clearMessage()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        studio.onMicPermission(granted)
    }
    LaunchedEffect(state.pendingMicAction) {
        if (state.pendingMicAction == null) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) studio.onMicPermission(true) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pad = pickerPad
        pickerPad = -1
        if (uri != null && pad >= 0) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.loadPadFromUri(pad, uri)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.project.name + if (state.dirty) " •" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { studio.save() }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save project")
                    }
                    IconButton(onClick = {
                        studio.refreshProjects()
                        sheet = Sheet.Projects
                    }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Projects")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TransportBar(
                project = state.project,
                transport = transportState.value,
                recordEnabled = state.recordEnabled,
                onPlay = studio::togglePlay,
                onStop = studio::stop,
                onRecord = studio::toggleRecord,
                onBpm = studio::setBpm,
                onMetronome = studio::toggleMetronome,
                onCountIn = studio::toggleCountIn,
                onQuantize = studio::setQuantize,
            )

            TrackStrip(
                tracks = state.project.tracks,
                selected = state.selectedTrack,
                transport = transportState,
                recordEnabled = state.recordEnabled,
                onSelect = studio::selectTrack,
                onMute = { studio.setTrackMix(it.index, mute = !it.mute) },
                onSolo = { studio.setTrackMix(it.index, solo = !it.solo) },
                onAdd = studio::addTrack,
                onDelete = studio::deleteTrack,
                onRename = studio::renameTrack,
            )

            val track = state.track
            if (track != null) {
                TrackToolbar(
                    track = track,
                    canUndo = state.canUndo,
                    onLength = { studio.setTrackLength(track.index, it) },
                    onDuplicate = { studio.duplicateClip(track.index) },
                    onClear = { studio.clearClip(track.index) },
                    onUndo = studio::undo,
                    onEffects = { sheet = Sheet.Effects(track.index) },
                    onSynth = { sheet = Sheet.Synth(track.index) },
                )
                ClipView(
                    track = track,
                    peaks = state.clipPeaks[track.index],
                    transport = transportState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (track.type == TrackType.Audio) 140.dp else 96.dp),
                )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (track?.type) {
                    TrackType.Synth -> Keyboard(
                        onKeyDown = { studio.keyDown(it) },
                        onKeyUp = { studio.keyUp(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    TrackType.Audio -> AudioPanel(
                        track = track,
                        transport = transportState,
                        recordEnabled = state.recordEnabled,
                        micEnabled = state.micEnabled,
                        onRecord = studio::toggleRecord,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> PadGrid(
                        pads = state.project.pads,
                        loading = state.loadingPads,
                        looping = state.loopingPads,
                        editMode = padEditMode,
                        onEditMode = { padEditMode = it },
                        onDown = { pad ->
                            if (padEditMode) sheet = Sheet.Pad(pad) else studio.padDown(pad)
                        },
                        onUp = { pad -> if (!padEditMode) studio.padUp(pad) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    when (val open = sheet) {
        is Sheet.Pad -> PadSheet(
            pad = state.project.pads[open.index],
            looping = open.index in state.loopingPads,
            sampling = state.samplingPad == open.index,
            onChange = studio::setPad,
            onLoadFile = {
                pickerPad = open.index
                picker.launch(arrayOf("audio/*"))
            },
            onSampleStart = { studio.startSampling(open.index) },
            onSampleStop = studio::stopSampling,
            onToggleLoop = { studio.togglePadLoop(open.index) },
            onPreview = {
                studio.padDown(open.index)
                studio.padUp(open.index)
            },
            onDismiss = { sheet = null },
        )
        is Sheet.Effects -> state.project.tracks.firstOrNull { it.index == open.track }?.let { t ->
            EffectsSheet(
                track = t,
                onFx = { studio.setFx(t.index, it) },
                onMix = { volume, pan -> studio.setTrackMix(t.index, volume = volume, pan = pan) },
                onDismiss = { sheet = null },
            )
        }
        is Sheet.Synth -> state.project.tracks.firstOrNull { it.index == open.track }?.let { t ->
            SynthSheet(
                track = t,
                pads = state.project.pads,
                onChange = { studio.setSynth(t.index, it) },
                onDismiss = { sheet = null },
            )
        }
        Sheet.Projects -> ProjectsSheet(
            current = state.folder,
            projects = state.savedProjects,
            onOpen = {
                studio.open(it)
                sheet = null
            },
            onNew = {
                studio.newProject()
                sheet = null
            },
            onSaveAs = {
                studio.saveAs(it)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
