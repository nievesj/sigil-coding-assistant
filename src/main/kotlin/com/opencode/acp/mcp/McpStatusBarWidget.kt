package com.opencode.acp.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.ui.ClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.config.settings.OpenCodeSettingsConfigurable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

private val logger = KotlinLogging.logger {}

// ── Color helpers ──────────────────────────────────────────────────────────
private fun successColor(): Color = JBColor(0x499C54, 0x6BBE50)
private fun errorColor(): Color = JBColor(0xDB4437, 0xE55341)
private fun warningColor(): Color = JBColor(0xD9A300, 0xE5C100)
private fun infoColor(): Color = JBColor(0x999999, 0x888888)
private fun panelBg(): Color = JBColor(Color.WHITE, Color(45, 45, 45))

/**
 * Status bar widget factory for MCP server status.
 */
class McpStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "OpenCodeMCP"
    override fun getDisplayName(): String = "OpenCode MCP Servers"
    override fun isAvailable(project: Project): Boolean =
        com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance().enableIntellijMcp
    override fun createWidget(project: Project): StatusBarWidget = McpStatusBarWidget(project)
}

/**
 * Status bar widget showing MCP server connection status.
 *
 * Follows the same pattern as RsExternalLinterWidget and MemoryUsagePanel:
 * - Implements CustomStatusBarWidget for getComponent()
 * - Uses TextPanel.WithIconAndArrows() as the rendered component
 * - TextPanel is what the platform's status bar layout knows how to render
 */
class McpStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusBar: StatusBar? = null
    private var collectionJob: Job? = null

    @Volatile private var connected = false
    @Volatile private var serverStatuses: Map<String, McpConnectionStatus> = emptyMap()

    private val component = TextPanel.WithIconAndArrows().apply {
        setTextAlignment(java.awt.Component.CENTER_ALIGNMENT)
        border = JBUI.CurrentTheme.StatusBar.Widget.border()
        isFocusable = false
    }

    init {
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                showPopup(event)
                return true
            }
        }.installOn(component, true)
    }

    override fun ID(): String = "OpenCodeMCP"

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        collectionJob = scope.launch {
            project.service<OpenCodeService>().mcpServerStatuses.collect { statuses ->
                serverStatuses = statuses
                connected = statuses.values.any { it.state == McpConnectionState.CONNECTED }
                ApplicationManager.getApplication().invokeLater {
                    updateComponent()
                    statusBar.updateWidget(ID())
                }
            }
        }
        updateComponent()
        statusBar.updateWidget(ID())
    }

    override fun dispose() {
        collectionJob?.cancel()
        scope.cancel()
    }

    override fun getComponent(): JComponent = component

    private fun updateComponent() {
        val accent = if (connected) successColor() else infoColor()
        component.text = ""
        component.toolTipText = getTooltipText()
        component.icon = McpIcon(connected, accent)
        component.repaint()
    }

    private fun getTooltipText(): String {
        val s = serverStatuses
        if (s.isEmpty()) return "MCP: No servers configured"
        val c = s.values.count { it.state == McpConnectionState.CONNECTED }
        return "MCP: $c/${s.size} servers connected"
    }

    // ── Custom icon ─────────────────────────────────────────────────────────

    private class McpIcon(private val connected: Boolean, private val accent: Color) : Icon {
        override fun getIconWidth(): Int = 16
        override fun getIconHeight(): Int = 16

        override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.translate(x, y)

            val w = iconWidth; val h = iconHeight

            // Server stack: 3 horizontal bars
            val bw = 10; val bh = 2; val gap = 2
            val sx = (w - bw) / 2
            val sy = (h - (bh * 3 + gap * 2)) / 2
            g2.color = accent
            for (i in 0..2) g2.fillRoundRect(sx, sy + i * (bh + gap), bw, bh, 2, 2)

            // Dot overlay (top-right)
            val ds = 6
            g2.fillOval(w - ds - 1, 1, ds, ds)
            g2.color = panelBg()
            g2.drawOval(w - ds - 1, 1, ds, ds)

            g2.translate(-x, -y)
            g2.dispose()
        }
    }

    // ── Popup ─────────────────────────────────────────────────────────────

    private fun showPopup(e: MouseEvent) {
        val statuses = serverStatuses
        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            background = panelBg()
        }

        panel.add(JLabel("MCP Servers").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        }, BorderLayout.NORTH)

        val serverPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = panelBg()
        }

        if (statuses.isEmpty()) {
            serverPanel.add(JLabel("No MCP servers configured").apply {
                foreground = JBColor.GRAY
                border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
            })
        } else {
            for ((name, status) in statuses) {
                val dotColor = when (status.state) {
                    McpConnectionState.CONNECTED -> successColor()
                    McpConnectionState.ERROR -> errorColor()
                    McpConnectionState.REGISTERING, McpConnectionState.DETECTING -> warningColor()
                    McpConnectionState.DISCONNECTED -> infoColor()
                }
                val infoText = when {
                    status.state == McpConnectionState.CONNECTED && status.toolCount > 0 ->
                        "$name (${status.toolCount} tools)"
                    status.error != null -> "$name — ${status.error.take(50)}"
                    else -> name
                }
                serverPanel.add(JPanel(BorderLayout()).apply {
                    background = panelBg()
                    border = BorderFactory.createEmptyBorder(3, 0, 3, 0)
                    preferredSize = Dimension(280, 24)
                    add(JLabel("●").apply {
                        foreground = dotColor
                        font = font.deriveFont(10f)
                    }, BorderLayout.WEST)
                    add(JLabel(infoText).apply {
                        font = font.deriveFont(12f)
                        border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
                    }, BorderLayout.CENTER)
                })
            }
        }

        panel.add(serverPanel, BorderLayout.CENTER)

        panel.add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            background = panelBg()
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
            add(JButton("Open Settings").apply {
                addActionListener {
                    SwingUtilities.getWindowAncestor(this)?.dispose()
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project, OpenCodeSettingsConfigurable::class.java
                    )
                }
            })
        }, BorderLayout.SOUTH)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle("MCP Servers")
            .setFocusable(true)
            .setRequestFocus(true)
            .setMovable(false)
            .setCancelOnWindowDeactivation(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        popup.show(RelativePoint(e.component, Point(0, -200)))
    }
}
