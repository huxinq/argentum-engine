package com.wingedsheep.engine.core

import com.wingedsheep.engine.mechanics.StateBasedActionChecker
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId

/**
 * Shared helper for the repeated "apply SBAs then check game over" pattern
 * used across multiple step handlers.
 */
object StepActionHelper {

    /**
     * Apply state-based actions and check if the game is over.
     * If paused (e.g., for a simultaneous destruction decision), returns the paused result.
     * If the game is over, nulls out priority.
     * Otherwise returns the post-SBA state with events appended.
     */
    fun applySbasAndCheckGameOver(
        state: GameState,
        activePlayer: EntityId,
        sbaChecker: StateBasedActionChecker,
        priorEvents: MutableList<GameEvent>
    ): ExecutionResult {
        val sbaResult = sbaChecker.checkAndApply(state)
        if (sbaResult.isPaused) {
            return ExecutionResult.propagatePause(
                sbaResult.state,
                priorEvents + sbaResult.events
            )
        }
        var newState = sbaResult.newState
        priorEvents.addAll(sbaResult.events)

        if (newState.gameOver) {
            newState = newState.copy(priorityPlayerId = null)
        }

        return ExecutionResult.success(newState, priorEvents)
    }
}
