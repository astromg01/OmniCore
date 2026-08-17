from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.strip() + "\n", encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"missing migration anchor in {path}: {old[:120]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


write("app/src/main/java/com/omnicore/emulator/settings/InputSettings.kt", r'''
package com.omnicore.emulator.settings

import android.content.Context

object InputSettings {
    enum class AnalogMode(val storage: String, val label: String, val subtitle: String) {
        SMART("smart", "Inteligente", "Analógico nativo + D-pad para jogos antigos"),
        NATIVE("native", "Nativo", "Envia somente eixos analógicos DualShock"),
        DPAD("dpad", "D-pad", "Stick touch funciona como direcional digital")
    }

    enum class OverlayPreset(val storage: String, val label: String, val subtitle: String) {
        CLEAN("clean", "Limpo", "Pouca informação na tela e controles discretos"),
        COMPACT("compact", "Compacto", "Controles menores e próximos das bordas"),
        STANDARD("standard", "Padrão", "Layout clássico com labels visíveis"),
        LEFT("left", "Mão esquerda", "Ações aproximadas do lado esquerdo"),
        RIGHT("right", "Mão direita", "Analógico aproximado do lado direito"),
        TABLET("tablet", "Tablet", "Controles espalhados para telas maiores")
    }

    data class Config(
        val analogMode: AnalogMode,
        val touchOpacity: Float,
        val touchScale: Float,
        val haptics: Boolean,
        val showDpad: Boolean,
        val overlayPreset: OverlayPreset,
        val cleanOverlay: Boolean,
        val dynamicOpacity: Boolean,
        val showLabels: Boolean,
        val showShoulders: Boolean,
        val showStartSelect: Boolean,
        val showPerformanceHud: Boolean,
        val controlsVisible: Boolean
    )

    data class ControlPosition(val x: Float, val y: Float)

    private const val PREFS = "input_settings"
    private const val KEY_ANALOG_MODE = "analog_mode"
    private const val KEY_TOUCH_OPACITY = "touch_opacity"
    private const val KEY_TOUCH_SCALE = "touch_scale"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_SHOW_DPAD = "show_dpad"
    private const val KEY_OVERLAY_PRESET = "overlay_preset"
    private const val KEY_CLEAN_OVERLAY = "clean_overlay"
    private const val KEY_DYNAMIC_OPACITY = "dynamic_opacity"
    private const val KEY_SHOW_LABELS = "show_labels"
    private const val KEY_SHOW_SHOULDERS = "show_shoulders"
    private const val KEY_SHOW_START_SELECT = "show_start_select"
    private const val KEY_SHOW_PERFORMANCE_HUD = "show_performance_hud"
    private const val KEY_CONTROLS_VISIBLE = "controls_visible"
    private const val POSITION_PREFIX = "control_position_"
    private const val GAME_PREFIX = "game_"

    fun resolve(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modeRaw = prefs.getString(KEY_ANALOG_MODE, AnalogMode.SMART.storage)
        val analogMode = AnalogMode.entries.firstOrNull { it.storage == modeRaw } ?: AnalogMode.SMART
        val presetRaw = prefs.getString(KEY_OVERLAY_PRESET, OverlayPreset.CLEAN.storage)
        val preset = OverlayPreset.entries.firstOrNull { it.storage == presetRaw } ?: OverlayPreset.CLEAN
        val defaultShowDpad = analogMode == AnalogMode.DPAD
        return Config(
            analogMode = analogMode,
            touchOpacity = prefs.getFloat(KEY_TOUCH_OPACITY, 0.78f).coerceIn(0.35f, 1f),
            touchScale = prefs.getFloat(KEY_TOUCH_SCALE, 1f).coerceIn(0.80f, 1.20f),
            haptics = prefs.getBoolean(KEY_HAPTICS, false),
            showDpad = if (prefs.contains(KEY_SHOW_DPAD)) prefs.getBoolean(KEY_SHOW_DPAD, defaultShowDpad) else defaultShowDpad,
            overlayPreset = preset,
            cleanOverlay = prefs.getBoolean(KEY_CLEAN_OVERLAY, preset != OverlayPreset.STANDARD),
            dynamicOpacity = prefs.getBoolean(KEY_DYNAMIC_OPACITY, true),
            showLabels = prefs.getBoolean(KEY_SHOW_LABELS, preset == OverlayPreset.STANDARD),
            showShoulders = prefs.getBoolean(KEY_SHOW_SHOULDERS, true),
            showStartSelect = prefs.getBoolean(KEY_SHOW_START_SELECT, true),
            showPerformanceHud = prefs.getBoolean(KEY_SHOW_PERFORMANCE_HUD, false),
            controlsVisible = prefs.getBoolean(KEY_CONTROLS_VISIBLE, true)
        )
    }

    fun resolveForGame(context: Context, gameKey: String): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = resolve(context)
        val prefix = gamePrefix(gameKey)
        if (!prefs.all.keys.any { it.startsWith(prefix) }) return base

        fun string(key: String, fallback: String): String = prefs.getString(prefix + key, fallback) ?: fallback
        fun bool(key: String, fallback: Boolean): Boolean = if (prefs.contains(prefix + key)) prefs.getBoolean(prefix + key, fallback) else fallback
        fun number(key: String, fallback: Float): Float = if (prefs.contains(prefix + key)) prefs.getFloat(prefix + key, fallback) else fallback

        val analog = AnalogMode.entries.firstOrNull { it.storage == string(KEY_ANALOG_MODE, base.analogMode.storage) } ?: base.analogMode
        val preset = OverlayPreset.entries.firstOrNull { it.storage == string(KEY_OVERLAY_PRESET, base.overlayPreset.storage) } ?: base.overlayPreset
        return base.copy(
            analogMode = analog,
            touchOpacity = number(KEY_TOUCH_OPACITY, base.touchOpacity).coerceIn(0.35f, 1f),
            touchScale = number(KEY_TOUCH_SCALE, base.touchScale).coerceIn(0.80f, 1.20f),
            haptics = bool(KEY_HAPTICS, base.haptics),
            showDpad = bool(KEY_SHOW_DPAD, base.showDpad),
            overlayPreset = preset,
            cleanOverlay = bool(KEY_CLEAN_OVERLAY, base.cleanOverlay),
            dynamicOpacity = bool(KEY_DYNAMIC_OPACITY, base.dynamicOpacity),
            showLabels = bool(KEY_SHOW_LABELS, base.showLabels),
            showShoulders = bool(KEY_SHOW_SHOULDERS, base.showShoulders),
            showStartSelect = bool(KEY_SHOW_START_SELECT, base.showStartSelect),
            showPerformanceHud = bool(KEY_SHOW_PERFORMANCE_HUD, base.showPerformanceHud),
            controlsVisible = bool(KEY_CONTROLS_VISIBLE, base.controlsVisible)
        )
    }

    fun saveGameConfig(context: Context, gameKey: String, config: Config) {
        val prefix = gamePrefix(gameKey)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(prefix + KEY_ANALOG_MODE, config.analogMode.storage)
            .putFloat(prefix + KEY_TOUCH_OPACITY, config.touchOpacity.coerceIn(0.35f, 1f))
            .putFloat(prefix + KEY_TOUCH_SCALE, config.touchScale.coerceIn(0.80f, 1.20f))
            .putBoolean(prefix + KEY_HAPTICS, config.haptics)
            .putBoolean(prefix + KEY_SHOW_DPAD, config.showDpad)
            .putString(prefix + KEY_OVERLAY_PRESET, config.overlayPreset.storage)
            .putBoolean(prefix + KEY_CLEAN_OVERLAY, config.cleanOverlay)
            .putBoolean(prefix + KEY_DYNAMIC_OPACITY, config.dynamicOpacity)
            .putBoolean(prefix + KEY_SHOW_LABELS, config.showLabels)
            .putBoolean(prefix + KEY_SHOW_SHOULDERS, config.showShoulders)
            .putBoolean(prefix + KEY_SHOW_START_SELECT, config.showStartSelect)
            .putBoolean(prefix + KEY_SHOW_PERFORMANCE_HUD, config.showPerformanceHud)
            .putBoolean(prefix + KEY_CONTROLS_VISIBLE, config.controlsVisible)
            .apply()
    }

    fun clearGameProfile(context: Context, gameKey: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = gamePrefix(gameKey)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    fun saveAnalogMode(context: Context, mode: AnalogMode) = edit(context).putString(KEY_ANALOG_MODE, mode.storage).apply()
    fun saveTouchOpacity(context: Context, value: Float) = edit(context).putFloat(KEY_TOUCH_OPACITY, value.coerceIn(0.35f, 1f)).apply()
    fun saveTouchScale(context: Context, value: Float) = edit(context).putFloat(KEY_TOUCH_SCALE, value.coerceIn(0.80f, 1.20f)).apply()
    fun saveHaptics(context: Context, enabled: Boolean) = edit(context).putBoolean(KEY_HAPTICS, enabled).apply()
    fun saveShowDpad(context: Context, enabled: Boolean) = edit(context).putBoolean(KEY_SHOW_DPAD, enabled).apply()
    fun saveOverlayPreset(context: Context, preset: OverlayPreset) = edit(context).putString(KEY_OVERLAY_PRESET, preset.storage).apply()
    fun saveCleanOverlay(context: Context, enabled: Boolean) = edit(context).putBoolean(KEY_CLEAN_OVERLAY, enabled).apply()
    fun saveDynamicOpacity(context: Context, enabled: Boolean) = edit(context).putBoolean(KEY_DYNAMIC_OPACITY, enabled).apply()
    fun saveShowLabels(context: Context, enabled: Boolean) = edit(context).putBoolean(KEY_SHOW_LABELS, enabled).apply()
    fun saveShowShoulders(context: Context, enabled: Boolean) = edit(context).putBoolean(KEY_SHOW_SHOULDERS, enabled).apply()
    fun saveShowStartSelect(context: Context, enabled: Boolean) = edit(context).putBoolean(KEY_SHOW_START_SELECT, enabled).apply()
    fun saveShowPerformanceHud(context: Context, enabled: Boolean) = edit(context).putBoolean(KEY_SHOW_PERFORMANCE_HUD, enabled).apply()
    fun saveControlsVisible(context: Context, enabled: Boolean) = edit(context).putBoolean(KEY_CONTROLS_VISIBLE, enabled).apply()

    fun resolveControlPosition(
        context: Context,
        key: String,
        defaultX: Float,
        defaultY: Float,
        gameKey: String? = null
    ): ControlPosition {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val globalX = "${POSITION_PREFIX}${key}_x"
        val globalY = "${POSITION_PREFIX}${key}_y"
        val gamePrefix = gameKey?.let(::gamePrefix)
        val xKey = gamePrefix?.let { it + globalX }
        val yKey = gamePrefix?.let { it + globalY }
        val x = when {
            xKey != null && prefs.contains(xKey) -> prefs.getFloat(xKey, defaultX)
            prefs.contains(globalX) -> prefs.getFloat(globalX, defaultX)
            else -> defaultX
        }.coerceIn(0.04f, 0.96f)
        val y = when {
            yKey != null && prefs.contains(yKey) -> prefs.getFloat(yKey, defaultY)
            prefs.contains(globalY) -> prefs.getFloat(globalY, defaultY)
            else -> defaultY
        }.coerceIn(0.06f, 0.95f)
        return ControlPosition(x, y)
    }

    fun saveControlPosition(context: Context, key: String, x: Float, y: Float, gameKey: String? = null) {
        val prefix = gameKey?.let(::gamePrefix).orEmpty()
        edit(context)
            .putFloat("$prefix${POSITION_PREFIX}${key}_x", x.coerceIn(0.04f, 0.96f))
            .putFloat("$prefix${POSITION_PREFIX}${key}_y", y.coerceIn(0.06f, 0.95f))
            .apply()
    }

    fun resetControlPositions(context: Context, gameKey: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = gameKey?.let { gamePrefix(it) + POSITION_PREFIX } ?: POSITION_PREFIX
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun edit(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()

    private fun gamePrefix(value: String): String = GAME_PREFIX + safeGameKey(value) + "_"

    private fun safeGameKey(value: String): String = buildString(value.length) {
        value.forEach { char -> append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_') }
    }.ifBlank { "game" }
}
''')

