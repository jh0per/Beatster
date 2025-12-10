package com.datawithart.beatster.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Current fallback implementation using SoundPool/MediaRecorder/MediaPlayer/ToneSynth.
 * Keeps the app working while the native Oboe engine is being built.
 */
class SoundPoolAudioController(private val context: Context) : PadAudioController {
    private val audioEngine = AudioEngine(context)
    private val loopRecorder = LoopRecorder(context)
    private val loopPlayer = LoopPlayer()
    private val toneSynth = ToneSynth()

    override suspend fun loadSample(uri: Uri): LoadedSample? =
        withContext(Dispatchers.IO) { audioEngine.load(uri) }

    override suspend fun loadSample(file: File): LoadedSample? =
        withContext(Dispatchers.IO) { audioEngine.load(file) }

    override suspend fun loadSampleAsset(assetPath: String): LoadedSample? =
        withContext(Dispatchers.IO) { audioEngine.loadAsset(assetPath) ?: audioEngine.loadAssetViaCopy(assetPath, assetPath.substringAfterLast('/').substringBeforeLast('.')) }

    override fun playSample(sampleId: Int) {
        audioEngine.play(sampleId)
    }

    override fun playSampleLoop(sampleId: Int): Boolean {
        return audioEngine.playLoop(sampleId) != null
    }

    override fun stopSampleLoop(sampleId: Int) {
        audioEngine.stopLoop(sampleId)
    }

    override fun stopAllSampleLoops() {
        audioEngine.stopAllLoops()
    }

    override fun unloadSample(sampleId: Int) {
        audioEngine.unload(sampleId)
    }

    override fun startRecordingLoop(): Boolean {
        return loopRecorder.start()
    }

    override fun stopRecordingLoop(): File? {
        return loopRecorder.stop()
    }

    override fun playRecordedLoop(file: File): Boolean {
        return loopPlayer.playLoop(file)
    }

    override fun stopRecordedLoop() {
        loopPlayer.stop()
    }

    override fun playTone(frequency: Float) {
        toneSynth.play(frequency)
    }

    override fun release() {
        audioEngine.stopAllLoops()
        toneSynth.release()
        loopPlayer.stop()
        loopRecorder.release()
        audioEngine.release()
    }
}
