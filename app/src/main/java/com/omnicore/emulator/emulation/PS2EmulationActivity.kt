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

/** PS2 process/lifecycle bring-up screen. Game boot remains disabled until adapter wiring is validated. */
class PS2EmulationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(7, 8, 15)) }
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
                    available = p.playBackend,
                    arm64Jit = p.playBackend && p.architecture == "arm64-v8a" && p.pointerBits == 64,
                    vulkan = p.vulkanLoader,
                    gles3 = p.gles3Build,
                    hleBios = p.playBackend,
                    externalBios = p.playBackend,
                    saveStates = p.playBackend,
                    backendVersion = if (p.playBackend) p.playRevision.take(12) else "not-packaged",
                    notes = if (p.playBackend) "Play! binary load probe passed." else "Backend binary not packaged in this build."
                )
                val plan = PS2SmartPerf.initial(this, caps)
                buildString {
                    appendLine("OmniCore PS2 — Backend Bring-up 1")
                    appendLine()
                    appendLine(descriptor)
                    appendLine("Process: com.omnicore.emulator:ps2")
                    appendLine("Arch: ${p.architecture} / ${p.pointerBits}-bit")
                    appendLine("Android API: ${p.apiLevel}")
                    appendLine("Page size: ${p.pageSize}")
                    appendLine("Vulkan loader: ${if (p.vulkanLoader) "yes" else "no"}")
                    appendLine("GLES3 build path: ${if (p.gles3Build) "yes" else "no"}")
                    appendLine("Play backend: ${if (p.playBackend) "READY" else "not packaged"}")
                    appendLine("Play revision: ${p.playRevision.take(12)}")
                    appendLine()
                    appendLine("SmartPerf seed: ${plan.mode}")
                    appendLine("Renderer preference: ${plan.renderer}")
                    appendLine("Quality floor: ${plan.qualityFloorScale}x")
                    appendLine("Dynamic resolution: disabled")
                    appendLine("Cycle skipping: disabled")
                    appendLine()
                    append(
                        if (p.playBackend) {
                            "Backend binary validated. Next gate: OmniCore adapter lifecycle + first legal image boot."
                        } else {
                            "Foundation only. Gameplay backend is not enabled in this package."
                        }
                    )
                }
            },
            onFailure = { error ->
                "PS2 BACKEND PROBE FAILED\n${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            }
        )
    }
}
