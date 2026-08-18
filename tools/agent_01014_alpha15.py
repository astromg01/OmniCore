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
        raise SystemExit(f"{path}: expected {count} occurrence(s), found {found}: {old[:120]!r}")
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
replace("app/build.gradle.kts", "versionCode = 29", "versionCode = 30")
replace("app/build.gradle.kts", 'versionName = "0.10.13"', 'versionName = "0.10.14"')

# SmartPerf 3: preserve requested visual quality and let live native telemetry
# distinguish CPU/GPU/audio pressure instead of guessing from core count.
write(
    "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt",
    r'''package com.omnicore.emulator.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.omnicore.emulator.settings.N64PerformanceProfile
import com.omnicore.emulator.settings.N64Settings
import kotlin.math.max

/** Nintendo 64 adaptive policy. Visual quality is never reduced automatically. */
object N64SmartPerf {
    enum class Level { ECO, BALANCED, TURBO }

    data class Telemetry(
        val averageFrameMs: Float = 0f,
        val p95FrameMs: Float = 0f,
        val droppedFrames: Int = 0,
        val audioUnderruns: Int = 0,
        val sampleWindowFrames: Int = 0,
        val audioFillMs: Float = 0f,
        val audioBufferMs: Float = 0f,
        val targetFps: Float = 0f,
        val pacingCorrectionPct: Float = 0f,
        val presentAverageMs: Float = 0f,
        val presentP95Ms: Float = 0f
    ) {
        val hasUsefulWindow: Boolean get() = sampleWindowFrames >= 90
        val targetFrameMs: Float
            get() = if (targetFps in 40f..75f) 1000f / targetFps else 1000f / 60f
        val audioCritical: Boolean
            get() = audioBufferMs > 0f && audioFillMs in 0f..max(8f, audioBufferMs * 0.35f)
        val gpuBound: Boolean
            get() = hasUsefulWindow && presentP95Ms >= max(3.5f, targetFrameMs * 0.22f) &&
                p95FrameMs > 0f && presentP95Ms >= p95FrameMs * 0.25f
    }

    data class Decision(
        val level: Level,
        val effective: N64Settings.Config,
        val audioBufferBursts: Int,
        val preferPowerEfficiency: Boolean,
        val aggressiveFramePacing: Boolean,
        val allowResolutionPromotion: Boolean,
        val leanGraphics: Boolean = false,
        val reason: String
    )

    private data class RuntimeSignals(
        val thermalStatus: Int,
        val memoryPressure: Boolean,
        val powerSave: Boolean
    )

    /** Session hysteresis controls safe live knobs; renderer/core changes wait for restart. */
    class Session(context: Context, private val requested: N64Settings.Config) {
        private val appContext = context.applicationContext
        private val profile = N64PerformanceProfile.detect(appContext)
        private var current = resolve(profile, requested, runtimeSignals(appContext), Telemetry())
        private var lastUnderruns = 0
        private var pressureStreak = 0
        private var healthyStreak = 0
        private var lastTransitionAt = 0L
        private var lastAudioStressAt = 0L
        private val warmupStartedAt = SystemClock.elapsedRealtime()
        private val warmupMinUntil = warmupStartedAt + 4_000L
        private val warmupMaxUntil = warmupStartedAt + 12_000L
        private var warmupStableWindows = 0
        private var warmupActive = true

        fun initial(): Decision = current.copy(
            effective = protectedConfig(current.effective),
            audioBufferBursts = max(current.audioBufferBursts, 6),
            preferPowerEfficiency = false,
            aggressiveFramePacing = false,
            allowResolutionPromotion = false,
            leanGraphics = false,
            reason = "Precision WarmStart: qualidade preservada e áudio protegido"
        )

        fun adapt(raw: Telemetry): Decision {
            val recentUnderruns = (raw.audioUnderruns - lastUnderruns).coerceAtLeast(0)
            lastUnderruns = raw.audioUnderruns
            val telemetry = raw.copy(audioUnderruns = recentUnderruns)
            val signals = runtimeSignals(appContext)
            val candidate = resolve(profile, requested, signals, telemetry)
            val now = SystemClock.elapsedRealtime()
            if (recentUnderruns > 0 || telemetry.audioCritical) lastAudioStressAt = now

            val stableWarmupWindow = telemetry.hasUsefulWindow &&
                telemetry.p95FrameMs <= telemetry.targetFrameMs * 1.10f &&
                telemetry.droppedFrames <= 2 &&
                recentUnderruns == 0 &&
                !telemetry.audioCritical
            if (warmupActive) {
                if (stableWarmupWindow && now >= warmupMinUntil) warmupStableWindows++
                else if (!stableWarmupWindow) warmupStableWindows = 0
                if (warmupStableWindows >= 2 || now >= warmupMaxUntil) warmupActive = false
            }

            fun protectAudio(decision: Decision): Decision {
                val recovering = now - lastAudioStressAt < 8_000L
                val target = when {
                    recentUnderruns >= 2 || telemetry.audioCritical -> 7
                    recentUnderruns > 0 || recovering -> 6
                    warmupActive -> 6
                    else -> decision.audioBufferBursts
                }
                return decision.copy(
                    effective = protectedConfig(decision.effective),
                    audioBufferBursts = max(decision.audioBufferBursts, target),
                    allowResolutionPromotion = false,
                    leanGraphics = false,
                    reason = if (recentUnderruns > 0) "PrecisionGovernor: recuperando áudio medido" else decision.reason
                )
            }

            if (warmupActive && signals.thermalStatus < PowerManager.THERMAL_STATUS_SEVERE) {
                pressureStreak = 0
                healthyStreak = 0
                current = protectAudio(current.copy(
                    effective = protectedConfig(current.effective),
                    reason = "Precision WarmStart estabilizando sem alterar qualidade"
                ))
                return current
            }

            val emergency = signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
                recentUnderruns >= 3 || telemetry.audioCritical
            if (emergency) {
                pressureStreak = 0
                healthyStreak = 0
                current = protectAudio(candidate)
                lastTransitionAt = now
                return current
            }

            when {
                candidate.level.ordinal < current.level.ordinal -> {
                    pressureStreak++
                    healthyStreak = 0
                    if (pressureStreak >= 2 && now - lastTransitionAt >= 3_500L) {
                        current = candidate
                        lastTransitionAt = now
                        pressureStreak = 0
                    }
                }
                candidate.level.ordinal > current.level.ordinal -> {
                    healthyStreak++
                    pressureStreak = 0
                    if (healthyStreak >= 4 && now - lastTransitionAt >= 8_000L) {
                        current = candidate
                        lastTransitionAt = now
                        healthyStreak = 0
                    }
                }
                else -> {
                    pressureStreak = 0
                    healthyStreak = 0
                    current = candidate
                }
            }
            current = protectAudio(current)
            return current
        }

        private fun protectedConfig(config: N64Settings.Config): N64Settings.Config = config.copy(
            cpuMode = N64Settings.CpuMode.DYNAREC,
            rspMode = N64Settings.RspMode.HLE,
            internalResolution = requested.internalResolution,
            framebufferEmulation = requested.framebufferEmulation || requested.aspectRatio.wide,
            threadedRenderer = requested.threadedRenderer
        )
    }

    fun initial(context: Context, requested: N64Settings.Config): Decision =
        resolve(N64PerformanceProfile.detect(context), requested, runtimeSignals(context), Telemetry())

    fun adapt(context: Context, requested: N64Settings.Config, telemetry: Telemetry): Decision =
        resolve(N64PerformanceProfile.detect(context), requested, runtimeSignals(context), telemetry)

    internal fun resolve(
        profile: N64PerformanceProfile.Profile,
        requested: N64Settings.Config,
        thermalStatus: Int,
        telemetry: Telemetry
    ): Decision = resolve(
        profile,
        requested,
        RuntimeSignals(thermalStatus, memoryPressure = false, powerSave = false),
        telemetry
    )

    private fun resolve(
        profile: N64PerformanceProfile.Profile,
        requested: N64Settings.Config,
        signals: RuntimeSignals,
        telemetry: Telemetry
    ): Decision {
        val severeThermal = signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val warmThermal = signals.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val target = telemetry.targetFrameMs
        val framePressure = telemetry.hasUsefulWindow && (
            telemetry.p95FrameMs >= target * 1.18f ||
                telemetry.droppedFrames >= 5 ||
                telemetry.audioUnderruns >= 1
            )
        val heavyPressure = telemetry.hasUsefulWindow && (
            telemetry.p95FrameMs >= target * 1.42f ||
                telemetry.droppedFrames >= 12 ||
                telemetry.audioUnderruns >= 3 || telemetry.audioCritical
            )

        fun protectedConfig(): N64Settings.Config = requested.copy(
            cpuMode = N64Settings.CpuMode.DYNAREC,
            rspMode = N64Settings.RspMode.HLE,
            internalResolution = requested.internalResolution,
            framebufferEmulation = requested.framebufferEmulation || requested.aspectRatio.wide,
            threadedRenderer = requested.threadedRenderer
        )

        if (severeThermal || heavyPressure) {
            return Decision(
                level = Level.ECO,
                effective = protectedConfig(),
                audioBufferBursts = if (telemetry.audioCritical || telemetry.audioUnderruns >= 2) 7 else 6,
                preferPowerEfficiency = severeThermal,
                aggressiveFramePacing = false,
                allowResolutionPromotion = false,
                leanGraphics = false,
                reason = when {
                    severeThermal -> "PrecisionGovernor: pressão térmica, qualidade visual preservada"
                    telemetry.audioCritical -> "PrecisionGovernor: pressão de áudio detectada"
                    telemetry.gpuBound -> "PrecisionGovernor: gargalo de apresentação/GPU medido"
                    else -> "PrecisionGovernor: pressão sustentada de emulação medida"
                }
            )
        }

        if (warmThermal || framePressure || signals.memoryPressure || signals.powerSave ||
            profile.tier == N64PerformanceProfile.Tier.LOW) {
            return Decision(
                level = Level.BALANCED,
                effective = protectedConfig(),
                audioBufferBursts = if (telemetry.audioUnderruns > 0 || telemetry.audioCritical) 6 else 5,
                preferPowerEfficiency = warmThermal || signals.powerSave,
                aggressiveFramePacing = false,
                allowResolutionPromotion = false,
                leanGraphics = false,
                reason = when {
                    warmThermal -> "PrecisionGovernor: desempenho sustentável sem reduzir resolução"
                    signals.memoryPressure -> "PrecisionGovernor: pressão de memória monitorada"
                    signals.powerSave -> "PrecisionGovernor: economia de energia detectada"
                    telemetry.gpuBound -> "PrecisionGovernor: pressão GPU/present detectada"
                    framePressure -> "PrecisionGovernor: frame time fora do orçamento"
                    else -> "PrecisionGovernor: perfil conservador de hardware"
                }
            )
        }

        val highMargin = telemetry.hasUsefulWindow &&
            telemetry.p95FrameMs <= target * 1.05f &&
            telemetry.droppedFrames <= 1 &&
            telemetry.audioUnderruns == 0 && !telemetry.audioCritical
        return Decision(
            level = if (highMargin) Level.TURBO else Level.BALANCED,
            effective = protectedConfig(),
            audioBufferBursts = if (highMargin) 3 else 4,
            preferPowerEfficiency = false,
            aggressiveFramePacing = highMargin,
            allowResolutionPromotion = false,
            leanGraphics = false,
            reason = if (highMargin) {
                "PrecisionGovernor: margem sustentada confirmada"
            } else {
                "PrecisionGovernor: equilíbrio medido"
            }
        )
    }

    private fun runtimeSignals(context: Context): RuntimeSignals {
        val am = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val memoryPressure = memory.lowMemory ||
            (memory.threshold > 0L && memory.availMem in 1L..(memory.threshold * 3L / 2L))
        val power = context.getSystemService(PowerManager::class.java)
        return RuntimeSignals(
            thermalStatus = currentThermalStatus(context),
            memoryPressure = memoryPressure,
            powerSave = power?.isPowerSaveMode == true
        )
    }

    fun currentThermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return PowerManager.THERMAL_STATUS_NONE
        return runCatching {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE
        }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
    }
}
'''
)

