package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.opencode.acp.chat.model.SessionContext
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.model.CompactionState
import com.opencode.acp.chat.model.ContextBreakdown
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.config.settings.OpenCodeContextSettingsState
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.foundation.theme.JewelTheme

// ── Context Panel (sidebar tab content) ────────────────────────────────────

@Composable
fun ContextPanel(
    state: SessionContextState,
    onRetry: () -> Unit,
    compactionState: CompactionState = CompactionState.Idle,
    onCompact: () -> Unit = {},
    checkpointReady: Boolean = false,
    modifier: Modifier = Modifier
) {
    when (state) {
        is SessionContextState.Loading -> {
            LoadingContent(modifier = modifier)
        }
        is SessionContextState.Loaded -> {
            ContextDetails(
                context = state.context,
                compactionState = compactionState,
                onCompact = onCompact,
                checkpointReady = checkpointReady,
                modifier = modifier
            )
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
private fun ContextDetails(
    context: SessionContext,
    compactionState: CompactionState = CompactionState.Idle,
    onCompact: () -> Unit = {},
    checkpointReady: Boolean = false,
    modifier: Modifier = Modifier
) {
    val sectionColor = ChatTheme.colors.component.contextPanelValue
    val labelColor = ChatTheme.colors.component.contextPanelLabel
    val valueColor = ChatTheme.colors.component.contextPanelValue
    val separator = ChatTheme.colors.component.contextPanelSeparator
    val progressBg = ChatTheme.colors.component.contextProgressBarBg
    val showBreakdown = OpenCodeContextSettingsState.getInstance().showContextBreakdown
    val compactConfirmation = OpenCodeContextSettingsState.getInstance().compactConfirmation

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
            progressBg = progressBg,
            breakdown = if (showBreakdown) context.breakdown else null
        )

        if (showBreakdown && context.breakdown != null && context.breakdown.totalTokens > 0) {
            Spacer(Modifier.height(4.dp))
            BreakdownLegend(breakdown = context.breakdown, labelColor, valueColor)
        }

        // ── Pressure forecast ──
        if (context.pressure != null && context.pressure.turnsUntilCompact != null) {
            Spacer(Modifier.height(6.dp))
            DetailRow(
                label = "Turns until compact",
                value = if (context.pressure.turnsUntilCompact == 0) "Compaction imminent" else "~${context.pressure.turnsUntilCompact} turns",
                labelColor,
                valueColor
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalSeparator(separator)
        Spacer(Modifier.height(10.dp))

        // ── Compact Now button ──
        CompactButtonRow(
            compactionState = compactionState,
            onCompact = onCompact,
            sectionColor = sectionColor,
            showConfirmation = compactConfirmation,
            checkpointReady = checkpointReady,
        )

        Spacer(Modifier.height(10.dp))
        HorizontalSeparator(separator)
        Spacer(Modifier.height(10.dp))

        // ── Pruner section ──
        if (OpenCodeContextSettingsState.getInstance().enableContextPruner) {
            PrunerSection(
                tokensSaved = context.prunerTokensSaved,
                outputsPruned = context.prunerOutputsPruned,
                inputsPruned = context.prunerInputsPruned,
                lastRunMs = context.prunerLastRunMs,
                labelColor = labelColor,
                valueColor = valueColor,
                sectionColor = sectionColor,
            )
            Spacer(Modifier.height(10.dp))
            HorizontalSeparator(separator)
            Spacer(Modifier.height(10.dp))
        }

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
private fun UsageBar(
    usagePercent: Float,
    contextLimit: Long,
    totalTokens: Long,
    progressBg: Color,
    breakdown: ContextBreakdown? = null
) {
    val fallbackColor = contextColorForPercent(usagePercent)
    val displayPercent = if (usagePercent >= 100f) "${usagePercent.toInt()}%" else "${String.format(java.util.Locale.US, "%.1f", usagePercent)}%"
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
                color = fallbackColor
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

                if (breakdown != null && breakdown.totalTokens > 0) {
                    // Show breakdown colors within the fill region.
                    // Each category segment = its fraction of totalTokens × fillFraction.
                    val total = breakdown.totalTokens.coerceAtLeast(1L).toFloat()
                    val systemFrac = (breakdown.systemPromptTokens / total * fillFraction).coerceIn(0f, 1f)
                    val userFrac = (breakdown.userTokens / total * fillFraction).coerceIn(0f, 1f)
                    val assistantFrac = (breakdown.assistantTokens / total * fillFraction).coerceIn(0f, 1f)
                    val toolFrac = (breakdown.toolTokens / total * fillFraction).coerceIn(0f, 1f)
                    // otherFrac fills remaining space up to fillFraction
                    val otherFrac = (fillFraction - systemFrac - userFrac - assistantFrac - toolFrac).coerceAtLeast(0f)

                    val systemColor = ChatTheme.colors.accent.blue
                    val userColor = ChatTheme.colors.accent.contextGreen
                    val assistantColor = ChatTheme.colors.accent.contextYellow
                    val toolColor = ChatTheme.colors.accent.contextRed
                    val otherColor = ChatTheme.colors.accent.contextUnknown

                    Row(Modifier.fillMaxWidth(fillFraction).height(8.dp)) {
                        if (systemFrac > 0f) Box(Modifier.weight(systemFrac).fillMaxWidth().height(8.dp).background(systemColor))
                        if (userFrac > 0f) Box(Modifier.weight(userFrac).fillMaxWidth().height(8.dp).background(userColor))
                        if (assistantFrac > 0f) Box(Modifier.weight(assistantFrac).fillMaxWidth().height(8.dp).background(assistantColor))
                        if (toolFrac > 0f) Box(Modifier.weight(toolFrac).fillMaxWidth().height(8.dp).background(toolColor))
                        if (otherFrac > 0f) Box(Modifier.weight(otherFrac).fillMaxWidth().height(8.dp).background(otherColor))
                    }
                } else {
                    // No breakdown — solid color
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillFraction)
                            .height(8.dp)
                            .clip(RoundedCornerShape(topStart = ChatTheme.dims.contextProgressBarCornerRadius, bottomStart = ChatTheme.dims.contextProgressBarCornerRadius))
                            .background(fallbackColor)
                    )
                }

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
                            color = fallbackColor
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
        tokens < 1_000_000L -> "${String.format(java.util.Locale.US, "%.1f", tokens / 1000.0)}k"
        else -> "${String.format(java.util.Locale.US, "%.1f", tokens / 1_000_000.0)}M"
    }
}

private fun formatPanelCost(cost: Double): String {
    return if (cost == 0.0) "$0.00" else "$${String.format(java.util.Locale.US, "%.4f", cost)}"
}

// ── Breakdown Legend ────────────────────────────────────────────────────────

@Composable
private fun BreakdownLegend(breakdown: ContextBreakdown, labelColor: Color, valueColor: Color) {
    var toolBreakdownExpanded by remember { mutableStateOf(true) }
    var otherBreakdownExpanded by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LegendRow("System + Tools", breakdown.systemPromptTokens, breakdown.systemPromptPercent, ChatTheme.colors.accent.blue, labelColor, valueColor)
        LegendRow("User", breakdown.userTokens, breakdown.userPercent, ChatTheme.colors.accent.contextGreen, labelColor, valueColor)
        LegendRow("Assistant", breakdown.assistantTokens, breakdown.assistantPercent, ChatTheme.colors.accent.contextYellow, labelColor, valueColor)

        // Tool Calls row + inline expandable breakdown
        LegendRow("Tool Calls", breakdown.toolTokens, breakdown.toolPercent, ChatTheme.colors.accent.contextRed, labelColor, valueColor)
        if (breakdown.toolBreakdown.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { toolBreakdownExpanded = !toolBreakdownExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (toolBreakdownExpanded) "\u25BC" else "\u25B6",
                    fontSize = ChatTheme.fonts.contextDetailLabel,
                    color = labelColor,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Tool breakdown (${breakdown.toolBreakdown.size} tools)",
                    fontSize = ChatTheme.fonts.contextDetailLabel,
                    color = labelColor,
                )
            }

            AnimatedVisibility(
                visible = toolBreakdownExpanded,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val sortedTools = breakdown.toolBreakdown.values.sortedByDescending { it.estimatedTokens }
                    for (tool in sortedTools) {
                        LegendRow(
                            label = "${tool.toolName} (${tool.callCount}x)",
                            tokens = tool.estimatedTokens,
                            percent = if (breakdown.totalTokens > 0)
                                tool.estimatedTokens.toFloat() / breakdown.totalTokens * 100
                            else 0f,
                            color = ChatTheme.colors.accent.contextRed,
                            labelColor = labelColor,
                            valueColor = valueColor,
                        )
                    }
                }
            }
        }

        // Other row + inline expandable breakdown
        if (breakdown.otherTokens > 0) {
            LegendRow("Other", breakdown.otherTokens, breakdown.otherPercent, ChatTheme.colors.accent.contextUnknown, labelColor, valueColor)
            if (breakdown.otherBreakdown.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { otherBreakdownExpanded = !otherBreakdownExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (otherBreakdownExpanded) "\u25BC" else "\u25B6",
                        fontSize = ChatTheme.fonts.contextDetailLabel,
                        color = labelColor,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Other breakdown (${breakdown.otherBreakdown.size} categories)",
                        fontSize = ChatTheme.fonts.contextDetailLabel,
                        color = labelColor,
                    )
                }

                AnimatedVisibility(
                    visible = otherBreakdownExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top)
                ) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val sortedOther = breakdown.otherBreakdown.values.sortedByDescending { it.estimatedTokens }
                        for (category in sortedOther) {
                            LegendRow(
                                label = category.categoryName,
                                tokens = category.estimatedTokens,
                                percent = if (breakdown.totalTokens > 0)
                                    category.estimatedTokens.toFloat() / breakdown.totalTokens * 100
                                else 0f,
                                color = ChatTheme.colors.accent.contextUnknown,
                                labelColor = labelColor,
                                valueColor = valueColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendRow(label: String, tokens: Long, percent: Float, color: Color, labelColor: Color, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().height(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(ChatTheme.shapes.contextProgressBarCornerRadius).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = ChatTheme.fonts.contextDetailLabel,
            color = labelColor,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${formatPanelTokens(tokens)} (${String.format(java.util.Locale.US, "%.0f", percent)}%)",
            fontSize = ChatTheme.fonts.contextDetailValue,
            color = valueColor,
        )
    }
}

// ── Compact Button Row ──────────────────────────────────────────────────────

@Composable
private fun CompactButtonRow(
    compactionState: CompactionState,
    onCompact: () -> Unit,
    sectionColor: Color,
    showConfirmation: Boolean = false,
    checkpointReady: Boolean = false,
) {
    var showDialog by remember { mutableStateOf(false) }

    SectionHeader("Compaction", sectionColor)
    when (compactionState) {
        is CompactionState.InProgress -> {
            Text(
                text = "Compacting...",
                fontSize = ChatTheme.fonts.contextDetailValue,
                color = ChatTheme.colors.accent.contextYellow,
            )
        }
        is CompactionState.Error -> {
            val msg = when (compactionState.error) {
                com.opencode.acp.chat.model.CompactionError.NoActiveSession -> "No active session"
                com.opencode.acp.chat.model.CompactionError.NotConnected -> "Not connected"
                com.opencode.acp.chat.model.CompactionError.Timeout -> "Compaction timed out"
                is com.opencode.acp.chat.model.CompactionError.ServerError -> "Error: ${compactionState.error.message}"
            }
            Text(
                text = msg,
                fontSize = ChatTheme.fonts.contextDetailValue,
                color = ChatTheme.colors.accent.red,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onCompact, enabled = true) { Text("Retry") }
        }
        is CompactionState.Idle -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        if (showConfirmation) showDialog = true else onCompact()
                    },
                    enabled = true
                ) { Text("Compact Now") }
                if (checkpointReady) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "\u25CF Ready",
                        fontSize = ChatTheme.fonts.contextDetailValue,
                        color = ChatTheme.colors.accent.contextGreen,
                    )
                }
            }
        }
    }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Box(
                modifier = Modifier.width(320.dp).clip(RoundedCornerShape(12.dp))
                    .background(JewelTheme.globalColors.panelBackground)
                    .border(1.dp, separatorColor(), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Compact Context?",
                        fontSize = ChatTheme.fonts.contextSectionHeader,
                        fontWeight = FontWeight.Medium,
                        color = ChatTheme.colors.component.contextPanelValue,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "This will summarize the conversation history. The session will be compacted on the server.",
                        fontSize = ChatTheme.fonts.contextDetailValue,
                        color = JewelTheme.globalColors.text.disabled,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = { showDialog = false },
                            enabled = true,
                        ) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                showDialog = false
                                onCompact()
                            },
                            enabled = true,
                        ) { Text("Compact") }
                    }
                }
            }
        }
    }
}

@Composable
private fun separatorColor(): Color = ChatTheme.colors.component.contextPanelSeparator

// ── Pruner Section ─────────────────────────────────────────────────────────

@Composable
private fun PrunerSection(
    tokensSaved: Long,
    outputsPruned: Long,
    inputsPruned: Long,
    lastRunMs: Long,
    labelColor: Color,
    valueColor: Color,
    sectionColor: Color,
) {
    SectionHeader("Context Pruner", sectionColor)
    if (tokensSaved == 0L && outputsPruned == 0L && inputsPruned == 0L) {
        DetailRow(
            label = "Status",
            value = "Active (no pruning yet)",
            labelColor,
            valueColor,
        )
    } else {
        DetailRow(
            label = "Tokens saved",
            value = formatPanelTokens(tokensSaved),
            labelColor,
            valueColor,
            bold = true,
        )
        DetailRow(label = "Outputs pruned", value = "$outputsPruned", labelColor, valueColor)
        DetailRow(label = "Inputs pruned", value = "$inputsPruned", labelColor, valueColor)
    }
    if (lastRunMs > 0) {
        DetailRow(
            label = "Last run",
            value = formatRelativeTime(lastRunMs),
            labelColor,
            valueColor,
        )
    }
}