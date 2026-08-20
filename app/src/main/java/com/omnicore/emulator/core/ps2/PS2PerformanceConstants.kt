package com.omnicore.emulator.core.ps2

/**
 * Stable identifiers shared by the PS2 backend and Alpha 6 build guards.
 *
 * v4 intentionally starts a clean per-game learning set for Alpha 6 #20. The
 * official ARMSX2 release hides PerformanceMetrics C++ symbols, so #18/#19
 * could only persist coarse process-level guesses. #20 learns again from real
 * Android per-thread EE/MTVU/GS CPU accounting instead of inheriting those
 * ambiguous classifications.
 */
internal const val PERF_PREFS = "omnicore_ps2_perf_learning_v4"

internal const val PERF_PROFILE_VU = -3
internal const val PERF_PROFILE_EE = -2
internal const val PERF_PROFILE_COMPUTE = -1 // legacy compatibility
internal const val PERF_PROFILE_UNKNOWN = 0
internal const val PERF_PROFILE_RENDER = 1 // legacy compatibility
internal const val PERF_PROFILE_GS = 2
internal const val PERF_PROFILE_GPU = 3
// #22: GS pipelining is useful, but EE+VU can become the new parallel critical
// path. BALANCED keeps the Vulkan GS split while reserving scheduler priority
// for EE/VU instead of disabling the pipeline and regressing GS throughput.
internal const val PERF_PROFILE_BALANCED = 4

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
    PERF_PROFILE_BALANCED -> "BALANCED"
    else -> "UNKNOWN"
}

internal fun ps2VisibilityName(visibility: Int): String = when (visibility) {
    PERF_VIS_GEOMETRY -> "GEOMETRY"
    PERF_VIS_FILL -> "FILL"
    else -> "UNKNOWN"
}
