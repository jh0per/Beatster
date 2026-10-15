package com.datawithart.beatster.model

import kotlinx.serialization.Serializable

const val PAD_COUNT = 16
const val MAX_TRACKS = 8
const val BEATS_PER_BAR = 4

/** What a track plays. Ordinals match the native TrackType enum. */
@Serializable
enum class TrackType { Drum, Synth, Audio }

/** One recorded or drawn note. Beats are quarter notes from the start of the clip. */
@Serializable
data class Note(
    val id: Long,
    val start: Double,
    val length: Double,
    val pitch: Int,
    val velocity: Float = 1f,
)

/**
 * Where a pad's sound comes from: `asset:samples/kicks/1.wav` for the bundled kit, or a path
 * relative to the project folder (`pads/pad3.wav`) for sounds the user loaded or sampled.
 */
@Serializable
data class PadConfig(
    val index: Int,
    val name: String = "Pad ${index + 1}",
    val source: String? = null,
    val volume: Float = 1f,
    val pan: Float = 0f,
    val pitch: Float = 0f,
    val gate: Boolean = false,
    val chokeGroup: Int = 0,
)

@Serializable
data class SynthSettings(
    val wave: Int = 0,
    val attack: Float = 0.005f,
    val decay: Float = 0.25f,
    val sustain: Float = 0.6f,
    val release: Float = 0.3f,
    val cutoff: Float = 3200f,
    val resonance: Float = 0.25f,
    val detune: Float = 8f,
    val sourcePad: Int = -1,
)

@Serializable
data class FxSettings(
    val filterOn: Boolean = false,
    val filterMode: Int = 0,
    val filterCutoff: Float = 1800f,
    val filterResonance: Float = 0.3f,
    val driveOn: Boolean = false,
    val driveAmount: Float = 0.3f,
    val crushOn: Boolean = false,
    val crushBits: Float = 8f,
    val crushDownsample: Float = 4f,
    val delayOn: Boolean = false,
    val delayBeats: Float = 0.75f,
    val delayFeedback: Float = 0.35f,
    val delayMix: Float = 0.3f,
    val reverbOn: Boolean = false,
    val reverbSize: Float = 0.6f,
    val reverbDamping: Float = 0.4f,
    val reverbMix: Float = 0.3f,
)

@Serializable
data class Track(
    val index: Int,
    val name: String,
    val type: TrackType,
    val lengthBars: Int = 1,
    val volume: Float = 0.8f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val notes: List<Note> = emptyList(),
    /** Recorded audio for Audio tracks, relative to the project folder. */
    val audioFile: String? = null,
    /** How many beats the recorded audio spans (the clip length when it was recorded). */
    val audioBeats: Double? = null,
    val synth: SynthSettings = SynthSettings(),
    val fx: FxSettings = FxSettings(),
) {
    val lengthBeats: Double get() = (lengthBars * BEATS_PER_BAR).toDouble()
}

@Serializable
data class Project(
    val name: String = "Untitled",
    val bpm: Double = 100.0,
    val metronome: Boolean = true,
    val countIn: Boolean = true,
    val quantize: QuantizeGrid = QuantizeGrid.Sixteenth,
    val pads: List<PadConfig> = List(PAD_COUNT) { PadConfig(it) },
    val tracks: List<Track> = emptyList(),
    val version: Int = 1,
) {
    companion object {
        /** A fresh project: the bundled kit on the pads, and a track of each kind. */
        fun default(name: String = "Untitled"): Project = Project(
            name = name,
            pads = DefaultKit.pads,
            tracks = listOf(
                Track(0, "Beat", TrackType.Drum, lengthBars = 2),
                Track(1, "Melody", TrackType.Synth, lengthBars = 4),
                Track(2, "Voice", TrackType.Audio, lengthBars = 4, fx = FxSettings(reverbOn = true, reverbMix = 0.25f)),
                Track(3, "Perc", TrackType.Drum, lengthBars = 1),
            ),
        )
    }
}

/** Note values a recording snaps to. */
@Serializable
enum class QuantizeGrid(val beats: Double?, val label: String) {
    Off(null, "Off"),
    Quarter(1.0, "1/4"),
    Eighth(0.5, "1/8"),
    EighthTriplet(1.0 / 3.0, "1/8T"),
    Sixteenth(0.25, "1/16"),
    SixteenthTriplet(1.0 / 6.0, "1/16T"),
    ThirtySecond(0.125, "1/32"),
}

object DefaultKit {
    private data class Slot(val path: String, val name: String, val choke: Int = 0)

    private val slots = listOf(
        Slot("kicks/1.wav", "Kick"),
        Slot("kicks/2.wav", "Kick 2"),
        Slot("snare/1.wav", "Snare"),
        Slot("clap/1.wav", "Clap"),
        Slot("hats/1.wav", "Hat", choke = 1),
        Slot("hats/2.wav", "Hat 2", choke = 1),
        Slot("hats/5.wav", "Open Hat", choke = 1),
        Slot("crash/1.wav", "Crash"),
        Slot("808/1.wav", "808", choke = 2),
        Slot("808/2.wav", "808 2", choke = 2),
        Slot("808/3.wav", "808 3", choke = 2),
        Slot("kicks/3.wav", "Kick 3"),
        Slot("hats/3.wav", "Hat 3", choke = 1),
        Slot("hats/4.wav", "Tick"),
        Slot("hats/7.wav", "Half Hat", choke = 1),
        Slot("hats/6.wav", "Long Hat", choke = 1),
    )

    val pads: List<PadConfig> = slots.mapIndexed { i, s ->
        PadConfig(index = i, name = s.name, source = "asset:samples/${s.path}", chokeGroup = s.choke)
    }
}
