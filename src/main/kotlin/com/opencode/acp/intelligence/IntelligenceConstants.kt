package com.opencode.acp.intelligence

// Limits
const val MAX_SYMBOL_SEARCH_RESULTS = 200
const val DEFAULT_SYMBOL_SEARCH_LIMIT = 50
const val MAX_REFERENCE_RESULTS = 500
const val DEFAULT_REFERENCE_LIMIT = 200
const val MAX_CALL_HIERARCHY_DEPTH = 4
const val DEFAULT_CALL_HIERARCHY_DEPTH = 2
const val MAX_CALL_HIERARCHY_NODES_PER_LEVEL = 50
const val DEFAULT_CALL_HIERARCHY_NODES_PER_LEVEL = 20
const val MAX_IMPACT_DEPTH = 3
const val DEFAULT_IMPACT_DEPTH = 1
const val MAX_IMPACT_RESULTS = 300
const val DEFAULT_IMPACT_LIMIT = 100
const val MAX_IMPACT_QUEUE_SIZE = 500
const val MAX_REPO_MAP_RESULTS = 500
const val DEFAULT_REPO_MAP_LIMIT = 100

// Repo map caching
const val REPO_MAP_CACHE_TTL_MS = 300_000L
const val REPO_MAP_COMPUTATION_TIMEOUT_MS = 15_000L
const val REPO_MAP_SAMPLE_SIZE = 500

// Token budget
const val MAX_TOOL_OUTPUT_CHARS = 80_000

// Per-tool timeouts
const val TOOL_TIMEOUT_FIND_REFERENCES_MS = 30_000L
const val TOOL_TIMEOUT_IMPACT_ANALYSIS_MS = 60_000L
const val TOOL_TIMEOUT_REPO_MAP_MS = 120_000L
const val TOOL_TIMEOUT_DEFAULT_MS = 10_000L

// Risk thresholds
const val RISK_LOW_THRESHOLD = 5
const val RISK_MEDIUM_THRESHOLD = 20
const val RISK_HIGH_THRESHOLD = 50

// Context file
const val CONTEXT_FILE_PATH = ".opencode/context/repo-structure.md"
const val CONTEXT_GLOB_PATTERN = ".opencode/context/**/*.md"