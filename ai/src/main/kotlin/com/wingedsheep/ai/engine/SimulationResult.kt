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

    /** The action completed fully — no further input needed. */
    data class Terminal(
        override val state: GameState,
        override val events: List<GameEvent>
    ) : SimulationResult

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
