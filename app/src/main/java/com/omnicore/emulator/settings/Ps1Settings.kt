package com.omnicore.emulator.settings

import android.content.Context

object Ps1Settings {
    enum class Preset(val storage: String, val label: String, val subtitle: String) {
        SMART("smart", "Inteligente", "Equilibra qualidade, temperatura e estabilidade"),
        PERFORMANCE("performance", "Desempenho", "Menor carga e maior margem para aparelhos modestos"),
        BALANCED("balanced", "Equilibrado", "Fidelidade original com boa estabilidade"),
        QUALITY("quality", "Qualidade", "Resolução aprimorada e maior fidelidade visual"),
        CUSTOM("custom", "Custom", "Ajustes avançados definidos por você")
    }

    enum class AspectMode(val storage: String, val label: String, val subtitle: String) {
        ORIGINAL_4_3("4_3", "4:3 original", "Proporção clássica do PlayStation"),
        WIDE_16_9("16_9", "16:9", "Expande a apresentação para widescreen"),
        FULLSCREEN("fullscreen", "Tela cheia", "Preenche toda a área disponível")
    }

    data class Config(
        val preset: Preset,
        val enhancedResolution: Boolean,
        val enhancedSpeedHack: Boolean,
        val textureAdjustment: Boolean,
        val dithering: Boolean,
        val threadedGpu: Boolean,
        val threadedSpu: Boolean,
        val frameskipAuto: Boolean,
        val cdReadAhead: Int,
        val interpolation: String,
        val dualShock: Boolean,
        val showBiosBootLogo: Boolean,
        val aspectMode: AspectMode
    ) {
        fun toCoreOptions(): String = buildList {
            add("pcsx_rearmed_bios=auto")
            add("pcsx_rearmed_drc=enabled")
            add("pcsx_rearmed_drc_thread=auto")
            add("pcsx_rearmed_gpu_thread_rendering=${if (!threadedGpu) "disabled" else if (preset == Preset.PERFORMANCE) "enabled" else "auto"}")
            add("pcsx_rearmed_spu_thread=${if (threadedSpu) "enabled" else "disabled"}")
            add("pcsx_rearmed_neon_enhancement_enable=${if (enhancedResolution) "enabled" else "disabled"}")
            add("pcsx_rearmed_neon_enhancement_no_main=${if (enhancedSpeedHack) "enabled" else "disabled"}")
            add("pcsx_rearmed_neon_enhancement_tex_adj_v2=${if (textureAdjustment) "enabled" else "disabled"}")
            add("pcsx_rearmed_dithering=${if (dithering) "enabled" else "disabled"}")
            add("pcsx_rearmed_frameskip_type=${if (frameskipAuto) "auto" else "disabled"}")
            add("pcsx_rearmed_frameskip_threshold=33")
            add("pcsx_rearmed_cd_readahead=${cdReadAhead.coerceIn(0, 128)}")
            add("pcsx_rearmed_spu_interpolation=${if (interpolation in setOf("simple", "gaussian", "cubic", "off")) interpolation else "simple"}")
            add("pcsx_rearmed_spu_reverb=${if (preset == Preset.PERFORMANCE) "disabled" else "enabled"}")
            add("pcsx_rearmed_region=auto")
            add("pcsx_rearmed_psxclock=auto")
            add("pcsx_rearmed_cd_turbo=disabled")
            add("pcsx_rearmed_nostalls=disabled")
            add("pcsx_rearmed_icache_emulation=enabled")
            add("pcsx_rearmed_exception_emulation=disabled")
            add("pcsx_rearmed_gpu_slow_llists=auto")
            add("pcsx_rearmed_fractional_framerate=auto")
            add("pcsx_rearmed_neon_interlace_enable_v2=auto")
            add("pcsx_rearmed_rgb32_output=enabled")
            add("pcsx_rearmed_noxadecoding=disabled")
            add("pcsx_rearmed_nocdaudio=disabled")
            add("pcsx_rearmed_show_bios_bootlogo=${if (showBiosBootLogo) "enabled" else "disabled"}")
            add("pcsx_rearmed_memcard1=libretro")
        }.joinToString("\n")
    }