# Kotlin telemetry: passive cache + precise native pressure classifier.
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''        val shaderCacheReady: Boolean = false,\n        val smartAnalogDpadActive: Boolean = false,\n        val smartPrecompileReady: Boolean = false\n''',
    '''        val shaderCacheReady: Boolean = false,\n        val smartAnalogDpadActive: Boolean = false,\n        val passiveWarmCacheReady: Boolean = false,\n        val precisionGovernorMode: Int = 0\n'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''            presentAverageMs = presentAverageMs,\n            presentP95Ms = presentP95Ms,\n            smartPrecompileReady = smartPrecompileReady\n''',
    '''            presentAverageMs = presentAverageMs,\n            presentP95Ms = presentP95Ms\n'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''            shaderCacheReady = raw.getOrElse(16) { 0f } >= 0.5f,\n            smartAnalogDpadActive = raw.getOrElse(17) { 0f } >= 0.5f,\n            smartPrecompileReady = raw.getOrElse(18) { 0f } >= 0.5f\n''',
    '''            shaderCacheReady = raw.getOrElse(16) { 0f } >= 0.5f,\n            smartAnalogDpadActive = raw.getOrElse(17) { 0f } >= 0.5f,\n            passiveWarmCacheReady = raw.getOrElse(18) { 0f } >= 0.5f,\n            precisionGovernorMode = raw.getOrElse(19) { 0f }.roundToInt()\n'''
)

