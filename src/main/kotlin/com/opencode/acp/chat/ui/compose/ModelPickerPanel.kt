package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image as ComposeImage
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.config.settings.OpenCodeSettingsState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Public API ──────────────────────────────────────────────────────────────

/**
 * Full model picker panel matching the OpenCode TUI design.
 * Shows favorites at top, then provider-grouped models with search, context window, and star toggle.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun ModelPickerPanel(
    models: List<ProviderModel>,
    selectedModel: ProviderModel?,
    onModelSelected: (ProviderModel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = remember { OpenCodeSettingsState.getInstance() }
    val searchState = remember { TextFieldState() }
    val searchFocusRequester = remember { FocusRequester() }
    val searchQuery = searchState.text.toString()

    // Track favorites version to trigger recomposition when favorites change
    var favoritesVersion by remember { mutableIntStateOf(0) }

    // Group models: favorites first, then by provider
    val grouped = remember(models, searchQuery, favoritesVersion) {
        buildModelGroups(models, settings, searchQuery)
    }

    // Collapsible section state — providers collapsed by default when favorites exist
    var favoritesExpanded by remember { mutableStateOf(true) }
    val providerExpanded = remember {
        mutableStateOf(
            grouped.providers.keys.associateWith { grouped.favorites.isEmpty() }.toMutableMap()
        )
    }

    // Auto-focus search field
    LaunchedEffect(Unit) {
        try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = modifier
            .width(340.dp)
            .heightIn(max = 480.dp)
            .clip(ChatTheme.shapes.pickerCornerRadius)
            .background(ChatTheme.colors.component.inputBg)
            .border(1.dp, ChatTheme.colors.border.default, ChatTheme.shapes.pickerCornerRadius),
    ) {
        // ── Search field ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                key = AllIconsKeys.Actions.Search,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = ChatTheme.colors.text.muted,
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                state = searchState,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else false
                    },
                placeholder = { Text("Search models", color = ChatTheme.colors.text.muted, fontSize = ChatTheme.fonts.pickerSearchPlaceholder) },
            )
        }

        // ── Model list ──────────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 0.dp),
        ) {
            // Favorites section
            if (grouped.favorites.isNotEmpty()) {
                item(key = "header_favorites") {
                    SectionHeader(
                        title = "FAVORITES",
                        iconKey = AllIconsKeys.Nodes.Favorite,
                        iconColor = ChatTheme.colors.component.starGold,
                        expanded = favoritesExpanded,
                        onToggle = { favoritesExpanded = !favoritesExpanded },
                    )
                }
                if (favoritesExpanded) {
                    items(
                        count = grouped.favorites.size,
                        key = { "${grouped.favorites[it].providerID}/${grouped.favorites[it].modelID}" },
                    ) { index ->
                        val model = grouped.favorites[index]
                        ModelRow(
                            model = model,
                            isSelected = model == selectedModel,
                            isFavorite = true,
                            onToggleFavorite = {
                                settings.toggleFavoriteModel(model.providerID, model.modelID)
                                favoritesVersion++
                            },
                            onClick = { onModelSelected(model) },
                        )
                    }
                }
            }

            // Provider sections
            grouped.providers.forEach { (providerName, providerModels) ->
                val isExpanded = providerExpanded.value.getOrPut(providerName) { true }
                item(key = "header_$providerName") {
                    SectionHeader(
                        title = providerName.uppercase(),
                        expanded = isExpanded,
                        onToggle = {
                            providerExpanded.value = providerExpanded.value.toMutableMap().apply {
                                this[providerName] = !isExpanded
                            }
                        },
                    )
                }
                if (isExpanded) {
                    items(
                        count = providerModels.size,
                        key = { "${providerModels[it].providerID}/${providerModels[it].modelID}" },
                    ) { index ->
                        val model = providerModels[index]
                        ModelRow(
                            model = model,
                            isSelected = model == selectedModel,
                            isFavorite = settings.isFavoriteModel(model.providerID, model.modelID),
                            onToggleFavorite = {
                                settings.toggleFavoriteModel(model.providerID, model.modelID)
                                favoritesVersion++
                            },
                            onClick = { onModelSelected(model) },
                        )
                    }
                }
            }

            // Empty state
            if (grouped.favorites.isEmpty() && grouped.providers.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "No models found",
                        fontSize = ChatTheme.fonts.pickerModelName,
                        color = ChatTheme.colors.text.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        // ── Footer hints ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FooterHint("↑↓ navigate")
                FooterHint("Tab switch agent")
            }
        }
    }
}

// ── Section header ──────────────────────────────────────────────────────────

@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun SectionHeader(
    title: String,
    iconText: String? = null,
    iconKey: org.jetbrains.jewel.ui.icon.IconKey? = null,
    iconColor: Color = ChatTheme.colors.text.muted,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron
        Icon(
            key = if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(10.dp),
            tint = ChatTheme.colors.text.muted,
        )
        Spacer(modifier = Modifier.width(6.dp))

        // Section icon (platform icon or text fallback)
        if (iconKey != null) {
            Icon(
                key = iconKey,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = iconColor,
            )
            Spacer(modifier = Modifier.width(4.dp))
        } else if (iconText != null) {
            Text(
                text = iconText,
                fontSize = ChatTheme.fonts.pickerModelName,
                color = iconColor,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Title
        Text(
            text = title,
            fontSize = ChatTheme.fonts.pickerSectionHeader,
            fontWeight = FontWeight.Bold,
            color = ChatTheme.colors.accent.infoBlue,
        )
    }
}

// ── Model row ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ModelRow(
    model: ProviderModel,
    isSelected: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor = when {
        isSelected -> ChatTheme.colors.component.selectedRowBg
        isHovered -> ChatTheme.colors.component.hoverBg
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(ChatTheme.shapes.pickerRowCornerRadius)
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Provider icon (text-based fallback)
        ProviderIcon(
            providerId = model.providerIconId,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))

        // Model name
        Text(
            text = model.modelID,
            fontSize = ChatTheme.fonts.pickerModelName,
            color = if (isSelected) Color.White else ChatTheme.colors.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Feature indicator icons
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (model.reasoning) {
                Icon(
                    key = AllIconsKeys.Actions.Lightning,
                    contentDescription = "Reasoning",
                    modifier = Modifier.size(10.dp),
                    tint = ChatTheme.colors.component.starGold,
                )
            }
            if (hasVisionCapability(model)) {
                Icon(
                    key = AllIconsKeys.Actions.Search,
                    contentDescription = "Vision",
                    modifier = Modifier.size(10.dp),
                    tint = ChatTheme.colors.component.capabilityVision,
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Context window
        if (model.contextWindow > 0) {
            Text(
                text = formatContextWindow(model.contextWindow),
                fontSize = ChatTheme.fonts.pickerContextWindow,
                color = ChatTheme.colors.text.muted,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        // Star toggle
        Icon(
            key = if (isFavorite) AllIconsKeys.Nodes.Favorite else AllIconsKeys.Nodes.Favorite,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            modifier = Modifier
                .size(14.dp)
                .clip(ChatTheme.shapes.modelPickerButtonShape)
                .clickable(onClick = onToggleFavorite)
                .padding(horizontal = 2.dp),
            tint = if (isFavorite) ChatTheme.colors.component.starGold else ChatTheme.colors.component.starMuted,
        )
    }
}

// ── Provider icon ───────────────────────────────────────────────────────────

@Composable
internal fun ProviderIcon(
    providerId: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val bitmap = remember(providerId) { ProviderIconLoader.load(providerId, 20) }

    if (bitmap != null) {
        ComposeImage(
            bitmap = bitmap,
            contentDescription = providerId,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            colorFilter = tint?.let { ColorFilter.tint(it) },
        )
    } else {
        // Fallback: text-based colored circle with initial
        val color = tint ?: (ChatTheme.colors.provider.colorMap[providerId] ?: ChatTheme.colors.text.muted)
        val initial = providerId.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f))
                .border(1.dp, color.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                fontSize = ChatTheme.fonts.pickerProviderLetter,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

// ── Footer hint ─────────────────────────────────────────────────────────────

@Composable
private fun FooterHint(text: String) {
    Text(
        text = text,
        fontSize = ChatTheme.fonts.pickerFooter,
        color = ChatTheme.colors.text.muted,
    )
}

// ── Data structures ─────────────────────────────────────────────────────────

private data class ModelGroups(
    val favorites: List<ProviderModel>,
    val providers: Map<String, List<ProviderModel>>,
)

private fun buildModelGroups(
    models: List<ProviderModel>,
    settings: OpenCodeSettingsState,
    searchQuery: String,
): ModelGroups {
    if (models.isEmpty()) return ModelGroups(emptyList(), emptyMap())

    settings.cleanupStaleFavorites(models)

    val filtered = if (searchQuery.isBlank()) models
    else models.filter { model ->
        model.modelID.contains(searchQuery, ignoreCase = true) ||
            model.displayName.contains(searchQuery, ignoreCase = true)
    }

    val favorites = filtered.filter { settings.isFavoriteModel(it.providerID, it.modelID) }
    val nonFavorites = filtered.filter { !settings.isFavoriteModel(it.providerID, it.modelID) }

    val providers = nonFavorites
        .groupBy { it.displayName.substringBefore(" / ").trim() }
        .toSortedMap(compareBy { it })

    return ModelGroups(favorites, providers)
}

// ── Context window formatting ───────────────────────────────────────────────

private fun formatContextWindow(tokens: Int): String = when {
    tokens >= 1_000_000 -> {
        val m = tokens / 1_000_000.0
        if (m == m.toLong().toDouble()) "${m.toLong()}M" else "${"%.1f".format(m)}M"
    }
    tokens >= 1_000 -> {
        val k = tokens / 1_000.0
        if (k == k.toLong().toDouble()) "${k.toLong()}K" else "${"%.1f".format(k)}K"
    }
    else -> tokens.toString()
}

/**
 * Heuristic detection of vision/multimodal capability from model name.
 * Covers known vision-capable model families.
 */
private fun hasVisionCapability(model: ProviderModel): Boolean {
    val id = model.modelID.lowercase()
    val name = model.displayName.lowercase()
    return id.contains("vision") ||
        id.startsWith("gpt-4o") ||
        id.startsWith("gpt-4.1") ||
        id.contains("gemini") ||
        id.contains("claude-3") ||
        id.contains("claude-4") ||
        id.contains("qwen2.5-vl") ||
        id.contains("qwen-vl") ||
        id.contains("kimi-k2.6") ||
        id.contains("kimi-k2") ||
        name.contains("vision")
}
