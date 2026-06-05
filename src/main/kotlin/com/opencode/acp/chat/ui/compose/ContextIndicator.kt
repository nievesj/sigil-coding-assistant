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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.chat.model.SessionContext
import com.opencode.acp.chat.model.SessionContextState
import org.jetbrains.jewel.ui.component.Text

// ── Context indicator colors ──────────────────────────────────────────────────

private val ContextGreen = Color(0xFF6BBE50)
private val ContextYellow = Color(0xFFE5A617)
private val ContextRed = Color(0xFFE5534B)
private val ContextUnknown = Color(0xFF808080)
private val IndicatorBg = Color(0xFF3C3C3C)
private val TooltipBg = Color(0xFF2B2B2B)
private val TooltipBorder = Color(0xFF4A4A4A)
private val TooltipText = Color(0xFFCCCCCC)
private val TooltipMuted = Color(0xFF999999)

/** Returns the context color for a given usage percentage. */
fun contextColorForPercent(percent: Float): Color {
    return when {
        percent < 0f -> ContextUnknown
        percent < 50f -> ContextGreen
        percent < 75f -> ContextYellow
        else -> ContextRed
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
    // Pulsing animation while streaming
    val infiniteTransition = rememberInfiniteTransition(label = "contextPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "contextPulseAlpha"
    )

    val sizeDp = ChatConstants.CONTEXT_INDICATOR_SIZE_DP

    TooltipArea(
        tooltip = {
            ContextTooltip(state = state, isStreaming = isStreaming)
        },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
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

                    // Outer ring(s) for high usage
                    if (ctx.contextLimit > 0L) {
                        when {
                            ctx.isOverflow -> {
                                // Double pulsing ring for context overflow
                                Canvas(
                                    modifier = Modifier
                                        .size((sizeDp + 6).dp)
                                        .align(Alignment.Center)
                                ) {
                                    val outerDiameter = (sizeDp + 6).dp.toPx()
                                    val ringRadius = outerDiameter / 2f - 2.dp.toPx()
                                    drawCircle(
                                        color = color.copy(alpha = alpha),
                                        radius = ringRadius,
                                        center = center,
                                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                }
                                Canvas(
                                    modifier = Modifier
                                        .size((sizeDp + 10).dp)
                                        .align(Alignment.Center)
                                ) {
                                    val outerDiameter = (sizeDp + 10).dp.toPx()
                                    val ringRadius = outerDiameter / 2f - 2.dp.toPx()
                                    drawCircle(
                                        color = color.copy(alpha = alpha * 0.5f),
                                        radius = ringRadius,
                                        center = center,
                                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                }
                            }
                            ctx.usagePercent >= 75f -> {
                                // Single ring for high usage
                                Canvas(
                                    modifier = Modifier
                                        .size((sizeDp + 4).dp)
                                        .align(Alignment.Center)
                                ) {
                                    val outerDiameter = (sizeDp + 4).dp.toPx()
                                    val ringRadius = outerDiameter / 2f - 2.dp.toPx()
                                    drawCircle(
                                        color = color.copy(alpha = alpha),
                                        radius = ringRadius,
                                        center = center,
                                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                }
                            }
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
private fun LoadingCircle(sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(IndicatorBg)
            .border(1.dp, TooltipBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⋯",
            fontSize = 10.sp,
            color = TooltipMuted
        )
    }
}

// ── Doughnut Ring (Canvas-based) ────────────────────────────────────────────

@Composable
private fun DoughnutRing(
    fillFraction: Float,
    fillColor: Color,
    sizeDp: Int,
    modifier: Modifier = Modifier
) {
    val displayText = "${(fillFraction * 100).toInt()}"

    Box(modifier = modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val diameter = sizeDp.dp.toPx()
            val radius = diameter / 2f
            val stroke = 3.dp.toPx() // Thick stroke for doughnut effect
            val innerRadius = radius - stroke

            // Background ring (track)
            drawCircle(
                color = IndicatorBg,
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
                color = TooltipBorder,
                radius = radius,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Center text
        Text(
            text = displayText,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1
        )
    }
}

// ── Error Circle ───────────────────────────────────────────────────────────

@Composable
private fun ErrorCircle(retryable: Boolean, sizeDp: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(IndicatorBg)
            .border(1.dp, if (retryable) ContextYellow else ContextRed, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (retryable) "!" else "✕",
            fontSize = if (retryable) 11.sp else 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (retryable) ContextYellow else ContextRed
        )
    }
}

// ── Tooltip ────────────────────────────────────────────────────────────────

@Composable
private fun ContextTooltip(
    state: SessionContextState,
    isStreaming: Boolean
) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(TooltipBg)
            .border(1.dp, TooltipBorder, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        when (state) {
            is SessionContextState.Loading -> {
                Text("Loading context...", fontSize = 11.sp, color = TooltipMuted)
            }
            is SessionContextState.Loaded -> {
                val ctx = state.context
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Model line
                    Text(
                        text = "${ctx.providerName} / ${ctx.modelName}".ifBlank { "Unknown model" },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TooltipText,
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
                        fontSize = 10.sp,
                        color = contextColorForPercent(ctx.usagePercent)
                    )
                    if (ctx.isOverflow && ctx.contextLimit > 0L) {
                        Text(
                            text = "⚠ Context overflow — compact to free space",
                            fontSize = 10.sp,
                            color = ContextRed,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    // Cost line
                    Text(
                        text = "Cost: ${formatTooltipCost(ctx.totalCost)}",
                        fontSize = 10.sp,
                        color = TooltipMuted
                    )
                    if (isStreaming) {
                        Text(
                            text = "Updating...",
                            fontSize = 9.sp,
                            color = TooltipMuted
                        )
                    }
                }
            }
            is SessionContextState.Error -> {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Context unavailable",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (state.retryable) ContextYellow else ContextRed
                    )
                    Text(
                        text = state.message,
                        fontSize = 10.sp,
                        color = TooltipMuted,
                        maxLines = 2
                    )
                    if (state.retryable) {
                        Text(
                            text = "Click to retry",
                            fontSize = 10.sp,
                            color = ContextGreen
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
