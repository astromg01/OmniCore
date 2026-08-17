package com.omnicore.emulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.performance.PerformanceManager
import com.omnicore.emulator.ui.OmniCoreV3App
import com.omnicore.emulator.ui.theme.OmniCoreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        warmRuntimeCaches()
        setContent {
            OmniCoreTheme {
                OmniCoreV3App()
            }
        }
    }

    private fun warmRuntimeCaches() {
        val appContext = applicationContext
        Thread({
            runCatching { NativeBridge.hasPs1Core() }
            runCatching { PerformanceManager.profile(appContext) }
        }, "OmniCore-AppWarmup").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }
}
