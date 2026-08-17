package com.omnicore.emulator.settings

import android.content.Context

object Ps1Settings {
    enum class Preset(val storage: String, val label: String, val subtitle: String) {
        SMART("smart", "Inteligente", "Equilibra qualidade e estabilidade automaticamente"),
        PERFORMANCE("performance", "Desempenho", "Menor carga e maior margem para aparelhos modestos"),
        BALANCED("balanced", "Equilibrado", "Fidelidade original com boa estabilidade"),
        QUALITY("quality", "Qualidade", "Resolução aprimorada e maior fidelidade visual")
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
            add("pcsx_rearmed_cd_readahead=$cdReadAhead")
            add("pcsx_rearmed_spu_interpolation=$interpolation")
            add("pcsx_rearmed_spu_reverb=${if (preset == Preset.PERFORMANCE) "disabled" else "enabled"}")
            add("pcsx_rearmed_region=auto")
            add("pcsx_rearmed_memcard1=libretro")
        }.joinToString("\n")
    }

    private const val PREFS = "ps1_settings"
    private const val KEY_PRESET = "preset"
    private const val KEY_DUALSHOCK = "dualshock"

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

    fun resolve(context: Context): Config {
        val preset = readPreset(context)
        val dualShock = readDualShock(context)
        return when (preset) {
            Preset.SMART -> Config(
                preset = preset,
                enhancedResolution = false,
                enhancedSpeedHack = false,
                textureAdjustment = true,
                dithering = true,
                threadedGpu = true,
                threadedSpu = false,
                frameskipAuto = false,
                cdReadAhead = 16,
                interpolation = "simple",
                dualShock = dualShock
            )
            Preset.PERFORMANCE -> Config(
                preset = preset,
                enhancedResolution = false,
                enhancedSpeedHack = false,
                textureAdjustment = false,
                dithering = false,
                threadedGpu = true,
                threadedSpu = true,
                frameskipAuto = true,
                cdReadAhead = 32,
                interpolation = "simple",
                dualShock = dualShock
            )
            Preset.BALANCED -> Config(
                preset = preset,
                enhancedResolution = false,
                enhancedSpeedHack = false,
                textureAdjustment = true,
                dithering = true,
                threadedGpu = true,
                threadedSpu = false,
                frameskipAuto = false,
                cdReadAhead = 16,
                interpolation = "gaussian",
                dualShock = dualShock
            )
            Preset.QUALITY -> Config(
                preset = preset,
                enhancedResolution = true,
                enhancedSpeedHack = false,
                textureAdjustment = true,
                dithering = true,
                threadedGpu = true,
                threadedSpu = false,
                frameskipAuto = false,
                cdReadAhead = 16,
                interpolation = "gaussian",
                dualShock = dualShock
            )
        }
    }
}
