package com.datawithart.beatster.audio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Native-backed controller intended to wrap an Oboe engine.
 * Currently stubs native calls; replace with real Oboe plumbing inside :audioengine.
 */
class NativeAudioController(private val context: Context) : PadAudioController {

    private val nativeLoaded: Boolean = try {
        System.loadLibrary("beatster_audio")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    init {
        if (nativeLoaded) {
            nativeInit()
        }
    }

    override suspend fun loadSample(uri: Uri): LoadedSample? {
        val label = uri.lastPathSegment ?: "Sample"
        if (!nativeLoaded) return LoadedSample(0, label)
        // TODO: stream file into native engine once Oboe is integrated.
        val id = nativeLoadSample(context.contentResolver.openFileDescriptor(uri, "r")?.detachFd() ?: -1, label)
        return if (id > 0) LoadedSample(id, label) else null
    }

    override suspend fun loadSample(file: File): LoadedSample? {
        if (!nativeLoaded) return LoadedSample(0, file.nameWithoutExtension)
        val id = nativeLoadSample(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).detachFd(), file.name)
        return if (id > 0) LoadedSample(id, file.name) else null
    }

    override suspend fun loadSampleAsset(assetPath: String): LoadedSample? {
        val label = assetPath.substringAfterLast('/')
        if (!nativeLoaded) return LoadedSample(0, label)
        return null
    }

    override fun playSample(sampleId: Int) {
        if (nativeLoaded) nativePlaySample(sampleId)
    }

    override fun playSampleLoop(sampleId: Int): Boolean {
        return nativeLoaded && nativePlaySampleLoop(sampleId)
    }

    override fun stopSampleLoop(sampleId: Int) {
        if (nativeLoaded) nativeStopSampleLoop(sampleId)
    }

    override fun stopAllSampleLoops() {
        if (nativeLoaded) nativeStopAllSampleLoops()
    }

    override fun unloadSample(sampleId: Int) {
        if (nativeLoaded) nativeUnloadSample(sampleId)
    }

    override fun startRecordingLoop(): Boolean {
        return nativeLoaded && nativeStartLoopRecording()
    }

    override fun stopRecordingLoop(): File? {
        if (!nativeLoaded) return null
        val path = nativeStopLoopRecording() ?: return null
        return File(path)
    }

    override fun playRecordedLoop(file: File): Boolean {
        return nativeLoaded && nativePlayLoopFile(file.absolutePath)
    }

    override fun stopRecordedLoop() {
        if (nativeLoaded) nativeStopLoopFile()
    }

    override fun playTone(frequency: Float) {
        if (nativeLoaded) nativePlayTone(frequency)
    }

    override fun release() {
        if (nativeLoaded) nativeShutdown()
    }

    // Native hooks
    private external fun nativeInit()
    private external fun nativeLoadSample(fd: Int, label: String): Int
    private external fun nativePlaySample(sampleId: Int)
    private external fun nativePlaySampleLoop(sampleId: Int): Boolean
    private external fun nativeStopSampleLoop(sampleId: Int)
    private external fun nativeStopAllSampleLoops()
    private external fun nativeUnloadSample(sampleId: Int)

    private external fun nativeStartLoopRecording(): Boolean
    private external fun nativeStopLoopRecording(): String?
    private external fun nativePlayLoopFile(path: String): Boolean
    private external fun nativeStopLoopFile()
    private external fun nativePlayTone(frequency: Float)
    private external fun nativeShutdown()
}
