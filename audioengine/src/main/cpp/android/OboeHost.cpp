#include "OboeHost.h"

#include <android/log.h>
#include <sys/system_properties.h>

#include <algorithm>
#include <cstring>

namespace beatster {

namespace {

constexpr const char* kTag = "BeatsterAudio";
constexpr int32_t kMaxCallbackFrames = 8192;

bool isEmulator() {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("ro.kernel.qemu", value);
    return value[0] == '1';
}

} // namespace

OboeHost::~OboeHost() { close(); }

int32_t OboeHost::openOutput() {
    std::lock_guard<std::mutex> lock(mLock);
    mInputBuffer.assign(kMaxCallbackFrames, 0.0f);
    mFloatOut.assign(kMaxCallbackFrames * kOutputChannels, 0.0f);
    return openOutputLocked() ? mSampleRate : 0;
}

bool OboeHost::openOutputLocked() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setChannelConversionAllowed(true)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (mSampleRate > 0) {
        // Reopening after a device change: keep the engine's rate and let Oboe convert.
        builder.setSampleRate(mSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    }
    if (isEmulator()) {
        builder.setAudioApi(oboe::AudioApi::OpenSLES)->setPerformanceMode(oboe::PerformanceMode::None);
    }

    oboe::Result result = builder.openStream(mOutput);
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Output open failed: %s", oboe::convertToText(result));
        mOutput.reset();
        return false;
    }
    if (mSampleRate == 0) mSampleRate = mOutput->getSampleRate();
    // Two bursts is the usual sweet spot between latency and glitches.
    mOutput->setBufferSizeInFrames(mOutput->getFramesPerBurst() * 2);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Output: %d Hz, %d ch, %s, burst %d", mOutput->getSampleRate(),
                        mOutput->getChannelCount(), oboe::convertToText(mOutput->getFormat()),
                        mOutput->getFramesPerBurst());
    if (mRunning) mOutput->requestStart();
    return true;
}

bool OboeHost::openInputLocked() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setChannelConversionAllowed(true)
        ->setInputPreset(oboe::InputPreset::Unprocessed)
        ->setSampleRate(mSampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    if (isEmulator()) {
        builder.setAudioApi(oboe::AudioApi::OpenSLES)->setPerformanceMode(oboe::PerformanceMode::None);
    }
    oboe::Result result = builder.openStream(mInput);
    if (result != oboe::Result::OK) {
        // Some devices reject Unprocessed; fall back to the default preset.
        builder.setInputPreset(oboe::InputPreset::VoiceRecognition);
        result = builder.openStream(mInput);
    }
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Input open failed: %s", oboe::convertToText(result));
        mInput.reset();
        return false;
    }
    if (mInput->requestStart() != oboe::Result::OK) {
        mInput->close();
        mInput.reset();
        return false;
    }
    mDrainInput = true;
    mInputReady.store(true, std::memory_order_release);
    return true;
}

void OboeHost::closeInputLocked() {
    mInputReady.store(false, std::memory_order_release);
    if (mInput) {
        // The output callback may be mid-read; a blocking stop() of the output guarantees it
        // has returned before the input stream goes away.
        const bool outputRunning = mOutput && mRunning;
        if (outputRunning) mOutput->stop();
        mInput->stop();
        mInput->close();
        mInput.reset();
        if (outputRunning) mOutput->start();
    }
}

void OboeHost::close() {
    std::lock_guard<std::mutex> lock(mLock);
    mEngine.store(nullptr, std::memory_order_release);
    closeInputLocked();
    if (mOutput) {
        mOutput->requestStop();
        mOutput->close();
        mOutput.reset();
    }
}

bool OboeHost::setRunning(bool running) {
    std::lock_guard<std::mutex> lock(mLock);
    mRunning = running;
    if (!mOutput) return openOutputLocked();
    const oboe::Result r = running ? mOutput->requestStart() : mOutput->requestStop();
    if (mInput) {
        if (running) {
            mInput->requestStart();
            mDrainInput = true;
        } else {
            mInput->requestStop();
        }
    }
    return r == oboe::Result::OK;
}

