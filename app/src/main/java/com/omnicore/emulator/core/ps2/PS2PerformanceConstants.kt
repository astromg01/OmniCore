package com.omnicore.emulator.core.ps2

/**
 * Stable identifiers shared by the PS2 backend and Alpha 6 build guards.
 *
 * v3 learning is intentionally preserved for Alpha 6 #19: #18 already learned
 * useful per-game EE/VU/GS/GPU classifications. #19 makes those classifications
 * actionable and exposes them in telemetry instead of throwing the data away.
 */
internal const val PERF_PREFS = "omnicore_ps2_perf_learning_v3"

internal const val PERF_PROFILE_VU = -3
internal const val PERF_PROFILE_EE = -2
internal const val PERF_PROFILE_COMPUTE = -1 // legacy compatibility
internal const val PERF_PROFILE_UNKNOWN = 0
internal const val PERF_PROFILE_RENDER = 1 // legacy compatibility
internal const val PERF_PROFILE_GS = 2
internal const val PERF_PROFILE_GPU = 3

internal const val PERF_VIS_UNKNOWN = 0
internal const val PERF_VIS_GEOMETRY = 1
internal const val PERF_VIS_FILL = 2

internal fun ps2PerfProfileName(profile: Int): String = when (profile) {
    PERF_PROFILE_VU -> "VU"
    PERF_PROFILE_EE -> "EE"
    PERF_PROFILE_COMPUTE -> "COMPUTE"
    PERF_PROFILE_RENDER -> "RENDER"
    PERF_PROFILE_GS -> "GS"
    PERF_PROFILE_GPU -> "GPU"
    else -> "UNKNOWN"
}

internal fun ps2VisibilityName(visibility: Int): String = when (visibility) {
    PERF_VIS_GEOMETRY -> "GEOMETRY"
    PERF_VIS_FILL -> "FILL"
    else -> "UNKNOWN"
}
