package com.opencode.acp.review

/**
 * The review OpenCode skills — instructions injected into the chat when the
 * user invokes a `/review-*` slash command.
 *
 * ## Two commands, two directions
 *
 * - **`/review-perform`** → LLM reviews VCS-changed files and writes
 *   `.review/<path>.json` files with open comments. Direction: LLM → human.
 *   Built by [buildPerformPrompt]. The prompt is explicitly **adversarial** —
 *   it sets a "this code is untrusted, find every flaw" mindset and lists the
 *   specific failure patterns LLM-generated code is known to produce
 *   (injection, missing validation, auth bypass, error-handling gaps,
 *   logic-vs-syntax divergence, hallucinated imports, architectural drift).
 *   See the research sources cited inline in the prompt.
 * - **`/review-resolve`** → LLM reads all open `.review/` JSON files, fixes
 *   the code, marks comments resolved. Direction: human → LLM.
 *   Built by [buildResolvePrompt].
 *
 * ## Design deviation from the TDD (documented)
 *
 * The TDD §9.4 specifies a `SKILL.md` file at `.opencode/skills/review/SKILL.md`
 * in each project, plus a `ReviewSkillRegistrar` that registers the slash
 * command. The shipped implementation takes a simpler, plugin-bundled
 * approach: the skill text lives here as Kotlin constants, and
 * [ReviewSkillRegistrar] documents how it is wired into the chat (via
 * [com.opencode.acp.chat.viewmodel.ChatViewModel]).
 *
 * Rationale: a per-project `SKILL.md` requires file I/O on every project open,
 * a write path that races with `.gitignore` checks, and a discoverability
 * mechanism (the agent has to know to read `.opencode/skills/review/`).
 * Bundling the skill text in the plugin is simpler, version-controlled with
 * the plugin, and injected directly into the prompt on `/review-*` — no file
 * discovery needed.
 */
object ReviewSkill {

    // ── /review-perform: adversarial LLM code review ──

