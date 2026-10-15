#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "EngineTypes.h"

namespace beatster {

/** A decoded sample, already converted to the engine's sample rate. Immutable once shared. */
struct Sample {
    std::vector<float> data;  // interleaved
    int32_t channels = 1;     // 1 or 2
    int64_t frames = 0;

    float at(int64_t frame, int32_t channel) const {
        return data[static_cast<size_t>(frame * channels + (channels == 2 ? channel : 0))];
    }
};

/**
 * Converts interleaved PCM of any rate and channel count into a Sample at targetRate
 * (linear interpolation; more than two channels are folded down to stereo).
 * Returns nullptr when the input is empty or malformed.
 */
std::unique_ptr<Sample> createSample(const float* interleaved, int64_t frames, int32_t channels,
                                     int32_t sourceRate, int32_t targetRate);

/** A clip pattern: what a Drum or Synth track plays every loop. Sorted by startBeat. */
struct Pattern {
    std::vector<NoteEvent> events;
};

/** Recorded (or loaded) mono audio for an Audio track. */
struct AudioClip {
    std::vector<float> data;
    int64_t offsetFrames = 0;   // where beat 0 of the clip starts (input latency compensation)
    int64_t lengthFrames = 0;   // frames that span lengthBeats at the recording tempo
    double lengthBeats = 4.0;
};

/** Microphone capture used for sampling onto a pad. */
struct CaptureBuffer {
    std::vector<float> data;
};

enum RetireKind : int32_t {
    kRetirePattern = 0,
    kRetireAudioClip = 1,
    kRetireSample = 2,
    kRetireCapture = 3,
};

} // namespace beatster
