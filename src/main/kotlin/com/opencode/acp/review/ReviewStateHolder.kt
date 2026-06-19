package com.opencode.acp.review

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the current [ReviewIndex] in a [MutableStateFlow].
 *
 * - Synchronous, thread-safe reads via [value] — used by
 *   [ReviewCommentLineMarkerProvider] on EDT (no dispatcher switch, no
 *   await). StateFlow.value is atomic.
 * - Reactive updates via [state] (a read-only StateFlow) for collectors
 *   that need to react to changes (Review tab UI, editor highlight
 *   re-application).
 */
class ReviewStateHolder(initial: ReviewIndex = ReviewIndex()) {
    private val _state = MutableStateFlow(initial)

    /** Read-only StateFlow for collectors. */
    val state: StateFlow<ReviewIndex> = _state.asStateFlow()

    /** Synchronous current-value read. Thread-safe. */
    val value: ReviewIndex get() = _state.value

    /** Atomically swap the current index. */
    fun set(index: ReviewIndex) { _state.value = index }
}
