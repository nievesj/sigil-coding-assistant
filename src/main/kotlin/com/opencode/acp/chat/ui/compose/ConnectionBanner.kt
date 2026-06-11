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
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
fun ConnectionBanner(
    state: ConnectionState,
    onRetry: () -> Unit
) {
    if (state == ConnectionState.CONNECTED) return

    when (state) {
        ConnectionState.DISCONNECTED -> BannerRow(
            text = "Not connected to OpenCode",
            retryLink = onRetry
        )
        ConnectionState.CONNECTING -> BannerRow(
            text = "Connecting..."
        )
        ConnectionState.RECONNECTING -> BannerRow(
            text = "Reconnecting..."
        )
        ConnectionState.ERROR -> BannerRow(
            text = "Connection failed",
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