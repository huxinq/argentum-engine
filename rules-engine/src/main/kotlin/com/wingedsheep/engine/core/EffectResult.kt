package com.wingedsheep.engine.core

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.sdk.model.EntityId

/**
 * Result of executing an effect within the effect pipeline.
 *
 * Extends the core [ExecutionResult] fields with pipeline-internal data
 * ([updatedCollections], [updatedSubtypeGroups]) that composite executors
 * merge into [com.wingedsheep.engine.handlers.PipelineState] between
 * sub-effect steps. These fields never leave the effect execution subsystem.
 */
data class EffectResult(
    val state: GameState,
    val events: List<GameEvent> = emptyList(),
    val error: String? = null,
    val pendingDecision: PendingDecision? = null,
    /** Card collections produced by pipeline effects (GatherCards, SelectFromCollection, etc.) */
    val updatedCollections: Map<String, List<EntityId>> = emptyMap(),
    /** Subtype-group lists produced by pipeline effects (GatherSubtypes, etc.) */
    val updatedSubtypeGroups: Map<String, List<Set<String>>> = emptyMap(),
    /** Named numeric values produced by pipeline effects (StoreNumber, etc.). */
    val updatedStoredNumbers: Map<String, Int> = emptyMap(),
    /** Named string values produced by pipeline effects (StoreCardName, etc.). */
    val updatedChosenValues: Map<String, String> = emptyMap(),
    /**
     * LKI snapshots of permanents sacrificed by a sacrifice *effect* during this step.
     * Composite executors merge these into [EffectContext.sacrificedPermanents] so a
     * following sibling effect (e.g. "gain life equal to its toughness") can read the
     * sacrificed permanent's characteristics as it last existed (Rule 608.2h). Mirrors
     * the cost-sacrifice path, which captures the same snapshots at cost-payment time.
     */
    val updatedSacrificedPermanents: List<EntitySnapshot> = emptyList(),
    /**
     * True when this effect already ran trigger detection + processing on its own emitted events
     * (e.g. a nested cast via [com.wingedsheep.sdk.scripting.effects.CastFromCollectionWithoutPayingCostEffect],
     * which routes through `CastSpellHandler` and stacks cast-triggers itself). Callers that re-scan
     * a resumed continuation's events for triggers must honor this flag and skip those events, or a
     * "whenever you cast a spell" trigger fires twice (Vaan, Street Thief casting an opponent's card).
     * Mirrors [ExecutionResult.triggersAlreadyProcessed].
     */
    val triggersAlreadyProcessed: Boolean = false
) {
    val isSuccess: Boolean get() = error == null && pendingDecision == null
    val isPaused: Boolean get() = pendingDecision != null
    val newState: GameState get() = state

    fun toExecutionResult() =
        ExecutionResult(state, events, error, pendingDecision, triggersAlreadyProcessed = triggersAlreadyProcessed)

    companion object {
        /** Wrap an [ExecutionResult] from a non-effect subsystem (e.g., StackResolver). */
        fun from(result: ExecutionResult) = EffectResult(
            result.state, result.events, result.error, result.pendingDecision,
            triggersAlreadyProcessed = result.triggersAlreadyProcessed
        )

        fun success(state: GameState): EffectResult =
            EffectResult(state)

        fun success(state: GameState, events: List<GameEvent>): EffectResult =
            EffectResult(state, events)

        fun error(state: GameState, message: String): EffectResult =
            EffectResult(state, error = message)

        /** Propagate an existing suspension without allocating or installing another question. */
        fun propagatePause(state: GameState, events: List<GameEvent> = emptyList()): EffectResult =
            from(ExecutionResult.propagatePause(state, events))
    }
}
