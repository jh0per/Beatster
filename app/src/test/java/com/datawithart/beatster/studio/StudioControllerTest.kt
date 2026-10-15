package com.datawithart.beatster.studio

import com.datawithart.beatster.audio.WavCodec
import com.datawithart.beatster.data.ProjectStore
import com.datawithart.beatster.engine.EngineParams
import com.datawithart.beatster.engine.Pcm
import com.datawithart.beatster.model.FxSettings
import com.datawithart.beatster.model.QuantizeGrid
import com.datawithart.beatster.model.TrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StudioControllerTest {
    private val root = Files.createTempDirectory("studio").toFile()
    private val store = ProjectStore(root)
    private val engine = FakeEngine()
    private val loadedAssets = mutableListOf<String>()
    private val loader = object : SampleLoader {
        override fun loadAsset(path: String): Pcm {
            loadedAssets += path
            return Pcm(FloatArray(100) { 0.5f }, 1, 44_100)
        }

        override fun loadFile(file: File): Pcm? = if (file.isFile) WavCodec.decode(file.readBytes()) else null
    }

    private fun controller(): StudioController = StudioController(
        engine, store, loader, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, clock = { 42L },
    ).also { it.start() }

    private val drum = 0
    private val synth = 1
    private val audio = 2

    @Test
    fun startCreatesTheDefaultProjectAndLoadsTheKit() {
        val c = controller()
        assertEquals(16, loadedAssets.size)
        assertEquals(16, engine.pads.size)
        assertEquals(TrackType.Drum, engine.trackTypes[drum])
        assertEquals(TrackType.Synth, engine.trackTypes[synth])
        assertEquals(TrackType.Audio, engine.trackTypes[audio])
        assertEquals(8.0, engine.trackLengths[drum]!!, 0.0)
        assertEquals(16.0, engine.trackLengths[synth]!!, 0.0)
        assertTrue(c.state.value.loadingPads.isEmpty())
        assertEquals(1, store.list().size)
    }

    @Test
    fun tappingAPadPlaysItOnTheDrumTrack() {
        val c = controller()
        c.padDown(3)
        c.padUp(3)
        assertEquals(listOf("on 0 3", "off 0 3"), engine.calls.filter { it.startsWith("on") || it.startsWith("off") })
        assertTrue(c.state.value.project.tracks[drum].notes.isEmpty())  // not recording
    }

    @Test
    fun recordingCapturesQuantisedNotesAndUndoRemovesThem() {
        val c = controller()
        c.setQuantize(QuantizeGrid.Sixteenth)
        c.toggleRecord()
        assertTrue(engine.calls.contains("play 4.0"))  // count-in
        engine.status = engine.status.copy(beat = 1.02)
        c.padDown(0)
        engine.status = engine.status.copy(beat = 1.1)
        c.padUp(0)
        engine.status = engine.status.copy(beat = 9.49)  // third loop of the 2-bar clip
        c.padDown(2)
        c.padUp(2)

        val notes = c.state.value.project.tracks[drum].notes
        assertEquals(listOf(1.0 to 0, 1.5 to 2), notes.map { it.start to it.pitch })
        val pushed = engine.patterns[drum]!!
        assertEquals(2, pushed.size)
        // The second press snapped ahead of the play head, so it is flagged to skip once.
        assertEquals(EngineParams.NOTE_SKIP_ONCE, pushed.first { it.first.pitch == 2 }.second)

        c.undo()
        assertTrue(c.state.value.project.tracks[drum].notes.isEmpty())
        assertTrue(engine.patterns[drum]!!.isEmpty())
    }

    @Test
    fun synthKeysRecordNoteLengths() {
        val c = controller()
        c.selectTrack(synth)
        c.toggleCountIn()
        c.toggleRecord()
        assertTrue(engine.calls.contains("play 0.0"))
        engine.status = engine.status.copy(beat = 2.0)
        c.keyDown(60)
        engine.status = engine.status.copy(beat = 3.5)
        c.keyUp(60)
        val note = c.state.value.project.tracks[synth].notes.single()
        assertEquals(60, note.pitch)
        assertEquals(1.5, note.length, 1e-9)
        assertTrue(engine.calls.contains("on 1 60"))
    }

    @Test
    fun clipLengthsAreIndependentAndDuplicateCopiesNotes() {
        val c = controller()
        c.setTrackLength(3, 1)
        assertEquals(4.0, engine.trackLengths[3]!!, 0.0)
        c.toggleRecord()
        engine.status = engine.status.copy(beat = 0.5)
        c.padDown(1); c.padUp(1)
        c.stop()
        c.duplicateClip(drum)
        val t = c.state.value.project.tracks[drum]
        assertEquals(4, t.lengthBars)
        assertEquals(listOf(0.5, 8.5), t.notes.map { it.start })
        assertEquals(16.0, engine.trackLengths[drum]!!, 0.0)
    }

    @Test
    fun audioRecordingAsksForTheMicThenArmsAndSavesTheTake() {
        val c = controller()
        c.selectTrack(audio)
        c.toggleRecord()
        assertEquals(MicAction.RecordAudio, c.state.value.pendingMicAction)
        assertFalse(c.state.value.recordEnabled)

        c.onMicPermission(true)
        assertTrue(engine.inputEnabled)
        assertEquals(audio, engine.armedTrack)
        assertTrue(c.state.value.recordEnabled)

        engine.finishTake(FloatArray(48_000) { if (it % 2 == 0) 0.4f else -0.4f })
        c.tick()
        assertFalse(c.state.value.recordEnabled)
        assertNotNull(c.state.value.clipPeaks[audio])

        c.save()
        val saved = store.load(c.state.value.folder)!!.tracks[audio]
        assertEquals("audio/track3.wav", saved.audioFile)
        assertEquals(16.0, saved.audioBeats!!, 0.0)
        val wav = WavCodec.decode(File(root, "${c.state.value.folder}/audio/track3.wav").readBytes())!!
        assertEquals(48_000, wav.frames)

        // Reopening brings the take back into the engine.
        engine.audioClips.clear()
        val again = controller()
        assertEquals(48_000, engine.audioClips[audio]!!.size)
        assertNotNull(again.state.value.clipPeaks[audio])
    }

    @Test
    fun deniedMicDoesNotArm() {
        val c = controller()
        c.selectTrack(audio)
        c.toggleRecord()
        c.onMicPermission(false)
        assertEquals(-1, engine.armedTrack)
        assertNotNull(c.state.value.message)
    }

    @Test
    fun effectsAndMixReachTheEngine() {
        val c = controller()
        c.setFx(audio, FxSettings(delayOn = true, delayBeats = 0.5f))
        assertEquals(1f, engine.trackParams[audio to EngineParams.FX_DELAY_ON])
        assertEquals(0.5f, engine.trackParams[audio to EngineParams.FX_DELAY_BEATS])
        c.setTrackMix(drum, mute = true)
        assertEquals(1f, engine.trackParams[drum to EngineParams.TRACK_MUTE])
        assertTrue(c.state.value.project.tracks[drum].mute)
    }

    @Test
    fun samplingTrimsAndStoresThePadSoundInTheProject() {
        val c = controller()
        c.startSampling(5)
        assertEquals(MicAction.SamplePad, c.state.value.pendingMicAction)
        c.onMicPermission(true)
        c.startSampling(5)
        assertEquals(5, c.state.value.samplingPad)
        engine.captured = FloatArray(9600) { if (it in 4800 until 7200) 0.5f else 0f }
        c.stopSampling()
        assertNull(c.state.value.samplingPad)
        val pad = c.state.value.project.pads[5]
        assertEquals("pads/pad6_42.wav", pad.source)
        assertEquals(240 + 2400 + 959, engine.pads[5]!!.frames)  // 5 ms lead-in, 20 ms tail
        assertTrue(File(root, "${c.state.value.folder}/pads/pad6_42.wav").isFile)
    }

    @Test
    fun newProjectSavesTheOldOneAndOpenSwitchesBack() {
        val c = controller()
        val first = c.state.value.folder
        c.setBpm(87.0)
        c.newProject()
        assertEquals(100.0, c.state.value.project.bpm, 0.0)
        assertEquals(2, c.state.value.savedProjects.size)
        c.open(first)
        assertEquals(87.0, c.state.value.project.bpm, 0.0)
        assertEquals(87.0, engine.status.tempo, 0.0)
    }

    @Test
    fun addAndDeleteTracks() {
        val c = controller()
        c.addTrack(TrackType.Synth)
        assertEquals(5, c.state.value.project.tracks.size)
        assertEquals(4, c.state.value.selectedTrack)
        assertEquals(TrackType.Synth, engine.trackTypes[4])
        c.deleteTrack(4)
        assertEquals(4, c.state.value.project.tracks.size)
        assertEquals(TrackType.Drum, engine.trackTypes[4])
    }
}
