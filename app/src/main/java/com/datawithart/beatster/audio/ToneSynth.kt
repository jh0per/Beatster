package com.datawithart.beatster.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tiny tone generator for a quick synth-like sound.
 */
class ToneSynth {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    fun play(frequency: Float = 440f, durationMs: Int = 400) {
        val sampleRate = 44_100
        val samples = (sampleRate * durationMs) / 1000
        if (samples <= 0) return

        val buffer = ShortArray(samples) { index ->
            val angle = 2.0 * PI * index * frequency / sampleRate
            var value = sin(angle)
            // Apply simple 5ms attack/release fade to reduce clicks.
            val attackSamples = (sampleRate * 0.005).toInt().coerceAtLeast(1)
            val releaseStart = samples - attackSamples
            val gain = when {
                index < attackSamples -> index.toFloat() / attackSamples
                index >= releaseStart -> (samples - index).toFloat() / attackSamples
                else -> 1f
            }
            (value * gain * Short.MAX_VALUE).toInt().toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(buffer, 0, buffer.size)
        track.play()

        scope.launch {
            delay(durationMs + 100L)
            track.release()
        }
    }

    fun release() {
        scope.coroutineContext[Job]?.cancel()
    }
}