    private const val PREFS = "ps1_settings"
    private const val KEY_PRESET = "preset"
    private const val KEY_DUALSHOCK = "dualshock"
    private const val KEY_BOOT_LOGO = "bios_boot_logo"
    private const val KEY_ASPECT_MODE = "aspect_mode"
    private const val K_ENHANCED = "enhanced"
    private const val K_SPEED = "enhanced_speed"
    private const val K_TEXTURE = "texture_adj"
    private const val K_DITHER = "dither"
    private const val K_GPU_THREAD = "gpu_thread"
    private const val K_SPU_THREAD = "spu_thread"
    private const val K_FRAMESKIP = "frameskip"
    private const val K_READAHEAD = "readahead"
    private const val K_INTERPOLATION = "interpolation"

    fun readPreset(context: Context): Preset {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRESET, Preset.SMART.storage)
        return Preset.entries.firstOrNull { it.storage == raw } ?: Preset.SMART
    }

    fun savePreset(context: Context, preset: Preset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PRESET, preset.storage).apply()
    }

    fun readDualShock(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DUALSHOCK, true)

    fun saveDualShock(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DUALSHOCK, enabled).apply()
    }

    fun readBiosBootLogo(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BOOT_LOGO, true)

    fun saveBiosBootLogo(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BOOT_LOGO, enabled).apply()
    }

    fun readAspectMode(context: Context): AspectMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ASPECT_MODE, AspectMode.ORIGINAL_4_3.storage)
        return AspectMode.entries.firstOrNull { it.storage == raw } ?: AspectMode.ORIGINAL_4_3
    }

    fun saveAspectMode(context: Context, mode: AspectMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ASPECT_MODE, mode.storage).apply()
    }

    private fun presetConfig(
        preset: Preset,
        dualShock: Boolean,
        showBiosBootLogo: Boolean,
        aspectMode: AspectMode
    ): Config = when (preset) {
        Preset.PERFORMANCE -> Config(preset, false, false, false, false, true, true, true, 32, "simple", dualShock, showBiosBootLogo, aspectMode)
        Preset.BALANCED -> Config(preset, false, false, true, true, false, false, false, 8, "simple", dualShock, showBiosBootLogo, aspectMode)
        Preset.QUALITY -> Config(preset, true, false, true, true, false, false, false, 8, "gaussian", dualShock, showBiosBootLogo, aspectMode)
        else -> Config(Preset.SMART, false, false, false, true, false, false, false, 8, "simple", dualShock, showBiosBootLogo, aspectMode)
    }

    fun resolve(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val preset = readPreset(context)
        val dualShock = readDualShock(context)
        val bootLogo = readBiosBootLogo(context)
        val aspectMode = readAspectMode(context)
        if (preset != Preset.CUSTOM) return presetConfig(preset, dualShock, bootLogo, aspectMode)
        val fallback = presetConfig(Preset.SMART, dualShock, bootLogo, aspectMode)
        return Config(
            preset = Preset.CUSTOM,
            enhancedResolution = prefs.getBoolean(K_ENHANCED, fallback.enhancedResolution),
            enhancedSpeedHack = prefs.getBoolean(K_SPEED, fallback.enhancedSpeedHack),
            textureAdjustment = prefs.getBoolean(K_TEXTURE, fallback.textureAdjustment),
            dithering = prefs.getBoolean(K_DITHER, fallback.dithering),
            threadedGpu = prefs.getBoolean(K_GPU_THREAD, fallback.threadedGpu),
            threadedSpu = prefs.getBoolean(K_SPU_THREAD, fallback.threadedSpu),
            frameskipAuto = prefs.getBoolean(K_FRAMESKIP, fallback.frameskipAuto),
            cdReadAhead = prefs.getInt(K_READAHEAD, fallback.cdReadAhead),
            interpolation = prefs.getString(K_INTERPOLATION, fallback.interpolation) ?: fallback.interpolation,
            dualShock = dualShock,
            showBiosBootLogo = bootLogo,
            aspectMode = aspectMode
        )
    }

    fun saveCustom(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRESET, Preset.CUSTOM.storage)
            .putBoolean(K_ENHANCED, config.enhancedResolution)
            .putBoolean(K_SPEED, config.enhancedSpeedHack)
            .putBoolean(K_TEXTURE, config.textureAdjustment)
            .putBoolean(K_DITHER, config.dithering)
            .putBoolean(K_GPU_THREAD, config.threadedGpu)
            .putBoolean(K_SPU_THREAD, config.threadedSpu)
            .putBoolean(K_FRAMESKIP, config.frameskipAuto)
            .putInt(K_READAHEAD, config.cdReadAhead)
            .putString(K_INTERPOLATION, config.interpolation)
            .putBoolean(KEY_BOOT_LOGO, config.showBiosBootLogo)
            .putString(KEY_ASPECT_MODE, config.aspectMode.storage)
            .apply()
    }
}
