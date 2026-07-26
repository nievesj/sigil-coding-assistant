package com.opencode.acp.chat.service

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.SkillInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages skill fetching from the OpenCode server, with staleness-based
 * re-fetch. Mirrors the CommandManager pattern: the manager owns the fetch
 * logic + error handling + staleness tracking; the ViewModel delegates to it.
 *
 * Skills are fetched from GET /skill on init, session switch, and on $
 * palette trigger if stale.
 */
class SkillManager(
    private val clientProvider: () -> OpenCodeClient?,
) {

    private val logger = KotlinLogging.logger {}

    private val _availableSkills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val availableSkills: StateFlow<List<SkillInfo>> = _availableSkills.asStateFlow()

    @Volatile
    private var lastSkillFetchTimeMs: Long = 0L

    /**
     * Fetch skills from GET /skill.
     *
     * @param force If true, always fetch. If false, only fetch if stale
     *              (now - lastSkillFetchTimeMs > SKILL_STALENESS_MS).
     */
    suspend fun fetchAvailableSkills(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSkillFetchTimeMs < SKILL_STALENESS_MS) {
            return  // cached, not stale
        }
        val client = clientProvider() ?: return
        try {
            val skills = client.listSkills()
            _availableSkills.value = skills
            lastSkillFetchTimeMs = now
            logger.debug { "[ACP] Fetched ${skills.size} skills from server" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Do NOT clear _availableSkills on fetch error.
            // Fall back to the cached list rather than showing empty.
            logger.warn(e) { "[ACP] Failed to fetch skills — using cached list" }
        }
    }

    companion object {
        /** Staleness window for skill re-fetch (30 seconds). */
        const val SKILL_STALENESS_MS = 30_000L
    }
}