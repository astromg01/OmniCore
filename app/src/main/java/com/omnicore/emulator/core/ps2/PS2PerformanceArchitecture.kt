package com.omnicore.emulator.core.ps2

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/**
 * Static per-session PS2 performance policy.
 *
 * This deliberately follows a GameDB/device-profile model instead of changing
 * emulator timing while a game is running. PCSX2's own GameDB remains the
 * compatibility authority; OmniCore only selects a small set of host-side
 * policies before VM boot.
 */
object PS2PerformanceArchitecture {
    enum class DeviceClass {
        MALI_CLASS,
        ADRENO_CLASS,
        GENERIC
    }

    enum class GameClass {
        DEFAULT,
        HEAVY_GS_REFERENCE
    }

    enum class ReadbackPolicy(val nativeValue: Int) {
        /** Normal PCSX2 hardware readbacks. */
        ENABLED(0),
        /** PCSX2 GSHardwareDownloadMode::NoReadbacks. */
        NO_READBACKS(2)
    }

    data class SessionPolicy(
        val tier: PS2Backend.PerformanceTier,
        val deviceClass: DeviceClass,
        val gameClass: GameClass,
        val renderer: PS2Backend.Renderer,
        val readbacks: ReadbackPolicy,
        val queueAheadFrames: Int,
        val mtvu: Boolean,
        val instantVu1: Boolean,
        val vuFlagHack: Boolean,
        val waitLoopHack: Boolean,
        val intcStatHack: Boolean,
        val reason: String
    )

    fun resolve(
        context: Context,
        gameKey: String,
        requested: PS2Backend.RuntimeConfig
    ): SessionPolicy {
        val am = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val power = context.getSystemService(PowerManager::class.java)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val supportsVulkan = Build.VERSION.SDK_INT >= 24 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val supportsGles3 = (am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x00030000

        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else PowerManager.THERMAL_STATUS_NONE
        val constrained = power?.isPowerSaveMode == true ||
            thermal >= PowerManager.THERMAL_STATUS_SEVERE

        val deviceClass = classifyDevice()
        val gameClass = classifyGame(gameKey)
        val tier = if (constrained) PS2Backend.PerformanceTier.SAFE else requested.performanceTier

        // Explicit user renderer choices always win. AUTO is resolved once,
        // before VM boot, and never changed in the middle of a session.
        val renderer = when {
            requested.renderer != PS2Backend.Renderer.AUTO -> requested.renderer
            !supportsVulkan -> if (supportsGles3) PS2Backend.Renderer.GLES3 else PS2Backend.Renderer.AUTO
            tier == PS2Backend.PerformanceTier.FAST -> PS2Backend.Renderer.VULKAN
            gameClass == GameClass.HEAVY_GS_REFERENCE && tier == PS2Backend.PerformanceTier.OPTIMAL ->
                PS2Backend.Renderer.VULKAN
            deviceClass == DeviceClass.ADRENO_CLASS -> PS2Backend.Renderer.VULKAN
            else -> PS2Backend.Renderer.AUTO
        }

        // NetherSX2's public performance guidance is used only as a policy
        // reference: Vulkan + disabled hardware readbacks is an aggressive host
        // profile. OmniCore scopes that choice to FAST, plus the currently
        // validated heavy-reference GameDB class under OPTIMAL. SAFE never does it.
        val noReadbacks = !constrained && (
            tier == PS2Backend.PerformanceTier.FAST ||
                (tier == PS2Backend.PerformanceTier.OPTIMAL &&
                    gameClass == GameClass.HEAVY_GS_REFERENCE)
            )
        val readbacks = if (noReadbacks) ReadbackPolicy.NO_READBACKS else ReadbackPolicy.ENABLED

        val queueAhead = when (tier) {
            PS2Backend.PerformanceTier.SAFE -> 1
            PS2Backend.PerformanceTier.OPTIMAL -> if (cores >= 6) 2 else 1
            PS2Backend.PerformanceTier.FAST -> if (cores >= 6) 2 else 1
        }
        val mtvu = cores >= 6 && !constrained

        return SessionPolicy(
            tier = tier,
            deviceClass = deviceClass,
            gameClass = gameClass,
            renderer = renderer,
            readbacks = readbacks,
            queueAheadFrames = queueAhead.coerceIn(1, 2),
            mtvu = mtvu,
            instantVu1 = true,
            vuFlagHack = true,
            waitLoopHack = true,
            intcStatHack = true,
            reason = buildString {
                append("tier=")
                append(tier.name)
                append(" device=")
                append(deviceClass.name)
                append(" game=")
                append(gameClass.name)
                append(" renderer=")
                append(renderer.name)
                append(" readbacks=")
                append(readbacks.name)
                if (constrained) append(" constrained")
            }
        )
    }

    private fun classifyDevice(): DeviceClass {
        val socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        val id = listOf(
            socManufacturer,
            socModel,
            Build.HARDWARE,
            Build.BOARD,
            Build.DEVICE,
            Build.PRODUCT,
            Build.MANUFACTURER
        ).joinToString(" ").lowercase()

        return when {
            listOf("qualcomm", "qcom", "snapdragon").any(id::contains) -> DeviceClass.ADRENO_CLASS
            listOf("mediatek", "mt67", "mt68", "mt69", "exynos").any(id::contains) -> DeviceClass.MALI_CLASS
            else -> DeviceClass.GENERIC
        }
    }

    private fun classifyGame(gameKey: String): GameClass {
        val key = gameKey.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace('.', ' ')
        return if (
            key.contains("god of war") ||
            key.contains("godofwar")
        ) {
            GameClass.HEAVY_GS_REFERENCE
        } else {
            GameClass.DEFAULT
        }
    }
}
