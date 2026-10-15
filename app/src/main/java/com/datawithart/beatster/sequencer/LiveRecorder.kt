package com.datawithart.beatster.sequencer

import com.datawithart.beatster.engine.EngineParams
import com.datawithart.beatster.model.Note
import com.datawithart.beatster.model.QuantizeGrid
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

object Quantizer {
    /**
     * Snaps a clip-relative position to the grid. The result is in [0, length): a note snapped
     * forward onto the loop end wraps to the start.
     */
    fun snap(position: Double, grid: QuantizeGrid, length: Double): Double {
        val step = grid.beats ?: return position.mod(length)
        val snapped = (position / step).roundToLong() * step
        return if (snapped >= length - EPSILON) snapped - length else maxOf(snapped, 0.0)
    }

    const val EPSILON = 1e-9
}

/**
 * Turns live note-on/off presses into pattern notes while the transport runs (MIDI recording).
 * Positions are in absolute transport beats; each track's clip loops every `length` beats.
 *
 * A press that quantises slightly ahead of the play head has already been heard live, so it is
 * flagged to be skipped the first time the engine reaches it (otherwise it would flam).
 */
class LiveRecorder(private val nextId: () -> Long) {

    private data class Held(val id: Long, val pressedAt: Double)

    private val held = mutableMapOf<Pair<Int, Int>, Held>()
    private val skipUntil = mutableMapOf<Long, Double>()

    /**
     * Records a press heard at [heardBeat]. [engineBeat] is where the engine has rendered up to.
     * Returns the track's new notes, or null if the press does not land in the clip
     * (e.g. well before the count-in ends).
     */
    fun noteOn(
        notes: List<Note>,
        track: Int,
        pitch: Int,
        velocity: Float,
        heardBeat: Double,
        engineBeat: Double,
        length: Double,
        grid: QuantizeGrid,
    ): List<Note>? {
        val step = grid.beats ?: 0.0
        // Presses just before beat 0 (end of the count-in) belong to beat 0.
        if (heardBeat < -maxOf(step / 2, LATE_COUNT_IN_TOLERANCE)) return null
        val clamped = maxOf(heardBeat, 0.0)
        var loopStart = floor(clamped / length) * length
        val raw = clamped - loopStart
        val snapped = Quantizer.snap(raw, grid, length)
        if (snapped < raw - length / 2) loopStart += length  // wrapped forward to the next loop
        val absolute = loopStart + snapped

        val id = nextId()
        val defaultLength = grid.beats ?: DEFAULT_LENGTH
        val note = Note(id = id, start = snapped, length = minOf(defaultLength, length), pitch = pitch, velocity = velocity)
        held[track to pitch] = Held(id, clamped)
        if (absolute > engineBeat + Quantizer.EPSILON) skipUntil[id] = absolute

        // Overdubbing the same pitch on the same step replaces the old hit instead of stacking it.
        return notes.filterNot { it.pitch == pitch && abs(it.start - snapped) < Quantizer.EPSILON } + note
    }

    /** Sets the length of the note started by the matching [noteOn]. Null if it was not recorded. */
    fun noteOff(notes: List<Note>, track: Int, pitch: Int, heardBeat: Double, length: Double): List<Note>? {
        val h = held.remove(track to pitch) ?: return null
        val duration = (maxOf(heardBeat, 0.0) - h.pressedAt).coerceIn(MIN_LENGTH, length)
        return notes.map { if (it.id == h.id) it.copy(length = duration) else it }
    }

    /** Engine flags for [note] given how far the engine has rendered. */
    fun flagsFor(note: Note, engineBeat: Double): Int {
        val until = skipUntil[note.id] ?: return 0
        if (engineBeat < until) return EngineParams.NOTE_SKIP_ONCE
        skipUntil.remove(note.id)
        return 0
    }

    fun isHolding(track: Int, pitch: Int): Boolean = held.containsKey(track to pitch)

    fun reset() {
        held.clear()
        skipUntil.clear()
    }

    companion object {
        const val DEFAULT_LENGTH = 0.25
        const val MIN_LENGTH = 1.0 / 32.0
        const val LATE_COUNT_IN_TOLERANCE = 0.125
    }
}
