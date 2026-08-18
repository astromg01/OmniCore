from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# Version + runtime identity.
replace_once(
    "app/build.gradle.kts",
    'versionCode = 24\n        versionName = "0.10.8"',
    'versionCode = 25\n        versionName = "0.10.9"'
)
replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    'OmniCore N64 Runtime 0.10.8 • Mupen64Plus-Next • GLES3 + AAudio host v7',
    'OmniCore N64 Runtime 0.10.9 • Mupen64Plus-Next • GLES3 + AAudio host v8'
)

# Real intermediate render tier supported by the pinned Mupen64Plus-Next core.
settings = "app/src/main/java/com/omnicore/emulator/settings/N64Settings.kt"
replace_once(
    settings,
    '        NATIVE("native", "Nativa", 1),\n        X2("2x", "2×", 2)',
    '        NATIVE("native", "Nativa", 10),\n'
    '        X15("1.5x", "1,5×", 15),\n'
    '        X2("2x", "2×", 20)'
)
replace_once(
    settings,
    '                internalResolution = InternalResolution.NATIVE,\n                aspectRatio = AspectRatio.ORIGINAL_4_3,\n                framebufferEmulation = true,\n                expansionPak = ExpansionPak.AUTO,\n                threadedRenderer = canThread && device.tier != N64PerformanceProfile.Tier.LOW',
    '                internalResolution = if (device.tier == N64PerformanceProfile.Tier.LOW) {\n'
    '                    InternalResolution.NATIVE\n'
    '                } else {\n'
    '                    InternalResolution.X15\n'
    '                },\n'
    '                aspectRatio = AspectRatio.ORIGINAL_4_3,\n'
    '                framebufferEmulation = true,\n'
    '                expansionPak = ExpansionPak.AUTO,\n'
    '                threadedRenderer = canThread && device.tier != N64PerformanceProfile.Tier.LOW'
)
replace_once(
    settings,
    '                internalResolution = if (device.tier == N64PerformanceProfile.Tier.HIGH) InternalResolution.X2 else InternalResolution.NATIVE,',
    '                internalResolution = when (device.tier) {\n'
    '                    N64PerformanceProfile.Tier.HIGH -> InternalResolution.X2\n'
    '                    N64PerformanceProfile.Tier.BALANCED -> InternalResolution.X15\n'
    '                    N64PerformanceProfile.Tier.LOW -> InternalResolution.NATIVE\n'
    '                },'
)
replace_once(
    settings,
    '                // AUTO starts cheap. Quality promotion should happen only after\n'
    '                // telemetry proves that there is sustained margin.\n'
    '                internalResolution = InternalResolution.NATIVE,',
    '                // AUTO no longer starts at the lowest image tier on capable\n'
    '                // phones. 1.5x is the visual/performance middle step; 2x is\n'
    '                // still reserved for explicit Quality/high-margin sessions.\n'
    '                internalResolution = if (device.tier == N64PerformanceProfile.Tier.LOW) {\n'
    '                    InternalResolution.NATIVE\n'
    '                } else {\n'
    '                    InternalResolution.X15\n'
    '                },'
)
replace_once(
    settings,
    '        val safeResolution = if (\n            device.tier == N64PerformanceProfile.Tier.LOW && internalResolution == InternalResolution.X2\n        ) InternalResolution.NATIVE else internalResolution',
    '        val safeResolution = if (\n'
    '            device.tier == N64PerformanceProfile.Tier.LOW && internalResolution != InternalResolution.NATIVE\n'
    '        ) InternalResolution.NATIVE else internalResolution'
)

