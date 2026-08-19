package com.omnicore.emulator.ui.ps2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.omnicore.emulator.ui.theme.OmniCoreTheme

/** Standalone PS2 settings host. Changes apply to the next PS2 session. */
class PS2SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OmniCoreTheme {
                PS2SettingsDialog(onDismiss = { finish() })
            }
        }
    }
}
