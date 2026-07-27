package com.opencode.acp.intelligence

import com.opencode.acp.intelligence.model.AffectedSymbol
import com.opencode.acp.intelligence.model.CallHierarchyNode
import com.opencode.acp.intelligence.model.ClassStructure
import com.opencode.acp.intelligence.model.FileStructure
import com.opencode.acp.intelligence.model.ImpactResult
import com.opencode.acp.intelligence.model.MemberInfo
import com.opencode.acp.intelligence.model.ReferenceInfo
import com.opencode.acp.intelligence.model.RepoMapEntry
import com.opencode.acp.intelligence.model.SymbolInfo

/**
 * Formats PSI query results as structured JSON strings for MCP tool responses.
 *
 * All tool results are JSON strings (the MCP Server plugin transports them to
 * the agent). Errors use [formatError] with a `retry` boolean field so the
 * agent can decide whether to retry.
 *
 * JSON is built via manual [StringBuilder] string construction — no
 * kotlinx.serialization dependency — so the object is pure-logic and
 * unit-testable without serialization setup.
 *
 * All string values are escaped via [jsonEscape]. Numeric values are inserted
 * directly (no quotes). Nullable fields (signature, qualifiedName) are omitted
 * when null rather than serialized as `null`. Enum values use `kind.name`.
 *
 * Every format method applies [MAX_TOOL_OUTPUT_CHARS] truncation: if the
 * serialized result exceeds the limit, it is truncated and `,"_truncated":true`
 * is appended before the closing brace/bracket.
 */
object SymbolFormatter {

    /**
     * Format an error message as JSON: `{"error": "<message>", "retry": <bool>}`.
     *
     * - `retry = true`: transient failures the agent should retry
     *   (e.g., "Indexing in progress", "timeout", "cache warming").
     * - `retry = false`: permanent failures the agent should not retry
     *   (e.g., "symbol not found", "ambiguous name", "disabled").
     */
    fun formatError(message: String, retry: Boolean = false): String {
        val escaped = jsonEscape(message)
        return enforceTokenBudget("""{"error":"$escaped","retry":$retry}""")
    }

