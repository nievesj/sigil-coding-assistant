package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opencode.acp.chat.model.SelectionOption
import com.opencode.acp.chat.model.SelectionPrompt
import com.opencode.acp.chat.model.SelectionResponse
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@Composable
fun SelectionPrompt(
    prompt: SelectionPrompt,
    onSubmit: (SelectionResponse) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedIndices = remember { mutableStateOf(setOf<Int>()) }
    val customInputState = remember { TextFieldState() }

    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .border(
                width = ChatTheme.dims.selectionBorderWidth,
                color = ChatTheme.colors.border.default,
                shape = ChatTheme.shapes.selectionCornerRadius
            )
            .background(
                color = ChatTheme.colors.surface.card,
                shape = ChatTheme.shapes.selectionCornerRadius
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        Column {
            Text(
                text = prompt.question,
                fontWeight = ChatTheme.fontWeights.selectionQuestion,
                fontSize = ChatTheme.fonts.selectionQuestion
            )
            if (prompt.subtitle != null) {
                Text(
                    text = prompt.subtitle,
                    color = ChatTheme.colors.text.muted,
                    fontSize = ChatTheme.fonts.selectionSubtitle
                )
            }
        }

        // Options list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = ChatTheme.dims.selectionMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            prompt.options.forEachIndexed { index, option ->
                CheckboxOptionRow(
                    option = option,
                    isSelected = index in selectedIndices.value,
                    onToggle = {
                        selectedIndices.value = if (prompt.multiSelect) {
                            if (index in selectedIndices.value) {
                                selectedIndices.value - index
                            } else {
                                selectedIndices.value + index
                            }
                        } else {
                            setOf(index)
                        }
                    }
                )
            }
        }

        // Custom input
        if (prompt.allowCustomInput) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "\u2022",
                    color = ChatTheme.colors.component.selectionCustomBullet,
                    fontSize = ChatTheme.fonts.selectionCustomBullet,
                    modifier = Modifier.width(20.dp)
                )
                TextField(
                    state = customInputState,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type your own answer", color = ChatTheme.colors.text.muted) }
                )
            }
        }

        // Buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.End)
        ) {
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss) {
                Text("Dismiss")
            }
            DefaultButton(
                onClick = {
                    onSubmit(
                        SelectionResponse(
                            selectedIndices = selectedIndices.value,
                            customInput = customInputState.text.toString().ifBlank { null }
                        )
                    )
                },
                enabled = selectedIndices.value.isNotEmpty() || customInputState.text.isNotBlank()
            ) {
                Text("Submit")
            }
        }
    }
}

@Composable
private fun CheckboxOptionRow(
    option: SelectionOption,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ChatTheme.shapes.selectionRowCornerRadius)
            .clickable(onClick = onToggle)
            .background(
                if (isSelected) ChatTheme.colors.accent.highlightBlueAlpha else Color.Transparent,
                ChatTheme.shapes.selectionRowCornerRadius
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Checkbox indicator
        Box(
            modifier = Modifier
                .size(ChatTheme.dims.selectionCheckboxSize)
                .then(
                    if (isSelected) {
                        Modifier.background(ChatTheme.colors.component.selectionCheckboxFill, ChatTheme.shapes.selectionCheckboxCornerRadius)
                    } else {
                        Modifier.border(ChatTheme.dims.selectionBorderWidth, ChatTheme.colors.component.selectionCheckboxBorder, ChatTheme.shapes.selectionCheckboxCornerRadius)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Text(
                    text = "\u2713",
                    color = ChatTheme.colors.text.inverse,
                    fontSize = ChatTheme.fonts.selectionCheckmark,
                    fontWeight = ChatTheme.fontWeights.selectionCheckmark
                )
            }
        }

        // Option text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.title,
                fontSize = ChatTheme.fonts.selectionOptionTitle,
                fontWeight = ChatTheme.fontWeights.selectionOptionTitle
            )
            Text(
                text = option.description,
                fontSize = ChatTheme.fonts.selectionOptionDescription,
                color = ChatTheme.colors.component.selectionCheckboxBorder
            )
        }
    }
}
