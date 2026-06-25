package com.opencode.acp.chat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Immutable class holding all chat UI visual constants.
 * Created once per composition root via [ChatTheme] composable.
 *
 * Semantic colors (backgrounds, text, borders, selection) are read from
 * IntelliJ's UIManager at composition time — they adapt to the user's IDE theme.
 * Chat-specific accent colors (green, red, blue) are hardcoded constants.
 *
 * Colors are grouped into domain-specific sub-classes to avoid JVM method
 * descriptor limits (160+ constructor params in a single class causes ClassFormatError).
 */
@Immutable
data class ChatThemeData(
    val colors: Colors,
    val dims: Dims,
    val fonts: Fonts,
    val fontWeights: FontWeights,
    val shapes: Shapes,
    val animations: Animations,
) {
    // ── Colors: grouped by UI domain ─────────────────────────────────────────

    @Immutable
    data class Colors(
        val surface: SurfaceColors,
        val text: TextColors,
        val border: BorderColors,
        val accent: AccentColors,
        val provider: ProviderColors,
        val file: FileColors,
        val tool: ToolColors,
        val component: ComponentColors,
    )

    /** Backgrounds and surfaces. */
    @Immutable
    data class SurfaceColors(
        val primary: Color,
        val secondary: Color,
        val card: Color,
        val dark: Color,
        val elevated: Color,
        val tableHeader: Color,
    )

    /** Text hierarchy, links, and error text. */
    @Immutable
    data class TextColors(
        val primary: Color,
        val secondary: Color,
        val muted: Color,
        val disabled: Color,
        val inverse: Color,
        val link: Color,
        val error: Color,
    )

    /** Borders, separators, and selection highlight. */
    @Immutable
    data class BorderColors(
        val default: Color,
        val subtle: Color,
        val accent: Color,
        val selectionBg: Color,
        val selectionFg: Color,
    )

    /** Brand palette, context indicators, code diffs, overlays, banners, user bubble, permission. */
    @Immutable
    data class AccentColors(
        // Primary palette
        val green: Color,
        val greenLight: Color,
        val blue: Color,
        val red: Color,
        val yellow: Color,
        val infoBlue: Color,
        // Context indicator
        val contextGreen: Color,
        val contextYellow: Color,
        val contextRed: Color,
        val contextUnknown: Color,
        // Code & diffs
        val codeAdded: Color,
        val codeDeleted: Color,
        val codeModified: Color,
        // Inline code
        val inlineCodeBackground: Color,
        // Block quote
        val blockQuoteLine: Color,
        val blockQuoteText: Color,
        // Tool pill
        val pillContainerBg: Color,
        // Transparency overlays
        val overlaySemiTransparent: Color,
        val highlightBlueAlpha: Color,
        val highlightBlueBorder: Color,
        // Banner backgrounds
        val bannerErrorBg: Color,
        val bannerErrorBorder: Color,
        val bannerInfoBg: Color,
        val bannerInfoBorder: Color,
        // User message bubble
        val userBubbleBg: Color,
        val userAvatarBorder: Color,
        val userAvatarFill: Color,
        // Permission prompt
        val permissionBorder: Color,
        val permissionBg: Color,
    )

    /** Provider brand colors. */
    @Immutable
    data class ProviderColors(
        val anthropic: Color,
        val openAI: Color,
        val deepSeek: Color,
        val google: Color,
        val ollama: Color,
        val mistral: Color,
        val cohere: Color,
        val groq: Color,
        val together: Color,
        val fireworks: Color,
        val nvidia: Color,
        val openRouter: Color,
        val gitHubCopilot: Color,
        val xAI: Color,
        val alibaba: Color,
        val miniMax: Color,
        val moonshot: Color,
        val stepFun: Color,
        val zhipu: Color,
        val kimi: Color,
        /** Maps server providerId strings to color. */
        val colorMap: Map<String, Color>,
    )

    /** File type accent colors. */
    @Immutable
    data class FileColors(
        val kotlin: Color,
        val java: Color,
        val javaScript: Color,
        val typeScript: Color,
        val python: Color,
        val ruby: Color,
        val go: Color,
        val rust: Color,
        val html: Color,
        val css: Color,
        val xml: Color,
        val json: Color,
        val yaml: Color,
        val markdown: Color,
        val sql: Color,
        val shell: Color,
    )

    /** Tool kind accent colors. */
    @Immutable
    data class ToolColors(
        val execute: Color,
        val edit: Color,
        val read: Color,
        val search: Color,
        val delete: Color,
        val move: Color,
        val fetch: Color,
        val think: Color,
        val switchMode: Color,
        val other: Color,
    )

    /** Component-specific colors: sidebar, splash, task, retry, input, attachment,
     *  context panel, indicator, tooltip, code block, todo, compaction, selector,
     *  star, selected row, thinking, mime, review, table, streaming glow, hover, interrupted. */
    @Immutable
    data class ComponentColors(
        // Sidebar shimmer
        val sidebarShimmerCreating: Color,
        val sidebarShimmerStreaming: Color,
        // Splash screen
        val splashConnected: Color,
        val splashError: Color,
        val splashRetry: Color,
        // Task/subagent pills
        val taskAccent: Color,
        val taskRunning: Color,
        val taskCompleted: Color,
        val taskFailed: Color,
        val taskPending: Color,
        // Retry pills
        val retryBg: Color,
        val retryText: Color,
        val retryErrorDetail: Color,
        // Selection prompt
        val selectionCheckboxFill: Color,
        val selectionCheckboxBorder: Color,
        val selectionCustomBullet: Color,
        // Interrupted / divider
        val interruptedDivider: Color,
        // Markdown table
        val tableHeaderText: Color,
        val tableCellText: Color,
        val tableSeparator: Color,
        val tableHeaderBg: Color,
        val tableHoverBg: Color,
        val tableBorder: Color,
        val tableContainerBg: Color,
        // Streaming glow animation
        val glowTransparent: Color,
        val glowStart: Color,
        val glowPeak: Color,
        val glowHot: Color,
        // Input area
        val inputBg: Color,
        val inputBorder: Color,
        val inputCursor: Color,
        val inputText: Color,
        val inputPlaceholder: Color,
        val dragActiveBg: Color,
        // Attachment thumbnails
        val attachmentBg: Color,
        val attachmentRemoveIcon: Color,
        val attachmentImageOverlay: Color,
        val attachmentImageRemove: Color,
        val attachmentFileSize: Color,
        // Context panel
        val contextPanelLabel: Color,
        val contextPanelValue: Color,
        val contextPanelSeparator: Color,
        val contextProgressBarBg: Color,
        // Indicator
        val indicatorBg: Color,
        // Tooltip
        val tooltipBg: Color,
        val tooltipBorder: Color,
        val tooltipText: Color,
        val tooltipMuted: Color,
        // Code block
        val codeCopyIcon: Color,
        val codeLanguageLabel: Color,
        // Todo panel
        val todoBg: Color,
        val todoHeader: Color,
        val todoPending: Color,
        val todoInProgress: Color,
        val todoCompleted: Color,
        val todoCancelled: Color,
        val todoActiveText: Color,
        // Compaction pill
        val compactionText: Color,
        val compactionIcon: Color,
        // Selector hover tint
        val selectorHoverTint: Color,
        // Star ratings
        val starGold: Color,
        val starMuted: Color,
        // Selected row
        val selectedRowBg: Color,
        // Thinking indicator
        val thinkingText: Color,
        val thinkingChevron: Color,
        // MIME text
        val mimeText: Color,
        // Review panel "Added" label
        val reviewAddedLabel: Color,
        // Hover background
        val hoverBg: Color,
        // Compaction pill background (dark green)
        val compactionBg: Color,
        // Slash command palette row hover
        val paletteHoverBg: Color,
        // Capability icon accent (vision, etc.)
        val capabilityVision: Color,
        // Splash screen background
        val splashBg: Color,
    )

    // ── Other domains (unchanged) ────────────────────────────────────────────

    @Immutable
    data class Dims(
        val sidebarWidth: Dp,
        val sidebarContextWidth: Dp,
        val sidebarReviewWidth: Dp,
        val contextIndicatorSize: Dp,
        val contextRingStroke: Dp,
        val contextRingGap: Dp,
        val contextIndicatorTextWidth: Dp,
        val contextTooltipWidth: Dp,
        val contextTooltipPadding: Dp,
        val contextErrorBadgeBorder: Dp,
        val inputOuterPaddingH: Dp,
        val inputOuterPaddingV: Dp,
        val inputCornerRadius: Dp,
        val inputMinHeight: Dp,
        val inputMaxHeight: Dp,
        val inputLineHeight: Dp,
        val inputContentPaddingH: Dp,
        val inputContentPaddingV: Dp,
        val inputDragBorderWidth: Dp,
        val inputDefaultBorderWidth: Dp,
        val actionButtonSize: Dp,
        val actionButtonCornerRadius: Dp,
        val actionIconSize: Dp,
        val stopIconSize: Dp,
        val attachmentThumbnailSize: Dp,
        val attachmentThumbnailCornerRadius: Dp,
        val attachmentChipPaddingH: Dp,
        val attachmentChipPaddingV: Dp,
        val attachmentFileIconSize: Dp,
        val attachmentImageRemoveSize: Dp,
        val attachmentImageRemoveBadge: Dp,
        val attachmentFileRemoveBadge: Dp,
        val modelPickerButtonSize: Dp,
        val modelPickerPanelWidthMin: Dp,
        val modelPickerPanelWidthMax: Dp,
        val modelPickerPanelMaxHeight: Dp,
        val modelPickerPanelCornerRadius: Dp,
        val modelPickerRowHeight: Dp,
        val selectorSize: Dp,
        val selectorCornerRadius: Dp,
        val messageBubbleCornerRadius: Dp,
        val messagePaddingH: Dp,
        val messagePaddingV: Dp,
        val userAvatarSize: Dp,
        val codeBlockCornerRadius: Dp,
        val codeHeaderPaddingH: Dp,
        val codeHeaderPaddingV: Dp,
        val codeCopyIconSize: Dp,
        val codeLanguageIconSize: Dp,
        val toolPillCornerRadius: Dp,
        val toolAccentStripWidth: Dp,
        val toolAccentStripHeight: Dp,
        val toolAccentStripCornerRadius: Dp,
        val sessionRowCornerRadius: Dp,
        val sessionRowPaddingH: Dp,
        val sessionRowPaddingV: Dp,
        val sessionChevronSize: Dp,
        val sessionIconSize: Dp,
        val sessionIndentPerLevel: Dp,
        val contextProgressBarHeight: Dp,
        val contextProgressBarCornerRadius: Dp,
        val todoCornerRadius: Dp,
        val todoPaddingH: Dp,
        val todoPaddingV: Dp,
        val todoStatusIconSize: Dp,
        val todoChevronSize: Dp,
        val paletteCornerRadius: Dp,
        val paletteMaxWidth: Dp,
        val paletteRowCornerRadius: Dp,
        val permissionCornerRadius: Dp,
        val permissionBorderWidth: Dp,
        val permissionPaddingH: Dp,
        val permissionPaddingV: Dp,
        val selectionCornerRadius: Dp,
        val selectionBorderWidth: Dp,
        val selectionCheckboxSize: Dp,
        val selectionCheckboxCornerRadius: Dp,
        val selectionRowCornerRadius: Dp,
        val selectionMaxHeight: Dp,
        val bannerBorderWidth: Dp,
        val bannerCornerRadius: Dp,
        val bannerPaddingH: Dp,
        val bannerPaddingV: Dp,
        val bannerIconSize: Dp,
        val splashLogoSize: Dp,
        val splashIndicatorSize: Dp,
        val splashSettingsDotSize: Dp,
        val splashButtonCornerRadius: Dp,
        val thinkingSpinnerSize: Dp,
        val thinkingStreamingSpinnerSize: Dp,
        val reviewFileIconSize: Dp,
        val reviewOpenFileIconSize: Dp,
        val overlayCornerRadius: Dp,
    )

    @Immutable
    data class Fonts(
        val inputText: TextUnit,
        val inputPlaceholder: TextUnit,
        val attachmentFileName: TextUnit,
        val attachmentFileSize: TextUnit,
        val messageBody: TextUnit,
        val messageOrderedListItem: TextUnit,
        val messagePatchFileCount: TextUnit,
        val messagePatchFileName: TextUnit,
        val messageFileName: TextUnit,
        val messageFileMime: TextUnit,
        val messageInterrupted: TextUnit,
        val messageAgentBadge: TextUnit,
        val messageStepFinish: TextUnit,
        val messageError: TextUnit,
        val codeBody: TextUnit,
        val codeLanguageLabel: TextUnit,
        val codeCopiedLabel: TextUnit,
        val toolKindLabel: TextUnit,
        val toolFileName: TextUnit,
        val toolLineDelta: TextUnit,
        val toolTaskDescription: TextUnit,
        val toolTaskStatus: TextUnit,
        val thinkingLabel: TextUnit,
        val thinkingChevron: TextUnit,
        val contextPercent: TextUnit,
        val contextErrorBadge: TextUnit,
        val contextTooltipLabel: TextUnit,
        val contextTooltipValue: TextUnit,
        val contextTooltipSub: TextUnit,
        val contextPanelTitle: TextUnit,
        val contextSectionHeader: TextUnit,
        val contextDetailLabel: TextUnit,
        val contextDetailValue: TextUnit,
        val contextProgressBarPercent: TextUnit,
        val sessionTitle: TextUnit,
        val sessionFooter: TextUnit,
        val sessionDialogTitle: TextUnit,
        val sessionDialogBody: TextUnit,
        val paletteCommand: TextUnit,
        val paletteDescription: TextUnit,
        val paletteEmpty: TextUnit,
        val pickerModelName: TextUnit,
        val pickerContextWindow: TextUnit,
        val pickerProviderLetter: TextUnit,
        val pickerSectionHeader: TextUnit,
        val pickerSearchPlaceholder: TextUnit,
        val pickerFooter: TextUnit,
        val todoHeader: TextUnit,
        val todoCount: TextUnit,
        val todoContent: TextUnit,
        val todoMoreHint: TextUnit,
        val selectorChip: TextUnit,
        val selectorItem: TextUnit,
        val bannerText: TextUnit,
        val splashTitle: TextUnit,
        val splashStatus: TextUnit,
        val splashSettingsLabel: TextUnit,
        val splashError: TextUnit,
        val tableCell: TextUnit,
        val attachMenuFileName: TextUnit,
        val attachMenuFilePath: TextUnit,
        val attachMenuSectionLabel: TextUnit,
        val attachMenuSearchPlaceholder: TextUnit,
        val reviewFileName: TextUnit,
        val reviewStatusLabel: TextUnit,
        val reviewFilePath: TextUnit,
        val reviewLineDelta: TextUnit,
        val reviewLoading: TextUnit,
        val reviewEmpty: TextUnit,
        val reviewError: TextUnit,
        val permissionDescription: TextUnit,
        val selectionQuestion: TextUnit,
        val selectionSubtitle: TextUnit,
        val selectionCustomBullet: TextUnit,
        val selectionCheckmark: TextUnit,
        val selectionOptionTitle: TextUnit,
        val selectionOptionDescription: TextUnit,
    )

    @Immutable
    data class FontWeights(
        val sectionHeader: FontWeight,
        val badge: FontWeight,
        val semibold: FontWeight,
        val detailLabel: FontWeight,
        val detailValue: FontWeight,
        val contextPercent: FontWeight,
        val todoHeader: FontWeight,
        val selectionQuestion: FontWeight,
        val selectionCheckmark: FontWeight,
        val selectionOptionTitle: FontWeight,
        val commandName: FontWeight,
        val toolKindLabel: FontWeight,
        val toolLineDelta: FontWeight,
        val bannerText: FontWeight,
        val splashTitle: FontWeight,
        val splashError: FontWeight,
        val tableHeader: FontWeight,
        val sessionTitleSelected: FontWeight,
        val sessionTitleUnselected: FontWeight,
    )

    @Immutable
    data class Shapes(
        val inputCornerRadius: RoundedCornerShape,
        val attachmentCornerRadius: RoundedCornerShape,
        val actionButtonCornerRadius: RoundedCornerShape,
        val messageBubbleCornerRadius: RoundedCornerShape,
        val codeBlockCornerRadius: RoundedCornerShape,
        val toolPillCornerRadius: RoundedCornerShape,
        val toolAccentCornerRadius: RoundedCornerShape,
        val sessionRowCornerRadius: RoundedCornerShape,
        val contextIndicatorShape: Shape,
        val contextErrorBadgeShape: Shape,
        val contextTooltipCornerRadius: RoundedCornerShape,
        val contextProgressBarCornerRadius: RoundedCornerShape,
        val todoCornerRadius: RoundedCornerShape,
        val paletteCornerRadius: RoundedCornerShape,
        val paletteRowCornerRadius: RoundedCornerShape,
        val permissionCornerRadius: RoundedCornerShape,
        val selectionCornerRadius: RoundedCornerShape,
        val selectionCheckboxCornerRadius: RoundedCornerShape,
        val selectionRowCornerRadius: RoundedCornerShape,
        val bannerCornerRadius: RoundedCornerShape,
        val splashButtonCornerRadius: RoundedCornerShape,
        val splashSettingsCornerRadius: RoundedCornerShape,
        val chipCornerRadius: RoundedCornerShape,
        val pickerCornerRadius: RoundedCornerShape,
        val pickerRowCornerRadius: RoundedCornerShape,
        val modelPickerButtonShape: Shape,
        val fileChangeRowCornerRadius: RoundedCornerShape,
        val errorBadgeCornerRadius: RoundedCornerShape,
        val avatarShape: Shape,
        val imageRemoveBadgeShape: Shape,
        val fileRemoveBadgeShape: Shape,
        val searchIconShape: Shape,
        val splashSettingsIndicatorShape: RoundedCornerShape,
        val sidebarLeftCornerRadius: RoundedCornerShape,
        val overlayCornerRadius: RoundedCornerShape,
        val dialogCornerRadius: RoundedCornerShape,
        val retryPillCornerRadius: RoundedCornerShape,
        val attachMenuCornerRadius: RoundedCornerShape,
        val attachFileRowCornerRadius: RoundedCornerShape,
        val tableCornerRadius: RoundedCornerShape,
    )

    @Immutable
    data class Animations(
        val glowPulseMs: Int,
        val contextPulseMs: Int,
        val shimmerSweepMs: Int,
        val chipHoverMs: Int,
        val thinkingIndicatorDelayMs: Long,
    )
}