write("app/src/main/java/com/omnicore/emulator/cheats/CheatStore.kt", r'''
package com.omnicore.emulator.cheats

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object CheatStore {
    data class Cheat(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val code: String,
        val enabled: Boolean = true
    )

    private const val PREFS = "omnicore_cheats"
    private const val MAX_CHEATS = 128
    private const val MAX_CODE_CHARS = 8192

    fun load(context: Context, gameKey: String): List<Cheat> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(safeGameKey(gameKey), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_CHEATS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val code = item.optString("code").trim().take(MAX_CODE_CHARS)
                    if (code.isBlank()) continue
                    add(
                        Cheat(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name").ifBlank { "Cheat ${index + 1}" }.take(80),
                            code = code,
                            enabled = item.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, gameKey: String, cheats: List<Cheat>) {
        val array = JSONArray()
        cheats.take(MAX_CHEATS).forEach { cheat ->
            val code = cheat.code.trim().take(MAX_CODE_CHARS)
            if (code.isBlank()) return@forEach
            array.put(
                JSONObject()
                    .put("id", cheat.id)
                    .put("name", cheat.name.trim().ifBlank { "Cheat" }.take(80))
                    .put("code", code)
                    .put("enabled", cheat.enabled)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(safeGameKey(gameKey), array.toString())
            .apply()
    }

    private fun safeGameKey(value: String): String = buildString(value.length) {
        value.forEach { char -> append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_') }
    }.ifBlank { "game" }
}
''')

write("app/src/main/java/com/omnicore/emulator/core/nativebridge/NativeBridge.kt", r'''
package com.omnicore.emulator.core.nativebridge

import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object NativeBridge {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("omnicore_runtime")
        true
    }.getOrDefault(false)

    private val coreAvailabilityLock = Any()
    @Volatile private var ps1CoreAvailable: Boolean? = null

    private val stateLoadGeneration = AtomicInteger(0)
    private val stateLoadExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OmniCore-StatePrefetch").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }
    @Volatile private var activeGameKey: String = ""
    @Volatile private var activeStateDir: String = ""

    fun isLoaded(): Boolean = loaded

    fun runtimeVersion(): String =
        if (loaded) runCatching { nativeRuntimeVersion() }.getOrDefault("native-runtime-error")
        else "native-runtime-unavailable"

    fun hasPs1Core(): Boolean {
        if (!loaded) return false
        ps1CoreAvailable?.let { return it }
        return synchronized(coreAvailabilityLock) {
            ps1CoreAvailable ?: runCatching { nativeHasPs1Core() }
                .getOrDefault(false)
                .also { ps1CoreAvailable = it }
        }
    }

    fun startPs1(
        gamePath: String,
        gameKey: String,
        systemDir: String,
        saveDir: String,
        stateDir: String,
        surface: Surface,
        performancePolicy: Int,
        audioBufferBursts: Int,
        tryExclusiveAudio: Boolean,
        preferPowerEfficiency: Boolean,
        aggressiveFramePacing: Boolean,
        coreOptions: String,
        dualShock: Boolean
    ): Boolean {
        if (!loaded) return false
        val started = runCatching {
            nativeStartPs1(
                gamePath, gameKey, systemDir, saveDir, stateDir, surface,
                performancePolicy, audioBufferBursts, tryExclusiveAudio,
                preferPowerEfficiency, aggressiveFramePacing, coreOptions, dualShock
            )
        }.getOrDefault(false)
        if (started) {
            activeGameKey = gameKey
            activeStateDir = stateDir
            stateLoadGeneration.incrementAndGet()
        }
        return started
    }

    fun stop() {
        stateLoadGeneration.incrementAndGet()
        activeGameKey = ""
        activeStateDir = ""
        if (loaded) runCatching { nativeStop() }
    }

    fun isRunning(): Boolean = loaded && runCatching { nativeIsRunning() }.getOrDefault(false)

    fun setButton(id: Int, pressed: Boolean) {
        if (loaded) runCatching { nativeSetButton(id, pressed) }
    }

    fun setAnalog(stick: Int, x: Float, y: Float) {
        if (!loaded) return
        val sx = (x.coerceIn(-1f, 1f) * 32767f).toInt()
        val sy = (y.coerceIn(-1f, 1f) * 32767f).toInt()
        runCatching { nativeSetAnalog(stick, sx, sy) }
    }

    fun saveState(slot: Int = 0) {
        if (loaded) runCatching { nativeSaveState(slot.coerceIn(0, 9)) }
    }

    fun loadState(slot: Int = 0) {
        if (!loaded) return
        val safeSlot = slot.coerceIn(0, 9)
        val stateDir = activeStateDir
        val gameKey = activeGameKey
        if (stateDir.isBlank() || gameKey.isBlank()) {
            runCatching { nativeLoadState(safeSlot) }
            return
        }

        val generation = stateLoadGeneration.incrementAndGet()
        stateLoadExecutor.execute {
            if (generation != stateLoadGeneration.get()) return@execute
            warmStateFile(File(stateDir, "${safeGameKey(gameKey)}.state$safeSlot"))
            if (generation == stateLoadGeneration.get()) runCatching { nativeLoadState(safeSlot) }
        }
    }

    fun resetCheats() {
        if (loaded) runCatching { nativeResetCheats() }
    }

    fun setCheat(index: Int, enabled: Boolean, code: String) {
        if (!loaded || code.isBlank()) return
        runCatching { nativeSetCheat(index.coerceIn(0, 127), enabled, code.take(8192)) }
    }

    private fun warmStateFile(file: File) {
        if (!file.isFile || file.length() <= 0L) return
        runCatching {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (input.read(buffer) >= 0) Unit
            }
        }
    }

    private fun safeGameKey(value: String): String = buildString(value.length) {
        value.forEach { char -> append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_') }
    }.ifBlank { "game" }

    fun lastMessage(): String =
        if (loaded) runCatching { nativeLastMessage() }.getOrDefault("Runtime indisponível")
        else "Runtime indisponível"

    fun updatePerformancePolicy(
        performancePolicy: Int,
        audioBufferBursts: Int,
        tryExclusiveAudio: Boolean,
        preferPowerEfficiency: Boolean,
        aggressiveFramePacing: Boolean
    ) {
        if (loaded) runCatching {
            nativeUpdatePerformancePolicy(
                performancePolicy, audioBufferBursts, tryExclusiveAudio,
                preferPowerEfficiency, aggressiveFramePacing
            )
        }
    }

    private external fun nativeRuntimeVersion(): String
    private external fun nativeHasPs1Core(): Boolean
    private external fun nativeStartPs1(
        gamePath: String,
        gameKey: String,
        systemDir: String,
        saveDir: String,
        stateDir: String,
        surface: Surface,
        performancePolicy: Int,
        audioBufferBursts: Int,
        tryExclusiveAudio: Boolean,
        preferPowerEfficiency: Boolean,
        aggressiveFramePacing: Boolean,
        coreOptions: String,
        dualShock: Boolean
    ): Boolean
    private external fun nativeStop()
    private external fun nativeUpdatePerformancePolicy(
        performancePolicy: Int,
        audioBufferBursts: Int,
        tryExclusiveAudio: Boolean,
        preferPowerEfficiency: Boolean,
        aggressiveFramePacing: Boolean
    )
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetButton(id: Int, pressed: Boolean)
    private external fun nativeSetAnalog(stick: Int, x: Int, y: Int)
    private external fun nativeSaveState(slot: Int)
    private external fun nativeLoadState(slot: Int)
    private external fun nativeResetCheats()
    private external fun nativeSetCheat(index: Int, enabled: Boolean, code: String)
    private external fun nativeLastMessage(): String
}
''')

