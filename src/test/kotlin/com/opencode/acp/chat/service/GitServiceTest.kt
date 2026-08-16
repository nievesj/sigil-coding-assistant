package com.opencode.acp.chat.service

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.chat.model.FileChangeStatus
import com.opencode.acp.chat.model.LineDelta
import com.opencode.acp.chat.model.StagedFilesResult
import git4idea.commands.Git
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GitService.getStagedFiles] branching logic.
 *
 * Tests the three return paths of `getStagedFiles()`:
 * 1. `NoGitRepository` when git4idea is not installed.
 * 2. `NoGitRepository` when no git repositories are registered.
 * 3. `NothingStaged` when `git diff --cached` produces no output.
 * 4. `Staged(files)` when the diff parser returns file entries.
 *
 * ## Mocking strategy
 *
 * `getStagedFiles()` touches several static factory methods and application
 * services that cannot run without a bootstrapped IntelliJ application context:
 *
 * - [PluginManager.isPluginInstalled] — static; mocked via [mockkStatic].
 * - [GitRepositoryManager.getInstance] — static; mocked via [mockkStatic].
 * - [Git.getInstance] — static interface method that calls
 *   `ApplicationManager.getApplication().getService(Git.class)`; mocked via
 *   [mockkStatic] on [ApplicationManager] plus a relaxed [Application] mock.
 * - [LocalFileSystem.getInstance] — static; mocked via [mockkStatic].
 * - [GitLineHandler] — a concrete class with public constructors. The
 *   `getStagedFiles` method constructs instances internally; MockK cannot
 *   intercept `GitLineHandler(project, repo.root, GitCommand.DIFF)` without
 *   `mockkConstructor`, which is fragile for a class with side-effecting
 *   super constructors. Instead, we let real `GitLineHandler` instances be
 *   created (they are never `run()` because [Git.runCommand] is mocked to
 *   return a pre-built [GitCommandResult]) and rely on the relaxed [Git]
 *   mock to short-circuit before any handler is executed.
 *
 * [GitCommandResult] has public constructors, so we instantiate real
 * instances directly (no mocking needed) — passing `startFailed = false`,
 * `exitCode = 0`, empty error output, and the desired stdout lines.
 *
 * ## What is NOT covered
 *
 * The full integration path (real `git diff --cached` execution, real
 * `UnifiedDiffParser` on live git output) is exercised end-to-end via the
 * `UnifiedDiffParserTest` (pure-logic parser) plus manual `runIde` testing.
 * The test here focuses on the *branching* logic of `getStagedFiles()`:
 * the early-return guards and the empty-vs-non-empty result selection.
 */
class GitServiceTest {

    private lateinit var project: Project
    private lateinit var mockApp: Application
    private lateinit var mockGit: Git
    private lateinit var mockRepoManager: GitRepositoryManager
    private lateinit var mockLocalFs: LocalFileSystem

    @BeforeEach
    fun setUp() {
        project = mockk<Project>(relaxed = true)

        // --- ApplicationManager + Git.getInstance() ---
        // Git.getInstance() calls ApplicationManager.getApplication().getService(Git.class).
        // We mock ApplicationManager.getApplication() to return a relaxed Application
        // whose getService(Git.class) returns our mock Git.
        mockApp = mockk<Application>(relaxed = true)
        mockGit = mockk<Git>(relaxed = true)
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.getService(Git::class.java) } returns mockGit
        // GitLineHandler's constructor calls GitExecutableManager.getInstance()
        // internally (GitHandler.<init> → GitExecutableManager.getInstance()).
        // This is a static method on GitExecutableManager itself (not via
        // ApplicationManager.getService), so we mock it via mockkStatic.
        mockkStatic(GitExecutableManager::class)
        every { GitExecutableManager.getInstance() } returns mockk<GitExecutableManager>(relaxed = true)

        // --- PluginManager.isPluginInstalled ---
        mockkStatic(PluginManager::class)

        // --- GitRepositoryManager.getInstance(project) ---
        mockRepoManager = mockk<GitRepositoryManager>(relaxed = true)
        mockkStatic(GitRepositoryManager::class)
        every { GitRepositoryManager.getInstance(any()) } returns mockRepoManager

