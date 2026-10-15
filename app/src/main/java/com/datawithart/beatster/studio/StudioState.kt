package com.datawithart.beatster.studio

import com.datawithart.beatster.data.ProjectStore
import com.datawithart.beatster.model.Project
import com.datawithart.beatster.model.Track

data class StudioState(
    val project: Project = Project.default(),
    /** Folder of the open project inside the project store. */
    val folder: String = "",
    val selectedTrack: Int = 0,
    /** The record button is lit: MIDI presses are captured, or an audio take is armed. */
    val recordEnabled: Boolean = false,
    val engineReady: Boolean = false,
    val micEnabled: Boolean = false,
    /** Set when an action needs the microphone and permission has not been granted yet. */
    val pendingMicAction: MicAction? = null,
    val samplingPad: Int? = null,
    val loadingPads: Set<Int> = emptySet(),
    val loopingPads: Set<Int> = emptySet(),
    /** Waveform overview (0..1 peaks) of each Audio track's clip. */
    val clipPeaks: Map<Int, FloatArray> = emptyMap(),
    val canUndo: Boolean = false,
    val dirty: Boolean = false,
    val savedProjects: List<ProjectStore.Entry> = emptyList(),
    val message: String? = null,
) {
    val track: Track? get() = project.tracks.getOrNull(selectedTrack)
}

data class TransportState(
    val playing: Boolean = false,
    val beat: Double = 0.0,
    /** An audio take is armed on this track (-1 none); [rolling] once it is capturing. */
    val recordingTrack: Int = -1,
    val rolling: Boolean = false,
    val inputLevel: Float = 0f,
    val outputLevel: Float = 0f,
) {
    val countingIn: Boolean get() = playing && beat < 0.0
}

enum class MicAction { RecordAudio, SamplePad }
