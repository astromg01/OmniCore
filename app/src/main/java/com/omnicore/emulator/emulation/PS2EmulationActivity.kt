package com.omnicore.emulator.emulation

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.omnicore.emulator.core.ps2.PS2Backend
import com.omnicore.emulator.core.ps2.PS2NativeBridge
import com.omnicore.emulator.performance.PS2SmartPerf

/**
 * PS2 process/lifecycle foundation.
 *
 * This screen intentionally does not boot games until a backend is integrated
 * behind PS2Backend. It exists to validate isolated native loading and the
 * device capability envelope without touching PS1/N64 runtime state.
 */
class PS2EmulationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 8, 15))
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        val probe = runCatching { PS2NativeBridge.probe() }
        val descriptor = runCatching { PS2NativeBridge.descriptor() }
            .getOrElse { "native probe unavailable: ${it.javaClass.simpleName}" }

        status.text = probe.fold(
            onSuccess = { p ->
                val caps = PS2Backend.Capabilities(
                    available = false,
                    arm64Jit = p.architecture == "arm64-v8a" && p.pointerBits == 64,
                    vulkan = p.vulkanLoader,
                    gles3 = p.gles3Build,
                    hleBios = false,
                    externalBios = false,
                    saveStates = false,
                    backendVersion = "foundation-only",
                    notes = "Backend adapter not integrated yet."
                )
                val plan = PS2SmartPerf.initial(this, caps)
                buildString {
                    appendLine("OmniCore PS2 Foundation")
                    appendLine()
                    appendLine(descriptor)
                    appendLine("Process: com.omnicore.emulator:ps2")
                    appendLine("Arch: ${p.architecture} / ${p.pointerBits}-bit")
                    appendLine("Android API: ${p.apiLevel}")
                    appendLine("Page size: ${p.pageSize}")
                    appendLine("Vulkan loader: ${if (p.vulkanLoader) "yes" else "no"}")
                    appendLine("GLES3 build path: ${if (p.gles3Build) "yes" else "no"}")
                    appendLine()
                    appendLine("SmartPerf seed: ${plan.mode}")
                    appendLine("Renderer preference: ${plan.renderer}")
                    appendLine("Quality floor: ${plan.qualityFloorScale}x")
                    appendLine("Dynamic resolution: disabled")
                    appendLine("Cycle skipping: disabled")
                    appendLine()
                    append("Foundation ready. PS2 gameplay backend is not enabled yet.")
                }
            },
            onFailure = { error ->
                "PS2 FOUNDATION PROBE FAILED\n${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            }
        )
    }
}
