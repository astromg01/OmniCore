#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(rel: str, old: str, new: str) -> None:
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing expected Alpha 10 source in {rel}: {old[:90]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# App/runtime version.
replace_once("app/build.gradle.kts", 'versionCode = 25', 'versionCode = 26')
replace_once("app/build.gradle.kts", 'versionName = "0.10.9"', 'versionName = "0.10.10"')
replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    'OmniCore N64 Runtime 0.10.9 • Mupen64Plus-Next • GLES3 + AAudio host v8',
    'OmniCore N64 Runtime 0.10.10 • Mupen64Plus-Next • GLES3 + AAudio host v9 • BurstShield'
)

# Native telemetry exposes whether the new transient-workload path is actually
# available on the current Android build. It is independent from SmartPerf.
replace_once(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '    float adpfActive = 0.0f;\n};',
    '    float adpfActive = 0.0f;\n    float burstShieldActive = 0.0f;\n};'
)
replace_once(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '    std::atomic<bool> adpfActive_{false};\n',
    '    std::atomic<bool> adpfActive_{false};\n    std::atomic<bool> burstShieldActive_{false};\n'
)

# Upgrade the existing dynamically loaded ADPF session. Optional Android 16+
# symbols stay best-effort: older devices simply keep the existing ADPF path.
host_cpp = "app/src/main/cpp/n64/n64_libretro_host.cpp"
old_perf = '''class PerformanceHintSession final {
public:
    using GetManagerFn = void* (*)();
    using CreateSessionFn = void* (*)(void*, const std::int32_t*, std::size_t, std::int64_t);
    using ReportFn = int (*)(void*, std::int64_t);
    using UpdateTargetFn = int (*)(void*, std::int64_t);
    using CloseFn = void (*)(void*);

    bool open(double fps) {
        close();
        library_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!library_) return false;
        getManager_ = reinterpret_cast<GetManagerFn>(dlsym(library_, "APerformanceHint_getManager"));
        createSession_ = reinterpret_cast<CreateSessionFn>(dlsym(library_, "APerformanceHint_createSession"));
        report_ = reinterpret_cast<ReportFn>(dlsym(library_, "APerformanceHint_reportActualWorkDuration"));
        updateTarget_ = reinterpret_cast<UpdateTargetFn>(dlsym(library_, "APerformanceHint_updateTargetWorkDuration"));
        closeSession_ = reinterpret_cast<CloseFn>(dlsym(library_, "APerformanceHint_closeSession"));
        if (!getManager_ || !createSession_ || !report_ || !closeSession_) { close(); return false; }
        manager_ = getManager_();
        if (!manager_) { close(); return false; }
        const std::int32_t tid = static_cast<std::int32_t>(syscall(__NR_gettid));
        targetNs_ = static_cast<std::int64_t>(1.0e9 / std::clamp(fps, 40.0, 75.0));
        session_ = createSession_(manager_, &tid, 1u, targetNs_);
        return session_ != nullptr;
    }

    void report(std::int64_t actualNs) {
        if (session_ && report_ && actualNs > 0) report_(session_, actualNs);
    }

    void close() {
        if (session_ && closeSession_) closeSession_(session_);
        session_ = nullptr;
        manager_ = nullptr;
        getManager_ = nullptr;
        createSession_ = nullptr;
        report_ = nullptr;
        updateTarget_ = nullptr;
        closeSession_ = nullptr;
        if (library_) dlclose(library_);
        library_ = nullptr;
        targetNs_ = 0;
    }

    bool active() const { return session_ != nullptr; }
    ~PerformanceHintSession() { close(); }

private:
    void* library_ = nullptr;
    void* manager_ = nullptr;
    void* session_ = nullptr;
    GetManagerFn getManager_ = nullptr;
    CreateSessionFn createSession_ = nullptr;
    ReportFn report_ = nullptr;
    UpdateTargetFn updateTarget_ = nullptr;
    CloseFn closeSession_ = nullptr;
    std::int64_t targetNs_ = 0;
};'''
new_perf = '''class PerformanceHintSession final {
public:
    using GetManagerFn = void* (*)();
    using CreateSessionFn = void* (*)(void*, const std::int32_t*, std::size_t, std::int64_t);
    using ReportFn = int (*)(void*, std::int64_t);
    using UpdateTargetFn = int (*)(void*, std::int64_t);
    using NotifyWorkloadFn = int (*)(void*, bool, bool, const char*);
    using SetNativeSurfacesFn = int (*)(void*, ANativeWindow* const*, std::size_t, void* const*, std::size_t);
    using CloseFn = void (*)(void*);

    bool open(double fps) {
        close();
        library_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!library_) return false;
        getManager_ = reinterpret_cast<GetManagerFn>(dlsym(library_, "APerformanceHint_getManager"));
        createSession_ = reinterpret_cast<CreateSessionFn>(dlsym(library_, "APerformanceHint_createSession"));
        report_ = reinterpret_cast<ReportFn>(dlsym(library_, "APerformanceHint_reportActualWorkDuration"));
        updateTarget_ = reinterpret_cast<UpdateTargetFn>(dlsym(library_, "APerformanceHint_updateTargetWorkDuration"));
        notifySpike_ = reinterpret_cast<NotifyWorkloadFn>(dlsym(library_, "APerformanceHint_notifyWorkloadSpike"));
        notifyIncrease_ = reinterpret_cast<NotifyWorkloadFn>(dlsym(library_, "APerformanceHint_notifyWorkloadIncrease"));
        notifyReset_ = reinterpret_cast<NotifyWorkloadFn>(dlsym(library_, "APerformanceHint_notifyWorkloadReset"));
        setNativeSurfaces_ = reinterpret_cast<SetNativeSurfacesFn>(dlsym(library_, "APerformanceHint_setNativeSurfaces"));
        closeSession_ = reinterpret_cast<CloseFn>(dlsym(library_, "APerformanceHint_closeSession"));
        if (!getManager_ || !createSession_ || !report_ || !closeSession_) { close(); return false; }
        manager_ = getManager_();
        if (!manager_) { close(); return false; }
        const std::int32_t tid = static_cast<std::int32_t>(syscall(__NR_gettid));
        targetNs_ = static_cast<std::int64_t>(1.0e9 / std::clamp(fps, 40.0, 75.0));
        session_ = createSession_(manager_, &tid, 1u, targetNs_);
        return session_ != nullptr;
    }

    void report(std::int64_t actualNs) {
        if (session_ && report_ && actualNs > 0) report_(session_, actualNs);
    }

    bool bindSurface(ANativeWindow* window) {
        if (!session_ || !setNativeSurfaces_ || !window) return false;
        ANativeWindow* windows[] = {window};
        return setNativeSurfaces_(session_, windows, 1u, nullptr, 0u) == 0;
    }

    void notifyReset(bool cpu, bool gpu, const char* identifier) {
        if (session_ && notifyReset_ && identifier) notifyReset_(session_, cpu, gpu, identifier);
    }

    void notifySpike(bool cpu, bool gpu, const char* identifier) {
        if (!session_ || !notifySpike_ || !identifier) return;
        const auto now = std::chrono::steady_clock::now();
        if (lastSpikeAt_.time_since_epoch().count() != 0 &&
            now - lastSpikeAt_ < std::chrono::milliseconds(700)) return;
        notifySpike_(session_, cpu, gpu, identifier);
        lastSpikeAt_ = now;
    }

    void notifyIncrease(bool cpu, bool gpu, const char* identifier) {
        if (!session_ || !notifyIncrease_ || !identifier) return;
        const auto now = std::chrono::steady_clock::now();
        if (lastIncreaseAt_.time_since_epoch().count() != 0 &&
            now - lastIncreaseAt_ < std::chrono::seconds(10)) return;
        notifyIncrease_(session_, cpu, gpu, identifier);
        lastIncreaseAt_ = now;
    }

    void close() {
        if (session_ && closeSession_) closeSession_(session_);
        session_ = nullptr;
        manager_ = nullptr;
        getManager_ = nullptr;
        createSession_ = nullptr;
        report_ = nullptr;
        updateTarget_ = nullptr;
        notifySpike_ = nullptr;
        notifyIncrease_ = nullptr;
        notifyReset_ = nullptr;
        setNativeSurfaces_ = nullptr;
        closeSession_ = nullptr;
        if (library_) dlclose(library_);
        library_ = nullptr;
        targetNs_ = 0;
        lastSpikeAt_ = {};
        lastIncreaseAt_ = {};
    }

    bool active() const { return session_ != nullptr; }
    bool burstCapable() const { return session_ != nullptr && notifySpike_ != nullptr; }
    ~PerformanceHintSession() { close(); }

private:
    void* library_ = nullptr;
    void* manager_ = nullptr;
    void* session_ = nullptr;
    GetManagerFn getManager_ = nullptr;
    CreateSessionFn createSession_ = nullptr;
    ReportFn report_ = nullptr;
    UpdateTargetFn updateTarget_ = nullptr;
    NotifyWorkloadFn notifySpike_ = nullptr;
    NotifyWorkloadFn notifyIncrease_ = nullptr;
    NotifyWorkloadFn notifyReset_ = nullptr;
    SetNativeSurfacesFn setNativeSurfaces_ = nullptr;
    CloseFn closeSession_ = nullptr;
    std::int64_t targetNs_ = 0;
    std::chrono::steady_clock::time_point lastSpikeAt_{};
    std::chrono::steady_clock::time_point lastIncreaseAt_{};
};'''
replace_once(host_cpp, old_perf, new_perf)

