package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import com.opencode.acp.chat.ui.theme.ChatTheme

/**
 * Recent file entry for the attachment menu.
 */
data class RecentFile(
    val name: String,
    val path: String
)

/**
 * Attachment menu popup matching the IntelliJ-style dark popup design.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun AttachMenu(
    recentFiles: List<RecentFile>,
    searchResults: List<RecentFile>,
    onFilesAndFolders: () -> Unit,
    onImage: () -> Unit,
    onRecentFileClick: (RecentFile) -> Unit,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val searchState = remember { TextFieldState() }
    val searchFocusRequester = remember { FocusRequester() }
    var hoveredIndex by remember { mutableStateOf(-1) }

    val bgColor = ChatTheme.colors.component.inputBg
    val borderColor = ChatTheme.colors.border.default
    val mutedTextColor = ChatTheme.colors.text.muted
    val hoverBg = ChatTheme.colors.component.hoverBg
    val dividerColor = ChatTheme.colors.border.default

    // Filter recent files by search query — computed directly so TextFieldState changes trigger recomposition
    val searchQuery = searchState.text.toString()
    val filteredRecent = if (searchQuery.isBlank()) recentFiles
        else recentFiles.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.path.contains(searchQuery, ignoreCase = true)
        }

    // Combine: when searching, show search results from project files; otherwise show recent files
    val displayFiles = if (searchQuery.isNotBlank()) searchResults else filteredRecent
    val sectionLabel = if (searchQuery.isNotBlank()) "Search results" else "Recent files"

    // Reset hover when filter changes
    LaunchedEffect(searchQuery) {
        hoveredIndex = -1
        onSearch(searchQuery)
    }

    // Auto-focus search field when popup appears
    LaunchedEffect(Unit) {
        try {
            searchFocusRequester.requestFocus()
        } catch (_: Exception) {
            // Focus request can fail if the composable is not yet laid out
        }
    }

    Column(
        modifier = modifier
            .width(320.dp)
            .heightIn(max = 480.dp)
            .clip(ChatTheme.shapes.attachMenuCornerRadius)
            .background(bgColor)
            .border(1.dp, borderColor, ChatTheme.shapes.attachMenuCornerRadius),
    ) {
        // Search field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                key = AllIconsKeys.Actions.Search,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = mutedTextColor,
            )
            Spacer(modifier = Modifier.width(6.dp))
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
                placeholder = { Text("Search", color = mutedTextColor, fontSize = ChatTheme.fonts.attachMenuSearchPlaceholder) },
            )
        }

        // Files and Folders
        AttachMenuItem(
            icon = AllIconsKeys.Nodes.Folder,
            iconTint = ChatTheme.colors.component.attachmentRemoveIcon,
            label = "Files and Folders",
            trailing = {
                Icon(
                    key = AllIconsKeys.Actions.Forward,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = mutedTextColor,
                )
            },
            hovered = hoveredIndex == 0,
            hoverBg = hoverBg,
            onHover = { hoveredIndex = if (it) 0 else -1 },
            onClick = {
                onFilesAndFolders()
                onDismiss()
            },
        )

        // Image...
        AttachMenuItem(
            icon = AllIconsKeys.FileTypes.Image,
            iconTint = ChatTheme.colors.component.attachmentRemoveIcon,
            label = "Image...",
            hovered = hoveredIndex == 1,
            hoverBg = hoverBg,
            onHover = { hoveredIndex = if (it) 1 else -1 },
            onClick = {
                onImage()
                onDismiss()
            },
        )

        // Divider
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor)
        )

        // Files section
        if (searchQuery.isNotBlank() && displayFiles.isEmpty()) {
            // No matches for search query
            Text(
                text = "No matching files",
                fontSize = ChatTheme.fonts.attachMenuSectionLabel,
                color = mutedTextColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        } else if (displayFiles.isNotEmpty()) {
            Text(
                text = sectionLabel,
                fontSize = ChatTheme.fonts.attachMenuSectionLabel,
                color = mutedTextColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 0.dp),
            ) {
                items(
                    count = displayFiles.size,
                    key = { displayFiles[it].path }
                ) { index ->
                    val file = displayFiles[index]
                    val iconInfo = fileIconForFile(file.name)
                    AttachMenuItem(
                        icon = iconInfo.first,
                        iconTint = iconInfo.second,
                        label = file.name,
                        subtitle = file.path,
                        hovered = hoveredIndex == index + 2,
                        hoverBg = hoverBg,
                        onHover = { hoveredIndex = if (it) index + 2 else -1 },
                        onClick = {
                            onRecentFileClick(file)
                            onDismiss()
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Single menu item row with icon, label, optional trailing, and hover state.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun AttachMenuItem(
    icon: org.jetbrains.jewel.ui.icon.IconKey,
    iconTint: Color,
    label: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    hovered: Boolean,
    hoverBg: Color,
    onHover: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val bgColor = if (hovered) hoverBg else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(ChatTheme.shapes.attachFileRowCornerRadius)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            key = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = iconTint,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = ChatTheme.fonts.attachMenuFileName,
                color = ChatTheme.colors.text.secondary,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = ChatTheme.fonts.attachMenuFilePath,
                    color = ChatTheme.colors.text.disabled,
                    maxLines = 1,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Returns the appropriate IntelliJ icon and tint color for a file based on its extension.
 * Only uses icons known to exist in the IntelliJ platform.
 */
@Composable
private fun fileIconForFile(fileName: String): Pair<org.jetbrains.jewel.ui.icon.IconKey, Color> {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when {
        ext == "kt" || ext == "kts" -> AllIconsKeys.Language.Kotlin to ChatTheme.colors.file.kotlin
        ext == "java" -> AllIconsKeys.FileTypes.Java to ChatTheme.colors.file.java
        ext == "js" || ext == "jsx" -> AllIconsKeys.FileTypes.JavaScript to ChatTheme.colors.file.javaScript
        ext == "ts" || ext == "tsx" -> AllIconsKeys.FileTypes.JavaScript to ChatTheme.colors.file.typeScript
        ext == "py" -> AllIconsKeys.Language.Python to ChatTheme.colors.file.python
        ext == "rb" -> AllIconsKeys.Language.Ruby to ChatTheme.colors.file.ruby
        ext == "go" -> AllIconsKeys.Language.GO to ChatTheme.colors.file.go
        ext == "rs" -> AllIconsKeys.Language.Rust to ChatTheme.colors.file.rust
        ext == "html" || ext == "htm" -> AllIconsKeys.FileTypes.Html to ChatTheme.colors.file.html
        ext == "css" || ext == "scss" -> AllIconsKeys.FileTypes.Css to ChatTheme.colors.file.css
        ext == "xml" -> AllIconsKeys.FileTypes.Xml to ChatTheme.colors.file.xml
        ext == "json" -> AllIconsKeys.FileTypes.Json to ChatTheme.colors.file.json
        ext == "yaml" || ext == "yml" -> AllIconsKeys.FileTypes.Yaml to ChatTheme.colors.file.yaml
        ext == "md" -> AllIconsKeys.FileTypes.Text to ChatTheme.colors.file.markdown
        ext == "sql" -> AllIconsKeys.FileTypes.Text to ChatTheme.colors.file.sql
        ext == "sh" || ext == "bash" -> AllIconsKeys.Nodes.Console to ChatTheme.colors.file.shell
        else -> AllIconsKeys.FileTypes.Text to ChatTheme.colors.component.attachmentRemoveIcon
    }
}
