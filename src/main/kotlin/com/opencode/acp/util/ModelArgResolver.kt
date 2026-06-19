package com.opencode.acp.util

import com.opencode.acp.chat.model.ProviderModel

/**
 * Resolves user-typed model name fragments (e.g. "glm5.2", "claude sonnet",
 * "anthropic/claude-sonnet-4-20250514") to concrete [ProviderModel] entries
 * from the server-fetched model list.
 *
 * Used by `/review-perform <model...>` to let the user specify which model(s)
 * should perform a review without typing the exact server model ID.
 *
 * ## Matching strategy (first match wins, per arg)
 *
 * 1. **Exact `providerID/modelID`** — `"anthropic/claude-sonnet-4-20250514"`
 *    matches directly. Also handles the slash-less exact `modelID`.
 * 2. **Exact `modelID`** — `"glm-5.2:cloud"` matches.
 * 3. **Normalized `modelID` contains normalized input** — `"glm52"` is found
 *    inside `"glm52cloud"` (after stripping non-alphanumerics).
 * 4. **Normalized `displayName` contains normalized input** — `"GLM 5.2 Cloud"`
 *    normalized contains `"glm52"`.
 * 5. **Word-token match** — each whitespace-separated token of the input must
 *    match (contains, normalized) somewhere in `modelID` or `displayName`.
 *    This lets `"claude sonnet"` match `claude-sonnet-4-20250514`.
 *
 * Normalization: lowercase, strip all non-alphanumeric characters. So
 * `"glm 5.2"`, `"glm5.2"`, `"GLM-5.2"` all become `"glm52"`.
 *
 * ## Ambiguity
 *
 * If 2+ models match at the same priority tier, the resolver returns the
 * first match (deterministic by list order). The caller can surface a
 * "did you mean?" prompt if needed, but for review-perform the common case
 * is unambiguous enough that first-match is acceptable.
 */
object ModelArgResolver {

    /**
     * Resolve a single user-typed arg to a [ProviderModel], or null if no match.
     *
     * @param arg the user-typed fragment (e.g. "glm5.2", "claude-sonnet")
     * @param models the full list of available models (typically
     *   `controlState.allModels`)
     */
    fun resolve(arg: String, models: List<ProviderModel>): ProviderModel? {
        val trimmed = arg.trim()
        if (trimmed.isEmpty()) return null

        // 1. Exact "providerID/modelID" or exact "modelID"
        if (trimmed.contains('/')) {
            val slashIdx = trimmed.indexOf('/')
            val pID = trimmed.substring(0, slashIdx).trim()
            val mID = trimmed.substring(slashIdx + 1).trim()
            models.firstOrNull { it.providerID.equals(pID, ignoreCase = true) && it.modelID.equals(mID, ignoreCase = true) }
                ?.let { return it }
        }
        models.firstOrNull { it.modelID.equals(trimmed, ignoreCase = true) }?.let { return it }

        // 2-4. Normalized contains-match on modelID or displayName
        val normInput = normalize(trimmed)
        if (normInput.isEmpty()) return null

        // modelID normalized contains
        models.firstOrNull { normalize(it.modelID).contains(normInput) }?.let { return it }
        // displayName normalized contains
        models.firstOrNull { normalize(it.displayName).contains(normInput) }?.let { return it }

        // 5. Multi-token: every token must match somewhere in modelID or displayName
        val tokens = normInput.split(" ").filter { it.isNotEmpty() }
        if (tokens.size > 1) {
            models.firstOrNull { m ->
                val normId = normalize(m.modelID)
                val normName = normalize(m.displayName)
                tokens.all { token -> normId.contains(token) || normName.contains(token) }
            }?.let { return it }
        }

        return null
    }

    /**
     * Resolve a whitespace-separated arg string to a list of [ProviderModel]s.
     *
     * - Empty/blank arg string → empty list (caller uses default model).
     * - `*` → all available models (wildcard — useful with a settings-curated
     *   list in the future; for now returns all models).
     * - Each token is resolved independently via [resolve].
     *
     * @return a [Resolution] containing matched models and any unresolved args
     *   (so the caller can surface an error for typos).
     */
    fun resolveAll(args: String, models: List<ProviderModel>): Resolution {
        val trimmed = args.trim()
        if (trimmed.isEmpty()) return Resolution(emptyList(), emptyList())

        if (trimmed == "*") return Resolution(models, emptyList())

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matched = mutableListOf<ProviderModel>()
        val unresolved = mutableListOf<String>()
        for (token in tokens) {
            val m = resolve(token, models)
            if (m != null) matched.add(m) else unresolved.add(token)
        }
        // Deduplicate matched models (preserve order) in case the user types
        // two fragments that resolve to the same model.
        return Resolution(matched.distinctBy { it.providerID + "/" + it.modelID }, unresolved)
    }

    /** Result of [resolveAll]: matched models + unresolved arg tokens. */
    data class Resolution(
        val models: List<ProviderModel>,
        val unresolved: List<String>,
    ) {
        /** True if every arg resolved to a model. */
        val allResolved: Boolean get() = unresolved.isEmpty()
    }

    /** Lowercase + strip non-alphanumeric, keeping internal structure comparable. */
    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
}