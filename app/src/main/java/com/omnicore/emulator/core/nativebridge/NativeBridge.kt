package com.omnicore.emulator.core.nativebridge

import android.view.Surface

object NativeBridge {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("omnicore_runtime")
        true
    }.getOrDefault(false)

    fun isLoaded(): Boolean = loaded

    fun runtimeVersion(): String =
        if (loaded) runCatching { nativeRuntimeVersion() }.getOrDefault("native-runtime-error")
        else "native-runtime-unavailable"

    fun hasPs1Core(): Boolean =
        loaded && runCatching { nativeHasPs1Core() }.getOrDefault(false)

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
    ): Boolean = loaded && runCatching {
        nativeStartPs1(
            gamePath, gameKey, systemDir, saveDir, stateDir, surface,
            performancePolicy, audioBufferBursts, tryExclusiveAudio,
            preferPowerEfficiency, aggressiveFramePacing, coreOptions, dualShock
        )
    }.getOrDefault(false)

    fun stop() {
        if (loaded) runCatching { nativeStop() }
    }

    fun isRunning(): Boolean =
        loaded && runCatching { nativeIsRunning() }.getOrDefault(false)

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
        if (loaded) runCatching { nativeSaveState(slot) }
    }

    fun loadState(slot: Int = 0) {
        if (loaded) runCatching { nativeLoadState(slot) }
    }

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
    private external fun nativeLastMessage(): String
}
