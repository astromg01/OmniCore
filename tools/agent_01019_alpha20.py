from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Alpha 20 migration marker missing in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Version.
replace_once(
    "app/build.gradle.kts",
    'versionCode = 34\n        versionName = "0.10.18"',
    'versionCode = 35\n        versionName = "0.10.19"',
)

# Game Intelligence: preserve Kirby digital profile and add a racing-only analog profile.
replace_once(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64GameIntelligence.kt",
    '''    data class InputPolicy(\n        val bridgeDpadInAuto: Boolean,\n        val internalTitle: String,\n        val reason: String?\n    )''',
    '''    data class InputPolicy(\n        val bridgeDpadInAuto: Boolean,\n        val internalTitle: String,\n        val analogProfile: String,\n        val reason: String?\n    )''',
)
replace_once(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64GameIntelligence.kt",
    '''        val identity = "$title $fileIdentity"\n        val matched = dpadFirstMarkers.firstOrNull { identity.contains(it) }\n        return InputPolicy(\n            bridgeDpadInAuto = matched != null,\n            internalTitle = title,\n            reason = matched?.let { "digital-profile:$it" }\n        )''',
    '''        val identity = "$title $fileIdentity"\n        val matched = dpadFirstMarkers.firstOrNull { identity.contains(it) }\n        val racingProfile = identity.contains("MARIOKART") || identity.contains("MARIO KART")\n        val reasons = listOfNotNull(\n            matched?.let { "digital-profile:$it" },\n            if (racingProfile) "analog-profile:RACING" else null\n        )\n        return InputPolicy(\n            bridgeDpadInAuto = matched != null,\n            internalTitle = title,\n            analogProfile = if (racingProfile) "racing" else "balanced",\n            reason = reasons.takeIf { it.isNotEmpty() }?.joinToString("+")\n        )''',
)

# Kotlin JNI bridge: pass the per-game analog profile to native without changing user settings.
replace_once(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''                    "smartInput=${inputPolicy.reason ?: "generic"},title=${inputPolicy.internalTitle.take(28)}\\n"''',
    '''                    "smartInput=${inputPolicy.reason ?: "generic"},analogProfile=${inputPolicy.analogProfile},title=${inputPolicy.internalTitle.take(28)}\\n"''',
)
replace_once(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''                smartAnalogMode = input.smartAnalogMode.storage,\n                smartAnalogAutoDpad = !input.showDpad || inputPolicy.bridgeDpadInAuto,\n                precisionAnalog = input.precisionAnalog,''',
    '''                smartAnalogMode = input.smartAnalogMode.storage,\n                smartAnalogAutoDpad = !input.showDpad || inputPolicy.bridgeDpadInAuto,\n                analogProfile = inputPolicy.analogProfile,\n                precisionAnalog = input.precisionAnalog,''',
)
replace_once(
    "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt",
    '''        smartAnalogMode: String,\n        smartAnalogAutoDpad: Boolean,\n        precisionAnalog: Boolean,''',
    '''        smartAnalogMode: String,\n        smartAnalogAutoDpad: Boolean,\n        analogProfile: String,\n        precisionAnalog: Boolean,''',
)

