from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:110]!r}")
    write(path, text.replace(old, new, 1))


# Version / runtime identity.
replace_once(
    "app/build.gradle.kts",
    'versionCode = 23\n        versionName = "0.10.7"',
    'versionCode = 24\n        versionName = "0.10.8"'
)
replace_once(
    "app/src/main/cpp/n64/n64_native_bridge.cpp",
    'OmniCore N64 Runtime 0.10.7 • Mupen64Plus-Next • GLES3 + AAudio host v6',
    'OmniCore N64 Runtime 0.10.8 • Mupen64Plus-Next • GLES3 + AAudio host v7'
)

# Widescreen is an independent display preference, not a performance preset.
settings = "app/src/main/java/com/omnicore/emulator/settings/N64Settings.kt"
replace_once(
    settings,
    '''    fun savePreset(context: Context, preset: Preset) {
        prefs(context).edit().putString(KEY_PRESET, preset.storage).apply()
    }

    fun resolve(context: Context): Config {
        val preset = readPreset(context)
        if (preset != Preset.CUSTOM) return presetConfig(preset, N64PerformanceProfile.detect(context))

        val storage = prefs(context)
        val device = N64PerformanceProfile.detect(context)
''',
    '''    fun savePreset(context: Context, preset: Preset) {
        prefs(context).edit().putString(KEY_PRESET, preset.storage).apply()
    }

    fun readAspectRatio(context: Context): AspectRatio {
        val raw = prefs(context).getString(KEY_ASPECT, AspectRatio.ORIGINAL_4_3.storage)
        return AspectRatio.entries.firstOrNull { it.storage == raw } ?: AspectRatio.ORIGINAL_4_3
    }

    fun saveAspectRatio(context: Context, aspectRatio: AspectRatio) {
        prefs(context).edit().putString(KEY_ASPECT, aspectRatio.storage).apply()
    }

    fun resolve(context: Context): Config {
        val preset = readPreset(context)
        val device = N64PerformanceProfile.detect(context)
        if (preset != Preset.CUSTOM) {
            return presetConfig(preset, device)
                .copy(aspectRatio = readAspectRatio(context))
                .sanitized(device)
        }

        val storage = prefs(context)
'''
)
replace_once(
    settings,
    'framebufferEmulation = device.tier == N64PerformanceProfile.Tier.HIGH,',
    'framebufferEmulation = true,'
)
replace_once(
    settings,
    '''        val safeExpansionPak = ExpansionPak.AUTO
        return copy(
            rspMode = safeRsp,
            internalResolution = safeResolution,
            expansionPak = safeExpansionPak,
            threadedRenderer = safeThreadedRenderer
        )
''',
    '''        val safeExpansionPak = ExpansionPak.AUTO
        // GLideN64 framebuffer emulation is required by many effects and by
        // widescreen/aspect handling. Only an explicit 4:3 performance config
        // may request it off; wide modes always protect compatibility.
        val safeFramebuffer = framebufferEmulation || aspectRatio.wide
        return copy(
            rspMode = safeRsp,
            internalResolution = safeResolution,
            framebufferEmulation = safeFramebuffer,
            expansionPak = safeExpansionPak,
            threadedRenderer = safeThreadedRenderer
        )
'''
)