    /**
     * Escape a string for safe embedding in a JSON string literal.
     * Handles backslash, double-quote, and control characters.
     */
    fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u").append("%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Format a list of symbols as a JSON array.
     *
     * Each entry: `{"name":"...","kind":"...","file":"...","line":N,"signature":"..."|"qualifiedName":"..."}`.
     * Nullable `signature`/`qualifiedName` are omitted when null.
     */
    fun formatSymbols(symbols: List<SymbolInfo>): String {
        val sb = StringBuilder()
        sb.append('[')
        symbols.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"name\":\"").append(jsonEscape(s.name)).append('"')
            sb.append(",\"kind\":\"").append(s.kind.name).append('"')
            sb.append(",\"file\":\"").append(jsonEscape(s.file)).append('"')
            sb.append(",\"line\":").append(s.line)
            s.signature?.let {
                sb.append(",\"signature\":\"").append(jsonEscape(it)).append('"')
            }
            s.qualifiedName?.let {
                sb.append(",\"qualifiedName\":\"").append(jsonEscape(it)).append('"')
            }
            sb.append('}')
        }
        sb.append(']')
        return enforceTokenBudget(sb.toString())
    }

    /**
     * Format references as JSON. When [truncated] is true, a `_meta` footer is
     * appended inside the array:
     *
     * `{"references":[...],"truncated":true,"total":N,"showing":M}`
     *
     * When not truncated, the output is a plain JSON array of reference objects.
     */
    fun formatReferences(refs: List<ReferenceInfo>, truncated: Boolean, total: Int): String {
        val sb = StringBuilder()
        if (!truncated) {
            sb.append('[')
            refs.forEachIndexed { i, r ->
                if (i > 0) sb.append(',')
                sb.appendReference(r)
            }
            sb.append(']')
        } else {
            sb.append("{\"references\":[")
            refs.forEachIndexed { i, r ->
                if (i > 0) sb.append(',')
                sb.appendReference(r)
            }
            sb.append("],\"truncated\":true,\"total\":")
            sb.append(total)
            sb.append(",\"showing\":")
            sb.append(refs.size)
            sb.append('}')
        }
        return enforceTokenBudget(sb.toString())
    }

    private fun StringBuilder.appendReference(r: ReferenceInfo) {
        append('{')
        append("\"file\":\"").append(jsonEscape(r.file)).append('"')
        append(",\"line\":").append(r.line)
        append(",\"column\":").append(r.column)
        r.enclosingSymbol?.let {
            append(",\"enclosingSymbol\":\"").append(jsonEscape(it)).append('"')
        }
        append(",\"text\":\"").append(jsonEscape(r.text)).append('"')
        append('}')
    }

    /**
     * Format a call hierarchy tree as nested JSON:
     * `{"name":"...","kind":"...","file":"...","line":N,"children":[...]}`.
     */
    fun formatCallHierarchy(root: CallHierarchyNode): String {
        val sb = StringBuilder()
        sb.appendCallHierarchyNode(root)
        return enforceTokenBudget(sb.toString())
    }

    private fun StringBuilder.appendCallHierarchyNode(node: CallHierarchyNode) {
        append('{')
        append("\"name\":\"").append(jsonEscape(node.name)).append('"')
        append(",\"kind\":\"").append(node.kind.name).append('"')
        append(",\"file\":\"").append(jsonEscape(node.file)).append('"')
        append(",\"line\":").append(node.line)
        append(",\"children\":[")
        node.children.forEachIndexed { i, c ->
            if (i > 0) append(',')
            appendCallHierarchyNode(c)
        }
        append("]}")
    }

    /**
     * Format an impact analysis result:
     * `{"symbol":"...","affectedFiles":[...],"affectedSymbols":[...],"riskLevel":"...","summary":"...","totalAffected":N}`.
     */
    fun formatImpact(result: ImpactResult): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"symbol\":\"").append(jsonEscape(result.symbol)).append('"')
        sb.append(",\"affectedFiles\":[")
        result.affectedFiles.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append('"').append(jsonEscape(f)).append('"')
        }
        sb.append("],\"affectedSymbols\":[")
        result.affectedSymbols.forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            sb.appendAffectedSymbol(a)
        }
        sb.append("],\"riskLevel\":\"").append(result.riskLevel.name).append('"')
        sb.append(",\"summary\":\"").append(jsonEscape(result.summary)).append('"')
        sb.append(",\"totalAffected\":").append(result.totalAffected)
        sb.append('}')
        return enforceTokenBudget(sb.toString())
    }

    private fun StringBuilder.appendAffectedSymbol(a: AffectedSymbol) {
        append('{')
        append("\"name\":\"").append(jsonEscape(a.name)).append('"')
        append(",\"kind\":\"").append(a.kind.name).append('"')
        append(",\"file\":\"").append(jsonEscape(a.file)).append('"')
        append(",\"line\":").append(a.line)
        append(",\"depth\":").append(a.depth)
        append(",\"relationship\":\"").append(jsonEscape(a.relationship)).append('"')
        append('}')
    }

    /**
     * Format a file structure:
     * `{"file":"...","language":"...","classes":[{"name":"...","kind":"...","fields":[...],"methods":[...],"nestedClasses":[...]}]}`.
     */
    fun formatFileStructure(structure: FileStructure): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"file\":\"").append(jsonEscape(structure.file)).append('"')
        sb.append(",\"language\":\"").append(jsonEscape(structure.language)).append('"')
        sb.append(",\"classes\":[")
        structure.classes.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.appendClassStructure(c)
        }
        sb.append("]}")
        return enforceTokenBudget(sb.toString())
    }

    private fun StringBuilder.appendClassStructure(c: ClassStructure) {
        append('{')
        append("\"name\":\"").append(jsonEscape(c.name)).append('"')
        append(",\"kind\":\"").append(c.kind.name).append('"')
        append(",\"fields\":[")
        c.fields.forEachIndexed { i, m ->
            if (i > 0) append(',')
            appendMember(m)
        }
        append("],\"methods\":[")
        c.methods.forEachIndexed { i, m ->
            if (i > 0) append(',')
            appendMember(m)
        }
        append("],\"nestedClasses\":[")
        c.nestedClasses.forEachIndexed { i, n ->
            if (i > 0) append(',')
            appendClassStructure(n)
        }
        append("]}")
    }

    private fun StringBuilder.appendMember(m: MemberInfo) {
        append('{')
        append("\"name\":\"").append(jsonEscape(m.name)).append('"')
        append(",\"signature\":\"").append(jsonEscape(m.signature)).append('"')
        append(",\"modifiers\":[")
        m.modifiers.forEachIndexed { i, mod ->
            if (i > 0) append(',')
            append('"').append(jsonEscape(mod)).append('"')
        }
        append("]}")
    }

    /**
     * Format a repo map as a JSON array of entries:
     * `{"name":"...","kind":"...","file":"...","line":N,"referenceCount":N,"importance":D}`.
     *
     * `importance` is a Double, inserted directly (no quotes).
     */
    fun formatRepoMap(entries: List<RepoMapEntry>): String {
        val sb = StringBuilder()
        sb.append('[')
        entries.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"name\":\"").append(jsonEscape(e.name)).append('"')
            sb.append(",\"kind\":\"").append(e.kind.name).append('"')
            sb.append(",\"file\":\"").append(jsonEscape(e.file)).append('"')
            sb.append(",\"line\":").append(e.line)
            sb.append(",\"referenceCount\":").append(e.referenceCount)
            sb.append(",\"importance\":").append(e.importance)
            sb.append('}')
        }
        sb.append(']')
        return enforceTokenBudget(sb.toString())
    }

    /**
     * Format ambiguous-symbol candidates:
     * `{"ambiguous":true,"candidates":[...same as formatSymbols entries...]}`.
     */
    fun formatCandidates(candidates: List<SymbolInfo>): String {
        val sb = StringBuilder()
        sb.append("{\"ambiguous\":true,\"candidates\":")
        sb.append(formatSymbolsRaw(candidates))
        sb.append('}')
        return enforceTokenBudget(sb.toString())
    }

    /**
     * Internal: serialize symbols without applying the token-budget truncation
     * (the outer [formatCandidates] call applies it once on the full result).
     */
    private fun formatSymbolsRaw(symbols: List<SymbolInfo>): String {
        val sb = StringBuilder()
        sb.append('[')
        symbols.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"name\":\"").append(jsonEscape(s.name)).append('"')
            sb.append(",\"kind\":\"").append(s.kind.name).append('"')
            sb.append(",\"file\":\"").append(jsonEscape(s.file)).append('"')
            sb.append(",\"line\":").append(s.line)
            s.signature?.let {
                sb.append(",\"signature\":\"").append(jsonEscape(it)).append('"')
            }
            s.qualifiedName?.let {
                sb.append(",\"qualifiedName\":\"").append(jsonEscape(it)).append('"')
            }
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * Apply the [MAX_TOOL_OUTPUT_CHARS] token budget to a serialized JSON string.
     *
     * If the string exceeds the limit, it is truncated to the limit and
     * `,"_truncated":true` is appended before the last closing `]` or `}`.
     * If the last significant character is neither, the marker is appended at
     * the end.
     */
    fun enforceTokenBudget(json: String): String {
        if (json.length <= MAX_TOOL_OUTPUT_CHARS) return json
        val truncated = json.substring(0, MAX_TOOL_OUTPUT_CHARS)

        // Scan the truncated portion to find:
        //  - The last safe position (closing } or ] outside a string)
        //  - The nesting depth at that position
        //  - Whether we're inside a string at the cut point
        var inString = false
        var escape = false
        var safePos = -1
        var depthAtSafePos = 0
        var currentDepth = 0

        for (i in truncated.indices) {
            val c = truncated[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
            }
            if (!inString) {
                when (c) {
                    '{', '[' -> currentDepth++
                    '}', ']' -> {
                        safePos = i
                        depthAtSafePos = currentDepth
                    }
                }
            }
        }

        // If we found a safe position outside strings, truncate there,
        // append the marker, then close all remaining open containers.
        // The safePos points to a closing } or ], so depthAtSafePos is the
        // depth BEFORE that bracket closes. After the bracket, depth is
        // depthAtSafePos - 1. We discard everything after safePos (it may
        // contain incomplete values) and close all containers that are still
        // open at that point.
        return if (safePos > 0) {
            val prefix = truncated.substring(0, safePos)
            // depthAtSafePos - 1 containers remain open after the closing bracket at safePos.
            // We discard the closing bracket and everything after it, then append the marker
            // and close all remaining open containers.
            val closes = StringBuilder()
            // Reconstruct closing brackets matching the open containers.
            // We need to close (depthAtSafePos - 1) containers. Since we don't
            // track the exact bracket types, we use a heuristic: scan the prefix
            // to determine the stack of open bracket types.
            val bracketStack = ArrayDeque<Char>()
            var scanInString = false
            var scanEscape = false
            for (i in prefix.indices) {
                val c = prefix[i]
                if (scanEscape) { scanEscape = false; continue }
                if (c == '\\' && scanInString) { scanEscape = true; continue }
                if (c == '"') { scanInString = !scanInString }
                if (!scanInString) {
                    when (c) {
                        '{', '[' -> bracketStack.addLast(c)
                        '}' -> if (bracketStack.isNotEmpty() && bracketStack.last() == '{') bracketStack.removeLast()
                        ']' -> if (bracketStack.isNotEmpty() && bracketStack.last() == '[') bracketStack.removeLast()
                    }
                }
            }
           // Close remaining open brackets in reverse order.
            val innermostOpen = bracketStack.lastOrNull()
           while (bracketStack.isNotEmpty()) {
               val open = bracketStack.removeLast()
               closes.append(if (open == '{') '}' else ']')
           }
           // Insert the marker before the closing brackets.
           // If there are open containers, add the marker as a key in the innermost one.
           // The marker prefix depends on the last significant character of the prefix
           // to avoid producing invalid JSON at key-value boundaries (e.g. `{"a":,` or
           // `[1,2,,` or `{"a":{,`).
            // Additionally, the marker format depends on whether the innermost open
            // container is an array `[` or an object `{`. Arrays expect bare values,
            // not key-value pairs, so we use `"_truncated"` (a string value) for arrays
            // and `"_truncated":true` (a key-value pair) for objects.
            val lastSig = lastSignificantChar(prefix)
            val marker = when {
                innermostOpen == '[' -> when (lastSig) {
                    ',', '[' -> "\"_truncated\""
                    else -> ",\"_truncated\""
                }
                lastSig == ':' -> "null,\"_truncated\":true"
                lastSig == ',' || lastSig == '{' || lastSig == '[' -> "\"_truncated\":true"
                else -> ",\"_truncated\":true"
            }
            if (closes.isNotEmpty()) {
                prefix + marker + closes.toString()
            } else {
                // No open containers — just append the marker.
                prefix + marker
            }
        } else {
            // No safe cut point found (entire truncated portion is inside a string
            // or has no closing braces). Close any open string, then close all open
            // containers, then append the marker.
            val closed = if (inString) truncated + "\"" else truncated
            // Scan the full truncated content (after closing string) for open brackets.
            val bracketStack = ArrayDeque<Char>()
            var scanInString2 = false
            var scanEscape2 = false
            for (i in closed.indices) {
                val c = closed[i]
                if (scanEscape2) { scanEscape2 = false; continue }
                if (c == '\\' && scanInString2) { scanEscape2 = true; continue }
                if (c == '"') { scanInString2 = !scanInString2 }
                if (!scanInString2) {
                    when (c) {
                        '{', '[' -> bracketStack.addLast(c)
                        '}' -> if (bracketStack.isNotEmpty() && bracketStack.last() == '{') bracketStack.removeLast()
                        ']' -> if (bracketStack.isNotEmpty() && bracketStack.last() == '[') bracketStack.removeLast()
                    }
                }
            }
           val closes = StringBuilder()
            val innermostOpen = bracketStack.lastOrNull()
           while (bracketStack.isNotEmpty()) {
               val open = bracketStack.removeLast()
               closes.append(if (open == '{') '}' else ']')
           }
           // The marker prefix depends on the last significant character of `closed`
           // to avoid producing invalid JSON at key-value boundaries (e.g. `{"a":,`
           // or `[1,2,,` or `{"a":{,`).
           // Additionally, the marker format depends on whether the innermost open
           // container is an array `[` or an object `{`. Arrays expect bare values,
           // not key-value pairs, so we use `"_truncated"` (a string value) for arrays
           // and `"_truncated":true` (a key-value pair) for objects.
           val lastSig = lastSignificantChar(closed)
           val marker = when {
                innermostOpen == '[' -> when (lastSig) {
                    ',', '[' -> "\"_truncated\""
                    else -> ",\"_truncated\""
                }
                lastSig == ':' -> "null,\"_truncated\":true"
                lastSig == ',' || lastSig == '{' || lastSig == '[' -> "\"_truncated\":true"
                else -> ",\"_truncated\":true"
            }
            if (closes.isNotEmpty()) {
                closed + marker + closes.toString()
            } else {
                closed + marker
            }
        }
    }

    /**
     * Returns the last non-whitespace character of [s] that is outside a JSON
     * string literal, or `null` if there is none. Used by [enforceTokenBudget]
     * to decide how to prefix the `"_truncated":true` marker so the result is
     * valid JSON at key-value boundaries (after `:`, `,`, `{`, or `[`).
     */
    private fun lastSignificantChar(s: String): Char? {
        var inString = false
        var escape = false
        var lastSig: Char? = null
        for (c in s) {
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') inString = !inString
            if (!inString && !c.isWhitespace()) lastSig = c
        }
        return lastSig
    }
}