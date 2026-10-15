package com.datawithart.beatster.engine

import android.util.Log
import com.datawithart.beatster.model.Note
import com.datawithart.beatster.model.TrackType

/**
 * JNI bridge to the Oboe engine in :audioengine (libbeatster_audio.so). If the library
 * or the audio device is unavailable every call degrades to a no-op instead of crashing.
 */
object NativeEngine : AudioEngine {
    private const val TAG = "NativeEngine"

    private val libraryLoaded: Boolean = runCatching { System.loadLibrary("beatster_audio") }
        .onFailure { Log.e(TAG, "libbeatster_audio not loaded", it) }
        .isSuccess

    @Volatile
    private var created = false

    /** Opens the output stream. Safe to call repeatedly. */
    @Synchronized
    fun create(): Boolean {
        if (!libraryLoaded) return false
        if (!created) created = nativeCreate()
        return created
    }

    override val isAvailable: Boolean get() = created

    override val sampleRate: Int get() = if (created) nativeSampleRate() else 48_000

    override fun setRunning(running: Boolean) {
        if (created) nativeSetRunning(running)
    }

    override fun enableInput(enable: Boolean): Boolean = created && nativeEnableInput(enable)

    override fun setPadSample(pad: Int, pcm: Pcm): Boolean =
        created && nativeSetPadSample(pad, pcm.samples, pcm.channels, pcm.sampleRate)

    override fun clearPad(pad: Int) {
        if (created) nativeClearPad(pad)
    }

    override fun setPadParam(pad: Int, param: Int, value: Float) {
        if (created) nativeSetPadParam(pad, param, value)
    }

    override fun noteOn(track: Int, note: Int, velocity: Float) {
        if (created) nativeNoteOn(track, note, velocity)
    }

    override fun noteOff(track: Int, note: Int) {
        if (created) nativeNoteOff(track, note)
    }

    override fun padLoop(track: Int, pad: Int, on: Boolean) {
        if (created) nativePadLoop(track, pad, on)
    }

    override fun allNotesOff() {
        if (created) nativeAllNotesOff()
    }

    override fun setTrackType(track: Int, type: TrackType) {
        if (created) nativeSetTrackType(track, type.ordinal)
    }

    override fun setTrackParam(track: Int, param: Int, value: Float) {
        if (created) nativeSetTrackParam(track, param, value)
    }

    override fun setTrackLength(track: Int, beats: Double) {
        if (created) nativeSetTrackLength(track, beats)
    }

    override fun setPattern(track: Int, notes: List<Note>, flags: (Note) -> Int) {
        if (!created) return
        nativeSetPattern(
            track,
            DoubleArray(notes.size) { notes[it].start },
            DoubleArray(notes.size) { notes[it].length },
            IntArray(notes.size) { notes[it].pitch },
            FloatArray(notes.size) { notes[it].velocity },
            IntArray(notes.size) { flags(notes[it]) },
        )
    }

    override fun setAudioClip(track: Int, mono: Pcm, lengthBeats: Double): Boolean =
        created && mono.channels == 1 && nativeSetAudioClip(track, mono.samples, mono.sampleRate, lengthBeats)

    override fun clearAudioClip(track: Int) {
        if (created) nativeClearAudioClip(track)
    }

    override fun getAudioClip(track: Int): FloatArray? = if (created) nativeGetAudioClip(track) else null

    override fun play(countInBeats: Double) {
        if (created) nativePlay(countInBeats)
    }

    override fun stop() {
        if (created) nativeStop()
    }

    override fun setTempo(bpm: Double) {
        if (created) nativeSetTempo(bpm)
    }

    override fun setMetronome(on: Boolean, volume: Float) {
        if (created) nativeSetMetronome(on, volume)
    }

    override fun armAudioRecord(track: Int, lengthBeats: Double, extraLatencyMs: Double): Boolean =
        created && nativeArmAudioRecord(track, lengthBeats, extraLatencyMs)

    override fun cancelAudioRecord() {
        if (created) nativeCancelAudioRecord()
    }

