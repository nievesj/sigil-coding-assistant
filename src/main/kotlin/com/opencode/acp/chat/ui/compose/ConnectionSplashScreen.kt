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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import com.opencode.acp.chat.model.ConnectionErrorReason
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.model.ReadyState
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.viewmodel.ChatViewModel
import com.opencode.acp.chat.ui.theme.ChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * Splash screen shown when the plugin is not connected to the OpenCode server.
 * Covers the entire chat window with connection status, guidance, and controls.
 */
@Composable
fun ConnectionSplashScreen(
    connectionState: ConnectionState,
    connectionErrorReason: ConnectionErrorReason? = null,
    readyState: ReadyState = ReadyState.NOT_STARTED,
    onConnect: () -> Unit = {},
    onRetry: () -> Unit = {},
    onStop: () -> Unit = {},
    onCancel: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
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
            // Sigil app logo — loaded from /icons/sigil-toolwindow.svg
            // Loaded off the composition thread to avoid a jank frame on first display
            // (SVG I/O + Skia rasterization). SigilLogoLoader caches the result, so the
            // off-thread load only happens once.
            var logoBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            LaunchedEffect(Unit) {
                logoBitmap = withContext(Dispatchers.IO) { SigilLogoLoader.load(128) }
            }
            val bitmap = logoBitmap
            if (bitmap != null) {
                ComposeImage(
                    bitmap = bitmap,
                    contentDescription = "Sigil",
                    modifier = Modifier.size(ChatTheme.dims.splashLogoSize),
                    contentScale = ContentScale.Fit
                )
            } else {
                // Fallback to a generic icon if the SVG fails to load
                Icon(
                    key = AllIconsKeys.General.Information,
                    contentDescription = "Sigil",
                    modifier = Modifier.size(ChatTheme.dims.splashLogoSize),
                    tint = if (connectionState == ConnectionState.ERROR) ChatTheme.colors.component.splashError
                           else ChatTheme.colors.component.splashConnected
                )
            }

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
            val (statusMessage, detailMessage) = resolveStatusMessages(connectionState, connectionErrorReason, readyState)

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

            if (detailMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = detailMessage,
                    fontSize = ChatTheme.fonts.splashSettingsLabel,
                    color = ChatTheme.colors.text.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(420.dp)
                )
            }

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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ActionButton(
                            text = "Retry",
                            icon = AllIconsKeys.Actions.Refresh,
                            onClick = onRetry,
                            backgroundColor = ChatTheme.colors.component.splashRetry
                        )

                        // Show an "Open Settings" helper for configuration-related errors
                        if (connectionErrorReason is ConnectionErrorReason.NoBinaryConfigured
                            || connectionErrorReason is ConnectionErrorReason.BinaryLaunchFailed) {
                            Spacer(modifier = Modifier.height(12.dp))
                            ActionButton(
                                text = "Open Settings",
                                icon = AllIconsKeys.General.Settings,
                                onClick = onOpenSettings,
                                backgroundColor = Color.Transparent,
                                contentColor = ChatTheme.colors.text.secondary
                            )
                        }
                    }
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

/**
 * Returns the main status line and a human-readable detail/hint based on the
 * connection state and, for [ConnectionState.ERROR], the typed error reason.
 */
