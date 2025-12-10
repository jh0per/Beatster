package com.datawithart.beatster.audio

import android.media.MediaPlayer
import java.io.File

/**
 * Simple looping player for recorded files.
 */
class LoopPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun playLoop(file: File): Boolean {
        stop()
        return try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        } finally {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun release() {
        stop()
    }
}
