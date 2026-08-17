package com.omnicore.emulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.omnicore.emulator.core.n64.N64NativeBridge
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.performance.PerformanceManager
import com.omnicore.emulator.settings.N64PerformanceProfile
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
            // Both consoles warm only their own probes/profiles. Running this on
            // a low-priority worker keeps first navigation taps off native dlopen
            // and hardware-classification work.
            runCatching { PerformanceManager.profile(appContext) }
            runCatching { NativeBridge.hasPs1Core() }
            runCatching { N64PerformanceProfile.detect(appContext) }
            runCatching { N64NativeBridge.hasCore() }
        }, "OmniCore-AppWarmup").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
            start()
        }
    }
}
