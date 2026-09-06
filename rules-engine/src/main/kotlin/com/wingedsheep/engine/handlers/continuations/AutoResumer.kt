package com.wingedsheep.engine.handlers.continuations

import com.wingedsheep.engine.core.AutomaticContinuation
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.state.GameState
import kotlin.reflect.KClass

/**
 * Interface for auto-resume handlers.
 *
 * Auto-resumers handle continuation frames that don't require player input
 * but need processing when found on the continuation stack after another
 * frame resolves. This follows the same Strategy pattern as [ContinuationResumer]
 * but for automatic (non-decision) continuations.
 *
 * @param T The specific continuation frame type this auto-resumer handles
 */
interface AutoResumer<T : AutomaticContinuation> {
    val frameType: KClass<T>

    /**
     * Whether this continuation can be auto-resumed in its current state.
     * Returning false means the frame is left on the stack (treated as no match).
     */
    fun canAutoResume(continuation: T): Boolean = true

    /**
     * Auto-resume execution for this continuation frame.
     *
     * @param state The game state after popping the continuation
     * @param continuation The continuation frame to process
     * @param events Accumulated events from prior processing
     * @param checkForMore Callback to recursively check for more continuations
     * @return The execution result
     */
    fun autoResume(
        state: GameState,
        continuation: T,
        events: List<GameEvent>,
        checkForMore: CheckForMore
    ): ExecutionResult
}

/**
 * Factory function to create an [AutoResumer] from lambdas.
 */
fun <T : AutomaticContinuation> autoResumer(
    type: KClass<T>,
    canResume: (T) -> Boolean = { true },
    handler: (GameState, T, List<GameEvent>, CheckForMore) -> ExecutionResult
): AutoResumer<T> = object : AutoResumer<T> {
    override val frameType: KClass<T> = type
    override fun canAutoResume(continuation: T): Boolean = canResume(continuation)
    override fun autoResume(
        state: GameState,
        continuation: T,
        events: List<GameEvent>,
        checkForMore: CheckForMore
    ): ExecutionResult = handler(state, continuation, events, checkForMore)
}

/**
 * Interface for grouping related auto-resumers into modules.
 */
interface AutoResumerModule {
    fun autoResumers(): List<AutoResumer<*>>
}
