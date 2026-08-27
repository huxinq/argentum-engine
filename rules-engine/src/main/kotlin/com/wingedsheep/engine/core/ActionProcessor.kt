package com.wingedsheep.engine.core

import com.wingedsheep.engine.handlers.actions.ActionHandlerRegistry
import com.wingedsheep.engine.handlers.actions.ability.AbilityModule
import com.wingedsheep.engine.handlers.actions.combat.CombatModule
import com.wingedsheep.engine.handlers.actions.decision.DecisionModule
import com.wingedsheep.engine.handlers.actions.land.LandModule
import com.wingedsheep.engine.handlers.actions.morph.MorphModule
import com.wingedsheep.engine.handlers.actions.mulligan.MulliganModule
import com.wingedsheep.engine.handlers.actions.priority.PriorityModule
import com.wingedsheep.engine.handlers.actions.room.RoomModule
import com.wingedsheep.engine.handlers.actions.special.SpecialActionsModule
import com.wingedsheep.engine.handlers.actions.spell.SpellModule
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.core.UndoPolicyComputer
import kotlin.reflect.KClass

/**
 * Wraps the result of [ActionProcessor.process] with an undo checkpoint policy.
 *
 * The engine computes the policy; the server follows it mechanically.
 */
data class ProcessedAction(
    val result: ExecutionResult,
    val undoPolicy: UndoCheckpointAction = UndoCheckpointAction.CLEAR
)

/**
 * The central action processor for the game engine.
 *
 * This is the main entry point for all game actions. It validates actions,
 * executes them against the game state, and returns the result.
 *
 * The processor is stateless - it's a pure function:
 * (GameState, GameAction) -> ProcessedAction(ExecutionResult, UndoCheckpointAction)
 *
 * Action handling is delegated to specialized handlers registered in the
 * ActionHandlerRegistry. This class serves as a thin facade that:
 * 1. Performs basic validation (game not over, player exists)
 * 2. Delegates to the appropriate handler via the registry
 */
class ActionProcessor(
    private val services: EngineServices,
    private val computeUndo: Boolean = true
) {
    /**
     * Backward-compatible constructor: wraps a CardRegistry in EngineServices.
     */
    constructor(cardRegistry: CardRegistry) : this(EngineServices(cardRegistry))

    /**
     * Registry that maps action types to their handlers.
     */
    private val registry = ActionHandlerRegistry().apply {
        registerModule(SpecialActionsModule(services))
        registerModule(PriorityModule(services))
        registerModule(LandModule(services))
        registerModule(MulliganModule(services))
        registerModule(CombatModule(services))
        registerModule(AbilityModule(services))
        registerModule(MorphModule(services))
        registerModule(RoomModule(services))
        registerModule(SpellModule(services))
        registerModule(DecisionModule(services))
    }

    /** Exact production dispatch topology, exposed internally for invariant verification. */
    internal fun registeredActionTypes(): Set<KClass<out GameAction>> = registry.registeredActionTypes()

    /**
     * Process a game action and return the result.
     *
     * @param state The current game state
     * @param action The action to process
     * @return ExecutionResult with new state, events, and any error or pending decision
     */
    fun process(state: GameState, action: GameAction): ProcessedAction {
        // Basic validation that applies to all actions
        val basicError = validateBasics(state, action)
        if (basicError != null) {
            return ProcessedAction(ExecutionResult.error(state, basicError))
        }

        // Delegate to the handler registry for action-specific validation
        val validationError = registry.validate(state, action)
        if (validationError != null) {
            return ProcessedAction(ExecutionResult.error(state, validationError))
        }

        // Execute the action, then update which revealed/returned cards opponents may see
        // only when the handler accepted it. Rejected events must not reach this event-driven
        // tracker.
        val executed = registry.execute(state, action)
        val tracked = if (executed.error == null) {
            com.wingedsheep.engine.mechanics.RevealedInHandTracker.applyAfterAction(executed)
        } else {
            executed
        }

        // Finish behind one transaction boundary. Handlers and continuation resumers may
        // compose several immutable intermediate states before a later step rejects the
        // operation. An error means the action did not happen, so none of those states,
        // events, decisions, or processing metadata may escape to callers.
        val result = normalizeFailedAction(state, tracked)
        val undoPolicy = if (computeUndo) {
            UndoPolicyComputer.compute(action, state, result, services.cardRegistry)
        } else {
            UndoCheckpointAction.CLEAR
        }
        return ProcessedAction(result, undoPolicy)
    }

    /**
     * Basic validation that applies to all actions.
     */
    private fun validateBasics(state: GameState, action: GameAction): String? {
        // Check game is not over
        if (state.gameOver) {
            return "Game is already over"
        }

        // Check player exists
        if (!state.turnOrder.contains(action.playerId)) {
            return "Unknown player: ${action.playerId}"
        }

        return null
    }
}

/**
 * Enforce the public action contract at the single [ActionProcessor] boundary.
 *
 * Individual handlers remain pure and may build intermediate immutable states while an
 * operation is in progress. If any nested handler ultimately reports an error, the whole
 * operation is rejected: return the exact entry state and retain only the error message.
 */
internal fun normalizeFailedAction(
    originalState: GameState,
    result: ExecutionResult
): ExecutionResult = if (result.error == null) {
    result
} else {
    ExecutionResult.error(originalState, result.error)
}
