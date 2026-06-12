# Technical Design Document: Model Favorites with Clickable Star

> **Status:** Draft (Updated after adversarial review)
> **Last Updated:** 2026-06-02

---

## 1. TL;DR

Add model favorites to the OpenCode IntelliJ plugin. Users can ☆/★ any model in the model dropdown via a clickable star at the far right edge of each dropdown item. Favorites appear at the top under a "★ Favorites" section, and the state persists across IDE restarts via `PersistentStateComponent`.

---

## 2. Context & Scope

### 2.1 Current State

The model dropdown in `ControlBarComponent` lists all models from connected providers grouped by provider name (e.g., "Ollama Cloud", "OpenCode Go"). Models are shown as flat text items. There is no way to mark a model as a favorite.

The model list can be large (e.g., 91 models from 4 providers), making it tedious to find frequently used models.

### 2.2 Problem Statement

Users need to frequently switch between a handful of preferred models. Without favorites, they must scroll through the full grouped list every time. We need a simple, persistent mechanism to mark models as favorites and surface them at the top of the dropdown.

---

## 3. Goals & Non-Goals

### Goals

1. **Persistent favorites:** Favorited models are saved and restored across IDE restarts.
2. **Visual star toggle:** Each model in the dropdown has a ☆/★ at the far right edge.
3. **Favorites section:** Favorited models appear at the top under "★ Favorites".
4. **Provider disambiguation:** For favorited models that share names across providers, show provider in parentheses.
5. **Click to favorite:** Clicking the star toggles the favorite state without selecting the item.

### Non-Goals

- Keyboard shortcuts for favorites.
- Drag-to-reorder favorites.
- Syncing favorites across multiple IDE instances.

---

## 4. Proposed Solution

### 4.1 Summary

The solution has three parts:

1. **Persistence layer:** `OpenCodeSettingsState` stores a `Set<String>` of favorite model keys (`"providerID\x1FmodelID"`) using a non-ambiguous delimiter. `toggleFavoriteModel()` and `isFavoriteModel()` helper methods manage the set. A `cleanupStaleFavorites()` method runs on model refresh to garbage-collect orphaned keys.

2. **Renderer:** A `ListCellRenderer` returns a `JPanel` containing a `JLabel` for model name and a `JLabel` for the star. The star label has a `MouseListener` — renderer components in IntelliJ's popup list DO receive mouse events. No HTML table hacks.

3. **Sealed type hierarchy:** A `sealed interface DropdownItem` distinguishes `ProviderHeader`, `ModelItem`, and `FavoriteSeparator`. The combo box model is `List<DropdownItem>`, not `List<Any>`.

### 4.2 Model Changes (`ChatModels.kt`)

Add a sealed interface for dropdown items:

```kotlin
/** Sealed type for heterogeneous combo box model items. */
sealed interface DropdownItem {
    /** Provider section header — not interactive. */
    data class ProviderHeader(val name: String) : DropdownItem

    /** A single model entry with favorite toggle. */
    data class ModelItem(
        val model: ProviderModel,
        val providerName: String,
        val modelName: String,
        val isFavorite: Boolean
    ) : DropdownItem {
        override fun equals(other: Any?): Boolean = other is ModelItem && model == other.model
        override fun hashCode(): Int = model.hashCode()
    }
}
```

`DropdownItem.ModelItem.equals()` is overridden to compare by `ProviderModel` only, allowing `JComboBox.setSelectedItem()` to match a `ProviderModel` against items in the model via structural equality.

### 4.3 Settings Changes (`OpenCodeSettingsState.kt`)

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `favoriteModels` | `Set<String>` | `emptySet()` | Keys in `"providerID\x1FmodelID"` format (Using `\x1F` (Unit Separator) as delimiter to avoid collisions with `:` in provider IDs) |

New methods:

```kotlin
fun modelKey(providerID: String, modelID: String): String = "$providerID\u001F$modelID"

fun isFavoriteModel(providerID: String, modelID: String): Boolean =
    myState.favoriteModels.contains(modelKey(providerID, modelID))

fun toggleFavoriteModel(providerID: String, modelID: String) {
    val key = modelKey(providerID, modelID)
    val current = myState.favoriteModels
    myState = myState.copy(favoriteModels = if (key in current) current - key else current + key)
}

/** Remove stale favorites for models that no longer exist in the current model list. */
fun cleanupStaleFavorites(allModels: List<ProviderModel>) {
    val validKeys = allModels.map { modelKey(it.providerID, it.modelID) }.toSet()
    val stale = myState.favoriteModels - validKeys
    if (stale.isNotEmpty()) {
        myState = myState.copy(favoriteModels = myState.favoriteModels - stale)
    }
}
```

### 4.4 Component Design (`ControlBarComponent.kt`)

**4.4.1 Data Transform: `buildGroupedModelList()`**

Produces a `List<DropdownItem>`:

