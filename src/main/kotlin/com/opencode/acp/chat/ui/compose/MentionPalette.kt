package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import com.opencode.acp.chat.ui.theme.ChatTheme

/**
 * File mention autocomplete palette. Shown when the user types `@` in the input.
 *
 * Displays matching files with open editor files prioritized first (blue "Open Files"
 * section header + indicator dot), followed by other matching files.
 *
 * Filtering and selection are owned by the caller (InputArea); this composable only renders.
 * Modeled after [SlashCommandPalette].
 *
 * @param filtered the pre-filtered list of files to display (open files should come first)
 * @param selectedIndex the index of the currently highlighted file
 * @param onSelectedIndexChange called when hover or keyboard changes the highlighted index
 * @param onFileSelected callback with the selected [RecentFile] (click or Enter)
 * @param onDismiss callback when the palette should close (Escape, click outside)
 */
@Composable
fun MentionPalette(
    filtered: List<RecentFile>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onFileSelected: (RecentFile) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val shapes = ChatTheme.shapes
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts

    if (filtered.isEmpty()) {
        Column(
            modifier = modifier
                .clip(shapes.paletteCornerRadius)
                .background(colors.surface.dark)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "No matching files",
                fontSize = fonts.paletteEmpty,
                color = colors.text.muted,
            )
        }
        return
    }

    // Split into open files (prioritized) and other files
    val openFiles = filtered.filter { it.isOpen }
    val otherFiles = filtered.filter { !it.isOpen }

    Column(
        modifier = modifier
            .clip(shapes.paletteCornerRadius)
            .background(colors.surface.dark)
            .widthIn(max = dims.paletteMaxWidth)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // --- Open Files section ---
        if (openFiles.isNotEmpty()) {
            Text(
                text = "Open Files",
                fontSize = fonts.attachMenuSectionLabel,
                color = colors.accent.blue,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            openFiles.forEachIndexed { index, file ->
                MentionItem(
                    file = file,
                    isSelected = index == selectedIndex,
                    showOpenDot = true,
                    onHover = { onSelectedIndexChange(index) },
                    onClick = { onFileSelected(file) },
                )
            }
        }

        // --- Other files section ---
        if (otherFiles.isNotEmpty()) {
            val headerOffset = openFiles.size
            Text(
                text = "Files",
                fontSize = fonts.attachMenuSectionLabel,
                color = colors.text.muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            otherFiles.forEachIndexed { index, file ->
                val globalIndex = headerOffset + index
                MentionItem(
                    file = file,
                    isSelected = globalIndex == selectedIndex,
                    showOpenDot = false,
                    onHover = { onSelectedIndexChange(globalIndex) },
                    onClick = { onFileSelected(file) },
                )
            }
        }
    }
}

/**
 * Single file mention item row.
 */
@Composable
private fun MentionItem(
    file: RecentFile,
    isSelected: Boolean,
    showOpenDot: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = ChatTheme.colors
    val shapes = ChatTheme.shapes
    val fonts = ChatTheme.fonts

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) onHover()
    }

    val iconInfo = FileTypeIcons.iconKeyForFileName(file.name) to
        FileTypeIcons.fileColorForExtension(file.name.substringAfterLast('.', "").lowercase())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.paletteRowCornerRadius)
            .background(if (isSelected) colors.component.paletteHoverBg else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // File type icon
        Box(
            modifier = Modifier.width(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                key = iconInfo.first,
                contentDescription = file.name,
                modifier = Modifier.size(14.dp),
                tint = iconInfo.second,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // File name
        Text(
            text = file.name,
            fontSize = fonts.paletteCommand,
            color = colors.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 80.dp, max = 200.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        // File path (relative-ish, truncated)
        Text(
            text = file.path,
            fontSize = fonts.paletteDescription,
            color = colors.text.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Open-file indicator dot
        if (showOpenDot) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(shapes.paletteRowCornerRadius)
                    .background(colors.accent.blue),
            )
        }
    }
}