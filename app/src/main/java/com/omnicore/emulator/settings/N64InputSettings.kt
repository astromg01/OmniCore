package com.omnicore.emulator.settings

import android.content.Context

/** Nintendo 64 controller policy. Never reuses PS1/DualShock button semantics. */
object N64InputSettings {
    enum class CButtonMode(val storage: String, val label: String) {
        BUTTONS("buttons", "4 botões C"),
        RIGHT_STICK("right_stick", "Analógico direito")
    }

    enum class PakMode(val storage: String, val label: String) {
        AUTO("auto", "Automático"),
        MEMORY("memory", "Controller Pak"),
        RUMBLE("rumble", "Rumble Pak"),
        NONE("none", "Nenhum")
    }

    enum class OverlayPreset(val storage: String, val label: String) {
        CLEAN("clean", "Clear"),
        STANDARD("standard", "Padrão"),
        COMPACT("compact", "Compacto")
    }

    data class Config(
        val analogDeadzone: Float,
        val analogSensitivity: Float,
        val cButtonMode: CButtonMode,
        val pakMode: PakMode,
        val haptics: Boolean,
        val overlayPreset: OverlayPreset,
        val touchOpacity: Float,
        val touchScale: Float,
        val dynamicOpacity: Boolean,
        val showDpad: Boolean
    )

    private const val PREFS = "n64_input_settings"
    private const val KEY_DEADZONE = "analog_deadzone"
    private const val KEY_SENSITIVITY = "analog_sensitivity"
    private const val KEY_C_MODE = "c_button_mode"
    private const val KEY_PAK = "pak_mode"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_OVERLAY = "overlay_preset"
    private const val KEY_OPACITY = "touch_opacity"
    private const val KEY_SCALE = "touch_scale"
    private const val KEY_DYNAMIC_OPACITY = "dynamic_opacity"
    private const val KEY_SHOW_DPAD = "show_dpad"

    fun resolve(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            analogDeadzone = prefs.getFloat(KEY_DEADZONE, 0.10f).coerceIn(0.04f, 0.30f),
            analogSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.05f).coerceIn(0.70f, 1.30f),
            cButtonMode = CButtonMode.entries.firstOrNull {
                it.storage == prefs.getString(KEY_C_MODE, CButtonMode.BUTTONS.storage)
            } ?: CButtonMode.BUTTONS,
            pakMode = PakMode.entries.firstOrNull {
                it.storage == prefs.getString(KEY_PAK, PakMode.AUTO.storage)
            } ?: PakMode.AUTO,
            haptics = prefs.getBoolean(KEY_HAPTICS, false),
            overlayPreset = OverlayPreset.entries.firstOrNull {
                it.storage == prefs.getString(KEY_OVERLAY, OverlayPreset.CLEAN.storage)
            } ?: OverlayPreset.CLEAN,
            touchOpacity = prefs.getFloat(KEY_OPACITY, 0.62f).coerceIn(0.25f, 1f),
            touchScale = prefs.getFloat(KEY_SCALE, 0.92f).coerceIn(0.72f, 1.28f),
            dynamicOpacity = prefs.getBoolean(KEY_DYNAMIC_OPACITY, true),
            showDpad = prefs.getBoolean(KEY_SHOW_DPAD, true)
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_DEADZONE, config.analogDeadzone.coerceIn(0.04f, 0.30f))
            .putFloat(KEY_SENSITIVITY, config.analogSensitivity.coerceIn(0.70f, 1.30f))
            .putString(KEY_C_MODE, config.cButtonMode.storage)
            .putString(KEY_PAK, config.pakMode.storage)
            .putBoolean(KEY_HAPTICS, config.haptics)
            .putString(KEY_OVERLAY, config.overlayPreset.storage)
            .putFloat(KEY_OPACITY, config.touchOpacity.coerceIn(0.25f, 1f))
            .putFloat(KEY_SCALE, config.touchScale.coerceIn(0.72f, 1.28f))
            .putBoolean(KEY_DYNAMIC_OPACITY, config.dynamicOpacity)
            .putBoolean(KEY_SHOW_DPAD, config.showDpad)
            .apply()
    }
}