# Native config/JNI.
replace_once(
    "app/src/main/cpp/n64/n64_libretro_host.h",
    '''    std::string smartAnalogMode = "auto";\n    bool smartAnalogAutoDpad = false;\n    bool precisionAnalog = true;''',
    '''    std::string smartAnalogMode = "auto";\n    bool smartAnalogAutoDpad = false;\n    std::string analogProfile = "balanced";\n    bool precisionAnalog = true;''',
)
replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''OmniCore N64 Runtime 0.10.18 • Mupen64Plus-Next • GLES3 + AAudio host v15 • PrecisionGovernor v2.1 + MicroBurstShield + CruiseGuard + ComfortAnalog + PassiveWarmCache + DirectPresenter''',
    '''OmniCore N64 Runtime 0.10.19 • Mupen64Plus-Next • GLES3 + AAudio host v16 • TransitionAudioShield + PrecisionGovernor v2.1 + RacingComfort + MicroBurstShield + CruiseGuard + PassiveWarmCache + DirectPresenter''',
)
replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''    jstring smartAnalogMode,\n    jboolean smartAnalogAutoDpad,\n    jboolean precisionAnalog,''',
    '''    jstring smartAnalogMode,\n    jboolean smartAnalogAutoDpad,\n    jstring analogProfile,\n    jboolean precisionAnalog,''',
)
replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    '''    config.smartAnalogMode = fromJString(env, smartAnalogMode);\n    config.smartAnalogAutoDpad = smartAnalogAutoDpad == JNI_TRUE;\n    config.precisionAnalog = precisionAnalog == JNI_TRUE;''',
    '''    config.smartAnalogMode = fromJString(env, smartAnalogMode);\n    config.smartAnalogAutoDpad = smartAnalogAutoDpad == JNI_TRUE;\n    config.analogProfile = fromJString(env, analogProfile);\n    if (config.analogProfile.empty()) config.analogProfile = "balanced";\n    config.precisionAnalog = precisionAnalog == JNI_TRUE;''',
)

host_path = "app/src/main/cpp/n64/n64_libretro_host.cpp"

# ComfortAnalog v2: generic profile remains the Alpha 19 curve; Mario Kart gets a softer racing center.
replace_once(
    host_path,
    '''AnalogVector shapeAnalog(float x, float y, int deadzonePercent, int sensitivityPercent, bool precision) {''',
    '''AnalogVector shapeAnalog(float x, float y, int deadzonePercent, int sensitivityPercent, bool precision, const std::string& profile) {''',
)
replace_once(
    host_path,
    '''    // ComfortAnalog keeps a small high-precision zone around center, then\n    // returns to a nearly linear response instead of suppressing the entire\n    // stick range. This makes slow walking/aiming controllable without making\n    // normal movement feel heavy. The last ~1.5% of the physical/touch radius\n    // is treated as full travel so users do not have to pin the thumb to the rim.\n    constexpr float kFineZone = 0.28f;\n    if (normalized < kFineZone) {\n        const float local = normalized / kFineZone;\n        normalized = std::pow(local, 1.08f) * kFineZone;\n    }\n    normalized = std::min(1.0f, normalized / 0.985f);\n    normalized *= std::clamp(static_cast<float>(sensitivityPercent) / 100.0f, 0.70f, 1.30f);''',
    '''    const float userSensitivity = std::clamp(\n        static_cast<float>(sensitivityPercent) / 100.0f, 0.70f, 1.30f);\n    if (profile == "racing") {\n        // RacingComfort: Mario Kart benefits from a wider fine-steering zone and\n        // a near-neutral effective default sensitivity. Full steering remains\n        // reachable at the rim, so this improves control without capping range.\n        constexpr float kRacingFineZone = 0.42f;\n        if (normalized < kRacingFineZone) {\n            const float local = normalized / kRacingFineZone;\n            normalized = std::pow(local, 1.16f) * kRacingFineZone;\n        }\n        normalized = std::min(1.0f, normalized / 0.995f);\n        normalized *= userSensitivity * 0.96f;\n    } else {\n        // Generic ComfortAnalog keeps the Alpha 19 behavior that tested well\n        // for Zelda and normal analog titles.\n        constexpr float kFineZone = 0.28f;\n        if (normalized < kFineZone) {\n            const float local = normalized / kFineZone;\n            normalized = std::pow(local, 1.08f) * kFineZone;\n        }\n        normalized = std::min(1.0f, normalized / 0.985f);\n        normalized *= userSensitivity;\n    }''',
)
replace_once(
    host_path,
    '''    const AnalogVector shaped = shapeAnalog(\n        x, y, config_.analogDeadzonePercent, config_.analogSensitivityPercent, config_.precisionAnalog);''',
    '''    const AnalogVector shaped = shapeAnalog(\n        x, y, config_.analogDeadzonePercent, config_.analogSensitivityPercent,\n        config_.precisionAnalog, config_.analogProfile);''',
)

