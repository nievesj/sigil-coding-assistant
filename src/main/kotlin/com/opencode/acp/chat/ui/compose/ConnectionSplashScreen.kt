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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.model.ReadyState
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.viewmodel.ChatViewModel
import com.opencode.acp.chat.ui.theme.ChatTheme
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
    readyState: ReadyState = ReadyState.NOT_STARTED,
    onConnect: () -> Unit = {},
    onRetry: () -> Unit = {},
    onStop: () -> Unit = {},
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val settings = remember { OpenCodeSettingsState.getInstance() }
    var autoConnect by remember { mutableStateOf(settings.autoConnect) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ChatTheme.colors.component.splashBg),
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
                contentDescription = "Sigil",
                modifier = Modifier.size(ChatTheme.dims.splashLogoSize),
                tint = ChatTheme.colors.component.splashConnected
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            Text(
                text = "Sigil",
                fontSize = ChatTheme.fonts.splashTitle,
                fontWeight = ChatTheme.fontWeights.splashTitle,
                color = ChatTheme.colors.text.inverse
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status message
            val statusMessage = when {
                connectionState == ConnectionState.DISCONNECTED -> "Not connected to OpenCode server"
                connectionState == ConnectionState.CONNECTING -> "Connecting to OpenCode server..."
                connectionState == ConnectionState.CONNECTED && readyState == ReadyState.READY -> "Connected"
                connectionState == ConnectionState.CONNECTED -> when (readyState) {
                    ReadyState.INITIALIZING_SERVICE -> "Starting OpenCode service..."
                    ReadyState.LOADING_AGENTS -> "Loading agents..."
                    ReadyState.LOADING_PROVIDERS -> "Loading models..."
                    ReadyState.LOADING_MCP -> "Discovering MCP tools..."
                    else -> "Initializing..."
                }
                connectionState == ConnectionState.RECONNECTING -> "Reconnecting..."
                connectionState == ConnectionState.ERROR -> "Connection failed"
                else -> "Connecting..."
            }

            val statusColor = when {
                connectionState == ConnectionState.CONNECTED && readyState == ReadyState.READY -> ChatTheme.colors.component.splashConnected
                connectionState == ConnectionState.CONNECTED -> ChatTheme.colors.component.sidebarShimmerCreating
                connectionState == ConnectionState.ERROR -> ChatTheme.colors.component.splashError
                connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.RECONNECTING -> ChatTheme.colors.component.sidebarShimmerCreating
                else -> ChatTheme.colors.text.muted
            }

            Text(
                text = statusMessage,
                fontSize = ChatTheme.fonts.splashStatus,
                color = statusColor,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Action buttons
            when {
                connectionState == ConnectionState.DISCONNECTED -> {
                    ActionButton(
                        text = "Connect",
                        icon = AllIconsKeys.Actions.Execute,
                        onClick = onConnect,
                        backgroundColor = ChatTheme.colors.component.splashConnected
                    )
                }
                connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.RECONNECTING -> {
                    ActionButton(
                        text = "Stop",
                        icon = AllIconsKeys.Actions.Suspend,
                        onClick = onStop,
                        backgroundColor = ChatTheme.colors.component.splashError
                    )
                }
                connectionState == ConnectionState.ERROR -> {
                    ActionButton(
                        text = "Retry",
                        icon = AllIconsKeys.Actions.Refresh,
                        onClick = onRetry,
                        backgroundColor = ChatTheme.colors.component.splashRetry
                    )
                }
                connectionState == ConnectionState.CONNECTED && readyState != ReadyState.READY -> {
                    ActionButton(
                        text = "Cancel",
                        icon = AllIconsKeys.Actions.Close,
                        onClick = onCancel,
                        backgroundColor = ChatTheme.colors.component.splashError
                    )
                }
                else -> {
                    // Connected and ready — no button
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Auto-connect checkbox
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(ChatTheme.shapes.splashButtonCornerRadius)
                    .clickable {
                        autoConnect = !autoConnect
                        settings.autoConnect = autoConnect
                    }
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(ChatTheme.dims.splashIndicatorSize)
                        .border(
                            width = 1.dp,
                            color = if (autoConnect) ChatTheme.colors.component.splashConnected else ChatTheme.colors.text.muted,
                            shape = ChatTheme.shapes.splashSettingsCornerRadius
                        )
                        .background(
                            color = if (autoConnect) ChatTheme.colors.component.splashConnected else Color.Transparent,
                            shape = ChatTheme.shapes.splashSettingsCornerRadius
                        ),
                    contentAlignment = Alignment.Center
                ) {
                if (autoConnect) {
                    Icon(
                        key = AllIconsKeys.Actions.Checked,
                        contentDescription = "Checked",
                        modifier = Modifier.size(ChatTheme.dims.splashSettingsDotSize),
                        tint = ChatTheme.colors.text.inverse
                    )
                }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Connect automatically when plugin opens",
                    fontSize = ChatTheme.fonts.splashSettingsLabel,
                    color = ChatTheme.colors.text.secondary
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
            .clip(ChatTheme.shapes.splashButtonCornerRadius)
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
                modifier = Modifier.size(ChatTheme.dims.splashIndicatorSize),
                tint = ChatTheme.colors.text.inverse
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = ChatTheme.fonts.splashStatus,
                fontWeight = ChatTheme.fontWeights.splashError,
                color = ChatTheme.colors.text.inverse
            )
        }
    }
}
