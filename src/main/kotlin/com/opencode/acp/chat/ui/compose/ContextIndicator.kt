package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.SessionContext
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.model.PressureLevel
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.config.settings.OpenCodeContextSettingsState
import org.jetbrains.jewel.ui.component.Text

// ── Context indicator colors ──────────────────────────────────────────────────

/** Returns the context color for a given usage percentage. */
@Composable
fun contextColorForPercent(percent: Float): Color {
    val colors = ChatTheme.colors
    return when {
        percent < 0f -> colors.accent.contextUnknown
        percent < 50f -> colors.accent.contextGreen
        percent < 75f -> colors.accent.contextYellow
        else -> colors.accent.contextRed
    }
}

// ── Pulsing Alpha State ─────────────────────────────────────────────────────

/**
 * Returns a [State<Float>] that pulses between 1f and 0.5f when [isStreaming] is true,
 * or a constant 1f when not streaming.
 *
 * Uses [rememberThrottledInfiniteAnimation] instead of [rememberInfiniteTransition] to
 * reduce GPU command flush pressure. The throttle FPS is configurable via Settings →
 * Tools → Sigil → "Animation FPS". The state is read inside draw scopes to prevent
 * recomposition on every animation frame.
 */
@Composable
private fun rememberPulsingAlpha(isStreaming: Boolean, pulseMs: Int): State<Float> {
    return if (isStreaming) {
        rememberThrottledInfiniteAnimation(
            active = isStreaming,
            initialValue = 1f,
            targetValue = 0.5f,
            durationMillis = pulseMs,
            repeatMode = RepeatMode.Reverse,
            label = "contextPulse",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
}

// ── Context Indicator ──────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextIndicator(
    state: SessionContextState,
    isStreaming: Boolean,
    onShowDetails: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChatTheme.colors
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts
    val animations = ChatTheme.animations

    val ringSize = dims.contextIndicatorSize

    TooltipArea(
        tooltip = {
            ContextTooltip(state = state, isStreaming = isStreaming)
        },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .height(28.dp)
                .clickable { onClickAction(state, onShowDetails, onRetry) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Ring glyph
            Box(modifier = Modifier.size(ringSize)) {
                when (state) {
                    is SessionContextState.Loading -> {
                        LoadingCircle(sizeDp = ringSize, modifier = Modifier.align(Alignment.Center))
                    }
                    is SessionContextState.Loaded -> {
                        val ctx = state.context
                        val color = contextColorForPercent(ctx.usagePercent)
                        val fillFraction = if (ctx.contextLimit > 0L) {
                            (ctx.usagePercent / 100f).coerceIn(0f, 1f)
                        } else 0f
                        val alphaState = rememberPulsingAlpha(isStreaming, animations.contextPulseMs)

                        // Outer ring for high usage
                        if (ctx.contextLimit > 0L && ctx.usagePercent >= 75f) {
                            Canvas(
                                modifier = Modifier
                                    .size(ringSize + 3.dp)
                                    .align(Alignment.Center)
                            ) {
                                val outerDiameter = (ringSize + 3.dp).toPx()
                                val ringRadius = outerDiameter / 2f - 1.5.dp.toPx()
                                drawCircle(
                                    color = color.copy(alpha = alphaState.value),
                                    radius = ringRadius,
                                    center = center,
                                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }

                        DoughnutRing(
                            fillFraction = fillFraction,
                            fillColor = color,
                            alphaState = alphaState,
                            sizeDp = ringSize,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    is SessionContextState.Error -> {
                        ErrorCircle(
                            retryable = state.retryable,
                            sizeDp = ringSize,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Percent/error label outside on the right, fixed width next to ring
            Column(
                modifier = Modifier
                    .width(dims.contextIndicatorTextWidth)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                val (labelText, labelColor) = when (state) {
                    is SessionContextState.Loading -> "…" to colors.component.tooltipMuted
                    is SessionContextState.Loaded -> {
                        val pct = state.context.usagePercent.coerceAtLeast(0f)
                        val pressure = state.context.pressure
                        // Show pressure badge if pressure level meets the notification threshold
                        val pressureSuffix = pressureBadgeText(pressure?.pressureLevel)
                        "${pct.toInt()}%$pressureSuffix" to contextColorForPercent(pct)
                    }
                    is SessionContextState.Error -> {
                        if (state.retryable) "!" to colors.accent.contextYellow
                        else "✕" to colors.accent.contextRed
                    }
                }

                Text(
                    text = labelText,
                    fontSize = fonts.contextPercent,
                    fontWeight = ChatTheme.fontWeights.contextPercent,
                    color = labelColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun onClickAction(state: SessionContextState, onShowDetails: () -> Unit, onRetry: () -> Unit) {
    when (state) {
        is SessionContextState.Loaded -> onShowDetails()
        is SessionContextState.Error -> if (state.retryable) onRetry() else onShowDetails()
        is SessionContextState.Loading -> { /* no-op */ }
    }
}

// ── Loading Circle ──────────────────────────────────────────────────────────

@Composable
private fun LoadingCircle(sizeDp: Dp, modifier: Modifier = Modifier) {
    val colors = ChatTheme.colors
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(colors.component.indicatorBg)
            .border(1.dp, colors.component.tooltipBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⋯",
            fontSize = ChatTheme.fonts.contextTooltipValue,
            color = colors.component.tooltipMuted
        )
    }
}

// ── Doughnut Ring (Canvas-based) ────────────────────────────────────────────

@Composable
private fun DoughnutRing(
    fillFraction: Float,
    fillColor: Color,
    alphaState: State<Float>,
    sizeDp: Dp,
    modifier: Modifier = Modifier
) {
    val colors = ChatTheme.colors
    val strokeWidth = ChatTheme.dims.contextRingStroke

    Box(modifier = modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(sizeDp)) {
            val stroke = strokeWidth.toPx()
            val alpha = alphaState.value
            val diameter = sizeDp.toPx()
            val radius = diameter / 2f

            // Background ring (track)
            drawCircle(
                color = colors.component.indicatorBg,
                radius = radius - stroke / 2f,
                center = center,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Progress arc (fills clockwise from top)
            if (fillFraction > 0f) {
                drawArc(
                    color = fillColor.copy(alpha = alpha),
                    startAngle = -90f, // Start from top
                    sweepAngle = 360f * fillFraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(diameter - stroke, diameter - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // Outer border ring
            drawCircle(
                color = colors.component.tooltipBorder,
                radius = radius,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

// ── Error Circle ───────────────────────────────────────────────────────────

@Composable
private fun ErrorCircle(retryable: Boolean, sizeDp: Dp, modifier: Modifier = Modifier) {
    val colors = ChatTheme.colors
    val borderColor = if (retryable) colors.accent.contextYellow else colors.accent.contextRed
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(colors.component.indicatorBg)
            .border(1.dp, borderColor, CircleShape)
    )
}

// ── Tooltip ────────────────────────────────────────────────────────────────

@Composable
private fun ContextTooltip(
    state: SessionContextState,
    isStreaming: Boolean
) {
    val colors = ChatTheme.colors
    val dims = ChatTheme.dims
    val shapes = ChatTheme.shapes
    val fonts = ChatTheme.fonts

    Box(
        modifier = Modifier
            .width(dims.contextTooltipWidth)
            .clip(shapes.contextTooltipCornerRadius)
            .background(colors.component.tooltipBg)
            .border(1.dp, colors.component.tooltipBorder, shapes.contextTooltipCornerRadius)
            .padding(dims.contextTooltipPadding)
    ) {
        when (state) {
            is SessionContextState.Loading -> {
                Text("Loading context...", fontSize = fonts.contextTooltipValue, color = colors.component.tooltipMuted)
            }
            is SessionContextState.Loaded -> {
                val ctx = state.context
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Model line
                    Text(
                        text = "${ctx.providerName} / ${ctx.modelName}".ifBlank { "Unknown model" },
                        fontSize = fonts.contextTooltipValue,
                        fontWeight = FontWeight.Medium,
                        color = colors.component.tooltipText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    // Usage line
                    Text(
                        text = if (ctx.contextLimit > 0L) {
                            "Usage: ${formatTooltipPercent(ctx.usagePercent)}% (${formatTooltipTokens(ctx.totalTokens)} / ${formatTooltipTokens(ctx.contextLimit)})"
                        } else {
                            "Usage: ${formatTooltipTokens(ctx.totalTokens)} tokens (limit unknown)"
                        },
                        fontSize = fonts.contextTooltipLabel,
                        color = contextColorForPercent(ctx.usagePercent)
                    )
                    // Cost line
                    Text(
                        text = "Cost: ${formatTooltipCost(ctx.totalCost)}",
                        fontSize = fonts.contextTooltipLabel,
                        color = colors.component.tooltipMuted
                    )
                    if (isStreaming) {
                        Text(
                            text = "Updating...",
                            fontSize = fonts.contextTooltipSub,
                            color = colors.component.tooltipMuted
                        )
                    }
                }
            }
            is SessionContextState.Error -> {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Context unavailable",
                        fontSize = fonts.contextTooltipValue,
                        fontWeight = FontWeight.Medium,
                        color = if (state.retryable) colors.accent.contextYellow else colors.accent.contextRed
                    )
                    Text(
                        text = state.message,
                        fontSize = fonts.contextTooltipLabel,
                        color = colors.component.tooltipMuted,
                        maxLines = 2
                    )
                    if (state.retryable) {
                        Text(
                            text = "Click to retry",
                            fontSize = fonts.contextTooltipLabel,
                            color = colors.accent.contextGreen
                        )
                    }
                }
            }
        }
    }
}

// ── Format helpers for tooltip ─────────────────────────────────────────────

private fun formatTooltipPercent(percent: Float): String {
    return if (percent >= 100f) "${percent.toInt()}%" else "${String.format("%.1f", percent)}%"
}

private fun formatTooltipTokens(tokens: Long): String {
    return when {
        tokens == 0L -> "0"
        tokens < 1000L -> tokens.toString()
        tokens < 1_000_000L -> "${String.format("%.1f", tokens / 1000.0)}k"
        else -> "${String.format("%.1f", tokens / 1_000_000.0)}M"
    }
}

private fun formatTooltipCost(cost: Double): String {
    return if (cost == 0.0) "$0.00" else "$${String.format("%.4f", cost)}"
}

/**
 * Returns a short pressure badge suffix to append to the percent label, or empty string
 * if pressure is null or below the user's configured notification threshold.
 *
 * The badge uses a single character: "▲" for HIGH, "⚠" for CRITICAL, "·" for ELEVATED.
 * The threshold is read from settings (NEVER / ELEVATED / HIGH / CRITICAL).
 */
@Composable
private fun pressureBadgeText(level: PressureLevel?): String {
    if (level == null) return ""
    val threshold = remember { OpenCodeContextSettingsState.getInstance().pressureNotificationThreshold }
    val thresholdLevel = when (threshold) {
        "NEVER" -> null
        "ELEVATED" -> PressureLevel.ELEVATED
        "CRITICAL" -> PressureLevel.CRITICAL
        else -> PressureLevel.HIGH
    }
    if (thresholdLevel == null) return ""
    // Show badge if the current level is >= the threshold level (ordinal comparison)
    if (level.ordinal < thresholdLevel.ordinal) return ""
    return when (level) {
        PressureLevel.COMFORTABLE -> ""
        PressureLevel.ELEVATED -> " ·"
        PressureLevel.HIGH -> " ▲"
        PressureLevel.CRITICAL -> " ⚠"
    }
}
