package com.opencode.acp.chat.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified

// ── CompositionLocal ────────────────────────────────────────────────────────

val LocalChatTheme = staticCompositionLocalOf<ChatThemeData> {
    error("No ChatThemeData provided — wrap your composable tree in ChatTheme {}")
}

/**
 * Provides [ChatThemeData] to the composition tree.
 * Reads semantic colors from IntelliJ's UIManager via [retrieveColorOrUnspecified].
 * Must be called inside a Compose context (e.g., inside a JewelTheme).
 */
@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    val themeData = rememberChatThemeData()
    CompositionLocalProvider(LocalChatTheme provides themeData) {
        content()
    }
}

/**
 * Convenience accessor: `ChatTheme.colors.surface.primary`
 */
object ChatTheme {
    val colors: ChatThemeData.Colors @Composable get() = LocalChatTheme.current.colors
    val dims: ChatThemeData.Dims @Composable get() = LocalChatTheme.current.dims
    val fonts: ChatThemeData.Fonts @Composable get() = LocalChatTheme.current.fonts
    val fontWeights: ChatThemeData.FontWeights @Composable get() = LocalChatTheme.current.fontWeights
    val shapes: ChatThemeData.Shapes @Composable get() = LocalChatTheme.current.shapes
    val animations: ChatThemeData.Animations @Composable get() = LocalChatTheme.current.animations
}

/**
 * Creates [ChatThemeData] by reading semantic colors from IntelliJ's UIManager.
 * Called once by [ChatTheme] composable. Hardcoded values for chat-specific accents.
 * Wrapped in [remember] to avoid recreating the object every recomposition.
 */