# TransitionAudioShield state and helpers live in the N64 Impl only.
replace_once(
    host_path,
    '''    std::uint64_t lastRingUnderruns = 0;\n    std::int16_t lastAudioLeft = 0;''',
    '''    std::uint64_t lastRingUnderruns = 0;\n    std::chrono::steady_clock::time_point transitionAudioShieldUntil{};\n    std::int16_t lastAudioLeft = 0;''',
)
replace_once(
    host_path,
    '''    void updateAudioTelemetry() {\n        if (!owner || outputSampleRate <= 0) return;''',
    '''    void armTransitionAudioShield(std::chrono::milliseconds duration) {\n        const auto requestedUntil = std::chrono::steady_clock::now() + duration;\n        if (requestedUntil > transitionAudioShieldUntil) transitionAudioShieldUntil = requestedUntil;\n        stableAudioChecks = 0;\n    }\n\n    bool transitionAudioShieldActive() const {\n        return std::chrono::steady_clock::now() < transitionAudioShieldUntil;\n    }\n\n    void updateAudioTelemetry() {\n        if (!owner || outputSampleRate <= 0) return;''',
)

# Slightly larger startup reserve, still bounded and far smaller than permanent high latency.
replace_once(
    host_path,
    '''            audioPrimeFrames = std::min<int>(\n                static_cast<int>(audioRing.capacitySamples() / 4u),\n                std::max(framesPerBurst * 5, outputSampleRate / 24));''',
    '''            audioPrimeFrames = std::min<int>(\n                static_cast<int>(audioRing.capacitySamples() / 4u),\n                std::max(framesPerBurst * 6, outputSampleRate / 20));\n            armTransitionAudioShield(std::chrono::milliseconds(6000));''',
)
replace_once(
    host_path,
    '''        resampleAccumulator = 0.0;\n        lastRingUnderruns = 0;\n        stableAudioChecks = 0;\n        updateAudioTelemetry();''',
    '''        resampleAccumulator = 0.0;\n        lastRingUnderruns = 0;\n        stableAudioChecks = 0;\n        armTransitionAudioShield(std::chrono::milliseconds(2200));\n        updateAudioTelemetry();''',
)

# Fast, temporary refill when a transition drains the ring; normal gameplay keeps the old low correction.
replace_once(
    host_path,
    '''        const float targetFillMs = std::max(42.0f, bufferMs * 1.65f);\n        double scale = 1.0;\n        if (fillMs < targetFillMs * 0.55f) scale = 1.0075;\n        else if (fillMs < targetFillMs * 0.80f) scale = 1.0035;\n        else if (fillMs > targetFillMs * 1.75f) scale = 0.9945;\n        else if (fillMs > targetFillMs * 1.40f) scale = 0.9975;''',
    '''        const float steadyTargetFillMs = std::max(42.0f, bufferMs * 1.65f);\n        const bool transitionShield = transitionAudioShieldActive();\n        const float targetFillMs = transitionShield\n            ? std::max(steadyTargetFillMs, 76.0f)\n            : steadyTargetFillMs;\n        double scale = 1.0;\n        if (transitionShield && fillMs < targetFillMs * 0.42f) scale = 1.0180;\n        else if (transitionShield && fillMs < targetFillMs * 0.68f) scale = 1.0120;\n        else if (transitionShield && fillMs < targetFillMs * 0.90f) scale = 1.0065;\n        else if (fillMs < targetFillMs * 0.55f) scale = 1.0075;\n        else if (fillMs < targetFillMs * 0.80f) scale = 1.0035;\n        else if (fillMs > targetFillMs * 1.75f) scale = 0.9945;\n        else if (fillMs > targetFillMs * 1.40f) scale = 0.9975;''',
)
replace_once(
    host_path,
    '''        requestedBursts = std::clamp(requestedBursts, 2, 8);\n        const int xruns = std::max(0, AAudioStream_getXRunCount(audioStream));''',
    '''        requestedBursts = std::clamp(requestedBursts, 2, 8);\n        if (transitionAudioShieldActive()) requestedBursts = std::max(requestedBursts, 7);\n        const int xruns = std::max(0, AAudioStream_getXRunCount(audioStream));''',
)

