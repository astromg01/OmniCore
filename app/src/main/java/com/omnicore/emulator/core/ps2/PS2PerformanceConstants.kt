package com.omnicore.emulator.core.ps2

/**
 * Stable compile-time identifiers shared by the PS2 backend and the Alpha 6
 * build-time runtime transform.
 *
 * Keep these outside Pcsx2PS2Backend so generated pressure-profiler code can
 * reference them even when that transform already contains the identifier names
 * before it reaches the companion-object insertion step.
 */
internal const val PERF_PREFS = "omnicore_ps2_perf_learning_v1"
internal const val PERF_PROFILE_COMPUTE = -1
internal const val PERF_PROFILE_UNKNOWN = 0
internal const val PERF_PROFILE_RENDER = 1
