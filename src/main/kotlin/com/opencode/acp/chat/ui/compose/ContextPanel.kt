package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.SessionContext
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.chat.model.SessionContextState
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.foundation.theme.JewelTheme

// ── Context Panel (sidebar tab content) ────────────────────────────────────

@Composable
fun ContextPanel(
    state: SessionContextState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is SessionContextState.Loading -> {
            LoadingContent(modifier = modifier)
        }
        is SessionContextState.Loaded -> {
            ContextDetails(context = state.context, modifier = modifier)
        }
        is SessionContextState.Error -> {
            ErrorContent(message = state.message, retryable = state.retryable, onRetry = onRetry, modifier = modifier)
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading context...",
            fontSize = ChatTheme.fonts.contextPanelTitle,
            color = JewelTheme.globalColors.text.disabled
        )
    }
}

@Composable
private fun ErrorContent(message: String, retryable: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Failed to load context",
            fontSize = ChatTheme.fonts.contextPanelTitle,
            fontWeight = FontWeight.Medium,
            color = ChatTheme.colors.accent.red
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            fontSize = ChatTheme.fonts.contextDetailValue,
            color = JewelTheme.globalColors.text.disabled,
            maxLines = 2
        )
        if (retryable) {
            Spacer(Modifier.height(8.dp))
            Link("Retry", onClick = onRetry)
        }
    }
}

@Composable
private fun ContextDetails(context: SessionContext, modifier: Modifier = Modifier) {
    val sectionColor = ChatTheme.colors.component.contextPanelValue
    val labelColor = ChatTheme.colors.component.contextPanelLabel
    val valueColor = ChatTheme.colors.component.contextPanelValue
    val separator = ChatTheme.colors.component.contextPanelSeparator
    val progressBg = ChatTheme.colors.component.contextProgressBarBg

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── Model section ──
        SectionHeader("Model", sectionColor)
        DetailRow(label = "Provider", value = context.providerName.ifBlank { "Unknown" }, labelColor, valueColor)
        DetailRow(label = "Model", value = context.modelName.ifBlank { "Unknown" }, labelColor, valueColor)

        Spacer(Modifier.height(10.dp))
        HorizontalSeparator(separator)
        Spacer(Modifier.height(10.dp))

        // ── Context Usage section ──
        SectionHeader("Context Usage", sectionColor)
        Spacer(Modifier.height(4.dp))

        UsageBar(
            usagePercent = context.usagePercent,
            contextLimit = context.contextLimit,
            totalTokens = context.totalTokens,
            progressBg = progressBg
        )

        Spacer(Modifier.height(10.dp))
        HorizontalSeparator(separator)
        Spacer(Modifier.height(10.dp))

        // ── Tokens section ──
        SectionHeader("Tokens", sectionColor)
        DetailRow(label = "Input", value = formatPanelTokens(context.inputTokens), labelColor, valueColor)
        DetailRow(label = "Output", value = formatPanelTokens(context.outputTokens), labelColor, valueColor)
        if (context.reasoningTokens > 0) {
            DetailRow(label = "Reasoning", value = formatPanelTokens(context.reasoningTokens), labelColor, valueColor)
        }
        if (context.cacheReadTokens > 0) {
            DetailRow(label = "Cache Read", value = formatPanelTokens(context.cacheReadTokens), labelColor, valueColor)
        }
        if (context.cacheWriteTokens > 0) {
            DetailRow(label = "Cache Write", value = formatPanelTokens(context.cacheWriteTokens), labelColor, valueColor)
        }
        DetailRow(label = "Total", value = formatPanelTokens(context.totalTokens), labelColor, valueColor, bold = true)

        Spacer(Modifier.height(10.dp))
        HorizontalSeparator(separator)
        Spacer(Modifier.height(10.dp))

        // ── Cost section ──
        SectionHeader("Cost", sectionColor)
        DetailRow(label = "Total", value = formatPanelCost(context.totalCost), labelColor, valueColor, bold = true)

        Spacer(Modifier.height(10.dp))
        HorizontalSeparator(separator)
        Spacer(Modifier.height(10.dp))

        // ── Changes section ──
        if (context.additions > 0 || context.deletions > 0 || context.filesModified > 0) {
            SectionHeader("Changes", sectionColor)
            DetailRow(label = "Files", value = "${context.filesModified}", labelColor, valueColor)
            DetailRow(label = "Added", value = "+${context.additions}", labelColor, ChatTheme.colors.accent.codeAdded)
            DetailRow(label = "Removed", value = "-${context.deletions}", labelColor, ChatTheme.colors.accent.red)

            Spacer(Modifier.height(10.dp))
            HorizontalSeparator(separator)
            Spacer(Modifier.height(10.dp))
        }

        // ── Session section ──
        SectionHeader("Session", sectionColor)
        DetailRow(label = "Messages", value = "${context.messageCount}", labelColor, valueColor)
        DetailRow(label = "User", value = "${context.userMessageCount}", labelColor, valueColor)
        DetailRow(label = "Assistant", value = "${context.assistantMessageCount}", labelColor, valueColor)
        if (context.sessionCreated > 0) {
            DetailRow(label = "Created", value = formatRelativeTime(context.sessionCreated), labelColor, valueColor)
        }
        if (context.lastUpdated > 0) {
            DetailRow(label = "Updated", value = formatRelativeTime(context.lastUpdated), labelColor, valueColor)
        }
    }
}

