package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.opencode.acp.config.settings.OpenCodeSettingsState
import kotlinx.coroutines.isActive

/**
 * Throttled infinite animation that replaces `rememberInfiniteTransition` + `animateFloat`
 * to reduce GPU frame pressure on Windows.
 *
 * `rememberInfiniteTransition` requests a new frame every vsync (~60fps). Each frame triggers
 * a Skiko GPU command flush (`DirectContextKt._nFlushAndSubmit`) which can stall the EDT when
 * the GPU driver is under pressure (D3D command queue back-pressure, TDR recovery, DWM
 * composition stalls). By driving the animation from a coroutine with [withFrameNanos] + a
 * time gate, we cap the effective frame rate (configurable via Settings → Tools → Sigil,
 * default 30fps), halving the GPU flush frequency while keeping the animation visually
 * identical for slow effects (glow sweep, context pulse, session shimmer).
 *
 * The returned [State] must be read inside a draw scope (`drawBehind {}` or `Canvas`) to
 * avoid recomposition on every tick — only the draw phase re-executes.
 *
 * @param active When false, the animation is stopped and the state holds [initialValue].
 * @param initialValue Start of the animation range.
 * @param targetValue End of the animation range.
 * @param durationMillis Time for one sweep from [initialValue] to [targetValue].
 * @param repeatMode `Restart`: jump back to [initialValue]. `Reverse`: bounce between initial and target.
 * @param label For debugging / trace labels only. Not used as a composition key.
 * @return A [State<Float>] that animates while [active] is true.
 */
@Composable
fun rememberThrottledInfiniteAnimation(
    active: Boolean,
    initialValue: Float,
    targetValue: Float,
    durationMillis: Int,
    repeatMode: RepeatMode = RepeatMode.Restart,
    label: String = "throttled",
): State<Float> {
    require(durationMillis > 0) { "durationMillis must be positive, got $durationMillis" }

    val state = remember { mutableFloatStateOf(initialValue) }

    // NOTE: animationThrottleFps is captured once at first composition. Changing the
    // setting in Settings → Tools → Sigil takes effect on the next tool window reopen,
    // not live — this is acceptable because the animation is a slow visual effect where
    // a few FPS difference is imperceptible. Live updates would require a StateFlow key
    // on the remember block, adding recomposition overhead for negligible benefit.
    val targetFps = remember { OpenCodeSettingsState.getInstance().animationThrottleFps.coerceIn(15, 60) }
    val frameIntervalNanos = 1_000_000_000L / targetFps

    LaunchedEffect(active, initialValue, targetValue, durationMillis, repeatMode, targetFps) {
        if (!active) {
            state.floatValue = initialValue
            return@LaunchedEffect
        }

        val range = targetValue - initialValue
        val cycleNanos = durationMillis.toLong() * 1_000_000L
        val reverseCycleNanos = cycleNanos * 2
        var lastAppliedFrame = 0L
        var startTimeNanos = 0L

        // The while(isActive) loop relies on LaunchedEffect cancellation to exit.
        // When the composable leaves composition, LaunchedEffect is cancelled,
        // which cancels the withFrameNanos suspension. Compose's frame clock
        // respects coroutine cancellation, so the loop exits cleanly.
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                // Record the first frame as the animation start point so the animation
                // begins at initialValue rather than at a random phase determined by the
                // absolute frameTimeNanos modulo.
                if (startTimeNanos == 0L) startTimeNanos = frameTimeNanos

                // Time gate: skip frames that arrive too soon after the last applied frame.
                if (frameTimeNanos - lastAppliedFrame < frameIntervalNanos) return@withFrameNanos
                lastAppliedFrame = frameTimeNanos

                val elapsed = frameTimeNanos - startTimeNanos

                state.floatValue = when (repeatMode) {
                    RepeatMode.Restart -> {
                        val elapsedInCycle = ((elapsed % cycleNanos).toDouble() / cycleNanos).toFloat()
                        initialValue + range * elapsedInCycle
                    }
                    RepeatMode.Reverse -> {
                        // Triangle wave: 0→1→0 over two half-cycles (period = 2 * durationMillis)
                        val phase = ((elapsed % reverseCycleNanos).toDouble() / cycleNanos).toFloat()
                        val t = if (phase < 1f) phase else 2f - phase
                        initialValue + range * t
                    }
                }
            }
        }
    }

    return state
}