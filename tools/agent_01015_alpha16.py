from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    found = text.count(old)
    if found < count:
        raise SystemExit(f"{path}: expected {count} occurrence(s), found {found}: {old[:140]!r}")
    write(path, text.replace(old, new, count))


def replace_region(path: str, start_marker: str, end_marker: str, replacement: str) -> None:
    text = read(path)
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"{path}: missing start marker: {start_marker!r}")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"{path}: missing end marker: {end_marker!r}")
    write(path, text[:start] + replacement + text[end:])


# Version.
replace("app/build.gradle.kts", "versionCode = 30", "versionCode = 31")
replace("app/build.gradle.kts", 'versionName = "0.10.14"', 'versionName = "0.10.15"')

# Native telemetry: expose classifier confidence + smoothed jitter so device tests
# can distinguish a strong diagnosis from a transient guess.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '''    float passiveWarmCacheReady = 0.0f;\n    float precisionGovernorMode = 0.0f;\n''',
    '''    float passiveWarmCacheReady = 0.0f;\n    float precisionGovernorMode = 0.0f;\n    float precisionGovernorConfidence = 0.0f;\n    float frameJitterMs = 0.0f;\n'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '''    std::atomic<bool> smartAnalogDpadActive_{false};\n    std::atomic<int> precisionGovernorMode_{0};\n    std::atomic<float> lastPresentMs_{0.0f};\n''',
    '''    std::atomic<bool> smartAnalogDpadActive_{false};\n    std::atomic<int> precisionGovernorMode_{0};\n    std::atomic<float> precisionGovernorConfidence_{0.0f};\n    std::atomic<float> frameJitterMs_{0.0f};\n    std::atomic<float> lastPresentMs_{0.0f};\n'''
)

replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    out.passiveWarmCacheReady = passiveWarmCacheReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n    out.precisionGovernorMode = static_cast<float>(precisionGovernorMode_.load(std::memory_order_acquire));\n''',
    '''    out.passiveWarmCacheReady = passiveWarmCacheReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n    out.precisionGovernorMode = static_cast<float>(precisionGovernorMode_.load(std::memory_order_acquire));\n    out.precisionGovernorConfidence = precisionGovernorConfidence_.load(std::memory_order_acquire);\n    out.frameJitterMs = frameJitterMs_.load(std::memory_order_acquire);\n'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''            warmStartActive_.store(false, std::memory_order_release);\n            precisionGovernorMode_.store(0, std::memory_order_release);\n            impl_->closeAudio();\n''',
    '''            warmStartActive_.store(false, std::memory_order_release);\n            precisionGovernorMode_.store(0, std::memory_order_release);\n            precisionGovernorConfidence_.store(0.0f, std::memory_order_release);\n            frameJitterMs_.store(0.0f, std::memory_order_release);\n            impl_->closeAudio();\n'''
)

replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    std::uint32_t adaptationCounter = 0;\n    int pressureStreak = 0;\n    int stableStreak = 0;\n    int warmStableFrames = 0;\n    float frameEwma = targetMs;\n    float presentEwma = 0.0f;\n    const auto warmStartBegan = std::chrono::steady_clock::now();\n    auto lastGovernorChange = warmStartBegan - std::chrono::seconds(2);\n    auto governorHeadroomUntil = std::chrono::steady_clock::time_point{};\n    int governorMode = 0;  // 0 stable, 1 CPU, 2 GPU/present, 3 mixed.\n    bool wasPaused = false;\n''',
    '''    std::uint32_t adaptationCounter = 0;\n    int stableStreak = 0;\n    int warmStableFrames = 0;\n    int candidateMode = 0;\n    int candidateStreak = 0;\n    float fastFrameEwma = targetMs;\n    float slowFrameEwma = targetMs;\n    float fastPresentEwma = 0.0f;\n    float slowPresentEwma = 0.0f;\n    float jitterEwma = 0.0f;\n    float previousFrameMs = targetMs;\n    float pressureDebt = 0.0f;\n    const auto warmStartBegan = std::chrono::steady_clock::now();\n    auto lastGovernorChange = warmStartBegan - std::chrono::seconds(5);\n    auto governorHeadroomUntil = std::chrono::steady_clock::time_point{};\n    int governorMode = 0;  // 0 stable, 1 CPU, 2 GPU/present, 3 mixed.\n    bool wasPaused = false;\n'''
)