# SmartPerf: image quality is reduced only for measured GPU/memory/thermal pressure,
# not merely because the controller is in BALANCED while warming up.
smart = "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt"
replace_once(
    smart,
    '        val allowResolutionPromotion: Boolean,\n        val reason: String',
    '        val allowResolutionPromotion: Boolean,\n'
    '        val leanGraphics: Boolean = false,\n'
    '        val reason: String'
)
replace_once(
    smart,
    '        private var lastAudioStressAt = 0L\n\n        fun initial(): Decision = current',
    '        private var lastAudioStressAt = 0L\n'
    '        private val startupGraceUntil = SystemClock.elapsedRealtime() + 10_000L\n\n'
    '        fun initial(): Decision = current.copy(\n'
    '            audioBufferBursts = max(current.audioBufferBursts, 6),\n'
    '            reason = "SmartPerf N64 aquecendo shaders e protegendo áudio inicial"\n'
    '        )'
)
replace_once(
    smart,
    '            fun protectAudio(decision: Decision): Decision = if (now - lastAudioStressAt < 12_000L) {',
    '            fun protectAudio(decision: Decision): Decision = if (\n'
    '                now < startupGraceUntil || now - lastAudioStressAt < 12_000L\n'
    '            ) {'
)
replace_once(
    smart,
    '                    requested.copy(\n                        internalResolution = N64Settings.InternalResolution.NATIVE,\n                        framebufferEmulation = compatibleFramebuffer(telemetry.gpuBound || signals.memoryPressure)\n                    ),',
    '                    requested.copy(\n'
    '                        internalResolution = when {\n'
    '                            severeThermal -> N64Settings.InternalResolution.NATIVE\n'
    '                            telemetry.gpuBound || signals.memoryPressure ->\n'
    '                                if (requested.internalResolution == N64Settings.InternalResolution.X2) {\n'
    '                                    N64Settings.InternalResolution.X15\n'
    '                                } else {\n'
    '                                    requested.internalResolution\n'
    '                                }\n'
    '                            else -> requested.internalResolution\n'
    '                        },\n'
    '                        framebufferEmulation = compatibleFramebuffer(telemetry.gpuBound || signals.memoryPressure)\n'
    '                    ),'
)
replace_once(
    smart,
    '                allowResolutionPromotion = false,\n                reason = when {\n                    severeThermal ->',
    '                allowResolutionPromotion = false,\n'
    '                leanGraphics = telemetry.gpuBound || signals.memoryPressure || severeThermal,\n'
    '                reason = when {\n'
    '                    severeThermal ->'
)
replace_once(
    smart,
    '                    requested.copy(\n                        internalResolution = N64Settings.InternalResolution.NATIVE,\n                        framebufferEmulation = compatibleFramebuffer(\n                            telemetry.gpuBound || signals.memoryPressure\n                        )\n                    ),',
    '                    requested.copy(\n'
    '                        internalResolution = if (telemetry.gpuBound || signals.memoryPressure) {\n'
    '                            if (requested.internalResolution == N64Settings.InternalResolution.X2) {\n'
    '                                N64Settings.InternalResolution.X15\n'
    '                            } else {\n'
    '                                requested.internalResolution\n'
    '                            }\n'
    '                        } else {\n'
    '                            requested.internalResolution\n'
    '                        },\n'
    '                        framebufferEmulation = compatibleFramebuffer(\n'
    '                            telemetry.gpuBound || signals.memoryPressure\n'
    '                        )\n'
    '                    ),'
)
replace_once(
    smart,
    '                allowResolutionPromotion = false,\n                reason = when {\n                    warmThermal ->',
    '                allowResolutionPromotion = false,\n'
    '                leanGraphics = telemetry.gpuBound || signals.memoryPressure || warmThermal,\n'
    '                reason = when {\n'
    '                    warmThermal ->'
)

# Kotlin -> native: use the explicit quality-pressure signal, not BALANCED level.
bridge = "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt"
replace_once(
    bridge,
    '                leanGraphics = decision.level != N64SmartPerf.Level.TURBO,',
    '                leanGraphics = decision.leanGraphics,'
)

# Native rendering ladder, buffered framebuffer copies, and Start/menu transition audio reserve.
host_h = "app/src/main/cpp/n64/n64_libretro_host.h"
replace_once(host_h, '    int internalResolution = 1;', '    int internalResolution = 10;')
replace_once(
    host_h,
    '    std::atomic<int> audioTargetBursts_{4};',
    '    std::atomic<int> audioTargetBursts_{4};\n    std::atomic<bool> menuTransitionBoost_{false};'
)

