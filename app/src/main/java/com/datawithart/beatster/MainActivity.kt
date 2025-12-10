package com.datawithart.beatster

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.datawithart.beatster.ui.theme.BeatsterTheme

class MainActivity : ComponentActivity() {
    private val padsViewModel: PadsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeatsterTheme {
                BeatPadScreen(viewModel = padsViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeatPadScreen(viewModel: PadsViewModel = viewModel()) {
    val context = LocalContext.current
    var pendingPadIndex by remember { mutableStateOf<Int?>(null) }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecordingLoop()
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val index = pendingPadIndex
        if (uri != null && index != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers do not allow persistable permissions; ignore.
            }
            viewModel.loadPadSound(index, uri)
        }
        pendingPadIndex = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Beatster Pads") },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        FilterChip(
                            selected = viewModel.padCount == 8,
                            onClick = { viewModel.updatePadCount(8) },
                            label = { Text("8 Pads") }
                        )
                        FilterChip(
                            selected = viewModel.padCount == 16,
                            onClick = { viewModel.updatePadCount(16) },
                            label = { Text("16 Pads") }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Tap a pad to play. Load a custom sound onto each pad.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (viewModel.isRecordingLoop) {
                            viewModel.stopRecordingLoop()
                        } else {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                viewModel.startRecordingLoop()
                            } else {
                                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                ) {
                    Text(if (viewModel.isRecordingLoop) "Stop Recording Loop" else "Record Loop")
                }

                Button(
                    onClick = { viewModel.toggleLoopPlayback() },
                    enabled = viewModel.isLoopAvailable
                ) {
                    Text(if (viewModel.isLoopPlaying) "Stop Loop" else "Play Loop")
                }

                Button(onClick = { viewModel.playSynth(440f) }) {
                    Text("Play Synth")
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(viewModel.pads.take(viewModel.padCount), key = { it.index }) { pad ->
                    PadCard(
                        pad = pad,
                        onTrigger = { viewModel.triggerPad(pad.index) },
                        onToggleLoop = { viewModel.toggleLoop(pad.index) },
                        onLoad = {
                            pendingPadIndex = pad.index
                            picker.launch(arrayOf("audio/*"))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PadCard(
    pad: PadState,
    onTrigger: () -> Unit,
    onToggleLoop: () -> Unit,
    onLoad: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTrigger,
                onLongClick = onLoad,
                enabled = !pad.isLoading
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (pad.isLoaded) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pad.label,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null
                )
            }
            Text(
                text = pad.soundName ?: if (pad.isLoading) "Loading..." else "Load a sound",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Text(
                text = "Tap to play, long-press to load",
                style = MaterialTheme.typography.labelSmall
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleLoop,
                    enabled = pad.isLoaded && !pad.isLoading
                ) {
                    Text(if (pad.isLooping) "Stop Loop" else "Loop")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun BeatPadPreview() {
    BeatsterTheme {
        val previewPads = List(8) { index ->
            PadState(
                index = index,
                label = "Pad ${index + 1}",
                soundName = if (index % 2 == 0) "Kick $index" else null,
                isLoaded = index % 2 == 0
            )
        }
        Scaffold {
            Column(
                modifier = Modifier
                    .padding(it)
                    .padding(16.dp)
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(previewPads) { pad ->
                        PadCard(pad = pad, onTrigger = {}, onToggleLoop = {}, onLoad = {})
                    }
                }
            }
        }
    }
}
