#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

#include "Engine.h"

namespace beatster {

/**
 * Control-side facade over Engine. Owns allocation and freeing of everything handed to the
 * audio thread, mirrors the little state the control side needs (current audio clips,
 * tempo), and turns raw audio-thread events into user-facing ones.
 *
 * All methods are thread-safe with respect to each other (one internal mutex) and never
 * block the audio thread.
 */
class EngineController {
public:
    struct UserEvent {
        EngineEventType type;
        int32_t track;
    };

    explicit EngineController(int32_t sampleRate);
    ~EngineController();

    Engine& engine() { return *mEngine; }
    int32_t sampleRate() const { return mEngine->sampleRate(); }

    // pads
    bool setPadSample(int32_t pad, const float* interleaved, int64_t frames, int32_t channels, int32_t sourceRate);
    void clearPad(int32_t pad);
    void setPadParam(int32_t pad, int32_t param, float value);

    // playing
    void noteOn(int32_t track, int32_t note, float velocity);
    void noteOff(int32_t track, int32_t note);
    void padLoop(int32_t track, int32_t pad, bool on);
    void allNotesOff();

    // tracks
    void setTrackType(int32_t track, int32_t type);
    void setTrackParam(int32_t track, int32_t param, float value);
    void setTrackLength(int32_t track, double beats);
    void setPattern(int32_t track, std::vector<NoteEvent> events);
    bool setAudioClip(int32_t track, const float* mono, int64_t frames, int32_t sourceRate, double lengthBeats);
    void clearAudioClip(int32_t track);
    /** The playable part of a track's audio clip (latency offset removed), at the engine rate. */
    std::vector<float> copyAudioClip(int32_t track, double* lengthBeats);

    // transport
    void play(double countInBeats);
    void stop();
    void setTempo(double bpm);
    double tempo() const { return mTempo; }
    void setMetronome(bool on, float volume);

    // recording
    void setInputLatencyFrames(int64_t frames);
    bool armAudioRecord(int32_t track, double lengthBeats);
    void cancelAudioRecord();
    bool startCapture(float maxSeconds);
    /** Stops mic sampling and returns what was captured; waits for the audio thread up to timeoutMs. */
    std::vector<float> stopCapture(int32_t timeoutMs);

    /** Events for the UI since the last call (recording started/finished). */
    std::vector<UserEvent> pollEvents();

private:
    bool send(const Command& command);
    void drainLocked();
    void freeRetired(void* ptr, int32_t kind);

    std::unique_ptr<Engine> mEngine;
    std::mutex mMutex;
    double mTempo = 120.0;
    std::array<AudioClip*, kMaxTracks> mClips{};  // what the audio thread is playing, read-only here
    AudioClip* mArmedClip = nullptr;
    int32_t mArmedTrack = -1;
    CaptureBuffer* mPendingCapture = nullptr;
    std::vector<float> mFinishedCapture;
    bool mCaptureFinished = false;
    std::vector<UserEvent> mUserEvents;
};

} // namespace beatster
