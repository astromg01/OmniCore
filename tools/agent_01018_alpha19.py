#!/usr/bin/env python3
from pathlib import Path


def replace_exact(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    found = text.count(old)
    if found != count:
        raise SystemExit(f"{path}: expected {count} occurrence(s), found {found}: {old[:100]!r}")
    p.write_text(text.replace(old, new, count), encoding="utf-8")

# Version.
replace_exact(
    "app/build.gradle.kts",
    '        versionCode = 33\n        versionName = "0.10.17"',
    '        versionCode = 34\n        versionName = "0.10.18"',
)

# Runtime identity.
replace_exact(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    'OmniCore N64 Runtime 0.10.17 • Mupen64Plus-Next • GLES3 + AAudio host v14 • PrecisionGovernor v2 + PassiveWarmCache + DirectPresenter + GameAware SmartAnalog',
    'OmniCore N64 Runtime 0.10.18 • Mupen64Plus-Next • GLES3 + AAudio host v15 • PrecisionGovernor v2.1 + MicroBurstShield + CruiseGuard + ComfortAnalog + PassiveWarmCache + DirectPresenter',
)

# Interaction micro-burst signal lives in the native host and never touches PS1.
replace_exact(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '    std::atomic<bool> menuTransitionBoost_{false};\n',
    '    std::atomic<bool> menuTransitionBoost_{false};\n    std::atomic<bool> interactionTransitionBoost_{false};\n',
)

host_path = Path("app/src/main/cpp/n64/n64_libretro_host.cpp")
host = host_path.read_text(encoding="utf-8")

old_shape = '''AnalogVector shapeAnalog(float x, float y, int deadzonePercent, int sensitivityPercent, bool precision) {
    x = std::clamp(x, -1.0f, 1.0f);
    y = std::clamp(y, -1.0f, 1.0f);
    if (!precision) return {x, y};
    const float magnitude = std::hypot(x, y);
    if (magnitude <= 0.00001f) return {};
    const float deadzone = std::clamp(static_cast<float>(deadzonePercent) / 100.0f, 0.0f, 0.30f);
    if (magnitude <= deadzone) return {};
    const float sourceMagnitude = std::min(1.0f, magnitude);
    float normalized = (sourceMagnitude - deadzone) / std::max(0.01f, 1.0f - deadzone);
    normalized = std::pow(std::clamp(normalized, 0.0f, 1.0f), 1.12f);
    normalized *= std::clamp(static_cast<float>(sensitivityPercent) / 100.0f, 0.70f, 1.30f);
    normalized = std::clamp(normalized, 0.0f, 1.0f);
    return {x / magnitude * normalized, y / magnitude * normalized};
}'''
new_shape = '''AnalogVector shapeAnalog(float x, float y, int deadzonePercent, int sensitivityPercent, bool precision) {
    x = std::clamp(x, -1.0f, 1.0f);
    y = std::clamp(y, -1.0f, 1.0f);
    if (!precision) return {x, y};
    const float magnitude = std::hypot(x, y);
    if (magnitude <= 0.00001f) return {};
    const float deadzone = std::clamp(static_cast<float>(deadzonePercent) / 100.0f, 0.0f, 0.30f);
    if (magnitude <= deadzone) return {};
    const float sourceMagnitude = std::min(1.0f, magnitude);
    float normalized = (sourceMagnitude - deadzone) / std::max(0.01f, 1.0f - deadzone);
    normalized = std::clamp(normalized, 0.0f, 1.0f);

    // ComfortAnalog keeps a small high-precision zone around center, then
    // returns to a nearly linear response instead of suppressing the entire
    // stick range. This makes slow walking/aiming controllable without making
    // normal movement feel heavy. The last ~1.5% of the physical/touch radius
    // is treated as full travel so users do not have to pin the thumb to the rim.
    constexpr float kFineZone = 0.28f;
    if (normalized < kFineZone) {
        const float local = normalized / kFineZone;
        normalized = std::pow(local, 1.08f) * kFineZone;
    }
    normalized = std::min(1.0f, normalized / 0.985f);
    normalized *= std::clamp(static_cast<float>(sensitivityPercent) / 100.0f, 0.70f, 1.30f);
    normalized = std::clamp(normalized, 0.0f, 1.0f);
    return {x / magnitude * normalized, y / magnitude * normalized};
}'''
if host.count(old_shape) != 1:
    raise SystemExit("shapeAnalog baseline not found exactly once")
host = host.replace(old_shape, new_shape, 1)

# Reset the predictive interaction signal at session start.
old_reset = '''    smartDpadMask_.store(0, std::memory_order_release);
    smartAnalogDpadActive_.store(false, std::memory_order_release);
    setAnalog(0.0f, 0.0f, 0.0f, 0.0f);'''
new_reset = '''    smartDpadMask_.store(0, std::memory_order_release);
    smartAnalogDpadActive_.store(false, std::memory_order_release);
    interactionTransitionBoost_.store(false, std::memory_order_release);
    setAnalog(0.0f, 0.0f, 0.0f, 0.0f);'''
if host.count(old_reset) != 1:
    raise SystemExit("session input reset baseline not found")
host = host.replace(old_reset, new_reset, 1)

# Boot status was stale since Alpha 15.
host = host.replace(
    'setMessage("N64 BOOT 1/6 • Alpha 15 PrecisionGovernor + passive cache…");',
    'setMessage("N64 BOOT 1/6 • Alpha 19 PrecisionGovernor v2.1 + MicroBurstShield…");',
    1,
)

# Predict action-heavy transitions before the core consumes A/B/Z. The ADPF
# notifier already rate-limits these hints, so button mashing cannot spam it.
old_button = '''    if (pressed && retroPadId == RETRO_DEVICE_ID_JOYPAD_START) {
        // Pause/menu screens often trigger the first expensive framebuffer
        // copy. Ask the emulation thread for a larger audio cushion before
        // the core consumes the Start press.
        menuTransitionBoost_.store(true, std::memory_order_release);
    }
    const auto bit = static_cast<std::uint16_t>(1u << retroPadId);'''
new_button = '''    if (pressed && retroPadId == RETRO_DEVICE_ID_JOYPAD_START) {
        // Pause/menu screens often trigger the first expensive framebuffer
        // copy. Ask the emulation thread for GPU headroom before the core
        // consumes the Start press.
        menuTransitionBoost_.store(true, std::memory_order_release);
    }
    if (pressed && (
            retroPadId == RETRO_DEVICE_ID_JOYPAD_B ||
            retroPadId == RETRO_DEVICE_ID_JOYPAD_Y ||
            retroPadId == RETRO_DEVICE_ID_JOYPAD_L2)) {
        // A/B/Z in OmniCore's N64 mapping are the most common action/attack
        // inputs. Collisions and hit effects frequently activate fresh CPU/RDP
        // work immediately after these presses. Signal a tiny predictive burst;
        // PerformanceHintSession bounds it to at most one notification / 700 ms.
        interactionTransitionBoost_.store(true, std::memory_order_release);
    }
    const auto bit = static_cast<std::uint16_t>(1u << retroPadId);'''
if host.count(old_button) != 1:
    raise SystemExit("setButton baseline not found")
host = host.replace(old_button, new_button, 1)

# Never let diagnostic sampling block the emulation/presentation thread.
old_record = '''void LibretroHost::recordFrame(float frameMs, float targetMs) {
    targetFrameMs_.store(targetMs, std::memory_order_release);
    std::lock_guard<std::mutex> lock(telemetryMutex_);
    frameWindow_[frameWindowWrite_] = frameMs;
    frameWindowWrite_ = (frameWindowWrite_ + 1) % kTelemetryCapacity;
    frameWindowCount_ = std::min(frameWindowCount_ + 1, kTelemetryCapacity);
}

void LibretroHost::recordPresent(float presentMs) {
    lastPresentMs_.store(presentMs, std::memory_order_release);
    std::lock_guard<std::mutex> lock(telemetryMutex_);
    presentWindow_[presentWindowWrite_] = presentMs;
    presentWindowWrite_ = (presentWindowWrite_ + 1) % kTelemetryCapacity;
    presentWindowCount_ = std::min(presentWindowCount_ + 1, kTelemetryCapacity);
}'''
new_record = '''void LibretroHost::recordFrame(float frameMs, float targetMs) {
    targetFrameMs_.store(targetMs, std::memory_order_release);
    // Telemetry is observational. If the UI is copying the diagnostic window,
    // skipping one sample is always preferable to blocking an emulation frame.
    std::unique_lock<std::mutex> lock(telemetryMutex_, std::try_to_lock);
    if (!lock.owns_lock()) return;
    frameWindow_[frameWindowWrite_] = frameMs;
    frameWindowWrite_ = (frameWindowWrite_ + 1) % kTelemetryCapacity;
    frameWindowCount_ = std::min(frameWindowCount_ + 1, kTelemetryCapacity);
}

void LibretroHost::recordPresent(float presentMs) {
    lastPresentMs_.store(presentMs, std::memory_order_release);
    // Same rule for presentation telemetry: diagnostics must never become a
    // source of periodic micro-stutter.
    std::unique_lock<std::mutex> lock(telemetryMutex_, std::try_to_lock);
    if (!lock.owns_lock()) return;
    presentWindow_[presentWindowWrite_] = presentMs;
    presentWindowWrite_ = (presentWindowWrite_ + 1) % kTelemetryCapacity;
    presentWindowCount_ = std::min(presentWindowCount_ + 1, kTelemetryCapacity);
}'''
if host.count(old_record) != 1:
    raise SystemExit("telemetry record baseline not found")
host = host.replace(old_record, new_record, 1)

# Add CruiseGuard state next to PrecisionGovernor v2 state.
old_vars = '''    auto lastGovernorChange = warmStartBegan - std::chrono::seconds(5);
    auto governorHeadroomUntil = std::chrono::steady_clock::time_point{};
    int governorMode = 0;  // 0 stable, 1 CPU, 2 GPU/present, 3 mixed.
    bool wasPaused = false;'''
new_vars = '''    auto lastGovernorChange = warmStartBegan - std::chrono::seconds(5);
    auto governorHeadroomUntil = std::chrono::steady_clock::time_point{};
    auto governorBoostBegan = warmStartBegan;
    int governorMode = 0;  // 0 stable, 1 CPU, 2 GPU/present, 3 mixed.
    bool cruiseRelaxed = false;
    bool wasPaused = false;'''
if host.count(old_vars) != 1:
    raise SystemExit("governor local state baseline not found")
host = host.replace(old_vars, new_vars, 1)

# Pre-input microburst immediately before core.run.
old_menu = '''        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            // Menus are commonly framebuffer-heavy. Hint GPU only; audio has its
            // own xrun/fill controller and must not expand just because Start was pressed.
            impl_->perfHint.notifySpike(false, true, "omnicore-n64-menu-present-spike");
            governorHeadroomUntil = std::max(
                governorHeadroomUntil,
                std::chrono::steady_clock::now() + std::chrono::milliseconds(800));
        }

        impl_->presentationTargetNs'''
new_menu = '''        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            // Menus are commonly framebuffer-heavy. Hint GPU only; audio has its
            // own xrun/fill controller and must not expand just because Start was pressed.
            impl_->perfHint.notifySpike(false, true, "omnicore-n64-menu-present-spike");
            governorHeadroomUntil = std::max(
                governorHeadroomUntil,
                std::chrono::steady_clock::now() + std::chrono::milliseconds(800));
        }
        if (interactionTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {
            // Predictive, bounded action burst. It is intentionally a workload
            // hint only: no clock mutation, no resolution change and no audio
            // buffer growth. This helps the frames immediately following
            // attacks/collisions where CPU logic and a new RDP effect often meet.
            impl_->perfHint.notifySpike(true, true, "omnicore-n64-action-microburst");
            governorHeadroomUntil = std::max(
                governorHeadroomUntil,
                std::chrono::steady_clock::now() + std::chrono::milliseconds(420));
        }

        impl_->presentationTargetNs'''
if host.count(old_menu) != 1:
    raise SystemExit("menu transition baseline not found")
host = host.replace(old_menu, new_menu, 1)

# Replace isolated catastrophic-only reaction with two-level transient handling.
old_spike = '''        // Isolated catastrophic spikes receive only a bounded transient hint.
        // They never change the governor mode by themselves.
        if (frameMs > targetMs * 1.85f) {
            const bool spikeGpu = presentMs >= std::max(4.0f, targetMs * 0.25f);
            impl_->perfHint.notifySpike(
                !spikeGpu,
                spikeGpu,
                spikeGpu ? "omnicore-n64-v2-single-gpu-spike" : "omnicore-n64-v2-single-cpu-spike");
        }
'''
new_spike = '''        // MicroBurstShield catches short sudden hitches that are visible but
        // too small to justify a governor mode change. This is especially useful
        // for collision/hit effects and first-use scene work. The hint is bounded
        // by PerformanceHintSession's cooldown and never changes fidelity.
        const float transientBaseline = std::max(targetMs, slowFrameEwma);
        const bool suddenMicroSpike = frameMs > targetMs * 1.30f &&
            frameMs > transientBaseline * 1.16f && pressureDebt < 0.55f;
        const bool catastrophicSpike = frameMs > targetMs * 1.85f;
        if (suddenMicroSpike || catastrophicSpike) {
            const bool spikeGpu = presentMs >= std::max(3.6f, targetMs * 0.22f);
            impl_->perfHint.notifySpike(
                !spikeGpu,
                spikeGpu,
                spikeGpu ? "omnicore-n64-v21-transient-gpu" : "omnicore-n64-v21-transient-cpu");
            governorHeadroomUntil = std::max(
                governorHeadroomUntil, controlNow + std::chrono::milliseconds(520));
        }
'''
if host.count(old_spike) != 1:
    raise SystemExit("single spike baseline not found")
host = host.replace(old_spike, new_spike, 1)

# Track boost age when entering/switching a governor mode.
old_apply_tail = '''            governorMode = nextMode;
            precisionGovernorMode_.store(nextMode, std::memory_order_release);
            governorHeadroomUntil = controlNow + std::chrono::milliseconds(1800);
            lastGovernorChange = controlNow;
            stableStreak = 0;
        }

        const bool recoveryConfidence'''
new_apply_tail = '''            governorMode = nextMode;
            precisionGovernorMode_.store(nextMode, std::memory_order_release);
            governorHeadroomUntil = controlNow + std::chrono::milliseconds(1800);
            governorBoostBegan = controlNow;
            cruiseRelaxed = false;
            lastGovernorChange = controlNow;
            stableStreak = 0;
        }

        // CruiseGuard prevents a modest, already-controlled workload from
        // holding the strictest ADPF target forever. After several seconds of
        // contained pressure it relaxes slightly to reduce long-session thermal
        // oscillation. Any renewed pressure immediately restores full headroom.
        if (governorMode != 0 && !cruiseRelaxed &&
            controlNow - governorBoostBegan >= std::chrono::seconds(7) &&
            controlNow >= governorHeadroomUntil &&
            slowRatio <= 1.09f && pressureDebt <= 0.56f &&
            jitterEwma <= targetMs * 0.20f) {
            if (adpfReady) {
                impl_->perfHint.setTargetScale(governorMode == 3 ? 0.95 : 0.97);
            }
            cruiseRelaxed = true;
        }
        if (governorMode != 0 && cruiseRelaxed &&
            (fastRatio > 1.14f || pressureDebt >= 0.62f || jitterEwma > targetMs * 0.27f)) {
            const bool cpuPressure = governorMode == 1 || governorMode == 3;
            const bool gpuPressure = governorMode == 2 || governorMode == 3;
            impl_->perfHint.notifySpike(cpuPressure, gpuPressure, "omnicore-n64-v21-cruise-reengage");
            if (adpfReady) {
                impl_->perfHint.setTargetScale(
                    governorMode == 1 ? 0.92 : (governorMode == 2 ? 0.94 : 0.90));
            }
            cruiseRelaxed = false;
            governorBoostBegan = controlNow;
            governorHeadroomUntil = controlNow + std::chrono::milliseconds(900);
        }

        const bool recoveryConfidence'''
if host.count(old_apply_tail) != 1:
    raise SystemExit("governor apply tail baseline not found")
host = host.replace(old_apply_tail, new_apply_tail, 1)

old_recovery_tail = '''            stableStreak = 0;
            candidateStreak = 0;
            candidateMode = 0;
            lastGovernorChange = controlNow;
        }
'''
new_recovery_tail = '''            stableStreak = 0;
            candidateStreak = 0;
            candidateMode = 0;
            cruiseRelaxed = false;
            governorBoostBegan = controlNow;
            lastGovernorChange = controlNow;
        }
'''
if host.count(old_recovery_tail) != 1:
    raise SystemExit("governor recovery tail baseline not found")
host = host.replace(old_recovery_tail, new_recovery_tail, 1)

host_path.write_text(host, encoding="utf-8")

# Relax stable UI/JNI polling slightly. SmartPerf still adapts every ~2.4 s,
# while the native telemetry path is now non-blocking for the emulation thread.
replace_exact(
    "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt",
    '                else -> 900L',
    '                else -> 1200L',
)

print("Alpha 19 MicroBurstShield + CruiseGuard + NonBlockingTelemetry + ComfortAnalog migration applied")
