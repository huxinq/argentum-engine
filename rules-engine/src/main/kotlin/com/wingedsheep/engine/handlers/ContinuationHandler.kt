package com.wingedsheep.engine.handlers

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.continuations.*
import com.wingedsheep.engine.state.GameState

/**
 * Handles resumption of execution after a player decision.
 *
 * The top suspension retains the player's question and the operation that consumes its answer.
 * This handler validates the response identity, consumes that suspension, and dispatches the
 * answer operation together with its question.
 *
 * Delegates to specialized resumer modules via the ContinuationResumerRegistry.
 */
class ContinuationHandler(
    private val services: EngineServices
) {

    private val effectRunner = EffectContinuationRunner(services.effectExecutorRegistry)

    private val registry = ContinuationResumerRegistry().apply {
        // Core engine resumers
        registerModule(EffectAndTriggerContinuationResumer(services, effectRunner))
        registerModule(MiscContinuationResumer(services, effectRunner))

        // Core engine auto-resumers
        registerAutoResumerModule(CoreAutoResumerModule(services, effectRunner))

        // Specialized resumer modules
        registerModule(CombatContinuationResumer(services))
        registerModule(CombatTaxContinuationResumer(services))
        registerModule(ColorChoiceContinuationResumer(services, effectRunner))
        val chainResumer = ChainSpellContinuationResumer(services)
        registerModule(chainResumer)
        registerAutoResumerModule(chainResumer)
        registerModule(CreatureTypeChoiceContinuationResumer(services))
        registerModule(TextReplacementContinuationResumer(services))
        registerModule(DrawReplacementContinuationResumer(services))
        registerModule(CardSpecificContinuationResumer(services))
        registerModule(DiscardAndDrawContinuationResumer(services))
        registerModule(StateBasedContinuationResumer(services))
        registerModule(SacrificeAndPayContinuationResumer(services))
        registerModule(CollectEvidenceContinuationResumer())
        registerModule(CostPaymentContinuationResumer(services))
        registerModule(ManaPaymentContinuationResumer(services))
        registerModule(LibraryAndZoneContinuationResumer(services))
        registerModule(GuessContinuationResumer(services))
        registerModule(ModalAndCloneContinuationResumer(services))
        registerModule(RoomDoorContinuationResumer(services))
        registerModule(CastModalContinuationResumer(services))
        registerModule(ModalTriggerContinuationResumer(services))
        registerModule(TokenContinuationResumer(services))
        registerModule(RingTemptContinuationResumer(services))
        registerModule(AmassContinuationResumer(services))
        val leylineResumer = LeylineContinuationResumer(services)
        registerModule(leylineResumer)
        registerAutoResumerModule(leylineResumer)
        registerModule(ActivateAbilityXCostContinuationResumer(services))
        registerModule(ActivateAbilityOpponentTargetResumer(services))

        // Replacement effect system
        val replacementResumer = ReplacementContinuationResumer(
            services.replacementEffectProcessor,
            services
        )
        registerModule(replacementResumer)
        registerAutoResumerModule(replacementResumer)
    }

    /**
     * Resume execution after a decision is submitted.
     *
     * @param state The game state containing the suspension being answered
     * @param response The player's decision response
     * @return The result of resuming execution
     */
    fun resume(state: GameState, response: DecisionResponse): ExecutionResult {
        val suspension = state.peekContinuation() as? Suspension
            ?: return ExecutionResult.error(state, "No suspension is awaiting an answer")
        if (suspension.question.id != response.decisionId) {
            return ExecutionResult.error(
                state,
                "Decision ID mismatch: expected ${suspension.question.id}, got ${response.decisionId}"
            )
        }

        val (_, stateAfterPop) = state.popContinuation()
        return registry.resume(stateAfterPop, suspension.answer, suspension.question, response, ::checkForMoreContinuations)
    }

    private fun checkForMoreContinuations(
        state: GameState,
        events: List<GameEvent>
    ): ExecutionResult {
        return registry.tryAutoResume(state, events, ::checkForMoreContinuations)
            ?: ExecutionResult.success(state, events)
    }
}
