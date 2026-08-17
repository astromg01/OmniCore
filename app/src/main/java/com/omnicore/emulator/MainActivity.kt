package com.omnicore.emulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.omnicore.emulator.ui.OmniCoreApp
import com.omnicore.emulator.ui.theme.OmniCoreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OmniCoreTheme {
                OmniCoreApp()
            }
        }
    }
}
