#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <oboe/Oboe.h>
#include <oboe/Utilities.h>
#include <unistd.h>
#include <string>
#include <cmath>
#include <sys/system_properties.h>
#include <vector>

namespace {

// Log tag for the native module
constexpr const char* kLogTag = "BeatsterNative";
constexpr double kPi = M_PI;
constexpr double kTwoPi = 2.0 * kPi;

void log(const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", msg);
}

bool isEmulator() {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("ro.kernel.qemu", value);
    return value[0] == '1';
}

/**
 * A simple sine wave generator.
 */
class SineWave {
public:
    void setFrequency(float frequency, int32_t sampleRate) {
        mPhaseIncrement.store(kTwoPi * static_cast<double>(frequency) / sampleRate);
    }

    void setPlaying(bool isPlaying) {
        mIsPlaying.store(isPlaying);
    }

    // Renders the sine wave into a float buffer
    void render(std::vector<float>& buffer, int32_t channelCount) {
        if (!mIsPlaying.load()) {
            std::fill(buffer.begin(), buffer.end(), 0.0f);
            return;
        }

        double phase = mPhase.load();
        const double phaseIncrement = mPhaseIncrement.load();
        const int32_t numFrames = buffer.size() / channelCount;

        for (int i = 0; i < numFrames; ++i) {
            float value = sin(phase) * 0.5f;
            for (int j = 0; j < channelCount; ++j) {
                buffer[i * channelCount + j] = value;
            }
            phase += phaseIncrement;
            if (phase > kTwoPi) {
                phase -= kTwoPi;
            }
        }
        mPhase.store(phase);
    }

private:
    std::atomic<bool> mIsPlaying{false};
    std::atomic<double> mPhase{0.0};
    std::atomic<double> mPhaseIncrement{0.0};
};

SineWave gSineWave;
std::vector<float> gFloatBuffer; // A reusable buffer for float data

/**
 * The main Oboe callback. It now handles both Float and I16 formats.
 */
class BeatsterAudioCallback : public oboe::AudioStreamCallback {
public:
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override {

        const int32_t channelCount = audioStream->getChannelCount();
        const int32_t totalSamples = numFrames * channelCount;

        // Ensure our float buffer is large enough
        if (gFloatBuffer.size() < totalSamples) {
            gFloatBuffer.resize(totalSamples);
        }

        // Render the sine wave into the float buffer first
        gSineWave.render(gFloatBuffer, channelCount);

        // Now, convert the float buffer to the stream's actual format
        oboe::AudioFormat format = audioStream->getFormat();
        if (format == oboe::AudioFormat::Float) {
            memcpy(audioData, gFloatBuffer.data(), totalSamples * sizeof(float));
        } else if (format == oboe::AudioFormat::I16) {
            auto* output = static_cast<int16_t*>(audioData);
            oboe::convertFloatToPcm16(gFloatBuffer.data(), output, totalSamples);
        } else {
            log("Unsupported audio format");
            return oboe::DataCallbackResult::Stop;
        }

        return oboe::DataCallbackResult::Continue;
    }
};

class AudioEngine {
public:
    AudioEngine() {
        startStream();
    }

    ~AudioEngine() {
        stopStream();
    }

    void playTone(float frequency) {
        if (mStream) {
            gSineWave.setFrequency(frequency, mStream->getSampleRate());
            gSineWave.setPlaying(true);
        }
    }

    void stopTone() {
        gSineWave.setPlaying(false);
    }

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    BeatsterAudioCallback mCallback;

    void startStream() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Unspecified) // Let Oboe choose the best format
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setCallback(&mCallback);

        if (isEmulator()) {
            builder.setAudioApi(oboe::AudioApi::OpenSLES)
                   ->setPerformanceMode(oboe::PerformanceMode::None);
        }

        oboe::Result result = builder.openStream(mStream);

        if (result == oboe::Result::OK) {
            log("Oboe stream opened successfully.");
            log((std::string("Format: ") + oboe::convertToText(mStream->getFormat())).c_str());
            mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);
            mStream->start();
        } else {
            log((std::string("Failed to open Oboe stream: ") + oboe::convertToText(result)).c_str());
        }
    }

    void stopStream() {
        if (mStream) {
            mStream->stop();
            mStream->close();
            mStream.reset();
            log("Oboe stream closed.");
        }
    }
};

AudioEngine* gEngine = nullptr;
std::atomic<int> gSampleId{1};
std::atomic<bool> isTonePlaying{false};

} // namespace

// --- JNI Functions (Implementation) ---

extern "C"
JNIEXPORT jint JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeLoadSample(
        JNIEnv* env,
        jobject /*thiz*/,
        jint fd,
        jstring /*label*/) {
    if (fd >= 0) {
        close(fd);
    }
    return gSampleId.fetch_add(1);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativePlaySample(JNIEnv*, jobject, jint) {
    log("play sample stub");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativePlaySampleLoop(JNIEnv*, jobject, jint) {
    log("play loop stub");
    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStopSampleLoop(JNIEnv*, jobject, jint) {
    log("stop loop stub");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStopAllSampleLoops(JNIEnv*, jobject) {
    log("stop all loops stub");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeUnloadSample(JNIEnv*, jobject, jint) {
    log("unload sample stub");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStartLoopRecording(JNIEnv*, jobject) {
    log("start loop recording stub");
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStopLoopRecording(JNIEnv*, jobject) {
    log("stop loop recording stub");
    return nullptr;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativePlayLoopFile(JNIEnv*, jobject, jstring) {
    log("play loop file stub");
    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStopLoopFile(JNIEnv*, jobject) {
    log("stop loop file stub");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativePlayTone(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jfloat frequency) {
    if (gEngine) {
        if (isTonePlaying.exchange(!isTonePlaying.load())) {
            gEngine->stopTone();
        } else {
            gEngine->playTone(frequency);
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeInit(JNIEnv*, jobject) {
    if (!gEngine) {
        gEngine = new AudioEngine();
        log("Oboe AudioEngine initialized.");
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeShutdown(JNIEnv*, jobject) {
    if (gEngine) {
        delete gEngine;
        gEngine = nullptr;
        log("Oboe AudioEngine shutdown.");
    }
}