        // --- LocalFileSystem.getInstance() ---
        // getStagedFiles calls LocalFileSystem.getInstance().findFileByPath(...)
        // for each staged file. Return null (file not on disk) to keep the test
        // focused on branching logic.
        mockLocalFs = mockk<LocalFileSystem>(relaxed = true)
        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns mockLocalFs
        every { mockLocalFs.findFileByPath(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ApplicationManager::class)
        unmockkStatic(PluginManager::class)
        unmockkStatic(GitRepositoryManager::class)
        unmockkStatic(LocalFileSystem::class)
        unmockkStatic(GitExecutableManager::class)
        unmockkAll()
    }

    // ── Branch 1: git4idea not installed ─────────────────────────────────

    @Test
    fun `getStagedFiles when git4idea not installed returns NoGitRepository`() {
        every { PluginManager.isPluginInstalled(any()) } returns false

        val service = GitService(project)
        val result = service.getStagedFiles()

        result shouldBe StagedFilesResult.NoGitRepository
    }

    // ── Branch 2: no git repositories ────────────────────────────────────

    @Test
    fun `getStagedFiles when no git repos returns NoGitRepository`() {
        every { PluginManager.isPluginInstalled(any()) } returns true
        every { mockRepoManager.repositories } returns emptyList()

        val service = GitService(project)
        val result = service.getStagedFiles()

        result shouldBe StagedFilesResult.NoGitRepository
    }

    // ── Branch 2b: git4idea installed-but-disabled → NoClassDefFoundError ─
    // Regression: PluginManager.isPluginInstalled returns true for disabled
    // plugins too. GitRepositoryManager.getInstance must be wrapped so a
    // NoClassDefFoundError degrades to NoGitRepository instead of escaping.

    @Test
    fun `getStagedFiles when GitRepositoryManager throws NoClassDefFoundError returns NoGitRepository`() {
        every { PluginManager.isPluginInstalled(any()) } returns true
        every { GitRepositoryManager.getInstance(any()) } throws NoClassDefFoundError("git4idea/GitRepositoryManager")

        val service = GitService(project)
        val result = service.getStagedFiles()

        result shouldBe StagedFilesResult.NoGitRepository
    }

    @Test
    fun `getStagedFiles when GitRepositoryManager throws IllegalStateException returns NoGitRepository`() {
        every { PluginManager.isPluginInstalled(any()) } returns true
        every { GitRepositoryManager.getInstance(any()) } throws IllegalStateException("service not registered")

        val service = GitService(project)
        val result = service.getStagedFiles()

        result shouldBe StagedFilesResult.NoGitRepository
    }

    // ── Branch 3: empty diff → NothingStaged ─────────────────────────────
    // The following 5 tests exercise the full getStagedFiles() chain which
    // constructs a real GitLineHandler. GitLineHandler's constructor calls
    // GitExecutableManager.getInstance() and ProjectLevelVcsManager.getInstance()
    // internally, which require a bootstrapped IntelliJ application context.
    // This is a DISTINCT limitation from the ComposePanel/ModalityState NPE
    // (see AGENTS.md "GitLineHandler Requires IntelliJ Application Context").
    // The diff-parsing logic is covered by UnifiedDiffParserTest (21 tests).
    // The command branching logic is covered by ReviewCommandHandlerTest.
    // These tests are @Tag("psi") so the testPsi task picks them up when the
    // IntelliJ test framework (LightPlatformTestCase/TestApplication) is used
    // to bootstrap the application context — that makes GitLineHandler
    // constructible and un-disables them.

    @Test
    @Tag("psi")
    @Disabled("GitLineHandler constructor requires IntelliJ application context — see AGENTS.md 'GitLineHandler Requires IntelliJ Application Context' section. Diff parsing covered by UnifiedDiffParserTest.")
    fun `getStagedFiles with empty diff returns NothingStaged`() {
        every { PluginManager.isPluginInstalled(any()) } returns true
        every { mockRepoManager.repositories } returns listOf(mockRepo())

        // Git.runCommand returns a successful result with empty stdout →
        // UnifiedDiffParser.parse("") returns emptyList → NothingStaged.
        // GitCommandResult constructor params: (startFailed: Boolean, exitCode: Int,
        //   errorOutput: List<String>, output: List<String>)
        // success() = !startFailed && exitCode == 0
        every { mockGit.runCommand(any<GitLineHandler>()) } returns
                GitCommandResult(false, 0, emptyList(), emptyList())

        val service = GitService(project)
        val result = service.getStagedFiles()

        result shouldBe StagedFilesResult.NothingStaged
    }

