# IntelliJ MCP Tools — Parameter Reference

> Always-loaded reference for the IntelliJ MCP tools exposed by Sigil.
> Deployed by the plugin to `.opencode/context/intellij-mcp-tools.md`.

## Critical parameter rules

- **All file paths** are **project-relative** (e.g. `src/main/kotlin/Foo.kt`), NOT absolute.
- Pass `projectPath` (the project root) on **every** call — it disambiguates and speeds resolution.
- Lines/columns are **1-based**.
- Prefer `intellij_psi_find_symbol` / `intellij_search_symbol` (semantic) over text/grep search.
- Prefer `intellij_analyze_calls` (call graph) over `intellij_psi_find_references` when you need the call tree.
- **Never guess** a symbol's FQN — find it first with `intellij_search_symbol`, then pass the returned FQN.

## File / project operations

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_read_file` | `file_path`, `offset?`, `limit?` | 1-based offset. Default limit 2000, max 5000. |
| `intellij_create_new_file` | `pathInProject`, `text`, `overwrite?` | Path relative to project root. |
| `intellij_open_file_in_editor` | `filePath` | Project-relative or absolute. |
| `intellij_reformat_file` | `files: [String]` | List of project-relative paths. |
| `intellij_list_directory_tree` | `directoryPath`, `maxDepth?` | Project-relative. |
| `intellij_search_file` | `q` (glob), `paths?`, `limit?` | Glob, e.g. `**/*.kt`. |
| `intellij_search_text` | `q`, `paths?`, `limit?` | Literal substring. |
| `intellij_search_regex` | `q`, `paths?`, `limit?` | Regex pattern. |
| `intellij_get_file_problems` | `filePath`, `errorsOnly?` | Per-file inspections. |
| `intellij_lint_files` | `files: [String]`, `min_severity?` | Batch linting. `"warning"` or `"error"`. |
| `intellij_build_project` | `filesToRebuild?`, `rebuild?` | Full build if no files given. |

## Symbol / code intelligence

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_search_symbol` | `q`, `kind?`, `paths?`, `limit?` | Semantic lookup by name fragment. |
| `intellij_psi_find_symbol` | `pattern` | Alias of `intellij_search_symbol`. |
| `intellij_get_symbol_info` | `filePath`, `line`, `column` | Quick docs for symbol at position. |
| `intellij_psi_file_structure` | `file` | Class/method/field outline. |
| `intellij_psi_find_references` | `symbol`, `file?`, `scope?` | All usages of a symbol. |
| `intellij_psi_call_hierarchy` | `symbol`, `direction?`, `depth?`, `limit?` | `direction`: `"callers"` or `"callees"`. |
| `intellij_psi_impact_analysis` | `symbol`, `file?`, `depth?` | Blast radius of a change. |
| `intellij_psi_repo_map` | `limit?` | Importance-ranked symbol index. |

## Call analysis (PREFER over find_references for dependency graphs)

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_analyze_calls` | `symbolFqn`, `analysisKind`, `depth?` | `analysisKind`: `"INCOMING_CALLS"` or `"OUTGOING_CALLS"`. Pass the full FQN (e.g. `com.example.Service.run`). |

## Refactoring

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_rename_refactoring` | `pathInProject`, `symbolName`, `newName` | Updates ALL references automatically. |

## Run / debug

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_get_run_configurations` | `filePath?` | Lists configs, or run points in a file. |
| `intellij_execute_run_configuration` | `configurationName` **OR** `filePath`+`line` | Wait via `waitForExit`. |
| `intellij_execute_terminal_command` | `command`, `executeInShell?`, `timeout?` | IDE-integrated terminal. |

## Debugger (intellij_xdebug_*)

All debugger tools take `sessionId` (from `intellij_xdebug_get_debugger_status` or `intellij_xdebug_start_debugger_session`).

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_xdebug_start_debugger_session` | `configurationName` **OR** `filePath`+`line` | Set a breakpoint first. |
| `intellij_xdebug_get_debugger_status` | — | Lists all active sessions. |
| `intellij_xdebug_control_session` | `action` | `STEP_INTO`/`STEP_OVER`/`STEP_OUT`/`RESUME`/`PAUSE`/`STOP`/`WAIT_FOR_PAUSE`/`DRAIN_EVENTS`. |
| `intellij_xdebug_get_stack` | `threadId?` | After pause. |
| `intellij_xdebug_get_frame_values` | `frameIndex?`, `depth?` | `0` = top frame. |
| `intellij_xdebug_get_value_by_path` | `path: [String]`, `frameIndex?` | Drill into nested objects. |
| `intellij_xdebug_evaluate_expression` | `expression`, `frameIndex?`, `depth?` | Raw expression text. |
| `intellij_xdebug_set_breakpoint` | `filePath`, `line` | `logExpression`+`suspendPolicy=NONE` = non-suspending logpoint. |
| `intellij_xdebug_set_variable` | `path: [String]`, `newValue` | Mutate a variable. |

## Database (intellij_*_database_*)

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_list_database_connections` | — | All configured connections. |
| `intellij_test_database_connection` | `id` | Connection ID from list. |
| `intellij_create_database_connection` | `name`, `dbms`, `url`, `needToCheckDs` | |
| `intellij_execute_sql_query` | `connectionId`, `databaseName`, `schemaName`, `queryText` | Returns CSV. |
| `intellij_list_database_schemas` | `connectionId` | |
| `intellij_list_schema_objects` | `connectionId`, `databaseName`, `schemaName`, `kind?` | |
| `intellij_preview_table_data` | `connectionId`, `databaseName`, `schemaName`, `tableName` | |

## VCS / git

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_get_repositories` | — | VCS roots. |
| `intellij_git_status` | `repositoryPathRelativeToProject?` | Porcelain status. |

## Project structure

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_get_project_modules` | — | |
| `intellij_get_project_dependencies` | — | |
| `intellij_get_all_open_file_paths` | — | Active editor paths. |

## Threading / lock analysis (use sparingly — heuristic, not reliable)

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_find_threading_requirements_usages` | `filePath`, `line`, `column` | UI-thread constraints. |
| `intellij_find_lock_requirements_usages` | `filePath`, `line`, `column` | Read/Write lock needs. |

## Other

| Tool | Key params | Notes |
|------|-----------|-------|
| `intellij_execute_tool` | `command` | Generic tool executor. |
| `intellij_skill_search` | `mode`, `q` | Unified search: `file`/`text`/`regex`/`symbol`. |
| `intellij_create_ij_module` | `parentDirectoryPath`, `moduleName`, `kindTemplateName` | `kindTemplateName`: `empty`/`frontend`/`backend`/`shared`. |
| `intellij_recognize_ij_module_kind` | `descriptorPath` | |

## apply_patch (NOT intellij-prefixed — general tool)

Patch format for `intellij_apply_patch` (and the generic `apply_patch`):

```
*** Begin Patch
*** Update File: path/relative/to/project/File.kt
@@ path/relative/to/project/File.kt:LINE_NUMBER
-    old line content
+    new line content
*** End Patch
```

- `*** Update File:` line takes a **project-relative** path.
- `@@` hunk header includes the file path and **starting line number**.
- Use `*** Begin Patch` at the top and `*** End Patch` at the bottom.
- For new files use `*** Add File: path` instead of `*** Update File:`.
- For deletions use `*** Delete File: path`.

If `intellij_apply_patch` fails, fix the patch format and retry. Do NOT fall back to shell commands or helper scripts — see the agent's RULE 3.