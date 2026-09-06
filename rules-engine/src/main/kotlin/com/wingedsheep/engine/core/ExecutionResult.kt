package com.wingedsheep.engine.core

import com.wingedsheep.engine.state.GameState
import kotlinx.serialization.Serializable

/**
 * Result of executing a game action or engine step.
 *
 * The engine operates as a reentrant state machine. Every operation returns one of:
 * - **Success**: error == null && pendingDecision == null
 * - **PausedForDecision**: pendingDecision != null (needs player input)
 * - **Error**: error != null (action was rejected, state unchanged)
 *
 * Nested engine steps also use `ExecutionResult` while composing an action and may have built
 * intermediate immutable states before reporting an error. [ActionProcessor] is the public
 * transaction boundary: a top-level error exposes the exact input state and retains only the error
 * message, with no events, pending decision, or processed-trigger marker from the rejected attempt.
 *
 * Game-over is signaled via `state.gameOver` + a [GameEndedEvent] in `events`.
 */
@Serializable
data class ExecutionResult(
    val state: GameState,
    val events: List<GameEvent> = emptyList(),
    val error: String? = null,
    val pendingDecision: PendingDecision? = null,
    /**
     * `true` when the producing action handler already ran [TriggerDetector] over
     * [events] and put any resulting triggers on the stack itself. Callers resuming
     * a paused action (notably `SubmitDecisionHandler`) must skip detection on
     * [events] when this is set; otherwise battlefield triggers like Riku of Many
     * Paths would be duplicated on the stack — once by the handler, once by the
     * resumer running on the same `SpellCastEvent`.
     */
    val triggersAlreadyProcessed: Boolean = false
) {
    val isSuccess: Boolean get() = error == null && pendingDecision == null
    val isPaused: Boolean get() = pendingDecision != null

    /** Alias for state to indicate we're getting the resulting state after execution */
    val newState: GameState get() = state

    companion object {
        /**
         * Create a successful result with no events.
         */
        fun success(state: GameState): ExecutionResult =
            ExecutionResult(state)

        /**
         * Create a successful result with events.
         */
        fun success(state: GameState, events: List<GameEvent>): ExecutionResult =
            ExecutionResult(state, events)

        /**
         * Create an error result.
         */
        fun error(state: GameState, message: String): ExecutionResult =
            ExecutionResult(state, error = message)

        /**
         * Propagate an existing suspension through an outer execution layer. Never allocates or
         * installs a question; the state supplies the single authoritative pending decision.
         */
        fun propagatePause(state: GameState, events: List<GameEvent> = emptyList()): ExecutionResult =
            ExecutionResult(state, events, pendingDecision = requireNotNull(state.pendingDecision) {
                "A paused result must propagate an installed suspension"
            })
    }
}
