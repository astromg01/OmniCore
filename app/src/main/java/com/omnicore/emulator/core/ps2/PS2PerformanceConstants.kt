package com.omnicore.emulator.core.ps2

/**
 * Stable compile-time identifiers shared by the PS2 backend and the Alpha 6
 * build-time runtime transform.
 *
 * v2 deliberately invalidates the first pressure-learning data set. The v1
 * classifier normalized process CPU time by every logical core, which can hide
 * the few saturated EE/VU/GS threads typical of PS2 emulation on big.LITTLE SoCs.
 */
internal const val PERF_PREFS = "omnicore_ps2_perf_learning_v2"
internal const val PERF_PROFILE_COMPUTE = -1
internal const val PERF_PROFILE_UNKNOWN = 0
internal const val PERF_PROFILE_RENDER = 1
