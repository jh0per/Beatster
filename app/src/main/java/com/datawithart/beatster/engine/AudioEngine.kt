package com.datawithart.beatster.engine

import com.datawithart.beatster.model.Note
import com.datawithart.beatster.model.TrackType

/** Decoded audio: interleaved float samples. */
class Pcm(val samples: FloatArray, val channels: Int, val sampleRate: Int) {
    val frames: Int get() = if (channels > 0) samples.size / channels else 0
    val durationSeconds: Double get() = if (sampleRate > 0) frames.toDouble() / sampleRate else 0.0
}

data class EngineStatus(
    val playing: Boolean = false,
    val beat: Double = 0.0,
    val tempo: Double = 120.0,
    val outputLatencyMs: Double = -1.0,
    val inputPeak: Float = 0f,
    val outputPeak: Float = 0f,
    /** Track an audio take is armed on, or -1. */
    val recordingTrack: Int = -1,
    /** True once the armed take has reached its clip start and is capturing. */
    val recordingRolling: Boolean = false,
    val captureFrames: Long = 0,
    val activeVoices: Int = 0,
)

sealed interface EngineEvent {
    data class AudioRecordStarted(val track: Int) : EngineEvent
    data class AudioRecordFinished(val track: Int) : EngineEvent
}

/**
 * Everything the app asks of the audio engine. The real one is [NativeEngine]; tests use a fake.
 * Calls are cheap and non-blocking unless noted.
 */
interface AudioEngine {
    val isAvailable: Boolean
    val sampleRate: Int

    fun setRunning(running: Boolean)
    /** Opens the microphone. Needs RECORD_AUDIO already granted. */
    fun enableInput(enable: Boolean): Boolean

    fun setPadSample(pad: Int, pcm: Pcm): Boolean
    fun clearPad(pad: Int)
    fun setPadParam(pad: Int, param: Int, value: Float)

    fun noteOn(track: Int, note: Int, velocity: Float)
    fun noteOff(track: Int, note: Int)
    fun padLoop(track: Int, pad: Int, on: Boolean)
    fun allNotesOff()

    fun setTrackType(track: Int, type: TrackType)
    fun setTrackParam(track: Int, param: Int, value: Float)
    fun setTrackLength(track: Int, beats: Double)
    fun setPattern(track: Int, notes: List<Note>, flags: (Note) -> Int = { 0 })
    fun setAudioClip(track: Int, mono: Pcm, lengthBeats: Double): Boolean
    fun clearAudioClip(track: Int)
    /** The track's audio clip at [sampleRate], mono, or null if it has none. */
    fun getAudioClip(track: Int): FloatArray?

    fun play(countInBeats: Double)
    fun stop()
    fun setTempo(bpm: Double)
    fun setMetronome(on: Boolean, volume: Float)

    /** Records the mic into [track] for one clip length, starting at the clip's next loop start. */
    fun armAudioRecord(track: Int, lengthBeats: Double, extraLatencyMs: Double = 0.0): Boolean
    fun cancelAudioRecord()

    /** Mic sampling for a pad. [stopCapture] blocks briefly and returns mono audio at [sampleRate]. */
    fun startCapture(maxSeconds: Float): Boolean
    fun stopCapture(): FloatArray?

    fun status(): EngineStatus
    fun pollEvents(): List<EngineEvent>
    fun release()
}

/** Parameter ids shared with the native engine (core/EngineTypes.h). */
object EngineParams {
    const val PAD_VOLUME = 0
    const val PAD_PAN = 1
    const val PAD_PITCH = 2
    const val PAD_GATE = 3
    const val PAD_CHOKE_GROUP = 4

    const val TRACK_VOLUME = 0
    const val TRACK_PAN = 1
    const val TRACK_MUTE = 2
    const val TRACK_SOLO = 3

    const val SYNTH_WAVE = 10
    const val SYNTH_ATTACK = 11
    const val SYNTH_DECAY = 12
    const val SYNTH_SUSTAIN = 13
    const val SYNTH_RELEASE = 14
    const val SYNTH_CUTOFF = 15
    const val SYNTH_RESONANCE = 16
    const val SYNTH_DETUNE = 17
    const val SYNTH_SOURCE_PAD = 18

    const val FX_FILTER_ON = 30
    const val FX_FILTER_MODE = 31
    const val FX_FILTER_CUTOFF = 32
    const val FX_FILTER_RESONANCE = 33
    const val FX_DRIVE_ON = 40
    const val FX_DRIVE_AMOUNT = 41
    const val FX_CRUSH_ON = 45
    const val FX_CRUSH_BITS = 46
    const val FX_CRUSH_DOWNSAMPLE = 47
    const val FX_DELAY_ON = 50
    const val FX_DELAY_BEATS = 51
    const val FX_DELAY_FEEDBACK = 52
    const val FX_DELAY_MIX = 53
    const val FX_REVERB_ON = 60
    const val FX_REVERB_SIZE = 61
    const val FX_REVERB_DAMPING = 62
    const val FX_REVERB_MIX = 63

    /** Pattern note flag: already heard live, skip the first time the play head reaches it. */
    const val NOTE_SKIP_ONCE = 1

    const val EVENT_AUDIO_RECORD_STARTED = 2
    const val EVENT_AUDIO_RECORD_FINISHED = 3
}