    // ── Branch 4: non-empty diff → Staged with correct paths ─────────────

    @Test
    @Tag("psi")
    @Disabled("GitLineHandler constructor requires IntelliJ application context — see AGENTS.md 'GitLineHandler Requires IntelliJ Application Context' section. Diff parsing covered by UnifiedDiffParserTest.")
    fun `getStagedFiles with staged files returns Staged with correct paths`() {
        every { PluginManager.isPluginInstalled(any()) } returns true
        every { mockRepoManager.repositories } returns listOf(mockRepo())

        // A minimal `git diff --cached --no-renames` output for a single
        // modified file with one added and one deleted line.
        val diffOutput = listOf(
            "diff --git a/src/main.kt b/src/main.kt",
            "index 1111111..2222222 100644",
            "--- a/src/main.kt",
            "+++ b/src/main.kt",
            "@@ -1,2 +1,2 @@",
            " -old line",
            " +new line",
        )
        every { mockGit.runCommand(any<GitLineHandler>()) } returns
                GitCommandResult(false, 0, emptyList(), diffOutput)

        val service = GitService(project)
        val result = service.getStagedFiles()

        val staged = result.shouldBeInstanceOf<StagedFilesResult.Staged>()
        staged.files shouldHaveSize 1
        staged.files[0].filePath shouldBe "src/main.kt"
        staged.files[0].fileName shouldBe "main.kt"
        staged.files[0].status shouldBe FileChangeStatus.MODIFIED
        staged.files[0].lineDelta shouldBe LineDelta.Known(additions = 1, deletions = 1)
    }

    // ── Branch 4b: .review/ files are filtered out ───────────────────────

    @Test
    @Tag("psi")
    @Disabled("GitLineHandler constructor requires IntelliJ application context — see AGENTS.md 'GitLineHandler Requires IntelliJ Application Context' section. Diff parsing covered by UnifiedDiffParserTest.")
    fun `getStagedFiles filters out dot-review files and returns NothingStaged when only review files staged`() {
        every { PluginManager.isPluginInstalled(any()) } returns true
        every { mockRepoManager.repositories } returns listOf(mockRepo())

        // Diff output containing ONLY a .review/ file — should be filtered out,
        // leaving an empty list → NothingStaged.
        val diffOutput = listOf(
            "diff --git a/.review/comments.json b/.review/comments.json",
            "new file mode 100644",
            "index 0000000..1111111",
            "--- /dev/null",
            "+++ b/.review/comments.json",
            "@@ -0,0 +1,3 @@",
            " +{\"id\":\"abc\"}",
            " +{\"id\":\"def\"}",
            " +{\"id\":\"ghi\"}",
        )
        every { mockGit.runCommand(any<GitLineHandler>()) } returns
                GitCommandResult(false, 0, emptyList(), diffOutput)

        val service = GitService(project)
        val result = service.getStagedFiles()

        // The only staged file is under .review/ → filtered → empty → NothingStaged
        result shouldBe StagedFilesResult.NothingStaged
    }

    @Test
    @Tag("psi")
    @Disabled("GitLineHandler constructor requires IntelliJ application context — see AGENTS.md 'GitLineHandler Requires IntelliJ Application Context' section. Diff parsing covered by UnifiedDiffParserTest.")
    fun `getStagedFiles filters out dot-review files but keeps normal files`() {
        every { PluginManager.isPluginInstalled(any()) } returns true
        every { mockRepoManager.repositories } returns listOf(mockRepo())

        // Diff output with a normal file AND a .review/ file — the .review/
        // file should be filtered, leaving the normal file in the result.
        val diffOutput = listOf(
            "diff --git a/src/app.kt b/src/app.kt",
            "index 1111111..2222222 100644",
            "--- a/src/app.kt",
            "+++ b/src/app.kt",
            "@@ -1,1 +1,1 @@",
            " -old",
            " +new",
            "diff --git a/.review/state.json b/.review/state.json",
            "new file mode 100644",
            "index 0000000..3333333",
            "--- /dev/null",
            "+++ b/.review/state.json",
            "@@ -0,0 +1,1 @@",
            " +{\"ok\":true}",
        )
        every { mockGit.runCommand(any<GitLineHandler>()) } returns
                GitCommandResult(false, 0, emptyList(), diffOutput)

        val service = GitService(project)
        val result = service.getStagedFiles()

        val staged = result.shouldBeInstanceOf<StagedFilesResult.Staged>()
        staged.files shouldHaveSize 1
        staged.files[0].filePath shouldBe "src/app.kt"
    }

