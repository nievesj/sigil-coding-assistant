package com.opencode.acp.review

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * IntelliJ platform icons for review comment gutter markers.
 *
 * Used by [ReviewCommentLineMarkerProvider] for gutter icons (Swing API),
 * not by Compose UI (the Review tab uses Jewel `AllIconsKeys`).
 *
 * Three visually distinct icons per severity:
 * - ERROR   — red balloon with X ([AllIcons.General.BalloonError])
 * - WARNING — yellow warning triangle ([AllIcons.General.Warning])
 * - INFO    — blue info balloon ([AllIcons.General.BalloonInformation])
 */
object ReviewIcons {
    /** Red circle with X — for ERROR severity. */
    val ERROR_MARKER: Icon = AllIcons.General.BalloonError

    /** Yellow/amber warning triangle — for WARNING severity. */
    val WARNING_MARKER: Icon = AllIcons.General.Warning

    /** Blue info circle — for INFO severity. */
    val INFO_MARKER: Icon = AllIcons.General.BalloonInformation
}