# Performance status now exposes the live bottleneck classifier.
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    '''            append(if (t.smartPrecompileReady) " • SmartPrecompile ✓" else "")\n''',
    '''            append(if (t.passiveWarmCacheReady) " • WarmCache ✓" else "")\n            append(when (t.precisionGovernorMode) {\n                1 -> " • P-GOV CPU"\n                2 -> " • P-GOV GPU"\n                3 -> " • P-GOV MIX"\n                else -> " • P-GOV Stable"\n            })\n'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    '''                internalResolution = N64Settings.InternalResolution.NATIVE,\n''',
    '''                internalResolution = base.effective.internalResolution,\n'''
)
replace(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    '''            reason = "Boot rápido N64: Dynarec + resolução nativa + GL seguro"\n''',
    '''            reason = "Boot seguro N64: Dynarec + qualidade solicitada + GL protegido"\n'''
)

# Native declarations.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '''    float shaderCacheReady = 0.0f;\n    float smartAnalogDpadActive = 0.0f;\n    float smartPrecompileReady = 0.0f;\n''',
    '''    float shaderCacheReady = 0.0f;\n    float smartAnalogDpadActive = 0.0f;\n    float passiveWarmCacheReady = 0.0f;\n    float precisionGovernorMode = 0.0f;\n'''
)
replace("app/src/main/cpp/n64/n64_libretro_host.h", "    bool runSmartPrecompile();\n", "")
replace(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '''    std::atomic<bool> shaderCacheReady_{false};\n    std::atomic<bool> shaderCacheHot_{false};\n    std::atomic<bool> smartAnalogDpadActive_{false};\n    std::atomic<bool> smartPrecompileActive_{false};\n    std::atomic<bool> smartPrecompileReady_{false};\n''',
    '''    std::atomic<bool> shaderCacheReady_{false};\n    std::atomic<bool> passiveWarmCacheReady_{false};\n    std::atomic<bool> smartAnalogDpadActive_{false};\n    std::atomic<int> precisionGovernorMode_{0};\n'''
)

# Correct the actual cache budget: fadvise must use the bounded fileBudget,
# never the whole shader file size. Reduce the warm-cache footprint to 2 MiB.
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''#ifdef POSIX_FADV_WILLNEED\n        ::posix_fadvise(fd, 0, static_cast<off_t>(file.size), POSIX_FADV_WILLNEED);\n#endif\n        const std::size_t remaining = budgetBytes - warmed;\n        const std::size_t fileBudget = std::min(file.size, remaining);\n''',
    '''        const std::size_t remaining = budgetBytes - warmed;\n        const std::size_t fileBudget = std::min(file.size, remaining);\n#ifdef POSIX_FADV_WILLNEED\n        ::posix_fadvise(fd, 0, static_cast<off_t>(fileBudget), POSIX_FADV_WILLNEED);\n#endif\n'''
)

