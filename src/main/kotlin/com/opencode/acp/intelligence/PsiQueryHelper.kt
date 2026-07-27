package com.opencode.acp.intelligence

import com.intellij.mcpserver.reportToolActivity
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.opencode.acp.chat.util.AttachmentPathValidator
import com.opencode.acp.intelligence.model.AffectedSymbol
import com.opencode.acp.intelligence.model.CallHierarchyNode
import com.opencode.acp.intelligence.model.ClassStructure
import com.opencode.acp.intelligence.model.FileStructure
import com.opencode.acp.intelligence.model.ImpactResult
import com.opencode.acp.intelligence.model.MemberInfo
import com.opencode.acp.intelligence.model.ReferenceInfo
import com.opencode.acp.intelligence.model.RepoMapEntry
import com.opencode.acp.intelligence.model.SymbolInfo
import com.opencode.acp.intelligence.model.SymbolKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.getCallNameExpression
import java.io.File
import kotlin.math.ln

private val logger = KotlinLogging.logger {}

/**
 * Shared PSI query utilities for the MCP code intelligence toolsets.
 *
 * Instantiated per-call with the [Project] from the MCP coroutine context.
 * NOT stored as a field on toolset instances (toolsets are application-scoped
 * singletons; Project references must not leak across project close).
 *
 * All `run*` methods are `suspend` and use [readAction] for PSI access.
 * Every method checks (in order): disabled → dumb mode → scope:all →
 * path traversal (if a `file` param is present) → project disposal.
 *
 * Binding rules (TDD §12.2):
 *  - `forEach(Processor)` + `ensureActive()`, never `findAll()`.
 *  - Re-throw `CancellationException` before generic catch.
 *  - `reportToolActivity()` for long operations.
 *  - Kotlin Analysis API guarded fallback to `<inferred>`.
 *  - Path traversal guard on all `file` params.
 */
class PsiQueryHelper(private val project: Project) {

    // ------------------------------------------------------------------
    // PSI-backed query methods
    // ------------------------------------------------------------------

    /**
     * `psi_find_symbol` — find symbols by name pattern across the project.
     *
     * Uses [PsiShortNamesCache] for Java/Kotlin name-based lookup. Results are
     * filtered by [kind] if provided, then truncated to [limit] and formatted
     * via [SymbolFormatter.formatSymbols].
     */
    suspend fun runSymbolSearch(pattern: String, scope: String, limit: Int, kind: String?): String {
        val guard = preGuard(scope)
        if (guard != null) return guard
        val scopeResult = parseScope(scope)
        val kindFilter = kind?.let { parseSymbolKind(it) }
        val effectiveLimit = limit.coerceIn(1, MAX_SYMBOL_SEARCH_RESULTS)

        return try {
            withTimeout(TOOL_TIMEOUT_DEFAULT_MS) {
                readAction {
                    if (project.isDisposed) return@readAction SymbolFormatter.formatError("Project closed", retry = false)
                    val cache = PsiShortNamesCache.getInstance(project)
                    val results = mutableListOf<SymbolInfo>()

                    fun addClasses() {
                        val classes = cache.getClassesByName(pattern, scopeResult.scope)
                        for (cls in classes) {
                            if (kindFilter != null && kindFilter != symbolKindOf(cls)) continue
                            results.add(buildSymbolInfo(cls))
                        }
                    }
                    fun addMethods() {
                        val methods = cache.getMethodsByName(pattern, scopeResult.scope)
                        for (m in methods) {
                            if (kindFilter != null && kindFilter != SymbolKind.METHOD && kindFilter != SymbolKind.FUNCTION && kindFilter != SymbolKind.CONSTRUCTOR) continue
                            results.add(buildSymbolInfo(m))
                        }
                    }
                    fun addFields() {
                        val fields = cache.getFieldsByName(pattern, scopeResult.scope)
                        for (f in fields) {
                            if (kindFilter != null && kindFilter != SymbolKind.FIELD && kindFilter != SymbolKind.PROPERTY) continue
                            results.add(buildSymbolInfo(f))
                        }
                    }

                    when (kindFilter) {
                        null -> { addClasses(); addMethods(); addFields() }
                        SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.OBJECT, SymbolKind.ANNOTATION, SymbolKind.COMPANION_OBJECT -> addClasses()
                        SymbolKind.METHOD, SymbolKind.FUNCTION, SymbolKind.CONSTRUCTOR -> addMethods()
                        SymbolKind.FIELD, SymbolKind.PROPERTY -> addFields()
                        else -> { /* PACKAGE, TYPE_ALIAS, PARAMETER — not indexed by short-names cache */ }
                    }

                    val (kept, _, _) = truncateResults(results, effectiveLimit)
                    SymbolFormatter.formatSymbols(kept)
                }
            }
        } catch (e: TimeoutCancellationException) {
            SymbolFormatter.formatError("Symbol search timed out", retry = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] runSymbolSearch failed for '$pattern'" }
            SymbolFormatter.formatError("Internal error searching symbols: ${e.message}", retry = false)
        }
    }

    /**
     * `psi_find_references` — find all references to a symbol.
     *
     * Resolves the symbol via [PsiShortNamesCache], disambiguates by [file]
     * if provided, then uses [ReferencesSearch.search] with a [Processor] +
     * `ensureActive()` for cancellation cooperation.
     */
    suspend fun runFindReferences(symbol: String, file: String?, scope: String, limit: Int): String {
        val guard = preGuard(scope, file)
        if (guard != null) return guard
        val scopeResult = parseScope(scope)
        val effectiveLimit = limit.coerceIn(1, MAX_REFERENCE_RESULTS)

        return try {
            withTimeout(TOOL_TIMEOUT_FIND_REFERENCES_MS) {
                val ctx = currentCoroutineContext()
                readAction {
                    if (project.isDisposed) return@readAction SymbolFormatter.formatError("Project closed", retry = false)
                    val resolved = resolveSymbol(symbol, file, scopeResult.scope)
                    if (resolved.isEmpty()) {
                        return@readAction SymbolFormatter.formatError(
                            "No symbol matching '$symbol' found" + (file?.let { " in $it" } ?: ""),
                            retry = false,
                        )
                    }
                    if (resolved.size > 1) {
                        return@readAction SymbolFormatter.formatCandidates(resolved.map { buildSymbolInfo(it) })
                    }
                    val target = resolved.first()

                    val refs = mutableListOf<ReferenceInfo>()
                    var total = 0
                    var truncated = false
                    ReferencesSearch.search(target, scopeResult.scope).forEach(Processor { ref ->
                        ctx.ensureActive()
                        total++
                        if (refs.size < effectiveLimit) {
                            refs.add(buildReferenceInfo(ref))
                        } else {
                            // We have enough results. Set truncated and stop
                            // iterating to avoid O(N) traversal of all references
                            // just to compute an exact total count.
                            truncated = true
                            return@Processor false
                        }
                        true
                    })

                    SymbolFormatter.formatReferences(refs, truncated, total)
                }
            }
        } catch (e: TimeoutCancellationException) {
            SymbolFormatter.formatError("find_references timed out", retry = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] runFindReferences failed for '$symbol'" }
            SymbolFormatter.formatError("Internal error searching references: ${e.message}", retry = false)
        }
    }

