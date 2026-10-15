#include "EngineController.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <thread>

namespace beatster {

EngineController::EngineController(int32_t sampleRate) : mEngine(std::make_unique<Engine>(sampleRate)) {}

EngineController::~EngineController() {
    std::lock_guard<std::mutex> lock(mMutex);
    drainLocked();
    // Engine's destructor frees what the audio side still owns. The caller must have
    // stopped the audio stream before destroying the controller.
    mEngine.reset();
}

bool EngineController::send(const Command& command) {
    drainLocked();
    if (mEngine->post(command)) return true;
    // Queue full: we still own the payload.
    switch (command.type) {
        case CommandType::SetPadSample: delete static_cast<Sample*>(command.ptr); break;
        case CommandType::SetPattern: delete static_cast<Pattern*>(command.ptr); break;
        case CommandType::SetAudioClip:
        case CommandType::ArmAudioRecord: delete static_cast<AudioClip*>(command.ptr); break;
        case CommandType::StartCapture: delete static_cast<CaptureBuffer*>(command.ptr); break;
        default: break;
    }
    return false;
}

void EngineController::freeRetired(void* ptr, int32_t kind) {
    switch (kind) {
        case kRetirePattern: delete static_cast<Pattern*>(ptr); break;
        case kRetireAudioClip: {
            auto* clip = static_cast<AudioClip*>(ptr);
            for (auto& c : mClips) {
                if (c == clip) c = nullptr;
            }
            if (mArmedClip == clip) {
                mArmedClip = nullptr;
                mArmedTrack = -1;
            }
            delete clip;
            break;
        }
        case kRetireSample: delete static_cast<Sample*>(ptr); break;
        case kRetireCapture: delete static_cast<CaptureBuffer*>(ptr); break;
        default: break;
    }
}

void EngineController::drainLocked() {
    EngineEvent e;
    while (mEngine->popEvent(e)) {
        switch (e.type) {
            case EngineEventType::Retire:
                freeRetired(e.ptr, e.a);
                break;
            case EngineEventType::AudioRecordStarted:
                mUserEvents.push_back({e.type, e.a});
                break;
            case EngineEventType::AudioRecordFinished:
                if (mArmedClip != nullptr && mArmedTrack == e.a) {
                    mClips[static_cast<size_t>(e.a)] = mArmedClip;
                }
                mArmedClip = nullptr;
                mArmedTrack = -1;
                mUserEvents.push_back({e.type, e.a});
                break;
            case EngineEventType::CaptureFinished: {
                auto* buffer = static_cast<CaptureBuffer*>(e.ptr);
                if (buffer == mPendingCapture) {
                    const auto n = static_cast<size_t>(std::clamp<int64_t>(e.frames, 0, static_cast<int64_t>(buffer->data.size())));
                    mFinishedCapture.assign(buffer->data.begin(), buffer->data.begin() + static_cast<std::ptrdiff_t>(n));
                    mCaptureFinished = true;
                    mPendingCapture = nullptr;
                }
                delete buffer;
                break;
            }
            case EngineEventType::None:
                break;
        }
    }
}

bool EngineController::setPadSample(int32_t pad, const float* interleaved, int64_t frames, int32_t channels,
                                    int32_t sourceRate) {
    if (pad < 0 || pad >= kMaxPads) return false;
    // Decode/resample outside the lock: it can take a while for long samples.
    std::unique_ptr<Sample> sample = createSample(interleaved, frames, channels, sourceRate, sampleRate());
    if (!sample) return false;
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetPadSample;
    c.a = pad;
    c.ptr = sample.release();
    return send(c);
}

void EngineController::clearPad(int32_t pad) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetPadSample;
    c.a = pad;
    send(c);
}

void EngineController::setPadParam(int32_t pad, int32_t param, float value) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetPadParam;
    c.a = pad;
    c.b = param;
    c.f = value;
    send(c);
}

void EngineController::noteOn(int32_t track, int32_t note, float velocity) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::NoteOn;
    c.a = track;
    c.b = note;
    c.f = velocity;
    send(c);
}

void EngineController::noteOff(int32_t track, int32_t note) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::NoteOff;
    c.a = track;
    c.b = note;
    send(c);
}

void EngineController::padLoop(int32_t track, int32_t pad, bool on) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::PadLoop;
    c.a = track;
    c.b = pad;
    c.n = on ? 1 : 0;
    send(c);
}

void EngineController::allNotesOff() {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::AllNotesOff;
    send(c);
}

void EngineController::setTrackType(int32_t track, int32_t type) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetTrackType;
    c.a = track;
    c.b = type;
    send(c);
}

void EngineController::setTrackParam(int32_t track, int32_t param, float value) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetTrackParam;
    c.a = track;
    c.b = param;
    c.f = value;
    send(c);
}

void EngineController::setTrackLength(int32_t track, double beats) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetTrackLength;
    c.a = track;
    c.d = beats;
    send(c);
}

void EngineController::setPattern(int32_t track, std::vector<NoteEvent> events) {
    auto pattern = std::make_unique<Pattern>();
    std::stable_sort(events.begin(), events.end(),
                     [](const NoteEvent& x, const NoteEvent& y) { return x.startBeat < y.startBeat; });
    pattern->events = std::move(events);
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetPattern;
    c.a = track;
    c.ptr = pattern.release();
    send(c);
}