# Runtime: detect underrun episodes immediately and arm audio protection around known transitions.
replace_once(
    host_path,
    '''    std::uint32_t adaptationCounter = 0;\n    int stableStreak = 0;''',
    '''    std::uint32_t adaptationCounter = 0;\n    std::uint64_t observedAudioUnderruns = 0;\n    int stableStreak = 0;''',
)
replace_once(
    host_path,
    '''        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {\n            // Menus are commonly framebuffer-heavy. Hint GPU only; audio has its\n            // own xrun/fill controller and must not expand just because Start was pressed.\n            impl_->perfHint.notifySpike(false, true, "omnicore-n64-menu-present-spike");''',
    '''        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {\n            // Menus are commonly framebuffer-heavy. Give the renderer bounded\n            // headroom and temporarily protect the existing audio reserve.\n            impl_->armTransitionAudioShield(std::chrono::milliseconds(2400));\n            impl_->adaptAudio(std::max(audioTargetBursts_.load(std::memory_order_acquire), 7));\n            impl_->perfHint.notifySpike(false, true, "omnicore-n64-menu-present-spike");''',
)
replace_once(
    host_path,
    '''            impl_->perfHint.notifySpike(true, true, "omnicore-n64-action-microburst");\n            governorHeadroomUntil = std::max(''',
    '''            impl_->armTransitionAudioShield(std::chrono::milliseconds(1200));\n            impl_->perfHint.notifySpike(true, true, "omnicore-n64-action-microburst");\n            governorHeadroomUntil = std::max(''',
)
replace_once(
    host_path,
    '''        impl_->perfHint.report(workNs);\n        recordFrame(frameMs, targetMs);\n\n        const auto controlNow = std::chrono::steady_clock::now();''',
    '''        impl_->perfHint.report(workNs);\n        recordFrame(frameMs, targetMs);\n\n        // Audio underruns are handled as episodes immediately after the next\n        // emulation slice, not delayed until the 60-frame adaptation cadence.\n        const std::uint64_t ringUnderrunsNow = impl_->audioRing.underruns();\n        if (ringUnderrunsNow > observedAudioUnderruns) {\n            observedAudioUnderruns = ringUnderrunsNow;\n            impl_->armTransitionAudioShield(std::chrono::milliseconds(2600));\n            impl_->adaptAudio(std::max(audioTargetBursts_.load(std::memory_order_acquire), 7));\n        }\n\n        const auto controlNow = std::chrono::steady_clock::now();''',
)
replace_once(
    host_path,
    '''        if (suddenMicroSpike || catastrophicSpike) {\n            const bool spikeGpu = presentMs >= std::max(3.6f, targetMs * 0.22f);''',
    '''        if (suddenMicroSpike || catastrophicSpike) {\n            impl_->armTransitionAudioShield(std::chrono::milliseconds(1800));\n            const bool spikeGpu = presentMs >= std::max(3.6f, targetMs * 0.22f);''',
)

# Startup banner.
replace_once(
    host_path,
    '''setMessage("N64 BOOT 1/6 • Alpha 19 PrecisionGovernor v2.1 + MicroBurstShield…");''',
    '''setMessage("N64 BOOT 1/6 • Alpha 20 TransitionAudioShield + RacingComfort…");''',
)

print("Alpha 20 migration applied")
