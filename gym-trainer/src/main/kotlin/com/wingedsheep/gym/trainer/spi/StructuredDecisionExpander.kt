package com.wingedsheep.gym.trainer.spi

import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.state.GameState

/**
 * A deterministic, bounded set of responses that search may branch over for a pending decision.
 *
 * [isExhaustive] is true only when [responses] contains every valid response. When it is false,
 * [estimatedResponseCount] is either a deterministic estimate or null when the full response
 * space is not cheaply countable.
 */
data class StructuredExpansion(
    val responses: List<DecisionResponse>,
    val isExhaustive: Boolean,
    val estimatedResponseCount: Long? = null,
)

/** Expands a typed engine decision into distinct, validator-approved search branches. */
fun interface StructuredDecisionExpander {
    fun expand(state: GameState, decision: PendingDecision): StructuredExpansion
}

/**
 * Compatibility adapter for integrations that still provide a single forced response.
 * New search integrations should implement [StructuredDecisionExpander] directly.
 */
fun StructuredDecisionResolver.asExpander(): StructuredDecisionExpander = StructuredDecisionExpander { state, decision ->
    StructuredExpansion(
        responses = listOf(resolve(state, decision)),
        isExhaustive = false,
        estimatedResponseCount = null,
    )
}
