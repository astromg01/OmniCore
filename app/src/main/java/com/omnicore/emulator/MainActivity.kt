package com.omnicore.emulator

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.omnicore.emulator.achievements.AchievementBanner
import com.omnicore.emulator.achievements.OmniAchievements
import com.omnicore.emulator.core.n64.N64Diagnostics
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.performance.PerformanceManager
import com.omnicore.emulator.ui.OmniCoreV4App
import com.omnicore.emulator.ui.theme.OmniCoreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        warmSafeMainRuntimeCaches()
        setContent {
            OmniCoreTheme {
                OmniCoreV4App()
            }
        }
        recordLaunchAchievement()
    }

    override fun onResume() {
        super.onResume()
        // Android records process-death metadata asynchronously. Waiting a short
        // moment lets the hub explain a :n64 native crash immediately after the
        // game Activity disappears, with no PC/ADB required.
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val report = N64Diagnostics.consumeRecentProcessExit(this) ?: return@postDelayed
            AlertDialog.Builder(this)
                .setTitle("Diagnóstico Nintendo 64")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }, 850L)
    }

    private fun recordLaunchAchievement() {
        Thread({
            val unlock = runCatching { OmniAchievements.unlock(this, "first_light") }.getOrNull()
            if (unlock != null) runOnUiThread {
                window.decorView.postDelayed({ AchievementBanner.show(this, unlock) }, 650L)
            }
        }, "OmniCore-AchievementInit").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    private fun warmSafeMainRuntimeCaches() {
        val appContext = applicationContext
        Thread({
            // Only proven main-process components are warmed here. Nintendo 64
            // probes/native loading belong to the isolated :n64 process so a
            // driver/core failure cannot take the OmniCore hub down.
            runCatching { PerformanceManager.profile(appContext) }
            runCatching { NativeBridge.hasPs1Core() }
        }, "OmniCore-AppWarmup").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }
}
