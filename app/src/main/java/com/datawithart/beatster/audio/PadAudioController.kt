package com.datawithart.beatster.audio

import android.net.Uri
import java.io.File

/**
 * Abstraction for pad/loop audio so we can swap native Oboe or fallback engines.
 */
interface PadAudioController {
    suspend fun loadSample(uri: Uri): LoadedSample?
    suspend fun loadSample(file: File): LoadedSample?
    suspend fun loadSampleAsset(assetPath: String): LoadedSample?
    fun playSample(sampleId: Int)
    fun playSampleLoop(sampleId: Int): Boolean
    fun stopSampleLoop(sampleId: Int)
    fun stopAllSampleLoops()
    fun unloadSample(sampleId: Int)

    fun startRecordingLoop(): Boolean
    fun stopRecordingLoop(): File?
    fun playRecordedLoop(file: File): Boolean
    fun stopRecordedLoop()

    fun playTone(frequency: Float)
    fun release()
}
