#include <jni.h>

#include <android/api-level.h>
#include <dlfcn.h>
#include <unistd.h>

#include <cstdio>
#include <string>

namespace {

constexpr const char* kFoundationVersion = "0.11.0-ps2-foundation";

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

bool hasVulkanLoader() {
    void* handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
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
    return makeString(
        env,
        "OmniCore PS2 Foundation | isolated runtime probe | backend=none | smartperf=contract-v1");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_ps2_PS2NativeBridge_nativeProbe(
    JNIEnv* env,
    jobject /* thiz */) {
    const int api = android_get_device_api_level();
    const long pageSize = sysconf(_SC_PAGESIZE);
    const int pointerBits = static_cast<int>(sizeof(void*) * 8u);
    const bool vulkan = hasVulkanLoader();

    char buffer[320]{};
    std::snprintf(
        buffer,
        sizeof(buffer),
        "api=%d;ptr=%d;page=%ld;arch=%s;vulkan=%d;gles3=1;version=%s",
        api,
        pointerBits,
        pageSize,
        architectureName().c_str(),
        vulkan ? 1 : 0,
        kFoundationVersion);
    return makeString(env, buffer);
}