write("app/src/main/cpp/libretro_host.h", r'''
#pragma once

#include <android/native_window.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

struct RuntimePerformanceConfig {
    // 0 = sustained/efficiency, 1 = balanced, 2 = low latency/performance.
    int policy = 1;
    int audioBufferBursts = 3;
    bool tryExclusiveAudio = false;
    bool preferPowerEfficiency = false;
    bool aggressiveFramePacing = false;
};

class LibretroSession {
public:
    LibretroSession(
        std::string coreLibrary,
        std::string gamePath,
        std::string gameKey,
        std::string systemDir,
        std::string saveDir,
        std::string stateDir,
        ANativeWindow* window,
        RuntimePerformanceConfig performance,
        std::string coreOptions,
        bool dualShock
    );
    ~LibretroSession();

    LibretroSession(const LibretroSession&) = delete;
    LibretroSession& operator=(const LibretroSession&) = delete;

    bool start();
    void stop();
    bool running() const;
    void setButton(unsigned id, bool pressed);
    void setAnalog(unsigned stick, std::int16_t x, std::int16_t y);
    void requestSaveState(int slot);
    void requestLoadState(int slot);
    void requestCheatReset();
    void requestCheatSet(unsigned index, bool enabled, std::string code);
    void updatePerformanceConfig(RuntimePerformanceConfig performance);
    std::string status() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

bool probeLibretroCore(const char* libraryName);
''')