host = "app/src/main/cpp/n64/n64_libretro_host.cpp"
replace_once(
    host,
    '    options_["mupen64plus-43screensize"] = config_.internalResolution >= 2 ? "1280x960" : "640x480";\n'
    '    options_["mupen64plus-169screensize"] = config_.internalResolution >= 2 ? "1280x720" : "640x360";',
    '    const char* screen43 = config_.internalResolution >= 20 ? "1280x960" :\n'
    '        (config_.internalResolution >= 15 ? "960x720" : "640x480");\n'
    '    const char* screen169 = config_.internalResolution >= 20 ? "1280x720" :\n'
    '        (config_.internalResolution >= 15 ? "960x540" : "640x360");\n'
    '    options_["mupen64plus-43screensize"] = screen43;\n'
    '    options_["mupen64plus-169screensize"] = screen169;'
)
replace_once(
    host,
    '    options_["mupen64plus-EnableCopyColorToRDRAM"] = framebuffer ? "Async" : "Off";',
    '    // Triple-buffered color copies reduce the first heavy framebuffer transition\n'
    '    // (notably Zelda pause screens) without disabling compatibility. Under\n'
    '    // measured GPU pressure we fall back to the cheaper double-buffered path.\n'
    '    options_["mupen64plus-EnableCopyColorToRDRAM"] = framebuffer\n'
    '        ? (leanGraphics ? "Async" : "TripleBuffer")\n'
    '        : "Off";'
)
replace_once(
    host,
    '    const GLenum presentFilter = config_.internalResolution >= 2 ? GL_LINEAR : GL_NEAREST;',
    '    const GLenum presentFilter = config_.internalResolution >= 15 ? GL_LINEAR : GL_NEAREST;'
)
replace_once(
    host,
    '    const int renderWidth = config_.internalResolution >= 2 ? 1280 : 640;\n'
    '    const int renderHeight = wide\n'
    '        ? (config_.internalResolution >= 2 ? 720 : 360)\n'
    '        : (config_.internalResolution >= 2 ? 960 : 480);',
    '    const int renderWidth = config_.internalResolution >= 20 ? 1280 :\n'
    '        (config_.internalResolution >= 15 ? 960 : 640);\n'
    '    const int renderHeight = wide\n'
    '        ? (config_.internalResolution >= 20 ? 720 : (config_.internalResolution >= 15 ? 540 : 360))\n'
    '        : (config_.internalResolution >= 20 ? 960 : (config_.internalResolution >= 15 ? 720 : 480));'
)
replace_once(
    host,
    'void LibretroHost::setButton(unsigned retroPadId, bool pressed) {\n'
    '    if (retroPadId > RETRO_DEVICE_ID_JOYPAD_R3) return;\n'
    '    const auto bit = static_cast<std::uint16_t>(1u << retroPadId);\n'
    '    if (pressed) buttonMask_.fetch_or(bit, std::memory_order_acq_rel);\n'
    '    else buttonMask_.fetch_and(static_cast<std::uint16_t>(~bit), std::memory_order_acq_rel);\n'
    '}',
    'void LibretroHost::setButton(unsigned retroPadId, bool pressed) {\n'
    '    if (retroPadId > RETRO_DEVICE_ID_JOYPAD_R3) return;\n'
    '    if (pressed && retroPadId == RETRO_DEVICE_ID_JOYPAD_START) {\n'
    '        // Pause/menu screens often trigger the first expensive framebuffer\n'
    '        // copy. Ask the emulation thread for a larger audio cushion before\n'
    '        // the core consumes the Start press.\n'
    '        menuTransitionBoost_.store(true, std::memory_order_release);\n'
    '    }\n'
    '    const auto bit = static_cast<std::uint16_t>(1u << retroPadId);\n'
    '    if (pressed) buttonMask_.fetch_or(bit, std::memory_order_acq_rel);\n'
    '    else buttonMask_.fetch_and(static_cast<std::uint16_t>(~bit), std::memory_order_acq_rel);\n'
    '}'
)
replace_once(
    host,
    '        impl_->presentationTargetNs = std::chrono::duration_cast<std::chrono::nanoseconds>(\n'
    '            nextFrame.time_since_epoch()).count();\n'
    '        const auto begin = std::chrono::steady_clock::now();',
    '        if (menuTransitionBoost_.exchange(false, std::memory_order_acq_rel)) {\n'
    '            impl_->adaptAudio(8);\n'
    '        }\n'
    '        impl_->presentationTargetNs = std::chrono::duration_cast<std::chrono::nanoseconds>(\n'
    '            nextFrame.time_since_epoch()).count();\n'
    '        const auto begin = std::chrono::steady_clock::now();'
)

print("Alpha 10 migration applied successfully")
