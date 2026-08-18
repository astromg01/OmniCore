#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Version -----------------------------------------------------------------
gradle_path = "app/build.gradle.kts"
text = read(gradle_path)
text = replace_once(text, 'versionCode = 26', 'versionCode = 27', 'versionCode')
text = replace_once(text, 'versionName = "0.10.10"', 'versionName = "0.10.11"', 'versionName')
write(gradle_path, text)


# SmartPerf: adaptive warm-up instead of a fixed ten-second grace window. ---
smart_path = "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt"
text = read(smart_path)
old = '''        private var lastTransitionAt = 0L
        private var lastAudioStressAt = 0L
        private val startupGraceUntil = SystemClock.elapsedRealtime() + 10_000L

        fun initial(): Decision = current.copy(
            audioBufferBursts = max(current.audioBufferBursts, 6),
            reason = "SmartPerf N64 aquecendo shaders e protegendo áudio inicial"
        )
'''
new = '''        private var lastTransitionAt = 0L
        private var lastAudioStressAt = 0L
        private val warmupStartedAt = SystemClock.elapsedRealtime()
        private val warmupMinUntil = warmupStartedAt + 12_000L
        private val warmupMaxUntil = warmupStartedAt + 45_000L
        private var warmupStableWindows = 0
        private var warmupActive = true

        fun initial(): Decision = current.copy(
            audioBufferBursts = max(current.audioBufferBursts, 7),
            preferPowerEfficiency = false,
            aggressiveFramePacing = false,
            leanGraphics = false,
            reason = "WarmStart N64 protegendo a fase de compilação e áudio inicial"
        )
'''
text = replace_once(text, old, new, 'SmartPerf warmup fields')
old = '''            val candidate = resolve(profile, requested, signals, telemetry)
            val now = SystemClock.elapsedRealtime()
            if (recentUnderruns > 0 || telemetry.audioCritical) lastAudioStressAt = now
            fun protectAudio(decision: Decision): Decision = if (
                now < startupGraceUntil || now - lastAudioStressAt < 12_000L
            ) {
                decision.copy(
                    audioBufferBursts = max(decision.audioBufferBursts, 6),
                    reason = if (recentUnderruns > 0) "SmartPerf N64 recuperando áudio sem oscilar buffer" else decision.reason
                )
            } else decision
            val emergency = signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
                recentUnderruns >= 3 || telemetry.audioCritical
'''
new = '''            val candidate = resolve(profile, requested, signals, telemetry)
            val now = SystemClock.elapsedRealtime()
            if (recentUnderruns > 0 || telemetry.audioCritical) lastAudioStressAt = now

            val stableWarmupWindow = telemetry.hasUsefulWindow &&
                telemetry.p95FrameMs <= telemetry.targetFrameMs * 1.12f &&
                telemetry.droppedFrames <= 2 &&
                recentUnderruns == 0 &&
                !telemetry.audioCritical
            if (warmupActive) {
                if (stableWarmupWindow && now >= warmupMinUntil) {
                    warmupStableWindows++
                } else if (!stableWarmupWindow) {
                    warmupStableWindows = 0
                }
                if (warmupStableWindows >= 3 || now >= warmupMaxUntil) warmupActive = false
            }

            fun protectAudio(decision: Decision): Decision = if (
                warmupActive || now - lastAudioStressAt < 12_000L
            ) {
                decision.copy(
                    audioBufferBursts = max(decision.audioBufferBursts, if (warmupActive) 7 else 6),
                    reason = if (recentUnderruns > 0) "SmartPerf N64 recuperando áudio sem oscilar buffer" else decision.reason
                )
            } else decision

            fun protectWarmup(decision: Decision): Decision {
                if (!warmupActive || signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || signals.memoryPressure) {
                    return decision
                }
                return decision.copy(
                    effective = decision.effective.copy(
                        internalResolution = requested.internalResolution,
                        framebufferEmulation = requested.framebufferEmulation || requested.aspectRatio.wide,
                        threadedRenderer = requested.threadedRenderer
                    ),
                    audioBufferBursts = max(decision.audioBufferBursts, 7),
                    preferPowerEfficiency = false,
                    aggressiveFramePacing = false,
                    leanGraphics = false,
                    reason = "WarmStart N64 mantendo qualidade enquanto a sessão estabiliza"
                )
            }

            if (warmupActive &&
                signals.thermalStatus < PowerManager.THERMAL_STATUS_SEVERE &&
                !signals.memoryPressure
            ) {
                pressureStreak = 0
                healthyStreak = 0
                current = current.copy(
                    audioBufferBursts = max(current.audioBufferBursts, candidate.audioBufferBursts),
                    reason = "WarmStart N64 absorvendo picos de primeira execução"
                )
                return protectWarmup(protectAudio(current))
            }

            val emergency = signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
                recentUnderruns >= 3 || telemetry.audioCritical
'''
text = replace_once(text, old, new, 'SmartPerf warmup adaptation')
text = text.replace('return protectAudio(current)', 'return protectWarmup(protectAudio(current))')
if 'startupGraceUntil' in text:
    raise SystemExit('SmartPerf: stale startupGraceUntil remains')
