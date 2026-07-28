package com.opencode.acp.intelligence

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * Pre-warms the `psi_repo_map` cache on project open.
 *
 * Fire-and-forget: launches [PsiQueryHelper.warmRepoMap] on a background
 * dispatcher ([Dispatchers.Default]) after project open. Does NOT block
 * project open. If the project closes before pre-warm completes, the
 * coroutine is cancelled via project disposal (the read action + ensureActive
 * checks propagate cancellation).
 *
 * Registered as `<postStartupActivity>` in `plugin-mcp.xml`.
 */
class RepoMapPreWarmActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.Default) {
            try {
                val helper = PsiQueryHelper(project)
                helper.warmRepoMap()
                logger.info { "[ACP] RepoMap pre-warm completed for project ${project.name}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] RepoMap pre-warm failed for project ${project.name}" }
            }
        }
    }
}