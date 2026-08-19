package com.omnicore.emulator.settings

import android.content.Context

/** PlayStation 2 input preferences isolated from PS1/N64 state. */
object PS2InputSettings {
    enum class OverlayPreset(val storage: String, val label: String) {
        CLEAN("clean", "Clear"),
        STANDARD("standard", "Padrão"),
        COMPACT("compact", "Compacto")
    }

    data class ControlPosition(val x: Float, val y: Float)

    data class Config(
        val analogDeadzone: Float,
        val analogSensitivity: Float,
        val precisionAnalog: Boolean,
        val haptics: Boolean,
        val overlayPreset: OverlayPreset,
        val touchOpacity: Float,
        val touchScale: Float,
        val dynamicOpacity: Boolean,
        val showDpad: Boolean,
        val showRightStick: Boolean,
        val showL3R3: Boolean
    )

    private const val PREFS = "ps2_input_settings"
    private const val KEY_DEADZONE = "analog_deadzone"
    private const val KEY_SENSITIVITY = "analog_sensitivity"
    private const val KEY_PRECISION_ANALOG = "precision_analog"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_OVERLAY = "overlay_preset"
    private const val KEY_OPACITY = "touch_opacity"
    private const val KEY_SCALE = "touch_scale"
    private const val KEY_DYNAMIC_OPACITY = "dynamic_opacity"
    private const val KEY_SHOW_DPAD = "show_dpad"
    private const val KEY_SHOW_RIGHT_STICK = "show_right_stick"
    private const val KEY_SHOW_L3_R3 = "show_l3_r3"
    private const val POS_PREFIX = "layout_pos_"
    private const val CONTROL_SCALE_PREFIX = "layout_scale_"

    fun resolve(context: Context): Config {
        val prefs = prefs(context)
        return Config(
            analogDeadzone = prefs.getFloat(KEY_DEADZONE, 0.10f).coerceIn(0.03f, 0.30f),
            analogSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.0f).coerceIn(0.70f, 1.30f),
            precisionAnalog = prefs.getBoolean(KEY_PRECISION_ANALOG, true),
            haptics = prefs.getBoolean(KEY_HAPTICS, false),
            overlayPreset = OverlayPreset.entries.firstOrNull {
                it.storage == prefs.getString(KEY_OVERLAY, OverlayPreset.CLEAN.storage)
            } ?: OverlayPreset.CLEAN,
            touchOpacity = prefs.getFloat(KEY_OPACITY, 0.62f).coerceIn(0.22f, 1f),
            touchScale = prefs.getFloat(KEY_SCALE, 0.94f).coerceIn(0.70f, 1.30f),
            dynamicOpacity = prefs.getBoolean(KEY_DYNAMIC_OPACITY, true),
            showDpad = prefs.getBoolean(KEY_SHOW_DPAD, true),
            showRightStick = prefs.getBoolean(KEY_SHOW_RIGHT_STICK, true),
            showL3R3 = prefs.getBoolean(KEY_SHOW_L3_R3, true)
        )
    }

    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putFloat(KEY_DEADZONE, config.analogDeadzone.coerceIn(0.03f, 0.30f))
            .putFloat(KEY_SENSITIVITY, config.analogSensitivity.coerceIn(0.70f, 1.30f))
            .putBoolean(KEY_PRECISION_ANALOG, config.precisionAnalog)
            .putBoolean(KEY_HAPTICS, config.haptics)
            .putString(KEY_OVERLAY, config.overlayPreset.storage)
            .putFloat(KEY_OPACITY, config.touchOpacity.coerceIn(0.22f, 1f))
            .putFloat(KEY_SCALE, config.touchScale.coerceIn(0.70f, 1.30f))
            .putBoolean(KEY_DYNAMIC_OPACITY, config.dynamicOpacity)
            .putBoolean(KEY_SHOW_DPAD, config.showDpad)
            .putBoolean(KEY_SHOW_RIGHT_STICK, config.showRightStick)
            .putBoolean(KEY_SHOW_L3_R3, config.showL3R3)
            .apply()
    }

    fun resolveControlPosition(
        context: Context,
        key: String,
        defaultX: Float,
        defaultY: Float
    ): ControlPosition {
        val storage = prefs(context)
        return ControlPosition(
            storage.getFloat("${POS_PREFIX}${key}_x", defaultX).coerceIn(0.03f, 0.97f),
            storage.getFloat("${POS_PREFIX}${key}_y", defaultY).coerceIn(0.04f, 0.96f)
        )
    }

    fun saveControlPosition(context: Context, key: String, x: Float, y: Float) {
        prefs(context).edit()
            .putFloat("${POS_PREFIX}${key}_x", x.coerceIn(0.03f, 0.97f))
            .putFloat("${POS_PREFIX}${key}_y", y.coerceIn(0.04f, 0.96f))
            .apply()
    }

    fun resolveControlScale(context: Context, key: String): Float =
        prefs(context).getFloat("$CONTROL_SCALE_PREFIX$key", 1f).coerceIn(0.58f, 1.55f)

    fun saveControlScale(context: Context, key: String, scale: Float) {
        prefs(context).edit()
            .putFloat("$CONTROL_SCALE_PREFIX$key", scale.coerceIn(0.58f, 1.55f))
            .apply()
    }

    fun resetTouchLayout(context: Context) {
        val storage = prefs(context)
        val keys = storage.all.keys.filter {
            it.startsWith(POS_PREFIX) || it.startsWith(CONTROL_SCALE_PREFIX)
        }
        if (keys.isEmpty()) return
        storage.edit().apply { keys.forEach(::remove) }.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
