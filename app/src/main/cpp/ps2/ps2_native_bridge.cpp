#include <jni.h>

#include <android/api-level.h>
#include <dlfcn.h>
#include <unistd.h>

#include <cstdio>
#include <string>

namespace {

constexpr const char* kFoundationVersion = "0.11.0-ps2-bringup1";
constexpr const char* kPlayRevision = "04bde0df87ee7c0e2f0151b51bb2cc22c88541da";

std::string architectureName() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

bool canLoad(const char* library) {
    void* handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (!handle) return false;
    dlclose(handle);
    return true;
}

jstring makeString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_ps2_PS2NativeBridge_nativeDescriptor(
    JNIEnv* env,
    jobject /* thiz */) {
    const bool playReady = canLoad("libPlay.so");
    std::string result = "OmniCore PS2 Bring-up 1 | isolated adapter | backend=";
    result += playReady ? "Play-ready@" : "Play-not-packaged@";
    result += kPlayRevision;
    result += " | smartperf=contract-v1";
    return makeString(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_ps2_PS2NativeBridge_nativeProbe(
    JNIEnv* env,
    jobject /* thiz */) {
    const int api = android_get_device_api_level();
    const long pageSize = sysconf(_SC_PAGESIZE);
    const int pointerBits = static_cast<int>(sizeof(void*) * 8u);
    const bool vulkan = canLoad("libvulkan.so");
    const bool playReady = canLoad("libPlay.so");

    char buffer[420]{};
    std::snprintf(
        buffer,
        sizeof(buffer),
        "api=%d;ptr=%d;page=%ld;arch=%s;vulkan=%d;gles3=1;play=%d;version=%s;playrev=%s",
        api,
        pointerBits,
        pageSize,
        architectureName().c_str(),
        vulkan ? 1 : 0,
        playReady ? 1 : 0,
        kFoundationVersion,
        kPlayRevision);
    return makeString(env, buffer);
}
