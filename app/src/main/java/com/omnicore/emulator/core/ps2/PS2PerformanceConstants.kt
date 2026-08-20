package com.omnicore.emulator.core.ps2

/**
 * Stable compile-time identifiers shared by the PS2 backend and the Alpha 6
 * build-time runtime transforms.
 *
 * v3 resets learning because Alpha 6 #18 upgrades from process-wide heuristics
 * to PCSX2's own EE/VU/GS/GPU performance metrics when those symbols are
 * available. Legacy coarse profiles remain defined so the previous transforms
 * can still be applied idempotently before the #18 refinement runs.
 */
internal const val PERF_PREFS = "omnicore_ps2_perf_learning_v3"

internal const val PERF_PROFILE_VU = -3
internal const val PERF_PROFILE_EE = -2
internal const val PERF_PROFILE_COMPUTE = -1 // legacy compatibility
internal const val PERF_PROFILE_UNKNOWN = 0
internal const val PERF_PROFILE_RENDER = 1 // legacy compatibility
internal const val PERF_PROFILE_GS = 2
internal const val PERF_PROFILE_GPU = 3