replace_region(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''        const auto controlNow = std::chrono::steady_clock::now();\n        const float presentMs = lastPresentMs_.load(std::memory_order_acquire);\n''',
    '''        // Audio is controlled only by actual AAudio/ring evidence. Frame spikes\n''',
    r'''        const auto controlNow = std::chrono::steady_clock::now();
        const float presentMs = lastPresentMs_.load(std::memory_order_acquire);

        // PrecisionGovernor v2 uses a fast signal for responsiveness and a slow
        // signal for confidence. A mode change requires both sustained pressure
        // and a stable bottleneck identity, so scene transitions do not make the
        // governor bounce between CPU/GPU/MIX.
        fastFrameEwma += (frameMs - fastFrameEwma) * 0.12f;
        slowFrameEwma += (frameMs - slowFrameEwma) * 0.025f;
        fastPresentEwma += (presentMs - fastPresentEwma) * 0.14f;
        slowPresentEwma += (presentMs - slowPresentEwma) * 0.030f;
        const float instantJitter = std::abs(frameMs - previousFrameMs);
        previousFrameMs = frameMs;
        jitterEwma += (instantJitter - jitterEwma) * 0.08f;
        frameJitterMs_.store(jitterEwma, std::memory_order_release);

        const float fastRatio = fastFrameEwma / std::max(1.0f, targetMs);
        const float slowRatio = slowFrameEwma / std::max(1.0f, targetMs);
        const bool framePressure = fastRatio > 1.10f || slowRatio > 1.065f;
        const float severity = std::max(fastRatio - 1.08f, slowRatio - 1.045f);
        if (framePressure) {
            pressureDebt = std::min(1.0f, pressureDebt + 0.018f + std::max(0.0f, severity) * 0.10f);
            stableStreak = 0;
            warmStableFrames = 0;
        } else {
            pressureDebt = std::max(0.0f, pressureDebt - (slowRatio <= 1.03f ? 0.018f : 0.008f));
            if (slowRatio <= 1.045f && jitterEwma <= targetMs * 0.16f) {
                stableStreak = std::min(stableStreak + 1, 300);
                warmStableFrames = std::min(warmStableFrames + 1, 240);
            } else {
                stableStreak = std::max(0, stableStreak - 1);
                warmStableFrames = std::max(0, warmStableFrames - 1);
            }
        }

        const float presentShare = slowPresentEwma / std::max(1.0f, slowFrameEwma);
        const bool presentHeavy = slowPresentEwma >= std::max(3.2f, targetMs * 0.20f) &&
            presentShare >= 0.235f;
        const float cpuSideMs = std::max(0.0f, slowFrameEwma - slowPresentEwma);
        const bool cpuHeavy = cpuSideMs >= targetMs * 0.74f;
        const int observedMode = presentHeavy ? (cpuHeavy ? 3 : 2) : 1;

        if (framePressure || pressureDebt >= 0.20f) {
            if (candidateMode == observedMode) {
                candidateStreak = std::min(candidateStreak + 1, 180);
            } else {
                // A new diagnosis starts with low confidence instead of replacing
                // the active mode immediately.
                candidateMode = observedMode;
                candidateStreak = 1;
            }
        } else if (candidateStreak > 0) {
            candidateStreak = std::max(0, candidateStreak - 2);
            if (candidateStreak == 0) candidateMode = 0;
        }

        const float classifierConfidence = candidateMode == 0
            ? 0.0f
            : std::clamp(static_cast<float>(candidateStreak) / 45.0f, 0.0f, 1.0f);
        precisionGovernorConfidence_.store(classifierConfidence, std::memory_order_release);

        // Isolated catastrophic spikes receive only a bounded transient hint.
        // They never change the governor mode by themselves.
        if (frameMs > targetMs * 1.85f) {
            const bool spikeGpu = presentMs >= std::max(4.0f, targetMs * 0.25f);
            impl_->perfHint.notifySpike(
                !spikeGpu,
                spikeGpu,
                spikeGpu ? "omnicore-n64-v2-single-gpu-spike" : "omnicore-n64-v2-single-cpu-spike");
        }

        const auto modeDwell = controlNow - lastGovernorChange;
        const bool canEnter = governorMode == 0 && modeDwell >= std::chrono::milliseconds(2500);
        const bool canSwitch = governorMode != 0 && modeDwell >= std::chrono::seconds(4);
        const bool confidentPressure = pressureDebt >= 0.42f && candidateStreak >= 24;
        const bool confidentSwitch = pressureDebt >= 0.50f && candidateStreak >= 45;
        const bool shouldApply = candidateMode != 0 && candidateMode != governorMode &&
            ((canEnter && confidentPressure) || (canSwitch && confidentSwitch));

        if (shouldApply) {
            const int nextMode = candidateMode;
            const bool cpuPressure = nextMode == 1 || nextMode == 3;
            const bool gpuPressure = nextMode == 2 || nextMode == 3;
            const char* id = nextMode == 1 ? "omnicore-n64-precision-v2-cpu" :
                (nextMode == 2 ? "omnicore-n64-precision-v2-gpu" : "omnicore-n64-precision-v2-mixed");
            impl_->perfHint.notifyIncrease(cpuPressure, gpuPressure, id);
            if (adpfReady) {
                // Smaller target changes than Alpha 15 reduce thermal oscillation.
                impl_->perfHint.setTargetScale(nextMode == 1 ? 0.92 : (nextMode == 2 ? 0.94 : 0.90));
            }
            governorMode = nextMode;
            precisionGovernorMode_.store(nextMode, std::memory_order_release);
            governorHeadroomUntil = controlNow + std::chrono::milliseconds(1800);
            lastGovernorChange = controlNow;
            stableStreak = 0;
        }

        const bool recoveryConfidence = slowRatio <= 1.045f && pressureDebt <= 0.12f &&
            jitterEwma <= targetMs * 0.15f;
        if (governorMode != 0 && recoveryConfidence && stableStreak >= 120 &&
            controlNow >= governorHeadroomUntil && modeDwell >= std::chrono::seconds(3)) {
            governorMode = 0;
            precisionGovernorMode_.store(0, std::memory_order_release);
            precisionGovernorConfidence_.store(0.0f, std::memory_order_release);
            impl_->perfHint.notifyReset(true, true, "omnicore-n64-precision-v2-recovery");
            if (adpfReady) {
                impl_->perfHint.setTargetScale(
                    warmStartActive_.load(std::memory_order_acquire) ? 0.96 : 1.0);
            }
            stableStreak = 0;
            candidateStreak = 0;
            candidateMode = 0;
            lastGovernorChange = controlNow;
        }

        if (warmStartActive_.load(std::memory_order_acquire)) {
            const auto warmElapsed = controlNow - warmStartBegan;
            const bool minimumWarmupDone = warmElapsed >= std::chrono::seconds(4);
            const bool stableEnough = warmStableFrames >= 90;
            const bool maximumWarmupDone = warmElapsed >= std::chrono::seconds(10);
            if ((minimumWarmupDone && stableEnough) || maximumWarmupDone) {
                warmStartActive_.store(false, std::memory_order_release);
                if (governorMode == 0) {
                    impl_->perfHint.notifyReset(true, true, "omnicore-n64-v2-steady-state");
                    if (adpfReady) impl_->perfHint.setTargetScale(1.0);
                }
            }
        }

'''
)

