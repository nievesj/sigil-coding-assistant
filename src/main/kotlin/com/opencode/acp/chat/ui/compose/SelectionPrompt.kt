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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.SelectionOption
import com.opencode.acp.chat.model.SelectionPrompt
import com.opencode.acp.chat.model.SelectionResponse
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
                width = 1.dp,
                color = Color(0x40808080),
                shape = RoundedCornerShape(8.dp)
            )
            .background(
                color = Color(0x10808080),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        Column {
            Text(
                text = prompt.question,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            if (prompt.subtitle != null) {
                Text(
                    text = prompt.subtitle,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }

        // Options list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
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
                    color = Color(0xFF4CAF50),
                    fontSize = 16.sp,
                    modifier = Modifier.width(20.dp)
                )
                TextField(
                    state = customInputState,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type your own answer", color = Color.Gray) }
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
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .background(
                if (isSelected) Color(0x182196F3) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Checkbox indicator
        Box(
            modifier = Modifier
                .size(18.dp)
                .then(
                    if (isSelected) {
                        Modifier.background(Color(0xFF2196F3), RoundedCornerShape(3.dp))
                    } else {
                        Modifier.border(1.dp, Color.Gray, RoundedCornerShape(3.dp))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Text(
                    text = "\u2713",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Option text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = option.description,
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
}
