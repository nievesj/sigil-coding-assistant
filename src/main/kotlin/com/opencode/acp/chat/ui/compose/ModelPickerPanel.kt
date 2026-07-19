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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image as ComposeImage
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
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
    val density = LocalDensity.current

    // Track favorites version to trigger recomposition when favorites change.
    // favoritesList is a State<List<ProviderModel>> so that the LazyColumn item
    // content lambdas re-invoke on reorder (per AGENTS.md: items(count, key) with
    // stable keys does NOT re-invoke item lambdas unless they read a State<T>).
    var favoritesVersion by remember { mutableIntStateOf(0) }
    var favoritesList by remember { mutableStateOf(emptyList<ProviderModel>()) }

    // Group models: favorites first, then by provider
    val grouped = remember(models, searchQuery, favoritesVersion) {
        buildModelGroups(models, settings, searchQuery)
    }
    // Cleanup stale favorites as a side effect when models change (kept out of
    // the remember block above so buildModelGroups stays pure).
    LaunchedEffect(models) {
        settings.cleanupStaleFavorites(models)
    }
    // ── Drag-to-reorder state ──────────────────────────────────────────────
    // draggedIndex: index within grouped.favorites of the item being dragged (null when idle)
    // dragOffsetPx: accumulated vertical drag offset in pixels (used for live swap detection)
    // The drag handle reports its row's pixel height via onRowHeight so we can
    // compute swap thresholds without relying on LazyListState layout info.
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(32f * density.density) }
    val listState = rememberLazyListState()
    // Sync favoritesList from grouped on every recomposition. Synchronous
    // (not LaunchedEffect) so live swaps in onDragDelta are never overwritten
    // by a stale grouped.favorites emission that lagged behind a settings write.
    // Gate the write on idle (no active drag) so the live swap performed in
    // onDragDelta is preserved during dragging; when drag ends (draggedIndex
    // becomes null) the next recomposition syncs favoritesList back to
    // grouped.favorites.
    //
    // Wrapped in SideEffect to move the state write out of the composition
    // phase (Compose contract: composition should be side-effect-free). SideEffect
    // runs after every successful composition, before the frame is committed.
    SideEffect {
        if (draggedIndex == null) {
            favoritesList = grouped.favorites
        }
    }


    /** Called on each drag delta. Swaps the dragged item with its neighbor when
     *  the offset crosses a full row height past the current position. Only one
     *  swap per call — subsequent swaps happen on the next onDrag callback (which
     *  fires very frequently, so multi-row drags still feel responsive). This
     *  avoids stale-list bugs from multiple swaps against a single grouped snapshot.
     *  After a swap, favoritesList is updated synchronously so the next onDrag
     *  callback sees the new order immediately (no LaunchedEffect frame lag).
     *
     *  NOTE: Settings are NOT written here — per-swap writes to the
     *  application-level PersistentStateComponent during an active pointer
     *  gesture cause unnecessary settings churn (listener callbacks, potential
     *  recompositions). The final order is persisted once in [endDrag]. */
    fun onDragDelta(deltaY: Float) {
        dragOffsetPx += deltaY
        val idx = draggedIndex ?: return
        val favs = favoritesList
        if (favs.isEmpty()) return
        // Swap up: offset is negative beyond one row height
        if (dragOffsetPx <= -rowHeightPx && idx > 0) {
            // Synchronously update the visible list so the next onDrag sees
            // the new order. Settings persistence is deferred to endDrag().
            favoritesList = favs.toMutableList().apply {
                val tmp = this[idx]; this[idx] = this[idx - 1]; this[idx - 1] = tmp
            }
            favoritesVersion++
            draggedIndex = idx - 1
            dragOffsetPx += rowHeightPx
            return
        }
        // Swap down: offset is positive beyond one row height
        if (dragOffsetPx >= rowHeightPx && idx < favs.size - 1) {
            favoritesList = favs.toMutableList().apply {
                val tmp = this[idx]; this[idx] = this[idx + 1]; this[idx + 1] = tmp
            }
            favoritesVersion++
            draggedIndex = idx + 1
            dragOffsetPx -= rowHeightPx
            return
        }
    }

    fun startDrag(index: Int) {
        draggedIndex = index
        dragOffsetPx = 0f
    }

    fun endDrag() {
        // Persist the final favorites order to settings (single write on drag end).
        val favs = favoritesList
        val newOrder = favs.map { "${it.providerID}/${it.modelID}" }
        settings.setFavoriteModelsOrder(newOrder)
        draggedIndex = null
        dragOffsetPx = 0f
    }

    fun cancelDrag() {
        draggedIndex = null
        dragOffsetPx = 0f
    }

    // Collapsible section state — providers collapsed by default when favorites exist.
    // Persisted across model list updates: the remember has no key, so the map
    // survives recomposition. New providers are seeded via LaunchedEffect below
    // (NOT via getOrPut during composition — that would be a state write during
    // the composition phase, violating the Compose contract).
    var favoritesExpanded by remember { mutableStateOf(true) }
    val providerExpanded = remember {
        mutableStateOf(emptyMap<String, Boolean>().toMutableMap())
    }
    // Seed initial + new providers when the provider set changes. New providers
    // default to expanded=true when there are no favorites, collapsed=false otherwise.
    LaunchedEffect(grouped.providers.keys, grouped.favorites.isEmpty()) {
        val defaults = grouped.favorites.isEmpty()
        val newEntries = grouped.providers.keys.filter { it !in providerExpanded.value }
        if (newEntries.isNotEmpty()) {
            providerExpanded.value = providerExpanded.value.toMutableMap().apply {
                newEntries.forEach { put(it, defaults) }
            }
        }
    }

    // Auto-focus search field
    LaunchedEffect(Unit) {
        try { searchFocusRequester.requestFocus() } catch (e: Exception) {
            dragLogger.debug(e) { "[ACP] Failed to focus search field" }
        }
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
            state = listState,
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
                    // Read the State inside the item lambda so the snapshot system
                    // re-invokes the lambda when favoritesList changes (reorder).
                    // Per AGENTS.md: items(count, key) with stable keys does NOT
                    // re-invoke item lambdas unless they read a State<T>.
                    items(
                        count = favoritesList.size,
                        key = { "${favoritesList[it].providerID}/${favoritesList[it].modelID}" },
                    ) { index ->
                        // Read the State INSIDE the lambda — creates a snapshot
                        // subscription that invalidates the item on reorder.
                        val model = favoritesList[index]
                        ModelRow(
                            model = model,
                            isSelected = model == selectedModel,
                            isFavorite = true,
                            onToggleFavorite = {
                                settings.toggleFavoriteModel(model.providerID, model.modelID)
                                favoritesVersion++
                            },
                            onClick = { onModelSelected(model) },
                            showDragHandle = true,
                            isDragging = draggedIndex == index,
                            onDragStart = { startDrag(index) },
                            onDragMove = { deltaY -> onDragDelta(deltaY) },
                            onDragEnd = { endDrag() },
                            onDragCancel = { cancelDrag() },
                        )
                    }
                }
            }

            // Provider sections
            grouped.providers.forEach { (providerName, providerModels) ->
                val isExpanded = providerExpanded.value[providerName] ?: true
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
                if (grouped.favorites.size > 1) {
                    FooterHint("⠿ drag favorites to reorder")
                }
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
    showDragHandle: Boolean = false,
    isDragging: Boolean = false,
    onDragStart: () -> Unit = {},
    onDragMove: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor = when {
        isSelected -> ChatTheme.colors.component.selectedRowBg
        isHovered -> ChatTheme.colors.component.hoverBg
        else -> Color.Transparent
    }

    val rowModifier = Modifier
        .fillMaxWidth()
        .height(32.dp)
        .then(if (isDragging) Modifier.alpha(0.4f) else Modifier)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Drag handle (favorites only) — placed OUTSIDE the clickable content
        // area so the parent's clickable modifier does not intercept the pointer
        // events needed to start the drag gesture.
        if (showDragHandle) {
            DragHandle(
                onDragStart = onDragStart,
                onDragMove = onDragMove,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
            )
        }

        // Clickable content area (provider icon, model name, indicators, star)
        Row(
            modifier = Modifier
                .weight(1f)
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
                color = if (isSelected) ChatTheme.colors.text.inverse else ChatTheme.colors.text.secondary,
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
                key = AllIconsKeys.Nodes.Favorite,
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

    val filtered = if (searchQuery.isBlank()) models
    else models.filter { model ->
        model.modelID.contains(searchQuery, ignoreCase = true) ||
            model.displayName.contains(searchQuery, ignoreCase = true)
    }

    val favorites = filtered
        .filter { settings.isFavoriteModel(it.providerID, it.modelID) }
        // Sort by the user's custom order in favoriteModels (preserves drag/drop
        // reordering). Models not in favoriteModels (shouldn't happen after
        // cleanupStaleFavorites) sort to the end.
        .sortedBy { model ->
            val key = OpenCodeSettingsState.modelKey(model.providerID, model.modelID)
            settings.favoriteModels.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it }
        }
    val nonFavorites = filtered.filter { !settings.isFavoriteModel(it.providerID, it.modelID) }

    val providers = nonFavorites
        .groupBy {
            val dn = it.displayName.substringBefore(" / ").trim()
            if (dn.isEmpty() || dn == it.displayName) it.providerID.uppercase() else dn
        }
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
        id.contains("claude-3-opus") ||
        id.contains("claude-3-sonnet") ||
        id.contains("claude-4") ||
        id.contains("qwen2.5-vl") ||
        id.contains("qwen-vl") ||
        name.contains("vision")
}