# Initial ADPF request is milder because v2 waits for confidence before boosting.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''        impl_->perfHint.setTargetScale(0.94);\n''',
    '''        impl_->perfHint.setTargetScale(0.96);\n''',
    1
)

# Native bridge telemetry and runtime identity.
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    'OmniCore N64 Runtime 0.10.14 • Mupen64Plus-Next • GLES3 + AAudio host v13 • PrecisionGovernor + PassiveWarmCache + DirectPresenter + GameAware SmartAnalog • ',
    'OmniCore N64 Runtime 0.10.15 • Mupen64Plus-Next • GLES3 + AAudio host v14 • PrecisionGovernor v2 + PassiveWarmCache + DirectPresenter + GameAware SmartAnalog • '
)
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''    const jfloat values[20] = {\n''',
    '''    const jfloat values[22] = {\n'''
)
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''        telemetry.passiveWarmCacheReady,\n        telemetry.precisionGovernorMode\n    };\n    jfloatArray result = env->NewFloatArray(20);\n    if (result) env->SetFloatArrayRegion(result, 0, 20, values);\n''',
    '''        telemetry.passiveWarmCacheReady,\n        telemetry.precisionGovernorMode,\n        telemetry.precisionGovernorConfidence,\n        telemetry.frameJitterMs\n    };\n    jfloatArray result = env->NewFloatArray(22);\n    if (result) env->SetFloatArrayRegion(result, 0, 22, values);\n'''
)

# Kotlin telemetry surface.
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''        val passiveWarmCacheReady: Boolean = false,\n        val precisionGovernorMode: Int = 0\n''',
    '''        val passiveWarmCacheReady: Boolean = false,\n        val precisionGovernorMode: Int = 0,\n        val precisionGovernorConfidence: Float = 0f,\n        val frameJitterMs: Float = 0f\n'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''            passiveWarmCacheReady = raw.getOrElse(18) { 0f } >= 0.5f,\n            precisionGovernorMode = raw.getOrElse(19) { 0f }.roundToInt()\n''',
    '''            passiveWarmCacheReady = raw.getOrElse(18) { 0f } >= 0.5f,\n            precisionGovernorMode = raw.getOrElse(19) { 0f }.roundToInt(),\n            precisionGovernorConfidence = raw.getOrElse(20) { 0f }.coerceIn(0f, 1f),\n            frameJitterMs = raw.getOrElse(21) { 0f }.coerceAtLeast(0f)\n'''
)

