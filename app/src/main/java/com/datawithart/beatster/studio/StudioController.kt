package com.datawithart.beatster.studio

import com.datawithart.beatster.audio.WavCodec
import com.datawithart.beatster.data.ProjectStore
import com.datawithart.beatster.engine.AudioEngine
import com.datawithart.beatster.engine.EngineEvent
import com.datawithart.beatster.engine.EngineParams as P
import com.datawithart.beatster.engine.Pcm
import com.datawithart.beatster.model.BEATS_PER_BAR
import com.datawithart.beatster.model.FxSettings
import com.datawithart.beatster.model.MAX_TRACKS
import com.datawithart.beatster.model.Note
import com.datawithart.beatster.model.PAD_COUNT
import com.datawithart.beatster.model.PadConfig
import com.datawithart.beatster.model.Project
import com.datawithart.beatster.model.QuantizeGrid
import com.datawithart.beatster.model.SynthSettings
import com.datawithart.beatster.model.Track
import com.datawithart.beatster.model.TrackType
import com.datawithart.beatster.sequencer.LiveRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/** Decodes the sounds a project references. Android implementation wraps AudioDecoder. */
interface SampleLoader {
    fun loadAsset(path: String): Pcm?
    fun loadFile(file: File): Pcm?
}

/**
 * The studio's logic, free of Android: owns the open [Project], keeps the audio engine in sync
 * with it, and turns user actions (pad taps, keys, transport, recording) into project edits.
 * The ViewModel forwards UI intents here and calls [tick] on every frame while visible.
 */