bool EngineController::setAudioClip(int32_t track, const float* mono, int64_t frames, int32_t sourceRate,
                                    double lengthBeats) {
    if (track < 0 || track >= kMaxTracks) return false;
    std::unique_ptr<Sample> resampled = createSample(mono, frames, 1, sourceRate, sampleRate());
    if (!resampled) return false;
    auto clip = std::make_unique<AudioClip>();
    clip->data = std::move(resampled->data);
    clip->offsetFrames = 0;
    clip->lengthFrames = static_cast<int64_t>(clip->data.size());
    clip->lengthBeats = std::max(lengthBeats, 0.25);
    std::lock_guard<std::mutex> lock(mMutex);
    AudioClip* raw = clip.get();
    Command c;
    c.type = CommandType::SetAudioClip;
    c.a = track;
    c.ptr = clip.release();
    if (!send(c)) return false;
    mClips[static_cast<size_t>(track)] = raw;
    return true;
}

void EngineController::clearAudioClip(int32_t track) {
    if (track < 0 || track >= kMaxTracks) return;
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetAudioClip;
    c.a = track;
    if (send(c)) mClips[static_cast<size_t>(track)] = nullptr;
}

std::vector<float> EngineController::copyAudioClip(int32_t track, double* lengthBeats) {
    std::lock_guard<std::mutex> lock(mMutex);
    drainLocked();
    if (track < 0 || track >= kMaxTracks) return {};
    const AudioClip* clip = mClips[static_cast<size_t>(track)];
    if (clip == nullptr) return {};
    // The audio thread only reads clip data once a clip is published, so copying is safe;
    // it is freed only by drainLocked(), which needs the lock we hold.
    const auto begin = static_cast<size_t>(std::max<int64_t>(clip->offsetFrames, 0));
    const size_t end = std::min(clip->data.size(), begin + static_cast<size_t>(std::max<int64_t>(clip->lengthFrames, 0)));
    if (lengthBeats != nullptr) *lengthBeats = clip->lengthBeats;
    if (begin >= end) return {};
    return {clip->data.begin() + static_cast<std::ptrdiff_t>(begin), clip->data.begin() + static_cast<std::ptrdiff_t>(end)};
}

void EngineController::play(double countInBeats) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::Play;
    c.d = countInBeats;
    send(c);
}

void EngineController::stop() {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::Stop;
    send(c);
}

void EngineController::setTempo(double bpm) {
    std::lock_guard<std::mutex> lock(mMutex);
    mTempo = std::clamp(bpm, 30.0, 300.0);
    Command c;
    c.type = CommandType::SetTempo;
    c.d = mTempo;
    send(c);
}

void EngineController::setMetronome(bool on, float volume) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetMetronome;
    c.a = on ? 1 : 0;
    c.f = volume;
    send(c);
}

void EngineController::setInputLatencyFrames(int64_t frames) {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::SetInputLatency;
    c.n = frames;
    send(c);
}

bool EngineController::armAudioRecord(int32_t track, double lengthBeats) {
    if (track < 0 || track >= kMaxTracks) return false;
    std::lock_guard<std::mutex> lock(mMutex);
    // Room for the take at the slowest plausible tempo change plus latency tail and margin.
    const double seconds = lengthBeats * 60.0 / std::max(mTempo, 30.0);
    const auto frames = static_cast<size_t>((seconds * 1.25 + 1.0) * sampleRate());
    auto clip = std::make_unique<AudioClip>();
    clip->data.assign(frames, 0.0f);
    clip->lengthBeats = lengthBeats;
    AudioClip* raw = clip.get();
    Command c;
    c.type = CommandType::ArmAudioRecord;
    c.a = track;
    c.ptr = clip.release();
    if (!send(c)) return false;
    mArmedClip = raw;
    mArmedTrack = track;
    return true;
}

void EngineController::cancelAudioRecord() {
    std::lock_guard<std::mutex> lock(mMutex);
    Command c;
    c.type = CommandType::CancelAudioRecord;
    send(c);
}

bool EngineController::startCapture(float maxSeconds) {
    auto buffer = std::make_unique<CaptureBuffer>();
    buffer->data.assign(static_cast<size_t>(std::clamp(maxSeconds, 0.5f, 120.0f) * static_cast<float>(sampleRate())), 0.0f);
    std::lock_guard<std::mutex> lock(mMutex);
    CaptureBuffer* raw = buffer.get();
    Command c;
    c.type = CommandType::StartCapture;
    c.ptr = buffer.release();
    if (!send(c)) return false;
    mPendingCapture = raw;
    mCaptureFinished = false;
    mFinishedCapture.clear();
    return true;
}

std::vector<float> EngineController::stopCapture(int32_t timeoutMs) {
    std::unique_lock<std::mutex> lock(mMutex);
    if (mPendingCapture == nullptr && !mCaptureFinished) return {};
    Command c;
    c.type = CommandType::StopCapture;
    send(c);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (!mCaptureFinished && std::chrono::steady_clock::now() < deadline) {
        lock.unlock();
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
        lock.lock();
        drainLocked();
    }
    if (!mCaptureFinished) {
        // The stream is not running; the buffer is freed whenever the engine hands it back.
        mPendingCapture = nullptr;
        return {};
    }
    mCaptureFinished = false;
    return std::move(mFinishedCapture);
}

std::vector<EngineController::UserEvent> EngineController::pollEvents() {
    std::lock_guard<std::mutex> lock(mMutex);
    drainLocked();
    std::vector<UserEvent> out;
    out.swap(mUserEvents);
    return out;
}

} // namespace beatster
