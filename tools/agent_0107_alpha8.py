from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:90]!r}")
    write(path, text.replace(old, new, 1))


# Version.
replace_once("app/build.gradle.kts", 'versionCode = 22\n        versionName = "0.10.6"', 'versionCode = 23\n        versionName = "0.10.7"')
replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    'OmniCore N64 Runtime 0.10.6 • Mupen64Plus-Next • GLES3 + AAudio host v5',
    'OmniCore N64 Runtime 0.10.7 • Mupen64Plus-Next • GLES3 + AAudio host v6'
)

# Telemetry ABI: split presentation cost from total retro_run cost and expose ADPF.
header = "app/src/main/cpp/n64/n64_libretro_host.h"
replace_once(
    header,
    '    float pacingCorrectionPct = 0.0f;\n};',
    '    float pacingCorrectionPct = 0.0f;\n'
    '    float presentAverageMs = 0.0f;\n'
    '    float presentP95Ms = 0.0f;\n'
    '    float adpfActive = 0.0f;\n};'
)
replace_once(
    header,
    '    void recordFrame(float frameMs, float targetMs);',
    '    void recordFrame(float frameMs, float targetMs);\n    void recordPresent(float presentMs);'
)
replace_once(
    header,
    '    std::array<float, kTelemetryCapacity> frameWindow_{};\n    std::size_t frameWindowCount_ = 0;\n    std::size_t frameWindowWrite_ = 0;',
    '    std::array<float, kTelemetryCapacity> frameWindow_{};\n'
    '    std::size_t frameWindowCount_ = 0;\n'
    '    std::size_t frameWindowWrite_ = 0;\n'
    '    std::array<float, kTelemetryCapacity> presentWindow_{};\n'
    '    std::size_t presentWindowCount_ = 0;\n'
    '    std::size_t presentWindowWrite_ = 0;'
)
replace_once(
    header,
    '    std::atomic<float> targetFrameMs_{1000.0f / 60.0f};',
    '    std::atomic<float> targetFrameMs_{1000.0f / 60.0f};\n    std::atomic<bool> adpfActive_{false};'
)

bridge_kt = "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt"
replace_once(
    bridge_kt,
    '        val targetFps: Float = 0f,\n        val pacingCorrectionPct: Float = 0f\n',
    '        val targetFps: Float = 0f,\n'
    '        val pacingCorrectionPct: Float = 0f,\n'
    '        val presentAverageMs: Float = 0f,\n'
    '        val presentP95Ms: Float = 0f,\n'
    '        val adpfActive: Boolean = false\n'
)
replace_once(
    bridge_kt,
    '            targetFps = targetFps,\n            pacingCorrectionPct = pacingCorrectionPct\n',
    '            targetFps = targetFps,\n'
    '            pacingCorrectionPct = pacingCorrectionPct,\n'
    '            presentAverageMs = presentAverageMs,\n'
    '            presentP95Ms = presentP95Ms\n'
)
replace_once(
    bridge_kt,
    '            targetFps = raw.getOrElse(7) { 0f },\n            pacingCorrectionPct = raw.getOrElse(8) { 0f }\n',
    '            targetFps = raw.getOrElse(7) { 0f },\n'
    '            pacingCorrectionPct = raw.getOrElse(8) { 0f },\n'
    '            presentAverageMs = raw.getOrElse(9) { 0f },\n'
    '            presentP95Ms = raw.getOrElse(10) { 0f },\n'
    '            adpfActive = raw.getOrElse(11) { 0f } >= 0.5f\n'
)

