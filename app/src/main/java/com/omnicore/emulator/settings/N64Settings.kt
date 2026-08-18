package com.omnicore.emulator.settings

import android.content.Context

/** Nintendo 64 settings live in their own preference namespace. */
object N64Settings {
    enum class Preset(val storage: String, val label: String, val subtitle: String) {
        AUTO("auto", "Inteligente", "Escolhe opções seguras conforme o hardware"),
        PERFORMANCE("performance", "Desempenho", "Prioriza estabilidade, FPS e áudio"),
        BALANCED("balanced", "Equilibrado", "Boa compatibilidade com custo gráfico moderado"),
        QUALITY("quality", "Qualidade", "Aumenta qualidade somente quando existe margem"),
        CUSTOM("custom", "Custom", "Ajustes definidos manualmente")
    }

    enum class CpuMode(val storage: String, val label: String) {
        DYNAREC("dynamic_recompiler", "Dynarec"),
        CACHED_INTERPRETER("cached_interpreter", "Cached Interpreter")
    }

    enum class RspMode(val storage: String, val label: String) {
        HLE("hle", "HLE"),
        LLE("lle", "LLE")
    }

    enum class InternalResolution(val storage: String, val label: String, val multiplier: Int) {
        NATIVE("native", "Nativa", 1),
        X2("2x", "2×", 2)
    }

    enum class AspectRatio(val storage: String, val label: String, val wide: Boolean) {
        ORIGINAL_4_3("4:3", "Original 4:3", false),
        WIDE_ADJUSTED("16:9 adjusted", "Widescreen ajustado", true),
        WIDE_STRETCHED("16:9", "Widescreen esticado", true)
    }

    enum class ExpansionPak(val storage: String, val label: String) {
        AUTO("auto", "Automático"),
        ENABLED("enabled", "Ativado"),
        DISABLED("disabled", "Desativado")
    }

    data class Config(
        val preset: Preset,
        val cpuMode: CpuMode,
        val rspMode: RspMode,
        val internalResolution: InternalResolution,
        val aspectRatio: AspectRatio,
        val framebufferEmulation: Boolean,
        val expansionPak: ExpansionPak,
        val threadedRenderer: Boolean
    )

    private const val PREFS = "n64_settings"
    private const val KEY_PRESET = "preset"
    private const val KEY_CPU = "cpu_mode"
    private const val KEY_RSP = "rsp_mode"
    private const val KEY_RESOLUTION = "internal_resolution"
    private const val KEY_ASPECT = "aspect_ratio"
    private const val KEY_FRAMEBUFFER = "framebuffer_emulation"
    private const val KEY_EXPANSION = "expansion_pak"
    private const val KEY_THREADED_RENDERER = "threaded_renderer"

    fun readPreset(context: Context): Preset {
        val raw = prefs(context).getString(KEY_PRESET, Preset.AUTO.storage)
        return Preset.entries.firstOrNull { it.storage == raw } ?: Preset.AUTO
    }

    fun savePreset(context: Context, preset: Preset) {
        prefs(context).edit().putString(KEY_PRESET, preset.storage).apply()
    }

    fun resolve(context: Context): Config {
        val preset = readPreset(context)
        if (preset != Preset.CUSTOM) return presetConfig(preset, N64PerformanceProfile.detect(context))

        val storage = prefs(context)
        val device = N64PerformanceProfile.detect(context)
        val fallback = presetConfig(Preset.AUTO, device)
        return Config(
            preset = Preset.CUSTOM,
            cpuMode = CpuMode.entries.firstOrNull { it.storage == storage.getString(KEY_CPU, fallback.cpuMode.storage) } ?: fallback.cpuMode,
            rspMode = RspMode.entries.firstOrNull { it.storage == storage.getString(KEY_RSP, fallback.rspMode.storage) } ?: fallback.rspMode,
            internalResolution = InternalResolution.entries.firstOrNull {
                it.storage == storage.getString(KEY_RESOLUTION, fallback.internalResolution.storage)
            } ?: fallback.internalResolution,
            aspectRatio = AspectRatio.entries.firstOrNull {
                it.storage == storage.getString(KEY_ASPECT, fallback.aspectRatio.storage)
            } ?: fallback.aspectRatio,
            framebufferEmulation = storage.getBoolean(KEY_FRAMEBUFFER, fallback.framebufferEmulation),
            expansionPak = ExpansionPak.entries.firstOrNull {
                it.storage == storage.getString(KEY_EXPANSION, fallback.expansionPak.storage)
            } ?: fallback.expansionPak,
            threadedRenderer = storage.getBoolean(KEY_THREADED_RENDERER, fallback.threadedRenderer)
        ).sanitized(device)
    }