# Remove all hidden-frame pre-execution state from startup/input.
replace("app/src/main/cpp/n64/n64_libretro_host.cpp", "    smartPrecompileActive_.store(false, std::memory_order_release);\n", "", 2)
replace("app/src/main/cpp/n64/n64_libretro_host.cpp", "    smartPrecompileReady_.store(false, std::memory_order_release);\n", "", 2)
replace("app/src/main/cpp/n64/n64_libretro_host.cpp", "    shaderCacheHot_.store(false, std::memory_order_release);\n", "    passiveWarmCacheReady_.store(false, std::memory_order_release);\n")
replace("app/src/main/cpp/n64/n64_libretro_host.cpp", "    setMessage(\"N64 BOOT 1/7 • Alpha 14 SmartPrecompile + Game Intelligence…\");\n", "    precisionGovernorMode_.store(0, std::memory_order_release);\n    setMessage(\"N64 BOOT 1/6 • Alpha 15 PrecisionGovernor + passive cache…\");\n")
replace("app/src/main/cpp/n64/n64_libretro_host.cpp", "    if (smartPrecompileActive_.load(std::memory_order_acquire)) return 0;\n", "")
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    "    out.smartPrecompileReady = smartPrecompileReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n",
    "    out.passiveWarmCacheReady = passiveWarmCacheReady_.load(std::memory_order_acquire) ? 1.0f : 0.0f;\n    out.precisionGovernorMode = static_cast<float>(precisionGovernorMode_.load(std::memory_order_acquire));\n"
)

