#pragma once

#include <array>
#include <atomic>
#include <cstdint>

#include "Buffers.h"
#include "Effects.h"
#include "EngineTypes.h"
#include "SpscQueue.h"

namespace beatster {

enum class CommandType : int32_t {
    NoteOn,            // a = track, b = note, f = velocity
    NoteOff,           // a = track, b = note
    PadLoop,           // a = track, b = pad, n = 1 start / 0 stop
    SetPadSample,      // a = pad, ptr = Sample* (may be null to clear)
    SetPadParam,       // a = pad, b = PadParam, f = value
    SetTrackType,      // a = track, b = TrackType
    SetTrackParam,     // a = track, b = TrackParam, f = value
    SetTrackLength,    // a = track, d = length in beats
    SetPattern,        // a = track, ptr = Pattern*
    SetAudioClip,      // a = track, ptr = AudioClip* (may be null to clear)
    Play,              // d = count-in beats
    Stop,
    SetTempo,          // d = bpm
    SetMetronome,      // a = on/off, f = volume
    ArmAudioRecord,    // a = track, ptr = AudioClip* with preallocated data
    CancelAudioRecord,
    StartCapture,      // ptr = CaptureBuffer* with preallocated data
    StopCapture,
    SetInputLatency,   // n = frames between a sound being played and it arriving at the input
    AllNotesOff,
};

struct Command {
    CommandType type = CommandType::AllNotesOff;
    int32_t a = 0;
    int32_t b = 0;
    float f = 0.0f;
    double d = 0.0;
    int64_t n = 0;
    void* ptr = nullptr;
};

struct EngineEvent {
    EngineEventType type = EngineEventType::None;
    int32_t a = 0;
    int64_t frames = 0;
    void* ptr = nullptr;
};

/**
 * The real-time part of Beatster: a 16-pad sampler, a polyphonic synth, a transport with
 * metronome and count-in, per-track looping clips of independent lengths, audio recording
 * into clips, mic sampling and per-track effects, mixed to stereo.
 *
 * Threading: render() runs on the audio thread. Everything else talks to it through
 * post() (control -> audio) and popEvent() (audio -> control). Objects passed by pointer in
 * a command are owned by the audio thread from then on, and come back through a Retire or
 * CaptureFinished event for the control side to free. Nothing on the audio path allocates,
 * locks or frees.
 */
class Engine {
public:
    explicit Engine(int32_t sampleRate);
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    int32_t sampleRate() const { return mSampleRate; }

    // --- control thread ---
    bool post(const Command& command) { return mCommands.push(command); }
    bool popEvent(EngineEvent& event) { return mEvents.pop(event); }

    // --- read from any thread ---
    double beatPosition() const { return mBeatPublished.load(std::memory_order_relaxed); }
    bool isPlaying() const { return mPlayingPublished.load(std::memory_order_relaxed); }
    double tempo() const { return mTempoPublished.load(std::memory_order_relaxed); }
    float inputPeak() const { return mInputPeak.load(std::memory_order_relaxed); }
    float outputPeak() const { return mOutputPeak.load(std::memory_order_relaxed); }
    // -1 idle, otherwise the track; recordingActive tells armed (waiting for the bar) from rolling.
    int32_t recordingTrack() const { return mRecordTrackPublished.load(std::memory_order_relaxed); }
    bool recordingActive() const { return mRecordActivePublished.load(std::memory_order_relaxed); }
    int64_t captureFrames() const { return mCaptureFramesPublished.load(std::memory_order_relaxed); }
    uint64_t renderCount() const { return mRenderCount.load(std::memory_order_acquire); }
    int32_t activeVoices() const { return mActiveVoicesPublished.load(std::memory_order_relaxed); }

    // --- audio thread ---
    /**
     * Renders `frames` stereo interleaved frames into `out`. `input` is mono microphone
     * audio for the same period, or nullptr when there is no input stream.
     */
    void render(float* out, const float* input, int32_t frames);

private:
    enum class VoiceKind : int32_t { Sample, Synth };
    enum class EnvStage : int32_t { Attack, Decay, Sustain, Release, Off };

    struct Voice {
        bool active = false;
        VoiceKind kind = VoiceKind::Sample;
        int32_t track = -1;
        int32_t note = -1;
        int32_t pad = -1;          // pad whose sample is playing, if any
        int32_t chokeGroup = 0;
        uint64_t age = 0;

        const Sample* sample = nullptr;
        double position = 0.0;
        double rate = 1.0;
        bool loop = false;
        bool oneShot = true;       // sample voices that ignore note-off and play to the end
        bool held = false;         // live note waiting for its note-off
        int64_t gateFrames = -1;   // sequenced notes release after this many frames (-1 = none)
        int32_t startDelay = 0;    // frames to wait inside the current block (sample-accurate starts)

        float velocity = 1.0f;
        float gainL = 1.0f;
        float gainR = 1.0f;