```kotlin
fun buildGroupedModelList(models: List<ProviderModel>): List<DropdownItem> {
    val settings = OpenCodeSettingsState.getInstance()
    val result = mutableListOf<DropdownItem>()

    // 1. Clean up stale favorites
    settings.cleanupStaleFavorites(models)

    // 2. Favorites section
    val favorites = models.filter { settings.isFavoriteModel(it.providerID, it.modelID) }
    if (favorites.isNotEmpty()) {
        result.add(DropdownItem.ProviderHeader("★ Favorites"))
        for (model in favorites) {
            val (provider, name) = splitDisplayName(model.displayName)
            result.add(DropdownItem.ModelItem(model, provider, name, isFavorite = true))
        }
    }

    // 3. Grouped by provider (non-favorites only)
    val nonFavorites = models.filter { !settings.isFavoriteModel(it.providerID, it.modelID) }
    var lastProvider: String? = null
    for (model in nonFavorites) {
        val providerName = model.displayName.substringBefore(" / ").trim()
        if (providerName != lastProvider && providerName !in FAVORITES_DISPLAY_KEYS) {
            result.add(DropdownItem.ProviderHeader(providerName))
            lastProvider = providerName
        }
        val modelName = model.displayName.substringAfter(" / ").trim()
        result.add(DropdownItem.ModelItem(model, "", modelName, isFavorite = false))
    }

    return result
}

/** Parse displayName safely with fallback. */
private fun splitDisplayName(displayName: String): Pair<String, String> {
    val parts = displayName.split(" / ", limit = 2)
    return if (parts.size == 2) Pair(parts[0].trim(), parts[1].trim())
    else Pair(displayName, displayName)
}
```

**4.4.2 Renderer: `ModelRenderer`**

A custom `ListCellRenderer<DropdownItem>` that returns a `JPanel` with two labels:

```kotlin
class ModelRenderer : ListCellRenderer<DropdownItem> {
    private val headerLabel = JBLabel().apply {
        font = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(11f))
        foreground = JBColor(0x589df6, 0x589df6)
        border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(4), JBUI.scale(2), JBUI.scale(4))
    }
    private val modelPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(4))
        isOpaque = true
    }
    private val nameLabel = JBLabel()
    private val starLabel = JBLabel()

    init {
        starLabel.apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scaleFontSize(14f))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        modelPanel.add(nameLabel, BorderLayout.CENTER)
        modelPanel.add(starLabel, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out DropdownItem>,
        value: DropdownItem?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value !is DropdownItem) return headerLabel.apply { text = "" }

        return when (value) {
            is DropdownItem.ProviderHeader -> headerLabel.apply {
                text = value.name
                background = if (isSelected) list.selectionBackground else list.background
                isOpaque = true
            }

            is DropdownItem.ModelItem -> {
                val displayName = if (value.isFavorite && value.providerName.isNotEmpty()) {
                    escapeHtml("${value.modelName} (${value.providerName})")
                } else {
                    escapeHtml(value.modelName)
                }
                nameLabel.text = displayName
                nameLabel.font = if (value.isFavorite) {
                    nameLabel.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(12f))
                } else {
                    nameLabel.font.deriveFont(Font.PLAIN, JBUI.scaleFontSize(12f))
                }
                nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

                starLabel.text = if (value.isFavorite) "★" else "☆"
                starLabel.foreground = when {
                    isSelected -> list.selectionForeground
                    value.isFavorite && index == list.getAccessibleContext().AccessibleDescription.let(::parseIntOrZero) -> JBColor(0xffd700, 0xffd700)
                    value.isFavorite -> JBColor(0xf0c674, 0xf0c674)
                    else -> JBColor(0x6b6b6b, 0x6b6b6b)
                }

                modelPanel.apply {
                    background = if (isSelected) list.selectionBackground else list.background
                }
            }
        }
    }
}
```

**HTML escaping** must be applied to all model/provider names before setting on labels:

```kotlin
private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
```

**4.4.3 Star Click Detection: `MouseListener` on the JComboBox**

Instead of accessing the popup's JList via `accessibleContext`, use a `MouseListener` on the `JComboBox` combined with a `ListMouseListener` on the popup's `JList` obtained via the combo box model's `addListDataListener`:

```kotlin
// Register mouse listener on the combo box dropdown list
modelCombo.addPopupMenuListener(object : PopupMenuListener {
    private var attached = false

    override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
        // Get the popup's JList via the ComboBox's UI
        val child = (modelCombo.ui as? BasicComboBoxUI)?.let { ui ->
            try {
                val popupField = BasicComboBoxUI::class.java.getDeclaredField("popup")
                popupField.isAccessible = true
                (popupField.get(ui) as? ComboPopup)?.list
            } catch (_: Exception) { null }
        }
        // Fallback: accessible context
        val list = child ?: (modelCombo.accessibleContext.getAccessibleChild(0) as? ComboPopup)?.list
            ?: return

        if (!attached) {
            attached = true
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    val idx = list.locationToIndex(e.point)
                    if (idx < 0) return
                    val item = list.model.getElementAt(idx)
                    if (item !is DropdownItem.ModelItem) return
                    val bounds = list.getCellBounds(idx, idx) ?: return
                    // Use list.width (not bounds.width) for consistent coordinate math
                    val starEdge = bounds.x + bounds.width - JBUI.scale(28)
                    if (e.x >= starEdge) {
                        e.consume()
                        // Prevent selection by temporarily disabling the action listener
                        modelCombo.isPopupVisible = false // Close popup first
                        OpenCodeSettingsState.getInstance().toggleFavoriteModel(
                            item.model.providerID,
                            item.model.modelID
                        )
                        rebuildModelCombo()
                    }
                }
            })
        }
    }

    override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
    override fun popupMenuCanceled(e: PopupMenuEvent) { attached = false }
})
```

