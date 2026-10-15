package com.datawithart.beatster.sequencer

import com.datawithart.beatster.engine.EngineParams
import com.datawithart.beatster.model.QuantizeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRecorderTest {
    private var id = 1L
    private val recorder = LiveRecorder { id++ }

    @Test
    fun snapRoundsToTheNearestStepAndWrapsAtTheLoopEnd() {
        assertEquals(1.0, Quantizer.snap(1.1, QuantizeGrid.Sixteenth, 4.0), 1e-9)
        assertEquals(1.25, Quantizer.snap(1.2, QuantizeGrid.Sixteenth, 4.0), 1e-9)
        assertEquals(0.0, Quantizer.snap(3.95, QuantizeGrid.Sixteenth, 4.0), 1e-9)
        assertEquals(2.0 / 3.0, Quantizer.snap(0.7, QuantizeGrid.EighthTriplet, 4.0), 1e-9)
        assertEquals(1.13, Quantizer.snap(1.13, QuantizeGrid.Off, 4.0), 1e-9)
    }

    @Test
    fun pressIsPlacedRelativeToTheClipLoop() {
        // Clip of 1 bar, transport in its third loop: beat 9.1 is 1.1 into the clip.
        val notes = recorder.noteOn(emptyList(), 0, 36, 0.8f, 9.1, 9.2, 4.0, QuantizeGrid.Sixteenth)!!
        assertEquals(1, notes.size)
        assertEquals(1.0, notes[0].start, 1e-9)
        assertEquals(36, notes[0].pitch)
        assertEquals(0.8f, notes[0].velocity)
        // Already behind the play head: plays normally next loop.
        assertEquals(0, recorder.flagsFor(notes[0], 9.2))
    }

    @Test
    fun pressQuantisedAheadOfThePlayHeadIsSkippedOnce() {
        val notes = recorder.noteOn(emptyList(), 0, 36, 1f, 7.9, 7.92, 4.0, QuantizeGrid.Quarter)!!
        assertEquals(0.0, notes[0].start, 1e-9)  // wrapped to the next loop's downbeat
        assertEquals(EngineParams.NOTE_SKIP_ONCE, recorder.flagsFor(notes[0], 7.95))
        // Once the engine passed beat 8 the flag is gone for good.
        assertEquals(0, recorder.flagsFor(notes[0], 8.01))
        assertEquals(0, recorder.flagsFor(notes[0], 7.95))
    }

    @Test
    fun releaseSetsTheNoteLength() {
        var notes = recorder.noteOn(emptyList(), 1, 60, 1f, 2.0, 2.0, 16.0, QuantizeGrid.Sixteenth)!!
        notes = recorder.noteOff(notes, 1, 60, 3.5, 16.0)!!
        assertEquals(1.5, notes[0].length, 1e-9)
        assertNull(recorder.noteOff(notes, 1, 60, 4.0, 16.0))
    }

    @Test
    fun overdubOnTheSameStepReplacesInsteadOfStacking() {
        var notes = recorder.noteOn(emptyList(), 0, 38, 1f, 1.02, 1.1, 4.0, QuantizeGrid.Sixteenth)!!
        notes = recorder.noteOn(notes, 0, 38, 0.5f, 5.01, 5.1, 4.0, QuantizeGrid.Sixteenth)!!
        assertEquals(1, notes.size)
        assertEquals(0.5f, notes[0].velocity)
        notes = recorder.noteOn(notes, 0, 36, 1f, 5.01, 5.1, 4.0, QuantizeGrid.Sixteenth)!!
        assertEquals(2, notes.size)
    }

    @Test
    fun pressAtTheEndOfTheCountInLandsOnBeatZero() {
        val notes = recorder.noteOn(emptyList(), 0, 36, 1f, -0.05, -0.03, 4.0, QuantizeGrid.Sixteenth)!!
        assertEquals(0.0, notes[0].start, 1e-9)
        assertNull(recorder.noteOn(emptyList(), 0, 36, 1f, -1.5, -1.4, 4.0, QuantizeGrid.Sixteenth))
    }
}
