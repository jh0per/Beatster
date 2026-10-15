// JNI surface for com.datawithart.beatster.engine.NativeEngine (a Kotlin object with
// @JvmStatic external functions). Every entry point is coarse: one call per user action.

#include <jni.h>

#include <memory>
#include <mutex>
#include <vector>

#include "../core/EngineController.h"
#include "OboeHost.h"

using beatster::EngineController;
using beatster::NoteEvent;
using beatster::OboeHost;

namespace {

std::mutex gLifecycle;  // guards creation/destruction; the controller has its own lock
std::unique_ptr<OboeHost> gHost;
std::unique_ptr<EngineController> gController;

EngineController* controller() { return gController.get(); }

std::vector<float> toVector(JNIEnv* env, jfloatArray array) {
    if (array == nullptr) return {};
    const jsize n = env->GetArrayLength(array);
    std::vector<float> out(static_cast<size_t>(n));
    env->GetFloatArrayRegion(array, 0, n, out.data());
    return out;
}

jfloatArray toJava(JNIEnv* env, const std::vector<float>& data) {
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(data.size()));
    if (result != nullptr && !data.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(data.size()), data.data());
    }
    return result;
}

} // namespace

#define BEATSTER_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_datawithart_beatster_engine_NativeEngine_##name

BEATSTER_JNI(jboolean, nativeCreate)(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gLifecycle);
    if (gController) return JNI_TRUE;
    auto host = std::make_unique<OboeHost>();
    const int32_t rate = host->openOutput();
    if (rate <= 0) return JNI_FALSE;
    gController = std::make_unique<EngineController>(rate);
    host->attach(&gController->engine());
    gHost = std::move(host);
    return JNI_TRUE;
}

BEATSTER_JNI(void, nativeDestroy)(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gLifecycle);
    if (gHost) gHost->close();  // stops the callback before the engine goes away
    gHost.reset();
    gController.reset();
}

