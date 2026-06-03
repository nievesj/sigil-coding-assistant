package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Text

@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Text(
            text = " Thinking...",
            fontStyle = FontStyle.Italic,
            color = Color.Gray
        )
    }
}

@Composable
fun ThinkingPill(content: String, modifier: Modifier = Modifier) {
    // Full thinking content displayed as muted text — no truncation, no pill border
    Text(
        text = content,
        fontStyle = FontStyle.Italic,
        color = Color(0xFF9E9E9E), // muted gray, lighter than response text
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
