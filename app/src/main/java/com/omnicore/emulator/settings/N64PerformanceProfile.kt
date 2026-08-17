package com.omnicore.emulator.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * N64-only hardware classification. It does not reuse PS1 runtime policy knobs.
 * The profile is intentionally conservative because N64 RSP/RDP costs differ
 * significantly from the PlayStation backend.
 */
object N64PerformanceProfile {
    enum class Tier { LOW, BALANCED, HIGH }

    data class Profile(
        val tier: Tier,
        val ramMb: Long,
        val cpuCores: Int,
        val is64Bit: Boolean,
        val lowRamDevice: Boolean
    ) {
        val label: String
            get() = when (tier) {
                Tier.LOW -> "N64 • Conservador"
                Tier.BALANCED -> "N64 • Equilibrado"
                Tier.HIGH -> "N64 • Alta margem"
            }
    }

    @Volatile private var cached: Profile? = null

    fun detect(context: Context): Profile {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: compute(context.applicationContext).also { cached = it }
        }
    }

    private fun compute(context: Context): Profile {
        val am = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memoryInfo)
        val ramMb = if (memoryInfo.totalMem > 0) memoryInfo.totalMem / (1024L * 1024L) else 0L
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val lowRam = am?.isLowRamDevice == true

        val tier = when {
            !is64Bit || lowRam || ramMb in 1L..3071L || cores <= 4 -> Tier.LOW
            ramMb >= 6144L && cores >= 6 -> Tier.HIGH
            else -> Tier.BALANCED
        }
        return Profile(tier, ramMb, cores, is64Bit, lowRam)
    }
}
