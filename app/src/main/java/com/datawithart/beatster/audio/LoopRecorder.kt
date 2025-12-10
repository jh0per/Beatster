package com.datawithart.beatster.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Lightweight loop recorder using the device mic. Writes to AAC (.m4a) for quick reuse.
 */
class LoopRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): Boolean {
        stop()
        return try {
            val file = File(context.cacheDir, "loop.m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            outputFile = file
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop(): File? {
        return try {
            recorder?.run {
                stop()
                release()
            }
            recorder = null
            outputFile?.takeIf { it.exists() }
        } catch (_: Exception) {
            recorder?.release()
            recorder = null
            null
        }
    }

    fun release() {
        stop()
    }
}