# Settings UI: selecting widescreen no longer silently switches to CUSTOM.
dialog = "app/src/main/java/com/omnicore/emulator/ui/n64/N64SettingsDialog.kt"
replace_once(
    dialog,
    'Text("Widescreen ajustado usa o hack de aspect ratio do próprio GLideN64; não é só imagem esticada.", style = MaterialTheme.typography.bodySmall)',
    'Text("Formato da imagem é independente do preset de desempenho. Widescreen ajustado usa o hack do próprio GLideN64.", style = MaterialTheme.typography.bodySmall)'
)
replace_once(
    dialog,
    'onClick = { saveCore(config.copy(aspectRatio = mode)) },',
    'onClick = { N64Settings.saveAspectRatio(context, mode); refreshCore() },'
)
replace_once(
    dialog,
    '''                    N64Toggle(
                        title = "Framebuffer emulation",
                        subtitle = "Ative para efeitos que dependem de framebuffer. Desligado reduz custo e input lag em muitos jogos.",
                        checked = config.framebufferEmulation
                    ) { saveCore(config.copy(framebufferEmulation = it)) }
''',
    '''                    N64Toggle(
                        title = "Framebuffer emulation",
                        subtitle = if (config.aspectRatio.wide) {
                            "Protegido no widescreen para evitar menus/efeitos quebrados e manter o aspect ratio."
                        } else {
                            "Compatibilidade de efeitos e menus. Desligar é uma troca explícita por desempenho."
                        },
                        checked = config.framebufferEmulation,
                        enabled = !config.aspectRatio.wide
                    ) { saveCore(config.copy(framebufferEmulation = it)) }
'''
)
replace_once(
    dialog,
    'private fun N64Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {',
    'private fun N64Toggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {'
)
replace_once(
    dialog,
    'Switch(checked = checked, onCheckedChange = onChange)',
    'Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)'
)

# SmartPerf: protect compatibility framebuffer except in explicit Performance/4:3,
# and hold a larger audio cushion after any real underrun.
smart = "app/src/main/java/com/omnicore/emulator/performance/N64SmartPerf.kt"
replace_once(
    smart,
    '''        private var pressureStreak = 0
        private var healthyStreak = 0
        private var lastTransitionAt = 0L
''',
    '''        private var pressureStreak = 0
        private var healthyStreak = 0
        private var lastTransitionAt = 0L
        private var lastAudioStressAt = 0L
'''
)
replace_once(
    smart,
    '''            val candidate = resolve(profile, requested, signals, telemetry)
            val now = SystemClock.elapsedRealtime()
            val emergency = signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
''',
    '''            val candidate = resolve(profile, requested, signals, telemetry)
            val now = SystemClock.elapsedRealtime()
            if (recentUnderruns > 0 || telemetry.audioCritical) lastAudioStressAt = now
            fun protectAudio(decision: Decision): Decision = if (now - lastAudioStressAt < 12_000L) {
                decision.copy(
                    audioBufferBursts = max(decision.audioBufferBursts, 6),
                    reason = if (recentUnderruns > 0) "SmartPerf N64 recuperando áudio sem oscilar buffer" else decision.reason
                )
            } else decision
            val emergency = signals.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
'''
)
replace_once(
    smart,
    '''                current = candidate
                lastTransitionAt = now
                return current
''',
    '''                current = candidate
                lastTransitionAt = now
                return protectAudio(current)
'''
)
replace_once(
    smart,
    '''            return current
        }
    }
''',
    '''            return protectAudio(current)
        }
    }
'''
)
replace_once(
    smart,
    '''        val canThread = profile.is64Bit && profile.cpuCores >= 6

        fun safe(config: N64Settings.Config, threaded: Boolean): N64Settings.Config =
''',
    '''        val canThread = profile.is64Bit && profile.cpuCores >= 6
        val protectFramebuffer = requested.preset != N64Settings.Preset.PERFORMANCE || requested.aspectRatio.wide

        fun compatibleFramebuffer(underGpuPressure: Boolean): Boolean = when {
            protectFramebuffer -> true
            requested.aspectRatio.wide -> true
            underGpuPressure -> false
            else -> requested.framebufferEmulation
        }

        fun safe(config: N64Settings.Config, threaded: Boolean): N64Settings.Config =
'''
)
replace_once(
    smart,
    '''                        framebufferEmulation = if (telemetry.gpuBound || signals.memoryPressure) {
                            false
                        } else {
                            requested.framebufferEmulation
                        }
''',
    '''                        framebufferEmulation = compatibleFramebuffer(telemetry.gpuBound || signals.memoryPressure)
'''
)
replace_once(
    smart,
    '''                        framebufferEmulation = if (
                            telemetry.gpuBound || signals.memoryPressure ||
                                profile.tier == N64PerformanceProfile.Tier.LOW
                        ) false else requested.framebufferEmulation
''',
    '''                        framebufferEmulation = compatibleFramebuffer(
                            telemetry.gpuBound || signals.memoryPressure
                        )
'''
)

