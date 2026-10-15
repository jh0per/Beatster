package com.datawithart.beatster

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.datawithart.beatster.audio.AudioDecoder
import com.datawithart.beatster.data.ProjectStore
import com.datawithart.beatster.engine.NativeEngine
import com.datawithart.beatster.engine.Pcm
import com.datawithart.beatster.studio.SampleLoader
import com.datawithart.beatster.studio.StudioController
import com.datawithart.beatster.studio.StudioState
import com.datawithart.beatster.studio.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android host for [StudioController]: creates the native engine, wires file decoding, and
 * follows the screen's lifecycle. All studio logic lives in the controller.
 */
class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val decoder = AudioDecoder(application)
    private val engineReady = NativeEngine.create()

    val studio = StudioController(
        engine = NativeEngine,
        store = ProjectStore(File(application.filesDir, "projects")),
        loader = object : SampleLoader {
            override fun loadAsset(path: String): Pcm? = decoder.decodeAsset(path)
            override fun loadFile(file: File): Pcm? = decoder.decodeFile(file)
        },
        scope = viewModelScope,
        io = Dispatchers.IO,
    )

    val state: StateFlow<StudioState> = studio.state
    val transport: StateFlow<TransportState> = studio.transport

    init {
        studio.start()
        if (!engineReady) studio.showMessage("Audio engine unavailable on this device")
    }

    /** Called every frame while the screen is visible. */
    fun tick() = studio.tick()

    /** Keeps audio running in the background only while the transport plays. */
    fun onVisibilityChanged(visible: Boolean) {
        if (visible) {
            NativeEngine.setRunning(true)
        } else {
            studio.save()
            if (!transport.value.playing) NativeEngine.setRunning(false)
        }
    }

    fun loadPadFromUri(pad: Int, uri: Uri) {
        viewModelScope.launch {
            val (pcm, name) = withContext(Dispatchers.IO) { decoder.decodeUri(uri) to displayName(uri) }
            if (pcm == null) {
                studio.showMessage("Could not read that audio file")
            } else {
                studio.assignPadSample(pad, pcm, name ?: "Pad ${pad + 1}")
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.substringBeforeLast('.')
    }.getOrNull()

    override fun onCleared() {
        studio.save()
        NativeEngine.release()
        super.onCleared()
    }
}
