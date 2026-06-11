package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.SessionContext
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.ui.theme.ChatTheme
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
    val shapes = ChatTheme.shapes
    val animations = ChatTheme.animations

    // Pulsing animation while streaming
    val infiniteTransition = rememberInfiniteTransition(label = "contextPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = animations.contextPulseMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "contextPulseAlpha"
    )

    val sizeDp = dims.contextIndicatorSize

    TooltipArea(
        tooltip = {
            ContextTooltip(state = state, isStreaming = isStreaming)
        },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(sizeDp)
                .clickable { onClickAction(state, onShowDetails, onRetry) }
        ) {
            when (state) {
                is SessionContextState.Loading -> {
                    LoadingCircle(sizeDp)
                }
                is SessionContextState.Loaded -> {
                    val ctx = state.context
                    val color = contextColorForPercent(ctx.usagePercent)
                    val fillFraction = if (ctx.contextLimit > 0L) {
                        (ctx.usagePercent / 100f).coerceIn(0f, 1f)
                    } else 0f
                    val alpha = if (isStreaming) pulseAlpha else 1f

                    // Outer ring for high usage
                    if (ctx.contextLimit > 0L && ctx.usagePercent >= 75f) {
                        Canvas(
                            modifier = Modifier
                                .size(sizeDp + 4.dp)
                                .align(Alignment.Center)
                        ) {
                            val outerDiameter = (sizeDp + 4.dp).toPx()
                            val ringRadius = outerDiameter / 2f - 2.dp.toPx()
                            drawCircle(
                                color = color.copy(alpha = alpha),
                                radius = ringRadius,
                                center = center,
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }

                    DoughnutRing(
                        fillFraction = fillFraction,
                        fillColor = color.copy(alpha = alpha),
                        sizeDp = sizeDp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is SessionContextState.Error -> {
                    ErrorCircle(
                        retryable = state.retryable,
                        sizeDp = sizeDp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
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
private fun LoadingCircle(sizeDp: Dp) {
    val colors = ChatTheme.colors
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(colors.component.indicatorBg)
            .border(1.dp, colors.component.tooltipBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⋯",
            fontSize = ChatTheme.fonts.contextPercent,
            color = colors.component.tooltipMuted
        )
    }
}

// ── Doughnut Ring (Canvas-based) ────────────────────────────────────────────

@Composable
private fun DoughnutRing(
    fillFraction: Float,
    fillColor: Color,
    sizeDp: Dp,
    modifier: Modifier = Modifier
) {
    val colors = ChatTheme.colors
    val displayText = "${(fillFraction * 100).toInt()}"

    Box(modifier = modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(sizeDp)) {
            val diameter = sizeDp.toPx()
            val radius = diameter / 2f
            val stroke = 3.dp.toPx() // Thick stroke for doughnut effect
            val innerRadius = radius - stroke

            // Background ring (track)
            drawCircle(
                color = colors.component.indicatorBg,
                radius = innerRadius,
                center = center,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Progress arc (fills clockwise from top)
            if (fillFraction > 0f) {
                drawArc(
                    color = fillColor,
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

        // Center text
        Text(
            text = displayText,
            fontSize = ChatTheme.fonts.contextErrorBadge,
            fontWeight = ChatTheme.fontWeights.contextPercent,
            color = Color.White,
            maxLines = 1
        )
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
            .border(1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (retryable) "!" else "✕",
            fontSize = if (retryable) ChatTheme.fonts.contextTooltipValue else ChatTheme.fonts.contextTooltipSub,
            fontWeight = FontWeight.Bold,
            color = borderColor
        )
    }
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