write("app/src/main/java/com/omnicore/emulator/emulation/GamepadOverlayView.kt", r'''
package com.omnicore.emulator.emulation

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.omnicore.emulator.cheats.CheatStore
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.settings.InputSettings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

class GamepadOverlayView(context: Context) : View(context) {
    private data class Region(
        val key: String,
        val id: Int,
        val label: String,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val wide: Boolean = false
    )

    private val activity = context as? Activity
    private val gameKey = activity?.intent?.getStringExtra("gameId").orEmpty().ifBlank { "game" }
    private val gameTitle = activity?.intent?.getStringExtra("gameTitle").orEmpty().ifBlank { "PlayStation" }
    private var config = InputSettings.resolveForGame(context, gameKey)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 238, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.45f * resources.displayMetrics.density
    }
    private val analogBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 185, 255)
        style = Paint.Style.FILL
    }
    private val analogRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * resources.displayMetrics.density
    }
    private val analogKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 222, 255)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 22, 24, 42)
        style = Paint.Style.FILL
    }
    private val chromeAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 105, 87, 235)
        style = Paint.Style.FILL
    }

    private var regions: List<Region> = emptyList()
    private var regionPressed: Set<Int> = emptySet()
    private var analogDpadPressed: Set<Int> = emptySet()
    private var committedButtons: Set<Int> = emptySet()
    private var analogCx = 0f
    private var analogCy = 0f
    private var analogRadius = 0f
    private var analogKnobX = 0f
    private var analogKnobY = 0f
    private var analogPointerId = -1
    private var lastAnalogX = Float.NaN
    private var lastAnalogY = Float.NaN

    private var editMode = false
    private var editPointerId = -1
    private var editTargetKey: String? = null
    private var menuVisible = false
    private var activeUntilMs = 0L
    private var gesturePeak = 0
    private var gestureCaptured = false
    private var legacyStatusView: TextView? = null
    private var wasRunning = false

    private val menuHideRunnable = Runnable {
        if (!editMode) {
            menuVisible = false
            scheduleRedraw()
        }
    }
    private val fadeRunnable = Runnable { scheduleRedraw() }
    private val legacyStatusGuard = object : Runnable {
        override fun run() {
            val status = legacyStatusView
            if (status != null) {
                config = InputSettings.resolveForGame(context, gameKey)
                val text = status.text?.toString().orEmpty()
                val important = text.startsWith("PREP") || text.startsWith("BOOT E") ||
                    text.startsWith("RUNTIME E") || text.startsWith("RUNTIME W")
                val telemetry = text.startsWith("RUN OK") || text.startsWith("BOOT 6/6")
                status.alpha = when {
                    important -> 1f
                    telemetry && config.showPerformanceHud -> 1f
                    telemetry -> 0f
                    else -> status.alpha
                }
            }
            postDelayed(this, 500)
        }
    }
    private val cheatApplyGuard = object : Runnable {
        override fun run() {
            val running = NativeBridge.isRunning()
            if (running && !wasRunning) applyStoredCheats()
            wasRunning = running
            postDelayed(this, 500)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { suppressLegacyChrome() }
        post(legacyStatusGuard)
        post(cheatApplyGuard)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(menuHideRunnable)
        removeCallbacks(fadeRunnable)
        removeCallbacks(legacyStatusGuard)
        removeCallbacks(cheatApplyGuard)
        super.onDetachedFromWindow()
    }

    private fun suppressLegacyChrome() {
        val root = parent as? ViewGroup ?: return
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child === this) continue
            when (child) {
                is Button -> child.visibility = GONE
                is TextView -> {
                    val params = child.layoutParams as? FrameLayout.LayoutParams
                    val gravity = params?.gravity ?: 0
                    if ((gravity and Gravity.BOTTOM) == Gravity.BOTTOM) child.visibility = GONE
                    else if ((gravity and Gravity.TOP) == Gravity.TOP) legacyStatusView = child
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout(w, h)
    }

    private fun rebuildLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        config = InputSettings.resolveForGame(context, gameKey)
        val base = min(w, h).toFloat()
        val presetScale = when (config.overlayPreset) {
            InputSettings.OverlayPreset.CLEAN -> 0.92f
            InputSettings.OverlayPreset.COMPACT -> 0.82f
            InputSettings.OverlayPreset.STANDARD -> 1f
            InputSettings.OverlayPreset.LEFT, InputSettings.OverlayPreset.RIGHT -> 0.90f
            InputSettings.OverlayPreset.TABLET -> 1.05f
        }
        val scale = config.touchScale * presetScale
        val r = base * 0.060f * scale
        val small = base * 0.046f * scale
        val dpadR = base * 0.047f * scale
        val defaults = presetPositions(config.overlayPreset)

        fun xy(key: String, fallbackX: Float, fallbackY: Float): Pair<Float, Float> =
            defaults[key] ?: (fallbackX to fallbackY)

        val analogDefault = xy("analog", 0.145f, 0.73f)
        val analog = InputSettings.resolveControlPosition(context, "analog", analogDefault.first, analogDefault.second, gameKey)
        analogCx = w * analog.x
        analogCy = h * analog.y
        analogRadius = base * 0.105f * scale
        if (analogPointerId == -1) {
            analogKnobX = analogCx
            analogKnobY = analogCy
        }

        fun make(key: String, id: Int, label: String, fx: Float, fy: Float, radius: Float, wide: Boolean = false): Region {
            val d = xy(key, fx, fy)
            val saved = InputSettings.resolveControlPosition(context, key, d.first, d.second, gameKey)
            return Region(key, id, label, w * saved.x, h * saved.y, radius, wide)
        }

        regions = listOf(
            make("dpad_up", 4, "▲", 0.315f, 0.638f, dpadR),
            make("dpad_down", 5, "▼", 0.315f, 0.802f, dpadR),
            make("dpad_left", 6, "◀", 0.233f, 0.72f, dpadR),
            make("dpad_right", 7, "▶", 0.397f, 0.72f, dpadR),
            make("triangle", 9, "△", 0.845f, 0.55f, r),
            make("cross", 0, "×", 0.845f, 0.83f, r),
            make("square", 1, "□", 0.775f, 0.69f, r),
            make("circle", 8, "○", 0.915f, 0.69f, r),
            make("select", 2, "SELECT", 0.465f, 0.84f, small, wide = true),
            make("start", 3, "START", 0.565f, 0.84f, small, wide = true),
            make("l1", 10, "L1", 0.095f, 0.13f, small, wide = true),
            make("l2", 12, "L2", 0.215f, 0.13f, small, wide = true),
            make("r2", 13, "R2", 0.785f, 0.13f, small, wide = true),
            make("r1", 11, "R1", 0.905f, 0.13f, small, wide = true)
        )
    }

    private fun presetPositions(preset: InputSettings.OverlayPreset): Map<String, Pair<Float, Float>> = when (preset) {
        InputSettings.OverlayPreset.STANDARD -> emptyMap()
        InputSettings.OverlayPreset.CLEAN -> mapOf(
            "analog" to (0.115f to 0.77f),
            "dpad_up" to (0.275f to 0.65f), "dpad_down" to (0.275f to 0.81f),
            "dpad_left" to (0.20f to 0.73f), "dpad_right" to (0.35f to 0.73f),
            "triangle" to (0.875f to 0.57f), "cross" to (0.875f to 0.82f),
            "square" to (0.81f to 0.695f), "circle" to (0.94f to 0.695f),
            "select" to (0.47f to 0.91f), "start" to (0.55f to 0.91f),
            "l1" to (0.07f to 0.10f), "l2" to (0.18f to 0.10f),
            "r2" to (0.82f to 0.10f), "r1" to (0.93f to 0.10f)
        )
        InputSettings.OverlayPreset.COMPACT -> mapOf(
            "analog" to (0.10f to 0.80f),
            "triangle" to (0.90f to 0.62f), "cross" to (0.90f to 0.84f),
            "square" to (0.84f to 0.73f), "circle" to (0.96f to 0.73f),
            "select" to (0.48f to 0.92f), "start" to (0.56f to 0.92f),
            "l1" to (0.06f to 0.09f), "l2" to (0.16f to 0.09f),
            "r2" to (0.84f to 0.09f), "r1" to (0.94f to 0.09f)
        )
        InputSettings.OverlayPreset.LEFT -> mapOf(
            "analog" to (0.10f to 0.72f),
            "triangle" to (0.67f to 0.58f), "cross" to (0.67f to 0.82f),
            "square" to (0.61f to 0.70f), "circle" to (0.73f to 0.70f),
            "select" to (0.43f to 0.91f), "start" to (0.51f to 0.91f)
        )
        InputSettings.OverlayPreset.RIGHT -> mapOf(
            "analog" to (0.31f to 0.76f),
            "triangle" to (0.90f to 0.58f), "cross" to (0.90f to 0.82f),
            "square" to (0.84f to 0.70f), "circle" to (0.96f to 0.70f),
            "select" to (0.52f to 0.91f), "start" to (0.60f to 0.91f)
        )
        InputSettings.OverlayPreset.TABLET -> mapOf(
            "analog" to (0.08f to 0.79f),
            "triangle" to (0.92f to 0.58f), "cross" to (0.92f to 0.84f),
            "square" to (0.86f to 0.71f), "circle" to (0.98f to 0.71f),
            "select" to (0.47f to 0.92f), "start" to (0.54f to 0.92f),
            "l1" to (0.05f to 0.09f), "l2" to (0.16f to 0.09f),
            "r2" to (0.84f to 0.09f), "r1" to (0.95f to 0.09f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        config = InputSettings.resolveForGame(context, gameKey)
        if (config.controlsVisible || editMode) drawControls(canvas)
        if (editMode) drawEditorUi(canvas)
        drawMenuButton(canvas)
        if (menuVisible && !editMode) drawQuickMenu(canvas)
    }

    private fun drawControls(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val idleFactor = if (config.cleanOverlay && config.dynamicOpacity && now > activeUntilMs && committedButtons.isEmpty()) 0.46f else 1f
        val opacity = (config.touchOpacity * idleFactor).coerceIn(0.12f, 1f)
        analogBasePaint.alpha = scaledAlpha(58, opacity)
        analogRingPaint.alpha = scaledAlpha(130, opacity)
        analogKnobPaint.alpha = scaledAlpha(if (analogPointerId == -1) 120 else 190, opacity)
        strokePaint.alpha = scaledAlpha(135, opacity)
        textPaint.alpha = scaledAlpha(240, opacity)

        canvas.drawCircle(analogCx, analogCy, analogRadius, analogBasePaint)
        canvas.drawCircle(analogCx, analogCy, analogRadius, analogRingPaint)
        canvas.drawCircle(analogKnobX, analogKnobY, analogRadius * 0.42f, analogKnobPaint)
        if (config.showLabels || editMode || analogPointerId != -1) {
            textPaint.textSize = min(width, height) * 0.022f * config.touchScale
            canvas.drawText("L", analogCx, analogCy - analogRadius - textPaint.textSize * 0.25f, textPaint)
        }

        textPaint.textSize = min(width, height) * 0.029f * config.touchScale
        visibleRegions().forEach { region ->
            val active = region.id in committedButtons
            val alpha = scaledAlpha(if (active) 165 else 54, if (active) config.touchOpacity else opacity)
            fillPaint.color = Color.argb(alpha, if (active) 218 else 235, if (active) 214 else 238, 255)
            if (region.wide) {
                val halfW = region.radius * 1.55f
                val halfH = region.radius * 0.68f
                val rect = RectF(region.cx - halfW, region.cy - halfH, region.cx + halfW, region.cy + halfH)
                canvas.drawRoundRect(rect, halfH, halfH, fillPaint)
                canvas.drawRoundRect(rect, halfH, halfH, strokePaint)
            } else {
                canvas.drawCircle(region.cx, region.cy, region.radius, fillPaint)
                canvas.drawCircle(region.cx, region.cy, region.radius, strokePaint)
            }
            if (config.showLabels || editMode || active) {
                val baseline = region.cy - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(region.label, region.cx, baseline, textPaint)
            }
        }
        textPaint.alpha = 255
    }

    private fun drawMenuButton(canvas: Canvas) {
        val rect = menuButtonRect()
        val paint = if (menuVisible) chromeAccentPaint else chromePaint
        paint.alpha = if (menuVisible) 238 else 145
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        textPaint.alpha = 220
        textPaint.textSize = min(width, height) * 0.032f
        canvas.drawText("⋮", rect.centerX(), textBaseline(rect.centerY()), textPaint)
        textPaint.alpha = 255
    }

    private fun drawQuickMenu(canvas: Canvas) {
        val panel = quickMenuPanelRect()
        chromePaint.alpha = 238
        canvas.drawRoundRect(panel, min(width, height) * 0.025f, min(width, height) * 0.025f, chromePaint)
        val labels = arrayOf("SALVAR", "CARREGAR", "STATUS", "EDITAR", "VISUAL", "LAYOUT", "CHEATS", "SAIR")
        textPaint.textSize = min(width, height) * 0.018f
        labels.forEachIndexed { index, label ->
            val rect = quickMenuItemRect(index)
            fillPaint.color = Color.argb(if (index == 6) 130 else 80, 120, 105, 225)
            canvas.drawRoundRect(rect, rect.height() * 0.24f, rect.height() * 0.24f, fillPaint)
            canvas.drawText(label, rect.centerX(), textBaseline(rect.centerY()), textPaint)
        }
        textPaint.textSize = min(width, height) * 0.014f
        canvas.drawText("3 dedos: menu • 4 dedos: overlay", panel.centerX(), panel.bottom - min(width, height) * 0.018f, textPaint)
    }

    private fun drawEditorUi(canvas: Canvas) {
        textPaint.textSize = min(width, height) * 0.016f
        for (index in 0..2) {
            val rect = editorButtonRect(index)
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, if (index == 0) chromeAccentPaint else chromePaint)
            val label = when (index) {
                0 -> "CONCLUIR"
                1 -> if (config.showDpad) "SETAS ON" else "SETAS OFF"
                else -> "RESTAURAR"
            }
            canvas.drawText(label, rect.centerX(), textBaseline(rect.centerY()), textPaint)
        }
        textPaint.textSize = min(width, height) * 0.017f
        canvas.drawText("Arraste qualquer controle", width * 0.5f, height * 0.18f, textPaint)
    }

    private fun menuButtonRect(): RectF {
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val w = base * 0.075f
        val h = base * 0.060f
        return RectF(width - w - base * 0.018f, base * 0.018f, width - base * 0.018f, base * 0.018f + h)
    }

    private fun quickMenuPanelRect(): RectF {
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val panelW = base * 0.50f
        val panelH = base * 0.39f
        val right = width - base * 0.018f
        val top = base * 0.09f
        return RectF(right - panelW, top, right, top + panelH)
    }

    private fun quickMenuItemRect(index: Int): RectF {
        val panel = quickMenuPanelRect()
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val gap = base * 0.012f
        val footer = base * 0.045f
        val contentH = panel.height() - footer - gap * 5
        val itemW = (panel.width() - gap * 3) / 2f
        val itemH = contentH / 4f
        val column = index % 2
        val row = index / 2
        val left = panel.left + gap + column * (itemW + gap)
        val top = panel.top + gap + row * (itemH + gap)
        return RectF(left, top, left + itemW, top + itemH)
    }

    private fun editorButtonRect(index: Int): RectF {
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val buttonW = if (index == 0) base * 0.25f else base * 0.21f
        val buttonH = base * 0.062f
        val centerX = when (index) { 1 -> width * 0.22f; 2 -> width * 0.78f; else -> width * 0.50f }
        val centerY = height * 0.095f
        return RectF(centerX - buttonW / 2f, centerY - buttonH / 2f, centerX + buttonW / 2f, centerY + buttonH / 2f)
    }

    private fun textBaseline(cy: Float): Float = cy - (textPaint.ascent() + textPaint.descent()) / 2f
    private fun scaledAlpha(base: Int, opacity: Float): Int = (base * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        boostInteraction()
        if (handleMultiFingerGesture(event)) return true
        if (handleQuickMenuTouch(event)) return true
        if (editMode) return handleEditorTouch(event)
        if (!config.controlsVisible) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                if (analogPointerId == -1 && insideAnalog(x, y, 1.55f)) {
                    analogPointerId = event.getPointerId(index)
                    if (config.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == analogPointerId) {
                    analogPointerId = -1
                    updateAnalog(analogCx, analogCy)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                analogPointerId = -1
                updateAnalog(analogCx, analogCy)
            }
        }

        if (analogPointerId != -1) {
            val index = event.findPointerIndex(analogPointerId)
            if (index >= 0 && !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == analogPointerId)) {
                updateAnalog(event.getX(index), event.getY(index))
            }
        }

        val excludedUpIndex = when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            else -> -1
        }
        val next = if (event.actionMasked == MotionEvent.ACTION_CANCEL) emptySet() else buildSet {
            for (pointerIndex in 0 until event.pointerCount) {
                if (pointerIndex == excludedUpIndex) continue
                if (event.getPointerId(pointerIndex) == analogPointerId) continue
                findButtonAt(event.getX(pointerIndex), event.getY(pointerIndex), 1.34f)?.let { add(it.id) }
            }
        }
        if (next != regionPressed && config.haptics && (next - regionPressed).isNotEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        regionPressed = next
        commitButtons()
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    private fun handleMultiFingerGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturePeak = 1
                gestureCaptured = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gesturePeak = maxOf(gesturePeak, event.pointerCount)
                if (gesturePeak >= 3 && event.eventTime - event.downTime <= 420L) {
                    gestureCaptured = true
                    releaseAll()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureCaptured) {
                    if (gesturePeak >= 4) {
                        config = config.copy(controlsVisible = !config.controlsVisible)
                        persistGameConfig()
                        showToast(if (config.controlsVisible) "Overlay visível" else "Modo ultra imersivo")
                    } else {
                        toggleMenu()
                    }
                    gestureCaptured = false
                    gesturePeak = 0
                    return true
                }
            }
        }
        return gestureCaptured
    }

    private fun handleQuickMenuTouch(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return menuVisible
        val x = event.getX(event.actionIndex)
        val y = event.getY(event.actionIndex)
        if (menuButtonRect().contains(x, y)) {
            toggleMenu()
            return true
        }
        if (!menuVisible) return false
        val item = (0..7).firstOrNull { quickMenuItemRect(it).contains(x, y) }
        if (item == null) {
            menuVisible = false
            scheduleRedraw()
            return true
        }
        performMenuAction(item)
        return true
    }

    private fun performMenuAction(index: Int) {
        menuVisible = false
        removeCallbacks(menuHideRunnable)
        when (index) {
            0 -> { NativeBridge.saveState(0); showToast("Save state solicitado") }
            1 -> { NativeBridge.loadState(0); showToast("Carregando save state") }
            2 -> showStatusDialog()
            3 -> setEditMode(true)
            4 -> showVisualDialog()
            5 -> showLayoutDialog()
            6 -> showCheatDialog()
            7 -> activity?.finish()
        }
        scheduleRedraw()
    }

    private fun toggleMenu() {
        if (editMode) return
        menuVisible = !menuVisible
        removeCallbacks(menuHideRunnable)
        if (menuVisible) postDelayed(menuHideRunnable, 4500)
        scheduleRedraw()
    }

    private fun boostInteraction() {
        activeUntilMs = SystemClock.uptimeMillis() + 650L
        removeCallbacks(fadeRunnable)
        postDelayed(fadeRunnable, 680L)
    }

    private fun handleEditorTouch(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val actionX = event.getX(actionIndex)
        val actionY = event.getY(actionIndex)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (editorButtonRect(0).contains(actionX, actionY)) { setEditMode(false); return true }
            if (editorButtonRect(1).contains(actionX, actionY)) {
                config = config.copy(showDpad = !config.showDpad)
                persistGameConfig()
                regionPressed = regionPressed.filterNot { it in 4..7 }.toSet()
                commitButtons()
                scheduleRedraw()
                return true
            }
            if (editorButtonRect(2).contains(actionX, actionY)) {
                InputSettings.resetControlPositions(context, gameKey)
                rebuildLayout(width, height)
                showToast("Layout deste jogo restaurado")
                return true
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (editPointerId == -1) {
                    editTargetKey = findEditTarget(actionX, actionY)
                    if (editTargetKey != null) {
                        editPointerId = event.getPointerId(actionIndex)
                        if (config.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        moveEditTarget(actionX, actionY)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (editPointerId != -1) {
                    val index = event.findPointerIndex(editPointerId)
                    if (index >= 0) moveEditTarget(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == editPointerId) {
                    persistEditTarget()
                    editPointerId = -1
                    editTargetKey = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                editPointerId = -1
                editTargetKey = null
                rebuildLayout(width, height)
            }
        }
        scheduleRedraw()
        return true
    }

    fun toggleEditMode() = setEditMode(!editMode)
    fun isEditing(): Boolean = editMode

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        menuVisible = false
        editPointerId = -1
        editTargetKey = null
        releaseAll()
        if (enabled) showToast("EDITAR CONTROLES: arraste e toque em CONCLUIR quando terminar")
        scheduleRedraw()
    }

    private fun showStatusDialog() {
        val enabled = CheatStore.load(context, gameKey).count { it.enabled }
        AlertDialog.Builder(context)
            .setTitle(gameTitle)
            .setMessage("${NativeBridge.lastMessage()}\n\nPreset: ${config.overlayPreset.label}\nCheats ativos: $enabled")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showVisualDialog() {
        val labels = arrayOf(
            "Controles na tela",
            "Labels dos botões",
            "L1 / L2 / R1 / R2",
            "START / SELECT",
            "Setas do D-pad",
            "Modo limpo",
            "Opacidade dinâmica",
            "HUD de performance"
        )
        val checked = booleanArrayOf(
            config.controlsVisible, config.showLabels, config.showShoulders, config.showStartSelect,
            config.showDpad, config.cleanOverlay, config.dynamicOpacity, config.showPerformanceHud
        )
        AlertDialog.Builder(context)
            .setTitle("Visual • $gameTitle")
            .setMultiChoiceItems(labels, checked) { _, which, value ->
                config = when (which) {
                    0 -> config.copy(controlsVisible = value)
                    1 -> config.copy(showLabels = value)
                    2 -> config.copy(showShoulders = value)
                    3 -> config.copy(showStartSelect = value)
                    4 -> config.copy(showDpad = value)
                    5 -> config.copy(cleanOverlay = value)
                    6 -> config.copy(dynamicOpacity = value)
                    7 -> config.copy(showPerformanceHud = value)
                    else -> config
                }
                persistGameConfig()
                rebuildLayout(width, height)
                scheduleRedraw()
            }
            .setNeutralButton("Usar padrão global") { _, _ ->
                InputSettings.clearGameProfile(context, gameKey)
                config = InputSettings.resolve(context)
                rebuildLayout(width, height)
                scheduleRedraw()
            }
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showLayoutDialog() {
        val presets = InputSettings.OverlayPreset.entries
        val selected = presets.indexOf(config.overlayPreset).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Preset de layout")
            .setSingleChoiceItems(presets.map { "${it.label} — ${it.subtitle}" }.toTypedArray(), selected) { dialog, which ->
                val preset = presets[which]
                config = config.copy(
                    overlayPreset = preset,
                    cleanOverlay = preset != InputSettings.OverlayPreset.STANDARD,
                    showLabels = preset == InputSettings.OverlayPreset.STANDARD
                )
                persistGameConfig()
                InputSettings.resetControlPositions(context, gameKey)
                rebuildLayout(width, height)
                dialog.dismiss()
                showToast("Layout ${preset.label} aplicado a este jogo")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCheatDialog() {
        val current = CheatStore.load(context, gameKey).toMutableList()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        lateinit var dialog: AlertDialog

        if (current.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "Nenhum cheat salvo para este jogo. Use códigos compatíveis com PS1/PCSX-ReARMed."
                setPadding(0, dp(8), 0, dp(12))
            })
        } else {
            current.forEach { cheat ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val check = CheckBox(context).apply {
                    text = cheat.name
                    isChecked = cheat.enabled
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, enabled ->
                        val idx = current.indexOfFirst { it.id == cheat.id }
                        if (idx >= 0) {
                            current[idx] = current[idx].copy(enabled = enabled)
                            CheatStore.save(context, gameKey, current)
                            applyStoredCheats(current)
                        }
                    }
                }
                val delete = Button(context).apply {
                    text = "×"
                    minWidth = 0
                    setOnClickListener {
                        current.removeAll { it.id == cheat.id }
                        CheatStore.save(context, gameKey, current)
                        applyStoredCheats(current)
                        dialog.dismiss()
                        showCheatDialog()
                    }
                }
                row.addView(check)
                row.addView(delete, LinearLayout.LayoutParams(dp(52), dp(44)))
                container.addView(row)
            }
        }

        val add = Button(context).apply { text = "+ Adicionar cheat" }
        val disable = Button(context).apply { text = "Desativar todos" }
        container.addView(add)
        container.addView(disable)
        val scroll = ScrollView(context).apply { addView(container) }
        dialog = AlertDialog.Builder(context)
            .setTitle("Cheats • $gameTitle")
            .setView(scroll)
            .setPositiveButton("Fechar", null)
            .create()
        add.setOnClickListener { showAddCheatDialog(dialog) }
        disable.setOnClickListener {
            val disabled = current.map { it.copy(enabled = false) }
            CheatStore.save(context, gameKey, disabled)
            applyStoredCheats(disabled)
            dialog.dismiss()
            showCheatDialog()
        }
        dialog.show()
    }

    private fun showAddCheatDialog(parent: AlertDialog) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), 0)
        }
        val name = EditText(context).apply { hint = "Nome do cheat"; isSingleLine = true }
        val code = EditText(context).apply {
            hint = "Código (uma ou várias linhas)"
            minLines = 3
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(name)
        box.addView(code)
        AlertDialog.Builder(context)
            .setTitle("Adicionar cheat")
            .setView(box)
            .setMessage("Exemplo de formato: 80012345 0063. O código precisa corresponder à região/versão do jogo.")
            .setPositiveButton("Salvar") { _, _ ->
                val value = code.text.toString().trim()
                if (value.isBlank()) {
                    showToast("Informe um código")
                    return@setPositiveButton
                }
                val list = CheatStore.load(context, gameKey).toMutableList()
                list += CheatStore.Cheat(name = name.text.toString().trim().ifBlank { "Cheat ${list.size + 1}" }, code = value, enabled = true)
                CheatStore.save(context, gameKey, list)
                applyStoredCheats(list)
                parent.dismiss()
                showCheatDialog()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyStoredCheats(cheats: List<CheatStore.Cheat> = CheatStore.load(context, gameKey)) {
        if (!NativeBridge.isRunning()) return
        NativeBridge.resetCheats()
        cheats.forEachIndexed { index, cheat ->
            if (cheat.enabled && cheat.code.isNotBlank()) NativeBridge.setCheat(index, true, cheat.code)
        }
    }

    private fun persistGameConfig() {
        InputSettings.saveGameConfig(context, gameKey, config)
    }

    private fun findEditTarget(x: Float, y: Float): String? {
        if (insideAnalog(x, y, 1.55f)) return "analog"
        return findButtonAt(x, y, 1.45f)?.key
    }

    private fun findButtonAt(x: Float, y: Float, hitScale: Float): Region? =
        visibleRegions().asSequence().filter { contains(it, x, y, hitScale) }.minByOrNull { hitDistance(it, x, y) }

    private fun hitDistance(region: Region, x: Float, y: Float): Double {
        if (!region.wide) return hypot((x - region.cx).toDouble(), (y - region.cy).toDouble()) / region.radius.coerceAtLeast(1f)
        val nx = abs(x - region.cx) / (region.radius * 1.75f).coerceAtLeast(1f)
        val ny = abs(y - region.cy) / (region.radius * 0.92f).coerceAtLeast(1f)
        return hypot(nx.toDouble(), ny.toDouble())
    }

    private fun moveEditTarget(x: Float, y: Float) {
        val key = editTargetKey ?: return
        val margin = min(width, height) * 0.050f
        val nx = x.coerceIn(margin, width - margin)
        val ny = y.coerceIn(margin * 1.25f, height - margin)
        if (key == "analog") {
            analogCx = nx; analogCy = ny; analogKnobX = nx; analogKnobY = ny
        } else {
            regions = regions.map { if (it.key == key) it.copy(cx = nx, cy = ny) else it }
        }
        scheduleRedraw()
    }

    private fun persistEditTarget() {
        val key = editTargetKey ?: return
        if (width <= 0 || height <= 0) return
        if (key == "analog") {
            InputSettings.saveControlPosition(context, key, analogCx / width, analogCy / height, gameKey)
        } else {
            val region = regions.firstOrNull { it.key == key } ?: return
            InputSettings.saveControlPosition(context, key, region.cx / width, region.cy / height, gameKey)
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun releaseAll() {
        regionPressed = emptySet()
        analogDpadPressed = emptySet()
        commitButtons()
        analogPointerId = -1
        updateAnalog(analogCx, analogCy)
    }

    private fun visibleRegions(): List<Region> = regions.filter { region ->
        when {
            region.id in 4..7 && !config.showDpad -> false
            region.id in 10..13 && !config.showShoulders -> false
            region.id in 2..3 && !config.showStartSelect -> false
            else -> true
        }
    }

    private fun insideAnalog(x: Float, y: Float, scale: Float): Boolean =
        hypot((x - analogCx).toDouble(), (y - analogCy).toDouble()) <= analogRadius * scale

    private fun updateAnalog(x: Float, y: Float) {
        if (analogRadius <= 0f) return
        var dx = (x - analogCx) / analogRadius
        var dy = (y - analogCy) / analogRadius
        val magnitude = sqrt(dx * dx + dy * dy)
        if (magnitude > 1f) { dx /= magnitude; dy /= magnitude }
        val deadzone = 0.11f
        if (magnitude < deadzone) {
            dx = 0f; dy = 0f
        } else if (magnitude > 0f) {
            val remapped = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
            val remapScale = remapped / magnitude.coerceAtLeast(0.0001f)
            dx *= remapScale; dy *= remapScale
        }
        analogKnobX = analogCx + dx * analogRadius * 0.72f
        analogKnobY = analogCy + dy * analogRadius * 0.72f
        val analogChanged = lastAnalogX.isNaN() || abs(dx - lastAnalogX) > 0.0025f || abs(dy - lastAnalogY) > 0.0025f
        when (config.analogMode) {
            InputSettings.AnalogMode.NATIVE -> {
                if (analogChanged) NativeBridge.setAnalog(0, dx, dy)
                analogDpadPressed = emptySet()
            }
            InputSettings.AnalogMode.DPAD -> {
                if (analogChanged) NativeBridge.setAnalog(0, 0f, 0f)
                analogDpadPressed = dpadProjection(dx, dy)
            }
            InputSettings.AnalogMode.SMART -> {
                if (analogChanged) NativeBridge.setAnalog(0, dx, dy)
                analogDpadPressed = dpadProjection(dx, dy)
            }
        }
        lastAnalogX = dx; lastAnalogY = dy
        commitButtons()
        scheduleRedraw()
    }

    private fun dpadProjection(x: Float, y: Float): Set<Int> = buildSet {
        val threshold = 0.42f
        if (y <= -threshold) add(4)
        if (y >= threshold) add(5)
        if (x <= -threshold) add(6)
        if (x >= threshold) add(7)
    }

    private fun contains(region: Region, x: Float, y: Float, hitScale: Float): Boolean = if (region.wide) {
        val halfW = region.radius * 1.75f * hitScale
        val halfH = region.radius * 0.92f * hitScale
        x in (region.cx - halfW)..(region.cx + halfW) && y in (region.cy - halfH)..(region.cy + halfH)
    } else {
        hypot((x - region.cx).toDouble(), (y - region.cy).toDouble()) <= region.radius * hitScale
    }

    private fun commitButtons() {
        val next = regionPressed + analogDpadPressed
        if (next == committedButtons) return
        (committedButtons - next).forEach { NativeBridge.setButton(it, false) }
        (next - committedButtons).forEach { NativeBridge.setButton(it, true) }
        committedButtons = next
        scheduleRedraw()
    }

    private fun showToast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun scheduleRedraw() { if (isAttachedToWindow) postInvalidateOnAnimation() else invalidate() }
}
''')