replace_region(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    "bool LibretroHost::runSmartPrecompile() {\n",
    "void LibretroHost::run() {\n",
    ""
)

replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''            smartPrecompileActive_.store(false, std::memory_order_release);\n''',
    '''            precisionGovernorMode_.store(0, std::memory_order_release);\n'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''        const std::size_t warmed = warmDirectoryPages(shaderDir, 12u * 1024u * 1024u);\n        shaderCacheHot_.store(warmed > 0, std::memory_order_release);\n        if (warmed > 0) logPrint(ANDROID_LOG_INFO, "SmartPrecompile cache-prefetched %zu bytes", warmed);\n''',
    '''        const std::size_t warmed = warmDirectoryPages(shaderDir, 2u * 1024u * 1024u);\n        passiveWarmCacheReady_.store(warmed > 0, std::memory_order_release);\n        if (warmed > 0) logPrint(ANDROID_LOG_INFO, "Passive shader cache warmed %zu bytes", warmed);\n'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    if (adpfReady) {\n        impl_->perfHint.notifyReset(true, true, "omnicore-n64-session");\n        impl_->perfHint.bindSurface(window_);\n        impl_->perfHint.setTargetScale(0.80);\n    }\n    const bool precompileReady = runSmartPrecompile();\n    if (adpfReady) impl_->perfHint.setTargetScale(precompileReady ? 0.84 : 0.82);\n\n''',
    '''    if (adpfReady) {\n        impl_->perfHint.notifyReset(true, true, "omnicore-n64-session");\n        impl_->perfHint.bindSurface(window_);\n        impl_->perfHint.setTargetScale(0.94);\n    }\n\n'''
)
replace(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    '''    setMessage(audioReady\n        ? (precompileReady\n            ? "N64 BOOT 6/7 • SmartPrecompile ✓ • GLideN64 + AAudio, primeiro frame…"\n            : "N64 BOOT 6/7 • GLideN64 + AAudio, primeiro frame…")\n        : (precompileReady\n            ? "N64 BOOT 6/7 • SmartPrecompile ✓ • GLideN64, primeiro frame…"\n            : "N64 BOOT 6/7 • GLideN64 pronto, primeiro frame…"));\n\n''',
    '''    const bool passiveCache = passiveWarmCacheReady_.load(std::memory_order_acquire);\n    setMessage(audioReady\n        ? (passiveCache\n            ? "N64 BOOT 5/6 • WarmCache ✓ • GLideN64 + AAudio, primeiro frame…"\n            : "N64 BOOT 5/6 • GLideN64 + AAudio, primeiro frame…")\n        : (passiveCache\n            ? "N64 BOOT 5/6 • WarmCache ✓ • GLideN64, primeiro frame…"\n            : "N64 BOOT 5/6 • GLideN64 pronto, primeiro frame…"));\n\n'''
)

