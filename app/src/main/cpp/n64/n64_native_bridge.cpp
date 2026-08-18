#include "n64_libretro_host.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>

#include <string>

namespace {
constexpr const char* kCoreSoname = "libmupen64plus_next_libretro.so";

std::string fromJString(JNIEnv* env, jstring value) {
    if (!env || !value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

bool hasLibretroCore() {
    void* handle = dlopen(kCoreSoname, RTLD_NOW | RTLD_LOCAL);
    if (!handle) return false;
    const bool valid = dlsym(handle, "retro_api_version") != nullptr &&
        dlsym(handle, "retro_init") != nullptr &&
        dlsym(handle, "retro_load_game") != nullptr &&
        dlsym(handle, "retro_run") != nullptr;
    dlclose(handle);
    return valid;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeHasCore(JNIEnv*, jobject) {
    return hasLibretroCore() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeRuntimeInfo(JNIEnv* env, jobject) {
    const std::string value = std::string("OmniCore N64 Runtime 0.10.7 • Mupen64Plus-Next • GLES3 + AAudio host v6 • ") +
        (hasLibretroCore() ? "core ready" : "core missing");
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeStart(
    JNIEnv* env,
    jobject,
    jobject surface,
    jstring romPath,
    jstring systemDir,
    jstring saveDir,
    jstring saveRamPath,
    jstring diagnosticPath,
    jstring verificationPath,
    jstring cpuMode,
    jstring rspMode,
    jstring pakMode,
    jstring expansionPak,
    jstring aspectRatio,
    jboolean framebufferEmulation,
    jboolean threadedRenderer,
    jint internalResolution,
    jint analogDeadzonePercent,
    jint analogSensitivityPercent,
    jint audioBufferBursts) {
    if (!surface) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    omnicore::n64::RuntimeConfig config;
    config.romPath = fromJString(env, romPath);
    config.systemDir = fromJString(env, systemDir);
    config.saveDir = fromJString(env, saveDir);
    config.saveRamPath = fromJString(env, saveRamPath);
    config.diagnosticPath = fromJString(env, diagnosticPath);
    config.verificationPath = fromJString(env, verificationPath);
    config.cpuMode = fromJString(env, cpuMode);
    config.rspMode = fromJString(env, rspMode);
    config.pakMode = fromJString(env, pakMode);
    config.expansionPak = fromJString(env, expansionPak);
    config.aspectRatio = fromJString(env, aspectRatio);
    config.framebufferEmulation = framebufferEmulation == JNI_TRUE;
    config.threadedRenderer = threadedRenderer == JNI_TRUE;
    config.internalResolution = internalResolution;
    config.analogDeadzonePercent = analogDeadzonePercent;
    config.analogSensitivityPercent = analogSensitivityPercent;
    config.audioBufferBursts = audioBufferBursts;

    const bool valid = !config.romPath.empty() && !config.systemDir.empty() && !config.saveDir.empty();
    const bool started = valid && omnicore::n64::LibretroHost::instance().start(window, std::move(config));
    ANativeWindow_release(window);
    return started ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeSetAudioTargetBursts(
    JNIEnv*, jobject, jint bursts) {
    omnicore::n64::LibretroHost::instance().setAudioTargetBursts(static_cast<int>(bursts));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeRequestSaveState(
    JNIEnv* env, jobject, jstring path) {
    return omnicore::n64::LibretroHost::instance().requestSaveState(fromJString(env, path))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeRequestLoadState(
    JNIEnv* env, jobject, jstring path) {
    return omnicore::n64::LibretroHost::instance().requestLoadState(fromJString(env, path))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeRequestReset(JNIEnv*, jobject) {
    return omnicore::n64::LibretroHost::instance().requestReset() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeStop(JNIEnv*, jobject) {
    omnicore::n64::LibretroHost::instance().stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeSetPaused(
    JNIEnv*, jobject, jboolean paused) {
    omnicore::n64::LibretroHost::instance().setPaused(paused == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeIsRunning(JNIEnv*, jobject) {
    return omnicore::n64::LibretroHost::instance().running() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeLastMessage(JNIEnv* env, jobject) {
    const std::string message = omnicore::n64::LibretroHost::instance().lastMessage();
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeTelemetry(JNIEnv* env, jobject) {
    const auto telemetry = omnicore::n64::LibretroHost::instance().telemetry();
    const jfloat values[12] = {
        telemetry.averageFrameMs,
        telemetry.p95FrameMs,
        static_cast<jfloat>(telemetry.droppedFrames),
        static_cast<jfloat>(telemetry.audioUnderruns),
        static_cast<jfloat>(telemetry.sampleWindowFrames),
        telemetry.audioFillMs,
        telemetry.audioBufferMs,
        telemetry.targetFps,
        telemetry.pacingCorrectionPct,
        telemetry.presentAverageMs,
        telemetry.presentP95Ms,
        telemetry.adpfActive
    };
    jfloatArray result = env->NewFloatArray(12);
    if (result) env->SetFloatArrayRegion(result, 0, 12, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeSetButton(
    JNIEnv*, jobject, jint retroPadId, jboolean pressed) {
    if (retroPadId < 0) return;
    omnicore::n64::LibretroHost::instance().setButton(
        static_cast<unsigned>(retroPadId), pressed == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeSetAnalog(
    JNIEnv*, jobject, jfloat x, jfloat y, jfloat cX, jfloat cY) {
    omnicore::n64::LibretroHost::instance().setAnalog(x, y, cX, cY);
}
