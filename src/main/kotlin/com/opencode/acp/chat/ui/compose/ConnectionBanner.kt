package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencode.acp.chat.model.ConnectionErrorReason
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
fun ConnectionBanner(
    state: ConnectionState,
    errorReason: ConnectionErrorReason? = null,
    onRetry: () -> Unit
) {
    if (state == ConnectionState.CONNECTED) return

    val bannerText = when (state) {
        ConnectionState.DISCONNECTED -> "Not connected to OpenCode"
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.RECONNECTING -> "Reconnecting..."
        ConnectionState.ERROR -> when (errorReason) {
            is ConnectionErrorReason.NoBinaryConfigured -> "No OpenCode binary configured"
            is ConnectionErrorReason.BinaryLaunchFailed -> "Failed to launch OpenCode binary"
            is ConnectionErrorReason.ProcessExited -> "OpenCode process stopped (exit ${errorReason.exitCode})"
            is ConnectionErrorReason.HealthCheckTimeout -> "OpenCode server did not respond in time"
            is ConnectionErrorReason.ReconnectionFailed -> "Reconnection failed"
            is ConnectionErrorReason.ServerUnreachable -> "Server unreachable — reconnection attempts exhausted"
            is ConnectionErrorReason.Other -> errorReason.detail ?: "Connection failed"
            null -> "Connection failed"
        }
        ConnectionState.CONNECTED -> "" // unreachable — early return at line 29
    }

    when (state) {
        ConnectionState.DISCONNECTED -> BannerRow(
            text = bannerText,
            retryLink = onRetry
        )
        ConnectionState.CONNECTING -> BannerRow(
            text = bannerText
        )
        ConnectionState.RECONNECTING -> BannerRow(
            text = bannerText
        )
        ConnectionState.ERROR -> BannerRow(
            text = bannerText,
            isError = true,
            retryLink = onRetry
        )
    }
}

@Composable
private fun BannerRow(
    text: String,
    isError: Boolean = false,
    retryLink: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isError) ChatTheme.colors.accent.bannerErrorBg else ChatTheme.colors.accent.bannerInfoBg,
                shape = ChatTheme.shapes.bannerCornerRadius
            )
            .border(
                width = ChatTheme.dims.bannerBorderWidth,
                color = if (isError) ChatTheme.colors.accent.bannerErrorBorder else ChatTheme.colors.accent.bannerInfoBorder,
                shape = ChatTheme.shapes.bannerCornerRadius
            )
            .padding(horizontal = ChatTheme.dims.bannerPaddingH, vertical = ChatTheme.dims.bannerPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            key = if (isError) AllIconsKeys.General.BalloonError
                  else AllIconsKeys.General.BalloonInformation,
            contentDescription = null,
            modifier = Modifier.size(ChatTheme.dims.bannerIconSize)
        )
        Text(
            text = text,
            fontWeight = ChatTheme.fontWeights.bannerText
        )
        if (retryLink != null) {
            Spacer(Modifier.weight(1f))
            Link("Retry", onClick = retryLink)
        }
    }
}