# Keep compatibility framebuffer while allowing separate low-cost visual choices.
header = "app/src/main/cpp/n64/n64_libretro_host.h"
replace_once(
    header,
    '''    bool framebufferEmulation = true;
    bool threadedRenderer = false;
''',
    '''    bool framebufferEmulation = true;
    bool leanGraphics = false;
    bool threadedRenderer = false;
'''
)

bridge_kt = "app/src/main/java/com/omnicore/emulator/core/n64/N64NativeBridge.kt"
replace_once(
    bridge_kt,
    '''                aspectRatio = config.aspectRatio.storage,
                framebufferEmulation = config.framebufferEmulation,
                threadedRenderer = config.threadedRenderer,
''',
    '''                aspectRatio = config.aspectRatio.storage,
                framebufferEmulation = config.framebufferEmulation,
                leanGraphics = decision.level != N64SmartPerf.Level.TURBO,
                threadedRenderer = config.threadedRenderer,
'''
)
replace_once(
    bridge_kt,
    '''        aspectRatio: String,
        framebufferEmulation: Boolean,
        threadedRenderer: Boolean,
''',
    '''        aspectRatio: String,
        framebufferEmulation: Boolean,
        leanGraphics: Boolean,
        threadedRenderer: Boolean,
'''
)

native_bridge = "app/src/main/cpp/n64/n64_native_bridge.cpp"
replace_once(
    native_bridge,
    '''    jstring aspectRatio,
    jboolean framebufferEmulation,
    jboolean threadedRenderer,
''',
    '''    jstring aspectRatio,
    jboolean framebufferEmulation,
    jboolean leanGraphics,
    jboolean threadedRenderer,
'''
)
replace_once(
    native_bridge,
    '''    config.aspectRatio = fromJString(env, aspectRatio);
    config.framebufferEmulation = framebufferEmulation == JNI_TRUE;
    config.threadedRenderer = threadedRenderer == JNI_TRUE;
''',
    '''    config.aspectRatio = fromJString(env, aspectRatio);
    config.framebufferEmulation = framebufferEmulation == JNI_TRUE;
    config.leanGraphics = leanGraphics == JNI_TRUE;
    config.threadedRenderer = threadedRenderer == JNI_TRUE;
'''
)