write(smart_path, text)


# Kotlin JNI telemetry: expose WarmStart and shader-cache state. ------------
bridge_path = "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt"
text = read(bridge_path)
text = replace_once(
    text,
    '''        val adpfActive: Boolean = false,\n        val burstShieldActive: Boolean = false\n''',
    '''        val adpfActive: Boolean = false,\n        val burstShieldActive: Boolean = false,\n        val warmStartActive: Boolean = false,\n        val shaderCacheEnabled: Boolean = false\n''',
    'Kotlin telemetry fields'
)
text = replace_once(
    text,
    '''            adpfActive = raw.getOrElse(11) { 0f } >= 0.5f,\n            burstShieldActive = raw.getOrElse(12) { 0f } >= 0.5f\n''',
    '''            adpfActive = raw.getOrElse(11) { 0f } >= 0.5f,\n            burstShieldActive = raw.getOrElse(12) { 0f } >= 0.5f,\n            warmStartActive = raw.getOrElse(13) { 0f } >= 0.5f,\n            shaderCacheEnabled = raw.getOrElse(14) { 0f } >= 0.5f\n''',
    'Kotlin telemetry decode'
)
write(bridge_path, text)


# Performance overlay: make the new mechanisms visible on-device. ----------
activity_path = "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt"
text = read(activity_path)
old = '''            append(if (t.adpfActive) " • ADPF" else "")
            append(if (t.burstShieldActive) " • BurstShield" else "")
            append("\\nÁudio ")
'''
new = '''            append(if (t.adpfActive) " • ADPF" else "")
            append(if (t.burstShieldActive) " • BurstShield" else "")
            append(if (t.warmStartActive) " • WarmStart" else "")
            append(if (t.shaderCacheEnabled) " • ShaderCache" else "")
            append("\\nÁudio ")
'''
text = replace_once(text, old, new, 'performance overlay flags')
write(activity_path, text)


# Native telemetry and runtime state. --------------------------------------
header_path = "app/src/main/cpp/n64/n64_libretro_host.h"
text = read(header_path)
text = replace_once(
    text,
    '''    float adpfActive = 0.0f;\n    float burstShieldActive = 0.0f;\n''',
    '''    float adpfActive = 0.0f;\n    float burstShieldActive = 0.0f;\n    float warmStartActive = 0.0f;\n    float shaderCacheEnabled = 0.0f;\n''',
    'native telemetry fields'
)
text = replace_once(
    text,
    '''    std::atomic<bool> adpfActive_{false};\n    std::atomic<bool> burstShieldActive_{false};\n''',
    '''    std::atomic<bool> adpfActive_{false};\n    std::atomic<bool> burstShieldActive_{false};\n    std::atomic<bool> warmStartActive_{false};\n    std::atomic<bool> shaderCacheEnabled_{false};\n''',
    'native atomics'
)
write(header_path, text)


