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
        val dualShock: Boolean
    ) {
        fun toCoreOptions(): String = buildList {
            add("pcsx_rearmed_bios=auto")
            add("pcsx_rearmed_drc=enabled")
            add("pcsx_rearmed_drc_thread=auto")
            add("pcsx_rearmed_gpu_thread_rendering=${if (threadedGpu) "enabled" else "disabled"}")
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
            add("pcsx_rearmed_memcard1=libretro")
        }.joinToString("\n")
    }
}

private const val PREFS = "ps1_settings"
private const val KEY_PRESET = "preset"
private const val KEY_DUALSHOCK = "dualshock"
private const val K_ENHANCED = "enhanced"
private const val K_SPEED = "enhanced_speed"
private const val K_TEXTURE = "texture_adj"
private const val K_DITHER = "dither"
private const val K_GPU_THREAD = "gpu_thread"
private const val K_SPU_THREAD = "spu_thread"
private const val K_FRAMESKIP = "frameskip"
private const val K_READAHEAD = "readahead"
private const val K_INTERPOLATION = "interpolation"

fun Ps1Settings.readPreset(context: Context): Ps1Settings.Preset {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PRESET, Ps1Settings.Preset.SMART.storage)
    return Ps1Settings.Preset.entries.firstOrNull { it.storage == raw } ?: Ps1Settings.Preset.SMART
}

fun Ps1Settings.savePreset(context: Context, preset: Ps1Settings.Preset) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_PRESET, preset.storage).apply()
}

fun Ps1Settings.readDualShock(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DUALSHOCK, true)

fun Ps1Settings.saveDualShock(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_DUALSHOCK, enabled).apply()
}

private fun presetConfig(preset: Ps1Settings.Preset, dualShock: Boolean): Ps1Settings.Config = when (preset) {
    Ps1Settings.Preset.PERFORMANCE -> Ps1Settings.Config(
        preset, false, false, false, false, true, true, true, 32, "simple", dualShock
    )
    Ps1Settings.Preset.BALANCED -> Ps1Settings.Config(
        preset, false, false, true, true, true, false, false, 16, "gaussian", dualShock
    )
    Ps1Settings.Preset.QUALITY -> Ps1Settings.Config(
        preset, true, false, true, true, true, false, false, 16, "gaussian", dualShock
    )
    else -> Ps1Settings.Config(
        Ps1Settings.Preset.SMART, false, false, true, true, true, false, false, 16, "simple", dualShock
    )
}

fun Ps1Settings.resolve(context: Context): Ps1Settings.Config {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val preset = readPreset(context)
    val dualShock = readDualShock(context)
    if (preset != Ps1Settings.Preset.CUSTOM) return presetConfig(preset, dualShock)
    val fallback = presetConfig(Ps1Settings.Preset.SMART, dualShock)
    return Ps1Settings.Config(
        preset = Ps1Settings.Preset.CUSTOM,
        enhancedResolution = prefs.getBoolean(K_ENHANCED, fallback.enhancedResolution),
        enhancedSpeedHack = prefs.getBoolean(K_SPEED, fallback.enhancedSpeedHack),
        textureAdjustment = prefs.getBoolean(K_TEXTURE, fallback.textureAdjustment),
        dithering = prefs.getBoolean(K_DITHER, fallback.dithering),
        threadedGpu = prefs.getBoolean(K_GPU_THREAD, fallback.threadedGpu),
        threadedSpu = prefs.getBoolean(K_SPU_THREAD, fallback.threadedSpu),
        frameskipAuto = prefs.getBoolean(K_FRAMESKIP, fallback.frameskipAuto),
        cdReadAhead = prefs.getInt(K_READAHEAD, fallback.cdReadAhead),
        interpolation = prefs.getString(K_INTERPOLATION, fallback.interpolation) ?: fallback.interpolation,
        dualShock = dualShock
    )
}

fun Ps1Settings.saveCustom(context: Context, config: Ps1Settings.Config) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_PRESET, Ps1Settings.Preset.CUSTOM.storage)
        .putBoolean(K_ENHANCED, config.enhancedResolution)
        .putBoolean(K_SPEED, config.enhancedSpeedHack)
        .putBoolean(K_TEXTURE, config.textureAdjustment)
        .putBoolean(K_DITHER, config.dithering)
        .putBoolean(K_GPU_THREAD, config.threadedGpu)
        .putBoolean(K_SPU_THREAD, config.threadedSpu)
        .putBoolean(K_FRAMESKIP, config.frameskipAuto)
        .putInt(K_READAHEAD, config.cdReadAhead)
        .putString(K_INTERPOLATION, config.interpolation)
        .apply()
}