# Native host: load optional libretro cheat exports, queue all calls onto the emulation thread.
replace_once(
    "app/src/main/cpp/libretro_host_v7.cpp",
    "    using get_memory_size_t = std::size_t (*)(unsigned);\n",
    "    using get_memory_size_t = std::size_t (*)(unsigned);\n    using cheat_reset_t = void (*)();\n    using cheat_set_t = void (*)(unsigned, bool, const char*);\n"
)
replace_once(
    "app/src/main/cpp/libretro_host_v7.cpp",
    "    get_memory_data_t getMemoryData = nullptr;\n    get_memory_size_t getMemorySize = nullptr;\n",
    "    get_memory_data_t getMemoryData = nullptr;\n    get_memory_size_t getMemorySize = nullptr;\n    cheat_reset_t cheatReset = nullptr;\n    cheat_set_t cheatSet = nullptr;\n"
)
replace_once(
    "app/src/main/cpp/libretro_host_v7.cpp",
    "        symbol(api.handle, \"retro_get_memory_data\", api.getMemoryData) &&\n        symbol(api.handle, \"retro_get_memory_size\", api.getMemorySize);\n    if (!ok) {\n",
    "        symbol(api.handle, \"retro_get_memory_data\", api.getMemoryData) &&\n        symbol(api.handle, \"retro_get_memory_size\", api.getMemorySize);\n    if (ok) {\n        api.cheatReset = reinterpret_cast<CoreApi::cheat_reset_t>(dlsym(api.handle, \"retro_cheat_reset\"));\n        api.cheatSet = reinterpret_cast<CoreApi::cheat_set_t>(dlsym(api.handle, \"retro_cheat_set\"));\n    }\n    if (!ok) {\n"
)
replace_once(
    "app/src/main/cpp/libretro_host_v7.cpp",
    "class LibretroSession::Impl {\npublic:\n",
    "class LibretroSession::Impl {\n    struct CheatCommand {\n        unsigned index = 0;\n        bool enabled = false;\n        std::string code;\n    };\npublic:\n"
)
replace_once(
    "app/src/main/cpp/libretro_host_v7.cpp",
    "    void requestSaveState(int slot) { saveStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }\n    void requestLoadState(int slot) { loadStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }\n\n",
    "    void requestSaveState(int slot) { saveStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }\n    void requestLoadState(int slot) { loadStateRequest_.store(std::clamp(slot, 0, 9), std::memory_order_release); }\n    void requestCheatReset() {\n        std::lock_guard<std::mutex> lock(cheatMutex_);\n        cheatResetPending_ = true;\n        cheatCommands_.clear();\n    }\n    void requestCheatSet(unsigned index, bool enabled, std::string code) {\n        if (code.empty()) return;\n        if (code.size() > 8192) code.resize(8192);\n        std::lock_guard<std::mutex> lock(cheatMutex_);\n        cheatCommands_.push_back(CheatCommand{std::min(index, 127u), enabled, std::move(code)});\n    }\n\n"
)
replace_once(
    "app/src/main/cpp/libretro_host_v7.cpp",
    "    void runLoop() {\n",
    "    void applyCheatRequests(CoreApi& api) {\n        bool reset = false;\n        std::vector<CheatCommand> commands;\n        {\n            std::lock_guard<std::mutex> lock(cheatMutex_);\n            reset = cheatResetPending_;\n            cheatResetPending_ = false;\n            commands.swap(cheatCommands_);\n        }\n        if (reset && api.cheatReset) api.cheatReset();\n        if (!api.cheatSet) return;\n        for (const auto& command : commands) {\n            api.cheatSet(command.index, command.enabled, command.code.c_str());\n        }\n    }\n\n    void runLoop() {\n"
)
replace_once(
    "app/src/main/cpp/libretro_host_v7.cpp",
    "                const int saveSlot = saveStateRequest_.exchange(-1, std::memory_order_acq_rel);\n                if (saveSlot >= 0) saveState(api, saveSlot);\n\n",
    "                const int saveSlot = saveStateRequest_.exchange(-1, std::memory_order_acq_rel);\n                if (saveSlot >= 0) saveState(api, saveSlot);\n                applyCheatRequests(api);\n\n"
)
replace_once(
    "app/src/main/cpp/libretro_host_v7.cpp",
    "    std::atomic<int> saveStateRequest_{-1};\n    std::atomic<int> loadStateRequest_{-1};\n    std::atomic<bool> audioReconfigureRequested_{false};\n",
    "    std::atomic<int> saveStateRequest_{-1};\n    std::atomic<int> loadStateRequest_{-1};\n    std::mutex cheatMutex_;\n    bool cheatResetPending_ = false;\n    std::vector<CheatCommand> cheatCommands_;\n    std::atomic<bool> audioReconfigureRequested_{false};\n"
)
replace_once(
    "app/src/main/cpp/libretro_host_v7.cpp",
    "void LibretroSession::requestSaveState(int slot) { impl_->requestSaveState(slot); }\nvoid LibretroSession::requestLoadState(int slot) { impl_->requestLoadState(slot); }\nvoid LibretroSession::updatePerformanceConfig(RuntimePerformanceConfig performance) { impl_->updatePerformanceConfig(performance); }\n",
    "void LibretroSession::requestSaveState(int slot) { impl_->requestSaveState(slot); }\nvoid LibretroSession::requestLoadState(int slot) { impl_->requestLoadState(slot); }\nvoid LibretroSession::requestCheatReset() { impl_->requestCheatReset(); }\nvoid LibretroSession::requestCheatSet(unsigned index, bool enabled, std::string code) { impl_->requestCheatSet(index, enabled, std::move(code)); }\nvoid LibretroSession::updatePerformanceConfig(RuntimePerformanceConfig performance) { impl_->updatePerformanceConfig(performance); }\n"
)

