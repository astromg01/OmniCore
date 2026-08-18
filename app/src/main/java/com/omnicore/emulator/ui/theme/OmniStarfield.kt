package com.omnicore.emulator.ui.theme

import android.app.ActivityManager
import android.os.PowerManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

/**
 * Draw-only StarUI background. The animation state is consumed inside Canvas,
 * so frame ticks invalidate the drawing layer instead of recomposing the hub.
 */
@Composable
fun OmniStarfieldBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val activityManager = remember(context) { context.getSystemService(ActivityManager::class.java) }
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    val reducedMotion = remember(activityManager, powerManager) {
        activityManager?.isLowRamDevice == true || powerManager?.isPowerSaveMode == true
    }
    val count = if (reducedMotion) 8 else 18
    val stars = remember(count) {
        val random = Random(0x0C0E2026L)
        List(count) {
            OmniStar(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.75f + random.nextFloat() * 1.55f,
                speed = 0.030f + random.nextFloat() * 0.075f,
                pulse = random.nextFloat()
            )
        }
    }

    if (reducedMotion) {
        Canvas(modifier) {
            stars.forEachIndexed { index, star ->
                val tint = if (index % 5 == 0) Color(0xFF9FE7FF) else Color(0xFFEDE8FF)
                drawCircle(
                    color = tint.copy(alpha = 0.52f),
                    radius = star.radius * density,
                    center = Offset(star.x * size.width, star.y * size.height)
                )
            }
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "OmniStarfield")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OmniStarPhase"
    )

    Canvas(modifier) {
        // Read animation state in DrawScope: draw invalidation only, no full hub recomposition.
        val currentPhase = phase.value
        stars.forEachIndexed { index, star ->
            val yUnit = (star.y + currentPhase * star.speed * 8f) % 1f
            val twinkle = (
                0.47f + 0.22f * sin((currentPhase + star.pulse) * PI * 2.0).toFloat()
            ).coerceIn(0.28f, 0.69f)
            val tint = if (index % 5 == 0) Color(0xFF9FE7FF) else Color(0xFFEDE8FF)
            drawCircle(
                color = tint.copy(alpha = twinkle),
                radius = star.radius * density,
                center = Offset(star.x * size.width, yUnit * size.height)
            )
        }
    }
}
