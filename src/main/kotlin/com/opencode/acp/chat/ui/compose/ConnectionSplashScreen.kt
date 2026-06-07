package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.project.Project
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.viewmodel.ChatViewModel
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Splash screen shown when the plugin is not connected to the OpenCode server.
 * Covers the entire chat window with connection status and controls.
 */
@Composable
fun ConnectionSplashScreen(
    connectionState: ConnectionState,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onAutoConnectChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = remember { OpenCodeSettingsState.getInstance() }
    var autoConnect by remember { mutableStateOf(settings.autoConnect) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // OpenCode logo/icon
            Icon(
                key = AllIconsKeys.General.Information,
                contentDescription = "OpenCode",
                modifier = Modifier.size(64.dp),
                tint = Color(0xFF4CAF50)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = "OpenCode",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status message
            val statusMessage = when (connectionState) {
                ConnectionState.DISCONNECTED -> "Not connected to OpenCode server"
                ConnectionState.CONNECTING -> "Connecting to OpenCode server..."
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.RECONNECTING -> "Reconnecting..."
                ConnectionState.ERROR -> "Connection failed"
            }

            val statusColor = when (connectionState) {
                ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                ConnectionState.ERROR -> Color(0xFFF44336)
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFC107)
                else -> Color(0xFF9E9E9E)
            }

            Text(
                text = statusMessage,
                fontSize = 14.sp,
                color = statusColor,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Action buttons
            when (connectionState) {
                ConnectionState.DISCONNECTED -> {
                    // Connect button
                    ActionButton(
                        text = "Connect",
                        icon = AllIconsKeys.Actions.Execute,
                        onClick = onConnect,
                        backgroundColor = Color(0xFF4CAF50)
                    )
                }
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                    // Stop button
                    ActionButton(
                        text = "Stop",
                        icon = AllIconsKeys.Actions.Suspend,
                        onClick = onStop,
                        backgroundColor = Color(0xFFF44336)
                    )
                }
                ConnectionState.ERROR -> {
                    // Retry button
                    ActionButton(
                        text = "Retry",
                        icon = AllIconsKeys.Actions.Refresh,
                        onClick = onRetry,
                        backgroundColor = Color(0xFFFF9800)
                    )
                }
                ConnectionState.CONNECTED -> {
                    // Connected - no button needed
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Auto-connect checkbox
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        autoConnect = !autoConnect
                        settings.autoConnect = autoConnect
                        onAutoConnectChanged(autoConnect)
                    }
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .border(
                            width = 1.dp,
                            color = if (autoConnect) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                            shape = RoundedCornerShape(2.dp)
                        )
                        .background(
                            color = if (autoConnect) Color(0xFF4CAF50) else Color.Transparent,
                            shape = RoundedCornerShape(2.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                if (autoConnect) {
                    Icon(
                        key = AllIconsKeys.Actions.Checked,
                        contentDescription = "Checked",
                        modifier = Modifier.size(12.dp),
                        tint = Color.White
                    )
                }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Connect automatically when plugin opens",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB)
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: IconKey,
    onClick: () -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                key = icon,
                contentDescription = text,
                modifier = Modifier.size(16.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}