@Composable
private fun rememberChatThemeData(): ChatThemeData {
    // Semantic colors — read from IDE theme
    val panelBg = retrieveColorOrUnspecified("Panel.background")
    val panelFg = retrieveColorOrUnspecified("Panel.foreground")
    val borderColor = retrieveColorOrUnspecified("Component.borderColor")
    val selectionBg = retrieveColorOrUnspecified("List.selectionBackground")
    val selectionFg = retrieveColorOrUnspecified("List.selectionForeground")
    val hoverBg = retrieveColorOrUnspecified("MenuItem.selectionBackground")
    val linkColor = retrieveColorOrUnspecified("Link.activeForeground")
    val errorColor = retrieveColorOrUnspecified("Component.errorFocusColor")
    val disabledFg = retrieveColorOrUnspecified("Label.disabledForeground")

    return remember(panelBg, panelFg, borderColor, selectionBg, selectionFg, hoverBg, linkColor, errorColor, disabledFg) {
        // Derived theme-aware values — adapt to light/dark IDE themes.
        // panelBg/panelFg come from UIManager and are the source of truth for the
        // current IDE theme. All component colors below derive from these so the
        // chat UI stays readable on both light and dark themes.
        val isDarkTheme = panelBg.red * 0.299f + panelBg.green * 0.587f + panelBg.blue * 0.114f < 0.5f
        val surfaceCard = panelBg.copy(red = panelBg.red * 0.9f, green = panelBg.green * 0.9f, blue = panelBg.blue * 0.9f)
        val surfaceDark = panelBg.copy(red = panelBg.red * 0.75f, green = panelBg.green * 0.75f, blue = panelBg.blue * 0.75f)
        val surfaceTableHeader = panelBg.copy(red = panelBg.red * 0.82f, green = panelBg.green * 0.82f, blue = panelBg.blue * 0.82f)
        val textPrimary = panelFg
        val textSecondary = panelFg.copy(alpha = 0.85f)
        val textMuted = panelFg.copy(alpha = 0.5f)
        val subtleBorder = borderColor.copy(alpha = 0.5f)

        ChatThemeData(
        colors = ChatThemeData.Colors(
            surface = ChatThemeData.SurfaceColors(
                primary = panelBg,
                secondary = panelBg.copy(red = panelBg.red * 0.85f, green = panelBg.green * 0.85f, blue = panelBg.blue * 0.85f),
                card = surfaceCard,
                dark = surfaceDark,
                elevated = hoverBg,
                tableHeader = surfaceTableHeader,
            ),
            text = ChatThemeData.TextColors(
                primary = textPrimary,
                secondary = textSecondary,
                muted = textMuted,
                disabled = disabledFg,
                // text.inverse is only used on known-dark or saturated fills (splash bg,
                // checkbox fill, accent blue). White is correct in all themes for those
                // surfaces. Do NOT use text.inverse on theme-aware backgrounds.
                inverse = Color.White,
                link = linkColor,
                error = errorColor,
            ),
            border = ChatThemeData.BorderColors(
                default = borderColor,
                subtle = borderColor.copy(alpha = 0.6f),
                accent = borderColor.copy(alpha = 0.8f),
                selectionBg = selectionBg,
                selectionFg = selectionFg,
            ),
            accent = ChatThemeData.AccentColors(
                green = Color(0xFF6BBE50),
                greenLight = Color(0xFF4EAF4E),
                blue = Color(0xFF3574F0),
                red = Color(0xFFE5534B),
                yellow = Color(0xFFE5A617),
                infoBlue = Color(0xFF589DF6),
                contextGreen = Color(0xFF6BBE50),
                contextYellow = Color(0xFFE5A617),
                contextRed = Color(0xFFE5534B),
                contextUnknown = Color(0xFF808080),
                codeAdded = Color(0xFF7EE787),
                codeDeleted = Color(0xFFFF7B72),
                codeModified = Color(0xFFF0C674),
                inlineCodeBackground = Color.Transparent,
                blockQuoteLine = subtleBorder,
                // blockQuoteText is drawn as text on theme-aware surfaces — derive from panelFg
                blockQuoteText = textMuted,
                pillContainerBg = Color(0x0CFFFFFF),
                overlaySemiTransparent = Color(0x88000000),
                highlightBlueAlpha = Color(0x182196F3),
                highlightBlueBorder = Color(0x402196F3),
                bannerErrorBg = Color(0x1ADB4437),
                bannerErrorBorder = Color(0x40DB4437),
                bannerInfoBg = Color(0x1A589DF6),
                bannerInfoBorder = Color(0x40589DF6),
                userBubbleBg = Color(0xFF3574F0).copy(alpha = 0.12f),
                userAvatarBorder = Color(0xFF3574F0),
                userAvatarFill = Color(0xFF5E9AFF),
                permissionBorder = Color(0x40808080),
                permissionBg = Color(0x10808080),
            ),
            provider = ChatThemeData.ProviderColors(
                anthropic = Color(0xFFCC785C),
                openAI = Color(0xFF10A37F),
                deepSeek = Color(0xFF4D6BFE),
                google = Color(0xFF4285F4),
                ollama = Color(0xFFCCCCCC),
                mistral = Color(0xFFDDA63A),
                cohere = Color(0xFF39D98A),
                groq = Color(0xFFF55036),
                together = Color(0xFF6366F1),
                fireworks = Color(0xFFFF6B35),
                nvidia = Color(0xFF76B900),
                openRouter = Color(0xFF8B5CF6),
                gitHubCopilot = Color(0xFFCCCCCC),
                xAI = Color(0xFFCCCCCC),
                alibaba = Color(0xFFFF6A00),
                miniMax = Color(0xFF6366F1),
                moonshot = Color(0xFFCCCCCC),
                stepFun = Color(0xFFCCCCCC),
                zhipu = Color(0xFF4D6BFE),
                kimi = Color(0xFFCCCCCC),
                colorMap = mapOf(
                    "anthropic" to Color(0xFFCC785C),
                    "openai" to Color(0xFF10A37F),
                    "deepseek" to Color(0xFF4D6BFE),
                    "google" to Color(0xFF4285F4),
                    "ollama-cloud" to Color(0xFFCCCCCC),
                    "mistral" to Color(0xFFDDA63A),
                    "cohere" to Color(0xFF39D98A),
                    "groq" to Color(0xFFF55036),
                    "togetherai" to Color(0xFF6366F1),
                    "fireworks-ai" to Color(0xFFFF6B35),
                    "nvidia" to Color(0xFF76B900),
                    "openrouter" to Color(0xFF8B5CF6),
                    "github-copilot" to Color(0xFFCCCCCC),
                    "xai" to Color(0xFFCCCCCC),
                    "alibaba" to Color(0xFFFF6A00),
                    "minimax" to Color(0xFF6366F1),
                    "moonshotai" to Color(0xFFCCCCCC),
                    "stepfun" to Color(0xFFCCCCCC),
                    "zhipuai" to Color(0xFF4D6BFE),
                    "kimi-for-coding" to Color(0xFFCCCCCC),
                ),
            ),
            file = ChatThemeData.FileColors(
                kotlin = Color(0xFFA97BFF),
                java = Color(0xFFED8B00),
                javaScript = Color(0xFFF7DF1E),
                typeScript = Color(0xFF3178C6),
                python = Color(0xFF3776AB),
                ruby = Color(0xFFCC342D),
                go = Color(0xFF00ADD8),
                rust = Color(0xFFCE422B),
                html = Color(0xFFE44D26),
                css = Color(0xFF264DE4),
                xml = Color(0xFF0060AC),
                json = Color(0xFFBBBBBB),
                yaml = Color(0xFFCB171E),
                markdown = Color(0xFF519ABA),
                sql = Color(0xFFE38C00),
                shell = Color(0xFF4EAA25),
            ),
            tool = ChatThemeData.ToolColors(
                execute = Color(0xFF3574F0),
                edit = Color(0xFF7EE787),
                // read/move are used as TEXT on theme-aware surfaces (tool pill header text,
                // task pill description). Derive from panelFg so they stay readable on light themes.
                read = textSecondary,
                search = Color(0xFFF0C674),
                delete = Color(0xFFFF7B72),
                move = textSecondary,
                fetch = Color(0xFFF0C674),
                // think/switchMode/other are used as text/icon tints on theme-aware surfaces.
                think = textMuted,
                switchMode = textMuted,
                other = textMuted,
            ),
            component = ChatThemeData.ComponentColors(
                // Sidebar shimmer — accent colors, safe as hardcoded (used as icon tints)
                sidebarShimmerCreating = Color(0xFFFFC107),
                sidebarShimmerStreaming = Color(0xFF4CAF50),
                // Splash screen — accent status colors, safe as hardcoded (icons on dark splash bg)
                splashConnected = Color(0xFF4CAF50),
                splashError = Color(0xFFF44336),
                splashRetry = Color(0xFFFF9800),
                // Task/subagent pills — text on theme-aware pill container bg
                taskAccent = Color(0xFF7EE787),
                taskRunning = textMuted,
                taskCompleted = textSecondary,
                taskFailed = Color(0xFFFF7B72),
                taskPending = textMuted,
                // Retry pill — paired palette: bg is a tinted fill, text must contrast with it.
                // Use accent.yellow as the base tint with alpha, and derive text from panelFg
                // so it adapts. On dark themes the tint is dark amber → light text; on light themes
                // the tint is light amber → dark text.
                retryBg = if (isDarkTheme) Color(0xFF5C4A00) else Color(0xFFFFF4D6),
                retryText = if (isDarkTheme) Color(0xFFFFD666) else Color(0xFF6B4A00),
                retryErrorDetail = if (isDarkTheme) Color(0xFFCCAA44) else Color(0xFF8A6A00),
                // Selection prompt
                selectionCheckboxFill = Color(0xFF2196F3),
                // selectionCheckboxBorder is used BOTH as the checkbox border (on theme-aware bg)
                // AND as the option description text color (SelectionPrompt.kt:201). Derive from
                // panelFg so description text is readable on light themes.
                selectionCheckboxBorder = textMuted,
                selectionCustomBullet = Color(0xFF4CAF50),
                // Interrupted / divider — on theme-aware surface
                interruptedDivider = subtleBorder,
                // Markdown table — full unit: bg + text + border together.
                // tableHeaderText uses text.primary for readability; brand green tint applied
                // to the header bg instead (text-on-tinted-bg is the safe pattern).
                tableHeaderText = textPrimary,
                tableCellText = textPrimary,
                tableSeparator = subtleBorder,
                tableHeaderBg = surfaceTableHeader,
                tableHoverBg = surfaceDark,
                tableBorder = subtleBorder,
                tableContainerBg = surfaceCard,
                // Streaming glow animation — accent colors on dark input, safe as hardcoded
                glowTransparent = Color.Transparent,
                glowStart = Color(0xFF4A9EFF).copy(alpha = 0.15f),
                glowPeak = Color(0xFF4A9EFF).copy(alpha = 0.45f),
                glowHot = Color(0xFF00D4FF).copy(alpha = 0.85f),
                // Input area — full unit: bg + text + border + cursor together.
                // Derive from panelBg/panelFg so the input adapts to light/dark themes.
                inputBg = surfaceCard,
                inputBorder = subtleBorder,
                inputCursor = textPrimary,
                inputText = textPrimary,
                inputPlaceholder = disabledFg,
                dragActiveBg = if (isDarkTheme) Color(0xFF2E3A2E) else Color(0xFFE8F5E8),
                // Attachment thumbnails — on theme-aware input bg
                attachmentBg = surfaceDark,
                attachmentRemoveIcon = textSecondary,
                attachmentImageOverlay = Color(0x88000000),
                attachmentImageRemove = if (isDarkTheme) Color(0xFFCCCCCC) else Color(0xFFFFFFFF),
                attachmentFileSize = textMuted,
                // Context panel — labels/values on theme-aware sidebar bg
                contextPanelLabel = textMuted,
                contextPanelValue = textSecondary,
                contextPanelSeparator = subtleBorder,
                contextProgressBarBg = surfaceDark,
                // Indicator — context ring background
                indicatorBg = surfaceDark,
                // Tooltip — derive from panelBg/panelFg for theme adaptation
                tooltipBg = surfaceDark,
                tooltipBorder = subtleBorder,
                tooltipText = textSecondary,
                tooltipMuted = textMuted,
                // Code block — header is on editorBgColor (from EditorColorsScheme), NOT theme-aware.
                // The code block is a special unit: its surfaces come from the editor scheme and
                // are NOT derived from panelBg. Use text.muted for the language label so it stays
                // readable on both light and dark editor schemes (muted = panelFg × 0.5, which
                // is dark-gray on light editor bg and light-gray on dark editor bg).
                codeCopyIcon = textMuted,
                codeLanguageLabel = textMuted,
                // Todo panel — full unit: bg + text together.
                todoBg = surfaceCard,
                todoHeader = textMuted,
                todoPending = textMuted,
                todoInProgress = Color(0xFFE5A617),
                todoCompleted = Color(0xFF6BBE50),
                todoCancelled = textMuted,
                todoAccent = Color(0xFF3574F0),
                todoActiveText = textPrimary,
                // Compaction pill — paired palette like retry pill
                compactionText = if (isDarkTheme) Color(0xFF7EBF7E) else Color(0xFF2E6B2E),
                compactionIcon = if (isDarkTheme) Color(0xFF7EBF7E) else Color(0xFF2E6B2E),
                // Selector hover tint
                selectorHoverTint = Color(0xFF3574F0),
                // Star ratings — accent colors, safe as hardcoded (icons)
                starGold = Color(0xFFE5C100),
                starMuted = textMuted,
                // Selected row — use platform selection color (already read from UIManager)
                selectedRowBg = selectionBg,
                // Thinking indicator — text on theme-aware surface
                thinkingText = textMuted,
                thinkingChevron = textMuted,
                // MIME text — on theme-aware surface
                mimeText = textMuted,
                // Review panel "Added" label — accent color, but used as text. Use a darker
                // green on light themes for readability.
                reviewAddedLabel = if (isDarkTheme) Color(0xFF7CB342) else Color(0xFF3D7A00),
                // Hover background — already theme-aware from UIManager
                hoverBg = hoverBg,
                // Compaction pill background — paired with compactionText above
                compactionBg = if (isDarkTheme) Color(0xFF1A2A1A) else Color(0xFFE8F5E8),
                // Slash command palette row hover — use theme-aware hover bg
                paletteHoverBg = hoverBg,
                // Capability icon accent (vision, etc.) — accent, safe as hardcoded icon tint
                capabilityVision = Color(0xFF4285F4),
                // Splash screen background — intentional dark branding island.
                // Kept dark in all themes for visual consistency with the splash status colors
                // (splashConnected/Error/Retry are hardcoded bright colors that need a dark bg).
                splashBg = Color(0xFF1E1E1E),
            ),
        ),
        dims = ChatThemeData.Dims(
            sidebarWidth = 260.dp,
            sidebarContextWidth = 320.dp,
            sidebarReviewWidth = 260.dp,
            contextIndicatorSize = 28.dp,
            contextRingStroke = 2.dp,
            contextRingGap = 2.dp,
            contextTooltipWidth = 240.dp,
            contextTooltipPadding = 8.dp,
            contextErrorBadgeBorder = 1.dp,
            inputOuterPaddingH = 12.dp,
            inputOuterPaddingV = 8.dp,
            inputCornerRadius = 12.dp,
            inputMinHeight = 56.dp,
            inputMaxHeight = 156.dp,
            inputLineHeight = 20.dp,
            inputContentPaddingH = 12.dp,
            inputContentPaddingV = 8.dp,
            inputDragBorderWidth = 2.dp,
            inputDefaultBorderWidth = 1.dp,
            actionButtonSize = 28.dp,
            actionButtonCornerRadius = 6.dp,
            actionIconSize = 16.dp,
            stopIconSize = 12.dp,
            attachmentThumbnailSize = 48.dp,
            attachmentThumbnailCornerRadius = 6.dp,
            attachmentChipPaddingH = 6.dp,
            attachmentChipPaddingV = 4.dp,
            attachmentFileIconSize = 14.dp,
            attachmentImageRemoveSize = 16.dp,
            attachmentImageRemoveBadge = 10.dp,
            attachmentFileRemoveBadge = 10.dp,
            modelPickerButtonSize = 32.dp,
            modelPickerPanelWidthMin = 140.dp,
            modelPickerPanelWidthMax = 240.dp,
            modelPickerPanelMaxHeight = 320.dp,
            modelPickerPanelCornerRadius = 8.dp,
            modelPickerRowHeight = 32.dp,
            selectorSize = 28.dp,
            selectorCornerRadius = 6.dp,
            messageBubbleCornerRadius = 8.dp,
            messagePaddingH = 12.dp,
            messagePaddingV = 4.dp,
            userAvatarSize = 28.dp,
            codeBlockCornerRadius = 8.dp,
            codeHeaderPaddingH = 12.dp,
            codeHeaderPaddingV = 4.dp,
            codeCopyIconSize = 14.dp,
            codeLanguageIconSize = 16.dp,
            toolPillCornerRadius = 8.dp,
            toolAccentStripWidth = 3.dp,
            toolAccentStripHeight = 40.dp,
            toolAccentStripCornerRadius = 1.5.dp,
            sessionRowCornerRadius = 6.dp,
            sessionRowPaddingH = 10.dp,
            sessionRowPaddingV = 8.dp,
            sessionChevronSize = 16.dp,
            sessionIconSize = 14.dp,
            sessionIndentPerLevel = 16.dp,
            contextProgressBarHeight = 18.dp,
            contextProgressBarCornerRadius = 4.dp,
            todoCornerRadius = 8.dp,
            todoPaddingH = 12.dp,
            todoPaddingV = 6.dp,
            todoStatusIconSize = 12.dp,
            todoChevronSize = 12.dp,
            paletteCornerRadius = 8.dp,
            paletteMaxWidth = 440.dp,
            paletteRowCornerRadius = 4.dp,
            permissionCornerRadius = 4.dp,
            permissionBorderWidth = 1.dp,
            permissionPaddingH = 8.dp,
            permissionPaddingV = 4.dp,
            selectionCornerRadius = 8.dp,
            selectionBorderWidth = 1.dp,
            selectionCheckboxSize = 18.dp,
            selectionCheckboxCornerRadius = 3.dp,
            selectionRowCornerRadius = 4.dp,
            selectionMaxHeight = 240.dp,
            bannerBorderWidth = 1.dp,
            bannerCornerRadius = 4.dp,
            bannerPaddingH = 8.dp,
            bannerPaddingV = 4.dp,
            bannerIconSize = 16.dp,
            splashLogoSize = 64.dp,
            splashIndicatorSize = 16.dp,
            splashSettingsDotSize = 12.dp,
            splashButtonCornerRadius = 8.dp,
            thinkingSpinnerSize = 16.dp,
            thinkingStreamingSpinnerSize = 14.dp,
            reviewFileIconSize = 16.dp,
            reviewOpenFileIconSize = 18.dp,
            overlayCornerRadius = 8.dp,
        ),
        fonts = ChatThemeData.Fonts(
            inputText = 13.sp,
            inputPlaceholder = 13.sp,
            attachmentFileName = 12.sp,
            attachmentFileSize = 11.sp,
            messageBody = 13.sp,
            messageOrderedListItem = 14.sp,
            messagePatchFileCount = 12.sp,
            messagePatchFileName = 11.sp,
            messageFileName = 12.sp,
            messageFileMime = 10.sp,
            messageInterrupted = 12.sp,
            messageAgentBadge = 11.sp,
            messageStepFinish = 11.sp,
            messageError = 11.sp,
            codeBody = 13.sp,
            codeLanguageLabel = 11.sp,
            codeCopiedLabel = 12.sp,
            toolKindLabel = 13.sp,
            toolFileName = 12.sp,
            toolLineDelta = 12.sp,
            toolTaskDescription = 12.sp,
            toolTaskStatus = 11.sp,
            thinkingLabel = 13.sp,
            thinkingChevron = 16.sp,
            contextPercent = 10.sp,
            contextErrorBadge = 8.sp,
            contextTooltipLabel = 10.sp,
            contextTooltipValue = 11.sp,
            contextTooltipSub = 9.sp,
            contextPanelTitle = 12.sp,
            contextSectionHeader = 12.sp,
            contextDetailLabel = 11.sp,
            contextDetailValue = 11.sp,
            contextProgressBarPercent = 7.sp,
            sessionTitle = 12.sp,
            sessionFooter = 11.sp,
            sessionDialogTitle = 14.sp,
            sessionDialogBody = 12.sp,
            paletteCommand = 12.sp,
            paletteDescription = 11.sp,
            paletteEmpty = 12.sp,
            pickerModelName = 12.sp,
            pickerContextWindow = 10.sp,
            pickerProviderLetter = 9.sp,
            pickerSectionHeader = 11.sp,
            pickerSearchPlaceholder = 12.sp,
            pickerFooter = 10.sp,
            todoHeader = 11.sp,
            todoCount = 10.sp,
            todoContent = 11.sp,
            todoMoreHint = 10.sp,
            selectorChip = 12.sp,
            selectorItem = 12.sp,
            bannerText = 13.sp,
            splashTitle = 24.sp,
            splashStatus = 14.sp,
            splashSettingsLabel = 12.sp,
            splashError = 14.sp,
            tableCell = 13.sp,
            attachMenuFileName = 12.sp,
            attachMenuFilePath = 10.sp,
            attachMenuSectionLabel = 11.sp,
            attachMenuSearchPlaceholder = 12.sp,
            reviewFileName = 12.sp,
            reviewStatusLabel = 10.sp,
            reviewFilePath = 10.sp,
            reviewLineDelta = 11.sp,
            reviewLoading = 12.sp,
            reviewEmpty = 12.sp,
            reviewError = 12.sp,
            permissionDescription = 12.sp,
            selectionQuestion = 14.sp,
            selectionSubtitle = 12.sp,
            selectionCustomBullet = 16.sp,
            selectionCheckmark = 12.sp,
            selectionOptionTitle = 13.sp,
            selectionOptionDescription = 11.sp,
        ),
        fontWeights = ChatThemeData.FontWeights(
            sectionHeader = FontWeight.Bold,
            badge = FontWeight.Medium,
            semibold = FontWeight.SemiBold,
            detailLabel = FontWeight.SemiBold,
            detailValue = FontWeight.Normal,
            contextPercent = FontWeight.Bold,
            todoHeader = FontWeight.SemiBold,
            selectionQuestion = FontWeight.Bold,
            selectionCheckmark = FontWeight.Bold,
            selectionOptionTitle = FontWeight.Medium,
            commandName = FontWeight.SemiBold,
            toolKindLabel = FontWeight.Bold,
            toolLineDelta = FontWeight.Medium,
            bannerText = FontWeight.Medium,
            splashTitle = FontWeight.Bold,
            splashError = FontWeight.Medium,
            tableHeader = FontWeight.Bold,
            sessionTitleSelected = FontWeight.Medium,
            sessionTitleUnselected = FontWeight.Normal,
        ),
        shapes = ChatThemeData.Shapes(
            inputCornerRadius = RoundedCornerShape(12.dp),
            attachmentCornerRadius = RoundedCornerShape(6.dp),
            actionButtonCornerRadius = RoundedCornerShape(6.dp),
            messageBubbleCornerRadius = RoundedCornerShape(8.dp),
            codeBlockCornerRadius = RoundedCornerShape(8.dp),
            toolPillCornerRadius = RoundedCornerShape(8.dp),
            toolAccentCornerRadius = RoundedCornerShape(1.5.dp),
            sessionRowCornerRadius = RoundedCornerShape(6.dp),
            contextIndicatorShape = CircleShape,
            contextErrorBadgeShape = CircleShape,
            contextTooltipCornerRadius = RoundedCornerShape(6.dp),
            contextProgressBarCornerRadius = RoundedCornerShape(4.dp),
            todoCornerRadius = RoundedCornerShape(8.dp),
            paletteCornerRadius = RoundedCornerShape(8.dp),
            paletteRowCornerRadius = RoundedCornerShape(4.dp),
            permissionCornerRadius = RoundedCornerShape(4.dp),
            selectionCornerRadius = RoundedCornerShape(8.dp),
            selectionCheckboxCornerRadius = RoundedCornerShape(3.dp),
            selectionRowCornerRadius = RoundedCornerShape(4.dp),
            bannerCornerRadius = RoundedCornerShape(4.dp),
            splashButtonCornerRadius = RoundedCornerShape(8.dp),
            splashSettingsCornerRadius = RoundedCornerShape(2.dp),
            chipCornerRadius = RoundedCornerShape(6.dp),
            pickerCornerRadius = RoundedCornerShape(8.dp),
            pickerRowCornerRadius = RoundedCornerShape(4.dp),
            modelPickerButtonShape = CircleShape,
            fileChangeRowCornerRadius = RoundedCornerShape(4.dp),
            errorBadgeCornerRadius = RoundedCornerShape(4.dp),
            avatarShape = CircleShape,
            imageRemoveBadgeShape = CircleShape,
            fileRemoveBadgeShape = CircleShape,
            searchIconShape = CircleShape,
            splashSettingsIndicatorShape = RoundedCornerShape(2.dp),
            sidebarLeftCornerRadius = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
            overlayCornerRadius = RoundedCornerShape(8.dp),
            dialogCornerRadius = RoundedCornerShape(8.dp),
            retryPillCornerRadius = RoundedCornerShape(4.dp),
            attachMenuCornerRadius = RoundedCornerShape(8.dp),
            attachFileRowCornerRadius = RoundedCornerShape(4.dp),
            tableCornerRadius = RoundedCornerShape(8.dp),
        ),
        animations = ChatThemeData.Animations(
            glowPulseMs = 2500,
            contextPulseMs = 800,
            shimmerSweepMs = 1500,
            chipHoverMs = 150,
            thinkingIndicatorDelayMs = 300L,
        ),
    )
    }
}