    /**
     * `psi_file_structure` — get file members with signatures (no bodies).
     *
     * Resolves the file via [LocalFileSystem], traverses top-level classes,
     * and extracts signatures (using the Kotlin Analysis API for Kotlin
     * inferred types, with guarded fallback to `<inferred>`).
     */
    suspend fun runFileStructure(file: String): String {
        val guard = preGuard(scope = null, file = file)
        if (guard != null) return guard

        return try {
            withTimeout(TOOL_TIMEOUT_DEFAULT_MS) {
                readAction {
                    if (project.isDisposed) return@readAction SymbolFormatter.formatError("Project closed", retry = false)
                    // preGuard already validated the path against the project root.
                    // Resolve relative paths against the project base directory and
                    // canonicalize for VirtualFile lookup.
                    val resolvedFile = if (java.io.File(file).isAbsolute) file
                        else project.basePath?.let { java.io.File(it, file).path } ?: file
                    val canonical = AttachmentPathValidator.canonicalizeOrReject(resolvedFile)
                        ?: return@readAction SymbolFormatter.formatError("Invalid path: $file", retry = false)
                    val vfile = LocalFileSystem.getInstance().findFileByIoFile(File(canonical))
                        ?: return@readAction SymbolFormatter.formatError("File not found: $file", retry = false)
                    val psiFile = PsiManager.getInstance(project).findFile(vfile)
                        ?: return@readAction SymbolFormatter.formatError("Could not load PSI for: $file", retry = false)

                    val language = when {
                        kotlinPluginAvailable && psiFile is KtFile -> "Kotlin"
                        psiFile is PsiClassOwner -> "Java"
                        else -> psiFile.language.displayName
                    }
                    val relPath = relativePath(vfile)

                    val classes = mutableListOf<ClassStructure>()
                    // Top-level classes only (parent is the file).
                    val topClasses = PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)
                        .filter { it.parent is PsiFile || it.parent is PsiClassOwner }
                    for (cls in topClasses) {
                        classes.add(buildClassStructure(cls))
                    }
                    // Kotlin top-level classes/objects.
                    if (kotlinPluginAvailable && psiFile is KtFile) {
                        val ktClasses = PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)
                            .filter { it.parent is KtFile }
                        for (ktCls in ktClasses) {
                            if (classes.none { it.name == ktCls.name }) {
                                classes.add(buildKtClassStructure(ktCls))
                            }
                        }
                        val ktObjects = PsiTreeUtil.findChildrenOfType(psiFile, KtObjectDeclaration::class.java)
                            .filter { it.parent is KtFile }
                        for (obj in ktObjects) {
                            if (classes.none { it.name == obj.name }) {
                                classes.add(buildKtObjectStructure(obj))
                            }
                        }
                    }

                    SymbolFormatter.formatFileStructure(FileStructure(relPath, language, classes))
                }
            }
        } catch (e: TimeoutCancellationException) {
            SymbolFormatter.formatError("File structure read timed out", retry = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] runFileStructure failed for '$file'" }
            SymbolFormatter.formatError("Internal error reading file structure: ${e.message}", retry = false)
        }
    }

    /**
     * `psi_call_hierarchy` — caller or callee tree for a method.
     *
     * For "callers": [ReferencesSearch.search] + enclosing method resolution.
     * For "callees": [PsiTreeUtil.findChildrenOfType] for call expressions +
     * resolve target method. Recurses to [depth] with cycle detection.
     */
    suspend fun runCallHierarchy(
        symbol: String,
        file: String?,
        direction: String,
        depth: Int,
        limit: Int,
        scope: String,
    ): String {
        val guard = preGuard(scope, file)
        if (guard != null) return guard
        val scopeResult = parseScope(scope)
        val effectiveDepth = depth.coerceIn(1, MAX_CALL_HIERARCHY_DEPTH)
        val effectiveLimit = limit.coerceIn(1, MAX_CALL_HIERARCHY_NODES_PER_LEVEL)

        return try {
            withTimeout(TOOL_TIMEOUT_DEFAULT_MS) {
                val ctx = currentCoroutineContext()
                readAction {
                    if (project.isDisposed) return@readAction SymbolFormatter.formatError("Project closed", retry = false)
                    val resolved = resolveSymbol(symbol, file, scopeResult.scope)
                    if (resolved.isEmpty()) {
                        return@readAction SymbolFormatter.formatError(
                            "No symbol matching '$symbol' found" + (file?.let { " in $it" } ?: ""),
                            retry = false,
                        )
                    }
                    if (resolved.size > 1) {
                        return@readAction SymbolFormatter.formatCandidates(resolved.map { buildSymbolInfo(it) })
                    }
                    val target = resolved.first()
                    // Call hierarchy requires a method (PsiMethod or KtNamedFunction).
                    val targetElement: PsiElement = when {
                        target is PsiMethod -> target
                        kotlinPluginAvailable && target is KtNamedFunction -> target
                        else -> return@readAction SymbolFormatter.formatError(
                            "Symbol '$symbol' is not a method; call hierarchy requires a method",
                            retry = false,
                        )
                    }

                    val visited = mutableSetOf<String>()
                    val root = when (direction.lowercase()) {
                        "callees" -> buildCalleeTree(targetElement, scopeResult.scope, effectiveDepth, effectiveLimit, visited, ctx)
                        else -> buildCallerTree(targetElement, scopeResult.scope, effectiveDepth, effectiveLimit, visited, ctx)
                    }
                    SymbolFormatter.formatCallHierarchy(root)
                }
            }
        } catch (e: TimeoutCancellationException) {
            SymbolFormatter.formatError("Call hierarchy timed out", retry = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] runCallHierarchy failed for '$symbol'" }
            SymbolFormatter.formatError("Internal error building call hierarchy: ${e.message}", retry = false)
        }
    }

    /**
     * `psi_impact_analysis` — blast radius of changing a symbol.
     *
     * Finds direct references via [ReferencesSearch], plus overrides (methods)
     * or inheritors (classes). If [depth] > 1, recurses (BFS) with a hard
     * 60s time budget via [withTimeout]. On timeout, returns partial results
     * with [RiskLevel.UNKNOWN].
     */
    suspend fun runImpactAnalysis(symbol: String, file: String?, depth: Int, limit: Int, scope: String): String {
        val guard = preGuard(scope, file)
        if (guard != null) return guard
        val scopeResult = parseScope(scope)
        val effectiveDepth = depth.coerceIn(1, MAX_IMPACT_DEPTH)
        val effectiveLimit = limit.coerceIn(1, MAX_IMPACT_RESULTS)

        return try {
            withTimeout(TOOL_TIMEOUT_IMPACT_ANALYSIS_MS) {
                val ctx = currentCoroutineContext()
                readAction {
                    if (project.isDisposed) return@readAction SymbolFormatter.formatError("Project closed", retry = false)
                    val resolved = resolveSymbol(symbol, file, scopeResult.scope)
                    if (resolved.isEmpty()) {
                        return@readAction SymbolFormatter.formatError(
                            "No symbol matching '$symbol' found" + (file?.let { " in $it" } ?: ""),
                            retry = false,
                        )
                    }
                    if (resolved.size > 1) {
                        return@readAction SymbolFormatter.formatCandidates(resolved.map { buildSymbolInfo(it) })
                    }
                    val target = resolved.first()

                    val affected = mutableListOf<AffectedSymbol>()
                    val affectedFiles = mutableSetOf<String>()
                    val visited = mutableSetOf<String>()
                    val touchesPublic = isPublicApiElement(target)

                    // BFS queue: (element, depth)
                    val queue = ArrayDeque<Pair<PsiElement, Int>>()
                    queue.addLast(target to 1)
                    visited.add(elementKey(target))

                    while (queue.isNotEmpty() && affected.size < effectiveLimit) {
                        ctx.ensureActive()
                        val (elem, curDepth) = queue.removeFirst()
                        if (curDepth > effectiveDepth) break

                        // Direct references.
                        ReferencesSearch.search(elem, scopeResult.scope).forEach(Processor { ref ->
                            ctx.ensureActive()
                            val refElem = ref.element
                            val key = elementKey(refElem)
                            if (visited.add(key)) {
                                val info = buildAffectedSymbol(refElem, curDepth, "references")
                                if (affected.size < effectiveLimit) {
                                    affected.add(info)
                                    affectedFiles.add(info.file)
                                }
                                if (curDepth < effectiveDepth && queue.size < MAX_IMPACT_QUEUE_SIZE) {
                                    queue.addLast(Pair(refElem, curDepth + 1))
                                }
                            }
                            // Stop iteration once we've hit the result or queue limit.
                            affected.size < effectiveLimit && queue.size < MAX_IMPACT_QUEUE_SIZE
                        })

                        // Method overrides.
                        if (elem is PsiMethod) {
                            val supers = elem.findSuperMethods()
                            for (sup in supers) {
                                val key = elementKey(sup)
                                if (visited.add(key)) {
                                    val info = buildAffectedSymbol(sup, curDepth, "overrides")
                                    if (affected.size < effectiveLimit) {
                                        affected.add(info)
                                        affectedFiles.add(info.file)
                                    }
                                    if (curDepth < effectiveDepth && queue.size < MAX_IMPACT_QUEUE_SIZE) {
                                        queue.addLast(sup to curDepth + 1)
                                    }
                                }
                            }
                        }

                        // Class inheritors.
                        if (elem is PsiClass) {
                            ClassInheritorsSearch.search(elem, scopeResult.scope, true).forEach(Processor { inheritor ->
                                ctx.ensureActive()
                                val key = elementKey(inheritor)
                                if (visited.add(key)) {
                                    val info = buildAffectedSymbol(inheritor, curDepth, "inherits")
                                    if (affected.size < effectiveLimit) {
                                        affected.add(info)
                                        affectedFiles.add(info.file)
                                    }
                                    if (curDepth < effectiveDepth && queue.size < MAX_IMPACT_QUEUE_SIZE) {
                                        queue.addLast(inheritor to curDepth + 1)
                                    }
                                }
                                affected.size < effectiveLimit && queue.size < MAX_IMPACT_QUEUE_SIZE
                            })
                        }

                        ctx.reportToolActivity("Impact analysis depth $curDepth: ${affected.size} affected so far")
                    }

                    val risk = RiskScorer.scoreImpact(affected, touchesPublic)
                    val result = ImpactResult(
                        symbol = symbol,
                        affectedFiles = affectedFiles.toList(),
                        affectedSymbols = affected,
                        riskLevel = risk,
                        summary = "Impact analysis for '$symbol': ${affected.size} affected symbols across ${affectedFiles.size} files. Risk level: ${risk.name}.",
                        totalAffected = affected.size,
                    )
                    SymbolFormatter.formatImpact(result)
                }
            }
        } catch (e: TimeoutCancellationException) {
            SymbolFormatter.formatError(
                "Impact analysis timed out; partial results unavailable. Re-run with depth=1 for direct impact only.",
                retry = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] runImpactAnalysis failed for '$symbol'" }
            SymbolFormatter.formatError("Internal error analyzing impact: ${e.message}", retry = false)
        }
    }

    /**
     * `psi_repo_map` — importance-ranked symbol index.
     *
     * Checks the [RepoMapCacheService] first. If the cache is fresh, returns
     * cached entries (up to [limit]). If cold start (no cache), returns a
     * retryable "cache warming" message and triggers a background rebuild.
     */
    suspend fun runRepoMap(limit: Int, scope: String): String {
        val guard = preGuard(scope)
        if (guard != null) return guard
        val scopeResult = parseScope(scope)
        val effectiveLimit = limit.coerceIn(1, MAX_REPO_MAP_RESULTS)
        val cacheService = RepoMapCacheService.getInstance(project)

        // Serve from cache if fresh.
        val cached = cacheService.cache
        if (cacheService.isCacheFresh() && cached != null) {
            val entries = cached.entries.take(effectiveLimit)
            return SymbolFormatter.formatRepoMap(entries)
        }

        // Cold start: no cache → rebuild synchronously (bounded by 15s timeout
        // in computeRepoMap). The TDD spec says "return retryable message and
        // trigger background rebuild", but we don't have a project-scoped
        // coroutine scope here. The pre-warm activity handles the common case;
        // if the cache is still cold when a tool call arrives, we rebuild
        // synchronously so the agent gets results on the first call.
        return try {
            rebuildRepoMap(scopeResult.scope, effectiveLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] runRepoMap cold-start rebuild failed" }
            SymbolFormatter.formatError("Cache warming, retry in 10s", retry = true)
        }
    }

    /**
     * Pre-warm the repo map cache (called by [RepoMapPreWarmActivity] on
     * project open). Fire-and-forget; does not return a result.
     */
    suspend fun warmRepoMap() {
        val cacheService = RepoMapCacheService.getInstance(project)
        // Rely solely on tryLock() for mutual exclusion. The `rebuilding` flag is
        // advisory only and can be stale (see RepoMapCacheService KDoc). Checking
        // it before tryLock() can skip valid rebuild opportunities.
        if (!cacheService.rebuildMutex.tryLock()) return
        try {
            cacheService.rebuilding = true
            val scopeResult = parseScope("project")
            val entries = computeRepoMap(scopeResult.scope, MAX_REPO_MAP_RESULTS)
            cacheService.cache = RepoMapCacheService.RepoMapCache(entries, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] warmRepoMap failed" }
        } finally {
            cacheService.rebuilding = false
            cacheService.rebuildMutex.unlock()
        }
    }

    /**
     * Rebuild the repo map cache and return formatted entries.
     */
    private suspend fun rebuildRepoMap(scope: GlobalSearchScope, limit: Int): String {
        val cacheService = RepoMapCacheService.getInstance(project)
        return cacheService.rebuildMutex.withLock {
            cacheService.rebuilding = true
            try {
                val entries = computeRepoMap(scope, limit)
                cacheService.cache = RepoMapCacheService.RepoMapCache(entries, System.currentTimeMillis())
                SymbolFormatter.formatRepoMap(entries)
            } finally {
                cacheService.rebuilding = false
            }
        }
    }

    /**
     * Compute the repo map: sample top [REPO_MAP_SAMPLE_SIZE] class names,
     * estimate importance via reference count (with [PsiSearchHelper] guard),
     * normalize log-scaled, sort by importance descending.
     *
     * Hard 15s time budget via [withTimeout].
     */
    private suspend fun computeRepoMap(scope: GlobalSearchScope, limit: Int): List<RepoMapEntry> =
        withTimeout(REPO_MAP_COMPUTATION_TIMEOUT_MS) {
            val ctx = currentCoroutineContext()
            readAction {
                if (project.isDisposed) return@readAction emptyList()
                val cache = PsiShortNamesCache.getInstance(project)
                val searchHelper = PsiSearchHelper.getInstance(project)

                // 1. Collect candidate class names via StubIndex.
                val names = mutableSetOf<String>()
                // Use PsiShortNamesCache.getAllClassNames() — simpler than StubIndex
                // and avoids type inference issues with StubIndexKey.createIndexKey.
                ctx.ensureActive()
                names.addAll(cache.getAllClassNames().toList())

                // 2. Sample top N alphabetically.
                val sampled = names.sorted().take(REPO_MAP_SAMPLE_SIZE)

                // 3. For each name, estimate importance via reference count.
                //    Per TDD §4.7.3: GUARD via PsiSearchHelper.isCheapEnoughToSearch
                //    (ZERO → 0, TOO_MANY → 0.1), then COUNT via ReferencesSearch
                //    with early termination at 100. The isCheapEnoughToSearch
                //    signature varies across platform versions, so we wrap it
                //    defensively and fall back to direct counting.
                data class Counted(val entry: RepoMapEntry, val count: Int, val needsNormalization: Boolean)
                val counted = mutableListOf<Counted>()
                for (name in sampled) {
                    ctx.ensureActive()
                    val classes = cache.getClassesByName(name, scope)
                    if (classes.isEmpty()) continue
                    val cls = classes.first()

                    // GUARD: isCheapEnoughToSearch (3-arg overload: name, scope, file).
                    val cost: PsiSearchHelper.SearchCostResult? = try {
                        searchHelper.isCheapEnoughToSearch(name, scope, null)
                    } catch (_: Exception) {
                        null
                    }

                    val importance: Double
                    val refCount: Int
                    val needsNormalization: Boolean
                    when (cost) {
                        PsiSearchHelper.SearchCostResult.ZERO_OCCURRENCES -> {
                            importance = 0.0
                            refCount = 0
                            needsNormalization = false
                        }
                        PsiSearchHelper.SearchCostResult.TOO_MANY_OCCURRENCES -> {
                            importance = 0.1
                            refCount = 100
                            needsNormalization = false
                        }
                        else -> {
                            // COUNT: ReferencesSearch with early termination at 100.
                            var c = 0
                            ReferencesSearch.search(cls, scope).forEach(Processor { _ ->
                                ctx.ensureActive()
                                c++
                                c < 100
                            })
                            refCount = c
                            importance = 0.0 // placeholder, will be normalized
                            needsNormalization = true
                        }
                    }
                    val relPath = relativePath(cls.containingFile.virtualFile)
                    val line = lineNumber(cls)
                    val kind = symbolKindOf(cls)
                    val entry = RepoMapEntry(name, kind, relPath, line, refCount, importance)
                    counted.add(Counted(entry, refCount, needsNormalization))
                }

                // 4. Normalize reference counts (log-scaled).
                val maxCount = counted.maxOfOrNull { it.count } ?: 0
                val normalized = counted.map { c ->
                    if (c.needsNormalization) {
                        val imp = if (maxCount > 0) ln((c.count + 1).toDouble()) / ln((maxCount + 1).toDouble()) else 0.0
                        c.entry.copy(importance = imp)
                    } else {
                        c.entry
                    }
                }

                // 5. Sort by importance descending, apply limit.
                ctx.reportToolActivity("RepoMap: computed ${normalized.size} entries")
                normalized.sortedByDescending { it.importance }.take(limit)
            }
        }

    // ------------------------------------------------------------------
    // Pre-guard: disabled / dumb / scope:all / path traversal / disposal
    // ------------------------------------------------------------------

    /**
     * Runs the standard pre-guard checks shared by all `run*` methods.
     * Returns a non-null error string if a guard fails, or null if all checks
     * pass (caller should proceed).
     *
     * @param scope the scope string (null for [runFileStructure] which has no scope).
     * @param file the file path (null if no file param). When non-null, the
     *   path is canonicalized and validated against the project root.
     */
    private fun preGuard(scope: String?, file: String? = null): String? {
        // 1. Disabled check.
        if (!OpenCodePsiToolsSettingsState.getInstance().psiToolsEnabled) {
            return SymbolFormatter.formatError(
                "PSI code intelligence tools are disabled. Enable in Settings → Tools → Sigil → PSI Tools.",
                retry = false,
            )
        }
        // 2. DumbService check.
        if (DumbService.isDumb(project)) {
            return SymbolFormatter.formatError("Indexing in progress, try again shortly", retry = true)
        }
        // 3. scope:all rejection.
        if (scope != null && scope.equals("all", ignoreCase = true)) {
            if (!OpenCodePsiToolsSettingsState.getInstance().allowScopeAll) {
                logger.info { "[ACP] scope=all requested; rejected (setting disabled)" }
                return SymbolFormatter.formatError(
                    "scope: \"all\" requires user opt-in via Settings → Tools → Sigil → PSI Tools. Request rejected — no results returned. Enable the setting or retry with scope: \"project\".",
                    retry = false,
                )
            }
        }
        // 4. Path traversal guard (if file param present).
        if (file != null) {
            // Resolve relative paths against the project base directory.
            val resolvedFile = if (java.io.File(file).isAbsolute) file
                else project.basePath?.let { java.io.File(it, file).path } ?: file
            val canonical = AttachmentPathValidator.canonicalizeOrReject(resolvedFile)
                ?: return SymbolFormatter.formatError("Invalid path: $file", retry = false)
            val projectBase = project.basePath?.let { AttachmentPathValidator.canonicalizeOrReject(it) }
            if (!AttachmentPathValidator.isInsideProject(canonical, projectBase)) {
                return SymbolFormatter.formatError(
                    "File outside project scope: $file",
                    retry = false,
                )
            }
        }
        // 5. Project disposal check.
        if (project.isDisposed) {
            return SymbolFormatter.formatError("Project closed", retry = false)
        }
        return null
    }

    // ------------------------------------------------------------------
    // Symbol resolution
    // ------------------------------------------------------------------

    /**
     * Resolve a symbol name to a list of candidate [PsiElement]s.
     * If [file] is provided, filters by containing file path.
     * Returns empty list if no match, multiple if ambiguous.
     */
    private fun resolveSymbol(name: String, file: String?, scope: GlobalSearchScope): List<PsiElement> {
        val cache = PsiShortNamesCache.getInstance(project)
        val candidates = mutableListOf<PsiElement>()

        candidates.addAll(cache.getMethodsByName(name, scope).toList())
        candidates.addAll(cache.getClassesByName(name, scope).toList())
        candidates.addAll(cache.getFieldsByName(name, scope).toList())

        val filtered = if (file != null) {
            val canonicalFile = AttachmentPathValidator.canonicalizeOrReject(file)
            candidates.filter { el ->
                val vfile = el.containingFile?.virtualFile
                if (vfile == null || canonicalFile == null) return@filter false
                val elCanonical = AttachmentPathValidator.canonicalizeOrReject(vfile.path) ?: return@filter false
                elCanonical == canonicalFile
            }
        } else {
            candidates
        }
        return filtered
    }

    // ------------------------------------------------------------------
    // Symbol info builders
    // ------------------------------------------------------------------

    private fun buildSymbolInfo(element: PsiElement): SymbolInfo {
        val vfile = element.containingFile?.virtualFile
        val file = vfile?.let { relativePath(it) } ?: "<unknown>"
        val line = lineNumber(element)
        val kind = symbolKindOf(element)
        val name = elementName(element) ?: "<anonymous>"
        val signature = extractSignature(element)
        val qualifiedName = extractQualifiedName(element)
        return SymbolInfo(name, kind, file, line, signature, qualifiedName)
    }

    private fun buildReferenceInfo(ref: PsiReference): ReferenceInfo {
        val element = ref.element
        val vfile = element.containingFile?.virtualFile
        val file = vfile?.let { relativePath(it) } ?: "<unknown>"
        val line = lineNumber(element)
        val column = (element.textOffset - lineStartOffset(element)) + 1
        val enclosingSymbol = findEnclosingSymbol(element)
        val text = lineText(element)
        return ReferenceInfo(file, line, column, enclosingSymbol, text)
    }

    private fun buildAffectedSymbol(element: PsiElement, depth: Int, relationship: String): AffectedSymbol {
        val vfile = element.containingFile?.virtualFile
        val file = vfile?.let { relativePath(it) } ?: "<unknown>"
        val line = lineNumber(element)
        val kind = symbolKindOf(element)
        val name = elementName(element) ?: "<anonymous>"
        return AffectedSymbol(name, kind, file, line, depth, relationship)
    }

    private fun buildClassStructure(cls: PsiClass): ClassStructure {
        val kind = symbolKindOf(cls)
        val fields = cls.fields.map { buildMemberInfo(it) }
        val methods = cls.methods.map { buildMemberInfo(it) }
        val nested = cls.innerClasses.map { buildClassStructure(it) }
        return ClassStructure(cls.name ?: "<anonymous>", kind, fields, methods, nested)
    }

    private fun buildKtClassStructure(cls: KtClass): ClassStructure {
        val kind = symbolKindOf(cls)
        val fields = cls.getDeclarations().filterIsInstance<KtProperty>().map { buildKtMemberInfo(it) }
        val methods = cls.getDeclarations().filterIsInstance<KtNamedFunction>().map { buildKtMemberInfo(it) }
        val nested = cls.getDeclarations().filterIsInstance<KtClass>().map { buildKtClassStructure(it) }
        return ClassStructure(cls.name ?: "<anonymous>", kind, fields, methods, nested)
    }

    private fun buildKtObjectStructure(obj: KtObjectDeclaration): ClassStructure {
        val kind = if (obj.isCompanion()) SymbolKind.COMPANION_OBJECT else SymbolKind.OBJECT
        val fields = obj.getDeclarations().filterIsInstance<KtProperty>().map { buildKtMemberInfo(it) }
        val methods = obj.getDeclarations().filterIsInstance<KtNamedFunction>().map { buildKtMemberInfo(it) }
        val nested = obj.getDeclarations().filterIsInstance<KtClass>().map { buildKtClassStructure(it) }
        return ClassStructure(obj.name ?: "<anonymous>", kind, fields, methods, nested)
    }

    private fun buildMemberInfo(method: PsiMethod): MemberInfo {
        val modifiers = extractModifiers(method)
        val sig = extractSignature(method) ?: method.name
        return MemberInfo(method.name, sig, modifiers)
    }

    private fun buildMemberInfo(field: PsiField): MemberInfo {
        val modifiers = extractModifiers(field)
        val sig = extractSignature(field) ?: field.name
        return MemberInfo(field.name, sig, modifiers)
    }

    private fun buildKtMemberInfo(fn: KtNamedFunction): MemberInfo {
        val modifiers = extractKtModifiers(fn)
        val sig = extractKtSignature(fn)
        return MemberInfo(fn.name ?: "<anonymous>", sig, modifiers)
    }

    private fun buildKtMemberInfo(prop: KtProperty): MemberInfo {
        val modifiers = extractKtModifiers(prop)
        val sig = extractKtSignature(prop)
        return MemberInfo(prop.name ?: "<anonymous>", sig, modifiers)
    }

    // ------------------------------------------------------------------
    // Call hierarchy tree builders
    // ------------------------------------------------------------------

    private fun buildCallerTree(
        target: PsiElement,
        scope: GlobalSearchScope,
        depth: Int,
        limit: Int,
        visited: MutableSet<String>,
        ctx: kotlin.coroutines.CoroutineContext,
    ): CallHierarchyNode {
        val name = elementName(target) ?: "<anonymous>"
        val vfile = target.containingFile?.virtualFile
        val file = vfile?.let { relativePath(it) } ?: "<unknown>"
        val line = lineNumber(target)
        val kind = symbolKindOf(target)
        val key = elementKey(target)
        visited.add(key)

        val children = mutableListOf<CallHierarchyNode>()
        if (depth <= 0) return CallHierarchyNode(name, kind, file, line, children)

        ReferencesSearch.search(target, scope).forEach(Processor { ref ->
            ctx.ensureActive()
            val caller = findEnclosingMethod(ref.element) ?: return@Processor true
            val callerKey = elementKey(caller)
            if (visited.add(callerKey)) {
                if (children.size < limit) {
                    children.add(buildCallerTree(caller, scope, depth - 1, limit, visited, ctx))
                }
            }
            children.size < limit
        })
        ctx.reportToolActivity("Callers depth $depth: found ${children.size} methods")
        return CallHierarchyNode(name, kind, file, line, children)
    }

    private fun buildCalleeTree(
        target: PsiElement,
        scope: GlobalSearchScope,
        depth: Int,
        limit: Int,
        visited: MutableSet<String>,
        ctx: kotlin.coroutines.CoroutineContext,
    ): CallHierarchyNode {
        val name = elementName(target) ?: "<anonymous>"
        val vfile = target.containingFile?.virtualFile
        val file = vfile?.let { relativePath(it) } ?: "<unknown>"
        val line = lineNumber(target)
        val kind = symbolKindOf(target)
        val key = elementKey(target)
        visited.add(key)

        val children = mutableListOf<CallHierarchyNode>()
        if (depth <= 0) return CallHierarchyNode(name, kind, file, line, children)

        // Find call expressions in the method body.
        val callExprs = PsiTreeUtil.findChildrenOfType(target, com.intellij.psi.PsiCallExpression::class.java)
        for (call in callExprs) {
            ctx.ensureActive()
            val resolved = when {
                call is com.intellij.psi.PsiMethodCallExpression -> call.resolveMethod()
                kotlinPluginAvailable && call is KtCallExpression -> resolveKtCall(call)
                else -> null
            } ?: continue
            val calleeKey = elementKey(resolved)
            if (visited.add(calleeKey)) {
                if (children.size < limit) {
                    children.add(buildCalleeTree(resolved, scope, depth - 1, limit, visited, ctx))
                }
            }
            if (children.size >= limit) break
        }
        ctx.reportToolActivity("Callees depth $depth: found ${children.size} methods")
        return CallHierarchyNode(name, kind, file, line, children)
    }

    private fun resolveKtCall(call: KtCallExpression): PsiElement? {
        // Defense-in-depth: only the Kotlin-plugin path calls this, but guard
        // anyway in case of future refactoring.
        if (!kotlinPluginAvailable) return null
        // Resolve the call expression via its reference, which respects
        // overload resolution at the call site. This is better than the
        // previous name-based PsiShortNamesCache lookup which picked the
        // first method with a matching name regardless of parameter types.
        return try {
            call.calleeExpression?.references?.firstOrNull()?.resolve()
        } catch (_: Exception) {
            null
        }
    }
    // ------------------------------------------------------------------
    // Signature / modifier extraction
    // ------------------------------------------------------------------

    private fun extractSignature(element: PsiElement): String? {
        return when {
            element is PsiMethod -> extractSignature(element)
            element is PsiClass -> extractSignature(element)
            element is PsiField -> extractSignature(element)
            kotlinPluginAvailable && element is KtNamedFunction -> extractKtSignature(element)
            kotlinPluginAvailable && element is KtClass -> extractKtSignature(element)
            kotlinPluginAvailable && element is KtProperty -> extractKtSignature(element)
            else -> null
        }
    }

    private fun extractSignature(method: PsiMethod): String {
        val mods = extractModifiers(method).joinToString(" ")
        val ret = method.returnType?.canonicalText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") { p ->
            "${p.type.canonicalText} ${p.name}"
        }
        return listOf(mods, ret, "${method.name}($params)").filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun extractSignature(cls: PsiClass): String {
        val mods = extractModifiers(cls).joinToString(" ")
        val typeParams = cls.typeParameters.takeIf { it.isNotEmpty() }
            ?.let { "<${it.joinToString(", ") { tp -> tp.name ?: "?" }}>" } ?: ""
        val supers = cls.extendsList?.referencedTypes
            ?.takeIf { it.isNotEmpty() }
            ?.let { " extends ${it.joinToString(", ") { t -> t.canonicalText }}" }
            ?: ""
        return listOf(mods, "${cls.kindString()} ${cls.name}$typeParams", supers.trim())
            .filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun extractSignature(field: PsiField): String {
        val mods = extractModifiers(field).joinToString(" ")
        val type = field.type.canonicalText
        return listOf(mods, type, field.name).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun extractKtSignature(fn: KtNamedFunction): String {
        if (!kotlinPluginAvailable) return "<inferred>"
        val mods = extractKtModifiers(fn)
        val params = fn.valueParameters.joinToString(", ") { p ->
            val typeRef = p.typeReference?.text ?: "<inferred>"
            "${p.name}: $typeRef"
        }
        // Use the explicitly-declared return type if present. For inferred
        // types, attempt the Kotlin Analysis API; fall back to <inferred>
        // if analyze() throws for this element (TDD §12.2 rule 6).
        val retType = fn.typeReference?.text ?: try {
            @OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
            analyze(fn) {
                fn.returnType.render(position = org.jetbrains.kotlin.types.Variance.INVARIANT)
            }
        } catch (_: Exception) {
            "<inferred>"
        }
        val hasSuspend = mods.contains("suspend")
        val modsStr = mods.filter { it != "suspend" }.joinToString(" ")
        val funKw = if (hasSuspend) "suspend fun" else "fun"
        return listOf(modsStr, "$funKw ${fn.name}($params): $retType")
            .filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun extractKtSignature(cls: KtClass): String {
        if (!kotlinPluginAvailable) return "<inferred>"
        val mods = extractKtModifiers(cls).joinToString(" ")
        val typeParams = cls.typeParameterList?.text ?: ""
        val supers = cls.superTypeListEntries.joinToString(", ") { it.text }
        val superStr = if (supers.isNotBlank()) " : $supers" else ""
        val kind = if (cls.isInterface()) "interface" else "class"
        return listOf(mods, "$kind ${cls.name}$typeParams$superStr").filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun extractKtSignature(prop: KtProperty): String {
        if (!kotlinPluginAvailable) return "<inferred>"
        val mods = extractKtModifiers(prop).joinToString(" ")
        val typeRef = prop.typeReference?.text ?: try {
            @OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
            analyze(prop) {
                prop.returnType.render(position = org.jetbrains.kotlin.types.Variance.INVARIANT)
            }
        } catch (_: Exception) {
            "<inferred>"
        }
        val valKw = if (prop.isVar()) "var" else "val"
        return listOf(mods, "$valKw ${prop.name}: $typeRef").filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun extractModifiers(owner: PsiModifierListOwner): List<String> {
        val ml = owner.modifierList ?: return emptyList()
        val mods = mutableListOf<String>()
        for (kw in PsiModifier.MODIFIERS) {
            if (ml.hasModifierProperty(kw)) mods.add(kw)
        }
        return mods
    }

    private fun extractKtModifiers(el: KtModifierListOwner): List<String> {
        if (!kotlinPluginAvailable) return emptyList()
        val ml = el.modifierList ?: return emptyList()
        // Iterate over the modifier keyword tokens in the list and collect
        // their text. KtModifierList has no enum-based API; we read the
        // AST nodes directly.
        val mods = mutableListOf<String>()
        // Check common Kotlin modifiers via hasModifier + KtTokens keyword
        // tokens. This avoids depending on a KtModifier enum that may not
        // exist in all Kotlin plugin versions.
        val modifierKeywords = listOf(
            "public" to KtTokens.PUBLIC_KEYWORD,
            "private" to KtTokens.PRIVATE_KEYWORD,
            "protected" to KtTokens.PROTECTED_KEYWORD,
            "internal" to KtTokens.INTERNAL_KEYWORD,
            "sealed" to KtTokens.SEALED_KEYWORD,
            "abstract" to KtTokens.ABSTRACT_KEYWORD,
            "open" to KtTokens.OPEN_KEYWORD,
            "final" to KtTokens.FINAL_KEYWORD,
            "override" to KtTokens.OVERRIDE_KEYWORD,
            "lateinit" to KtTokens.LATEINIT_KEYWORD,
            "companion" to KtTokens.COMPANION_KEYWORD,
            "inline" to KtTokens.INLINE_KEYWORD,
            "suspend" to KtTokens.SUSPEND_KEYWORD,
            "tailrec" to KtTokens.TAILREC_KEYWORD,
            "external" to KtTokens.EXTERNAL_KEYWORD,
            "infix" to KtTokens.INFIX_KEYWORD,
            "operator" to KtTokens.OPERATOR_KEYWORD,
            "data" to KtTokens.DATA_KEYWORD,
            "const" to KtTokens.CONST_KEYWORD,
            "fun" to KtTokens.FUN_KEYWORD,
            "value" to KtTokens.VALUE_KEYWORD,
            "annotation" to KtTokens.ANNOTATION_KEYWORD,
            "inner" to KtTokens.INNER_KEYWORD,
        )
        for ((label, token) in modifierKeywords) {
            if (ml.hasModifier(token)) mods.add(label)
        }
        return mods
    }

    // ------------------------------------------------------------------
    // Public API detection
    // ------------------------------------------------------------------

    private fun isPublicApiElement(element: PsiElement): Boolean {
        return when {
            kotlinPluginAvailable && element is KtModifierListOwner -> {
                // Only treat as public-API-eligible if this is a declaration that
                // can actually have visibility modifiers (class, function, property).
                // Local variables, parameters, etc. also implement KtModifierListOwner
                // but are not part of the public API surface.
                if (element !is KtClass && element !is KtNamedFunction && element !is KtProperty && element !is KtObjectDeclaration) {
                    return false
                }
                val ml = element.modifierList
                when {
                    ml == null -> true // Kotlin defaults to public
                    ml.hasModifier(KtTokens.PRIVATE_KEYWORD) -> false
                    ml.hasModifier(KtTokens.INTERNAL_KEYWORD) -> false
                    ml.hasModifier(KtTokens.PUBLIC_KEYWORD) -> true
                    ml.hasModifier(KtTokens.PROTECTED_KEYWORD) -> true
                    else -> true // no visibility modifier = public in Kotlin
                }
            }
            element is PsiModifierListOwner -> {
                val ml = element.modifierList
                ml?.hasModifierProperty(PsiModifier.PUBLIC) == true ||
                    ml?.hasModifierProperty(PsiModifier.PROTECTED) == true
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun PsiClass.kindString(): String = when {
        isInterface -> "interface"
        isEnum -> "enum"
        isAnnotationType -> "@interface"
        else -> "class"
    }

    private fun symbolKindOf(element: PsiElement): SymbolKind = when {
        kotlinPluginAvailable && element is KtClass -> when {
            element.isInterface() -> SymbolKind.INTERFACE
            element.isEnum() -> SymbolKind.ENUM
            element.isAnnotation() -> SymbolKind.ANNOTATION
            else -> SymbolKind.CLASS
        }
        kotlinPluginAvailable && element is KtObjectDeclaration -> if (element.isCompanion()) SymbolKind.COMPANION_OBJECT else SymbolKind.OBJECT
        kotlinPluginAvailable && element is KtNamedFunction -> SymbolKind.FUNCTION
        kotlinPluginAvailable && element is KtProperty -> SymbolKind.PROPERTY
        element is PsiClass -> when {
            element.isInterface -> SymbolKind.INTERFACE
            element.isEnum -> SymbolKind.ENUM
            element.isAnnotationType -> SymbolKind.ANNOTATION
            else -> SymbolKind.CLASS
        }
        element is PsiMethod -> if (element.isConstructor) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD
        element is PsiField -> SymbolKind.FIELD
        else -> SymbolKind.CLASS
    }

    private fun parseSymbolKind(kind: String): SymbolKind? = when (kind.lowercase()) {
        "class" -> SymbolKind.CLASS
        "method" -> SymbolKind.METHOD
        "field" -> SymbolKind.FIELD
        "function" -> SymbolKind.FUNCTION
        "property" -> SymbolKind.PROPERTY
        "interface" -> SymbolKind.INTERFACE
        "enum" -> SymbolKind.ENUM
        "object" -> SymbolKind.OBJECT
        "annotation" -> SymbolKind.ANNOTATION
        "constructor" -> SymbolKind.CONSTRUCTOR
        "package" -> SymbolKind.PACKAGE
        "type_alias" -> SymbolKind.TYPE_ALIAS
        "companion_object" -> SymbolKind.COMPANION_OBJECT
        else -> null
    }

    private fun elementName(element: PsiElement): String? = when {
        element is PsiClass -> element.name
        element is PsiMethod -> element.name
        element is PsiField -> element.name
        kotlinPluginAvailable && element is KtClass -> element.name
        kotlinPluginAvailable && element is KtObjectDeclaration -> element.name
        kotlinPluginAvailable && element is KtNamedFunction -> element.name
        kotlinPluginAvailable && element is KtProperty -> element.name
        else -> null
    }

    private fun extractQualifiedName(element: PsiElement): String? = when {
        element is PsiClass -> element.qualifiedName
        kotlinPluginAvailable && element is KtClass -> try { element.fqName?.asString() } catch (_: Exception) { null }
        else -> null
    }

    private fun findEnclosingSymbol(element: PsiElement): String? {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        if (method != null) return "${method.name}()"
        if (kotlinPluginAvailable) {
            val ktFn = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
            if (ktFn != null) return "${ktFn.name}()"
        }
        val cls = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
        if (cls != null) return cls.name
        if (kotlinPluginAvailable) {
            val ktCls = PsiTreeUtil.getParentOfType(element, KtClass::class.java, false)
            if (ktCls != null) return ktCls.name
        }
        return null
    }

    private fun findEnclosingMethod(element: PsiElement): PsiElement? {
        PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)?.let { return it }
        if (!kotlinPluginAvailable) return null
        return PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
    }

    private fun relativePath(vfile: VirtualFile?): String {
        if (vfile == null) return "<unknown>"
        val base = project.basePath ?: return vfile.path
        val baseFile = File(base)
        val vfileFile = File(vfile.path)
        return try {
            val rel = baseFile.toPath().relativize(vfileFile.toPath())
            rel.toString().replace(File.separatorChar, '/')
        } catch (_: Exception) {
            vfile.path
        }
    }

    private fun lineNumber(element: PsiElement): Int {
        val vfile = element.containingFile?.virtualFile ?: return 0
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
            ?: FileDocumentManager.getInstance().getDocument(vfile)
            ?: return 0
        return doc.getLineNumber(element.textOffset) + 1
    }

    private fun lineStartOffset(element: PsiElement): Int {
        val vfile = element.containingFile?.virtualFile ?: return 0
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
            ?: FileDocumentManager.getInstance().getDocument(vfile)
            ?: return 0
        val line = doc.getLineNumber(element.textOffset)
        return doc.getLineStartOffset(line)
    }

    private fun lineText(element: PsiElement): String {
        val vfile = element.containingFile?.virtualFile ?: return ""
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
            ?: FileDocumentManager.getInstance().getDocument(vfile)
            ?: return ""
        val line = doc.getLineNumber(element.textOffset)
        val start = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        return doc.text.substring(start, end).trim()
    }

    private fun elementKey(element: PsiElement): String {
        val vfile = element.containingFile?.virtualFile
        val path = vfile?.path ?: "<unknown>"
        val name = elementName(element) ?: element.javaClass.simpleName
        return "$path:${element.textOffset}:$name"
    }

    // ------------------------------------------------------------------
    // Pure-logic / scope helpers (fully implemented)
    // ------------------------------------------------------------------

    /**
     * Result of parsing a scope string into a [GlobalSearchScope].
     */
    data class ScopeResult(val scope: GlobalSearchScope, val isAllScope: Boolean)

    /**
     * Parse a scope string into a [GlobalSearchScope].
     */
    fun parseScope(scope: String): ScopeResult {
        return when {
            scope.equals("all", ignoreCase = true) ->
                ScopeResult(GlobalSearchScope.allScope(project), isAllScope = true)
            scope.startsWith("module:", ignoreCase = true) -> {
                val moduleName = scope.substringAfter(':').trim()
                val module = ModuleManager.getInstance(project).findModuleByName(moduleName)
                val moduleScope = module?.let { GlobalSearchScope.moduleScope(it) }
                    ?: GlobalSearchScope.projectScope(project)
                ScopeResult(moduleScope, isAllScope = false)
            }
            else -> ScopeResult(GlobalSearchScope.projectScope(project), isAllScope = false)
        }
    }

    /**
     * Format a PSI element's location as `"file:line"` (1-based line).
     */
    fun formatLocation(element: PsiElement): String {
        val vFile = element.containingFile?.virtualFile
        val path = vFile?.path ?: "<unknown>"
        val document = vFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0
        return "$path:$line"
    }

    companion object {
        /** True if the Kotlin plugin is installed and Kotlin PSI types are available.
         *  Checked via reflection to avoid NoClassDefFoundError on IDEs without Kotlin
         *  (e.g., Rider, PyCharm, WebStorm). All Kotlin-specific PSI code is guarded
         *  with this flag via short-circuit && to prevent class loading. */
        private val kotlinPluginAvailable: Boolean = try {
            Class.forName("org.jetbrains.kotlin.psi.KtFile")
            true
        } catch (_: Throwable) {
            false
        }

        fun <T> truncateResults(items: List<T>, limit: Int): Triple<List<T>, Boolean, Int> {
            val total = items.size
            if (total <= limit) return Triple(items, false, total)
            return Triple(items.take(limit), true, total)
        }

        fun enforceTokenBudget(json: String): String =
            SymbolFormatter.enforceTokenBudget(json)
    }
}

// ------------------------------------------------------------------
// Coroutine helpers (file-private)
// ------------------------------------------------------------------
// (kotlinx.coroutines.sync.withLock is used directly — no custom helpers needed.)