# Replace the old per-spike feedback loops with a low-noise pressure governor.
replace_region(
    "app/src/main/cpp/n64/n64_libretro_host.cpp",
    "    auto nextFrame = std::chrono::steady_clock::now() + targetDuration;\n",
    "        // Single pacing owner: EGL swap interval is zero, so this is the only\n",
    r'''    auto nextFrame = std::chrono::steady_clock::now() + targetDuration;
    std::uint32_t adaptationCounter = 0;
    int pressureStreak = 0;
    int stableStreak = 0;
    int warmStableFrames = 0;
    float frameEwma = targetMs;
    float presentEwma = 0.0f;
    const auto warmStartBegan = std::chrono::steady_clock::now();
    auto lastGovernorChange = warmStartBegan - std::chrono::seconds(2);
    auto governorHeadroomUntil = std::chrono::steady_clock::time_point{};
    int governorMode = 0;  // 0 stable, 1 CPU, 2 GPU/present, 3 mixed.
    bool wasPaused = false;
    while (!stopRequested_.load(std::memory_order_acquire)) {
        const bool commandRan = processPendingCommand();
        const bool paused = paused_.load(std::memory_order_acquire);
        if (paused) {
            if (!wasPaused) {
                persistSaveRam(false);
                if (impl_->audioStream) impl_->reprimeAudio();
            }
            wasPaused = true;
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            nextFrame = std::chrono::steady_clock::now() + targetDuration;
            continue;
        }
        if (wasPaused || commandRan) {
            wasPaused = false;
            nextFrame = std::chrono::steady_clock::now() + targetDuration;
        }

        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            // Menus are commonly framebuffer-heavy. Hint GPU only; audio has its
            // own xrun/fill controller and must not expand just because Start was pressed.
            impl_->perfHint.notifySpike(false, true, "omnicore-n64-menu-present-spike");
            governorHeadroomUntil = std::max(
                governorHeadroomUntil,
                std::chrono::steady_clock::now() + std::chrono::milliseconds(800));
        }

        impl_->presentationTargetNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
            nextFrame.time_since_epoch()).count();
        const auto begin = std::chrono::steady_clock::now();
        impl_->core.run();
        const auto afterRun = std::chrono::steady_clock::now();
        const auto workNs = std::chrono::duration_cast<std::chrono::nanoseconds>(afterRun - begin).count();
        const float frameMs = std::chrono::duration<float, std::milli>(afterRun - begin).count();
        impl_->perfHint.report(workNs);
        recordFrame(frameMs, targetMs);

        const auto controlNow = std::chrono::steady_clock::now();
        const float presentMs = lastPresentMs_.load(std::memory_order_acquire);
        frameEwma += (frameMs - frameEwma) * 0.08f;
        presentEwma += (presentMs - presentEwma) * 0.12f;

        const bool sustainedSlow = frameEwma > targetMs * 1.10f;
        const bool presentHeavy = presentEwma >= std::max(3.5f, targetMs * 0.22f) &&
            presentEwma >= frameEwma * 0.25f;
        const float cpuSideMs = std::max(0.0f, frameEwma - presentEwma);

        if (sustainedSlow) {
            ++pressureStreak;
            stableStreak = 0;
            warmStableFrames = 0;
        } else {
            pressureStreak = std::max(0, pressureStreak - 1);
            if (frameEwma <= targetMs * 1.06f) {
                stableStreak = std::min(stableStreak + 1, 240);
                warmStableFrames = std::min(warmStableFrames + 1, 240);
            } else {
                stableStreak = std::max(0, stableStreak - 1);
                warmStableFrames = std::max(0, warmStableFrames - 1);
            }
        }

        // A single catastrophic frame gets one bounded spike hint. It does not
        // change quality, threading or audio policy.
        if (frameMs > targetMs * 1.70f) {
            impl_->perfHint.notifySpike(
                !presentHeavy,
                presentHeavy,
                presentHeavy ? "omnicore-n64-single-gpu-spike" : "omnicore-n64-single-cpu-spike");
        }

        const bool governorCooldownDone = controlNow - lastGovernorChange >= std::chrono::milliseconds(900);
        if (pressureStreak >= 4 && governorCooldownDone) {
            const int nextMode = presentHeavy
                ? (cpuSideMs > targetMs * 0.72f ? 3 : 2)
                : 1;
            const bool cpuPressure = nextMode == 1 || nextMode == 3;
            const bool gpuPressure = nextMode == 2 || nextMode == 3;
            const char* id = nextMode == 1 ? "omnicore-n64-precision-cpu" :
                (nextMode == 2 ? "omnicore-n64-precision-gpu" : "omnicore-n64-precision-mixed");
            impl_->perfHint.notifyIncrease(cpuPressure, gpuPressure, id);
            if (adpfReady) {
                impl_->perfHint.setTargetScale(nextMode == 1 ? 0.90 : (nextMode == 2 ? 0.92 : 0.88));
            }
            governorMode = nextMode;
            precisionGovernorMode_.store(nextMode, std::memory_order_release);
            governorHeadroomUntil = controlNow + std::chrono::milliseconds(1500);
            lastGovernorChange = controlNow;
            pressureStreak = 0;
        }

        if (governorMode != 0 && stableStreak >= 90 && controlNow >= governorHeadroomUntil) {
            governorMode = 0;
            precisionGovernorMode_.store(0, std::memory_order_release);
            impl_->perfHint.notifyReset(true, true, "omnicore-n64-precision-recovery");
            if (adpfReady) {
                impl_->perfHint.setTargetScale(
                    warmStartActive_.load(std::memory_order_acquire) ? 0.94 : 1.0);
            }
            stableStreak = 0;
            lastGovernorChange = controlNow;
        }

        if (warmStartActive_.load(std::memory_order_acquire)) {
            const auto warmElapsed = controlNow - warmStartBegan;
            const bool minimumWarmupDone = warmElapsed >= std::chrono::seconds(4);
            const bool stableEnough = warmStableFrames >= 90;
            const bool maximumWarmupDone = warmElapsed >= std::chrono::seconds(12);
            if ((minimumWarmupDone && stableEnough) || maximumWarmupDone) {
                warmStartActive_.store(false, std::memory_order_release);
                if (governorMode == 0) {
                    impl_->perfHint.notifyReset(true, true, "omnicore-n64-steady-state");
                    if (adpfReady) impl_->perfHint.setTargetScale(1.0);
                }
            }
        }

        // Audio is controlled only by actual AAudio/ring evidence. Frame spikes
        // no longer force 7/8-burst buffers and therefore cannot create latency
        // or extra memory pressure as a side effect of renderer stress.
        if (++adaptationCounter >= 60u) {
            adaptationCounter = 0;
            int requestedBursts = audioTargetBursts_.load(std::memory_order_acquire);
            if (warmStartActive_.load(std::memory_order_acquire)) requestedBursts = std::max(requestedBursts, 6);
            impl_->adaptAudio(requestedBursts);
        }
'''
)

