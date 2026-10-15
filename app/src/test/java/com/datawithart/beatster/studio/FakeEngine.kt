package com.datawithart.beatster.studio

import com.datawithart.beatster.engine.AudioEngine
import com.datawithart.beatster.engine.EngineEvent
import com.datawithart.beatster.engine.EngineStatus
import com.datawithart.beatster.engine.Pcm
import com.datawithart.beatster.model.Note
import com.datawithart.beatster.model.TrackType

/** Records what the controller asks of the engine; the test drives the play head. */
class FakeEngine : AudioEngine {
    override val isAvailable = true
    override val sampleRate = 48_000

    var status = EngineStatus(tempo = 100.0, outputLatencyMs = 0.0)
    val calls = mutableListOf<String>()
    val pads = mutableMapOf<Int, Pcm>()
    val patterns = mutableMapOf<Int, List<Pair<Note, Int>>>()
    val trackTypes = mutableMapOf<Int, TrackType>()
    val trackLengths = mutableMapOf<Int, Double>()
    val trackParams = mutableMapOf<Pair<Int, Int>, Float>()
    val audioClips = mutableMapOf<Int, FloatArray>()
    val events = mutableListOf<EngineEvent>()
    var inputEnabled = false
    var armedTrack = -1
    var captured: FloatArray? = null

    override fun setRunning(running: Boolean) { calls += "running $running" }
    override fun enableInput(enable: Boolean): Boolean { inputEnabled = enable; return true }
    override fun setPadSample(pad: Int, pcm: Pcm): Boolean { pads[pad] = pcm; return true }
    override fun clearPad(pad: Int) { pads.remove(pad) }
    override fun setPadParam(pad: Int, param: Int, value: Float) = Unit
    override fun noteOn(track: Int, note: Int, velocity: Float) { calls += "on $track $note" }
    override fun noteOff(track: Int, note: Int) { calls += "off $track $note" }
    override fun padLoop(track: Int, pad: Int, on: Boolean) { calls += "loop $track $pad $on" }
    override fun allNotesOff() = Unit
    override fun setTrackType(track: Int, type: TrackType) { trackTypes[track] = type }
    override fun setTrackParam(track: Int, param: Int, value: Float) { trackParams[track to param] = value }
    override fun setTrackLength(track: Int, beats: Double) { trackLengths[track] = beats }
    override fun setPattern(track: Int, notes: List<Note>, flags: (Note) -> Int) {
        patterns[track] = notes.map { it to flags(it) }
    }
    override fun setAudioClip(track: Int, mono: Pcm, lengthBeats: Double): Boolean { audioClips[track] = mono.samples; return true }
    override fun clearAudioClip(track: Int) { audioClips.remove(track) }
    override fun getAudioClip(track: Int): FloatArray? = audioClips[track]
    override fun play(countInBeats: Double) {
        calls += "play $countInBeats"
        status = status.copy(playing = true, beat = -countInBeats)
    }
    override fun stop() {
        calls += "stop"
        status = status.copy(playing = false, beat = 0.0)
        armedTrack = -1
    }
    override fun setTempo(bpm: Double) { status = status.copy(tempo = bpm) }
    override fun setMetronome(on: Boolean, volume: Float) { calls += "metronome $on" }
    override fun armAudioRecord(track: Int, lengthBeats: Double, extraLatencyMs: Double): Boolean {
        if (!inputEnabled) return false
        armedTrack = track
        return true
    }
    override fun cancelAudioRecord() { armedTrack = -1 }
    override fun startCapture(maxSeconds: Float): Boolean = inputEnabled
    override fun stopCapture(): FloatArray? = captured
    override fun status(): EngineStatus = status
    override fun pollEvents(): List<EngineEvent> = events.toList().also { events.clear() }
    override fun release() = Unit

    /** Simulates the engine finishing an armed take. */
    fun finishTake(samples: FloatArray) {
        audioClips[armedTrack] = samples
        events += EngineEvent.AudioRecordFinished(armedTrack)
        armedTrack = -1
    }
}