# Reset/export BurstShield state with the rest of session telemetry.
replace_once(
    host_cpp,
    '    adpfActive_.store(false, std::memory_order_release);\n    hwRenderRequested_ = false;',
    '    adpfActive_.store(false, std::memory_order_release);\n    burstShieldActive_.store(false, std::memory_order_release);\n    hwRenderRequested_ = false;'
)
replace_once(
    host_cpp,
    '            impl_->perfHint.close();\n            adpfActive_.store(false, std::memory_order_release);',
    '            impl_->perfHint.close();\n            adpfActive_.store(false, std::memory_order_release);\n            burstShieldActive_.store(false, std::memory_order_release);'
)
replace_once(
    host_cpp,
    '    out.adpfActive = adpfActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n',
    '    out.adpfActive = adpfActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n    out.burstShieldActive = burstShieldActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n'
)

# Bind the ADPF session to the actual N64 ANativeWindow when supported and reset
# learned workload assumptions at session start. This is the GPU-facing half of
# BurstShield and does not alter GLideN64 correctness settings.
replace_once(
    host_cpp,
    '    adpfActive_.store(impl_->perfHint.open(impl_->targetFps), std::memory_order_release);\n    setMessage(audioReady',
    '''    const bool adpfReady = impl_->perfHint.open(impl_->targetFps);
    adpfActive_.store(adpfReady, std::memory_order_release);
    if (adpfReady) {
        impl_->perfHint.notifyReset(true, true, "omnicore-n64-session");
        impl_->perfHint.bindSurface(window_);
    }
    burstShieldActive_.store(adpfReady && impl_->perfHint.burstCapable(), std::memory_order_release);
    setMessage(audioReady'''
)