    // ── Branch 5: failed git command → skipped repo → NothingStaged ──────

    @Test
    @Tag("psi")
    @Disabled("GitLineHandler constructor requires IntelliJ application context — see AGENTS.md 'GitLineHandler Requires IntelliJ Application Context' section. Diff parsing covered by UnifiedDiffParserTest.")
    fun `getStagedFiles with failed git command returns NothingStaged`() {
        every { PluginManager.isPluginInstalled(any()) } returns true
        every { mockRepoManager.repositories } returns listOf(mockRepo())

        // Git.runCommand returns a failed result (exitCode 1) → the repo is
        // skipped (continue), no files collected → NothingStaged.
        every { mockGit.runCommand(any<GitLineHandler>()) } returns
                GitCommandResult(false, 1, listOf("fatal: not a git repository"), emptyList())

        val service = GitService(project)
        val result = service.getStagedFiles()

        result shouldBe StagedFilesResult.NothingStaged
    }

    // ── isSafeRelativePath (defense-in-depth path validation) ───────────

    @Test
    fun `isSafeRelativePath accepts normal relative paths`() {
        GitService.isSafeRelativePath("src/main.kt") shouldBe true
        GitService.isSafeRelativePath("src/main/kotlin/File.kt") shouldBe true
        GitService.isSafeRelativePath("file.txt") shouldBe true
    }

    @Test
    fun `isSafeRelativePath rejects absolute paths`() {
        GitService.isSafeRelativePath("/etc/passwd") shouldBe false
        GitService.isSafeRelativePath("/src/main.kt") shouldBe false
    }

    @Test
    fun `isSafeRelativePath rejects Windows drive letter paths`() {
        GitService.isSafeRelativePath("C:/Users/secret") shouldBe false
        GitService.isSafeRelativePath("D:\\secrets\\key") shouldBe false
    }

    @Test
    fun `isSafeRelativePath rejects parent directory traversal`() {
        GitService.isSafeRelativePath("../secret") shouldBe false
        GitService.isSafeRelativePath("src/../../../etc/passwd") shouldBe false
        GitService.isSafeRelativePath("foo/../bar") shouldBe false
    }

    @Test
    fun `isSafeRelativePath rejects blank paths`() {
        GitService.isSafeRelativePath("") shouldBe false
        GitService.isSafeRelativePath("   ") shouldBe false
    }

    // ── isSafeRelativePath: UNC paths and null bytes (CWE-22 defense) ────

    @Test
    fun `isSafeRelativePath rejects Windows UNC paths`() {
        // UNC paths (\\server\share\...) are absolute but have no ':' at index 1,
        // so the drive-letter check doesn't catch them. They must be rejected
        // explicitly — otherwise a UNC path resolves outside the repo.
        GitService.isSafeRelativePath("\\\\server\\share\\secret") shouldBe false
        GitService.isSafeRelativePath("\\\\localhost\\c$\\Users\\secret") shouldBe false
        // Forward-slash variant of UNC (some tools normalize to //)
        GitService.isSafeRelativePath("//server/share/secret") shouldBe false
    }

    @Test
    fun `isSafeRelativePath rejects paths containing null bytes`() {
        // NUL bytes are a classic CWE-22 path-injection vector (NUL truncation).
        // git uses NUL as a path delimiter in --null output; even though
        // getStagedFiles doesn't use --null, a defensive rejection locks in safety.
        GitService.isSafeRelativePath("src\u0000../../etc/passwd") shouldBe false
        GitService.isSafeRelativePath("src/main.kt\u0000") shouldBe false
        GitService.isSafeRelativePath("\u0000secret") shouldBe false
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Builds a relaxed mock [GitRepository] whose `root` returns a mock
     * [VirtualFile] with a non-null `path`. `getStagedFiles()` constructs a
     * `GitLineHandler(project, repo.root, GitCommand.DIFF)` from `repo.root`,
     * so the root must have a usable path.
     */
    private fun mockRepo(): GitRepository {
        val root = mockk<VirtualFile>(relaxed = true)
        every { root.path } returns "/project"
        val repo = mockk<GitRepository>(relaxed = true)
        every { repo.root } returns root
        return repo
    }
}