# Native host: persistent shader cache + adaptive ADPF headroom. -----------
host_path = "app/src/main/cpp/n64/n64_libretro_host.cpp"
text = read(host_path)
text = replace_once(
    text,
    '''        targetNs_ = static_cast<std::int64_t>(1.0e9 / std::clamp(fps, 40.0, 75.0));\n        session_ = createSession_(manager_, &tid, 1u, targetNs_);\n''',
    '''        baseTargetNs_ = static_cast<std::int64_t>(1.0e9 / std::clamp(fps, 40.0, 75.0));\n        targetNs_ = baseTargetNs_;\n        session_ = createSession_(manager_, &tid, 1u, targetNs_);\n''',
    'ADPF base target'
)
text = replace_once(
    text,
    '''    void report(std::int64_t actualNs) {\n        if (session_ && report_ && actualNs > 0) report_(session_, actualNs);\n    }\n\n    bool bindSurface(ANativeWindow* window) {\n''',
    '''    void report(std::int64_t actualNs) {\n        if (session_ && report_ && actualNs > 0) report_(session_, actualNs);\n    }\n\n    void setTargetScale(double scale) {\n        if (!session_ || !updateTarget_ || baseTargetNs_ <= 0) return;\n        scale = std::clamp(scale, 0.70, 1.00);\n        const auto requested = std::max<std::int64_t>(\n            1, static_cast<std::int64_t>(std::llround(static_cast<double>(baseTargetNs_) * scale)));\n        const auto tolerance = std::max<std::int64_t>(1, baseTargetNs_ / 100);\n        if (std::llabs(requested - targetNs_) <= tolerance) return;\n        if (updateTarget_(session_, requested) == 0) targetNs_ = requested;\n    }\n\n    bool bindSurface(ANativeWindow* window) {\n''',
    'ADPF target scale method'
)
text = replace_once(
    text,
    '''        targetNs_ = 0;\n        lastSpikeAt_ = {};\n''',
    '''        targetNs_ = 0;\n        baseTargetNs_ = 0;\n        lastSpikeAt_ = {};\n''',
    'ADPF close base target'
)
text = replace_once(
    text,
    '''    std::int64_t targetNs_ = 0;\n    std::chrono::steady_clock::time_point lastSpikeAt_{};\n''',
    '''    std::int64_t targetNs_ = 0;\n    std::int64_t baseTargetNs_ = 0;\n    std::chrono::steady_clock::time_point lastSpikeAt_{};\n''',
    'ADPF private base target'
)
text = replace_once(
    text,
    '''    adpfActive_.store(false, std::memory_order_release);\n    burstShieldActive_.store(false, std::memory_order_release);\n''',
    '''    adpfActive_.store(false, std::memory_order_release);\n    burstShieldActive_.store(false, std::memory_order_release);\n    warmStartActive_.store(false, std::memory_order_release);\n    shaderCacheEnabled_.store(false, std::memory_order_release);\n''',
    'start telemetry reset'
)
text = replace_once(
    text,
    '''    options_["mupen64plus-EnableFragmentDepthWrite"] = "False";\n    options_["mupen64plus-BackgroundMode"] = "OnePiece";\n''',
    '''    options_["mupen64plus-EnableFragmentDepthWrite"] = "False";\n    // GLideN64 stores compiled combiner programs per ROM/GPU in the writable\n    // N64 system directory. First execution may still compile new programs;\n    // later launches can restore them instead of paying that cost mid-scene.\n    options_["mupen64plus-EnableShadersStorage"] = "True";\n    options_["mupen64plus-EnableTextureCache"] = "False";\n    shaderCacheEnabled_.store(true, std::memory_order_release);\n    options_["mupen64plus-BackgroundMode"] = "OnePiece";\n''',
    'shader cache core option'
)
text = replace_once(
    text,
    '''            adpfActive_.store(false, std::memory_order_release);\n            burstShieldActive_.store(false, std::memory_order_release);\n            impl_->closeAudio();\n''',
    '''            adpfActive_.store(false, std::memory_order_release);\n            burstShieldActive_.store(false, std::memory_order_release);\n            warmStartActive_.store(false, std::memory_order_release);\n            impl_->closeAudio();\n''',
    'cleanup warmstart telemetry'
)
old = '''    if (adpfReady) {
        impl_->perfHint.notifyReset(true, true, "omnicore-n64-session");
        impl_->perfHint.bindSurface(window_);
    }
    burstShieldActive_.store(adpfReady && impl_->perfHint.burstCapable(), std::memory_order_release);
    setMessage(audioReady
'''
new = '''    if (adpfReady) {
        impl_->perfHint.notifyReset(true, true, "omnicore-n64-session");
        impl_->perfHint.bindSurface(window_);
        // Ask for transient headroom while dynarec blocks and GLideN64 shaders
        // are being seen for the first time. This does not change fidelity.
        impl_->perfHint.setTargetScale(0.84);
    }
    burstShieldActive_.store(adpfReady && impl_->perfHint.burstCapable(), std::memory_order_release);
    warmStartActive_.store(true, std::memory_order_release);
    setMessage(audioReady
'''
text = replace_once(text, old, new, 'native warmstart begin')
text = replace_once(
    text,
    '''    std::uint32_t adaptationCounter = 0;\n    int slowFrameStreak = 0;\n    bool wasPaused = false;\n''',
    '''    std::uint32_t adaptationCounter = 0;\n    int slowFrameStreak = 0;\n    int warmStableFrames = 0;\n    const auto warmStartBegan = std::chrono::steady_clock::now();\n    auto burstHeadroomUntil = std::chrono::steady_clock::time_point{};\n    bool wasPaused = false;\n''',
    'warmstart runtime state'
)
old = '''        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            impl_->adaptAudio(8);
            impl_->perfHint.notifySpike(true, true, "omnicore-n64-menu-transition");
        }
'''
new = '''        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            impl_->adaptAudio(8);
            impl_->perfHint.notifySpike(true, true, "omnicore-n64-menu-transition");
            impl_->perfHint.setTargetScale(0.76);
            burstHeadroomUntil = std::chrono::steady_clock::now() + std::chrono::milliseconds(1800);
        }
'''
text = replace_once(text, old, new, 'menu headroom')
old = '''        if (frameMs > targetMs * 1.18f) {
            ++slowFrameStreak;
        } else if (slowFrameStreak > 0) {
            --slowFrameStreak;
        }
        if (slowFrameStreak >= 4) {
            impl_->perfHint.notifyIncrease(true, true, "omnicore-n64-sustained-frame-pressure");
            impl_->adaptAudio(7);
            slowFrameStreak = 0;
        }

        if (++adaptationCounter >= 60u) {
            adaptationCounter = 0;
            impl_->adaptAudio(audioTargetBursts_.load(std::memory_order_acquire));
        }
'''
new = '''        const auto controlNow = std::chrono::steady_clock::now();
        const bool slowFrame = frameMs > targetMs * 1.18f;
        const bool verySlowFrame = frameMs > targetMs * 1.55f;
        if (slowFrame) {
            ++slowFrameStreak;
            warmStableFrames = 0;
        } else {
            if (slowFrameStreak > 0) --slowFrameStreak;
            if (frameMs <= targetMs * 1.08f) {
                warmStableFrames = std::min(warmStableFrames + 1, 240);
            } else if (warmStableFrames > 0) {
                --warmStableFrames;
            }
        }

        if (verySlowFrame) {
            impl_->perfHint.notifySpike(true, true, "omnicore-n64-frame-spike");
            impl_->perfHint.setTargetScale(0.78);
            burstHeadroomUntil = std::max(
                burstHeadroomUntil, controlNow + std::chrono::milliseconds(2200));
        }
        if (slowFrameStreak >= 4) {
            impl_->perfHint.notifyIncrease(true, true, "omnicore-n64-sustained-frame-pressure");
            impl_->adaptAudio(7);
            impl_->perfHint.setTargetScale(0.82);
            burstHeadroomUntil = std::max(
                burstHeadroomUntil, controlNow + std::chrono::milliseconds(2600));
            slowFrameStreak = 0;
        }

        if (warmStartActive_.load(std::memory_order_acquire)) {
            const auto warmElapsed = controlNow - warmStartBegan;
            const bool minimumWarmupDone = warmElapsed >= std::chrono::seconds(12);
            const bool stableEnough = warmStableFrames >= 180;
            const bool maximumWarmupDone = warmElapsed >= std::chrono::seconds(45);
            if ((minimumWarmupDone && stableEnough) || maximumWarmupDone) {
                warmStartActive_.store(false, std::memory_order_release);
                impl_->perfHint.notifyReset(true, true, "omnicore-n64-steady-state");
            }
        }

        if (adpfReady) {
            if (controlNow < burstHeadroomUntil) {
                // The event path already selected a stronger target; keep it.
            } else if (warmStartActive_.load(std::memory_order_acquire)) {
                impl_->perfHint.setTargetScale(0.84);
            } else {
                impl_->perfHint.setTargetScale(1.0);
            }
        }

        if (++adaptationCounter >= 60u) {
            adaptationCounter = 0;
            int requestedBursts = audioTargetBursts_.load(std::memory_order_acquire);
            if (warmStartActive_.load(std::memory_order_acquire)) requestedBursts = std::max(requestedBursts, 7);
            if (controlNow < burstHeadroomUntil) requestedBursts = std::max(requestedBursts, 7);
            impl_->adaptAudio(requestedBursts);
        }
'''
text = replace_once(text, old, new, 'runtime warmstart controller')
# Telemetry export in LibretroHost::telemetry()
old = '''    out.adpfActive = adpfActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.burstShieldActive = burstShieldActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
'''
new = '''    out.adpfActive = adpfActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.burstShieldActive = burstShieldActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.warmStartActive = warmStartActive_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
    out.shaderCacheEnabled = shaderCacheEnabled_.load(std::memory_order_acquire) ? 1.0f : 0.0f;
'''
text = replace_once(text, old, new, 'native telemetry export')
write(host_path, text)