// ── Sub-components ─────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String, color: Color) {
    Text(
        text = text,
        fontSize = ChatTheme.fonts.contextSectionHeader,
        fontWeight = ChatTheme.fontWeights.detailLabel,
        color = color
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DetailRow(label: String, value: String, labelColor: Color, valueColor: Color, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = ChatTheme.fonts.contextDetailLabel,
            color = labelColor,
            modifier = Modifier.width(90.dp)
        )
        Text(
            text = value,
            fontSize = ChatTheme.fonts.contextDetailValue,
            fontWeight = if (bold) ChatTheme.fontWeights.detailLabel else ChatTheme.fontWeights.detailValue,
            color = if (bold) valueColor else labelColor
        )
    }
}

@Composable
private fun UsageBar(usagePercent: Float, contextLimit: Long, totalTokens: Long, progressBg: Color) {
    val color = contextColorForPercent(usagePercent)
    val displayPercent = if (usagePercent >= 100f) "${usagePercent.toInt()}%" else "${String.format("%.1f", usagePercent)}%"
    val isUnknown = contextLimit == 0L

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayPercent,
                fontSize = ChatTheme.fonts.contextPanelTitle,
                fontWeight = ChatTheme.fontWeights.contextPercent,
                color = color
            )
            if (!isUnknown) {
                Text(
                    text = "${formatPanelTokens(totalTokens)} / ${formatPanelTokens(contextLimit)}",
                    fontSize = 10.sp,
                    color = ChatTheme.colors.component.contextPanelLabel
                )
            } else {
                Text(
                    text = "${formatPanelTokens(totalTokens)} tokens",
                    fontSize = 10.sp,
                    color = ChatTheme.colors.component.contextPanelLabel
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (!isUnknown) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(ChatTheme.shapes.contextProgressBarCornerRadius)
                    .background(progressBg)
            ) {
                val fillFraction = (usagePercent / 100f).coerceIn(0f, 1f)
                // Overflow indicator for >100%
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fillFraction)
                        .height(8.dp)
                        .clip(RoundedCornerShape(topStart = ChatTheme.dims.contextProgressBarCornerRadius, bottomStart = ChatTheme.dims.contextProgressBarCornerRadius))
                        .background(color)
                )
                if (usagePercent > 100f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 2.dp, top = 1.dp)
                    ) {
                        Text(
                            text = "+",
                            fontSize = ChatTheme.fonts.contextProgressBarPercent,
                            fontWeight = FontWeight.Bold,
                            color = contextColorForPercent(usagePercent)
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(ChatTheme.shapes.contextProgressBarCornerRadius)
                    .background(progressBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "N/A",
                    fontSize = ChatTheme.fonts.contextProgressBarPercent,
                    color = ChatTheme.colors.text.disabled
                )
            }
        }
    }
}

@Composable
private fun HorizontalSeparator(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

// ── Format helpers ─────────────────────────────────────────────────────────

private fun formatPanelTokens(tokens: Long): String {
    return when {
        tokens == 0L -> "0"
        tokens < 1000L -> tokens.toString()
        tokens < 1_000_000L -> "${String.format("%.1f", tokens / 1000.0)}k"
        else -> "${String.format("%.1f", tokens / 1_000_000.0)}M"
    }
}

private fun formatPanelCost(cost: Double): String {
    return if (cost == 0.0) "$0.00" else "$${String.format("%.4f", cost)}"
}