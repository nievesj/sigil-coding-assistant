package com.opencode.acp.chat.ui.compose

import com.opencode.acp.adapter.SkillInfo

/**
 * Escape a skill name for safe insertion into an XML-style tag attribute.
 * Skill names come from the OpenCode server's GET /skill endpoint, which
 * aggregates skills from external registries (untrusted data). Without
 * escaping, a skill name containing `"` or `</skill_content>` could inject
 * arbitrary content into the user's LLM message (prompt injection).
 */
internal fun escapeSkillName(name: String): String =
    name.replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * Builds the injected text for a selected skill, wrapping its content in
 * `<skill_content>` tags for the LLM. Shared by the SkillPalette click handler
 * and the ExecuteSkillCommand keyboard handler in InputArea.
 *
 * Security: the skill name is escaped via [escapeSkillName] (XML attribute escaping).
 * The skill content is sanitized by escaping `</skill_content>` sequences to prevent
 * a malicious skill from breaking out of the tag and injecting arbitrary LLM
 * instructions (prompt injection). The content comes from the OpenCode server's
 * GET /skill endpoint, which aggregates skills from external registries (untrusted).
 *
 * @param skill the selected skill
 * @param fullText the current input text (e.g. "$git some args")
 * @return the full text to replace the input with, for user review before sending
 */
fun buildSkillInjection(
    skill: SkillInfo,
    fullText: String,
): String {
    // Compute remaining text AFTER the matched skill name, not the full query.
    // The skill name may be shorter than the query (e.g. query="git-release" matched skill="git"),
    // so remaining text is whatever follows the skill name within the query.
    val remainingText = if (fullText.length > skill.name.length + 1) {
        fullText.substring(skill.name.length + 1).trim()
    } else ""

    // Sanitize content: escape </skill_content> (case-insensitive, whitespace-tolerant)
    // to prevent prompt injection breakout from untrusted skill content.
    val sanitizedContent = skill.content
        .replace(Regex("</skill_content\\s*>", RegexOption.IGNORE_CASE), "&lt;/skill_content&gt;")
        .trim()

    return buildString {
        appendLine("<skill_content name=\"${escapeSkillName(skill.name)}\">")
        appendLine(sanitizedContent)
        appendLine("</skill_content>")
        if (remainingText.isNotEmpty()) {
            appendLine()
            appendLine(remainingText)
        }
    }
}