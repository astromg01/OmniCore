#include <jni.h>

#include <android/api-level.h>
#include <dlfcn.h>
#include <unistd.h>

#include <cstdio>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>

namespace {

constexpr const char* kFoundationVersion = "0.11.5-ps2-alpha6";
constexpr const char* kPcsx2Revision = "7f0ae7a6c689b5b36eccc61b7adb480f65c7a3a3";

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

void* openLoadedCore() {
    constexpr const char* kCoreNames[] = {
        "libemucore_4k.so",
        "libemucore_16k.so",
    };

#ifdef RTLD_NOLOAD
    for (const char* name : kCoreNames) {
        if (void* handle = dlopen(name, RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD)) {
            return handle;
        }
    }
#endif

    // samplePcsx2Performance() is only called once the VM is active, so the
    // emucore should already be resident. This second pass is a compatibility
    // fallback for Android linkers which do not expose RTLD_NOLOAD reliably.
    for (const char* name : kCoreNames) {
        if (void* handle = dlopen(name, RTLD_NOW | RTLD_LOCAL)) {
            return handle;
        }
    }
    return nullptr;
}

template <typename T>
T resolve(void* handle, const char* symbol) {
    return reinterpret_cast<T>(dlsym(handle, symbol));
}

struct Pcsx2PerfApi {
    using FloatFn = float (*)();
    using DoubleFn = double (*)();

    void* handle = nullptr;
    DoubleFn eeUsage = nullptr;
    DoubleFn eeTime = nullptr;
    FloatFn gsUsage = nullptr;
    FloatFn gsTime = nullptr;
    FloatFn gsBackUsage = nullptr;
    FloatFn gsBackTime = nullptr;
    FloatFn vuUsage = nullptr;
    FloatFn vuTime = nullptr;
    FloatFn gpuUsage = nullptr;
    FloatFn gpuTime = nullptr;
    FloatFn frameAverage = nullptr;
    FloatFn frameMinimum = nullptr;
    FloatFn frameMaximum = nullptr;
    DoubleFn vsInvocations = nullptr;
    DoubleFn psInvocations = nullptr;
    bool ready = false;

    bool ensure() {
        if (ready) return true;
        if (!handle) handle = openLoadedCore();
        if (!handle) return false;

        // PCSX2 PerformanceMetrics functions are C++ namespace functions in the
        // pinned ARM64 emucore. Resolve them dynamically so OmniCore keeps the
        // official upstream binary untouched and can fail soft if a future pin
        // changes symbol visibility/ABI.
        eeUsage = resolve<DoubleFn>(handle, "_ZN18PerformanceMetrics17GetCPUThreadUsageEv");
        eeTime = resolve<DoubleFn>(handle, "_ZN18PerformanceMetrics23GetCPUThreadAverageTimeEv");
        gsUsage = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics16GetGSThreadUsageEv");
        gsTime = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics22GetGSThreadAverageTimeEv");
        gsBackUsage = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics20GetGSBackThreadUsageEv");
        gsBackTime = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics26GetGSBackThreadAverageTimeEv");
        vuUsage = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics16GetVUThreadUsageEv");
        vuTime = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics22GetVUThreadAverageTimeEv");
        gpuUsage = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics11GetGPUUsageEv");
        gpuTime = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics17GetGPUAverageTimeEv");
        frameAverage = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics19GetAverageFrameTimeEv");
        frameMinimum = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics19GetMinimumFrameTimeEv");
        frameMaximum = resolve<FloatFn>(handle, "_ZN18PerformanceMetrics19GetMaximumFrameTimeEv");
        vsInvocations = resolve<DoubleFn>(handle, "_ZN18PerformanceMetrics26GetGPUAverageVSInvocationsEv");
        psInvocations = resolve<DoubleFn>(handle, "_ZN18PerformanceMetrics26GetGPUAveragePSInvocationsEv");

        ready = eeUsage && eeTime && gsUsage && gsTime && gsBackUsage && gsBackTime &&
                vuUsage && vuTime && gpuUsage && gpuTime && frameAverage && frameMinimum &&
                frameMaximum && vsInvocations && psInvocations;
        return ready;
    }
};

Pcsx2PerfApi g_perfApi;
std::mutex g_perfMutex;

jstring makeString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string perfSnapshot() {
    std::lock_guard<std::mutex> lock(g_perfMutex);
    if (!g_perfApi.ensure()) {
        return "ok=0;source=pcsx2-symbols-unavailable";
    }

    std::ostringstream out;
    out.setf(std::ios::fixed);
    out << std::setprecision(3)
        << "ok=1;source=pcsx2-performance-metrics"
        << ";eePct=" << g_perfApi.eeUsage()
        << ";eeMs=" << g_perfApi.eeTime()
        << ";vuPct=" << g_perfApi.vuUsage()
        << ";vuMs=" << g_perfApi.vuTime()
        << ";gsPct=" << g_perfApi.gsUsage()
        << ";gsMs=" << g_perfApi.gsTime()
        << ";gsbPct=" << g_perfApi.gsBackUsage()
        << ";gsbMs=" << g_perfApi.gsBackTime()
        << ";gpuPct=" << g_perfApi.gpuUsage()
        << ";gpuMs=" << g_perfApi.gpuTime()
        << ";frameAvgMs=" << g_perfApi.frameAverage()
        << ";frameMinMs=" << g_perfApi.frameMinimum()
        << ";frameMaxMs=" << g_perfApi.frameMaximum()
        << ";vs=" << g_perfApi.vsInvocations()
        << ";ps=" << g_perfApi.psInvocations();
    return out.str();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_ps2_PS2NativeBridge_nativeDescriptor(
    JNIEnv* env,
    jobject /* thiz */) {
    const bool core4k = canLoad("libemucore_4k.so");
    const bool core16k = canLoad("libemucore_16k.so");
    std::string result = "OmniCore PS2 Alpha 6 | PCSX2/ARMSX2@";
    result += kPcsx2Revision;
    result += " | core4k=";
    result += core4k ? "ready" : "missing";
    result += " | core16k=";
    result += core16k ? "ready" : "missing";
    result += " | native-perf=dynamic";
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
    const bool coreReady = canLoad(pageSize >= 16384 ? "libemucore_16k.so" : "libemucore_4k.so");

    char buffer[640]{};
    std::snprintf(
        buffer,
        sizeof(buffer),
        "api=%d;ptr=%d;page=%ld;arch=%s;vulkan=%d;gles3=1;play=%d;playboot=%d;version=%s;playrev=%s",
        api,
        pointerBits,
        pageSize,
        architectureName().c_str(),
        vulkan ? 1 : 0,
        coreReady ? 1 : 0,
        coreReady ? 1 : 0,
        kFoundationVersion,
        kPcsx2Revision);
    return makeString(env, buffer);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_ps2_PS2NativeBridge_nativePcsx2Performance(
    JNIEnv* env,
    jobject /* thiz */) {
    return makeString(env, perfSnapshot());
}