    override fun startCapture(maxSeconds: Float): Boolean = created && nativeStartCapture(maxSeconds)

    override fun stopCapture(): FloatArray? = if (created) nativeStopCapture() else null

    private val statusBuffer = DoubleArray(10)

    @Synchronized
    override fun status(): EngineStatus {
        if (!created) return EngineStatus()
        nativeGetStatus(statusBuffer)
        val s = statusBuffer
        return EngineStatus(
            playing = s[0] > 0.5,
            beat = s[1],
            tempo = s[2],
            outputLatencyMs = s[3],
            inputPeak = s[4].toFloat(),
            outputPeak = s[5].toFloat(),
            recordingTrack = s[6].toInt(),
            recordingRolling = s[7] > 0.5,
            captureFrames = s[8].toLong(),
            activeVoices = s[9].toInt(),
        )
    }

    override fun pollEvents(): List<EngineEvent> {
        if (!created) return emptyList()
        val raw = nativePollEvents()
        return (0 until raw.size / 2).mapNotNull { i ->
            when (raw[i * 2]) {
                EngineParams.EVENT_AUDIO_RECORD_STARTED -> EngineEvent.AudioRecordStarted(raw[i * 2 + 1])
                EngineParams.EVENT_AUDIO_RECORD_FINISHED -> EngineEvent.AudioRecordFinished(raw[i * 2 + 1])
                else -> null
            }
        }
    }

    @Synchronized
    override fun release() {
        if (created) nativeDestroy()
        created = false
    }

    @JvmStatic private external fun nativeCreate(): Boolean
    @JvmStatic private external fun nativeDestroy()
    @JvmStatic private external fun nativeSetRunning(running: Boolean): Boolean
    @JvmStatic private external fun nativeEnableInput(enable: Boolean): Boolean
    @JvmStatic private external fun nativeSampleRate(): Int
    @JvmStatic private external fun nativeSetPadSample(pad: Int, pcm: FloatArray, channels: Int, sampleRate: Int): Boolean
    @JvmStatic private external fun nativeClearPad(pad: Int)
    @JvmStatic private external fun nativeSetPadParam(pad: Int, param: Int, value: Float)
    @JvmStatic private external fun nativeNoteOn(track: Int, note: Int, velocity: Float)
    @JvmStatic private external fun nativeNoteOff(track: Int, note: Int)
    @JvmStatic private external fun nativePadLoop(track: Int, pad: Int, on: Boolean)
    @JvmStatic private external fun nativeAllNotesOff()
    @JvmStatic private external fun nativeSetTrackType(track: Int, type: Int)
    @JvmStatic private external fun nativeSetTrackParam(track: Int, param: Int, value: Float)
    @JvmStatic private external fun nativeSetTrackLength(track: Int, beats: Double)
    @JvmStatic private external fun nativeSetPattern(
        track: Int,
        starts: DoubleArray,
        lengths: DoubleArray,
        notes: IntArray,
        velocities: FloatArray,
        flags: IntArray,
    )
    @JvmStatic private external fun nativeSetAudioClip(track: Int, pcm: FloatArray, sampleRate: Int, lengthBeats: Double): Boolean
    @JvmStatic private external fun nativeClearAudioClip(track: Int)
    @JvmStatic private external fun nativeGetAudioClip(track: Int): FloatArray?
    @JvmStatic private external fun nativePlay(countInBeats: Double)
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeSetTempo(bpm: Double)
    @JvmStatic private external fun nativeSetMetronome(on: Boolean, volume: Float)
    @JvmStatic private external fun nativeArmAudioRecord(track: Int, lengthBeats: Double, extraLatencyMs: Double): Boolean
    @JvmStatic private external fun nativeCancelAudioRecord()
    @JvmStatic private external fun nativeStartCapture(maxSeconds: Float): Boolean
    @JvmStatic private external fun nativeStopCapture(): FloatArray?
    @JvmStatic private external fun nativeGetStatus(out: DoubleArray)
    @JvmStatic private external fun nativePollEvents(): IntArray
}