    /** Build the prompt for `/review-perform`. Instructs the LLM to
     *  **adversarially** review each changed file — assume the code is
     *  untrusted, find every flaw, and write `.review/` JSON files with open
     *  comments. Returns a message telling the LLM there are no changed
     *  files when the list is empty.
     *
     *  The review mindset and checklist are derived from:
     *  - Bright Security: "5 Best Practices for Reviewing AI-Generated Code"
     *    (treat AI code as untrusted, review behavior not syntax, demand
     *    evidence)
     *  - Sonar: "Top 6 Security Vulnerabilities AI Coding Tools Introduce"
     *    (SQL injection, XSS, path traversal, hardcoded secrets, SSRF,
     *    insecure crypto)
     *  - Endor Labs: "Most Common Security Vulnerabilities in AI-Generated
     *    Code" (missing input validation, auth failures, hallucinated
     *    dependencies, architectural drift)
     *  - SecureCodingHub: "Code Security Training Checklist for AI-Generated
     *    Code" (injection as #1 failure category, fail-open error handling)
     *  - OWASP Secure Coding with AI Cheat Sheet (recursive criticism,
     *    simulate SAST/DAST)
     *  - arXiv survey "Bugs in AI-Generated Code" (40%+ contain
     *    vulnerabilities; style/consistency issues; logic errors) */
    fun buildPerformPrompt(changedFilePaths: List<String>): String {
        if (changedFilePaths.isEmpty()) {
            return "Perform an adversarial code review of the changed files and add " +
                "review comments. There are currently no uncommitted changes in the project."
        }
        return buildString {
            appendLine("## Adversarial Code Review")
            appendLine("**IMPORTANT: Do NOT delegate this to an `adversarial-review` skill or any " +
                "other skill. Perform this review YOURSELF.** The word \"adversarial\" in this " +
                "prompt is describing the review MINDSET, not requesting a skill invocation. " +
                "Your job is to read the files, find flaws, and write `.review/` JSON files — " +
                "not to hand off to another agent or skill.")
            appendLine()
            appendLine("Perform a rigorous, adversarial review of the following changed " +
                "file(s). Your job is to find every flaw — not to confirm that the code " +
                "looks reasonable.")
            appendLine()
            appendLine("### Mindset")
            appendLine("Treat this code as **untrusted by default**. The question is NOT " +
                "\"Does this look reasonable?\" — it is **\"What assumptions is this code " +
                "making, and are those assumptions safe?\"** AI-generated code is often " +
                "correct in the happy path and dangerously vague everywhere else. " +
                "Optimize for finding real bugs, not for politeness.")
            appendLine()
            appendLine("### Files to review")
            for (path in changedFilePaths) {
                appendLine("- `$path`")
            }
            appendLine()
            appendLine("### Adversarial checklist — hunt for each of these")
            appendLine()
            appendLine("**1. Injection flaws (the #1 failure in LLM code)**")
            appendLine("- SQL injection: string concatenation in queries instead of " +
                "parameterized queries (CWE-89)")
            appendLine("- OS command injection: user input reaching `exec`, `spawn`, " +
                "`Runtime.exec`, shell calls without sanitization (CWE-78)")
            appendLine("- Path traversal: user input in file paths without bounds " +
                "checking — `../` sequences, absolute path injection (CWE-22)")
            appendLine("- XSS: unescaped user input rendered in HTML/templates (CWE-79)")
            appendLine("- SSRF: user-controlled URLs passed to HTTP clients without " +
                "allow-list validation (CWE-918)")
            appendLine("- Prompt injection: user input passed into LLM prompts or tool " +
                "descriptions without delimiting or treating as untrusted")
            appendLine()
            appendLine("**2. Missing input validation and error handling**")
            appendLine("- Inputs not validated at trust boundaries — every entry point " +
                "from HTTP, CLI, files, network, user UI (CWE-20)")
            appendLine("- Fail-open error handling: `catch (e) { }` or `catch (e) { " +
                "return null }` that silently swallows errors and continues")
            appendLine("- Missing null/empty checks on values that can be null/empty")
            appendLine("- Unhandled exception branches — especially in async/coroutine " +
                "code where exceptions don't propagate the same way")
            appendLine("- Integer overflow in allocation arithmetic: `malloc(n * " +
                "sizeof(T))` without bounds checking (CWE-190)")
            appendLine()
            appendLine("**3. Authentication and authorization failures**")
            appendLine("- Auth checks tied to UI/client logic instead of server/enforcement " +
                "layer (CWE-306)")
            appendLine("- Broken access control: missing ownership checks, role checks " +
                "that assume a fixed set of roles (CWE-284)")
            appendLine("- Hardcoded credentials, API keys, secrets in source (CWE-798)")
            appendLine("- Session state reused across unrelated actions")
            appendLine("- \"Temporary\" security bypasses left in place (CWE-693)")
            appendLine()
            appendLine("**4. Insecure cryptography and data exposure**")
            appendLine("- Weak algorithms in sensitive contexts: MD5 for passwords, " +
                "ECB mode, hardcoded IVs, custom crypto (CWE-327)")
            appendLine("- Secrets logged, serialized, or included in error messages " +
                "(CWE-200)")
            appendLine("- PII or sensitive data exposed in API responses, logs, or " +
                "error messages")
            appendLine("- Overly broad file permissions (`chmod 777`) (CWE-732)")
            appendLine()
            appendLine("**5. Logic correctness (not just syntax)**")
            appendLine("- AI optimizes for syntactic correctness and surface plausibility, " +
                "NOT semantic correctness. The code compiles, passes type checks, looks " +
                "reasonable — but the LOGIC may be wrong.")
            appendLine("- Off-by-one errors in loops, boundaries, slicing")
            appendLine("- Race conditions: shared mutable state accessed from multiple " +
                "threads/coroutines without synchronization")
            appendLine("- Wrong return values, inverted conditions, misplaced negations")
            appendLine("- Missing edge cases: empty lists, zero counts, negative numbers, " +
                "concurrent modification, timeout/cancellation")
            appendLine("- Deadlocks: lock acquisition in inconsistent order, " +
                "`wait()` without `notify()`, coroutine cancellation that doesn't " +
                "propagate")
            appendLine()
            appendLine("**6. Hallucinated imports and dependencies**")
            appendLine("- Imports of packages/modules that don't exist in the project's " +
                "dependency tree — AI invents plausible-sounding package names")
            appendLine("- Method/class signatures that don't match the real API — " +
                "check that every external call matches the actual library version " +
                "the project uses, not a version from training data")
            appendLine("- Deprecated APIs used as if current")
            appendLine()
            appendLine("**7. Dead code and cross-file side effects**")
            appendLine("- Leftover code from abandoned approaches: unused variables, " +
                "unreachable branches, commented-out blocks")
            appendLine("- Changes outside the scope of the task: deletions, signature " +
                "changes, renamed variables that weren't part of the request")
            appendLine("- Modifications to config files, lockfiles, CI/CD, or test files " +
                "that weren't requested")
            appendLine()
            appendLine("**8. Design and maintainability**")
            appendLine("- SOLID violations: God objects, mixed responsibilities, " +
                "dependency on concretions instead of abstractions")
            appendLine("- Duplicated logic that should be extracted")
            appendLine("- Naming that doesn't match codebase conventions or is " +
                "misleading (e.g. `isX` that has side effects)")
            appendLine("- Architectural drift: code in the wrong layer, bypassing the " +
                "project's service/repository/controller boundaries")
            appendLine("- Over-engineering: premature abstraction, YAGNI violations, " +
                "unnecessary generality")
            appendLine()
            appendLine("**9. Test coverage gaps**")
            appendLine("- New code paths with no tests")
            appendLine("- Tests that pass but don't cover edge cases (empty input, " +
                "concurrent access, error paths)")
            appendLine("- Tests that assert the wrong thing (testing implementation " +
                "details instead of behavior)")
            appendLine()
            appendLine("**10. Recursive self-criticism (OWASP RCI technique)**")
            appendLine("After your first pass, review your OWN findings: did you miss " +
                "anything? Did you mark something as an issue that's actually fine? " +
                "Be skeptical of your own review — LLMs that generate vulnerable code " +
                "also miss vulnerabilities in review mode ~21% of the time.")
            appendLine()
            appendLine("### How to write review comments")
            appendLine("Comments are stored as one JSON file per reviewed source file in " +
                "the `.review/` directory, mirroring the project source tree.")
            appendLine()
            appendLine("**File path mapping:** a source file at `src/main/kotlin/Service.kt` " +
                "gets a review file at `.review/src/main/kotlin/Service.kt.json`.")
            appendLine()
            appendLine("**JSON schema (one file per source file):**")
            appendLine("```json")
            appendLine("""{"formatVersion": 1, "comments": [""")
            appendLine("""  {""")
            appendLine("""    "id": "cmt_<12 hex chars>",""")
            appendLine("""    "startLine": 42,""")
            appendLine("""    "endLine": 45,""")
            appendLine("""    "comment": "N+1 query in loop — use JOIN FETCH or batch loading.\n\nThis is a real query-per-iteration bug, not a style issue."""")
            appendLine("""    "severity": "warning",""")
            appendLine("""    "status": "open",""")
            appendLine("""    "author": "ai-review",""")
            appendLine("""    "createdAt": "2026-06-17T10:30:00Z"""")
            appendLine("""  }""")
            appendLine("""]}}""")
            appendLine("```")
            appendLine()
            appendLine("**Rules:**")
            appendLine("1. `id` must be unique: `cmt_` + 12 hex characters (e.g. `cmt_a3f1c2d4b5e6`)")
            appendLine("2. `startLine` and `endLine` are 1-based line numbers in the CURRENT file")
            appendLine("3. `endLine` must be >= `startLine`")
            appendLine("4. `severity`: `\"error\"` for security bugs / crashes / data loss, " +
                "`\"warning\"` for logic bugs / bad patterns / missing tests, " +
                "`\"info\"` for style / maintainability / minor improvements")
            appendLine("5. `status` must be `\"open\"` for new comments")
            appendLine("6. `author` should be `\"ai-review\"`")
            appendLine("7. `createdAt` is an ISO 8601 UTC timestamp with second precision " +
                "(e.g. `\"2026-06-17T10:30:00Z\"`)")
            appendLine("8. If a `.review/` JSON file already exists for a source file, READ it " +
                "first, add your new comments to the existing `comments` array, and write " +
                "the merged file back. Do NOT overwrite existing comments.")
            appendLine("9. **Be specific and rigorous.** Every comment must reference exact " +
                "line numbers, explain the issue precisely, and state the fix. A comment " +
                "like \"this could be better\" is useless — write \"Line 42: SQL " +
                "injection via string concatenation. Use parameterized queries with " +
                "`PreparedStatement` or the project's query builder.\" instead.")
            appendLine("10. Only comment on lines that are part of the changes, not the " +
                "entire file — unless the change introduced a bug that affects " +
                "surrounding code.")
            appendLine("11. If you find NO issues in a file, do NOT create an empty `.review/` " +
                "file. Absence means no comments.")
            appendLine("12. Prefer fewer high-quality `error`/`warning` comments over many " +
                "low-value `info` comments. Don't pad the review with nitpicks.")
        }
    }

    // ── /review-perform-gaming: adversarial review for game engines ──

    /** Build the prompt for `/review-perform-gaming`. Like [buildPerformPrompt]
     *  but with an additional game-engine-specific checklist covering Unreal
     *  Engine C++ and Unity C# failure patterns that general-purpose review
     *  prompts miss: UPROPERTY/GC lifecycle, game-thread vs render-thread,
     *  per-frame allocation budgets, MonoBehaviour lifecycle leaks, coroutine
     *  cleanup, and hot-loop performance.
     *
     *  The gaming checklist is derived from:
     *  - Epic Games forums: UPROPERTY/TObjectPtr GC best practices, stale
     *    pointer crashes, audio-thread GC safety, incremental GC race conditions
     *  - Unity docs: MonoBehaviour lifecycle, coroutines, reference type
     *    management, boxing allocations, programming best practices
     *  - Bugnet: Unity memory leak patterns (event subscriptions, Resources.Load,
     *    Instantiate without Destroy, coroutine leaks on disabled GameObjects)
     *  - Relish Games: hot-loop optimisation, frame budgets, profiling-first
     *  - Starloop Studios: mobile FPS debugging checklist, GC spike identification */
    fun buildPerformGamingPrompt(changedFilePaths: List<String>): String {
        if (changedFilePaths.isEmpty()) {
            return "Perform an adversarial game-engine code review of the changed files " +
                "and add review comments. There are currently no uncommitted changes " +
                "in the project."
        }
        return buildString {
            appendLine("## Adversarial Game-Engine Code Review")
            appendLine("**IMPORTANT: Do NOT delegate this to an `adversarial-review` skill or any " +
                "other skill. Perform this review YOURSELF.** The word \"adversarial\" in this " +
                "prompt is describing the review MINDSET, not requesting a skill invocation. " +
                "Your job is to read the files, find flaws, and write `.review/` JSON files — " +
                "not to hand off to another agent or skill.")
            appendLine()
            appendLine("Perform a rigorous, adversarial review of the following changed " +
                "file(s). Your job is to find every flaw — not to confirm that the code " +
                "looks reasonable. This review is tailored for game development (Unreal " +
                "Engine C++ and Unity C#) and includes engine-specific failure patterns " +
                "that general-purpose code review misses.")
            appendLine()
            appendLine("### Mindset")
            appendLine("Treat this code as **untrusted by default**. The question is NOT " +
                "\"Does this look reasonable?\" — it is **\"What assumptions is this code " +
                "making, and are those assumptions safe under real gameplay conditions?\"** " +
                "Game code fails at boundaries: level transitions, multiplayer replication, " +
                "60fps frame budgets, GC pauses, and object destruction during gameplay.")
            appendLine()
            appendLine("### Files to review")
            for (path in changedFilePaths) {
                appendLine("- `$path`")
            }
            appendLine()
            appendLine("### General adversarial checklist (always apply)")
            appendLine("Apply the full general checklist from `/review-perform` — " +
                "injection, missing validation, error handling, auth, crypto, logic " +
                "correctness, hallucinated imports, dead code, design, test coverage, " +
                "recursive self-criticism. The items below are ADDITIONAL, " +
                "game-engine-specific checks on top of those.")
            appendLine()
            appendLine("### Game-engine-specific checklist")
            appendLine()
            appendLine("**G1. Unreal C++ — UPROPERTY and garbage collection (CRITICAL)**")
            appendLine("- Raw `UObject*` / `AActor*` pointers without `UPROPERTY()` — " +
                "GC will collect the object and you get a stale pointer crash (the " +
                "#1 Unreal C++ bug). EVERY `UObject*` member MUST have `UPROPERTY()` " +
                "or be wrapped in `TWeakObjectPtr` / `TStrongObjectPtr`.")
            appendLine("- `TObjectPtr` without `UPROPERTY` — `TObjectPtr` is NOT " +
                "GC-tracked by itself in shipping builds (it becomes a raw pointer). " +
                "Use BOTH `UPROPERTY()` + `TObjectPtr` for member references.")
            appendLine("- Raw pointers to `TArray` owned by another object " +
                "(`TArray<AActor*>*`) — if the owner is destroyed, you have a " +
                "use-after-free. Store a reference to the OWNER, not the array.")
            appendLine("- `TSubclassOf<T>` without `UPROPERTY` — class gets GC'd " +
                "during level transitions, causing crashes in packaged builds " +
                "(works in PIE, crashes in shipping — a known Epic bug pattern).")
            appendLine("- Storing `UObject*` on non-Game threads (Audio Render Thread, " +
                "Async tasks) without `TStrongPtr` or `FGCObject` — GC runs " +
                "concurrently and collects the object mid-use.")
            appendLine("- Accessing `UObject` methods during `IsGarbageCollecting()` — " +
                "crashes. Check `!IsGarbageCollecting()` before UObject access from " +
                "callbacks that may fire during GC.")
            appendLine()
            appendLine("**G2. Unreal C++ — threading and async (CRITICAL)**")
            appendLine("- Game-thread-only APIs called from worker threads: " +
                "`GetWorld()`, `SpawnActor()`, `Destroy()`, Blueprint calls, " +
                "`TimerManager`, `GetTimerManager()` — these crash or silently " +
                "fail off the game thread. Use `AsyncTask(ENamedThreads::GameThread, " +
                "[&]() { ... })` to bounce back.")
            appendLine("- `TFuture`/`TPromise` without `.Wait()` timeout — can hang " +
                "forever if the async task never completes (e.g. network timeout).")
            appendLine("- Race conditions on shared `FThreadSafeBool` / `FThreadSafeCounter` " +
                "used as a proxy for proper synchronization — they're atomic for " +
                "read/write but don't provide happens-before guarantees for the " +
                "DATA they guard.")
            appendLine("- Async tasks capturing `this` or raw `UObject*` by reference " +
                "(`[&]` or `[this]`) — the object may be destroyed before the " +
                "task runs. Capture a `TWeakObjectPtr` or copy the needed data.")
            appendLine()
            appendLine("**G3. Unreal C++ — Actor/Component lifecycle**")
            appendLine("- `BeginPlay` accessing other Actors that may not have " +
                "`BeginPlay` yet (non-deterministic order). Use `Start()` for " +
                "cross-Actor references, not `Awake`/`BeginPlay`.")
            appendLine("- `EndPlay` / `BeginDestroy` not cleaning up: timers not " +
                "cleared (`GetWorldTimerManager().ClearTimer(Handle)`), delegates " +
                "not unbound, async tasks not cancelled.")
            appendLine("- `Destroy()` called on an Actor from within that Actor's " +
                "own callback — use `MarkPendingKill()` or schedule destruction " +
                "for next frame via a flag.")
            appendLine("- Component `Tick` running after the owning Actor is " +
                "pending kill — check `!IsPendingKill()` in Tick before accessing.")
            appendLine()
            appendLine("**G4. Unity C# — GC allocations in hot paths (CRITICAL)**")
            appendLine("- `Update()` / `FixedUpdate()` / `LateUpdate()` allocating " +
                "every frame: string concatenation, `new List<T>()`, LINQ queries, " +
                "`GetComponent<T>()` called per-frame, `foreach` on non-cached " +
                "collections, boxing value types (`enum` as dictionary key, " +
                "`params object[]`).")
            appendLine("- `new WaitForSeconds(x)` inside a coroutine yield — " +
                "allocates a new object every frame. Cache it as a field: " +
                "`private readonly WaitForSeconds _wait = new(1f);`")
            appendLine("- Closures / lambdas / delegate allocations in Update: " +
                "`someEvent += () => { ... }` creates a delegate object every " +
                "call if done per-frame. Assign once in `Awake`/`OnEnable`.")
            appendLine("- `string.Format` / interpolated strings ($\"...\") in " +
                "hot paths — each creates a new string + boxing for value types.")
            appendLine("- Goal: **zero managed allocations per frame** in the " +
                "core game loop. Check with the Profiler's GC Alloc column.")
            appendLine()
            appendLine("**G5. Unity C# — MonoBehaviour lifecycle and leaks (CRITICAL)**")
            appendLine("- Event subscriptions without unsubscription in `OnDisable`/" +
                "`OnDestroy` — the #1 Unity memory leak. Every `+=` needs a matching " +
                "`-=` or the publisher holds the subscriber alive forever.")
            appendLine("- `Instantiate()` without matching `Destroy()` — spawned " +
                "projectiles, particles, UI elements that never get cleaned up. " +
                "Use object pooling for frequently spawned/destroyed objects.")
            appendLine("- `Resources.Load()` without `Resources.UnloadUnusedAssets()` " +
                "— assets pinned in memory for the entire session. Null references " +
                "BEFORE calling UnloadUnusedAssets, or it won't free them.")
            appendLine("- Coroutines not stopped on `OnDisable`/`OnDestroy` — " +
                "a running coroutine holds a reference to its MonoBehaviour, " +
                "preventing GC. Call `StopCoroutine` / `StopAllCoroutines` in " +
                "`OnDisable`. Coroutines started by a Manager on behalf of this " +
                "component are NOT stopped when this component is disabled.")
            appendLine("- `StartCoroutine` returning `Coroutine` reference not " +
                "stored — can't stop it later. Store the reference and stop it " +
                "in OnDisable: `private Coroutine _myRoutine; ... StopCoroutine(" +
                "_myRoutine);`")
            appendLine("- Accessing other GameObjects in `OnDestroy` — they may " +
                "already be destroyed. Guard with null checks or use " +
                "`OnApplicationQuit` for global cleanup.")
            appendLine("- `DontDestroyOnLoad` objects accumulating across scene " +
                "loads — created multiple times if not guarded with a static " +
                "instance check (singleton pattern).")
            appendLine()
            appendLine("**G6. Unity C# — threading and coroutines**")
            appendLine("- UnityEngine APIs called from background threads — " +
                "`Transform`, `GameObject`, `GetComponent`, `Physics` all crash " +
                "off the main thread. Use `UnityMainThreadDispatcher` or " +
                "`Awaitable` (Unity 6+) to bounce back.")
            appendLine("- `Task.Result` / `Task.Wait()` on the main thread — " +
                "deadlock. Use `await` or `UniTask` (which is main-thread-aware).")
            appendLine("- Coroutines treated as threads — they run on the main " +
                "thread, so blocking calls (`Thread.Sleep`, `Task.Wait`) inside " +
                "a coroutine freeze the entire game.")
            appendLine()
            appendLine("**G7. Performance — frame budgets and hot loops (both engines)**")
            appendLine("- O(n²) loops in per-frame code: nested loops over all " +
                "entities, `FindObjectsOfType` in Update, `GameObject.Find` in " +
                "Update. Cache references in `Awake`/`Start`/`BeginPlay`.")
            appendLine("- Virtual function calls in tight inner loops — the CPU " +
                "can't predict the indirect jump. Mark methods `final` or use " +
                "function pointers / `static` dispatch for hot paths.")
            appendLine("- No early-out in per-frame loops: entities that are " +
                "off-screen, inactive, too far away, or dead are still processed. " +
                "Add spatial partitioning (grid/quadtree/BVH) or an active-entity " +
                "list.")
            appendLine("- Memory layout: array-of-structs vs struct-of-arrays. " +
                "For >100 entities processed per frame, SoA gives better cache " +
                "locality (process all positions, then all velocities, etc.).")
            appendLine("- Per-frame allocations in C++ too: `TArray` temporaries " +
                "in Tick, ` FString` concatenation in hot paths. Pre-allocate " +
                "buffers as members and `Reset()` each frame (keeps capacity, " +
                "clears content).")
            appendLine("- Missing frame-budget awareness: 60fps = 16.6ms/frame, " +
                "30fps = 33.3ms. A single `GetComponent` or `FindObjectsOfType` " +
                "can blow the budget. Profile before optimizing — intuition is " +
                "wrong ~80% of the time.")
            appendLine()
            appendLine("**G8. Blueprint interop (Unreal)**")
            appendLine("- C++ functions exposed to Blueprint without " +
                "`BlueprintCallable` / `BlueprintPure` — silently invisible to BP.")
            appendLine("- `UPROPERTY(BlueprintReadWrite)` on members that should " +
                "be `BlueprintReadOnly` — exposes mutable state to BP, which can " +
                "break C++ invariants at runtime.")
            appendLine("- BlueprintImplementableEvent called from C++ without " +
                "null-checking — if no BP override, it's a no-op, but the call " +
                "still has dispatch overhead. For hot paths, prefer " +
                "`BlueprintNativeEvent` with a C++ default implementation.")
            appendLine("- `TArray` / `TMap` passed by value across BP→C++ " +
                "boundary — copies the entire container. Pass by `const&`.")
            appendLine()
            appendLine("**G9. Replication (Unreal multiplayer)**")
            appendLine("- `UPROPERTY(Replicated)` without `GetLifetimeReplicatedProps` " +
                "— the property won't actually replicate.")
            appendLine("- `ReplicatedUsing = OnRep_X` callback using the old value " +
                "parameter incorrectly — the callback fires AFTER the value is " +
                "updated, so the parameter is the NEW value, not the old one.")
            appendLine("- Server-only logic in `Tick` without `HasAuthority()` " +
                "guard — runs on clients too, causing desync.")
            appendLine("- `RPC` (Server/Client/NetMulticast) with wrong reliability " +
                "setting — unreliable for gameplay-critical events (hit detection), " +
                "reliable for cosmetic events (wastes bandwidth).")
            appendLine()
            appendLine("### How to write review comments")
            appendLine("Same JSON schema and rules as `/review-perform` — see that " +
                "command's rules. Summary: one `.review/<path>.json` per source file, " +
                "`id` = `cmt_` + 12 hex, `severity` = error/warning/info, `status` = " +
                "open, `author` = `\"ai-review\"`. Be specific with line numbers and " +
                "concrete fixes. Merge with existing comments. No empty files for " +
                "issue-free files.")
            appendLine()
            appendLine("### Severity guidance for game-engine issues")
            appendLine("- `\"error\"`: GC stale-pointer crash, main-thread violation, " +
                "per-frame allocation storm, unreferenced UPROPERTY, event leak")
            appendLine("- `\"warning\"`: missing early-out in hot loop, " +
                "GetComponent in Update, coroutine not stopped, Blueprint exposure issue")
            appendLine("- `\"info\"`: pass-by-value across BP boundary, " +
                "virtual call in inner loop, missing `final` keyword")
        }
    }

    // ── /review-resolve: LLM fixes existing comments ──

    /** Build the prompt for `/review-resolve`. Includes a summary of all open
     *  comments grouped by file, plus the resolution-workflow instructions.
     *  Returns a no-op message when there are no open comments. */
    fun buildResolvePrompt(index: ReviewIndex): String {
        if (index.totalOpen == 0) {
            return "Read and resolve all review comments. " +
                "There are currently no open review comments in the project."
        }
        return buildString {
            appendLine("## Review Comments Summary")
            appendLine("There are ${index.totalOpen} open review comment(s) across " +
                "${index.commentsByFile.size} file(s):")
            appendLine()
            for ((filePath, comments) in index.commentsByFile) {
                val open = comments.filter { it.status == ReviewStatus.OPEN }
                if (open.isNotEmpty()) {
                    appendLine("### $filePath (${open.size} open)")
                    for (c in open) {
                        appendLine("- Line ${c.startLine}-${c.endLine} [${c.severity}]: ${c.comment}")
                        if (c.revision != null) {
                            appendLine("  (revision: ${c.revision})")
                        }
                    }
                    appendLine()
                }
            }
            appendLine("## Instructions")
            appendLine("1. Review comments are stored in `.review/` JSON files, one per source file")
            appendLine("2. Read each open comment and navigate to the referenced file and lines")
            appendLine("3. Apply the necessary fix to address each comment")
            appendLine("4. After fixing, update the comment status to `resolved` by writing to " +
                "the corresponding `.review/` JSON file")
            appendLine("5. Set `resolution` to a brief description of what was done")
            appendLine("6. Change `status` from `\"open\"` to `\"resolved\"`")
            appendLine("7. Add `resolvedAt` with the current ISO 8601 UTC timestamp " +
                "(e.g., `\"2026-06-17T12:00:00Z\"`)")
        }
    }

    // ── /review-recheck: re-run review with existing comments + replies ──

    /** Build the prompt for `/review-recheck`. Includes the full comment+reply
     *  thread for all comments (open AND resolved) grouped by file, plus
     *  re-review instructions. The LLM should:
     *  1. Verify whether replies address each open comment — if so, mark resolved
     *  2. Re-raise still-open issues that replies don't address
     *  3. Mark resolved comments that are no longer relevant (code changed since comment)
     *  4. Add new comments for issues introduced after the first review
     *  5. Add ai-review replies when it disagrees with a user's dispute
     *  Returns a no-op message when there are no comments at all.
     *
     *  CRITICAL: The LLM must VERIFY user reply claims against the actual current
     *  code, not accept them at face value. "Fixed in commit abc123" must be
     *  checked by reading the referenced lines. */
    fun buildRecheckPrompt(index: ReviewIndex, changedFilePaths: List<String>): String {
        if (index.commentsByFile.isEmpty()) {
            return "Re-check review comments. There are currently no review comments " +
                "in the project. Run /review-perform first to generate initial comments."
        }
        return buildString {
            appendLine("## Re-Review: Verify and Update Existing Comments")
            appendLine("**IMPORTANT: Do NOT delegate this to a skill. Perform this re-review " +
                "YOURSELF.** Read the files, verify replies against the code, and write " +
                "updated `.review/` JSON files.")
            appendLine()
            appendLine("You previously reviewed this code and left review comments. " +
                "The user has replied to some comments and may have made code changes. " +
                "Your job is to re-evaluate each comment in light of its replies and " +
                "the current code state.")
            appendLine()
            appendLine("### Mindset")
            appendLine("Treat user replies as **claims to verify, not facts to accept**. " +
                "A reply that says \"fixed in commit abc123\" is a hypothesis — you must " +
                "READ THE CODE at the referenced lines to confirm the fix is actually " +
                "present and correct. A reply that says \"this is intentional\" is a " +
                "position to evaluate — if the code is still dangerous, keep the comment " +
                "open and explain why in an ai-review reply.")
            appendLine()
            appendLine("### Current changed files (may have been modified since first review)")
            if (changedFilePaths.isEmpty()) {
                appendLine("(no uncommitted changes — re-evaluate against the current committed state)")
            } else {
                for (path in changedFilePaths) {
                    appendLine("- `$path`")
                }
            }
            appendLine()
            appendLine("### Existing comments with replies")
            for ((filePath, comments) in index.commentsByFile) {
                if (comments.isEmpty()) continue
                appendLine("#### $filePath (${comments.size} comment(s))")
                for (c in comments) {
                    appendLine("- **[${c.severity}] Line ${c.startLine}-${c.endLine}** " +
                        "(status: ${c.status}): ${c.comment}")
                    if (c.replies.isNotEmpty()) {
                        appendLine("  Replies:")
                        for (r in c.replies) {
                            appendLine("  - **${r.author}**: ${r.text}")
                        }
                    } else {
                        appendLine("  (no replies)")
                    }
                }
                appendLine()
            }
            appendLine("### Instructions")
            appendLine("1. Read each comment and its replies")
            appendLine("2. **Read the current code at the referenced lines** — do NOT trust " +
                "reply claims without verifying against the actual code")
            appendLine("3. For each OPEN comment:")
            appendLine("   - If a reply explains the fix AND the code confirms it: " +
                "mark `status` = `\"resolved\"`, set `resolution` to the reply text, " +
                "add `resolvedAt` timestamp")
            appendLine("   - If the reply disputes the comment and, after reading the code, " +
                "you AGREE the dispute is valid: mark `status` = `\"resolved\"`, " +
                "set `resolution` to `\"Withdrawn: \" + reply text`")
            appendLine("   - If the reply disputes the comment but, after reading the code, " +
                "you STILL think it's valid: keep `status` = `\"open\"`, ADD a reply " +
                "(author = `\"ai-review\"`) explaining why the dispute doesn't hold, " +
                "citing the specific code that's still problematic")
            appendLine("   - If the code has changed and the comment no longer applies " +
                "(the referenced lines are gone or rewritten): mark `status` = " +
                "`\"resolved\"`, set `resolution` to `\"No longer applicable — code changed\"`")
            appendLine("4. For each RESOLVED comment: skip (already handled)")
            appendLine("5. Add NEW comments for any issues in the changed files " +
                "that weren't caught in the first review (use new `cmt_` IDs)")
            appendLine("6. Write updated `.review/` JSON files with the full " +
                "comment+reply state")
            appendLine()
            appendLine("### Reply preservation — CRITICAL")
            appendLine("When you update a comment (e.g. to mark it resolved), you MUST " +
                "preserve its existing `replies` array. Append new replies; do NOT " +
                "overwrite or drop existing ones. The plugin will verify this after " +
                "you finish and re-merge any dropped replies, but dropping them " +
                "corrupts the discussion history.")
            appendLine()
            appendLine("### JSON schema reminder")
            appendLine("Same schema as `/review-perform`, but each comment now has " +
                "a `replies` array:")
            appendLine("```json")
            appendLine("""{"formatVersion": 1, "comments": [""")
            appendLine("""  {"id": "cmt_...", "startLine": 42, "endLine": 45, """)
            appendLine("""   "comment": "...", "severity": "warning", "status": "open",""")
            appendLine("""   "author": "ai-review", "createdAt": "...", """)
            appendLine("""   "replies": [""")
            appendLine("""     {"id": "rpl_...", "author": "user", "text": "...", "createdAt": "..."}""")
            appendLine("""   ]}""")
            appendLine("""]}}""")
            appendLine("```")
            appendLine()
            appendLine("**Rules:**")
            appendLine("- Preserve existing replies when updating a comment — " +
                "append new replies, don't overwrite")
            appendLine("- Reply IDs: `rpl_` + 12 hex chars")
            appendLine("- When marking resolved, set `resolvedAt` to current ISO 8601 UTC")
            appendLine("- Read the existing `.review/` file before writing — merge, don't overwrite")
            appendLine("- Do NOT physically delete comments — use `status = \"resolved\"` " +
                "to close them. The audit trail must be preserved.")
            appendLine("- Only add `ai-review` replies when you DISAGREE with a user's " +
                "dispute. Do not add `ai-review` replies to confirm a fix — just mark " +
                "the comment resolved with `resolution` set to the reply text.")
        }
    }
}