bool OboeHost::enableInput(bool enable) {
    std::lock_guard<std::mutex> lock(mLock);
    mInputWanted = enable;
    if (enable) return mInput != nullptr || openInputLocked();
    closeInputLocked();
    return true;
}

double OboeHost::outputLatencyMillis() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mOutput) return -1.0;
    auto result = mOutput->calculateLatencyMillis();
    return result ? result.value() : -1.0;
}

double OboeHost::roundTripLatencyMillis() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mOutput || !mInput) return -1.0;
    auto out = mOutput->calculateLatencyMillis();
    auto in = mInput->calculateLatencyMillis();
    if (!out || !in) return -1.0;
    return out.value() + in.value();
}

oboe::DataCallbackResult OboeHost::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    Engine* engine = mEngine.load(std::memory_order_acquire);
    const int32_t channels = stream->getChannelCount();
    const int32_t frames = std::min(numFrames, kMaxCallbackFrames);

    const float* input = nullptr;
    if (mInputReady.load(std::memory_order_acquire) && mInput) {
        if (mDrainInput) {
            // Throw away whatever piled up before we started listening.
            while (true) {
                auto drained = mInput->read(mInputBuffer.data(), kMaxCallbackFrames, 0);
                if (!drained || drained.value() <= 0) break;
            }
            mDrainInput = false;
        }
        auto read = mInput->read(mInputBuffer.data(), frames, 0);
        const int32_t got = read ? std::max(read.value(), 0) : 0;
        if (got < frames) std::fill(mInputBuffer.begin() + got, mInputBuffer.begin() + frames, 0.0f);
        // If the input runs ahead (clock drift), drop the excess so latency does not creep up.
        auto available = mInput->getAvailableFrames();
        if (available && available.value() > frames + mInput->getFramesPerBurst() * 2) {
            const int32_t excess = std::min(available.value() - frames, kMaxCallbackFrames);
            mInput->read(mFloatOut.data(), excess, 0);  // mFloatOut is free scratch at this point
        }
        input = mInputBuffer.data();
    }

    float* out = mFloatOut.data();
    if (engine != nullptr) {
        engine->render(out, input, frames);
    } else {
        std::fill_n(out, frames * kOutputChannels, 0.0f);
    }

    // The engine renders stereo float; adapt to whatever the stream actually is.
    if (stream->getFormat() == oboe::AudioFormat::Float) {
        auto* dst = static_cast<float*>(audioData);
        if (channels == 2) {
            std::memcpy(dst, out, sizeof(float) * frames * 2);
        } else {
            for (int32_t i = 0; i < frames; ++i) {
                for (int32_t c = 0; c < channels; ++c) dst[i * channels + c] = out[i * 2 + std::min(c, 1)];
            }
        }
    } else {
        auto* dst = static_cast<int16_t*>(audioData);
        for (int32_t i = 0; i < frames; ++i) {
            for (int32_t c = 0; c < channels; ++c) {
                const float v = std::clamp(out[i * 2 + std::min(c, 1)], -1.0f, 1.0f);
                dst[i * channels + c] = static_cast<int16_t>(v * 32767.0f);
            }
        }
    }
    if (frames < numFrames) {
        const size_t bytesPerFrame = stream->getBytesPerFrame();
        std::memset(static_cast<uint8_t*>(audioData) + frames * bytesPerFrame, 0, (numFrames - frames) * bytesPerFrame);
    }
    return oboe::DataCallbackResult::Continue;
}

void OboeHost::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Stream closed (%s), reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> lock(mLock);
    if (stream == mOutput.get()) {
        mOutput.reset();
        const bool hadInput = mInput != nullptr;
        if (hadInput) closeInputLocked();
        if (openOutputLocked() && hadInput && mInputWanted) openInputLocked();
    }
}

} // namespace beatster
