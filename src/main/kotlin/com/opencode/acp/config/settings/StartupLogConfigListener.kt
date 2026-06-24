package com.opencode.acp.config.settings

import com.intellij.ide.AppLifecycleListener

/**
 * Applies the persisted [OpenCodeSettingsState.logLevel] to Logback when the IDE
 * frame is created, so the level survives restarts. [OpenCodeService] is a
 * PROJECT-level service and initializes too late for pre-project logs.
 */
class StartupLogConfigListener : AppLifecycleListener {
    override fun appFrameCreated(components: List<String>) {
        DebugLogConfig.apply(OpenCodeSettingsState.getInstance().logLevel)
    }
}
