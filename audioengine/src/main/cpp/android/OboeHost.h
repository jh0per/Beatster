#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include <oboe/Oboe.h>

#include "../core/Engine.h"

namespace beatster {

/**
 * Owns the Oboe streams and drives Engine::render() from the output callback. The
 * microphone, when enabled, is an unbuffered input stream read without blocking inside
 * the same callback (Oboe's full-duplex pattern), so recorded audio and playback share
 * one clock.
 */
class OboeHost : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    OboeHost() = default;
    ~OboeHost() override;

    /** Opens and starts the output stream. Returns its sample rate, or 0 on failure. */
    int32_t openOutput();
    void attach(Engine* engine) { mEngine.store(engine, std::memory_order_release); }
    void close();

    bool setRunning(bool running);
    bool enableInput(bool enable);
    bool inputEnabled() const { return mInputWanted; }

    /** Output latency (what you hear lags what is rendered), or -1 if unknown. */
    double outputLatencyMillis();
    /** Output plus input latency: how late a played sound comes back through the mic. */
    double roundTripLatencyMillis();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openOutputLocked();
    bool openInputLocked();
    void closeInputLocked();

    std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mOutput;
    std::shared_ptr<oboe::AudioStream> mInput;
    std::atomic<Engine*> mEngine{nullptr};
    std::atomic<bool> mInputReady{false};
    bool mInputWanted = false;
    bool mRunning = true;
    int32_t mSampleRate = 0;
    bool mDrainInput = true;

    std::vector<float> mInputBuffer;
    std::vector<float> mFloatOut;
};

} // namespace beatster