# JNI bridge for the queued native cheat API.
replace_once(
    "app/src/main/cpp/native_bridge.cpp",
    "OmniCore Native Runtime 0.9.2 / libretro host v7 / EGL-GLES presenter",
    "OmniCore Native Runtime 0.9.3 / libretro host v7 / EGL-GLES presenter"
)
replace_once(
    "app/src/main/cpp/native_bridge.cpp",
    "extern \"C\" JNIEXPORT jstring JNICALL\nJava_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeLastMessage(\n",
    "extern \"C\" JNIEXPORT void JNICALL\nJava_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeResetCheats(\n        JNIEnv* /* env */, jobject /* thiz */) {\n    std::lock_guard<std::mutex> lock(gSessionMutex);\n    if (gSession) gSession->requestCheatReset();\n}\n\nextern \"C\" JNIEXPORT void JNICALL\nJava_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeSetCheat(\n        JNIEnv* env, jobject /* thiz */, jint index, jboolean enabled, jstring code) {\n    std::lock_guard<std::mutex> lock(gSessionMutex);\n    if (!gSession || index < 0 || index > 127 || !code) return;\n    gSession->requestCheatSet(static_cast<unsigned>(index), enabled == JNI_TRUE, toString(env, code));\n}\n\nextern \"C\" JNIEXPORT jstring JNICALL\nJava_com_omnicore_emulator_core_nativebridge_NativeBridge_nativeLastMessage(\n"
)