smart = "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt"
replace_once(
    smart,
    '        val targetFps: Float = 0f,\n        val pacingCorrectionPct: Float = 0f\n',
    '        val targetFps: Float = 0f,\n'
    '        val pacingCorrectionPct: Float = 0f,\n'
    '        val presentAverageMs: Float = 0f,\n'
    '        val presentP95Ms: Float = 0f\n'
)
replace_once(
    smart,
    '        val audioCritical: Boolean\n            get() = audioBufferMs > 0f && audioFillMs in 0f..max(8f, audioBufferMs * 0.35f)\n',
    '        val audioCritical: Boolean\n'
    '            get() = audioBufferMs > 0f && audioFillMs in 0f..max(8f, audioBufferMs * 0.35f)\n'
    '        val gpuBound: Boolean\n'
    '            get() = hasUsefulWindow && presentP95Ms >= 4.8f &&\n'
    '                p95FrameMs > 0f && presentP95Ms >= p95FrameMs * 0.24f\n'
)
replace_once(
    smart,
    '                        internalResolution = N64Settings.InternalResolution.NATIVE,\n                        framebufferEmulation = false\n',
    '                        internalResolution = N64Settings.InternalResolution.NATIVE,\n'
    '                        framebufferEmulation = if (telemetry.gpuBound || signals.memoryPressure) {\n'
    '                            false\n'
    '                        } else {\n'
    '                            requested.framebufferEmulation\n'
    '                        }\n'
)
replace_once(
    smart,
    '                            framePressure || signals.memoryPressure || profile.tier == N64PerformanceProfile.Tier.LOW\n                        ) false else requested.framebufferEmulation\n',
    '                            telemetry.gpuBound || signals.memoryPressure ||\n'
    '                                profile.tier == N64PerformanceProfile.Tier.LOW\n'
    '                        ) false else requested.framebufferEmulation\n'
)
replace_once(
    smart,
    '                    framePressure -> "SmartPerf N64 estabilizando frame pacing e áudio"',
    '                    telemetry.gpuBound -> "SmartPerf N64 reduziu custo gráfico medido"\n'
    '                    framePressure -> "SmartPerf N64 preservou imagem e atacou CPU/pacing"'
)

native_bridge = "app/src/main/cpp/n64/n64_native_bridge.cpp"
replace_once(
    native_bridge,
    '    const jfloat values[9] = {\n'
    '        telemetry.averageFrameMs,\n'
    '        telemetry.p95FrameMs,\n'
    '        static_cast<jfloat>(telemetry.droppedFrames),\n'
    '        static_cast<jfloat>(telemetry.audioUnderruns),\n'
    '        static_cast<jfloat>(telemetry.sampleWindowFrames),\n'
    '        telemetry.audioFillMs,\n'
    '        telemetry.audioBufferMs,\n'
    '        telemetry.targetFps,\n'
    '        telemetry.pacingCorrectionPct\n'
    '    };\n'
    '    jfloatArray result = env->NewFloatArray(9);\n'
    '    if (result) env->SetFloatArrayRegion(result, 0, 9, values);',
    '    const jfloat values[12] = {\n'
    '        telemetry.averageFrameMs,\n'
    '        telemetry.p95FrameMs,\n'
    '        static_cast<jfloat>(telemetry.droppedFrames),\n'
    '        static_cast<jfloat>(telemetry.audioUnderruns),\n'
    '        static_cast<jfloat>(telemetry.sampleWindowFrames),\n'
    '        telemetry.audioFillMs,\n'
    '        telemetry.audioBufferMs,\n'
    '        telemetry.targetFps,\n'
    '        telemetry.pacingCorrectionPct,\n'
    '        telemetry.presentAverageMs,\n'
    '        telemetry.presentP95Ms,\n'
    '        telemetry.adpfActive\n'
    '    };\n'
    '    jfloatArray result = env->NewFloatArray(12);\n'
    '    if (result) env->SetFloatArrayRegion(result, 0, 12, values);'
)

