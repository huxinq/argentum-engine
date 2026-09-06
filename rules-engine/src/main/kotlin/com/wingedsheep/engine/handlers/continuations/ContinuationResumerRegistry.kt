package com.wingedsheep.engine.handlers.continuations

import com.wingedsheep.engine.core.AnswerContinuation
import com.wingedsheep.engine.core.AutomaticContinuation
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.state.GameState
import kotlin.reflect.KClass

/**
 * Registry that maps continuation frame types to their resumers.
 *
 * This implements the Strategy pattern, allowing each continuation frame type
 * to have its own dedicated resumer while providing a unified dispatch mechanism.
 *
 * The registry uses a map-based dispatch system with modular sub-registries
 * for each category of continuations, reducing merge conflicts and enabling
 * dynamic resumer registration.
 */
class ContinuationResumerRegistry {
    private val resumers = mutableMapOf<KClass<out AnswerContinuation>, ContinuationResumer<*>>()
    private val autoResumers = mutableMapOf<KClass<out AutomaticContinuation>, AutoResumer<*>>()

    /**
     * Register all resumers from a module.
     */
    fun registerModule(module: ContinuationResumerModule) {
        module.resumers().forEach { resumer ->
            resumers[resumer.frameType] = resumer
        }
    }

    /**
     * Register all auto-resumers from a module.
     */
    fun registerAutoResumerModule(module: AutoResumerModule) {
        module.autoResumers().forEach { autoResumer ->
            autoResumers[autoResumer.frameType] = autoResumer
        }
    }

    /**
     * Register a single resumer.
     */
    fun <T : AnswerContinuation> register(resumer: ContinuationResumer<T>) {
        resumers[resumer.frameType] = resumer
    }

    /**
     * Register a single auto-resumer.
     */
    fun <T : AutomaticContinuation> registerAutoResumer(autoResumer: AutoResumer<T>) {
        autoResumers[autoResumer.frameType] = autoResumer
    }

    /**
     * Resume a continuation using the appropriate resumer.
     *
     * @param state The game state after popping the continuation
     * @param continuation The continuation frame to resume
     * @param question The question paired with this answer in the consumed suspension
     * @param response The player's decision response
     * @param checkForMore Callback to check for more continuations on the stack
     * @return The execution result with new state and events
     */
    @Suppress("UNCHECKED_CAST")
    fun resume(
        state: GameState,
        continuation: AnswerContinuation,
        question: PendingDecision,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        val resumer = resumers[continuation::class] as? ContinuationResumer<AnswerContinuation>
            ?: return ExecutionResult.error(state, "No resumer registered for continuation type: ${continuation::class.simpleName}")
        return resumer.resume(state, continuation, question, response, checkForMore)
    }

    /**
     * Try to auto-resume the top continuation on the stack.
     *
     * Peeks at the stack top, finds a matching auto-resumer, checks [AutoResumer.canAutoResume],
     * pops the frame, and dispatches. Returns null if no matching auto-resumer or if
     * [canAutoResume] returns false.
     *
     * @param state The current game state (continuation still on stack)
     * @param events Accumulated events from prior processing
     * @param checkForMore Callback to recursively check for more continuations
     * @return The execution result, or null if no auto-resumer matched
     */
    @Suppress("UNCHECKED_CAST")
    fun tryAutoResume(
        state: GameState,
        events: List<GameEvent>,
        checkForMore: CheckForMore
    ): ExecutionResult? {
        val top = state.peekContinuation() as? AutomaticContinuation ?: return null
        val resumer = autoResumers[top::class] as? AutoResumer<AutomaticContinuation> ?: return null
        if (!resumer.canAutoResume(top)) return null

        val (_, stateAfterPop) = state.popContinuation()
        return resumer.autoResume(stateAfterPop, top, events, checkForMore)
    }

    /**
     * Returns the number of registered resumers.
     */
    fun resumerCount(): Int = resumers.size
}
