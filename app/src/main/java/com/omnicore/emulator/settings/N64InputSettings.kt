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

    data class Config(
        val analogDeadzone: Float,
        val analogSensitivity: Float,
        val cButtonMode: CButtonMode,
        val pakMode: PakMode,
        val haptics: Boolean
    )

    private const val PREFS = "n64_input_settings"
    private const val KEY_DEADZONE = "analog_deadzone"
    private const val KEY_SENSITIVITY = "analog_sensitivity"
    private const val KEY_C_MODE = "c_button_mode"
    private const val KEY_PAK = "pak_mode"
    private const val KEY_HAPTICS = "haptics"

    fun resolve(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            analogDeadzone = prefs.getFloat(KEY_DEADZONE, 0.12f).coerceIn(0.04f, 0.30f),
            analogSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1f).coerceIn(0.70f, 1.30f),
            cButtonMode = CButtonMode.entries.firstOrNull {
                it.storage == prefs.getString(KEY_C_MODE, CButtonMode.BUTTONS.storage)
            } ?: CButtonMode.BUTTONS,
            pakMode = PakMode.entries.firstOrNull {
                it.storage == prefs.getString(KEY_PAK, PakMode.AUTO.storage)
            } ?: PakMode.AUTO,
            haptics = prefs.getBoolean(KEY_HAPTICS, false)
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_DEADZONE, config.analogDeadzone.coerceIn(0.04f, 0.30f))
            .putFloat(KEY_SENSITIVITY, config.analogSensitivity.coerceIn(0.70f, 1.30f))
            .putString(KEY_C_MODE, config.cButtonMode.storage)
            .putString(KEY_PAK, config.pakMode.storage)
            .putBoolean(KEY_HAPTICS, config.haptics)
            .apply()
    }
}
