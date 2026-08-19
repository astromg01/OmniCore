package com.omnicore.emulator.settings

import android.content.Context
import com.omnicore.emulator.core.ps2.PS2Backend

/** PlayStation 2 settings namespace. Nothing here mutates PS1 or N64 preferences. */
object PS2Settings {
    enum class Preset(val storage: String, val label: String, val subtitle: String) {
        AUTO("auto", "Inteligente", "Prioriza funcionamento estável e usa o renderer mais adequado disponível"),
        PERFORMANCE("performance", "Desempenho", "Menor overhead e resposta de áudio mais curta"),
        BALANCED("balanced", "Equilibrado", "Compatibilidade e latência moderadas"),
        QUALITY("quality", "Qualidade", "Aumenta resolução somente por escolha explícita"),
        CUSTOM("custom", "Custom", "Ajustes manuais do runtime PS2")
    }

    enum class RendererMode(val storage: String, val label: String) {
        AUTO("auto", "Automático"),
        GLES3("gles3", "OpenGL ES"),
        VULKAN("vulkan", "Vulkan")
    }

    enum class InternalResolution(val storage: String, val label: String, val factor: Int) {
        NATIVE("1x", "Nativa 1×", 1),
        X2("2x", "2×", 2),
        X4("4x", "4×", 4)
    }

    enum class Presentation(val storage: String, val label: String, val nativeValue: Int) {
        FILL("fill", "Preencher", 0),
        FIT("fit", "Ajustar", 1),
        ORIGINAL("original", "Original", 2)
    }

    enum class BootStyle(val storage: String, val label: String) {
        CLASSIC("classic", "Visual OmniCore"),
        DIRECT("direct", "Direta")
    }

    data class Config(
        val preset: Preset,
        val renderer: RendererMode,
        val internalResolution: InternalResolution,
        val widescreen: Boolean,
        val presentation: Presentation,
        val forceBilinear: Boolean,
        val frameLimit: Boolean,
        val spuBlockCount: Int,
        val bootStyle: BootStyle
    )

    private const val PREFS = "ps2_settings"
    private const val KEY_PRESET = "preset"
    private const val KEY_RENDERER = "renderer"
    private const val KEY_RESOLUTION = "internal_resolution"
    private const val KEY_WIDESCREEN = "widescreen"
    private const val KEY_PRESENTATION = "presentation"
    private const val KEY_BILINEAR = "force_bilinear"
    private const val KEY_FRAME_LIMIT = "frame_limit"
    private const val KEY_SPU_BLOCKS = "spu_blocks"
    private const val KEY_BOOT_STYLE = "boot_style"

    fun readPreset(context: Context): Preset {
        val raw = prefs(context).getString(KEY_PRESET, Preset.AUTO.storage)
        return Preset.entries.firstOrNull { it.storage == raw } ?: Preset.AUTO
    }

    fun savePreset(context: Context, preset: Preset) {
        prefs(context).edit().putString(KEY_PRESET, preset.storage).apply()
    }

    fun readBootStyle(context: Context): BootStyle {
        val raw = prefs(context).getString(KEY_BOOT_STYLE, BootStyle.DIRECT.storage)
        return BootStyle.entries.firstOrNull { it.storage == raw } ?: BootStyle.DIRECT
    }

    fun saveBootStyle(context: Context, style: BootStyle) {
        prefs(context).edit().putString(KEY_BOOT_STYLE, style.storage).apply()
    }