class StudioController(
    private val engine: AudioEngine,
    private val store: ProjectStore,
    private val loader: SampleLoader,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private val _transport = MutableStateFlow(TransportState())
    val transport: StateFlow<TransportState> = _transport.asStateFlow()

    private val ids = AtomicLong(1)
    private val recorder = LiveRecorder { ids.getAndIncrement() }
    private var undoTracks: List<Track>? = null
    /** Audio tracks whose clip changed since the last save. */
    private val unsavedAudio = mutableSetOf<Int>()

    private val project: Project get() = _state.value.project

    // ---------------------------------------------------------------- lifecycle

    /** Opens the most recent project, or creates the default one. */
    fun start() {
        _state.update { it.copy(engineReady = engine.isAvailable) }
        val latest = store.list().firstOrNull()
        val loaded = latest?.let { store.load(it.folder) }
        if (latest != null && loaded != null) {
            openLoaded(latest.folder, loaded)
        } else {
            val folder = store.newFolder("Beat 1")
            val fresh = Project.default("Beat 1")
            store.save(folder, fresh)
            openLoaded(folder, fresh)
        }
    }

    fun tick() {
        val status = engine.status()
        _transport.value = TransportState(
            playing = status.playing,
            beat = status.beat,
            recordingTrack = status.recordingTrack,
            rolling = status.recordingRolling,
            inputLevel = status.inputPeak,
            outputLevel = status.outputPeak,
        )
        for (event in engine.pollEvents()) {
            when (event) {
                is EngineEvent.AudioRecordStarted -> Unit
                is EngineEvent.AudioRecordFinished -> onAudioTakeFinished(event.track)
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    // ---------------------------------------------------------------- playing

    /** Pads always play the kit on a drum track: the selected one, or the first drum track. */
    fun drumTrackForPads(): Int {
        val selected = _state.value.track
        if (selected?.type == TrackType.Drum) return selected.index
        return project.tracks.firstOrNull { it.type == TrackType.Drum }?.index ?: 0
    }

    fun padDown(pad: Int, velocity: Float = 1f) = pressNote(drumTrackForPads(), pad, velocity)

    fun padUp(pad: Int) = releaseNote(drumTrackForPads(), pad)

    fun keyDown(pitch: Int, velocity: Float = 0.9f) {
        val track = _state.value.track ?: return
        if (track.type == TrackType.Synth) pressNote(track.index, pitch, velocity)
    }

    fun keyUp(pitch: Int) {
        val track = _state.value.track ?: return
        if (track.type == TrackType.Synth) releaseNote(track.index, pitch)
    }

    private fun pressNote(trackIndex: Int, pitch: Int, velocity: Float) {
        engine.noteOn(trackIndex, pitch, velocity)
        val track = project.tracks.getOrNull(trackIndex) ?: return
        if (!isMidiRecording(track)) return
        val (heard, rendered) = playHead()
        val updated = recorder.noteOn(
            track.notes, trackIndex, pitch, velocity, heard, rendered, track.lengthBeats, project.quantize,
        ) ?: return
        setNotes(trackIndex, updated, rendered)
    }

    private fun releaseNote(trackIndex: Int, pitch: Int) {
        engine.noteOff(trackIndex, pitch)
        val track = project.tracks.getOrNull(trackIndex) ?: return
        if (!recorder.isHolding(trackIndex, pitch)) return
        val (heard, rendered) = playHead()
        val updated = recorder.noteOff(track.notes, trackIndex, pitch, heard, track.lengthBeats) ?: return
        setNotes(trackIndex, updated, rendered)
    }

    private fun isMidiRecording(track: Track): Boolean =
        _state.value.recordEnabled && track.type != TrackType.Audio && engine.status().playing

    /** (beat the listener hears now, beat the engine has rendered up to). */
    private fun playHead(): Pair<Double, Double> {
        val s = engine.status()
        val latencyBeats = max(s.outputLatencyMs, 0.0) / 1000.0 * s.tempo / 60.0
        return (s.beat - latencyBeats) to s.beat
    }

    fun togglePadLoop(pad: Int) {
        val track = drumTrackForPads()
        val looping = pad in _state.value.loopingPads
        engine.padLoop(track, pad, !looping)
        _state.update { it.copy(loopingPads = if (looping) it.loopingPads - pad else it.loopingPads + pad) }
    }

    // ---------------------------------------------------------------- transport

    fun togglePlay() {
        if (engine.status().playing) stop() else engine.play(0.0)
    }

    fun stop() {
        engine.stop()
        recorder.reset()
        _state.update { it.copy(recordEnabled = false) }
    }

    /**
     * Record button. On a drum or synth track it toggles MIDI recording (overdub); on an audio
     * track it arms a take that starts at the clip's next loop start and lasts one clip.
     * Starting from stop plays a one-bar count-in when enabled.
     */
    fun toggleRecord() {
        val s = _state.value
        val track = s.track ?: return
        if (s.recordEnabled) {
            if (track.type == TrackType.Audio) engine.cancelAudioRecord()
            recorder.reset()
            _state.update { it.copy(recordEnabled = false) }
            return
        }
        if (track.type == TrackType.Audio) {
            if (!s.micEnabled) {
                _state.update { it.copy(pendingMicAction = MicAction.RecordAudio) }
                return
            }
            if (!engine.armAudioRecord(track.index, track.lengthBeats)) {
                _state.update { it.copy(message = "Could not start recording: microphone unavailable") }
                return
            }
        } else {
            undoTracks = project.tracks
        }
        _state.update { it.copy(recordEnabled = true, canUndo = it.canUndo || track.type != TrackType.Audio) }
        if (!engine.status().playing) engine.play(if (project.countIn) BEATS_PER_BAR.toDouble() else 0.0)
    }

    fun setBpm(bpm: Double) {
        val clamped = bpm.coerceIn(40.0, 240.0)
        engine.setTempo(clamped)
        edit { it.copy(bpm = clamped) }
    }

    fun toggleMetronome() {
        val on = !project.metronome
        engine.setMetronome(on, METRONOME_VOLUME)
        edit { it.copy(metronome = on) }
    }

    fun toggleCountIn() = edit { it.copy(countIn = !it.countIn) }

    fun setQuantize(grid: QuantizeGrid) = edit { it.copy(quantize = grid) }

    // ---------------------------------------------------------------- microphone

    /** Called with the result of the RECORD_AUDIO permission request. */
    fun onMicPermission(granted: Boolean) {
        val pending = _state.value.pendingMicAction
        _state.update { it.copy(pendingMicAction = null) }
        if (!granted) {
            _state.update { it.copy(message = "Microphone permission is needed to record") }
            return
        }
        val enabled = _state.value.micEnabled || engine.enableInput(true)
        _state.update { it.copy(micEnabled = enabled) }
        if (!enabled) {
            _state.update { it.copy(message = "Could not open the microphone") }
            return
        }
        when (pending) {
            MicAction.RecordAudio -> toggleRecord()
            // Sampling needs the finger still on the button; ask the user to press it again.
            MicAction.SamplePad -> _state.update { it.copy(message = "Microphone ready: hold the sample button to record") }
            null -> Unit
        }
    }

    // ---------------------------------------------------------------- tracks

    fun selectTrack(index: Int) {
        if (index == _state.value.selectedTrack) return
        if (_state.value.recordEnabled) toggleRecord()
        _state.update { it.copy(selectedTrack = index) }
    }

    fun addTrack(type: TrackType) {
        if (project.tracks.size >= MAX_TRACKS) return
        val index = (0 until MAX_TRACKS).first { i -> project.tracks.none { it.index == i } }
        val name = when (type) {
            TrackType.Drum -> "Drums ${index + 1}"
            TrackType.Synth -> "Synth ${index + 1}"
            TrackType.Audio -> "Audio ${index + 1}"
        }
        val track = Track(index, name, type, lengthBars = if (type == TrackType.Drum) 1 else 4)
        edit { p -> p.copy(tracks = (p.tracks + track).sortedBy { it.index }) }
        pushTrack(track)
        _state.update { it.copy(selectedTrack = index) }
    }

    fun deleteTrack(index: Int) {
        val track = project.tracks.firstOrNull { it.index == index } ?: return
        if (_state.value.recordEnabled) stop()
        resetEngineTrack(index)
        edit { p -> p.copy(tracks = p.tracks.filterNot { it.index == index }) }
        unsavedAudio.remove(index)
        track.audioFile?.let { File(store.dir(_state.value.folder), it).delete() }
        _state.update { s ->
            s.copy(
                selectedTrack = s.project.tracks.firstOrNull()?.index ?: 0,
                clipPeaks = s.clipPeaks - index,
            )
        }
    }

    fun renameTrack(index: Int, name: String) = updateTrack(index) { it.copy(name = name.take(24)) }

    fun setTrackLength(index: Int, bars: Int) {
        val t = updateTrack(index) { it.copy(lengthBars = bars.coerceIn(1, 16)) } ?: return
        engine.setTrackLength(index, t.lengthBeats)
    }

    /** Doubles the clip, copying its notes into the new half (the classic "x2"). */
    fun duplicateClip(index: Int) {
        val track = project.tracks.firstOrNull { it.index == index } ?: return
        if (track.type == TrackType.Audio || track.lengthBars * 2 > 16) return
        val shift = track.lengthBeats
        val copies = track.notes.filter { it.start < shift }.map { it.copy(id = ids.getAndIncrement(), start = it.start + shift) }
        undoTracks = project.tracks
        val t = updateTrack(index) { it.copy(lengthBars = it.lengthBars * 2, notes = it.notes + copies) } ?: return
        engine.setTrackLength(index, t.lengthBeats)
        engine.setPattern(index, t.notes)
        _state.update { it.copy(canUndo = true) }
    }

    fun clearClip(index: Int) {
        val track = project.tracks.firstOrNull { it.index == index } ?: return
        undoTracks = project.tracks
        if (track.type == TrackType.Audio) {
            engine.clearAudioClip(index)
            unsavedAudio.add(index)
            updateTrack(index) { it.copy(audioBeats = null) }
            _state.update { it.copy(clipPeaks = it.clipPeaks - index) }
        } else {
            updateTrack(index) { it.copy(notes = emptyList()) }
            engine.setPattern(index, emptyList())
            _state.update { it.copy(canUndo = true) }
        }
    }

    /** Restores the notes from before the last take, clear or duplicate. */
    fun undo() {
        val previous = undoTracks ?: return
        undoTracks = null
        recorder.reset()
        edit { p ->
            p.copy(tracks = p.tracks.map { t ->
                val old = previous.firstOrNull { it.index == t.index }
                if (old != null && t.type != TrackType.Audio) t.copy(notes = old.notes, lengthBars = old.lengthBars) else t
            })
        }
        project.tracks.filter { it.type != TrackType.Audio }.forEach {
            engine.setTrackLength(it.index, it.lengthBeats)
            engine.setPattern(it.index, it.notes)
        }
        _state.update { it.copy(canUndo = false) }
    }

    fun setTrackMix(index: Int, volume: Float? = null, pan: Float? = null, mute: Boolean? = null, solo: Boolean? = null) {
        val t = updateTrack(index) {
            it.copy(
                volume = volume ?: it.volume,
                pan = pan ?: it.pan,
                mute = mute ?: it.mute,
                solo = solo ?: it.solo,
            )
        } ?: return
        engine.setTrackParam(index, P.TRACK_VOLUME, t.volume)
        engine.setTrackParam(index, P.TRACK_PAN, t.pan)
        engine.setTrackParam(index, P.TRACK_MUTE, if (t.mute) 1f else 0f)
        engine.setTrackParam(index, P.TRACK_SOLO, if (t.solo) 1f else 0f)
    }

    fun setSynth(index: Int, synth: SynthSettings) {
        updateTrack(index) { it.copy(synth = synth) } ?: return
        pushSynth(index, synth)
    }

    fun setFx(index: Int, fx: FxSettings) {
        updateTrack(index) { it.copy(fx = fx) } ?: return
        pushFx(index, fx)
    }

    // ---------------------------------------------------------------- pads

    fun setPad(config: PadConfig) {
        if (config.index !in 0 until PAD_COUNT) return
        edit { p -> p.copy(pads = p.pads.map { if (it.index == config.index) config else it }) }
        pushPadParams(config)
    }

    /** Puts decoded audio on a pad, keeping a copy inside the project so it survives the source. */
    fun assignPadSample(pad: Int, pcm: Pcm, name: String) {
        if (pad !in 0 until PAD_COUNT) return
        _state.update { it.copy(loadingPads = it.loadingPads + pad) }
        val folder = _state.value.folder
        scope.launch {
            val relative = withContext(io) {
                val rel = "pads/pad${pad + 1}_${clock()}.wav"
                val file = File(store.dir(folder), rel)
                file.parentFile?.mkdirs()
                file.writeBytes(WavCodec.encode16(pcm))
                engine.setPadSample(pad, pcm)
                rel
            }
            val current = project.pads[pad]
            setPad(current.copy(name = name.take(16), source = relative))
            _state.update { it.copy(loadingPads = it.loadingPads - pad) }
        }
    }

    fun startSampling(pad: Int) {
        if (!_state.value.micEnabled) {
            _state.update { it.copy(pendingMicAction = MicAction.SamplePad) }
            return
        }
        if (engine.startCapture(MAX_SAMPLE_SECONDS)) _state.update { it.copy(samplingPad = pad) }
    }

    fun stopSampling() {
        val pad = _state.value.samplingPad ?: return
        _state.update { it.copy(samplingPad = null) }
        scope.launch {
            val captured = withContext(io) { engine.stopCapture() }
            val trimmed = captured?.let { trimSilence(it, sampleRate = engine.sampleRate) }
            if (trimmed == null || trimmed.isEmpty()) {
                _state.update { it.copy(message = "Nothing was recorded") }
                return@launch
            }
            assignPadSample(pad, Pcm(trimmed, 1, engine.sampleRate), "Sample ${pad + 1}")
        }
    }

    // ---------------------------------------------------------------- projects

    fun refreshProjects() = _state.update { it.copy(savedProjects = store.list()) }

    /** Saves the project file and any recorded audio that changed. */
    fun save() {
        val s = _state.value
        val dir = store.dir(s.folder)
        var p = s.project
        for (index in unsavedAudio.toList()) {
            val track = p.tracks.firstOrNull { it.index == index } ?: continue
            val clip = engine.getAudioClip(index)
            val rel = "audio/track${index + 1}.wav"
            val file = File(dir, rel)
            p = if (clip != null && clip.isNotEmpty()) {
                file.parentFile?.mkdirs()
                file.writeBytes(WavCodec.encode16(Pcm(clip, 1, engine.sampleRate)))
                p.withTrack(track.copy(audioFile = rel))
            } else {
                file.delete()
                p.withTrack(track.copy(audioFile = null, audioBeats = null))
            }
        }
        unsavedAudio.clear()
        store.save(s.folder, p)
        _state.update { it.copy(project = p, dirty = false, savedProjects = store.list()) }
    }

    fun saveAs(name: String) {
        val clean = name.trim().ifEmpty { return }
        val newFolder = store.copy(_state.value.folder, clean)
        _state.update { it.copy(folder = newFolder, project = it.project.copy(name = clean)) }
        unsavedAudio.addAll(project.tracks.filter { it.type == TrackType.Audio }.map { it.index })
        save()
    }

    fun newProject() {
        if (_state.value.dirty) save()
        val name = "Beat ${store.list().size + 1}"
        val folder = store.newFolder(name)
        val fresh = Project.default(name)
        store.save(folder, fresh)
        openLoaded(folder, fresh)
    }

    fun open(folder: String) {
        if (folder == _state.value.folder) return
        val loaded = store.load(folder) ?: run {
            _state.update { it.copy(message = "Could not open that project") }
            return
        }
        if (_state.value.dirty) save()
        openLoaded(folder, loaded)
    }

    // ---------------------------------------------------------------- internals

    private fun openLoaded(folder: String, loaded: Project) {
        engine.stop()
        recorder.reset()
        undoTracks = null
        unsavedAudio.clear()
        ids.set((loaded.tracks.flatMap { it.notes }.maxOfOrNull { it.id } ?: 0L) + 1)
        val pads = (0 until PAD_COUNT).map { i -> loaded.pads.firstOrNull { it.index == i } ?: PadConfig(i) }
        val project = loaded.copy(pads = pads)
        _state.update {
            it.copy(
                project = project,
                folder = folder,
                selectedTrack = project.tracks.firstOrNull()?.index ?: 0,
                recordEnabled = false,
                loopingPads = emptySet(),
                clipPeaks = emptyMap(),
                canUndo = false,
                dirty = false,
                savedProjects = store.list(),
            )
        }
        pushProject(project, folder)
    }

    private fun pushProject(project: Project, folder: String) {
        engine.setTempo(project.bpm)
        engine.setMetronome(project.metronome, METRONOME_VOLUME)
        for (i in 0 until MAX_TRACKS) {
            val track = project.tracks.firstOrNull { it.index == i }
            if (track == null) resetEngineTrack(i) else pushTrack(track)
        }
        project.pads.forEach(::pushPadParams)
        val dir = store.dir(folder)
        _state.update { it.copy(loadingPads = project.pads.filter { p -> p.source != null }.map { p -> p.index }.toSet()) }
        scope.launch {
            withContext(io) {
                for (pad in project.pads) {
                    val pcm = pad.source?.let { loadSource(it, dir) }
                    if (pcm != null) engine.setPadSample(pad.index, pcm) else engine.clearPad(pad.index)
                    _state.update { it.copy(loadingPads = it.loadingPads - pad.index) }
                }
                for (track in project.tracks.filter { it.type == TrackType.Audio && it.audioFile != null }) {
                    val pcm = loader.loadFile(File(dir, track.audioFile!!)) ?: continue
                    val mono = if (pcm.channels == 1) pcm else Pcm(downmix(pcm), 1, pcm.sampleRate)
                    if (engine.setAudioClip(track.index, mono, track.audioBeats ?: track.lengthBeats)) {
                        val peaks = peaks(mono.samples)
                        _state.update { it.copy(clipPeaks = it.clipPeaks + (track.index to peaks)) }
                    }
                }
            }
        }
    }

    private fun loadSource(source: String, dir: File): Pcm? =
        if (source.startsWith(ASSET_PREFIX)) loader.loadAsset(source.removePrefix(ASSET_PREFIX))
        else loader.loadFile(File(dir, source))

    private fun pushTrack(t: Track) {
        engine.setTrackType(t.index, t.type)
        engine.setTrackLength(t.index, t.lengthBeats)
        engine.setTrackParam(t.index, P.TRACK_VOLUME, t.volume)
        engine.setTrackParam(t.index, P.TRACK_PAN, t.pan)
        engine.setTrackParam(t.index, P.TRACK_MUTE, if (t.mute) 1f else 0f)
        engine.setTrackParam(t.index, P.TRACK_SOLO, if (t.solo) 1f else 0f)
        pushSynth(t.index, t.synth)
        pushFx(t.index, t.fx)
        engine.setPattern(t.index, if (t.type == TrackType.Audio) emptyList() else t.notes)
        if (t.type != TrackType.Audio || t.audioFile == null) engine.clearAudioClip(t.index)
    }

    private fun resetEngineTrack(index: Int) {
        engine.setTrackType(index, TrackType.Drum)
        engine.setPattern(index, emptyList())
        engine.clearAudioClip(index)
        pushFx(index, FxSettings())
        engine.setTrackParam(index, P.TRACK_SOLO, 0f)
        engine.setTrackParam(index, P.TRACK_MUTE, 0f)
    }

    private fun pushSynth(index: Int, s: SynthSettings) {
        engine.setTrackParam(index, P.SYNTH_WAVE, s.wave.toFloat())
        engine.setTrackParam(index, P.SYNTH_ATTACK, s.attack)
        engine.setTrackParam(index, P.SYNTH_DECAY, s.decay)
        engine.setTrackParam(index, P.SYNTH_SUSTAIN, s.sustain)
        engine.setTrackParam(index, P.SYNTH_RELEASE, s.release)
        engine.setTrackParam(index, P.SYNTH_CUTOFF, s.cutoff)
        engine.setTrackParam(index, P.SYNTH_RESONANCE, s.resonance)
        engine.setTrackParam(index, P.SYNTH_DETUNE, s.detune)
        engine.setTrackParam(index, P.SYNTH_SOURCE_PAD, s.sourcePad.toFloat())
    }

    private fun pushFx(index: Int, fx: FxSettings) {
        fun flag(b: Boolean) = if (b) 1f else 0f
        // Parameters first, then the on switches, so an effect never starts with stale settings.
        engine.setTrackParam(index, P.FX_FILTER_MODE, fx.filterMode.toFloat())
        engine.setTrackParam(index, P.FX_FILTER_CUTOFF, fx.filterCutoff)
        engine.setTrackParam(index, P.FX_FILTER_RESONANCE, fx.filterResonance)
        engine.setTrackParam(index, P.FX_DRIVE_AMOUNT, fx.driveAmount)
        engine.setTrackParam(index, P.FX_CRUSH_BITS, fx.crushBits)
        engine.setTrackParam(index, P.FX_CRUSH_DOWNSAMPLE, fx.crushDownsample)
        engine.setTrackParam(index, P.FX_DELAY_BEATS, fx.delayBeats)
        engine.setTrackParam(index, P.FX_DELAY_FEEDBACK, fx.delayFeedback)
        engine.setTrackParam(index, P.FX_DELAY_MIX, fx.delayMix)
        engine.setTrackParam(index, P.FX_REVERB_SIZE, fx.reverbSize)
        engine.setTrackParam(index, P.FX_REVERB_DAMPING, fx.reverbDamping)
        engine.setTrackParam(index, P.FX_REVERB_MIX, fx.reverbMix)
        engine.setTrackParam(index, P.FX_FILTER_ON, flag(fx.filterOn))
        engine.setTrackParam(index, P.FX_DRIVE_ON, flag(fx.driveOn))
        engine.setTrackParam(index, P.FX_CRUSH_ON, flag(fx.crushOn))
        engine.setTrackParam(index, P.FX_DELAY_ON, flag(fx.delayOn))
        engine.setTrackParam(index, P.FX_REVERB_ON, flag(fx.reverbOn))
    }

    private fun pushPadParams(pad: PadConfig) {
        engine.setPadParam(pad.index, P.PAD_VOLUME, pad.volume)
        engine.setPadParam(pad.index, P.PAD_PAN, pad.pan)
        engine.setPadParam(pad.index, P.PAD_PITCH, pad.pitch)
        engine.setPadParam(pad.index, P.PAD_GATE, if (pad.gate) 1f else 0f)
        engine.setPadParam(pad.index, P.PAD_CHOKE_GROUP, pad.chokeGroup.toFloat())
    }

    private fun onAudioTakeFinished(track: Int) {
        val clip = engine.getAudioClip(track)
        unsavedAudio.add(track)
        val lengthBeats = project.tracks.firstOrNull { it.index == track }?.lengthBeats
        updateTrack(track) { it.copy(audioBeats = lengthBeats) }
        _state.update { s ->
            s.copy(
                recordEnabled = if (s.selectedTrack == track) false else s.recordEnabled,
                clipPeaks = if (clip != null) s.clipPeaks + (track to peaks(clip)) else s.clipPeaks,
            )
        }
    }

    private fun setNotes(trackIndex: Int, notes: List<Note>, engineBeat: Double) {
        updateTrack(trackIndex) { it.copy(notes = notes) }
        engine.setPattern(trackIndex, notes) { recorder.flagsFor(it, engineBeat) }
    }

    private fun updateTrack(index: Int, transform: (Track) -> Track): Track? {
        var result: Track? = null
        edit { p ->
            p.copy(tracks = p.tracks.map { t -> if (t.index == index) transform(t).also { result = it } else t })
        }
        return result
    }

    private fun edit(transform: (Project) -> Project) {
        _state.update { it.copy(project = transform(it.project), dirty = true) }
    }

    private fun Project.withTrack(track: Track) = copy(tracks = tracks.map { if (it.index == track.index) track else it })

    companion object {
        const val ASSET_PREFIX = "asset:"
        const val METRONOME_VOLUME = 0.6f
        const val MAX_SAMPLE_SECONDS = 30f
        private const val PEAK_BUCKETS = 160

        fun peaks(samples: FloatArray, buckets: Int = PEAK_BUCKETS): FloatArray {
            if (samples.isEmpty()) return FloatArray(0)
            val out = FloatArray(buckets)
            val per = max(1, samples.size / buckets)
            for (b in 0 until buckets) {
                var m = 0f
                val from = b * per
                val to = minOf(samples.size, from + per)
                for (i in from until to) m = max(m, abs(samples[i]))
                out[b] = m.coerceAtMost(1f)
            }
            return out
        }

        /** Cuts leading/trailing near-silence from a mic take, keeping a few ms of lead-in. */
        fun trimSilence(samples: FloatArray, threshold: Float = 0.02f, sampleRate: Int = 48_000): FloatArray {
            val first = samples.indexOfFirst { abs(it) > threshold }
            if (first < 0) return FloatArray(0)
            val last = samples.indexOfLast { abs(it) > threshold }
            val pad = sampleRate / 200  // 5 ms
            val from = max(0, first - pad)
            val to = minOf(samples.size, last + pad * 4)
            return samples.copyOfRange(from, to)
        }

        fun downmix(pcm: Pcm): FloatArray = FloatArray(pcm.frames) { f ->
            var sum = 0f
            for (c in 0 until pcm.channels) sum += pcm.samples[f * pcm.channels + c]
            sum / pcm.channels
        }
    }
}
