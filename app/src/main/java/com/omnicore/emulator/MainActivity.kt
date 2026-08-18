package com.omnicore.emulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