private fun resolveStatusMessages(
    connectionState: ConnectionState,
    errorReason: ConnectionErrorReason?,
    readyState: ReadyState
): Pair<String, String> {
    return when {
        connectionState == ConnectionState.DISCONNECTED -> "Not connected to OpenCode server" to ""
        connectionState == ConnectionState.CONNECTING -> "Connecting to OpenCode server..." to ""
        connectionState == ConnectionState.CONNECTED && readyState == ReadyState.READY -> "Connected" to ""
        connectionState == ConnectionState.CONNECTED -> when (readyState) {
            ReadyState.INITIALIZING_SERVICE -> "Starting OpenCode service..." to ""
            ReadyState.LOADING_AGENTS -> "Loading agents..." to ""
            ReadyState.LOADING_PROVIDERS -> "Loading models..." to ""
            ReadyState.LOADING_MCP -> "Discovering MCP tools..." to ""
            else -> "Initializing..." to ""
        }
        connectionState == ConnectionState.RECONNECTING -> "Reconnecting..." to ""
        connectionState == ConnectionState.ERROR -> when (errorReason) {
            is ConnectionErrorReason.NoBinaryConfigured -> "No OpenCode binary configured" to
                "Set the path to your opencode executable in Settings → Tools → Sigil, then retry."
            is ConnectionErrorReason.BinaryLaunchFailed -> "Failed to start OpenCode" to
                (errorReason.detail?.let { "Could not launch the binary: $it. Check the path and permissions." }
                    ?: "Could not launch the configured OpenCode binary. Check the path and permissions.")
            is ConnectionErrorReason.ProcessExited -> "OpenCode stopped unexpectedly" to buildString {
                append("The server process exited with code ${errorReason.exitCode}.")
                if (!errorReason.outputTail.isNullOrBlank()) {
                    append("\n\n")
                    // Strip ANSI escape codes (color codes, cursor moves, etc.) that would
                    // render as garbage in Compose Text. Also strips adversarial Unicode
                    // control chars that could mislead the user via RTL override etc.
                    val cleaned = errorReason.outputTail
                        .replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
                        .replace(Regex("[\u202E\u200B\u200E\u200F\u202A-\u202D]"), "")
                    append(cleaned.take(240))
                }
            }
            is ConnectionErrorReason.HealthCheckTimeout -> "OpenCode server did not respond" to
                "The binary started but did not become healthy within 60 seconds. It may be busy, misconfigured, or the wrong executable."
            is ConnectionErrorReason.ReconnectionFailed -> "Reconnection failed" to
                (errorReason.detail ?: "The connection was lost and could not be re-established.")
            is ConnectionErrorReason.ServerUnreachable -> "Server unreachable" to
                "The OpenCode server did not respond after repeated reconnection attempts. It may be down or the port may be blocked. Check the server process and retry."
            is ConnectionErrorReason.Other -> "Connection failed" to
                (errorReason.detail ?: "An unexpected connection error occurred.")
            null -> "Connection failed" to ""
        }
        else -> "Connecting..." to ""
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: IconKey,
    onClick: () -> Unit,
    backgroundColor: Color,
    contentColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val fg = contentColor ?: ChatTheme.colors.text.inverse
    Box(
        modifier = modifier
            .clip(ChatTheme.shapes.splashButtonCornerRadius)
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = if (backgroundColor == Color.Transparent) ChatTheme.colors.component.splashRetry.copy(alpha = 0.5f)
                        else backgroundColor,
                shape = ChatTheme.shapes.splashButtonCornerRadius
            )
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
                tint = fg
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = ChatTheme.fonts.splashStatus,
                fontWeight = ChatTheme.fontWeights.splashError,
                color = fg
            )
        }
    }
}

/**
 * Loads the Sigil app logo from /icons/sigil-toolwindow.svg as a Compose [ImageBitmap].
 * Uses the same Skia SVG rasterization path as [ProviderIconLoader].
 */
private object SigilLogoLoader {
    @Volatile private var cached: androidx.compose.ui.graphics.ImageBitmap? = null
    @Volatile private var attempted = false

    fun load(size: Int = 128): androidx.compose.ui.graphics.ImageBitmap? {
        if (attempted) return cached
        synchronized(this) {
            if (attempted) return cached
            val result = try {
                val bytes = javaClass.getResourceAsStream("/icons/sigil-toolwindow.svg")?.use { it.readBytes() }
                    ?: run { attempted = true; return null }
                val data = org.jetbrains.skia.Data.makeFromBytes(bytes)
                val dom = org.jetbrains.skia.svg.SVGDOM(data)
                val svgWidth = dom.root?.width?.value?.takeIf { it > 0 }?.toInt() ?: size
                val svgHeight = dom.root?.height?.value?.takeIf { it > 0 }?.toInt() ?: size
                val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(size, size)
                val canvas = surface.canvas
                val scale = minOf(size.toFloat() / svgWidth, size.toFloat() / svgHeight)
                canvas.translate((size - svgWidth * scale) / 2f, (size - svgHeight * scale) / 2f)
                canvas.scale(scale, scale)
                dom.render(canvas)
                surface.makeImageSnapshot().toComposeImageBitmap()
            } catch (_: Exception) {
                null
            }
            // Only mark as attempted on success — allows retry on transient failure
            // (e.g., classloader race during plugin load). On permanent failure (resource
            // missing), the null-resource path above sets attempted=true to avoid retrying.
            if (result != null) {
                attempted = true
                cached = result
            }
            return result
        }
    }
}
