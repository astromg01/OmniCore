package com.omnicore.emulator.ui.theme

import android.app.ActivityManager
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.util.Random

private data class OmniStar(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float
)

/**
 * Lightweight StarUI background for the hub.
 *
 * Alpha 5 kept an infinite animation alive behind every tab. Even though that
 * animation only invalidated the Canvas, it still forced continuous GPU work
 * underneath translucent cards while the user scrolled. The hub now uses a
 * deterministic static star layer so scrolling owns the frame budget.
 */
@Composable
fun OmniStarfieldBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val activityManager = remember(context) { context.getSystemService(ActivityManager::class.java) }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val reducedDetail = remember(activityManager, powerManager) {
        activityManager?.isLowRamDevice == true || powerManager?.isPowerSaveMode == true
    }
    val count = if (reducedDetail) 6 else 10
    val stars = remember(count) {
        val random = Random(0x0C0E2026L)
        List(count) {
            OmniStar(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.75f + random.nextFloat() * 1.35f,
                alpha = 0.32f + random.nextFloat() * 0.25f
            )
        }
    }

    Canvas(modifier) {
        stars.forEachIndexed { index, star ->
            val tint = if (index % 5 == 0) Color(0xFF9FE7FF) else Color(0xFFEDE8FF)
            drawCircle(
                color = tint.copy(alpha = star.alpha),
                radius = star.radius * density,
                center = Offset(star.x * size.width, star.y * size.height)
            )
        }
    }
}
