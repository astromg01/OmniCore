package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Color
import android.view.View

/**
 * Alpha 6 classic boot hand-off.
 *
 * The previous OmniCore-made PS2-era animation is intentionally removed now
 * that the PCSX2 backend can execute the user's real BIOS. This view stays only
 * as the Activity's lifecycle gate and immediately yields to the native BIOS.
 */
class PS2ClassicBootView(context: Context) : View(context) {
    private var pending: Runnable? = null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start(onComplete: () -> Unit) {
        cancel()
        visibility = VISIBLE
        val runnable = Runnable { onComplete() }
        pending = runnable
        post(runnable)
    }

    fun cancel() {
        pending?.let { removeCallbacks(it) }
        pending = null
    }

    override fun onDetachedFromWindow() {
        cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val DURATION_MS = 0L
    }
}