# Versioning.
replace_once("app/build.gradle.kts", 'versionCode = 13\n        versionName = "0.9.2"', 'versionCode = 14\n        versionName = "0.9.3"')

# Tuning text and information page.
replace_once(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt",
    '                Text(\n                    "Dentro do jogo, use EDITAR CONTROLES para reposicionar botões, ocultar/mostrar as setas ou restaurar o layout. Tamanho e opacidade entram na próxima sessão.",\n                    color = Color(0xFF737C98),\n                    style = MaterialTheme.typography.labelSmall\n                )',
    '                Text(\n                    "No jogo, o botão ⋮ abre o Quick Menu com save/load, status, editor, visual, presets, cheats e saída. Perfis visuais e posições ficam salvos por jogo.",\n                    color = Color(0xFF737C98),\n                    style = MaterialTheme.typography.labelSmall\n                )'
)
replace_once(
    "app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt",
    '                Text("Novidades da linha 0.9.1", fontWeight = FontWeight.Bold)\n                Text("• Editor de overlay dentro do jogo com reposicionamento e restauração do layout.", color = HubSoft, style = MaterialTheme.typography.bodySmall)\n                Text("• Analógico Inteligente com D-pad visual opcional e projeção digital para jogos antigos.", color = HubSoft, style = MaterialTheme.typography.bodySmall)\n                Text("• Touch com seleção do controle mais próximo por dedo e multitouch mais previsível.", color = HubSoft, style = MaterialTheme.typography.bodySmall)\n                Text("• Pré-aquecimento de caches do app e prefetch de save state antes do unserialize.", color = HubSoft, style = MaterialTheme.typography.bodySmall)',
    '                Text("Novidades da linha 0.9.3", fontWeight = FontWeight.Bold)\n                Text("• Clean Overlay com Quick Menu compacto e HUD de performance oculto por padrão.", color = HubSoft, style = MaterialTheme.typography.bodySmall)\n                Text("• Presets Limpo, Compacto, Padrão, Mão esquerda, Mão direita e Tablet com perfil por jogo.", color = HubSoft, style = MaterialTheme.typography.bodySmall)\n                Text("• Labels, ombros, START/SELECT, D-pad e overlay completo podem ser ocultados por jogo.", color = HubSoft, style = MaterialTheme.typography.bodySmall)\n                Text("• Gestos de 3 dedos abrem o menu; 4 dedos alternam o modo ultra imersivo.", color = HubSoft, style = MaterialTheme.typography.bodySmall)\n                Text("• Cheat Manager por jogo integrado ao retro_cheat_set/reset do PCSX-ReARMed.", color = HubSoft, style = MaterialTheme.typography.bodySmall)\n                Text("• Pré-aquecimento de caches e prefetch de save state da linha 0.9.1 continuam ativos.", color = HubSoft, style = MaterialTheme.typography.bodySmall)'
)

