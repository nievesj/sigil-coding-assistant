# PSI Code Intelligence Skill

This skill teaches you how to use IntelliJ's PSI code intelligence MCP tools for semantic code understanding. These tools query live PSI (Program Structure Interface) data — always fresh, type-resolved, reflecting current editor state including unsaved changes.

## Available Tools

### `psi_find_symbol` — Symbol Search
Find symbols by name pattern across the project. Returns name, kind, file, line, and signature.

**Use when:**
- You need to locate a class, method, or field by name
- You want to know where a symbol is declared (faster than grep)
- You need the signature of a method/class

**Parameters:**
- `pattern` (required): Symbol name (camelCase, substring, or exact)
- `kind` (optional): Filter by "class", "method", "field", "function", "property", "interface", "enum", "object", "annotation"
- `scope` (optional): "project" (default) or "module:<name>"
- `limit` (optional): Max results (default 50, max 200)

**Prefer over grep when:** You need the declaration site, not all mentions. Grep finds text matches; `psi_find_symbol` finds semantic declarations.

### `psi_find_references` — Find Usages
Find all references to a symbol (function, class, field). Returns file, line, enclosing symbol, and reference text.

**Use when:**
- You need to know who calls a method
- You need to know where a class is instantiated/used
- You need to assess the impact of changing a symbol's signature

**Parameters:**
- `symbol` (required): Symbol name
- `file` (optional): File path to disambiguate when multiple symbols share a name
- `scope` (optional): "project" (default) or "module:<name>"
- `limit` (optional): Max results (default 200, max 500)

**Prefer over grep when:** You need semantic references (actual code that uses the symbol), not text matches. Grep finds the string "foo" everywhere; `psi_find_references` finds code that actually calls/instantiates/uses the symbol `foo`.

### `psi_call_hierarchy` — Call Hierarchy
Get caller or callee tree for a method. Returns a tree of name, file, line, and children.

**Use when:**
- You need to trace the call chain: "who calls the method that calls this method?"
- You need to understand the call graph around a method
- You're planning a refactor and need to know the blast radius

**Parameters:**
- `symbol` (required): Method name
- `direction` (optional): "callers" (default) or "callees"
- `depth` (optional): Traversal depth (default 2, max 4)
- `limit` (optional): Max nodes per level (default 20, max 50)

### `psi_impact_analysis` — Blast Radius
Analyze what breaks if a symbol changes. Returns affected files, symbols, risk level, and summary.

**Use when:**
- You're about to change a method/class and need to know the risk
- You want a risk assessment before a refactor
- You need to enumerate all affected code for a change

**Parameters:**
- `symbol` (required): Symbol name (method, class, field)
- `depth` (optional): Transitive depth (default 1, max 3)
- `limit` (optional): Max affected items (default 100, max 300)

**Risk levels:** LOW (<5 affected), MEDIUM (5-20), HIGH (21-50), CRITICAL (>50 or touches public API).

### `psi_file_structure` — File Members
Get file members with signatures (no bodies). Returns classes, fields, methods, and nested classes.

**Use when:**
- You need to understand the structure of a file without reading the whole thing
- You need method signatures to know how to call them
- You want a quick overview of a class's API

**Parameters:**
- `file` (required): File path (project-relative or absolute)

**Prefer over reading the file when:** You only need the API (signatures), not the implementation. Saves tokens — a 500-line file produces a compact signature list.

### `psi_repo_map` — Project Overview
Importance-ranked symbol index for the project. Returns symbols sorted by reference count.

**Use when:**
- You need to understand the project's key classes at a glance
- You want to know which classes are most referenced (likely most important)
- You're orienting yourself in an unfamiliar codebase

**Parameters:**
- `limit` (optional): Max symbols (default 100, max 500)

## When to Use PSI Tools vs grep/read

| Task | Use PSI Tool | Use grep/read |
|------|-------------|---------------|
| Find declaration of a symbol | `psi_find_symbol` | |
| Find all callers of a method | `psi_find_references` or `psi_call_hierarchy` | |
| Understand file API | `psi_file_structure` | |
| Assess refactor risk | `psi_impact_analysis` | |
| Project overview | `psi_repo_map` | |
| Find text in strings/comments | | grep |
| Find config values, TODOs | | grep |
| Read implementation details | | read |
| Search across non-code files | | grep |

## Error Handling

- **"Indexing in progress, try again shortly"** (`retry: true`): The IDE is indexing. Wait a few seconds and retry.
- **"No symbol matching 'X' found"** (`retry: false`): The symbol doesn't exist. Check spelling or try a broader pattern.
- **"Ambiguous symbol name"** (`retry: false`): Multiple symbols match. Use the `file` parameter to disambiguate, or pick from the candidates list.
- **"PSI code intelligence tools are disabled"** (`retry: false`): Enable in Settings → Tools → Sigil → PSI Tools.
- **"scope: 'all' requires user opt-in"** (`retry: false`): Library scope is disabled. Use `scope: "project"` instead.

## Tips

- Start with `psi_find_symbol` to locate a symbol, then use `psi_find_references` or `psi_call_hierarchy` to explore its usage
- Use `psi_file_structure` before reading a file — it gives you the API surface in compact form
- `psi_impact_analysis` with `depth=1` is fast; `depth=2+` can be slow on widely-referenced symbols
- The context file (`.opencode/context/repo-structure.md`) gives you a static overview; use `psi_repo_map` for a fresh, importance-ranked view
- All tools return JSON. Parse the `error` and `retry` fields to decide whether to retry or refine your query