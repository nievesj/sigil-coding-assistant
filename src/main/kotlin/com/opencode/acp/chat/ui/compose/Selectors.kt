package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.DropdownItem
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import com.opencode.acp.config.settings.OpenCodeSettingsState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.MenuScope

@OptIn(ExperimentalJewelApi::class)
@Composable
fun AgentSelector(
    controlState: ControlBarState,
    onAgentChanged: (OpenCodeAgentInfo) -> Unit
) {
    val agents = controlState.agents
    val selected = controlState.selectedAgent
    val displayText = selected?.name ?: "Agent"

    Dropdown(
        menuContent = {
            agents.forEach { agent ->
                selectableItem(
                    selected = agent == selected,
                    onClick = { onAgentChanged(agent) }
                ) {
                    Text(agent.name)
                }
            }
        }
    ) {
        SelectorChip(displayText)
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
fun ModelSelector(
    controlState: ControlBarState,
    onModelChanged: (ProviderModel) -> Unit
) {
    val settings = remember { OpenCodeSettingsState.getInstance() }
    val items = remember(controlState.models, controlState.selectedModel) {
        buildGroupedModelList(controlState.models, settings)
    }
    val displayText = controlState.selectedModel?.displayName?.substringAfter(" / ")?.trim() ?: "Model"

    Dropdown(
        menuContent = {
            items.forEach { item ->
                when (item) {
                    is DropdownItem.ProviderHeader -> {
                        passiveItem { GroupHeader(item.name) }
                    }
                    is DropdownItem.ModelItem -> {
                        selectableItem(
                            selected = item.model == controlState.selectedModel,
                            onClick = { onModelChanged(item.model) }
                        ) {
                            val label = if (item.isFavorite) "★ ${item.modelName}" else item.modelName
                            Text(label)
                        }
                    }
                }
            }
        }
    ) {
        SelectorChip(displayText)
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
fun ThinkingSelector(
    controlState: ControlBarState,
    onThinkingChanged: (ThinkingEffort) -> Unit
) {
    val isEnabled = controlState.selectedModel?.reasoning == true
    val displayText = controlState.thinkingEffort.label

    Dropdown(
        enabled = isEnabled,
        menuContent = {
            ThinkingEffort.entries.forEach { effort ->
                selectableItem(
                    selected = effort == controlState.thinkingEffort,
                    onClick = { onThinkingChanged(effort) }
                ) {
                    Text(effort.label)
                }
            }
        }
    ) {
        SelectorChip(
            text = displayText,
            enabled = isEnabled,
        )
    }
}

/**
 * Compact chip-style selector that matches the OpenCode aesthetic.
 * Dark background, rounded corners, subtle border, small text.
 */
@Composable
private fun SelectorChip(
    text: String,
    enabled: Boolean = true,
) {
    val bgColor = if (enabled) Color(0xFF2B2B2B) else Color(0xFF252525)
    val textColor = if (enabled) Color(0xFFCCCCCC) else Color(0xFF606060)
    val borderColor = if (enabled) Color(0xFF3E3E3E) else Color(0xFF333333)

    Text(
        text = text,
        fontSize = 11.sp,
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .then(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ),
    )
}

private fun buildGroupedModelList(
    models: List<ProviderModel>,
    settings: OpenCodeSettingsState
): List<DropdownItem> {
    if (models.isEmpty()) return emptyList()

    settings.cleanupStaleFavorites(models)

    val result = mutableListOf<DropdownItem>()

    // Favorites section
    val favorites = models.filter { settings.isFavoriteModel(it.providerID, it.modelID) }
    if (favorites.isNotEmpty()) {
        result.add(DropdownItem.ProviderHeader("★ Favorites"))
        for (model in favorites) {
            val (provider, name) = parseDisplayName(model.displayName)
            result.add(DropdownItem.ModelItem(model, provider, name, isFavorite = true))
        }
    }

    // Non-favorites grouped by provider
    val nonFavorites = models.filter { !settings.isFavoriteModel(it.providerID, it.modelID) }
    var lastProvider: String? = null
    for (model in nonFavorites) {
        val providerName = model.displayName.substringBefore(" / ").trim()
        if (providerName != lastProvider) {
            result.add(DropdownItem.ProviderHeader(providerName))
            lastProvider = providerName
        }
        val modelName = model.displayName.substringAfter(" / ").trim()
        result.add(DropdownItem.ModelItem(model, "", modelName, isFavorite = false))
    }
    return result
}

private fun parseDisplayName(displayName: String): Pair<String, String> {
    val parts = displayName.split(" / ", limit = 2)
    return if (parts.size == 2) Pair(parts[0].trim(), parts[1].trim())
    else Pair(displayName, displayName)
}