    fun resolve(context: Context): Config {
        val preset = readPreset(context)
        if (preset != Preset.CUSTOM) {
            return presetConfig(preset).copy(bootStyle = readBootStyle(context))
        }
        val storage = prefs(context)
        val fallback = presetConfig(Preset.AUTO)
        return Config(
            preset = Preset.CUSTOM,
            renderer = RendererMode.entries.firstOrNull {
                it.storage == storage.getString(KEY_RENDERER, fallback.renderer.storage)
            } ?: fallback.renderer,
            internalResolution = InternalResolution.entries.firstOrNull {
                it.storage == storage.getString(KEY_RESOLUTION, fallback.internalResolution.storage)
            } ?: fallback.internalResolution,
            widescreen = storage.getBoolean(KEY_WIDESCREEN, fallback.widescreen),
            presentation = Presentation.entries.firstOrNull {
                it.storage == storage.getString(KEY_PRESENTATION, fallback.presentation.storage)
            } ?: fallback.presentation,
            forceBilinear = storage.getBoolean(KEY_BILINEAR, fallback.forceBilinear),
            frameLimit = storage.getBoolean(KEY_FRAME_LIMIT, true),
            spuBlockCount = storage.getInt(KEY_SPU_BLOCKS, fallback.spuBlockCount).coerceIn(50, 400),
            bootStyle = readBootStyle(context)
        )
    }

    fun saveCustom(context: Context, config: Config) {
        val safe = config.copy(
            preset = Preset.CUSTOM,
            spuBlockCount = config.spuBlockCount.coerceIn(50, 400)
        )
        prefs(context).edit()
            .putString(KEY_PRESET, Preset.CUSTOM.storage)
            .putString(KEY_RENDERER, safe.renderer.storage)
            .putString(KEY_RESOLUTION, safe.internalResolution.storage)
            .putBoolean(KEY_WIDESCREEN, safe.widescreen)
            .putString(KEY_PRESENTATION, safe.presentation.storage)
            .putBoolean(KEY_BILINEAR, safe.forceBilinear)
            .putBoolean(KEY_FRAME_LIMIT, safe.frameLimit)
            .putInt(KEY_SPU_BLOCKS, safe.spuBlockCount)
            .putString(KEY_BOOT_STYLE, safe.bootStyle.storage)
            .apply()
    }

    /**
     * Play!'s Android backend supports both OpenGL ES and Vulkan. For the AUTO
     * path we prefer Vulkan whenever the device and packaged backend expose it.
     * Explicit GLES remains available as the compatibility fallback.
     */
    fun rendererFor(config: Config, caps: PS2Backend.Capabilities): PS2Backend.Renderer = when (config.renderer) {
        RendererMode.VULKAN -> if (caps.vulkan) PS2Backend.Renderer.VULKAN else PS2Backend.Renderer.GLES3
        RendererMode.GLES3 -> PS2Backend.Renderer.GLES3
        RendererMode.AUTO -> if (caps.vulkan) PS2Backend.Renderer.VULKAN else PS2Backend.Renderer.GLES3
    }

    private fun presetConfig(preset: Preset): Config = when (preset) {
        Preset.PERFORMANCE -> Config(
            preset, RendererMode.AUTO, InternalResolution.NATIVE,
            widescreen = false, presentation = Presentation.FIT,
            forceBilinear = false, frameLimit = true, spuBlockCount = 100,
            bootStyle = BootStyle.DIRECT
        )
        Preset.BALANCED -> Config(
            preset, RendererMode.AUTO, InternalResolution.NATIVE,
            widescreen = false, presentation = Presentation.FIT,
            forceBilinear = false, frameLimit = true, spuBlockCount = 100,
            bootStyle = BootStyle.DIRECT
        )
        Preset.QUALITY -> Config(
            preset, RendererMode.AUTO, InternalResolution.X2,
            widescreen = false, presentation = Presentation.FIT,
            forceBilinear = true, frameLimit = true, spuBlockCount = 100,
            bootStyle = BootStyle.DIRECT
        )
        Preset.AUTO, Preset.CUSTOM -> Config(
            Preset.AUTO, RendererMode.AUTO, InternalResolution.NATIVE,
            widescreen = false, presentation = Presentation.FIT,
            forceBilinear = false, frameLimit = true, spuBlockCount = 100,
            bootStyle = BootStyle.DIRECT
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