# Workflow 0.9.3 validation and release publication.
workflow = ROOT / ".github/workflows/android.yml"
text = workflow.read_text(encoding="utf-8")
text = text.replace("Validate 0.9.2 information and controls foundation", "Validate 0.9.3 clean overlay and cheats foundation", 1)
text = text.replace('versionName = "0.9.2"', 'versionName = "0.9.3"', 1)
text = text.replace("OmniCore Native Runtime 0.9.2", "OmniCore Native Runtime 0.9.3", 1)
anchor = "          grep -q 'EDITAR CONTROLES' app/src/main/java/com/omnicore/emulator/emulation/GamepadOverlayView.kt\n"
if anchor not in text:
    raise RuntimeError("workflow editor validation anchor missing")
text = text.replace(anchor, anchor +
    "          grep -q 'Quick Menu' app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt\n"
    "          grep -q 'CheatStore' app/src/main/java/com/omnicore/emulator/emulation/GamepadOverlayView.kt\n"
    "          grep -q 'nativeSetCheat' app/src/main/java/com/omnicore/emulator/core/nativebridge/NativeBridge.kt\n"
    "          grep -q 'retro_cheat_set' app/src/main/cpp/libretro_host_v7.cpp\n"
    "          grep -q 'OverlayPreset.TABLET' app/src/main/java/com/omnicore/emulator/emulation/GamepadOverlayView.kt\n", 1)
text = text.replace('TAG="v0.9.2-dev"', 'TAG="v0.9.3-dev"', 1)
text = text.replace('TITLE="OmniCore v0.9.2 Information & Control Polish"', 'TITLE="OmniCore v0.9.3 Clean Overlay & Cheat Manager"', 1)
old_notes_start = '          NOTES="Information and control discoverability update on top of the validated 0.9.1 touch, smoothness and save foundation.'
notes_line = next((line for line in text.splitlines() if line.startswith(old_notes_start)), None)
if notes_line is None:
    raise RuntimeError("0.9.2 release notes anchor missing")
new_notes = '          NOTES="Clean gameplay UI and per-game customization update on top of the validated Runtime v7/EGL-GLES foundation. The always-visible save/load/status/exit bars are retired by the Clean Overlay layer and replaced with a compact Quick Menu opened by the small ⋮ control or a three-finger gesture. Four fingers toggle an ultra-immersive overlay mode. Touch controls now support dynamic idle opacity, optional labels, shoulder visibility, START/SELECT visibility, D-pad visibility and six layout presets: Clean, Compact, Standard, Left hand, Right hand and Tablet. Layout positions and visual choices can be stored per game while global defaults remain available. The editor is only shown when requested instead of occupying the playfield. A per-game Cheat Manager stores named codes, toggles them live and applies them through PCSX-ReARMed libretro retro_cheat_reset/retro_cheat_set on the emulation thread. Existing Smart analog, nearest-button multitouch, save-state prefetch, CUE/BIN cache, controller support, BIOS boot, aspect modes, AAudio and SmartPerf remain intact. Uses the same stable DEV signing certificate and updates in place. ROMs, BIOS, firmware, console keys and cheat databases are not included."'
text = text.replace(notes_line, new_notes, 1)
text = text.replace('build/OmniCore-v0.9.2-debug.apk', 'build/OmniCore-v0.9.3-debug.apk')
text = text.replace('build/OmniCore-v0.9.2-debug.sha256', 'build/OmniCore-v0.9.3-debug.sha256')
workflow.write_text(text, encoding="utf-8")

# One-shot migration artifacts remove themselves in the generated commit.
for path in [
    ROOT / "tools/agent_093_migrate.py",
    ROOT / ".github/workflows/agent-093-migration.yml",
]:
    if path.exists():
        path.unlink()
