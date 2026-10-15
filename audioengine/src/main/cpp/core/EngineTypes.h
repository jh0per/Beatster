#pragma once

#include <cstdint>

namespace beatster {

constexpr int kMaxPads = 16;
constexpr int kMaxTracks = 8;
constexpr int kMaxVoices = 64;
constexpr int kMaxBlockFrames = 256;
constexpr int kOutputChannels = 2;

// Values must match com.datawithart.beatster.engine.EngineConstants on the Kotlin side.
enum class TrackType : int32_t {
    Drum = 0,   // notes are pad indices, played from the shared 16-pad kit
    Synth = 1,  // notes are MIDI note numbers, played by the built-in synth or a pad sample
    Audio = 2,  // a recorded audio clip, looped against the transport
};

enum PadParam : int32_t {
    kPadVolume = 0,      // 0..1.5 linear gain
    kPadPan = 1,         // -1..1
    kPadPitch = 2,       // semitones, -24..24
    kPadGate = 3,        // 0 = one-shot (plays to the end), 1 = plays while held
    kPadChokeGroup = 4,  // 0 = none; pads sharing a group cut each other off (open/closed hat)
};

enum TrackParam : int32_t {
    kTrackVolume = 0,  // 0..1.5
    kTrackPan = 1,     // -1..1
    kTrackMute = 2,    // 0/1
    kTrackSolo = 3,    // 0/1

    kSynthWave = 10,       // 0 saw, 1 square, 2 sine, 3 triangle
    kSynthAttack = 11,     // seconds
    kSynthDecay = 12,      // seconds
    kSynthSustain = 13,    // 0..1
    kSynthRelease = 14,    // seconds
    kSynthCutoff = 15,     // Hz
    kSynthResonance = 16,  // 0..1
    kSynthDetune = 17,     // cents between the two oscillators
    kSynthSourcePad = 18,  // -1 = oscillators, 0..15 = play that pad's sample chromatically

    kFxFilterOn = 30,
    kFxFilterMode = 31,       // 0 low-pass, 1 high-pass, 2 band-pass
    kFxFilterCutoff = 32,     // Hz
    kFxFilterResonance = 33,  // 0..1

    kFxDriveOn = 40,
    kFxDriveAmount = 41,  // 0..1

    kFxCrushOn = 45,
    kFxCrushBits = 46,        // 2..16
    kFxCrushDownsample = 47,  // 1..32

    kFxDelayOn = 50,
    kFxDelayBeats = 51,     // delay time in beats (0.25 = 1/16 at 4/4)
    kFxDelayFeedback = 52,  // 0..0.95
    kFxDelayMix = 53,       // 0..1

    kFxReverbOn = 60,
    kFxReverbSize = 61,     // 0..1
    kFxReverbDamping = 62,  // 0..1
    kFxReverbMix = 63,      // 0..1
};

/** One note of a pattern. Beats are quarter notes, relative to the clip start. */
struct NoteEvent {
    double startBeat = 0.0;
    double lengthBeats = 0.25;
    int32_t note = 0;
    float velocity = 1.0f;
    int32_t flags = 0;
};

// The note was just recorded slightly ahead of the play head (quantised forward), and was
// already heard live; skip it the first time the play head reaches it.
constexpr int32_t kNoteSkipOnce = 1;

// Events the audio thread reports back to the control thread.
enum class EngineEventType : int32_t {
    None = 0,
    Retire = 1,                // ptr is a Pattern/AudioClip/Sample the audio thread released
    AudioRecordStarted = 2,    // a = track
    AudioRecordFinished = 3,   // a = track; the new clip is now playing
    CaptureFinished = 4,       // ptr = CaptureBuffer, frames = frames written
};

} // namespace beatster
