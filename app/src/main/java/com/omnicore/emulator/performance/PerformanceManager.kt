package com.omnicore.emulator.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.Locale

/**
 * Device-aware policy selector. It intentionally avoids vendor-specific tweaks,
 * hidden APIs and persistent system changes. The goal is predictable performance
 * across devices rather than fragile benchmark-only tuning.
 */
object PerformanceManager {
    enum class UserMode(val storageValue: String, val label: String) {
        AUTO("auto", "Inteligente"),
        PERFORMANCE("performance", "Desempenho"),
        BALANCED("balanced", "Equilibrado"),
        BATTERY("battery", "Economia")
    }

    enum class RuntimePolicy(val nativeValue: Int, val label: String) {
        SUSTAINED(0, "Sustentável"),
        BALANCED(1, "Equilibrado"),
        LOW_LATENCY(2, "Baixa latência")
    }

    data class DeviceProfile(
        val ramMb: Long,
        val cpuCores: Int,
        val is64Bit: Boolean,
        val mediaPerformanceClass: Int,
        val lowRamDevice: Boolean,
        val maxCpuMhz: Int
    ) {
        val summary: String
            get() = buildString {
                append(cpuCores).append(" núcleos • ")
                append(String.format(Locale.US, "%.1f GB RAM", ramMb / 1024.0)).append(" • ")
                append(if (is64Bit) "64-bit" else "32-bit")
                if (maxCpuMhz > 0) append(" • até ").append(maxCpuMhz).append(" MHz")
                if (mediaPerformanceClass > 0) append(" • MPC ").append(mediaPerformanceClass)
            }
    }

    data class RuntimeConfig(
        val policy: RuntimePolicy,
        val audioBufferBursts: Int,
        val tryExclusiveAudio: Boolean,
        val preferPowerEfficiency: Boolean,
        val aggressiveFramePacing: Boolean,
        val reason: String
    )

    private const val PREFS = "omnicore_performance"
    private const val KEY_MODE = "user_mode"
    @Volatile private var cachedProfile: DeviceProfile? = null

