package com.datawithart.beatster

import android.app.Application
import android.net.Uri
import com.datawithart.beatster.audio.LoadedSample
import com.datawithart.beatster.audio.PadAudioController
import com.datawithart.beatster.audio.SoundPoolAudioController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

data class PadState(
    val index: Int,
    val label: String,
    val soundName: String? = null,
    val soundId: Int? = null,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isLooping: Boolean = false
)

class PadsViewModel(application: Application) : AndroidViewModel(application) {
    // TODO: swap to a NativeAudioController when the Oboe-backed engine is ready.
    private val audioController: PadAudioController = SoundPoolAudioController(application)

    var isRecordingLoop by mutableStateOf(false)
        private set

    var isLoopAvailable by mutableStateOf(false)
        private set

    var isLoopPlaying by mutableStateOf(false)
        private set

    private var loopFile: File? = null

    var padCount by mutableStateOf(8)
        private set

    val pads = mutableStateListOf<PadState>().apply {
        repeat(16) { index ->
            add(PadState(index = index, label = "Pad ${index + 1}"))
        }
    }

    init {
        preloadFromAssets()
        preloadFromSamplesFolder()
    }

    fun updatePadCount(count: Int) {
        padCount = count
    }

    fun triggerPad(index: Int) {
        pads.getOrNull(index)?.soundId?.let { audioController.playSample(it) }
    }

    fun loadPadSound(index: Int, uri: Uri) {
        updatePad(index) { current -> current.copy(isLoading = true) }
        val previous = pads.getOrNull(index)
        val previousId = previous?.soundId
        if (previous?.isLooping == true && previousId != null) {
            audioController.stopSampleLoop(previousId)
        }

        viewModelScope.launch {
            val result = audioController.loadSample(uri)
            withContext(Dispatchers.Main) {
                previousId?.let { audioController.unloadSample(it) }
                applyLoadedSample(index, result)
            }
        }
    }

    private fun applyLoadedSample(index: Int, sample: LoadedSample?) {
        updatePad(index) { current ->
            if (sample != null) {
                current.copy(
                    soundName = sample.label,
                    soundId = sample.id,
                    isLoaded = true,
                    isLoading = false,
                    isLooping = false
                )
            } else {
                current.copy(isLoading = false)
            }
        }
    }

    fun toggleLoop(index: Int) {
        val pad = pads.getOrNull(index) ?: return
        val soundId = pad.soundId ?: return

        if (pad.isLooping) {
            audioController.stopSampleLoop(soundId)
            updatePad(index) { it.copy(isLooping = false) }
        } else {
            val started = audioController.playSampleLoop(soundId)
            if (started) {
                updatePad(index) { it.copy(isLooping = true) }
            }
        }
    }

    private fun updatePad(index: Int, transform: (PadState) -> PadState) {
        val pad = pads.getOrNull(index) ?: return
        pads[index] = transform(pad)
    }

    override fun onCleared() {
        super.onCleared()
        audioController.release()
    }

    fun startRecordingLoop(): Boolean {
        if (isRecordingLoop) return false
        val started = audioController.startRecordingLoop()
        isRecordingLoop = started
        if (!started) {
            loopFile = null
            isLoopAvailable = false
        }
        return started
    }

    fun stopRecordingLoop() {
        val file = audioController.stopRecordingLoop()
        loopFile = file
        isRecordingLoop = false
        isLoopAvailable = file != null
    }

    fun toggleLoopPlayback() {
        val file = loopFile ?: return
        if (isLoopPlaying) {
            audioController.stopRecordedLoop()
            isLoopPlaying = false
        } else {
            val started = audioController.playRecordedLoop(file)
            isLoopPlaying = started
        }
    }

    fun playSynth(frequency: Float = 440f) {
        audioController.playTone(frequency)
    }

    private fun preloadFromAssets() {
        viewModelScope.launch(Dispatchers.IO) {
            val assets = getApplication<Application>().assets
            val files = collectAssetSamples(assets, "samples")
            var padIndex = 0
            files.forEach { name ->
                if (padIndex >= pads.size) return@forEach
                val assetPath = name
                val sample = audioController.loadSampleAsset(assetPath)
                if (sample != null) {
                    val index = padIndex
                    withContext(Dispatchers.Main) {
                        applyLoadedSample(index, sample)
                    }
                    padIndex++
                }
            }
        }
    }

    private fun collectAssetSamples(assets: android.content.res.AssetManager, root: String): List<String> {
        val result = mutableListOf<String>()
        fun walk(path: String) {
            val list = runCatching { assets.list(path) }.getOrNull() ?: return
            list.forEach { child ->
                if (child == ".DS_Store") return@forEach
                val fullPath = if (path.isEmpty()) child else "$path/$child"
                if (child.contains('.')) {
                    val isAudio = child.endsWith(".wav", true) || child.endsWith(".mp3", true)
                    if (isAudio) result.add(fullPath)
                } else {
                    walk(fullPath)
                }
            }
        }
        walk(root)
        return result.sorted()
    }

    private fun preloadFromSamplesFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = File(getApplication<Application>().filesDir, "samples")
            if (!folder.exists()) return@launch
            val files = folder.listFiles { f -> f.isFile && (f.extension.equals("wav", true) || f.extension.equals("mp3", true)) }
                ?.sortedBy { it.name }
                .orEmpty()
            var padIndex = 0
            files.forEach { file ->
                if (padIndex >= pads.size) return@forEach
                val sample = audioController.loadSample(file)
                if (sample != null) {
                    val index = padIndex
                    withContext(Dispatchers.Main) {
                        applyLoadedSample(index, sample)
                    }
                    padIndex++
                }
            }
        }
    }
}