host = "app/src/main/cpp/n64/n64_libretro_host.cpp"
replace_once(
    host,
    '''    const bool wide = config_.aspectRatio == "16:9" || config_.aspectRatio == "16:9 adjusted";
    const bool leanGraphics = !config_.framebufferEmulation;
''',
    '''    const bool wide = config_.aspectRatio == "16:9" || config_.aspectRatio == "16:9 adjusted";
    const bool framebuffer = config_.framebufferEmulation;
    const bool leanGraphics = config_.leanGraphics;
'''
)
replace_once(
    host,
    '''    options_["mupen64plus-EnableFBEmulation"] = boolOption(config_.framebufferEmulation);
    options_["mupen64plus-EnableCopyColorToRDRAM"] = leanGraphics ? "Off" : "Async";
    options_["mupen64plus-EnableCopyDepthToRDRAM"] = leanGraphics ? "Off" : "Software";
''',
    '''    options_["mupen64plus-EnableFBEmulation"] = boolOption(framebuffer);
    options_["mupen64plus-EnableCopyColorToRDRAM"] = framebuffer ? "Async" : "Off";
    options_["mupen64plus-EnableCopyDepthToRDRAM"] = framebuffer ? "Software" : "Off";
'''
)
replace_once(
    host,
    '    options_["mupen64plus-BackgroundMode"] = "OnePiece";\n',
    '    options_["mupen64plus-BackgroundMode"] = "OnePiece";\n    options_["mupen64plus-CorrectTexrectCoords"] = "Auto";\n'
)
replace_once(
    host,
    '''    std::uint64_t lastRingUnderruns = 0;
    double resampleAccumulator = 0.0;
''',
    '''    std::uint64_t lastRingUnderruns = 0;
    std::int16_t lastAudioLeft = 0;
    std::int16_t lastAudioRight = 0;
    double resampleAccumulator = 0.0;
'''
)
replace_once(
    host,
    '''        const std::size_t requested = static_cast<std::size_t>(numFrames) * 2u;
        const std::size_t read = self->audioRing.pop(static_cast<std::int16_t*>(audioData), requested);
        if (read < requested && self->owner) {
            self->owner->audioUnderruns_.fetch_add(1, std::memory_order_relaxed);
        }
''',
    '''        const std::size_t requested = static_cast<std::size_t>(numFrames) * 2u;
        auto* output = static_cast<std::int16_t*>(audioData);
        const std::size_t read = self->audioRing.pop(output, requested);
        if (read >= 2) {
            self->lastAudioLeft = output[read - 2];
            self->lastAudioRight = output[read - 1];
        }
        if (read < requested) {
            // Conceal a short scheduler underrun with a tiny fade instead of an
            // abrupt block of zeroes. This cannot invent missing game audio, but
            // it removes the harsh click/freeze sensation while SmartPerf grows
            // the real AAudio cushion on the next telemetry pass.
            const std::size_t missingFrames = (requested - read) / 2u;
            for (std::size_t frame = 0; frame < missingFrames; ++frame) {
                const float gain = 1.0f - static_cast<float>(frame + 1u) /
                    static_cast<float>(missingFrames + 1u);
                output[read + frame * 2u] = static_cast<std::int16_t>(self->lastAudioLeft * gain);
                output[read + frame * 2u + 1u] = static_cast<std::int16_t>(self->lastAudioRight * gain);
            }
            if (self->owner) self->owner->audioUnderruns_.fetch_add(1, std::memory_order_relaxed);
        }
'''
)
replace_once(
    host,
    '''            audioPrimeFrames = std::min<int>(
                static_cast<int>(audioRing.capacitySamples() / 4u),
                std::max(framesPerBurst * 4, outputSampleRate / 30));
''',
    '''            audioPrimeFrames = std::min<int>(
                static_cast<int>(audioRing.capacitySamples() / 4u),
                std::max(framesPerBurst * 5, outputSampleRate / 24));
'''
)
replace_once(host, '++stableAudioChecks >= 12', '++stableAudioChecks >= 24')
replace_once(host, 'const float targetFillMs = std::max(34.0f, bufferMs * 1.55f);', 'const float targetFillMs = std::max(42.0f, bufferMs * 1.65f);')

# Activity: first boot no longer disables required framebuffer, and the tiny
# animated star is visible only during boot so it costs nothing during gameplay.
activity = "app/src/main/java/com/omnicore/emulator/emulation/N64EmulationActivity.kt"
replace_once(
    activity,
    '''    private lateinit var controls: N64GamepadOverlayView
    private lateinit var statusView: TextView
''',
    '''    private lateinit var controls: N64GamepadOverlayView
    private lateinit var statusView: TextView
    private lateinit var bootStar: N64RetroStarView
'''
)
replace_once(
    activity,
    '''                internalResolution = N64Settings.InternalResolution.NATIVE,
                framebufferEmulation = false,
                threadedRenderer = false
''',
    '''                internalResolution = N64Settings.InternalResolution.NATIVE,
                framebufferEmulation = base.effective.framebufferEmulation,
                threadedRenderer = false
'''
)
replace_once(
    activity,
    '''        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(10) }
        )

        val menuButton = TextView(this).apply {
''',
    '''        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(10) }
        )

        bootStar = N64RetroStarView(this)
        root.addView(
            bootStar,
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(9)
                leftMargin = dp(10)
            }
        )

        val menuButton = TextView(this).apply {
'''
)
replace_once(
    activity,
    '''                    if (runOkPolls == 1) {
                        runCatching {
''',
    '''                    if (runOkPolls == 1) {
                        bootStar.visibility = View.GONE
                        runCatching {
'''
)

print("OmniCore 0.10.8 N64 Alpha 9 migration applied")
