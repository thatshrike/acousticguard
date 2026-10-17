#include <jni.h>

#include <aaudio/AAudio.h>

#include <cstdint>
#include <mutex>

namespace {

constexpr jint kProbeReady = 0;
constexpr jint kProbeUnsupported = 1;
constexpr jint kProbeUnavailable = 2;
constexpr int32_t kRequestedSampleRate = 16'000;
JavaVM* g_vm = nullptr;
std::mutex g_mutex;
AAudioStream* g_stream = nullptr;
jobject g_sink = nullptr;
jmethodID g_sinkMethod = nullptr;

// The status boundary never exposes or stores microphone samples. A callback
// is supplied only so the requested callback/low-latency stream configuration
// can be validated before a future capture integration is attempted.
aaudio_data_callback_result_t discardInputCallback(
    AAudioStream* /*stream*/,
    void* /*userData*/,
    void* /*audioData*/,
    int32_t /*numFrames*/) {
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t nativeInputCallback(
    AAudioStream* /*stream*/,
    void* userData,
    void* audioData,
    int32_t numFrames) {
    if (audioData == nullptr || numFrames <= 0 || g_vm == nullptr) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return AAUDIO_CALLBACK_RESULT_STOP;
        }
        attached = true;
    }
    jobject sink = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_sink != nullptr) {
            sink = env->NewLocalRef(g_sink);
            method = g_sinkMethod;
        }
    }
    if (sink != nullptr && method != nullptr) {
        const auto* samples = static_cast<const jshort*>(audioData);
        const jsize length = static_cast<jsize>(numFrames);
        jshortArray array = env->NewShortArray(length);
        if (array != nullptr) {
            env->SetShortArrayRegion(array, 0, length, samples);
            env->CallVoidMethod(sink, method, array);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(array);
        }
        env->DeleteLocalRef(sink);
    }
    if (attached) g_vm->DetachCurrentThread();
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

jint probeAaudioInput() {
    AAudioStreamBuilder* builder = nullptr;
    const aaudio_result_t createResult = AAudio_createStreamBuilder(&builder);
    if (createResult != AAUDIO_OK || builder == nullptr) {
        return kProbeUnavailable;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSampleRate(builder, kRequestedSampleRate);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setDataCallback(builder, discardInputCallback, nullptr);

    AAudioStream* stream = nullptr;
    const aaudio_result_t openResult = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);

    if (openResult != AAUDIO_OK || stream == nullptr) {
        if (openResult == AAUDIO_ERROR_UNAVAILABLE ||
            openResult == AAUDIO_ERROR_UNIMPLEMENTED) {
            return kProbeUnsupported;
        }
        return kProbeUnavailable;
    }

    const bool accepted =
        AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_INPUT &&
        AAudioStream_getSampleRate(stream) == kRequestedSampleRate &&
        AAudioStream_getFormat(stream) == AAUDIO_FORMAT_PCM_I16 &&
        AAudioStream_getChannelCount(stream) == 1 &&
        AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE &&
        AAudioStream_getPerformanceMode(stream) == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;

    AAudioStream_close(stream);
    return accepted ? kProbeReady : kProbeUnsupported;
}

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_acousticguard_NativeAudioBoundary_nativeProbe(JNIEnv*, jobject) {
    return probeAaudioInput();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_acousticguard_NativeAudioBoundary_nativeStart(JNIEnv* env, jobject, jobject sink) {
    if (env == nullptr || sink == nullptr || g_vm == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_stream != nullptr) return JNI_FALSE;
    jclass sinkClass = env->GetObjectClass(sink);
    if (sinkClass == nullptr) return JNI_FALSE;
    jmethodID method = env->GetMethodID(sinkClass, "onPcm16", "([S)V");
    env->DeleteLocalRef(sinkClass);
    if (method == nullptr) {
        env->ExceptionClear();
        return JNI_FALSE;
    }

    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) return JNI_FALSE;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSampleRate(builder, kRequestedSampleRate);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setDataCallback(builder, nativeInputCallback, nullptr);
    AAudioStream* stream = nullptr;
    const aaudio_result_t openResult = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (openResult != AAUDIO_OK || stream == nullptr ||
        AAudioStream_getSharingMode(stream) != AAUDIO_SHARING_MODE_EXCLUSIVE) {
        if (stream != nullptr) AAudioStream_close(stream);
        return JNI_FALSE;
    }
    g_sink = env->NewGlobalRef(sink);
    g_sinkMethod = method;
    g_stream = stream;
    const aaudio_result_t startResult = AAudioStream_requestStart(g_stream);
    if (startResult != AAUDIO_OK) {
        AAudioStream_close(g_stream);
        g_stream = nullptr;
        env->DeleteGlobalRef(g_sink);
        g_sink = nullptr;
        g_sinkMethod = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_acousticguard_NativeAudioBoundary_nativeStop(JNIEnv* env, jobject) {
    AAudioStream* stream = nullptr;
    jobject sink = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        stream = g_stream;
        sink = g_sink;
        g_stream = nullptr;
        g_sink = nullptr;
        g_sinkMethod = nullptr;
    }
    if (stream != nullptr) {
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
    }
    if (env != nullptr && sink != nullptr) env->DeleteGlobalRef(sink);
}
