package com.omnicore.emulator.settings

import android.content.Context

object InputSettings {
    enum class AnalogMode(val storage: String, val label: String, val subtitle: String) {
        SMART("smart", "Inteligente", "Analógico nativo + D-pad para jogos antigos"),
        NATIVE("native", "Nativo", "Envia somente eixos analógicos DualShock"),
        DPAD("dpad", "D-pad", "Stick touch funciona como direcional digital")
    }

    data class Config(
        val analogMode: AnalogMode,
        val touchOpacity: Float,
        val touchScale: Float,
        val haptics: Boolean
    )

    private const val PREFS = "input_settings"
    private const val KEY_ANALOG_MODE = "analog_mode"
    private const val KEY_TOUCH_OPACITY = "touch_opacity"
    private const val KEY_TOUCH_SCALE = "touch_scale"
    private const val KEY_HAPTICS = "haptics"

    fun resolve(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modeRaw = prefs.getString(KEY_ANALOG_MODE, AnalogMode.SMART.storage)
        return Config(
            analogMode = AnalogMode.entries.firstOrNull { it.storage == modeRaw } ?: AnalogMode.SMART,
            touchOpacity = prefs.getFloat(KEY_TOUCH_OPACITY, 0.82f).coerceIn(0.35f, 1f),
            touchScale = prefs.getFloat(KEY_TOUCH_SCALE, 1f).coerceIn(0.80f, 1.20f),
            haptics = prefs.getBoolean(KEY_HAPTICS, false)
        )
    }

    fun saveAnalogMode(context: Context, mode: AnalogMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ANALOG_MODE, mode.storage).apply()
    }

    fun saveTouchOpacity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TOUCH_OPACITY, value.coerceIn(0.35f, 1f)).apply()
    }

    fun saveTouchScale(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TOUCH_SCALE, value.coerceIn(0.80f, 1.20f)).apply()
    }

    fun saveHaptics(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAPTICS, enabled).apply()
    }
}