# Runtime/telemetry JNI.
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''OmniCore N64 Runtime 0.10.13 • Mupen64Plus-Next • GLES3 + AAudio host v12 • SmartPrecompile + DirectPresenter + RenderShield + GameAware SmartAnalog + ShaderCache''',
    '''OmniCore N64 Runtime 0.10.14 • Mupen64Plus-Next • GLES3 + AAudio host v13 • PrecisionGovernor + PassiveWarmCache + DirectPresenter + GameAware SmartAnalog'''
)
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''    const jfloat values[19] = {\n''',
    '''    const jfloat values[20] = {\n'''
)
replace(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''        telemetry.shaderCacheReady,\n        telemetry.smartAnalogDpadActive,\n        telemetry.smartPrecompileReady\n    };\n    jfloatArray result = env->NewFloatArray(19);\n    if (result) env->SetFloatArrayRegion(result, 0, 19, values);\n''',
    '''        telemetry.shaderCacheReady,\n        telemetry.smartAnalogDpadActive,\n        telemetry.passiveWarmCacheReady,\n        telemetry.precisionGovernorMode\n    };\n    jfloatArray result = env->NewFloatArray(20);\n    if (result) env->SetFloatArrayRegion(result, 0, 20, values);\n'''
)

print("Alpha 15 PrecisionGovernor migration applied")
