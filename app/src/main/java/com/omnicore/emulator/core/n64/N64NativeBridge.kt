package com.omnicore.emulator.core.n64

object N64NativeBridge {
    private val runtimeLoaded: Boolean = runCatching {
        System.loadLibrary("omnicore_n64_runtime")
        true
    }.getOrDefault(false)

    fun hasCore(): Boolean = runtimeLoaded && runCatching { nativeHasCore() }.getOrDefault(false)

    fun runtimeInfo(): String = if (!runtimeLoaded) {
        "N64 Runtime indisponível"
    } else {
        runCatching { nativeRuntimeInfo() }.getOrDefault("N64 Runtime")
    }

    private external fun nativeHasCore(): Boolean
    private external fun nativeRuntimeInfo(): String
}
