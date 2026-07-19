package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.CommandHistoryEntry
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the input command history — persistence, recall, and clearing.
 *
 * Extracted from [ChatViewModel] per TDD §4.2.3. Owns the `_commandHistory`
 * StateFlow and the persisted-settings write-back. The history is loaded from
 * [OpenCodeSettingsState.commandHistory] on construction and trimmed to the
 * configured [OpenCodeSettingsState.commandHistorySize] on each new entry.
 *
 * Threading: [recordCommand] and [clearCommandHistory] synchronize on a
 * dedicated lock (not the settings object) to avoid EDT blocking from
 * `synchronized(settings)`. The StateFlow reference assignment is atomic
 * (JLS §17.7), so EDT readers (settings panel) never see a partially-written
 * list — at worst a stale value, which the next StateFlow emission corrects.
 */
class CommandHistoryManager {

    private val logger = KotlinLogging.logger {}

    private val _commandHistory = MutableStateFlow<List<CommandHistoryEntry>>(emptyList())
    val commandHistory: StateFlow<List<CommandHistoryEntry>> = _commandHistory.asStateFlow()

    /** Lock for command history mutations — prevents EDT blocking from synchronized(settings). */
    private val commandHistoryLock = Any()

    /** Load persisted history from settings. Call once during ViewModel init. */
    fun loadFromSettings() {
        val settings = OpenCodeSettingsState.getInstance()
        _commandHistory.value = ArrayList(settings.commandHistory)
    }

    /**
     * Record a sent command (text + files) at the front of the history.
     *
     * Deduplication: if an identical entry (same text + same file lists) already
     * exists, it is moved to the front rather than duplicated. The history is
     * trimmed to [OpenCodeSettingsState.commandHistorySize] (clamped to 1..100)
     * and persisted to settings.
     */
    fun recordCommand(text: String, files: List<AttachedFile>) {
        if (text.isBlank() && files.isEmpty()) return
        val entry = CommandHistoryEntry(text = text, files = files)
        val settings = OpenCodeSettingsState.getInstance()
        val maxSize = settings.commandHistorySize.coerceIn(1, 100)
        // Synchronize on a dedicated lock to prevent lost-update race with clearCommandHistory
        // (which also writes settings.commandHistory from EDT). Using `settings` as the
        // monitor could block the EDT if a background coroutine holds it; the dedicated
        // lock avoids coupling EDT blocking to settings object monitor contention.
        //
        // Threading note: settings.commandHistory is a PersistentStateComponent field
        // written here from a background coroutine and read from EDT (settings panel).
        // This is safe because ArrayList reference assignment is atomic on the JVM
        // (reference writes are atomic per JLS §17.7). The EDT reader may see a stale
        // value but never a partially-written list. Using invokeLater for the write would
        // add EDT round-trip latency to every send — the reference-atomicity tradeoff is
        // intentional. Do NOT change to invokeLater without measuring the latency impact.
        synchronized(commandHistoryLock) {
            val current = _commandHistory.value.toMutableList()
            // Compute the new file lists once outside removeAll to avoid creating
            // 3 intermediate lists per existing-entry comparison (O(history×files)
            // → O(history+files) allocations).
            val names = files.map { it.name }
            val paths = files.map { it.path }
            val mimes = files.map { it.mime }
            current.removeAll { existing ->
                existing.text == text &&
                    existing.attachedFileNames == names &&
                    existing.attachedFilePaths == paths &&
                    existing.attachedFileMimes == mimes
            }
            current.add(0, entry)
            val trimmed = if (current.size > maxSize) current.take(maxSize) else current
            _commandHistory.value = trimmed
            settings.commandHistory = java.util.ArrayList(trimmed)
        }
    }

    /** Clear all command history (both the StateFlow and persisted settings). */
    fun clearCommandHistory() {
        val settings = OpenCodeSettingsState.getInstance()
        synchronized(commandHistoryLock) {
            _commandHistory.value = emptyList()
            settings.commandHistory = java.util.ArrayList()
        }
    }
}