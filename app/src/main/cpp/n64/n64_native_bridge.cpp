#include <jni.h>
#include <dlfcn.h>
#include <string>

namespace {
constexpr const char* kCoreSoname = "libmupen64plus_next_libretro.so";

bool has_libretro_core() {
    void* handle = dlopen(kCoreSoname, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) return false;
    void* api_version = dlsym(handle, "retro_api_version");
    void* init = dlsym(handle, "retro_init");
    void* run = dlsym(handle, "retro_run");
    const bool valid = api_version != nullptr && init != nullptr && run != nullptr;
    dlclose(handle);
    return valid;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeHasCore(JNIEnv*, jobject) {
    return has_libretro_core() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_n64_N64NativeBridge_nativeRuntimeInfo(JNIEnv* env, jobject) {
    const std::string value = std::string("OmniCore N64 Runtime 0.10.0 • Mupen64Plus-Next probe • ") +
        (has_libretro_core() ? "core ready" : "core missing");
    return env->NewStringUTF(value.c_str());
}