BEATSTER_JNI(jboolean, nativeSetRunning)(JNIEnv*, jclass, jboolean running) {
    return gHost && gHost->setRunning(running == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

BEATSTER_JNI(jboolean, nativeEnableInput)(JNIEnv*, jclass, jboolean enable) {
    if (!gHost || !controller()) return JNI_FALSE;
    const bool ok = gHost->enableInput(enable == JNI_TRUE);
    return ok ? JNI_TRUE : JNI_FALSE;
}

BEATSTER_JNI(jint, nativeSampleRate)(JNIEnv*, jclass) {
    return controller() ? controller()->sampleRate() : 0;
}

// --- pads ---

BEATSTER_JNI(jboolean, nativeSetPadSample)(JNIEnv* env, jclass, jint pad, jfloatArray pcm, jint channels, jint rate) {
    if (!controller() || channels <= 0) return JNI_FALSE;
    const std::vector<float> data = toVector(env, pcm);
    const auto frames = static_cast<int64_t>(data.size()) / channels;
    return controller()->setPadSample(pad, data.data(), frames, channels, rate) ? JNI_TRUE : JNI_FALSE;
}

BEATSTER_JNI(void, nativeClearPad)(JNIEnv*, jclass, jint pad) {
    if (controller()) controller()->clearPad(pad);
}

BEATSTER_JNI(void, nativeSetPadParam)(JNIEnv*, jclass, jint pad, jint param, jfloat value) {
    if (controller()) controller()->setPadParam(pad, param, value);
}

// --- playing ---

BEATSTER_JNI(void, nativeNoteOn)(JNIEnv*, jclass, jint track, jint note, jfloat velocity) {
    if (controller()) controller()->noteOn(track, note, velocity);
}

BEATSTER_JNI(void, nativeNoteOff)(JNIEnv*, jclass, jint track, jint note) {
    if (controller()) controller()->noteOff(track, note);
}

BEATSTER_JNI(void, nativePadLoop)(JNIEnv*, jclass, jint track, jint pad, jboolean on) {
    if (controller()) controller()->padLoop(track, pad, on == JNI_TRUE);
}

BEATSTER_JNI(void, nativeAllNotesOff)(JNIEnv*, jclass) {
    if (controller()) controller()->allNotesOff();
}

// --- tracks ---

BEATSTER_JNI(void, nativeSetTrackType)(JNIEnv*, jclass, jint track, jint type) {
    if (controller()) controller()->setTrackType(track, type);
}

BEATSTER_JNI(void, nativeSetTrackParam)(JNIEnv*, jclass, jint track, jint param, jfloat value) {
    if (controller()) controller()->setTrackParam(track, param, value);
}

BEATSTER_JNI(void, nativeSetTrackLength)(JNIEnv*, jclass, jint track, jdouble beats) {
    if (controller()) controller()->setTrackLength(track, beats);
}

BEATSTER_JNI(void, nativeSetPattern)(JNIEnv* env, jclass, jint track, jdoubleArray starts, jdoubleArray lengths,
                                     jintArray notes, jfloatArray velocities, jintArray flags) {
    if (!controller()) return;
    const jsize n = env->GetArrayLength(starts);
    if (env->GetArrayLength(lengths) != n || env->GetArrayLength(notes) != n ||
        env->GetArrayLength(velocities) != n || env->GetArrayLength(flags) != n) {
        return;
    }
    std::vector<jdouble> s(static_cast<size_t>(n)), l(static_cast<size_t>(n));
    std::vector<jint> no(static_cast<size_t>(n)), fl(static_cast<size_t>(n));
    std::vector<jfloat> ve(static_cast<size_t>(n));
    env->GetDoubleArrayRegion(starts, 0, n, s.data());
    env->GetDoubleArrayRegion(lengths, 0, n, l.data());
    env->GetIntArrayRegion(notes, 0, n, no.data());
    env->GetFloatArrayRegion(velocities, 0, n, ve.data());
    env->GetIntArrayRegion(flags, 0, n, fl.data());
    std::vector<NoteEvent> events(static_cast<size_t>(n));
    for (size_t i = 0; i < events.size(); ++i) {
        events[i] = NoteEvent{s[i], l[i], no[i], ve[i], fl[i]};
    }
    controller()->setPattern(track, std::move(events));
}

BEATSTER_JNI(jboolean, nativeSetAudioClip)(JNIEnv* env, jclass, jint track, jfloatArray pcm, jint rate,
                                           jdouble lengthBeats) {
    if (!controller()) return JNI_FALSE;
    const std::vector<float> data = toVector(env, pcm);
    return controller()->setAudioClip(track, data.data(), static_cast<int64_t>(data.size()), rate, lengthBeats)
               ? JNI_TRUE : JNI_FALSE;
}

BEATSTER_JNI(void, nativeClearAudioClip)(JNIEnv*, jclass, jint track) {
    if (controller()) controller()->clearAudioClip(track);
}

BEATSTER_JNI(jfloatArray, nativeGetAudioClip)(JNIEnv* env, jclass, jint track) {
    if (!controller()) return nullptr;
    double beats = 0.0;
    const std::vector<float> clip = controller()->copyAudioClip(track, &beats);
    if (clip.empty()) return nullptr;
    return toJava(env, clip);
}

// --- transport ---

BEATSTER_JNI(void, nativePlay)(JNIEnv*, jclass, jdouble countInBeats) {
    if (controller()) controller()->play(countInBeats);
}

BEATSTER_JNI(void, nativeStop)(JNIEnv*, jclass) {
    if (controller()) controller()->stop();
}

BEATSTER_JNI(void, nativeSetTempo)(JNIEnv*, jclass, jdouble bpm) {
    if (controller()) controller()->setTempo(bpm);
}

BEATSTER_JNI(void, nativeSetMetronome)(JNIEnv*, jclass, jboolean on, jfloat volume) {
    if (controller()) controller()->setMetronome(on == JNI_TRUE, volume);
}

// --- recording ---

BEATSTER_JNI(jboolean, nativeArmAudioRecord)(JNIEnv*, jclass, jint track, jdouble lengthBeats, jdouble extraLatencyMs) {
    if (!controller() || !gHost || !gHost->inputEnabled()) return JNI_FALSE;
    const double measured = gHost->roundTripLatencyMillis();
    // Without a measurement assume a typical Android round trip.
    const double latencyMs = (measured > 0.0 ? measured : 40.0) + extraLatencyMs;
    controller()->setInputLatencyFrames(static_cast<int64_t>(latencyMs * controller()->sampleRate() / 1000.0));
    return controller()->armAudioRecord(track, lengthBeats) ? JNI_TRUE : JNI_FALSE;
}

BEATSTER_JNI(void, nativeCancelAudioRecord)(JNIEnv*, jclass) {
    if (controller()) controller()->cancelAudioRecord();
}

BEATSTER_JNI(jboolean, nativeStartCapture)(JNIEnv*, jclass, jfloat maxSeconds) {
    if (!controller() || !gHost || !gHost->inputEnabled()) return JNI_FALSE;
    return controller()->startCapture(maxSeconds) ? JNI_TRUE : JNI_FALSE;
}

BEATSTER_JNI(jfloatArray, nativeStopCapture)(JNIEnv* env, jclass) {
    if (!controller()) return nullptr;
    const std::vector<float> captured = controller()->stopCapture(500);
    if (captured.empty()) return nullptr;
    return toJava(env, captured);
}

// --- status ---

/**
 * Fills `out` with: [0] playing, [1] beat, [2] tempo, [3] output latency ms, [4] input peak,
 * [5] output peak, [6] recording track (-1 none), [7] recording rolling (0/1),
 * [8] captured frames, [9] active voices.
 */
BEATSTER_JNI(void, nativeGetStatus)(JNIEnv* env, jclass, jdoubleArray out) {
    if (!controller() || out == nullptr || env->GetArrayLength(out) < 10) return;
    beatster::Engine& e = controller()->engine();
    const jdouble values[10] = {
        e.isPlaying() ? 1.0 : 0.0,
        e.beatPosition(),
        e.tempo(),
        gHost ? gHost->outputLatencyMillis() : -1.0,
        e.inputPeak(),
        e.outputPeak(),
        static_cast<double>(e.recordingTrack()),
        e.recordingActive() ? 1.0 : 0.0,
        static_cast<double>(e.captureFrames()),
        static_cast<double>(e.activeVoices()),
    };
    env->SetDoubleArrayRegion(out, 0, 10, values);
}

/** Pairs of (event type, track) since the last call; see EngineEventType. */
BEATSTER_JNI(jintArray, nativePollEvents)(JNIEnv* env, jclass) {
    std::vector<jint> flat;
    if (controller()) {
        for (const auto& e : controller()->pollEvents()) {
            flat.push_back(static_cast<jint>(e.type));
            flat.push_back(e.track);
        }
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(flat.size()));
    if (result != nullptr && !flat.empty()) env->SetIntArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}