// ── Drag-to-reorder support ─────────────────────────────────────────────────

private val dragLogger = KotlinLogging.logger("com.opencode.acp.chat.ui.compose.ModelPickerPanel")

/**
 * Drag handle composable for reordering favorites. Renders a small grip icon
 * that brightens on hover. Drag is initiated via [detectDragGestures] (mouse-
 * friendly: starts on pointer drag, no long-press required). A quick click on
 * the handle does not start a drag — the gesture detector only fires on actual
 * drag movement past the touch slop, preserving row click-through for selection.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun DragHandle(
    onDragStart: () -> Unit,
    onDragMove: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleInteraction = remember { MutableInteractionSource() }
    val isHovered by handleInteraction.collectIsHoveredAsState()
    val tint = if (isHovered) ChatTheme.colors.text.secondary else ChatTheme.colors.text.muted

    // Capture the latest callbacks so the pointerInput block (keyed on Unit, so
    // it is NOT recreated on recomposition) always invokes the current lambdas.
    // Without this, after a live swap triggers favoritesVersion++ and recomposition,
    // the drag gesture would keep calling the stale onDragMove capturing the old
    // grouped list, breaking subsequent swaps.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    Box(
        modifier = modifier
            .size(width = 20.dp, height = 32.dp)
            .hoverable(handleInteraction)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragLogger.debug { "[ACP] ModelPicker drag start" }
                        currentOnDragStart()
                    },
                    onDrag = { _, dragAmount ->
                        dragLogger.debug { "[ACP] ModelPicker drag move: deltaY=${dragAmount.y}" }
                        currentOnDragMove(dragAmount.y)
                    },
                    onDragEnd = {
                        dragLogger.debug { "[ACP] ModelPicker drag end" }
                        currentOnDragEnd()
                    },
                    onDragCancel = {
                        dragLogger.debug { "[ACP] ModelPicker drag cancel" }
                        currentOnDragCancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Two short horizontal lines as a grip glyph
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(2) {
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .height(1.5.dp)
                        .background(tint, CircleShape),
                )
            }
        }
    }
}
