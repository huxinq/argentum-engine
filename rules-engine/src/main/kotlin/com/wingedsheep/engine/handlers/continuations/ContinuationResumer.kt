package com.wingedsheep.engine.handlers.continuations

import com.wingedsheep.engine.core.AnswerContinuation
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.state.GameState
import kotlin.reflect.KClass

/**
 * Interface for continuation resumers.
 *
 * Each concrete continuation frame type has a corresponding resumer that handles
 * resumption logic after a player decision. This follows the Strategy pattern,
 * allowing continuation handling to be modular and testable.
 *
 * @param T The specific continuation frame type this resumer handles
 */
interface ContinuationResumer<T : AnswerContinuation> {
    /**
     * The continuation frame type this resumer handles.
     * Used for automatic registration in the resumer registry.
     */
    val frameType: KClass<T>

    /**
     * Resume execution after a decision is submitted.
     *
     * @param state The game state after popping the continuation
     * @param continuation The continuation frame describing what to resume
     * @param question The question paired with this answer in the consumed suspension
     * @param response The player's decision response
     * @param checkForMore Callback to check for more continuations on the stack
     * @return The execution result with new state and events
     */
    fun resume(
        state: GameState,
        continuation: T,
        question: PendingDecision,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult
}

/**
 * Factory function to create a [ContinuationResumer] from a method reference.
 *
 * Reduces boilerplate when implementing [ContinuationResumerModule.resumers].
 */
fun <T : AnswerContinuation> resumer(
    type: KClass<T>,
    handler: (GameState, T, DecisionResponse, CheckForMore) -> ExecutionResult
): ContinuationResumer<T> = object : ContinuationResumer<T> {
    override val frameType: KClass<T> = type
    override fun resume(
        state: GameState,
        continuation: T,
        question: PendingDecision,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult = handler(state, continuation, response, checkForMore)
}

/** A resumer that also needs the paired question's represented choices. */
fun <T : AnswerContinuation> questionResumer(
    type: KClass<T>,
    handler: (GameState, T, PendingDecision, DecisionResponse, CheckForMore) -> ExecutionResult
): ContinuationResumer<T> = object : ContinuationResumer<T> {
    override val frameType: KClass<T> = type
    override fun resume(
        state: GameState,
        continuation: T,
        question: PendingDecision,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult = handler(state, continuation, question, response, checkForMore)
}
