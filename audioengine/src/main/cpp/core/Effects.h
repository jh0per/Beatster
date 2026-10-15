#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace beatster {

constexpr float kPiF = 3.14159265358979323846f;

/** Topology-preserving state-variable filter (Simper/Cytomic), one channel. */
class SvfChannel {
public:
    void reset() { mIc1 = mIc2 = 0.0f; }

    // mode: 0 low-pass, 1 high-pass, 2 band-pass
    float process(float x, float g, float k, int mode) {
        const float a1 = 1.0f / (1.0f + g * (g + k));
        const float a2 = g * a1;
        const float a3 = g * a2;
        const float v3 = x - mIc2;
        const float v1 = a1 * mIc1 + a2 * v3;
        const float v2 = mIc2 + a2 * mIc1 + a3 * v3;
        mIc1 = 2.0f * v1 - mIc1;
        mIc2 = 2.0f * v2 - mIc2;
        switch (mode) {
            case 1: return x - k * v1 - v2;
            case 2: return v1;
            default: return v2;
        }
    }

    static float coefficientG(float cutoffHz, float sampleRate) {
        const float nyquistSafe = std::min(cutoffHz, sampleRate * 0.45f);
        return std::tan(kPiF * std::max(nyquistSafe, 10.0f) / sampleRate);
    }

    static float coefficientK(float resonance) {
        // resonance 0..1 -> Q 0.5..~20
        return 2.0f - 1.95f * std::clamp(resonance, 0.0f, 1.0f);
    }

private:
    float mIc1 = 0.0f;
    float mIc2 = 0.0f;
};

/** Freeverb-style comb/all-pass reverb, stereo. Buffers are allocated once in prepare(). */
class Reverb {
public:
    void prepare(float sampleRate) {
        static constexpr std::array<int, kCombs> kCombTunings{1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static constexpr std::array<int, kAllPasses> kAllPassTunings{556, 441, 341, 225};
        constexpr int kStereoSpread = 23;
        const float scale = sampleRate / 44100.0f;
        for (int ch = 0; ch < 2; ++ch) {
            for (int i = 0; i < kCombs; ++i) {
                const int size = static_cast<int>(static_cast<float>(kCombTunings[i] + ch * kStereoSpread) * scale);
                mCombs[ch][i].buffer.assign(static_cast<size_t>(std::max(size, 1)), 0.0f);
                mCombs[ch][i].index = 0;
                mCombs[ch][i].store = 0.0f;
            }
            for (int i = 0; i < kAllPasses; ++i) {
                const int size = static_cast<int>(static_cast<float>(kAllPassTunings[i] + ch * kStereoSpread) * scale);
                mAllPasses[ch][i].buffer.assign(static_cast<size_t>(std::max(size, 1)), 0.0f);
                mAllPasses[ch][i].index = 0;
            }
        }
    }

    void reset() {
        for (auto& channel : mCombs) for (auto& c : channel) { std::fill(c.buffer.begin(), c.buffer.end(), 0.0f); c.store = 0.0f; }
        for (auto& channel : mAllPasses) for (auto& a : channel) std::fill(a.buffer.begin(), a.buffer.end(), 0.0f);
    }

    /** Processes in place, mixing wet into the buffers. */
    void process(float* left, float* right, int frames, float size, float damping, float mix) {
        const float feedback = 0.7f + 0.28f * std::clamp(size, 0.0f, 1.0f);
        const float damp = 0.4f * std::clamp(damping, 0.0f, 1.0f);
        const float wet = std::clamp(mix, 0.0f, 1.0f);
        const float dry = 1.0f - wet * 0.5f;
        constexpr float kInputGain = 0.015f;
        for (int i = 0; i < frames; ++i) {
            const float input = (left[i] + right[i]) * kInputGain;
            float out[2] = {0.0f, 0.0f};
            for (int ch = 0; ch < 2; ++ch) {
                for (auto& comb : mCombs[ch]) {
                    float& slot = comb.buffer[comb.index];
                    const float y = slot;
                    comb.store = y * (1.0f - damp) + comb.store * damp;
                    slot = input + comb.store * feedback;
                    if (++comb.index >= comb.buffer.size()) comb.index = 0;
                    out[ch] += y;
                }
                for (auto& ap : mAllPasses[ch]) {
                    float& slot = ap.buffer[ap.index];
                    const float buffered = slot;
                    const float y = -out[ch] + buffered;
                    slot = out[ch] + buffered * 0.5f;
                    if (++ap.index >= ap.buffer.size()) ap.index = 0;
                    out[ch] = y;
                }
            }
            left[i] = left[i] * dry + out[0] * wet * 3.0f;
            right[i] = right[i] * dry + out[1] * wet * 3.0f;
        }
    }

private:
    static constexpr int kCombs = 8;
    static constexpr int kAllPasses = 4;
    struct Comb { std::vector<float> buffer; size_t index = 0; float store = 0.0f; };
    struct AllPass { std::vector<float> buffer; size_t index = 0; };
    std::array<std::array<Comb, kCombs>, 2> mCombs{};
    std::array<std::array<AllPass, kAllPasses>, 2> mAllPasses{};
};

/** Tempo-synced stereo delay with feedback. */
class Delay {
public:
    void prepare(float sampleRate, float maxSeconds) {
        const auto size = static_cast<size_t>(sampleRate * maxSeconds) + 4;
        for (auto& b : mBuffer) b.assign(size, 0.0f);
        mWrite = 0;
        mCurrentDelay = -1.0f;
    }