host = "app/src/main/cpp/n64/n64_libretro_host.cpp"
replace_once(host, '#include <EGL/egl.h>\n#include <GLES3/gl3.h>', '#include <EGL/egl.h>\n#include <EGL/eglext.h>\n#include <GLES3/gl3.h>')
replace_once(
    host,
    '#include <sys/mman.h>\n#include <sys/stat.h>\n#include <unistd.h>',
    '#include <sys/mman.h>\n#include <sys/resource.h>\n#include <sys/stat.h>\n#include <sys/syscall.h>\n#include <time.h>\n#include <unistd.h>'
)
replace_once(
    host,
    'std::int16_t axisFromFloat(float value) {\n'
    '    return static_cast<std::int16_t>(std::lround(std::clamp(value, -1.0f, 1.0f) * 32767.0f));\n'
    '}\n',
    'std::int16_t axisFromFloat(float value) {\n'
    '    return static_cast<std::int16_t>(std::lround(std::clamp(value, -1.0f, 1.0f) * 32767.0f));\n'
    '}\n\n'
    'class PerformanceHintSession final {\n'
    'public:\n'
    '    using GetManagerFn = void* (*)();\n'
    '    using CreateSessionFn = void* (*)(void*, const std::int32_t*, std::size_t, std::int64_t);\n'
    '    using ReportFn = int (*)(void*, std::int64_t);\n'
    '    using UpdateTargetFn = int (*)(void*, std::int64_t);\n'
    '    using CloseFn = void (*)(void*);\n\n'
    '    bool open(double fps) {\n'
    '        close();\n'
    '        library_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);\n'
    '        if (!library_) return false;\n'
    '        getManager_ = reinterpret_cast<GetManagerFn>(dlsym(library_, "APerformanceHint_getManager"));\n'
    '        createSession_ = reinterpret_cast<CreateSessionFn>(dlsym(library_, "APerformanceHint_createSession"));\n'
    '        report_ = reinterpret_cast<ReportFn>(dlsym(library_, "APerformanceHint_reportActualWorkDuration"));\n'
    '        updateTarget_ = reinterpret_cast<UpdateTargetFn>(dlsym(library_, "APerformanceHint_updateTargetWorkDuration"));\n'
    '        closeSession_ = reinterpret_cast<CloseFn>(dlsym(library_, "APerformanceHint_closeSession"));\n'
    '        if (!getManager_ || !createSession_ || !report_ || !closeSession_) { close(); return false; }\n'
    '        manager_ = getManager_();\n'
    '        if (!manager_) { close(); return false; }\n'
    '        const std::int32_t tid = static_cast<std::int32_t>(syscall(__NR_gettid));\n'
    '        targetNs_ = static_cast<std::int64_t>(1.0e9 / std::clamp(fps, 40.0, 75.0));\n'
    '        session_ = createSession_(manager_, &tid, 1u, targetNs_);\n'
    '        return session_ != nullptr;\n'
    '    }\n\n'
    '    void report(std::int64_t actualNs) {\n'
    '        if (session_ && report_ && actualNs > 0) report_(session_, actualNs);\n'
    '    }\n\n'
    '    void close() {\n'
    '        if (session_ && closeSession_) closeSession_(session_);\n'
    '        session_ = nullptr;\n'
    '        manager_ = nullptr;\n'
    '        getManager_ = nullptr;\n'
    '        createSession_ = nullptr;\n'
    '        report_ = nullptr;\n'
    '        updateTarget_ = nullptr;\n'
    '        closeSession_ = nullptr;\n'
    '        if (library_) dlclose(library_);\n'
    '        library_ = nullptr;\n'
    '        targetNs_ = 0;\n'
    '    }\n\n'
    '    bool active() const { return session_ != nullptr; }\n'
    '    ~PerformanceHintSession() { close(); }\n\n'
    'private:\n'
    '    void* library_ = nullptr;\n'
    '    void* manager_ = nullptr;\n'
    '    void* session_ = nullptr;\n'
    '    GetManagerFn getManager_ = nullptr;\n'
    '    CreateSessionFn createSession_ = nullptr;\n'
    '    ReportFn report_ = nullptr;\n'
    '    UpdateTargetFn updateTarget_ = nullptr;\n'
    '    CloseFn closeSession_ = nullptr;\n'
    '    std::int64_t targetNs_ = 0;\n'
    '};\n'
)
replace_once(
    host,
    '#ifdef MADV_SEQUENTIAL\n        madvise(data_, size_, MADV_SEQUENTIAL);\n#endif',
    '#ifdef POSIX_FADV_WILLNEED\n        posix_fadvise(fd_, 0, 0, POSIX_FADV_WILLNEED);\n#endif\n'
    '#ifdef MADV_WILLNEED\n        madvise(data_, size_, MADV_WILLNEED);\n#endif\n'
    '        // N64 ROM fetches are not a sequential media stream. Warm one byte\n'
    '        // per OS page before retro_load_game so random first-touch faults do\n'
    '        // not become visible gameplay micro-stutters later.\n'
    '        const long page = std::max<long>(4096, sysconf(_SC_PAGESIZE));\n'
    '        const auto* bytes = static_cast<const std::uint8_t*>(data_);\n'
    '        volatile std::uint8_t warm = 0;\n'
    '        for (std::size_t offset = 0; offset < size_; offset += static_cast<std::size_t>(page)) warm ^= bytes[offset];\n'
    '        if (size_ > 0) warm ^= bytes[size_ - 1];\n'
    '        (void)warm;'
)
replace_once(
    host,
    '    int stableAudioChecks = 0;\n    std::uint64_t lastRingUnderruns = 0;',
    '    int stableAudioChecks = 0;\n    int audioBufferFrames = 0;\n    std::uint64_t lastRingUnderruns = 0;'
)
replace_once(
    host,
    '    double targetFps = 60.0;\n\n    AudioRing audioRing;',
    '    double targetFps = 60.0;\n'
    '    PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTimeFn = nullptr;\n'
    '    std::int64_t presentationTargetNs = 0;\n'
    '    PerformanceHintSession perfHint;\n\n'
    '    AudioRing audioRing;'
)
replace_once(
    host,
    '        eglSwapInterval(display, 0);\n        eglQuerySurface(display, surface, EGL_WIDTH, &surfaceWidth);',
    '        eglSwapInterval(display, 0);\n'
    '        presentationTimeFn = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(\n'
    '            eglGetProcAddress("eglPresentationTimeANDROID"));\n'
    '        eglQuerySurface(display, surface, EGL_WIDTH, &surfaceWidth);'
)
replace_once(
    host,
    '        context = EGL_NO_CONTEXT;\n        surface = EGL_NO_SURFACE;\n        display = EGL_NO_DISPLAY;\n        eglConfig = nullptr;',
    '        context = EGL_NO_CONTEXT;\n'
    '        surface = EGL_NO_SURFACE;\n'
    '        display = EGL_NO_DISPLAY;\n'
    '        eglConfig = nullptr;\n'
    '        presentationTimeFn = nullptr;\n'
    '        presentationTargetNs = 0;'
)
replace_once(
    host,
    '        int bufferFrames = 0;\n        if (audioStream) bufferFrames = std::max(0, AAudioStream_getBufferSizeInFrames(audioStream));\n        const float bufferMs = static_cast<float>(bufferFrames) * 1000.0f /',
    '        const int bufferFrames = std::max(0, audioBufferFrames);\n'
    '        const float bufferMs = static_cast<float>(bufferFrames) * 1000.0f /'
)
replace_once(
    host,
    '            AAudioStream_setBufferSizeInFrames(audioStream, framesPerBurst * appliedAudioBursts);\n            // Start with enough PCM queued',
    '            const int requestedFrames = framesPerBurst * appliedAudioBursts;\n'
    '            const int appliedFrames = AAudioStream_setBufferSizeInFrames(audioStream, requestedFrames);\n'
    '            audioBufferFrames = appliedFrames > 0 ? appliedFrames : requestedFrames;\n'
    '            // Start with enough PCM queued'
)
replace_once(
    host,
    '        framesPerBurst = 0;\n        audioPrimeFrames = 0;',
    '        framesPerBurst = 0;\n        audioBufferFrames = 0;\n        audioPrimeFrames = 0;'
)
replace_once(
    host,
    '            AAudioStream_setBufferSizeInFrames(audioStream, framesPerBurst * next);\n            appliedAudioBursts = next;',
    '            const int requestedFrames = framesPerBurst * next;\n'
    '            const int appliedFrames = AAudioStream_setBufferSizeInFrames(audioStream, requestedFrames);\n'
    '            audioBufferFrames = appliedFrames > 0 ? appliedFrames : requestedFrames;\n'
    '            appliedAudioBursts = next;'
)
replace_once(
    host,
    '    options_["mupen64plus-EnableLODEmulation"] = leanGraphics ? "False" : "True";',
    '    // Readability floor: LOD is part of normal N64 texture selection and was\n'
    '    // too visually destructive to disable merely for performance.\n'
    '    options_["mupen64plus-EnableLODEmulation"] = "True";'
)
replace_once(
    host,
    '    options_["mupen64plus-EnableNativeResFactor"] = "0";',
    '    options_["mupen64plus-EnableNativeResFactor"] = "0";\n'
    '    options_["mupen64plus-EnableNativeResTexrects"] = "Optimized";'
)
replace_once(
    host,
    '    glBlitFramebuffer(0, 0, impl_->renderWidth, impl_->renderHeight,\n'
    '                      0, 0, impl_->surfaceWidth, impl_->surfaceHeight,\n'
    '                      GL_COLOR_BUFFER_BIT, GL_LINEAR);\n'
    '    if (!eglSwapBuffers(impl_->display, impl_->surface)) {',
    '    const auto presentBegin = std::chrono::steady_clock::now();\n'
    '    // Native N64 output was being blurred twice by linear upscaling. Keep\n'
    '    // 2x output smooth, but preserve low-resolution text/UI pixels at 1x.\n'
    '    const GLenum presentFilter = config_.internalResolution >= 2 ? GL_LINEAR : GL_NEAREST;\n'
    '    glBlitFramebuffer(0, 0, impl_->renderWidth, impl_->renderHeight,\n'
    '                      0, 0, impl_->surfaceWidth, impl_->surfaceHeight,\n'
    '                      GL_COLOR_BUFFER_BIT, presentFilter);\n'
    '    if (impl_->presentationTimeFn && impl_->presentationTargetNs > 0) {\n'
    '        impl_->presentationTimeFn(impl_->display, impl_->surface, impl_->presentationTargetNs);\n'
    '    }\n'
    '    if (!eglSwapBuffers(impl_->display, impl_->surface)) {'
)
replace_once(
    host,
    '    glBindFramebuffer(GL_FRAMEBUFFER, impl_->frontFbo);\n    glViewport(0, 0, impl_->renderWidth, impl_->renderHeight);\n    ++impl_->presentedFrames;',
    '    const auto presentEnd = std::chrono::steady_clock::now();\n'
    '    recordPresent(std::chrono::duration<float, std::milli>(presentEnd - presentBegin).count());\n'
    '    glBindFramebuffer(GL_FRAMEBUFFER, impl_->frontFbo);\n'
    '    glViewport(0, 0, impl_->renderWidth, impl_->renderHeight);\n'
    '    ++impl_->presentedFrames;'
)
replace_once(
    host,
    'void LibretroHost::recordFrame(float frameMs, float targetMs) {\n'
    '    targetFrameMs_.store(targetMs, std::memory_order_release);\n'
    '    std::lock_guard<std::mutex> lock(telemetryMutex_);\n'
    '    frameWindow_[frameWindowWrite_] = frameMs;\n'
    '    frameWindowWrite_ = (frameWindowWrite_ + 1) % kTelemetryCapacity;\n'
    '    frameWindowCount_ = std::min(frameWindowCount_ + 1, kTelemetryCapacity);\n'
    '}\n',
    'void LibretroHost::recordFrame(float frameMs, float targetMs) {\n'
    '    targetFrameMs_.store(targetMs, std::memory_order_release);\n'
    '    std::lock_guard<std::mutex> lock(telemetryMutex_);\n'
    '    frameWindow_[frameWindowWrite_] = frameMs;\n'
    '    frameWindowWrite_ = (frameWindowWrite_ + 1) % kTelemetryCapacity;\n'
    '    frameWindowCount_ = std::min(frameWindowCount_ + 1, kTelemetryCapacity);\n'
    '}\n\n'
    'void LibretroHost::recordPresent(float presentMs) {\n'
    '    std::lock_guard<std::mutex> lock(telemetryMutex_);\n'
    '    presentWindow_[presentWindowWrite_] = presentMs;\n'
    '    presentWindowWrite_ = (presentWindowWrite_ + 1) % kTelemetryCapacity;\n'
    '    presentWindowCount_ = std::min(presentWindowCount_ + 1, kTelemetryCapacity);\n'
    '}\n'
)
replace_once(
    host,
    '    std::array<float, kTelemetryCapacity> snapshot{};\n    std::size_t count = 0;\n    {\n        std::lock_guard<std::mutex> lock(telemetryMutex_);\n        count = frameWindowCount_;\n        for (std::size_t i = 0; i < count; ++i) snapshot[i] = frameWindow_[i];\n    }',
    '    std::array<float, kTelemetryCapacity> snapshot{};\n'
    '    std::array<float, kTelemetryCapacity> presentSnapshot{};\n'
    '    std::size_t count = 0;\n'
    '    std::size_t presentCount = 0;\n'
    '    {\n'
    '        std::lock_guard<std::mutex> lock(telemetryMutex_);\n'
    '        count = frameWindowCount_;\n'
    '        presentCount = presentWindowCount_;\n'
    '        for (std::size_t i = 0; i < count; ++i) snapshot[i] = frameWindow_[i];\n'
    '        for (std::size_t i = 0; i < presentCount; ++i) presentSnapshot[i] = presentWindow_[i];\n'
    '    }'
)
replace_once(
    host,
    '    out.pacingCorrectionPct = pacingCorrectionPct_.load(std::memory_order_acquire);\n    if (count == 0) return out;',
    '    out.pacingCorrectionPct = pacingCorrectionPct_.load(std::memory_order_acquire);\n'
    '    out.adpfActive = adpfActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n'
    '    if (presentCount > 0) {\n'
    '        float totalPresent = 0.0f;\n'
    '        for (std::size_t i = 0; i < presentCount; ++i) totalPresent += presentSnapshot[i];\n'
    '        out.presentAverageMs = totalPresent / static_cast<float>(presentCount);\n'
    '        std::sort(presentSnapshot.begin(), presentSnapshot.begin() + static_cast<std::ptrdiff_t>(presentCount));\n'
    '        const std::size_t pi = std::min(presentCount - 1, static_cast<std::size_t>(std::floor((presentCount - 1) * 0.95)));\n'
    '        out.presentP95Ms = presentSnapshot[pi];\n'
    '    }\n'
    '    if (count == 0) return out;'
)
replace_once(
    host,
    '        frameWindow_.fill(0.0f);\n        frameWindowCount_ = 0;\n        frameWindowWrite_ = 0;',
    '        frameWindow_.fill(0.0f);\n'
    '        frameWindowCount_ = 0;\n'
    '        frameWindowWrite_ = 0;\n'
    '        presentWindow_.fill(0.0f);\n'
    '        presentWindowCount_ = 0;\n'
    '        presentWindowWrite_ = 0;'
)
replace_once(
    host,
    '    targetFrameMs_.store(1000.0f / 60.0f, std::memory_order_release);\n    hwRenderRequested_ = false;',
    '    targetFrameMs_.store(1000.0f / 60.0f, std::memory_order_release);\n'
    '    adpfActive_.store(false, std::memory_order_release);\n'
    '    hwRenderRequested_ = false;'
)
replace_once(
    host,
    'void LibretroHost::run() {\n    impl_ = std::make_unique<Impl>(this);',
    'void LibretroHost::run() {\n'
    '    // App-owned emulation thread only; no governor/clock/system mutation.\n'
    '    // Android may reject the priority request, in which case ADPF/default\n'
    '    // scheduling remains in effect.\n'
    '    setpriority(PRIO_PROCESS, 0, -4);\n'
    '    impl_ = std::make_unique<Impl>(this);'
)
replace_once(
    host,
    '            if (impl_->gameLoaded) persistSaveRam(true);\n            impl_->closeAudio();',
    '            if (impl_->gameLoaded) persistSaveRam(true);\n'
    '            impl_->perfHint.close();\n'
    '            adpfActive_.store(false, std::memory_order_release);\n'
    '            impl_->closeAudio();'
)
replace_once(
    host,
    '    targetFrameMs_.store(targetMs, std::memory_order_release);\n    setMessage(audioReady',
    '    targetFrameMs_.store(targetMs, std::memory_order_release);\n'
    '    adpfActive_.store(impl_->perfHint.open(impl_->targetFps), std::memory_order_release);\n'
    '    setMessage(audioReady'
)
replace_once(
    host,
    '    auto nextFrame = std::chrono::steady_clock::now();\n    std::uint32_t adaptationCounter = 0;\n    std::uint32_t saveCounter = 0;',
    '    auto nextFrame = std::chrono::steady_clock::now() + targetDuration;\n'
    '    std::uint32_t adaptationCounter = 0;'
)
replace_once(
    host,
    '            if (!wasPaused && impl_->audioStream) impl_->reprimeAudio();\n            wasPaused = true;',
    '            if (!wasPaused) {\n'
    '                persistSaveRam(false);\n'
    '                if (impl_->audioStream) impl_->reprimeAudio();\n'
    '            }\n'
    '            wasPaused = true;'
)
replace_once(
    host,
    '            nextFrame = std::chrono::steady_clock::now();\n            continue;',
    '            nextFrame = std::chrono::steady_clock::now() + targetDuration;\n'
    '            continue;'
)
replace_once(
    host,
    '            nextFrame = std::chrono::steady_clock::now();\n        }\n\n        const auto begin = std::chrono::steady_clock::now();\n        impl_->core.run();\n        const auto afterRun = std::chrono::steady_clock::now();\n        recordFrame(std::chrono::duration<float, std::milli>(afterRun - begin).count(), targetMs);',
    '            nextFrame = std::chrono::steady_clock::now() + targetDuration;\n'
    '        }\n\n'
    '        impl_->presentationTargetNs = std::chrono::duration_cast<std::chrono::nanoseconds>(\n'
    '            nextFrame.time_since_epoch()).count();\n'
    '        const auto begin = std::chrono::steady_clock::now();\n'
    '        impl_->core.run();\n'
    '        const auto afterRun = std::chrono::steady_clock::now();\n'
    '        const auto workNs = std::chrono::duration_cast<std::chrono::nanoseconds>(afterRun - begin).count();\n'
    '        impl_->perfHint.report(workNs);\n'
    '        recordFrame(std::chrono::duration<float, std::milli>(afterRun - begin).count(), targetMs);'
)
replace_once(
    host,
    '        if (++saveCounter >= 600u) {\n            saveCounter = 0;\n            persistSaveRam(false);\n        }\n\n',
    ''
)
replace_once(
    host,
    '        nextFrame += targetDuration;\n        const auto now = std::chrono::steady_clock::now();\n        if (nextFrame > now) {\n            std::this_thread::sleep_until(nextFrame);\n        } else if (now - nextFrame > lateResetThreshold) {\n            nextFrame = now;\n        }',
    '        const auto now = std::chrono::steady_clock::now();\n'
    '        if (nextFrame > now) {\n'
    '            std::this_thread::sleep_until(nextFrame);\n'
    '            nextFrame += targetDuration;\n'
    '        } else if (now - nextFrame > lateResetThreshold) {\n'
    '            nextFrame = now + targetDuration;\n'
    '        } else {\n'
    '            nextFrame += targetDuration;\n'
    '        }'
)

# Show the new bottleneck split in the in-game diagnostics.
activity = "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt"
replace_once(
    activity,
    '            append(" ms\\nÁudio ")',
    '            append(" ms • present ")\n'
    '            append("%.1f".format(t.presentP95Ms))\n'
    '            append(" ms")\n'
    '            append(if (t.adpfActive) " • ADPF" else "")\n'
    '            append("\\nÁudio ")'
)

print("Alpha 8 migration applied successfully")