        EnvStage stage = EnvStage::Off;
        float env = 0.0f;
        float attackStep = 1.0f;
        float decayCoef = 0.0f;
        float sustain = 1.0f;
        float releaseCoef = 0.0f;

        double phase1 = 0.0, phase2 = 0.0;
        double inc1 = 0.0, inc2 = 0.0;
        int32_t wave = 0;
        SvfChannel filter;
    };

    struct PadState {
        Sample* sample = nullptr;
        float volume = 1.0f;
        float pan = 0.0f;
        float pitch = 0.0f;
        bool gate = false;
        int32_t chokeGroup = 0;
    };

    struct SynthParams {
        int32_t wave = 0;
        float attack = 0.005f;
        float decay = 0.2f;
        float sustain = 0.7f;
        float release = 0.25f;
        float cutoff = 4000.0f;
        float resonance = 0.2f;
        float detune = 8.0f;
        int32_t sourcePad = -1;
    };

    struct TrackState {
        TrackType type = TrackType::Drum;
        float volume = 0.8f;
        float pan = 0.0f;
        bool mute = false;
        bool solo = false;
        double lengthBeats = 4.0;
        Pattern* pattern = nullptr;
        AudioClip* audio = nullptr;
        SynthParams synth;
        EffectChain fx;
        float smoothedGainL = 0.0f;
        float smoothedGainR = 0.0f;
    };

    struct RecordJob {
        AudioClip* clip = nullptr;
        int32_t track = -1;
        bool started = false;
        double startBeat = 0.0;
        int64_t written = 0;
        int64_t lengthFrames = -1;  // set once the play head passes the clip end
    };

    void handleCommand(const Command& c);
    void renderBlock(float* out, const float* input, int32_t frames);
    void scheduleTrack(int32_t trackIndex, double beginBeat, double endBeat, double framesPerBeat, int32_t frames);
    void scheduleMetronome(double beginBeat, double endBeat, double framesPerBeat, int32_t frames);
    void renderVoice(Voice& v, int32_t frames);
    void renderAudioClip(TrackState& track, double beginBeat, double framesPerBeat, int32_t frames, float* left, float* right);
    void processRecording(const float* input, double beginBeat, double endBeat, double framesPerBeat, int32_t frames);

    void noteOn(int32_t track, int32_t note, float velocity, int32_t startDelay, int64_t gateFrames, bool live);
    void noteOff(int32_t track, int32_t note);
    void startSampleVoice(int32_t track, int32_t pad, int32_t note, float velocity, int32_t startDelay,
                          int64_t gateFrames, bool live, bool loop);
    void startSynthVoice(int32_t track, int32_t note, float velocity, int32_t startDelay, int64_t gateFrames, bool live);
    Voice& allocateVoice();
    void releaseVoice(Voice& v, float releaseSeconds);
    void killVoicesUsing(const Sample* sample);
    void releaseTrackVoices(int32_t track, bool hardCut);
    void retire(void* ptr, RetireKind kind);
    void finishRecording();
    void setTrackParam(TrackState& t, int32_t param, float value);
    static void panGains(float pan, float& left, float& right);

    const int32_t mSampleRate;

    SpscQueue<Command, 1024> mCommands;
    SpscQueue<EngineEvent, 1024> mEvents;

    std::array<PadState, kMaxPads> mPads{};
    std::array<TrackState, kMaxTracks> mTracks{};
    std::array<Voice, kMaxVoices> mVoices{};
    uint64_t mVoiceAge = 0;

    // transport
    bool mPlaying = false;
    double mBeat = 0.0;
    double mTempo = 120.0;
    bool mMetronomeOn = true;
    float mMetronomeVolume = 0.6f;
    double mClickPhase = 0.0;
    double mClickInc = 0.0;
    int32_t mClickRemaining = 0;
    int32_t mClickDelay = 0;
    float mClickGain = 0.0f;

    // recording
    RecordJob mRecord;
    CaptureBuffer* mCapture = nullptr;
    int64_t mCaptureWritten = 0;
    int64_t mInputLatencyFrames = 0;

    // scratch
    std::array<std::array<float, kMaxBlockFrames>, kMaxTracks> mTrackL{};
    std::array<std::array<float, kMaxBlockFrames>, kMaxTracks> mTrackR{};
    std::array<float, kMaxBlockFrames> mMixL{};
    std::array<float, kMaxBlockFrames> mMixR{};
    float mLimiterGain = 1.0f;

    std::atomic<double> mBeatPublished{0.0};
    std::atomic<bool> mPlayingPublished{false};
    std::atomic<double> mTempoPublished{120.0};
    std::atomic<float> mInputPeak{0.0f};
    std::atomic<float> mOutputPeak{0.0f};
    std::atomic<int32_t> mRecordTrackPublished{-1};
    std::atomic<bool> mRecordActivePublished{false};
    std::atomic<int64_t> mCaptureFramesPublished{0};
    std::atomic<uint64_t> mRenderCount{0};
    std::atomic<int32_t> mActiveVoicesPublished{0};
};

} // namespace beatster
