package com.opencode.acp.chat.ui.compose

import com.intellij.icons.AllIcons
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon

/**
 * Central registry of IntelliJ platform icons wrapped as Jewel [IconKey]s.
 *
 * ## Why this exists
 *
 * Jewel's [org.jetbrains.jewel.ui.icons.AllIconsKeys] constants are generated
 * with `iconClass` set to the **Jewel JAR** class (e.g. `AllIconsKeys.FileTypes.class`).
 * The actual SVG icon resources, however, live in the **IntelliJ platform JARs**
 * (e.g. `AllIcons.FileTypes.class`). When Jewel's icon resolver looks up the SVG
 * relative to `iconClass`, it searches the Jewel JAR — the SVGs aren't there,
 * so the resolver renders a **magenta/pink placeholder**.
 *
 * [IntelliJIconKey.fromPlatformIcon] extracts the SVG path from the platform icon
 * and sets `iconClass` to the **platform** class where the SVGs actually live.
 * This ensures Jewel's resolver can find the icon resource.
 *
 * ## Usage
 *
 * Replace `AllIconsKeys.FileTypes.Java` with `PlatformIconKeys.FileTypes.Java`.
 * The API mirrors `AllIconsKeys` one-to-one for easy migration.
 */
object PlatformIconKeys {

    object FileTypes {
        val Java: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Java)
        val JavaScript: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.JavaScript)
        val Css: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Css)
        val Html: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Html)
        val Xml: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Xml)
        val Json: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Json)
        val Yaml: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Yaml)
        val Text: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Text)
        val Image: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Image)
    }

    object Language {
        val Kotlin: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Language.Kotlin)
        val Python: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Language.Python)
        val Ruby: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Language.Ruby)
        val Rust: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Language.Rust)
        val GO: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Language.GO)
        val Scala: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Language.Scala)
        val Php: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Language.Php)
    }

    object Nodes {
        val Folder: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Nodes.Folder)
        val Console: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Nodes.Console)
    }

    object Actions {
        val Diff: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Diff)
        val Refresh: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Refresh)
    }

    object General {
        val BalloonError: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.General.BalloonError)
        val BalloonInformation: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.General.BalloonInformation)
        val Warning: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.General.Warning)
        val ChevronDown: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.General.ChevronDown)
        val ChevronRight: IconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.General.ChevronRight)
    }
}