# Performance status shows confidence and jitter directly on device.
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    '''            append(when (t.precisionGovernorMode) {\n                1 -> " • P-GOV CPU"\n                2 -> " • P-GOV GPU"\n                3 -> " • P-GOV MIX"\n                else -> " • P-GOV Stable"\n            })\n''',
    '''            append(when (t.precisionGovernorMode) {\n                1 -> " • P-GOV2 CPU"\n                2 -> " • P-GOV2 GPU"\n                3 -> " • P-GOV2 MIX"\n                else -> " • P-GOV2 Stable"\n            })\n            append(" ")\n            append((t.precisionGovernorConfidence * 100f).roundToInt())\n            append("%")\n            append(" • jitter ")\n            append("%.2f".format(t.frameJitterMs))\n            append(" ms")\n'''
)

# Kotlin-side session hysteresis is slightly slower as a second stability layer.
replace(
    "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt",
    '''                    if (pressureStreak >= 2 && now - lastTransitionAt >= 3_500L) {\n''',
    '''                    if (pressureStreak >= 3 && now - lastTransitionAt >= 5_000L) {\n'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt",
    '''                    if (healthyStreak >= 4 && now - lastTransitionAt >= 8_000L) {\n''',
    '''                    if (healthyStreak >= 5 && now - lastTransitionAt >= 10_000L) {\n'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt",
    'PrecisionGovernor:',
    'PrecisionGovernor v2:',
    12
)

print("Alpha 16 PrecisionGovernor v2 migration applied")
