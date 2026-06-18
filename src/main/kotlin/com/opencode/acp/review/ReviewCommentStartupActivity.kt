package com.opencode.acp.review

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * ProjectActivity registered via <postStartupActivity> in plugin.xml.
 * Forces construction of the lazy ReviewCommentManager service and
 * triggers loadAll() so the .review/ index is populated before the
 * user opens any file. Runs on a background coroutine.
 */
class ReviewCommentStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        // Construction triggers init { } which registers listeners.
        // loadAll() is the actual disk scan.
        val manager = ReviewCommentManager.getInstance(project)
        manager.loadAll()
    }
}