    void reset() {
        for (auto& b : mBuffer) std::fill(b.begin(), b.end(), 0.0f);
        mCurrentDelay = -1.0f;
    }

    void process(float* left, float* right, int frames, float delayFrames, float feedback, float mix) {
        const size_t size = mBuffer[0].size();
        if (size < 4) return;
        const float maxDelay = static_cast<float>(size - 3);
        const float target = std::clamp(delayFrames, 1.0f, maxDelay);
        if (mCurrentDelay < 0.0f) mCurrentDelay = target;
        const float fb = std::clamp(feedback, 0.0f, 0.95f);
        const float wet = std::clamp(mix, 0.0f, 1.0f);
        float* io[2] = {left, right};
        for (int i = 0; i < frames; ++i) {
            // Glide toward the new delay time so tempo changes do not click.
            mCurrentDelay += (target - mCurrentDelay) * 0.0005f;
            float readPos = static_cast<float>(mWrite) - mCurrentDelay;
            if (readPos < 0.0f) readPos += static_cast<float>(size);
            const auto i0 = static_cast<size_t>(readPos);
            const size_t i1 = (i0 + 1) % size;
            const float frac = readPos - static_cast<float>(i0);
            for (int ch = 0; ch < 2; ++ch) {
                const auto& buf = mBuffer[ch];
                const float delayed = buf[i0] + (buf[i1] - buf[i0]) * frac;
                const float in = io[ch][i];
                // Cross-feed a little for a wider, ping-pong-ish image.
                const float other = mBuffer[1 - ch][i0];
                mBuffer[ch][mWrite] = in + (delayed * 0.8f + other * 0.2f) * fb;
                io[ch][i] = in + delayed * wet;
            }
            if (++mWrite >= size) mWrite = 0;
        }
    }

private:
    std::array<std::vector<float>, 2> mBuffer{};
    size_t mWrite = 0;
    float mCurrentDelay = -1.0f;
};

/** Saturating drive; level-compensated so turning it up does not just make it louder. */
inline void processDrive(float* left, float* right, int frames, float amount) {
    const float gain = 1.0f + std::clamp(amount, 0.0f, 1.0f) * 24.0f;
    const float norm = 1.0f / std::tanh(gain);
    for (int i = 0; i < frames; ++i) {
        left[i] = std::tanh(left[i] * gain) * norm;
        right[i] = std::tanh(right[i] * gain) * norm;
    }
}

/** Bit depth and sample-rate reduction. */
class BitCrusher {
public:
    void reset() { mCounter = 0; mHeld[0] = mHeld[1] = 0.0f; }

    void process(float* left, float* right, int frames, float bits, float downsample) {
        const float levels = std::pow(2.0f, std::clamp(bits, 2.0f, 16.0f) - 1.0f);
        const int hold = std::max(1, static_cast<int>(downsample));
        for (int i = 0; i < frames; ++i) {
            if (mCounter == 0) {
                mHeld[0] = std::round(left[i] * levels) / levels;
                mHeld[1] = std::round(right[i] * levels) / levels;
            }
            if (++mCounter >= hold) mCounter = 0;
            left[i] = mHeld[0];
            right[i] = mHeld[1];
        }
    }

private:
    int mCounter = 0;
    float mHeld[2] = {0.0f, 0.0f};
};

/** Per-track insert chain: filter -> drive -> crush -> delay -> reverb. */
struct EffectChain {
    bool filterOn = false;
    int filterMode = 0;
    float filterCutoff = 2000.0f;
    float filterResonance = 0.2f;
    SvfChannel filter[2];

    bool driveOn = false;
    float driveAmount = 0.3f;

    bool crushOn = false;
    float crushBits = 8.0f;
    float crushDownsample = 4.0f;
    BitCrusher crusher;

    bool delayOn = false;
    float delayBeats = 0.75f;
    float delayFeedback = 0.35f;
    float delayMix = 0.3f;
    Delay delay;

    bool reverbOn = false;
    float reverbSize = 0.6f;
    float reverbDamping = 0.4f;
    float reverbMix = 0.3f;
    Reverb reverb;

    void prepare(float sampleRate) {
        delay.prepare(sampleRate, 4.0f);
        reverb.prepare(sampleRate);
    }

    void process(float* left, float* right, int frames, float sampleRate, double framesPerBeat) {
        if (filterOn) {
            const float g = SvfChannel::coefficientG(filterCutoff, sampleRate);
            const float k = SvfChannel::coefficientK(filterResonance);
            for (int i = 0; i < frames; ++i) {
                left[i] = filter[0].process(left[i], g, k, filterMode);
                right[i] = filter[1].process(right[i], g, k, filterMode);
            }
        }
        if (driveOn) processDrive(left, right, frames, driveAmount);
        if (crushOn) crusher.process(left, right, frames, crushBits, crushDownsample);
        if (delayOn) {
            delay.process(left, right, frames, static_cast<float>(delayBeats * framesPerBeat), delayFeedback, delayMix);
        }
        if (reverbOn) reverb.process(left, right, frames, reverbSize, reverbDamping, reverbMix);
    }
};

} // namespace beatster