# Start/menu is a predictable one-frame framebuffer workload. The hint is sent
# immediately before the core consumes that Start input, while audio is already
# expanded. No resolution or framebuffer option is changed.
replace_once(
    host_cpp,
    '''        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            impl_->adaptAudio(8);
        }
        impl_->presentationTargetNs =''',
    '''        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            impl_->adaptAudio(8);
            impl_->perfHint.notifySpike(true, true, "omnicore-n64-menu-transition");
        }
        impl_->presentationTargetNs ='''
)

# Sustained slow frames get a low-frequency workload-increase hint and a larger
# AAudio cushion. This path explicitly preserves the selected render resolution.
replace_once(
    host_cpp,
    '    std::uint32_t adaptationCounter = 0;\n    bool wasPaused = false;',
    '    std::uint32_t adaptationCounter = 0;\n    int slowFrameStreak = 0;\n    bool wasPaused = false;'
)
replace_once(
    host_cpp,
    '''        const auto workNs = std::chrono::duration_cast<std::chrono::nanoseconds>(afterRun - begin).count();
        impl_->perfHint.report(workNs);
        recordFrame(std::chrono::duration<float, std::milli>(afterRun - begin).count(), targetMs);

        if (++adaptationCounter >= 60u) {''',
    '''        const auto workNs = std::chrono::duration_cast<std::chrono::nanoseconds>(afterRun - begin).count();
        const float frameMs = std::chrono::duration<float, std::milli>(afterRun - begin).count();
        impl_->perfHint.report(workNs);
        recordFrame(frameMs, targetMs);

        if (frameMs > targetMs * 1.18f) {
            ++slowFrameStreak;
        } else if (slowFrameStreak > 0) {
            --slowFrameStreak;
        }
        if (slowFrameStreak >= 4) {
            impl_->perfHint.notifyIncrease(true, true, "omnicore-n64-sustained-frame-pressure");
            impl_->adaptAudio(7);
            slowFrameStreak = 0;
        }

        if (++adaptationCounter >= 60u) {'''
)

# JNI telemetry grows from 12 to 13 floats.
replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '    const jfloat values[12] = {',
    '    const jfloat values[13] = {'
)
replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''        telemetry.presentP95Ms,
        telemetry.adpfActive
    };
    jfloatArray result = env->NewFloatArray(12);
    if (result) env->SetFloatArrayRegion(result, 0, 12, values);''',
    '''        telemetry.presentP95Ms,
        telemetry.adpfActive,
        telemetry.burstShieldActive
    };
    jfloatArray result = env->NewFloatArray(13);
    if (result) env->SetFloatArrayRegion(result, 0, 13, values);'''
)

# Kotlin bridge/status surfaces the feature without feeding it back into
# SmartPerf, keeping the two controllers orthogonal.
bridge = "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt"
replace_once(
    bridge,
    '        val adpfActive: Boolean = false\n',
    '        val adpfActive: Boolean = false,\n        val burstShieldActive: Boolean = false\n'
)
replace_once(
    bridge,
    '            adpfActive = raw.getOrElse(11) { 0f } >= 0.5f\n',
    '            adpfActive = raw.getOrElse(11) { 0f } >= 0.5f,\n            burstShieldActive = raw.getOrElse(12) { 0f } >= 0.5f\n'
)

activity = "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt"
replace_once(
    activity,
    '            append(if (t.adpfActive) " • ADPF" else "")\n',
    '            append(if (t.adpfActive) " • ADPF" else "")\n            append(if (t.burstShieldActive) " • BurstShield" else "")\n'
)

# Keep the resolution-first policy explicit in SmartPerf documentation/reasoning.
replace_once(
    "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt",
    '                    framePressure -> "SmartPerf N64 preservou imagem e atacou CPU/pacing"',
    '                    framePressure -> "SmartPerf N64 preservou resolução; BurstShield ataca picos CPU/GPU"'
)

print("OmniCore 0.10.10 N64 Alpha 11 BurstShield migration applied")
