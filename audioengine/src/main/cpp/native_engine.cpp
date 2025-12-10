#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <unistd.h>

namespace {
std::atomic<int> gSampleId{1};

void log(const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, "BeatsterNative", "%s", msg);
}
} // namespace

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
    int id = gSampleId.fetch_add(1);
    return id;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativePlaySample(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint /*sample_id*/) {
    log("play sample stub");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativePlaySampleLoop(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint /*sample_id*/) {
    log("play loop stub");
    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStopSampleLoop(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint /*sample_id*/) {
    log("stop loop stub");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStopAllSampleLoops(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    log("stop all loops stub");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeUnloadSample(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint /*sample_id*/) {
    log("unload sample stub");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStartLoopRecording(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    log("start loop recording stub");
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStopLoopRecording(
        JNIEnv* env,
        jobject /*thiz*/) {
    log("stop loop recording stub");
    return nullptr;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativePlayLoopFile(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jstring /*path*/) {
    log("play loop file stub");
    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeStopLoopFile(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    log("stop loop file stub");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativePlayTone(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jfloat /*frequency*/) {
    log("play tone stub");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_datawithart_beatster_audio_NativeAudioController_nativeShutdown(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    log("shutdown stub");
}