    fun readUserMode(context: Context): UserMode {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, UserMode.AUTO.storageValue)
        return UserMode.entries.firstOrNull { it.storageValue == value } ?: UserMode.AUTO
    }

    fun saveUserMode(context: Context, mode: UserMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.storageValue)
            .apply()
    }

    fun profile(context: Context): DeviceProfile {
        cachedProfile?.let { return it }
        return synchronized(this) {
            cachedProfile ?: computeProfile(context.applicationContext).also { cachedProfile = it }
        }
    }

    private fun computeProfile(context: Context): DeviceProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val ramMb = if (memoryInfo.totalMem > 0) memoryInfo.totalMem / (1024L * 1024L) else 0L
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val performanceClass = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        val lowRam = activityManager?.isLowRamDevice == true
        val maxCpuMhz = detectMaxCpuMhz(cores)
        return DeviceProfile(ramMb, cores, is64Bit, performanceClass, lowRam, maxCpuMhz)
    }

    fun initialConfig(context: Context, thermalStatus: Int = currentThermalStatus(context)): RuntimeConfig {
        val profile = profile(context)
        val selected = readUserMode(context)
        return resolve(selected, profile, thermalStatus)
    }

    fun resolve(mode: UserMode, profile: DeviceProfile, thermalStatus: Int): RuntimeConfig {
        val thermallyConstrained = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val thermallyWarm = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

        if (thermallyConstrained) {
            return RuntimeConfig(
                policy = RuntimePolicy.SUSTAINED,
                audioBufferBursts = 4,
                tryExclusiveAudio = false,
                preferPowerEfficiency = true,
                aggressiveFramePacing = false,
                reason = "proteção térmica ativa"
            )
        }

        return when (mode) {
            UserMode.PERFORMANCE -> RuntimeConfig(
                policy = if (thermallyWarm) RuntimePolicy.BALANCED else RuntimePolicy.LOW_LATENCY,
                audioBufferBursts = if (thermallyWarm) 3 else 2,
                tryExclusiveAudio = !thermallyWarm,
                preferPowerEfficiency = false,
                aggressiveFramePacing = !thermallyWarm,
                reason = if (thermallyWarm) "desempenho limitado pela temperatura" else "modo desempenho selecionado"
            )

            UserMode.BALANCED -> RuntimeConfig(
                policy = RuntimePolicy.BALANCED,
                audioBufferBursts = 3,
                tryExclusiveAudio = false,
                preferPowerEfficiency = false,
                aggressiveFramePacing = false,
                reason = "modo equilibrado selecionado"
            )

            UserMode.BATTERY -> RuntimeConfig(
                policy = RuntimePolicy.SUSTAINED,
                audioBufferBursts = 4,
                tryExclusiveAudio = false,
                preferPowerEfficiency = true,
                aggressiveFramePacing = false,
                reason = "modo economia selecionado"
            )

            UserMode.AUTO -> {
                // Core count alone is not a performance signal: many entry-level SoCs
                // expose eight efficiency-oriented cores. Prefer a conservative default
                // unless Android's Media Performance Class or CPU/RAM headroom is clear.
                val modernPerformanceClass = profile.mediaPerformanceClass >= 34 &&
                    profile.is64Bit &&
                    !profile.lowRamDevice &&
                    profile.ramMb >= 6144
                val strongDevice = profile.is64Bit &&
                    !profile.lowRamDevice &&
                    profile.ramMb >= 6144 &&
                    profile.cpuCores >= 6 &&
                    profile.maxCpuMhz >= 2400
                val constrainedDevice = profile.lowRamDevice ||
                    profile.ramMb in 1L..3071L ||
                    profile.cpuCores <= 4 ||
                    profile.maxCpuMhz in 1..1799

                when {
                    thermallyWarm -> RuntimeConfig(
                        policy = RuntimePolicy.SUSTAINED,
                        audioBufferBursts = 4,
                        tryExclusiveAudio = false,
                        preferPowerEfficiency = true,
                        aggressiveFramePacing = false,
                        reason = "otimizador reduziu a carga por temperatura"
                    )

                    modernPerformanceClass || strongDevice -> RuntimeConfig(
                        policy = RuntimePolicy.LOW_LATENCY,
                        audioBufferBursts = 2,
                        tryExclusiveAudio = true,
                        preferPowerEfficiency = false,
                        aggressiveFramePacing = true,
                        reason = "hardware com margem para baixa latência"
                    )

                    constrainedDevice -> RuntimeConfig(
                        policy = RuntimePolicy.BALANCED,
                        audioBufferBursts = 3,
                        tryExclusiveAudio = false,
                        preferPowerEfficiency = false,
                        aggressiveFramePacing = false,
                        reason = "perfil conservador para hardware limitado"
                    )

                    else -> RuntimeConfig(
                        policy = RuntimePolicy.BALANCED,
                        audioBufferBursts = 3,
                        tryExclusiveAudio = false,
                        preferPowerEfficiency = false,
                        aggressiveFramePacing = false,
                        reason = "perfil equilibrado automático"
                    )
                }
            }
        }
    }

    private fun detectMaxCpuMhz(cpuCores: Int): Int {
        var maxKhz = 0L
        for (cpu in 0 until cpuCores.coerceAtMost(32)) {
            val candidates = listOf(
                "/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq",
                "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq"
            )
            for (path in candidates) {
                val khz = runCatching { java.io.File(path).readText().trim().toLong() }.getOrNull() ?: continue
                if (khz > maxKhz) maxKhz = khz
                break
            }
        }
        return if (maxKhz > 0) (maxKhz / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
    }

    fun currentThermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return PowerManager.THERMAL_STATUS_NONE
        return runCatching {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE
        }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
    }
}