    fun saveCustom(context: Context, config: Config) {
        val safe = config.copy(preset = Preset.CUSTOM).sanitized(N64PerformanceProfile.detect(context))
        prefs(context).edit()
            .putString(KEY_PRESET, Preset.CUSTOM.storage)
            .putString(KEY_CPU, safe.cpuMode.storage)
            .putString(KEY_RSP, safe.rspMode.storage)
            .putString(KEY_RESOLUTION, safe.internalResolution.storage)
            .putString(KEY_ASPECT, safe.aspectRatio.storage)
            .putBoolean(KEY_FRAMEBUFFER, safe.framebufferEmulation)
            .putString(KEY_EXPANSION, safe.expansionPak.storage)
            .putBoolean(KEY_THREADED_RENDERER, safe.threadedRenderer)
            .apply()
    }

    private fun presetConfig(preset: Preset, device: N64PerformanceProfile.Profile): Config = when (preset) {
        Preset.PERFORMANCE -> Config(
            preset = preset,
            cpuMode = CpuMode.DYNAREC,
            rspMode = RspMode.HLE,
            internalResolution = InternalResolution.NATIVE,
            aspectRatio = AspectRatio.ORIGINAL_4_3,
            framebufferEmulation = false,
            expansionPak = ExpansionPak.AUTO,
            threadedRenderer = false
        )
        Preset.BALANCED -> Config(
            preset = preset,
            cpuMode = CpuMode.DYNAREC,
            rspMode = RspMode.HLE,
            internalResolution = InternalResolution.NATIVE,
            aspectRatio = AspectRatio.ORIGINAL_4_3,
            framebufferEmulation = true,
            expansionPak = ExpansionPak.AUTO,
            threadedRenderer = false
        )
        Preset.QUALITY -> Config(
            preset = preset,
            cpuMode = CpuMode.DYNAREC,
            rspMode = RspMode.HLE,
            internalResolution = if (device.tier == N64PerformanceProfile.Tier.HIGH) InternalResolution.X2 else InternalResolution.NATIVE,
            aspectRatio = AspectRatio.ORIGINAL_4_3,
            framebufferEmulation = true,
            expansionPak = ExpansionPak.AUTO,
            threadedRenderer = false
        )
        Preset.AUTO, Preset.CUSTOM -> Config(
            preset = Preset.AUTO,
            cpuMode = CpuMode.DYNAREC,
            rspMode = RspMode.HLE,
            internalResolution = if (device.tier == N64PerformanceProfile.Tier.HIGH) InternalResolution.X2 else InternalResolution.NATIVE,
            aspectRatio = AspectRatio.ORIGINAL_4_3,
            framebufferEmulation = device.tier != N64PerformanceProfile.Tier.LOW,
            expansionPak = ExpansionPak.AUTO,
            threadedRenderer = false
        )
    }.sanitized(device)

    private fun Config.sanitized(device: N64PerformanceProfile.Profile): Config {
        val safeRsp = RspMode.HLE
        val safeResolution = if (
            device.tier == N64PerformanceProfile.Tier.LOW && internalResolution == InternalResolution.X2
        ) {
            InternalResolution.NATIVE
        } else {
            internalResolution
        }
        // Keep threaded GLideN64 opt-in until the single-thread path has wider
        // device coverage. Dynarec is now the default performance path.
        val safeThreadedRenderer = false
        val safeExpansionPak = ExpansionPak.AUTO
        return copy(
            rspMode = safeRsp,
            internalResolution = safeResolution,
            expansionPak = safeExpansionPak,
            threadedRenderer = safeThreadedRenderer
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
