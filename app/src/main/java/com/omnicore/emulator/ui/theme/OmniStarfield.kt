package com.omnicore.emulator.ui.theme

import android.app.ActivityManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

private data class OmniStar(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val pulse: Float
)

@Composable
fun OmniStarfieldBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val activityManager = remember(context) { context.getSystemService(ActivityManager::class.java) }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val reducedMotion = remember(activityManager, powerManager) {
        activityManager?.isLowRamDevice == true || powerManager?.isPowerSaveMode == true
    }
    val count = if (reducedMotion) 9 else 24
    val stars = remember(count) {
        val random = Random(0x0C0E2026L)
        List(count) {
            OmniStar(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.7f + random.nextFloat() * 1.7f,
                speed = 0.035f + random.nextFloat() * 0.09f,
                pulse = random.nextFloat()
            )
        }
    }
    val phase by produceState(initialValue = 0f, reducedMotion) {
        if (reducedMotion) {
            value = 0f
            return@produceState
        }
        val started = SystemClock.elapsedRealtime()
        while (true) {
            value = ((SystemClock.elapsedRealtime() - started) % 12000L) / 12000f
            delay(50L)
        }
    }

    Canvas(modifier) {
        stars.forEachIndexed { index, star ->
            val yUnit = (star.y + phase * star.speed * 8f) % 1f
            val twinkle = if (reducedMotion) 0.58f else {
                (0.46f + 0.24f * sin((phase + star.pulse) * PI * 2.0).toFloat()).coerceIn(0.24f, 0.72f)
            }
            val tint = if (index % 5 == 0) Color(0xFF9FE7FF) else Color(0xFFEDE8FF)
            drawCircle(
                color = tint.copy(alpha = twinkle),
                radius = star.radius * density,
                center = Offset(star.x * size.width, yUnit * size.height)
            )
        }
    }
}
