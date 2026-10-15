#include "Engine.h"

#include <algorithm>
#include <cmath>

namespace beatster {

namespace {

constexpr float kSampleAttackSeconds = 0.0005f;
constexpr float kSampleReleaseSeconds = 0.012f;
constexpr float kChokeReleaseSeconds = 0.006f;
constexpr float kLimiterThreshold = 0.95f;
constexpr int32_t kBeatsPerBar = 4;
constexpr float kSynthLevel = 0.22f;

float coefForSeconds(float seconds, float sampleRate) {
    // Exponential segment that falls to ~1% after `seconds`.
    const float s = std::max(seconds, 0.0005f);
    return std::exp(-4.6f / (s * sampleRate));
}

double polyBlep(double t, double dt) {
    if (t < dt) {
        t /= dt;
        return t + t - t * t - 1.0;
    }
    if (t > 1.0 - dt) {
        t = (t - 1.0) / dt;
        return t * t + t + t + 1.0;
    }
    return 0.0;
}

float oscillator(int32_t wave, double phase, double inc) {
    switch (wave) {
        case 1: {  // square
            double v = phase < 0.5 ? 1.0 : -1.0;
            v += polyBlep(phase, inc);
            v -= polyBlep(std::fmod(phase + 0.5, 1.0), inc);
            return static_cast<float>(v);
        }
        case 2:  // sine
            return static_cast<float>(std::sin(phase * 2.0 * 3.14159265358979323846));
        case 3:  // triangle
            return static_cast<float>(4.0 * std::fabs(phase - 0.5) - 1.0);
        default:  // saw
            return static_cast<float>(2.0 * phase - 1.0 - polyBlep(phase, inc));
    }
}

double midiToHz(double note) { return 440.0 * std::pow(2.0, (note - 69.0) / 12.0); }

} // namespace

Engine::Engine(int32_t sampleRate) : mSampleRate(sampleRate > 0 ? sampleRate : 48000) {
    for (auto& track : mTracks) {
        track.fx.prepare(static_cast<float>(mSampleRate));
    }
}

Engine::~Engine() {
    // The audio stream is closed before the engine is destroyed, so it is safe to free
    // whatever the audio side still owns, including commands that were never handled.
    Command c;
    while (mCommands.pop(c)) {
        switch (c.type) {
            case CommandType::SetPadSample: delete static_cast<Sample*>(c.ptr); break;
            case CommandType::SetPattern: delete static_cast<Pattern*>(c.ptr); break;
            case CommandType::SetAudioClip:
            case CommandType::ArmAudioRecord: delete static_cast<AudioClip*>(c.ptr); break;
            case CommandType::StartCapture: delete static_cast<CaptureBuffer*>(c.ptr); break;
            default: break;
        }
    }
    for (auto& pad : mPads) delete pad.sample;
    for (auto& track : mTracks) {
        delete track.pattern;
        delete track.audio;
    }
    delete mRecord.clip;
    delete mCapture;
}

void Engine::render(float* out, const float* input, int32_t frames) {
    Command command;
    while (mCommands.pop(command)) {
        handleCommand(command);
    }

    float inPeak = 0.0f;
    if (input != nullptr) {
        for (int32_t i = 0; i < frames; ++i) inPeak = std::max(inPeak, std::fabs(input[i]));
    }

    int32_t done = 0;
    while (done < frames) {
        const int32_t n = std::min(frames - done, kMaxBlockFrames);
        renderBlock(out + done * kOutputChannels, input != nullptr ? input + done : nullptr, n);
        done += n;
    }

    float outPeak = 0.0f;
    for (int32_t i = 0; i < frames * kOutputChannels; ++i) outPeak = std::max(outPeak, std::fabs(out[i]));

    int32_t voices = 0;
    for (const auto& v : mVoices) voices += v.active ? 1 : 0;

    mInputPeak.store(std::max(inPeak, mInputPeak.load(std::memory_order_relaxed) * 0.9f), std::memory_order_relaxed);
    mOutputPeak.store(std::max(outPeak, mOutputPeak.load(std::memory_order_relaxed) * 0.9f), std::memory_order_relaxed);
    mBeatPublished.store(mBeat, std::memory_order_relaxed);
    mPlayingPublished.store(mPlaying, std::memory_order_relaxed);
    mTempoPublished.store(mTempo, std::memory_order_relaxed);
    mRecordTrackPublished.store(mRecord.clip != nullptr ? mRecord.track : -1, std::memory_order_relaxed);
    mRecordActivePublished.store(mRecord.clip != nullptr && mRecord.started, std::memory_order_relaxed);
    mCaptureFramesPublished.store(mCapture != nullptr ? mCaptureWritten : 0, std::memory_order_relaxed);
    mActiveVoicesPublished.store(voices, std::memory_order_relaxed);
    mRenderCount.fetch_add(1, std::memory_order_release);
}

void Engine::handleCommand(const Command& c) {
    const bool validTrack = c.a >= 0 && c.a < kMaxTracks;
    const bool validPad = c.a >= 0 && c.a < kMaxPads;
    switch (c.type) {
        case CommandType::NoteOn:
            if (validTrack) noteOn(c.a, c.b, std::clamp(c.f, 0.0f, 1.0f), 0, -1, true);
            break;
        case CommandType::NoteOff:
            if (validTrack) noteOff(c.a, c.b);
            break;
        case CommandType::PadLoop:
            if (!validTrack || c.b < 0 || c.b >= kMaxPads) break;
            if (c.n != 0) {
                startSampleVoice(c.a, c.b, -100 - c.b, 1.0f, 0, -1, false, true);
            } else {
                for (auto& v : mVoices) {
                    if (v.active && v.track == c.a && v.pad == c.b && v.loop) releaseVoice(v, kSampleReleaseSeconds);
                }
            }
            break;
        case CommandType::SetPadSample: {
            if (!validPad) { retire(c.ptr, kRetireSample); break; }
            Sample* old = mPads[c.a].sample;
            killVoicesUsing(old);
            mPads[c.a].sample = static_cast<Sample*>(c.ptr);
            retire(old, kRetireSample);
            break;
        }
        case CommandType::SetPadParam: {
            if (!validPad) break;
            PadState& pad = mPads[c.a];
            switch (c.b) {
                case kPadVolume: pad.volume = std::clamp(c.f, 0.0f, 2.0f); break;
                case kPadPan: pad.pan = std::clamp(c.f, -1.0f, 1.0f); break;
                case kPadPitch: pad.pitch = std::clamp(c.f, -48.0f, 48.0f); break;
                case kPadGate: pad.gate = c.f > 0.5f; break;
                case kPadChokeGroup: pad.chokeGroup = static_cast<int32_t>(c.f); break;
                default: break;
            }
            break;
        }
        case CommandType::SetTrackType:
            if (!validTrack) break;
            releaseTrackVoices(c.a, true);
            mTracks[c.a].type = static_cast<TrackType>(std::clamp(c.b, 0, 2));
            break;
        case CommandType::SetTrackParam:
            if (validTrack) setTrackParam(mTracks[c.a], c.b, c.f);
            break;
        case CommandType::SetTrackLength:
            if (validTrack) mTracks[c.a].lengthBeats = std::max(c.d, 0.25);
            break;
        case CommandType::SetPattern: {
            if (!validTrack) { retire(c.ptr, kRetirePattern); break; }
            Pattern* old = mTracks[c.a].pattern;
            mTracks[c.a].pattern = static_cast<Pattern*>(c.ptr);
            retire(old, kRetirePattern);
            break;
        }
        case CommandType::SetAudioClip: {
            if (!validTrack) { retire(c.ptr, kRetireAudioClip); break; }
            AudioClip* old = mTracks[c.a].audio;
            mTracks[c.a].audio = static_cast<AudioClip*>(c.ptr);
            retire(old, kRetireAudioClip);
            break;
        }
        case CommandType::Play:
            if (!mPlaying) {
                mBeat = -std::max(0.0, c.d);
                mPlaying = true;
            }
            break;
        case CommandType::Stop:
            mPlaying = false;
            mBeat = 0.0;
            for (int32_t t = 0; t < kMaxTracks; ++t) releaseTrackVoices(t, false);
            if (mRecord.clip != nullptr) {
                if (mRecord.started && mRecord.lengthFrames >= 0) {
                    finishRecording();  // the take is complete apart from the latency tail
                } else {
                    retire(mRecord.clip, kRetireAudioClip);
                    mRecord = RecordJob{};
                }
            }
            break;
        case CommandType::SetTempo:
            mTempo = std::clamp(c.d, 30.0, 300.0);
            break;
        case CommandType::SetMetronome:
            mMetronomeOn = c.a != 0;
            mMetronomeVolume = std::clamp(c.f, 0.0f, 1.0f);
            break;
        case CommandType::ArmAudioRecord:
            if (mRecord.clip != nullptr) retire(mRecord.clip, kRetireAudioClip);
            mRecord = RecordJob{};
            if (!validTrack) { retire(c.ptr, kRetireAudioClip); break; }
            mRecord.clip = static_cast<AudioClip*>(c.ptr);
            mRecord.track = c.a;
            break;
        case CommandType::CancelAudioRecord:
            if (mRecord.clip != nullptr) retire(mRecord.clip, kRetireAudioClip);
            mRecord = RecordJob{};
            break;
        case CommandType::StartCapture:
            if (mCapture != nullptr) {
                mEvents.push(EngineEvent{EngineEventType::CaptureFinished, 0, mCaptureWritten, mCapture});
            }
            mCapture = static_cast<CaptureBuffer*>(c.ptr);
            mCaptureWritten = 0;
            break;
        case CommandType::StopCapture:
            if (mCapture != nullptr) {
                mEvents.push(EngineEvent{EngineEventType::CaptureFinished, 0, mCaptureWritten, mCapture});
                mCapture = nullptr;
                mCaptureWritten = 0;
            }
            break;
        case CommandType::SetInputLatency:
            mInputLatencyFrames = std::max<int64_t>(0, c.n);
            break;
        case CommandType::AllNotesOff:
            for (auto& v : mVoices) {
                if (v.active) releaseVoice(v, kSampleReleaseSeconds);
            }
            break;
    }
}

void Engine::setTrackParam(TrackState& t, int32_t param, float value) {
    auto on = [](float v) { return v > 0.5f; };
    EffectChain& fx = t.fx;
    switch (param) {
        case kTrackVolume: t.volume = std::clamp(value, 0.0f, 2.0f); break;
        case kTrackPan: t.pan = std::clamp(value, -1.0f, 1.0f); break;
        case kTrackMute: t.mute = on(value); break;
        case kTrackSolo: t.solo = on(value); break;

        case kSynthWave: t.synth.wave = std::clamp(static_cast<int32_t>(value), 0, 3); break;
        case kSynthAttack: t.synth.attack = std::clamp(value, 0.0005f, 10.0f); break;
        case kSynthDecay: t.synth.decay = std::clamp(value, 0.001f, 10.0f); break;
        case kSynthSustain: t.synth.sustain = std::clamp(value, 0.0f, 1.0f); break;
        case kSynthRelease: t.synth.release = std::clamp(value, 0.001f, 10.0f); break;
        case kSynthCutoff: t.synth.cutoff = std::clamp(value, 40.0f, 20000.0f); break;
        case kSynthResonance: t.synth.resonance = std::clamp(value, 0.0f, 1.0f); break;
        case kSynthDetune: t.synth.detune = std::clamp(value, 0.0f, 100.0f); break;
        case kSynthSourcePad: t.synth.sourcePad = std::clamp(static_cast<int32_t>(value), -1, kMaxPads - 1); break;

        case kFxFilterOn:
            if (on(value) && !fx.filterOn) { fx.filter[0].reset(); fx.filter[1].reset(); }
            fx.filterOn = on(value);
            break;
        case kFxFilterMode: fx.filterMode = std::clamp(static_cast<int32_t>(value), 0, 2); break;
        case kFxFilterCutoff: fx.filterCutoff = std::clamp(value, 20.0f, 20000.0f); break;
        case kFxFilterResonance: fx.filterResonance = std::clamp(value, 0.0f, 1.0f); break;
        case kFxDriveOn: fx.driveOn = on(value); break;
        case kFxDriveAmount: fx.driveAmount = std::clamp(value, 0.0f, 1.0f); break;
        case kFxCrushOn:
            if (on(value) && !fx.crushOn) fx.crusher.reset();
            fx.crushOn = on(value);
            break;
        case kFxCrushBits: fx.crushBits = std::clamp(value, 2.0f, 16.0f); break;
        case kFxCrushDownsample: fx.crushDownsample = std::clamp(value, 1.0f, 32.0f); break;
        case kFxDelayOn:
            if (on(value) && !fx.delayOn) fx.delay.reset();
            fx.delayOn = on(value);
            break;
        case kFxDelayBeats: fx.delayBeats = std::clamp(value, 0.0625f, 4.0f); break;
        case kFxDelayFeedback: fx.delayFeedback = std::clamp(value, 0.0f, 0.95f); break;
        case kFxDelayMix: fx.delayMix = std::clamp(value, 0.0f, 1.0f); break;
        case kFxReverbOn:
            if (on(value) && !fx.reverbOn) fx.reverb.reset();
            fx.reverbOn = on(value);
            break;
        case kFxReverbSize: fx.reverbSize = std::clamp(value, 0.0f, 1.0f); break;
        case kFxReverbDamping: fx.reverbDamping = std::clamp(value, 0.0f, 1.0f); break;
        case kFxReverbMix: fx.reverbMix = std::clamp(value, 0.0f, 1.0f); break;
        default: break;
    }
}

void Engine::renderBlock(float* out, const float* input, int32_t frames) {
    const double framesPerBeat = static_cast<double>(mSampleRate) * 60.0 / mTempo;
    const double beginBeat = mBeat;
    const double endBeat = mBeat + static_cast<double>(frames) / framesPerBeat;

    if (mPlaying) {
        scheduleMetronome(beginBeat, endBeat, framesPerBeat, frames);
        for (int32_t t = 0; t < kMaxTracks; ++t) {
            if (mTracks[t].type != TrackType::Audio) scheduleTrack(t, beginBeat, endBeat, framesPerBeat, frames);
        }
    }

    for (int32_t t = 0; t < kMaxTracks; ++t) {
        std::fill_n(mTrackL[t].begin(), frames, 0.0f);
        std::fill_n(mTrackR[t].begin(), frames, 0.0f);
    }

    for (auto& v : mVoices) {
        if (v.active) renderVoice(v, frames);
    }

    if (mPlaying) {
        for (int32_t t = 0; t < kMaxTracks; ++t) {
            TrackState& track = mTracks[t];
            const bool beingRecorded = mRecord.clip != nullptr && mRecord.started && mRecord.track == t;
            if (track.type == TrackType::Audio && track.audio != nullptr && !beingRecorded) {
                renderAudioClip(track, beginBeat, framesPerBeat, frames, mTrackL[t].data(), mTrackR[t].data());
            }
        }
    }

    processRecording(input, beginBeat, endBeat, framesPerBeat, frames);

    std::fill_n(mMixL.begin(), frames, 0.0f);
    std::fill_n(mMixR.begin(), frames, 0.0f);
    bool anySolo = false;
    for (const auto& t : mTracks) anySolo = anySolo || t.solo;

    for (int32_t t = 0; t < kMaxTracks; ++t) {
        TrackState& track = mTracks[t];
        float* left = mTrackL[t].data();
        float* right = mTrackR[t].data();
        track.fx.process(left, right, frames, static_cast<float>(mSampleRate), framesPerBeat);

        const bool audible = !track.mute && (!anySolo || track.solo);
        float targetL = 0.0f, targetR = 0.0f;
        if (audible) {
            panGains(track.pan, targetL, targetR);
            targetL *= track.volume;
            targetR *= track.volume;
        }
        // Ramp the gain across the block so volume, pan and mute changes do not click.
        const float stepL = (targetL - track.smoothedGainL) / static_cast<float>(frames);
        const float stepR = (targetR - track.smoothedGainR) / static_cast<float>(frames);
        float gl = track.smoothedGainL, gr = track.smoothedGainR;
        for (int32_t i = 0; i < frames; ++i) {
            gl += stepL;
            gr += stepR;
            mMixL[i] += left[i] * gl;
            mMixR[i] += right[i] * gr;
        }
        track.smoothedGainL = targetL;
        track.smoothedGainR = targetR;
    }

    // Metronome goes straight to the master bus, after the track effects.
    for (int32_t i = 0; i < frames && mClickRemaining > 0; ++i) {
        if (mClickDelay > 0) { --mClickDelay; continue; }
        const float env = static_cast<float>(mClickRemaining) / (0.03f * static_cast<float>(mSampleRate));
        const float s = static_cast<float>(std::sin(mClickPhase)) * mClickGain * env * env;
        mClickPhase += mClickInc;
        --mClickRemaining;
        mMixL[i] += s;
        mMixR[i] += s;
    }

    // Peak limiter with instant attack and ~100 ms release, then a hard safety clip.
    const float release = 1.0f - std::exp(-1.0f / (0.1f * static_cast<float>(mSampleRate)));
    for (int32_t i = 0; i < frames; ++i) {
        const float peak = std::max(std::fabs(mMixL[i]), std::fabs(mMixR[i]));
        const float target = peak > kLimiterThreshold ? kLimiterThreshold / peak : 1.0f;
        if (target < mLimiterGain) mLimiterGain = target;
        else mLimiterGain += (target - mLimiterGain) * release;
        out[i * 2] = std::clamp(mMixL[i] * mLimiterGain, -1.0f, 1.0f);
        out[i * 2 + 1] = std::clamp(mMixR[i] * mLimiterGain, -1.0f, 1.0f);
    }

    if (mPlaying) mBeat = endBeat;
}

void Engine::scheduleMetronome(double beginBeat, double endBeat, double framesPerBeat, int32_t frames) {
    for (auto beat = static_cast<int64_t>(std::ceil(beginBeat)); static_cast<double>(beat) < endBeat; ++beat) {
        const bool countIn = beat < 0;
        if (!mMetronomeOn && !countIn) continue;
        const int64_t inBar = ((beat % kBeatsPerBar) + kBeatsPerBar) % kBeatsPerBar;
        const bool accent = inBar == 0;
        const auto offset = static_cast<int32_t>((static_cast<double>(beat) - beginBeat) * framesPerBeat);
        mClickDelay = std::clamp(offset, 0, frames - 1);
        mClickRemaining = static_cast<int32_t>(0.03f * static_cast<float>(mSampleRate));
        mClickPhase = 0.0;
        mClickInc = 2.0 * 3.14159265358979323846 * (accent ? 1760.0 : 1320.0) / mSampleRate;
        mClickGain = (accent ? 0.5f : 0.32f) * std::max(mMetronomeVolume, countIn ? 0.5f : 0.0f);
    }
}

void Engine::scheduleTrack(int32_t trackIndex, double beginBeat, double endBeat, double framesPerBeat, int32_t frames) {
    TrackState& track = mTracks[trackIndex];
    if (track.pattern == nullptr || track.pattern->events.empty() || endBeat <= 0.0) return;
    const double length = track.lengthBeats;
    const double from = std::max(beginBeat, 0.0);
    // Walk every loop iteration that overlaps [from, endBeat). Absolute event times are
    // computed the same way in every block, so a note on a block edge plays exactly once.
    for (auto loop = static_cast<int64_t>(std::floor(from / length));; ++loop) {
        const double loopStart = static_cast<double>(loop) * length;
        if (loopStart >= endBeat) break;
        for (NoteEvent& e : track.pattern->events) {
            if (e.startBeat >= length || e.startBeat < 0.0) continue;
            const double at = loopStart + e.startBeat;
            if (at < from || at >= endBeat) continue;
            if ((e.flags & kNoteSkipOnce) != 0) {
                e.flags &= ~kNoteSkipOnce;
                continue;
            }
            const auto offset = static_cast<int32_t>((at - beginBeat) * framesPerBeat);
            const auto gate = static_cast<int64_t>(std::max(e.lengthBeats, 1.0 / 64.0) * framesPerBeat);
            noteOn(trackIndex, e.note, e.velocity, std::clamp(offset, 0, frames - 1), gate, false);
        }
    }
}

void Engine::noteOn(int32_t track, int32_t note, float velocity, int32_t startDelay, int64_t gateFrames, bool live) {
    const TrackState& t = mTracks[track];
    switch (t.type) {
        case TrackType::Drum:
            if (note >= 0 && note < kMaxPads) startSampleVoice(track, note, note, velocity, startDelay, gateFrames, live, false);
            break;
        case TrackType::Synth:
            if (t.synth.sourcePad >= 0 && mPads[t.synth.sourcePad].sample != nullptr) {
                startSampleVoice(track, t.synth.sourcePad, note, velocity, startDelay, gateFrames, live, false);
            } else {
                startSynthVoice(track, note, velocity, startDelay, gateFrames, live);
            }
            break;
        case TrackType::Audio:
            break;
    }
}

void Engine::noteOff(int32_t track, int32_t note) {
    for (auto& v : mVoices) {
        if (v.active && v.held && v.track == track && v.note == note) {
            v.held = false;
            if (!v.oneShot) releaseVoice(v, -1.0f);  // keep the voice's own release time
        }
    }
}

Engine::Voice& Engine::allocateVoice() {
    Voice* best = nullptr;
    for (auto& v : mVoices) {
        if (!v.active) return v;
    }
    // Steal: prefer the oldest releasing voice, otherwise the oldest voice.
    for (auto& v : mVoices) {
        if (v.stage == EnvStage::Release && (best == nullptr || v.age < best->age)) best = &v;
    }
    if (best != nullptr) return *best;
    best = &mVoices[0];
    for (auto& v : mVoices) {
        if (v.age < best->age) best = &v;
    }
    return *best;
}

void Engine::startSampleVoice(int32_t track, int32_t pad, int32_t note, float velocity, int32_t startDelay,
                              int64_t gateFrames, bool live, bool loop) {
    const PadState& p = mPads[pad];
    if (p.sample == nullptr || p.sample->frames <= 0) return;
    const TrackState& t = mTracks[track];
    const bool chromatic = t.type == TrackType::Synth;

    if (p.chokeGroup > 0 && !chromatic) {
        for (auto& v : mVoices) {
            if (v.active && v.track == track && v.chokeGroup == p.chokeGroup) releaseVoice(v, kChokeReleaseSeconds);
        }
    }

    Voice& v = allocateVoice();
    v = Voice{};
    v.active = true;
    v.kind = VoiceKind::Sample;
    v.track = track;
    v.note = note;
    v.pad = pad;
    v.chokeGroup = chromatic ? 0 : p.chokeGroup;
    v.age = ++mVoiceAge;
    v.sample = p.sample;
    v.position = 0.0;
    double semis = p.pitch;
    if (chromatic) semis += static_cast<double>(note - 60);
    v.rate = std::pow(2.0, semis / 12.0);
    v.loop = loop;
    v.oneShot = !loop && !chromatic && !p.gate;
    v.held = loop || (live && !v.oneShot);
    v.gateFrames = (loop || v.oneShot) ? -1 : gateFrames;
    v.startDelay = startDelay;
    v.velocity = velocity;
    panGains(p.pan, v.gainL, v.gainR);
    v.gainL *= p.volume * velocity;
    v.gainR *= p.volume * velocity;

    const float sr = static_cast<float>(mSampleRate);
    v.stage = EnvStage::Attack;
    v.env = 0.0f;
    if (chromatic) {
        v.attackStep = 1.0f / (std::max(t.synth.attack, 0.0005f) * sr);
        v.decayCoef = coefForSeconds(t.synth.decay, sr);
        v.sustain = t.synth.sustain;
        v.releaseCoef = coefForSeconds(t.synth.release, sr);
    } else {
        v.attackStep = 1.0f / (kSampleAttackSeconds * sr);
        v.decayCoef = 0.0f;
        v.sustain = 1.0f;
        v.releaseCoef = coefForSeconds(kSampleReleaseSeconds, sr);
    }
}

void Engine::startSynthVoice(int32_t track, int32_t note, float velocity, int32_t startDelay, int64_t gateFrames, bool live) {
    const SynthParams& s = mTracks[track].synth;
    Voice& v = allocateVoice();
    v = Voice{};
    v.active = true;
    v.kind = VoiceKind::Synth;
    v.track = track;
    v.note = note;
    v.age = ++mVoiceAge;
    v.oneShot = false;
    v.held = live;
    v.gateFrames = live ? -1 : gateFrames;
    v.startDelay = startDelay;
    v.velocity = velocity;
    v.gainL = v.gainR = kSynthLevel * velocity;
    v.wave = s.wave;
    const double hz = midiToHz(note);
    const double detune = std::pow(2.0, s.detune / 1200.0 / 2.0);
    v.inc1 = hz * detune / mSampleRate;
    v.inc2 = hz / detune / mSampleRate;
    v.phase1 = 0.0;
    v.phase2 = 0.37;  // decorrelate the two oscillators

    const float sr = static_cast<float>(mSampleRate);
    v.stage = EnvStage::Attack;
    v.env = 0.0f;
    v.attackStep = 1.0f / (std::max(s.attack, 0.0005f) * sr);
    v.decayCoef = coefForSeconds(s.decay, sr);
    v.sustain = s.sustain;
    v.releaseCoef = coefForSeconds(s.release, sr);
}

void Engine::releaseVoice(Voice& v, float releaseSeconds) {
    if (!v.active) return;
    if (releaseSeconds > 0.0f) v.releaseCoef = coefForSeconds(releaseSeconds, static_cast<float>(mSampleRate));
    v.stage = EnvStage::Release;
    v.held = false;
    v.gateFrames = -1;
    v.loop = false;
    if (v.startDelay > 0) v.active = false;  // never started
}

void Engine::killVoicesUsing(const Sample* sample) {
    if (sample == nullptr) return;
    for (auto& v : mVoices) {
        if (v.active && v.sample == sample) v.active = false;
    }
}

void Engine::releaseTrackVoices(int32_t track, bool hardCut) {
    for (auto& v : mVoices) {
        if (!v.active || v.track != track) continue;
        if (hardCut) v.active = false;
        else releaseVoice(v, kSampleReleaseSeconds);
    }
}

void Engine::renderVoice(Voice& v, int32_t frames) {
    float* left = mTrackL[v.track].data();
    float* right = mTrackR[v.track].data();
    const SynthParams& synth = mTracks[v.track].synth;
    float g = 0.0f, k = 0.0f;
    if (v.kind == VoiceKind::Synth) {
        // Filter follows the envelope a little for a plucky character; updated per block.
        const float cutoff = synth.cutoff * (0.35f + 0.65f * std::max(v.env, 0.05f));
        g = SvfChannel::coefficientG(cutoff, static_cast<float>(mSampleRate));
        k = SvfChannel::coefficientK(synth.resonance);
    }

    for (int32_t i = 0; i < frames; ++i) {
        if (v.startDelay > 0) {
            --v.startDelay;
            continue;
        }
        if (v.gateFrames > 0 && --v.gateFrames == 0) {
            if (!v.oneShot) {
                v.stage = EnvStage::Release;
                v.gateFrames = -1;
            }
        }

        switch (v.stage) {
            case EnvStage::Attack:
                v.env += v.attackStep;
                if (v.env >= 1.0f) { v.env = 1.0f; v.stage = EnvStage::Decay; }
                break;
            case EnvStage::Decay:
                v.env = v.sustain + (v.env - v.sustain) * v.decayCoef;
                if (std::fabs(v.env - v.sustain) < 1e-4f) { v.env = v.sustain; v.stage = EnvStage::Sustain; }
                break;
            case EnvStage::Sustain:
                v.env = v.sustain;
                break;
            case EnvStage::Release:
                v.env *= v.releaseCoef;
                if (v.env < 1e-4f) { v.active = false; return; }
                break;
            case EnvStage::Off:
                v.active = false;
                return;
        }

        if (v.kind == VoiceKind::Sample) {
            const Sample& s = *v.sample;
            if (v.position >= static_cast<double>(s.frames)) {
                if (v.loop) {
                    v.position = std::fmod(v.position, static_cast<double>(s.frames));
                } else {
                    v.active = false;
                    return;
                }
            }
            const auto i0 = static_cast<int64_t>(v.position);
            int64_t i1 = i0 + 1;
            if (i1 >= s.frames) i1 = v.loop ? 0 : i0;
            const auto frac = static_cast<float>(v.position - static_cast<double>(i0));
            const float l0 = s.at(i0, 0), l1 = s.at(i1, 0);
            const float r0 = s.at(i0, 1), r1 = s.at(i1, 1);
            const float sl = l0 + (l1 - l0) * frac;
            const float sr = r0 + (r1 - r0) * frac;
            left[i] += sl * v.gainL * v.env;
            right[i] += sr * v.gainR * v.env;
            v.position += v.rate;
        } else {
            const float osc = 0.5f * (oscillator(v.wave, v.phase1, v.inc1) + oscillator(v.wave, v.phase2, v.inc2));
            v.phase1 += v.inc1;
            if (v.phase1 >= 1.0) v.phase1 -= 1.0;
            v.phase2 += v.inc2;
            if (v.phase2 >= 1.0) v.phase2 -= 1.0;
            const float filtered = v.filter.process(osc, g, k, 0);
            const float s = filtered * v.env;
            left[i] += s * v.gainL;
            right[i] += s * v.gainR;
        }
    }
}

void Engine::renderAudioClip(TrackState& track, double beginBeat, double framesPerBeat, int32_t frames,
                             float* left, float* right) {
    const AudioClip& clip = *track.audio;
    if (clip.lengthFrames <= 0 || clip.lengthBeats <= 0.0 || clip.data.empty()) return;
    const double clipFramesPerBeat = static_cast<double>(clip.lengthFrames) / clip.lengthBeats;
    const double fade = 0.003 * mSampleRate;
    const auto last = static_cast<double>(clip.data.size() - 1);
    for (int32_t i = 0; i < frames; ++i) {
        const double beat = beginBeat + static_cast<double>(i) / framesPerBeat;
        if (beat < 0.0) continue;
        const double local = std::fmod(beat, track.lengthBeats);
        if (local >= clip.lengthBeats) continue;
        const double inClip = local * clipFramesPerBeat;
        const double pos = static_cast<double>(clip.offsetFrames) + inClip;
        if (pos >= last) continue;
        const auto i0 = static_cast<size_t>(pos);
        const auto frac = static_cast<float>(pos - static_cast<double>(i0));
        float s = clip.data[i0] + (clip.data[i0 + 1] - clip.data[i0]) * frac;
        const double toEnd = static_cast<double>(clip.lengthFrames) - inClip;
        if (inClip < fade) s *= static_cast<float>(inClip / fade);
        else if (toEnd < fade) s *= static_cast<float>(std::max(toEnd, 0.0) / fade);
        left[i] += s;
        right[i] += s;
    }
}

void Engine::processRecording(const float* input, double beginBeat, double endBeat, double framesPerBeat,
                              int32_t frames) {
    if (mCapture != nullptr) {
        auto& data = mCapture->data;
        for (int32_t i = 0; i < frames && mCaptureWritten < static_cast<int64_t>(data.size()); ++i) {
            data[static_cast<size_t>(mCaptureWritten++)] = input != nullptr ? input[i] : 0.0f;
        }
    }

    if (mRecord.clip == nullptr || !mPlaying) return;
    const double length = mTracks[mRecord.track].lengthBeats;
    int32_t from = 0;
    if (!mRecord.started) {
        const double candidate = beginBeat <= 0.0 ? 0.0 : std::ceil(beginBeat / length) * length;
        if (candidate < beginBeat || candidate >= endBeat) return;
        mRecord.started = true;
        mRecord.startBeat = candidate;
        mRecord.clip->lengthBeats = length;
        from = std::clamp(static_cast<int32_t>((candidate - beginBeat) * framesPerBeat), 0, frames - 1);
        mEvents.push(EngineEvent{EngineEventType::AudioRecordStarted, mRecord.track, 0, nullptr});
    }

    auto& data = mRecord.clip->data;
    const auto capacity = static_cast<int64_t>(data.size());
    const double endOfTake = mRecord.startBeat + mRecord.clip->lengthBeats;
    for (int32_t i = from; i < frames; ++i) {
        if (mRecord.lengthFrames < 0) {
            const double beat = beginBeat + static_cast<double>(i) / framesPerBeat;
            if (beat >= endOfTake) mRecord.lengthFrames = mRecord.written;
        }
        if (mRecord.lengthFrames >= 0 && mRecord.written >= mRecord.lengthFrames + mInputLatencyFrames) {
            finishRecording();
            return;
        }
        if (mRecord.written >= capacity) {
            finishRecording();
            return;
        }
        data[static_cast<size_t>(mRecord.written++)] = input != nullptr ? input[i] : 0.0f;
    }
}

void Engine::finishRecording() {
    AudioClip* clip = mRecord.clip;
    if (clip == nullptr) return;
    const int64_t latency = std::min(mInputLatencyFrames, mRecord.written);
    clip->offsetFrames = latency;
    clip->lengthFrames = mRecord.lengthFrames >= 0 ? mRecord.lengthFrames : mRecord.written - latency;
    const int32_t track = mRecord.track;
    AudioClip* old = mTracks[track].audio;
    mTracks[track].audio = clip;
    mRecord = RecordJob{};
    retire(old, kRetireAudioClip);
    mEvents.push(EngineEvent{EngineEventType::AudioRecordFinished, track, clip->lengthFrames, nullptr});
}

void Engine::retire(void* ptr, RetireKind kind) {
    if (ptr == nullptr) return;
    // If the queue is ever full this leaks rather than freeing on the audio thread.
    mEvents.push(EngineEvent{EngineEventType::Retire, kind, 0, ptr});
}

void Engine::panGains(float pan, float& left, float& right) {
    const float angle = (std::clamp(pan, -1.0f, 1.0f) + 1.0f) * 0.25f * kPiF;
    // Equal-power, normalised so centre is unity on both sides.
    left = std::cos(angle) * 1.41421356f;
    right = std::sin(angle) * 1.41421356f;
    left = std::min(left, 1.0f);
    right = std::min(right, 1.0f);
}

} // namespace beatster
