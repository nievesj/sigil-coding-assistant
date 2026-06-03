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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

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

    val bgColor = Color(0xFF2B2B2B)
    val borderColor = Color(0xFF3E3E3E)
    val mutedTextColor = Color(0xFF808080)
    val hoverBg = Color(0xFF3E3E3E)
    val dividerColor = Color(0xFF3E3E3E)

    // Filter recent files by search query — computed directly so TextFieldState changes trigger recomposition
    val searchQuery = searchState.text.toString()
    println("[AttachMenu] searchQuery='$searchQuery' (length=${searchQuery.length})")
    val filteredRecent = if (searchQuery.isBlank()) recentFiles
        else recentFiles.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.path.contains(searchQuery, ignoreCase = true)
        }

    // Combine: when searching, show search results from project files; otherwise show recent files
    val displayFiles = if (searchQuery.isNotBlank()) searchResults else filteredRecent
    val sectionLabel = if (searchQuery.isNotBlank()) "Search results" else "Recent files"
    println("[AttachMenu] displayFiles=${displayFiles.size} (searchResults=${searchResults.size}, filteredRecent=${filteredRecent.size})")

    // Reset hover when filter changes
    LaunchedEffect(searchQuery) {
        hoveredIndex = -1
        onSearch(searchQuery)
    }

    // Auto-focus search field when popup appears
    LaunchedEffect(Unit) {
        println("[AttachMenu] LaunchedEffect(Unit): requesting focus on search field")
        try {
            searchFocusRequester.requestFocus()
            println("[AttachMenu] Focus requested successfully")
        } catch (e: Exception) {
            println("[AttachMenu] Focus request FAILED: ${e.message}")
        }
    }

    Column(
        modifier = modifier
            .width(320.dp)
            .heightIn(max = 480.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp)),
    ) {
        // Search field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Search),
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
                placeholder = { Text("Search", color = mutedTextColor, fontSize = 12.sp) },
            )
        }

        // Files and Folders
        AttachMenuItem(
            icon = AllIcons.Nodes.Folder,
            iconTint = Color(0xFFBBBBBB),
            label = "Files and Folders",
            trailing = {
                Icon(
                    key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Forward),
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
            icon = AllIcons.FileTypes.Image,
            iconTint = Color(0xFFBBBBBB),
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
                fontSize = 11.sp,
                color = mutedTextColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        } else if (displayFiles.isNotEmpty()) {
            Text(
                text = sectionLabel,
                fontSize = 11.sp,
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
    icon: javax.swing.Icon,
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
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            key = IntelliJIconKey.fromPlatformIcon(icon),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = iconTint,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = Color(0xFFCCCCCC),
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color(0xFF666666),
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
private fun fileIconForFile(fileName: String): Pair<javax.swing.Icon, Color> {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when {
        ext == "kt" || ext == "kts" -> AllIcons.Language.Kotlin to Color(0xFFA97BFF)
        ext == "java" -> AllIcons.FileTypes.Java to Color(0xFFED8B00)
        ext == "js" || ext == "jsx" -> AllIcons.FileTypes.JavaScript to Color(0xFFF7DF1E)
        ext == "ts" || ext == "tsx" -> AllIcons.FileTypes.JavaScript to Color(0xFF3178C6)
        ext == "py" -> AllIcons.Language.Python to Color(0xFF3776AB)
        ext == "rb" -> AllIcons.Language.Ruby to Color(0xFFCC342D)
        ext == "go" -> AllIcons.Language.GO to Color(0xFF00ADD8)
        ext == "rs" -> AllIcons.Language.Rust to Color(0xFFCE422B)
        ext == "html" || ext == "htm" -> AllIcons.FileTypes.Html to Color(0xFFE44D26)
        ext == "css" || ext == "scss" -> AllIcons.FileTypes.Css to Color(0xFF264DE4)
        ext == "xml" -> AllIcons.FileTypes.Xml to Color(0xFF0060AC)
        ext == "json" -> AllIcons.FileTypes.Json to Color(0xFFBBBBBB)
        ext == "yaml" || ext == "yml" -> AllIcons.FileTypes.Yaml to Color(0xFFCB171E)
        ext == "md" -> AllIcons.FileTypes.Text to Color(0xFF519ABA)
        ext == "sql" -> AllIcons.FileTypes.Text to Color(0xFFE38C00)
        ext == "sh" || ext == "bash" -> AllIcons.Nodes.Console to Color(0xFF4EAA25)
        else -> AllIcons.FileTypes.Text to Color(0xFFBBBBBB)
    }
}