# Native JNI runtime version + 15-value telemetry. -------------------------
native_bridge_path = "app/src/main/cpp/n64/n64_native_bridge.cpp"
text = read(native_bridge_path)
text = replace_once(
    text,
    'OmniCore N64 Runtime 0.10.10 • Mupen64Plus-Next • GLES3 + AAudio host v9 • BurstShield • ',
    'OmniCore N64 Runtime 0.10.11 • Mupen64Plus-Next • GLES3 + AAudio host v10 • BurstShield + WarmStart + ShaderCache • ',
    'runtime version'
)
text = replace_once(text, 'const jfloat values[13] = {', 'const jfloat values[15] = {', 'telemetry array size')
text = replace_once(
    text,
    '''        telemetry.adpfActive,\n        telemetry.burstShieldActive\n    };\n    jfloatArray result = env->NewFloatArray(13);\n    if (result) env->SetFloatArrayRegion(result, 0, 13, values);\n''',
    '''        telemetry.adpfActive,\n        telemetry.burstShieldActive,\n        telemetry.warmStartActive,\n        telemetry.shaderCacheEnabled\n    };\n    jfloatArray result = env->NewFloatArray(15);\n    if (result) env->SetFloatArrayRegion(result, 0, 15, values);\n''',
    'telemetry JNI payload'
)
write(native_bridge_path, text)


# Sanity checks -------------------------------------------------------------
checks = {
    gradle_path: ['versionCode = 27', 'versionName = "0.10.11"'],
    smart_path: ['warmupMaxUntil', 'warmupStableWindows', 'WarmStart N64'],
    bridge_path: ['warmStartActive', 'shaderCacheEnabled'],
    activity_path: ['WarmStart', 'ShaderCache'],
    header_path: ['warmStartActive', 'shaderCacheEnabled'],
    host_path: [
        'mupen64plus-EnableShadersStorage',
        'setTargetScale(0.84)',
        'omnicore-n64-frame-spike',
        'warmStableFrames',
        'omnicore-n64-steady-state'
    ],
    native_bridge_path: ['Runtime 0.10.11', 'const jfloat values[15]']
}
for rel, needles in checks.items():
    content = read(rel)
    for needle in needles:
        if needle not in content:
            raise SystemExit(f"missing {needle!r} in {rel}")

print('Alpha 12 migration applied successfully')
