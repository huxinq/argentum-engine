package com.wingedsheep.ai.engine

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.state.GameState

/**
 * Result of simulating an action through the engine.
 */
sealed interface SimulationResult {
    val state: GameState
    val events: List<GameEvent>

    /** The rules engine reported that the Magic game ended. */
    data class GameEnded(
        override val state: GameState,
        override val events: List<GameEvent>
    ) : SimulationResult {
        init {
            require(state.gameOver) { "A game-ended simulation result requires an ended game" }
        }
    }

    /**
     * The simulator's declared automatic policy has no next action to take.
     * This is an evaluation boundary, not a claim that the Magic game ended.
     */
    data class Quiet(
        override val state: GameState,
        override val events: List<GameEvent>
    ) : SimulationResult {
        init {
            require(!state.gameOver) { "An ended game must use GameEnded" }
        }
    }

    /** The action paused mid-resolution — a decision is required. */
    data class NeedsDecision(
        override val state: GameState,
        val decision: PendingDecision,
        override val events: List<GameEvent>
    ) : SimulationResult

    /** The action was illegal or failed validation. */
    data class Illegal(
        override val state: GameState,
        override val events: List<GameEvent>,
        val reason: String
    ) : SimulationResult

    /**
     * Automatic resolution reached its declared transition limit while another automatic
     * transition remained. The retained state is unfinished and must not be scored as quiet or
     * interpreted as a game outcome.
     */
    data class StoppedAtLimit(
        override val state: GameState,
        override val events: List<GameEvent>,
        val automaticTransitions: Int,
        val limit: Int,
    ) : SimulationResult {
        init {
            require(!state.gameOver) { "An ended game must use GameEnded" }
            require(limit > 0)
            require(automaticTransitions >= limit)
        }
    }
}

/** Why an authoritative action appears in an exhaustive simulation trace. */
enum class SimulationActionOrigin { SUBMITTED, AUTO_PASS, AUTO_DECISION }

/** One raw ActionProcessor invocation, including its exact state boundary and emitted events. */
data class SimulationTraceStep(
    val origin: SimulationActionOrigin,
    val action: GameAction,
    val beforeState: GameState,
    val afterState: GameState,
    val events: List<GameEvent>,
    val rejectionReason: String?,
) {
    val accepted: Boolean get() = rejectionReason == null
}

data class TracedSimulationResult(
    val result: SimulationResult,
    val steps: List<SimulationTraceStep>,
)

/** Raised when a caller attempts to use an unfinished automatic-resolution state as a result. */
class AutomaticResolutionLimitException(
    val stopped: SimulationResult.StoppedAtLimit,
    context: String,
) : IllegalStateException(
    "$context stopped after ${stopped.automaticTransitions}/${stopped.limit} automatic transitions; " +
        "the retained state is unfinished",
)

fun SimulationResult.requireNoAutomaticResolutionStop(context: String): SimulationResult {
    if (this is SimulationResult.StoppedAtLimit) {
        throw AutomaticResolutionLimitException(this, context)
    }
    return this
}
