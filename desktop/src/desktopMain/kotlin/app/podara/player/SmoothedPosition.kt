package app.podara.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * Smooths a value that only updates a few times a second.
 *
 * mpv's position is polled every 250 ms, so binding a slider straight to it makes
 * playback advance in visible quarter-second steps. This chases the real value
 * instead: each frame it closes a fraction of the remaining gap, so the motion
 * reads as continuous while still converging on the truth within a few frames.
 *
 * A jump is applied immediately rather than eased. During ordinary playback the
 * gap per update is `pollInterval / duration` — around 0.0001 of the bar for a
 * half-hour episode — while a seek moves it by an arbitrary amount. Treating
 * those two cases differently is what stops a seek from reading as lag: the
 * audio moves at once, so the thumb must too.
 */
@Composable
internal fun rememberSmoothedFloat(
    target: Float,
    responsiveness: Float = 0.35f,
    epsilon: Float = 1e-4f,
    /** A change larger than this is a seek or a track change, not playback. */
    snapThreshold: Float = 0.01f
): State<Float> {
    val smoothed = remember { mutableFloatStateOf(target) }

    androidx.compose.runtime.LaunchedEffect(target) {
        val current = smoothed.floatValue
        if (abs(target - current) > snapThreshold) {
            smoothed.floatValue = target
            return@LaunchedEffect
        }
        while (abs(smoothed.floatValue - target) > epsilon) {
            withFrameNanos {
                smoothed.floatValue = smoothStep(smoothed.floatValue, target, responsiveness)
            }
        }
        smoothed.floatValue = target
    }

    return smoothed
}

/**
 * Exponential smoothing step, separated from composition so its behaviour is
 * directly testable.
 *
 * @return the next value after moving [fraction] of the way from [current] to
 *   [target].
 */
internal fun smoothStep(current: Float, target: Float, fraction: Float): Float {
    if (hasConverged(current, target)) return target
    return current + (target - current) * fraction
}

/** Whether smoothing has effectively reached [target]. */
internal fun hasConverged(current: Float, target: Float, epsilon: Float = 1e-4f): Boolean =
    abs(target - current) <= epsilon