**Key change:** Instead of relying on `e.consume()` to prevent selection (which doesn't work — JComboBox processes selection on `mousePressed`, before `mouseReleased`), we close the popup first (`modelCombo.isPopupVisible = false`) to prevent any selection, then toggle the favorite. This avoids the race with the UI delegate.

**4.4.4 Selection Fix**

The combo box model contains `DropdownItem.ModelItem`, while the ViewModel's `selectedModel` is `ProviderModel`. The `DropdownItem.ModelItem.equals()` override allows `JComboBox.setSelectedItem()` to find a match by comparing the wrapped `ProviderModel`:

```kotlin
// In updateState():
val match = groupedModels.find { it is DropdownItem.ModelItem && it.model == state.selectedModel }
modelCombo.selectedItem = match
```

### 4.5 Call Flow

```
User clicks dropdown
  → popupMenuWillBecomeVisible fires
  → MouseListener attached to popup's JList
  → User clicks ☆ on "deepseek-v4-pro"
  → mouseReleased → coordinate math → e.x >= starEdge
  → e.consume()
  → modelCombo.isPopupVisible = false  // Close popup before any selection
  → toggleFavoriteModel("ollama-cloud", "deepseek-v4-pro")
  → rebuildModelCombo()
  → starListenerAttached = false
  → Next popup open → listeners re-attached
```

### 4.6 Technology

| Layer | Technology |
|-------|-----------|
| UI Framework | Java Swing (IntelliJ LaF) |
| Renderer | `ListCellRenderer<DropdownItem>` returning `JPanel` |
| DPI Scaling | `JBUI.scale()` and `JBUI.scaleFontSize()` |
| Persistence | IntelliJ `PersistentStateComponent` (`opencode-settings.xml`) |
| Color | `JBColor` (dark/light theme aware) |

### 4.7 Implementation Plan

**4.7.1: Update `OpenCodeSettingsState.kt`**
- Change `favoriteModels` to `Set<String>` with `\x1F` delimiter
- Add `cleanupStaleFavorites(allModels)` method
- Add `modelKey()`, `isFavoriteModel()`, `toggleFavoriteModel()`

**4.7.2: Update `ChatModels.kt`**
- Add `sealed interface DropdownItem` with `ProviderHeader` and `ModelItem`
- Override `equals()` on `ModelItem` to compare by `ProviderModel`

**4.7.3: Update `ControlBarComponent.kt`**
- Rewrite `buildGroupedModelList()` to return `List<DropdownItem>`
- Implement `ModelRenderer` with `JPanel` + two labels
- Add `PopupMenuListener` with star click detection
- Handle stale favorite cleanup
- Use `JBUI.scale()` for DPI-aware sizing
- Add HTML escaping for model names

**4.7.4: Add `escapeHtml()` to renderer** (or reuse from `ChatUtils.kt`)

**4.7.5: File list**

| File | Status |
|------|--------|
| `config/settings/OpenCodeSettingsState.kt` | Modified |
| `chat/ui/ControlBarComponent.kt` | Modified |
| `chat/model/ChatModels.kt` | Modified (add `DropdownItem`) |

---

## 5. Open Questions

1. **ComboPopup access method** — Both `BasicComboBoxUI.popup` reflection and `AccessibleContext` are fragile. If neither works, log a warning and degrade gracefully (no interactive stars).

2. **`isPopupVisible = false` timing** — Closing the popup before toggling may cause a flash. Consider whether `SwingUtilities.invokeLater` is needed to delay the model rebuild until after the popup fully closes.

3. **`DropdownItem.ModelItem.equals()` override** — Using structural equality on `ProviderModel` means two `ModelItem` objects wrapping different `ProviderModel` instances with the same field values are equal. This is correct for combo matching but may cause unexpected behavior in other contexts (e.g., sets, maps). Keep it scoped to the combo.

---

## 6. Document History

| Date | Change | Author |
|------|--------|--------|
| 2026-06-02 | Initial draft | — |
| 2026-06-02 | Post-adversarial-review update: replaced `accessibleContext` hack with comboPopup fallback, `e.consume()` with `isPopupVisible=false`, `List<Any>` with `sealed interface DropdownItem`, added HTML escaping, DPI scaling, stale cleanup, proper `equals()` override on ModelItem | — |
